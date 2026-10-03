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
)

/** A complete match: world + bot brains. Advance it with [step]. */
class Match(val config: MatchConfig) {
    private val rng = Random(config.seed)
    val world: World
    val player: Fighter
    val brains: List<BotBrain>
    private val pathfinder: Pathfinder
    val freeForAll = config.mode == GameMode.LAST_SPARK
    /** Free Roam: nothing at stake, and it never ends on its own. */
    val practice = config.mode == GameMode.FREE_ROAM

    /** Seconds since the match became "over" for the player (ended, or eliminated in free-for-all). */
    var overFor = 0f
        private set
    private var turn = 0

    init {
        val names = BOT_NAMES.shuffled(rng).iterator()
        val roster = ArrayList<Fighter>()
        var id = 0
        player = Fighter(id++, Balance.fighter(config.playerFighter), config.playerLevel, config.playerSkin, 0, config.playerName, isBot = !config.humanPlayer)
        roster += player
        // Bots use the same level as the player — difficulty comes from behaviour, never from stats.
        if (freeForAll) {
            repeat(config.mode.players - 1) { roster += botFighter(id, team = id, names.next()); id++ }
        } else if (practice) {
            // Four dummies to shoot at and one giant to fight. Order matters: it matches the arena's spawn list.
            repeat(4) { roster += Fighter(id++, Balance.dummy, config.playerLevel, 0, 1, "Dummy ${it + 1}", isBot = true, dummy = true) }
            roster += Fighter(id++, Balance.boss, config.playerLevel, 2, 1, Balance.boss.name, isBot = true)
        } else {
            repeat(2) { roster += botFighter(id++, 0, names.next()) }
            repeat(3) { roster += botFighter(id++, 1, names.next()) }
        }
        val arena = if (freeForAll) Arenas.staticCanyon() else if (practice) Arenas.provingGround() else Arenas.foundryYard()
        val rules = if (freeForAll) MatchRules.lastSpark() else if (practice) MatchRules.freeRoam() else MatchRules.knockoutRush()
        world = World(arena, roster, rules, Random(rng.nextLong()))
        pathfinder = Pathfinder(world.arena)
        val profile = BotProfile.of(config.difficulty)
        brains = roster.filter { it.isBot && !it.dummy }.map { BotBrain(it, profile, world, pathfinder, Random(rng.nextLong())) }
        for (b in brains) b.others = brains
    }

    private fun botFighter(id: Int, team: Int, name: String): Fighter {
        val def = Balance.fighters[rng.nextInt(Balance.fighters.size)]
        val skin = rng.nextInt(def.skins.size)
        return Fighter(id, def, config.playerLevel, skin, team, name, isBot = true)
    }

    fun step(dt: Float) {
        pathfinder.budget = 1
        // Rotate who goes first so the same bot doesn't always get the tick's one path search.
        turn++
        for (i in brains.indices) brains[(i + turn) % brains.size].update(dt)
        world.step(dt)
        if (isOver) overFor += dt
        // Nobody steers a dummy, so make sure it never keeps an old input.
        for (f in world.fighters) if (f.dummy) f.control.clear()
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

    companion object {
        const val STEP = 1f / 60f

        val BOT_NAMES = listOf(
            "Rivet", "Cobalt", "Fennick", "Quill", "Tamsin", "Brisk", "Moss", "Pixel", "Juniper",
            "Sprocket", "Vesper", "Nimbus", "Pepper", "Ziggy", "Onyx", "Marlow", "Kestrel", "Fizz",
        )
    }
}
