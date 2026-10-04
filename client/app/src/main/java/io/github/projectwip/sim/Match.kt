package io.github.projectwip.sim

import io.github.projectwip.ai.BotBrain
import io.github.projectwip.ai.BotProfile
import io.github.projectwip.ai.Pathfinder
import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.MatchOutcome
import io.github.projectwip.data.MatchReport
import kotlin.random.Random

data class MatchConfig(
    val playerFighter: FighterId,
    val playerLevel: Int,
    val playerSkin: Int,
    val playerName: String,
    val difficulty: BotDifficulty,
    val mode: GameMode = GameMode.LAST_SPARK,
    /** When false, the player's slot is also a bot (used by tests and attract mode). */
    val humanPlayer: Boolean = true,
    val seed: Long = System.nanoTime(),
    /** Boss Mode: which boss it is. Null picks one at random. */
    val boss: io.github.projectwip.data.BossKind? = null,
    /** Names for the bots, when the game server set this match up. Any shortfall is filled from the built-in list. */
    val botNames: List<String> = emptyList(),
    /** The server's id for this match (0 = set up on the device). */
    val serverMatchId: Long = 0,
)

/** A complete match: world + bot brains. Advance it with [step]. */
class Match(val config: MatchConfig) {
    private val rng = Random(config.seed)
    val world: World
    val player: Fighter
    val brains: List<BotBrain>
    private val pathfinder: Pathfinder
    val freeForAll = config.mode == GameMode.LAST_SPARK
    val bossMode = config.mode == GameMode.BOSS
    /** Training Area: nothing at stake, and it never ends on its own. */
    val practice = config.mode == GameMode.TRAINING

    /** Seconds since the match became "over" for the player (ended, or eliminated in free-for-all). */
    var overFor = 0f
        private set
    private var turn = 0

    init {
        val names = (config.botNames + BOT_NAMES.shuffled(rng)).iterator()
        val passive = HashSet<Fighter>()
        val roster = ArrayList<Fighter>()
        var id = 0
        player = Fighter(id++, Balance.fighter(config.playerFighter), config.playerLevel, config.playerSkin, 0, config.playerName, isBot = !config.humanPlayer)
        roster += player
        // Bots use the same level as the player — difficulty comes from behaviour, never from stats.
        if (freeForAll) {
            repeat(config.mode.players - 1) { roster += botFighter(id, team = id, names.next()); id++ }
        } else if (practice) {
            // Everything stands where it is put, at fixed strength. Order matches the arena's spawn list:
            // dummies, boss, sentry, swarm. Only the sentry gets a brain: the rest are targets that never
            // fight back, so you can walk right up to the boss and the swarm and practise on them.
            val a = Arenas.trainingArea()
            val dummies = a.spawns[1].size - 2 - TRAINING_MINIS
            repeat(dummies) { roster += Fighter(id++, Balance.dummy, 1, 0, 1, "Dummy ${it + 1}", isBot = true, rooted = true).also { d -> passive += d } }
            val giant = Balance.boss(config.boss ?: Balance.bosses[rng.nextInt(Balance.bosses.size)].boss!!)
            roster += Fighter(id++, giant, 1, giant.skins.lastIndex, 1, giant.name, isBot = true, rooted = true).also { passive += it }
            roster += Fighter(id++, Balance.sentry, 1, 1, 1, "Sentry", isBot = true, rooted = true)
            repeat(TRAINING_MINIS) { roster += Fighter(id++, Balance.mini, 1, 2, 1, "Mini ${it + 1}", isBot = true, rooted = true).also { m -> passive += m } }
        } else if (bossMode) {
            // One boss, always level 1: its stats are fixed and never follow the player's level.
            val def = Balance.boss(config.boss ?: Balance.bosses[rng.nextInt(Balance.bosses.size)].boss!!)
            roster += Fighter(id++, def, 1, def.skins.lastIndex, 1, def.name, isBot = true)
        } else {
            repeat(2) { roster += botFighter(id++, 0, names.next()) }
            repeat(3) { roster += botFighter(id++, 1, names.next()) }
        }
        val arena = if (freeForAll) Arenas.staticCanyon() else if (practice) Arenas.trainingArea() else if (bossMode) Arenas.provingGround() else Arenas.foundryYard()
        val rules = if (freeForAll) MatchRules.lastSpark() else if (practice) MatchRules.training() else if (bossMode) MatchRules.bossMode() else MatchRules.knockoutRush()
        world = World(arena, roster, rules, Random(rng.nextLong()))
        pathfinder = Pathfinder(world.arena)
        val profile = BotProfile.of(config.difficulty)
        brains = roster.filter { it.isBot && it !in passive }.map { BotBrain(it, profile, world, pathfinder, Random(rng.nextLong())) }
        for (b in brains) b.others = brains
    }

