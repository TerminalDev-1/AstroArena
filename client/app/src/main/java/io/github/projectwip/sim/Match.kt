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
import io.github.projectwip.data.SuperKind
import kotlin.random.Random

data class MatchConfig(
    val playerFighter: FighterId,
    val playerLevel: Int,
    val playerSkin: Int,
    val playerName: String,
    val difficulty: BotDifficulty,
    val mode: GameMode = GameMode.BOSS,
    /** When false, the player's slot is also a bot (used by tests and attract mode). */
    val humanPlayer: Boolean = true,
    val seed: Long = System.nanoTime(),
    /** Names for the bots, when the game server set this match up. Any shortfall is filled from the built-in list. */
    val botNames: List<String> = emptyList(),
    /** The server's id for this match (0 = set up on the device). */
    val serverMatchId: Long = 0,
    /** A 1v1 against a real player: who they are and which side this device plays. Null: a 1v1 is against a bot. */
    val duel: DuelSetup? = null,
    /** The Entity's fight with a team of real players: who they are, and which of them this device plays. */
    val team: TeamSetup? = null,
    /** Debug preview: the Entity's uprising begins this many seconds into play (below 0: when the seed says). */
    val uprisingAt: Float = -1f,
)

/** One real player in a team. */
data class TeamPlayer(val fighter: FighterId, val level: Int, val skin: Int, val name: String)

/**
 * A team of real players, each on their own device, sharing one match. Every device builds the same fighters in
 * the same order ([players] first, then the bots), so that their simulations match; [slot] is which of the
 * players this device's is.
 */
data class TeamSetup(val slot: Int, val players: List<TeamPlayer>)

/**
 * The other player in a 1v1. Both devices build the same two fighters in the same order (side 0 first), so that
 * their simulations match; [side] is which of the two this device's player is.
 */
data class DuelSetup(val side: Int, val fighter: FighterId, val level: Int, val skin: Int, val name: String)

/** A complete match: world + bot brains. Advance it with [step]. */
class Match(val config: MatchConfig) {
    private val rng = Random(config.seed)
    val world: World
    val player: Fighter
    val brains: List<BotBrain>
    private val pathfinder: Pathfinder
    private val botLevel: Int
    /** The Entity's fight (Boss Mode in the code): the player's team against the Entity, its ritual first. */
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
        val duel = config.duel.takeIf { config.mode == GameMode.DUEL }
        val team = config.team.takeIf { config.mode == GameMode.BOSS }
        // Bots are as strong as the player; in a team, as its first player, so that every device agrees.
        botLevel = team?.players?.first()?.level ?: config.playerLevel
        if (team != null) {
            for (p in team.players) roster += Fighter(id++, Balance.fighter(p.fighter), p.level, p.skin, 0, p.name, isBot = false)
            player = roster[team.slot]
        } else if (duel != null) {
            // Two real players. Fighter 0 is side 0 on both devices, whoever's device this is.
            val mine = Fighter(duel.side, Balance.fighter(config.playerFighter), config.playerLevel, config.playerSkin, duel.side, config.playerName, isBot = false)
            val theirs = Fighter(1 - duel.side, Balance.fighter(duel.fighter), duel.level, duel.skin, 1 - duel.side, duel.name, isBot = false)
            player = mine
            roster += if (duel.side == 0) listOf(mine, theirs) else listOf(theirs, mine)
        } else {
            player = Fighter(id++, Balance.fighter(config.playerFighter), config.playerLevel, config.playerSkin, 0, config.playerName, isBot = !config.humanPlayer)
            roster += player
        }
        // Bots use the same level as the player — difficulty comes from behaviour, never from stats.
        if (duel != null) {
            // Nobody else is in a 1v1.
        } else if (config.mode == GameMode.DUEL) {
            // No real opponent (offline): a bot stands in, for practice.
            roster += botFighter(id++, 1, names.next())
        } else if (practice) {
            // Everything stands where it is put, at fixed strength. Order matches the arena's spawn list:
            // dummies, giant, sentry, swarm. Only the sentry gets a brain: the rest are targets that never
            // fight back, so you can walk right up to the giant and the swarm and practise on them.
            val a = Arenas.trainingArea()
            val dummies = a.spawns[1].size - 2 - TRAINING_MINIS
            repeat(dummies) { roster += Fighter(id++, Balance.dummy, 1, 0, 1, "Dummy ${it + 1}", isBot = true, rooted = true).also { d -> passive += d } }
            roster += Fighter(id++, Balance.giant, 1, 0, 1, Balance.giant.name, isBot = true, rooted = true).also { passive += it }
            roster += Fighter(id++, Balance.sentry, 1, 1, 1, "Sentry", isBot = true, rooted = true)
            repeat(TRAINING_MINIS) { roster += Fighter(id++, Balance.mini, 1, 2, 1, "Mini ${it + 1}", isBot = true, rooted = true).also { m -> passive += m } }
        } else {
            // The Entity's fight: a team of three against it, bots filling whatever places real players don't. (The
            // Entity itself is not a fighter: it is the uprising's, see `World.stepSky`.)
            repeat(3 - (team?.players?.size ?: 1)) { roster += botFighter(id++, 0, names.next()) }
        }
        val oneOnOne = config.mode == GameMode.DUEL
        val arena = if (practice) Arenas.trainingArea() else Arenas.provingGround()
        val rules = if (practice) MatchRules.training() else if (oneOnOne) MatchRules.duel() else MatchRules.bossMode()
        world = World(arena, roster, rules, Random(rng.nextLong()))
        pathfinder = Pathfinder(world.arena)
        val profile = BotProfile.of(config.difficulty)
        brains = roster.filter { it.isBot && it !in passive }.map { BotBrain(it, profile, world, pathfinder, Random(rng.nextLong())) }
        for (b in brains) b.others = brains
        // In the Training Area the super is there to be tried straight away.
        if (practice) player.superCharge = 1f
    }

    private fun botFighter(id: Int, team: Int, name: String): Fighter {
        val def = Balance.fighters[rng.nextInt(Balance.fighters.size)]
        val skin = rng.nextInt(def.skins.size)
        return Fighter(id, def, botLevel, skin, team, name, isBot = true)
    }

    /** The real players' fighters, in the order every device has them (just the player, outside a team or a 1v1). */
    val humans: List<Fighter> get() = world.fighters.filter { !it.isBot }.ifEmpty { listOf(player) }

    /** In a 1v1, the other fighter. */
    val opponent: Fighter? get() = if (config.mode == GameMode.DUEL) world.fighters.firstOrNull { it !== player } else null

    /**
     * The Entity's own brains, one for each real player it takes over (made the first time it does, the same moment on
     * every device and in the referee): a bot's mind at its sharpest, so a takeover is worth watching. Bots it takes
     * over keep their own brains. Built-in numbers only, never the server's overrides, so every replay agrees.
     */
    private val entityBrains = HashMap<Int, BotBrain>()
    private val entityProfile = BotProfile.builtIn(BotDifficulty.ELITE)
    /** The way the Entity's tornado is heading as it passes through someone (see [autoFight]). */
    private val entityHeading = HashMap<Int, FloatArray>()

    init {
        // The Entity's fight is its uprising, from the start: its ritual, then its world, and the match is decided
        // there. In a 1v1 it comes in the middle, at a moment the seed picks (the user's rule: it happens, it isn't a
        // roll); never in the Training Area.
        if (bossMode) world.uprising = Uprising(if (config.uprisingAt >= 0f) config.uprisingAt else Balance.UPRISING_BOSS_START, boss = true)
        else if (config.uprisingAt >= 0f) world.uprising = Uprising(config.uprisingAt)
        else if (config.mode != GameMode.TRAINING)
            world.uprising = Uprising(Balance.UPRISING_EARLIEST + Random(config.seed xor 0x0B5E55EDL).nextFloat() * Balance.UPRISING_SPREAD)
    }

    /** What the player's control held on every tick so far: the record the server replays to judge the match. */
    val inputs = InputLog()

    fun step(dt: Float) {
        if (config.humanPlayer) inputs.record(player.control)
        pathfinder.budget = 1
        // Rotate who goes first so the same bot doesn't always get the tick's one path search.
        turn++
        // (In the Entity's dimension the bots are driven below: their brains know only the arena.)
        if (!world.inSky) for (i in brains.indices) brains[(i + turn) % brains.size].update(dt)
        // Bots hold their fire for the first seconds of a match and while their spawn shield is up, so nobody is
        // beaten up the moment they arrive (the user found the bots got stuck in too early). They still move.
        val warmingUp = world.phase == Phase.PLAYING && world.phaseTime < Balance.BOT_WARMUP_SECONDS
        for (f in world.fighters) if (f.isBot && (warmingUp || f.shield > 0f)) {
            f.control.attack = false; f.control.superAttack = false; f.control.hyper = false
        }
        // In the Entity's dimension (its uprising) bots fight it and nobody else: they keep to their attack's reach of the
        // shadow under it, circling, strike at it, spend their powers on it and step out of its strikes. (While it holds
        // everyone, World moves them itself.)
        val up = world.uprising
        if (up != null && up.phase == UprisingPhase.SKY) for (f in world.fighters) {
            // (Whoever it has corrupted fights for it, human or bot.)
            if (f.alive && f.corruptLeft > 0f) { driveCorrupted(f); continue }
            if (!f.isBot || !f.alive) continue
            val c = f.control
            val dx = up.ex - f.x; val dy = up.ey - f.y
            val d = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
            val big = Balance.UPRISING_ENTITY_RADIUS
            val reach = f.def.attack.range + big
            // (Far enough in that the attack lands, never closer than it needs; a storm drives straight through it.)
            val want = if (f.inTempest) 0f else maxOf(big * 0.5f, reach * 0.7f)
            val ux = dx / d; val uy = dy / d
            // (Each keeps circling one way, and holds its distance within a band rather than on a line, easing from one
            // way of moving to the next: steering hard at an exact distance had them jerking back and forth.)
            val circle = if (f.id % 2 == 0) 0.5f else -0.5f
            val off = d - want
            val inward = if (kotlin.math.abs(off) < 0.9f) 0f else ((off - kotlin.math.sign(off) * 0.9f) / 2f).coerceIn(-1f, 1f)
            val wantX = ux * inward - uy * circle * (1f - kotlin.math.abs(inward))
            val wantY = uy * inward + ux * circle * (1f - kotlin.math.abs(inward))
            c.moveX += (wantX - c.moveX) * 0.12f
            c.moveY += (wantY - c.moveY) * 0.12f
            c.aimX = dx; c.aimY = dy; c.aiming = true
            c.attack = d < reach - 0.3f
            val sRange = when (f.def.superSpec.kind) {
                SuperKind.TEMPEST -> 6f
                SuperKind.RAM -> f.def.superSpec.range + big
                else -> f.def.superSpec.range + big * 0.8f
            }
            c.superAttack = f.superReady && d < sRange
            if (c.superAttack && f.def.superSpec.kind == SuperKind.BREW) { val r = minOf(d, f.def.superSpec.range); c.aimX = ux * r; c.aimY = uy * r }
            c.hyper = f.hyperReady
            dodgeHazards(f)
        }
        // A glitching fighter fights on its own: the Entity is in control (the user's lore).
        for (f in world.fighters) if (f.glitchCooldown > 0f) f.glitchCooldown = maxOf(0f, f.glitchCooldown - dt)
        for (f in world.fighters) if (f.glitchLeft > 0f) {
            f.glitchLeft = maxOf(0f, f.glitchLeft - dt)
            if (!f.alive || world.inSky) continue
            if (f.inTempest) { autoFight(f); dodgeHazards(f) }
            else if (!f.isBot) {
                val c = f.control
                c.attack = false; c.superAttack = false; c.hyper = false
                entityBrains.getOrPut(f.id) { BotBrain(f, entityProfile, world, pathfinder, Random(config.seed xor (0x3E7171L + f.id))) }.update(dt)
                // The Entity spends its powers (the user's rule): a ready super goes at the nearest enemy in reach,
                // and a ready hypercharge goes on, not saved for later.
                if (f.superReady || f.hyperReady) {
                    var near: Fighter? = null
                    var nd = Float.MAX_VALUE
                    for (o in world.fighters) {
                        if (o === f || !o.alive || o.team == f.team || !world.isVisibleTo(o, f.team)) continue
                        val d = kotlin.math.hypot(o.x - f.x, o.y - f.y)
                        if (d < nd) { nd = d; near = o }
                    }
                    if (near != null && nd <= maxOf(f.def.attack.range, f.def.superSpec.range)) {
                        if (f.hyperReady) c.hyper = true
                        if (f.superReady && !c.superAttack) { c.aimX = near.x - f.x; c.aimY = near.y - f.y; c.aiming = true; c.superAttack = true }
                    }
                }
                dodgeHazards(f)
            }
        }
        val seen = world.events.size
        world.step(dt)
        // Using its power draws the Entity in (the user's lore): its pull fills with every attack, super and
        // hypercharge, and with time once a fighter has started fighting; full, the fighter glitches. It is sure, not a
        // roll, and it happens as many times as the pull fills: never just once in a match.
        if (world.uprising?.active != true) {
            for (i in seen until world.events.size) {
                val e = world.events[i]
                val (id, pull) = when (e) {
                    is GameEvent.Shot -> e.fighterId to (if (e.isSuper) Balance.GLITCH_PULL_SUPER else Balance.GLITCH_PULL_ATTACK)
                    is GameEvent.Hyper -> e.fighterId to Balance.GLITCH_PULL_HYPER
                    else -> continue
                }
                val f = world.fighter(id) ?: continue
                if (f.glitchLeft > 0f || f.glitchCooldown > 0f) continue
                f.entityPull += pull
            }
            for (f in world.fighters) {
                if (!f.alive || f.glitchLeft > 0f || f.glitchCooldown > 0f) continue
                // (Time only adds once a fighter has fought: the Training Area's dummies, who never attack, stay put.)
                if (f.entityPull > 0f) f.entityPull += Balance.GLITCH_PULL_PER_SECOND * dt
                if (f.entityPull >= 1f) {
                    f.entityPull = 0f
                    // (In a tornado the Entity keeps it to the end: four seconds of it was too little.)
                    f.glitchLeft = maxOf(Balance.GLITCH_SECONDS, f.tempestLeft)
                    f.glitchCooldown = f.glitchLeft + Balance.GLITCH_COOLDOWN
                    world.events += GameEvent.Glitched(f.id)
                }
            }
        }
        if (isOver) overFor += dt
    }

    /**
     * The Entity sees what's coming (the user asked for it in Boss Mode): a marked patch of ground about to go off (a
     * boss's rockets, mines and slams, a brew) is never stood in or walked into. Standing in one, it takes the way out
     * that's clear soonest, leaning toward where it wanted to go; about to step into one, it goes round. Walking, walls
     * count; as a tornado it flies over them. Runs after its brain or its tornado driving, and only changes the move.
     */
    private fun dodgeHazards(f: Fighter) {
        val flares = world.uprising?.flares
        if (world.hazards.isEmpty() && flares.isNullOrEmpty()) return
        val c = f.control
        val margin = f.radius + HAZARD_MARGIN
        fun danger(x: Float, y: Float): Boolean {
            for (h in world.hazards) if (h.team != f.team && h.age < h.delay && kotlin.math.hypot(h.x - x, h.y - y) < h.radius + margin) return true
            // The Entity's flares too, once one has stopped following and a moment has passed to see it (a bot is not
            // instant: some still get caught).
            if (flares != null) for (fl in flares) if (fl.age > Balance.FLARE_LOCK + FLARE_REACT && kotlin.math.hypot(fl.x - x, fl.y - y) < Balance.FLARE_RADIUS + margin) return true
            return false
        }
        val wantX = c.moveX; val wantY = c.moveY
        val inside = danger(f.x, f.y)
        if (!inside && (wantX == 0f && wantY == 0f || !danger(f.x + wantX * HAZARD_LOOKAHEAD, f.y + wantY * HAZARD_LOOKAHEAD))) return
        var best = -Float.MAX_VALUE; var bx = 0f; var by = 0f
        for (k in 0 until 24) {
            val a = k * (6.2831855f / 24)
            val dx = kotlin.math.cos(a); val dy = kotlin.math.sin(a)
            var clearAt = -1
            for (step in 1..12) {
                val d = step * 0.35f
                val x = f.x + dx * d; val y = f.y + dy * d
                if (!f.inTempest && world.arena.circleBlocked(x, y, f.radius)) break
                if (!danger(x, y)) { clearAt = step; break }
            }
            if (clearAt < 0) continue
            val score = -clearAt + 0.6f * (dx * wantX + dy * wantY)
            if (score > best) { best = score; bx = dx; by = dy }
        }
        if (best > -Float.MAX_VALUE) { c.moveX = bx; c.moveY = by }
        else if (!inside) { c.moveX = 0f; c.moveY = 0f }
    }

    /**
     * What a glitching fighter does: goes for the nearest enemy it can see, closes to its attack's reach, aims at them
     * and attacks as fast as it can, and fires its super when it has one. Only reads what every device and the referee
     * share, so a replay goes the same way.
     */
    private fun autoFight(f: Fighter) {
        val c = f.control
        var best: Fighter? = null
        var bestD = Float.MAX_VALUE
        for (o in world.fighters) {
            if (o === f || !o.alive || o.team == f.team || !world.isVisibleTo(o, f.team)) continue
            val d = kotlin.math.hypot(o.x - f.x, o.y - f.y)
            if (d < bestD) { bestD = d; best = o }
        }
        c.attack = false; c.superAttack = false; c.hyper = false
        val t = best
        if (t == null) { c.moveX = 0f; c.moveY = 0f; c.aiming = false; return }
        val dx = t.x - f.x; val dy = t.y - f.y
        val d = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
        if (f.inTempest) {
            // The Entity drives the tornado flat out, straight through whoever it is after and on past them. Close in,
            // it holds the heading it came in on (turning to face them there flipped it every tick: the tornado
            // jerked about violently on top of them), and turns back once it is clear.
            val close = f.radius + t.radius + TEMPEST_PASS
            val h = entityHeading.getOrPut(f.id) { floatArrayOf(dx / d, dy / d) }
            if (d > close) { h[0] = dx / d; h[1] = dy / d }
            c.moveX = h[0]; c.moveY = h[1]
            c.aimX = dx; c.aimY = dy; c.aiming = true
            return
        }
        val reach = f.def.attack.range * 0.85f
        if (d > reach) { c.moveX = dx / d; c.moveY = dy / d } else { c.moveX = -dy / d * 0.8f; c.moveY = dx / d * 0.8f }
        c.aimX = dx; c.aimY = dy; c.aiming = true
        c.attack = d <= f.def.attack.range
        if (f.superCharge >= 1f && d <= f.def.attack.range) c.superAttack = true
    }

    /**
     * A fighter the Entity has corrupted (one of its flares): it goes after the nearest of its own side and fights them,
     * spending its powers as soon as it has them. Simple steering, no paths: it is in the Entity's open world.
     */
    private fun driveCorrupted(f: Fighter) {
        val c = f.control
        var t: Fighter? = null
        var best = Float.MAX_VALUE
        for (o in world.fighters) {
            if (o === f || !o.alive || o.team == f.team) continue
            val d = kotlin.math.hypot(o.x - f.x, o.y - f.y)
            if (d < best) { best = d; t = o }
        }
        c.attack = false; c.superAttack = false; c.hyper = false; c.detonate = false; c.skip = false
        if (t == null) { c.moveX = 0f; c.moveY = 0f; c.aiming = false; return }
        val dx = t.x - f.x; val dy = t.y - f.y
        val d = best.coerceAtLeast(1e-3f)
        val reach = f.def.attack.range * 0.75f
        if (d > reach) { c.moveX = dx / d; c.moveY = dy / d } else { c.moveX = -dy / d * 0.6f; c.moveY = dx / d * 0.6f }
        c.aimX = dx; c.aimY = dy; c.aiming = true
        c.attack = d <= f.def.attack.range
        c.superAttack = f.superReady && d <= maxOf(f.def.attack.range, f.def.superSpec.range)
        c.hyper = f.hyperReady
        dodgeHazards(f)
    }

    /** True once the result is decided for the player. */
    val isOver get() = world.phase == Phase.ENDED

    /** (No mode is played for a place any more; kept in the report for the server's API.) */
    val placement get() = 0

    fun report(): MatchReport {
        val outcome = when (world.winningTeam) {
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

    /** The report for a player who walks out: always a defeat. */
    fun forfeit(): MatchReport = report().copy(outcome = MatchOutcome.DEFEAT, mvp = false)

    companion object {
        /** How far past someone's edge the Entity's tornado keeps its heading through them. */
        const val TEMPEST_PASS = 1.2f
        /** How far clear of a marked patch's edge the Entity keeps, and how far ahead it looks for one. */
        const val HAZARD_MARGIN = 0.35f
        const val HAZARD_LOOKAHEAD = 0.9f
        const val STEP = 1f / 60f
        /** How many minis make up the Training Area's swarm. */
        const val TRAINING_MINIS = 12

        val BOT_NAMES = listOf(
            "Rivet", "Cobalt", "Fennick", "Quill", "Tamsin", "Brisk", "Moss", "Pixel", "Juniper",
            "Sprocket", "Vesper", "Nimbus", "Pepper", "Ziggy", "Onyx", "Marlow", "Kestrel", "Fizz",
        )
    }
}

/** How long a bot takes to see that an Entity flare has stopped following and step out from under it. */
private const val FLARE_REACT = 0.6f