    private fun botFighter(id: Int, team: Int, name: String): Fighter {
        val def = Balance.fighters[rng.nextInt(Balance.fighters.size)]
        val skin = rng.nextInt(def.skins.size)
        return Fighter(id, def, config.playerLevel, skin, team, name, isBot = true)
    }

    /** What the player's control held on every tick so far: the record the server replays to judge the match. */
    val inputs = InputLog()

    fun step(dt: Float) {
        if (config.humanPlayer) inputs.record(player.control)
        pathfinder.budget = 1
        // Rotate who goes first so the same bot doesn't always get the tick's one path search.
        turn++
        for (i in brains.indices) brains[(i + turn) % brains.size].update(dt)
        // A boss's brain only walks it about: what it does to the player is its script's business (see World).
        for (f in world.fighters) if (f.def.boss != null) { f.control.attack = false; f.control.superAttack = false }
        world.step(dt)
        if (isOver) overFor += dt
    }

    /** True once the result is decided for the player. */
    val isOver get() = world.phase == Phase.ENDED || (freeForAll && player.eliminated)

    /** Player's finishing place in free-for-all (1 if still standing). */
    val placement get() = if (!freeForAll) 0 else if (player.placement > 0) player.placement else 1

    fun report(): MatchReport {
        val outcome = if (freeForAll) {
            if (placement == 1) MatchOutcome.VICTORY else MatchOutcome.DEFEAT
        } else when (world.winningTeam) {
            player.team -> MatchOutcome.VICTORY
            -1 -> MatchOutcome.DRAW
            else -> MatchOutcome.DEFEAT
        }
        return MatchReport(
            outcome = outcome,
            mode = config.mode,
            placement = placement,
            players = world.fighters.size,
            fighter = config.playerFighter,
            kos = player.kos,
            deaths = player.deaths,
            damageDealt = player.damageDealt,
            mvp = world.mvp() === player,
            difficulty = config.difficulty,
            blueScore = world.score[0],
            redScore = world.score[1],
        )
    }

    /**
     * The report for a player who walks out: always a defeat, and in free-for-all they place behind everyone
     * still standing (not first, which is what "not knocked out yet" would otherwise read as).
     */
    fun forfeit(): MatchReport {
        val r = report()
        val standing = world.fighters.count { !it.eliminated }
        return r.copy(outcome = MatchOutcome.DEFEAT, placement = if (freeForAll && !player.eliminated) standing else r.placement, mvp = false)
    }

    companion object {
        const val STEP = 1f / 60f
        /** How many minis make up the Training Area's swarm. */
        const val TRAINING_MINIS = 12

        val BOT_NAMES = listOf(
            "Rivet", "Cobalt", "Fennick", "Quill", "Tamsin", "Brisk", "Moss", "Pixel", "Juniper",
            "Sprocket", "Vesper", "Nimbus", "Pepper", "Ziggy", "Onyx", "Marlow", "Kestrel", "Fizz",
        )
    }
}
