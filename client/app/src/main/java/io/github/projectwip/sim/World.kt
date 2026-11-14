package io.github.projectwip.sim

import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.Balance
import io.github.projectwip.data.SuperKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

enum class Phase { COUNTDOWN, PLAYING, ENDED }

/**
 * Match rules. Two sides with respawns: first to [koTarget] KOs, or most when time runs out (a 1v1); the Training Area,
 * which never ends; and the Entity's fight, which is decided in its world (see `Uprising`).
 */
data class MatchRules(
    val koTarget: Int = Balance.KO_TARGET,
    val durationSeconds: Float = Balance.MATCH_SECONDS,
    val countdownSeconds: Float = 3f,
    /** KOs the second team (index 1) needs to win, when that differs from [koTarget]. */
    val enemyKoTarget: Int = koTarget,
    /** The Entity's fight (Boss Mode): no clock and no KOs to reach; the Entity decides it. */
    val boss: Boolean = false,
    /** Training Area: no clock and no score to reach, so it only ends when the player leaves. */
    val practice: Boolean = false,
) {
    companion object {
        /** The Entity's fight: nothing but the Entity ends it (its uprising ends the match, won or lost). */
        fun bossMode() = MatchRules(koTarget = Int.MAX_VALUE, enemyKoTarget = Int.MAX_VALUE, durationSeconds = Float.MAX_VALUE, boss = true)
        /** One against one: first to three knockouts, or whoever is ahead after two minutes. */
        fun duel() = MatchRules(koTarget = 3, durationSeconds = 120f)
        fun training() = MatchRules(koTarget = Int.MAX_VALUE, enemyKoTarget = Int.MAX_VALUE, durationSeconds = Float.MAX_VALUE, practice = true)
    }
}

/**
 * The authoritative simulation. Advanced in fixed steps by [step]; has no Android dependencies so
 * it can run in unit tests, headless bot-vs-bot balance runs, or a future server.
 */
class World(
    /** (The Entity's dimension swaps it for its own platform while everyone is there: see `beginSky`.) */
    arena: Arena,
    val fighters: List<Fighter>,
    val rules: MatchRules = MatchRules(),
    private val random: Random = Random.Default,
) {
    var arena: Arena = arena
        private set
    val projectiles = ArrayList<Projectile>(64)
    val pickups = ArrayList<Pickup>()
    /** Marked patches of ground waiting to go off (the Entity's strikes, brews). */
    val hazards = ArrayList<Hazard>()
    /** The Entity's uprising in this match (see `Uprising`), or null where it doesn't come. */
    var uprising: Uprising? = null
    /** Rockets stuck in the ground (or on their way there), waiting for whoever planted them to set them off. */
    val planted = ArrayList<Planted>()
    /** Stars in the air (`AttackShape.STAR`), each on its way to burst where it was thrown. */
    val lobs = ArrayList<Lob>()
    /** Remaining health of each crate, keyed by tile index (y * width + x). */
    val crateHp = HashMap<Int, Int>()
    val events = ArrayList<GameEvent>(32)
    val score = IntArray(2)

    var phase = Phase.COUNTDOWN
        private set
    var phaseTime = 0f
        private set
    var timeLeft = rules.durationSeconds
        private set
    /** 0 = blue, 1 = red, -1 = draw. Valid once [phase] is ENDED. */
    var winningTeam = -1
        private set
    var time = 0f
        private set

    private val tmp = FloatArray(2)
    private var lastCountdownWhole = -1


    init {
        val teams = (fighters.maxOfOrNull { it.team } ?: 0) + 1
        for (f in fighters) f.spottedBy = FloatArray(teams)
        for (y in 0 until arena.height) for (x in 0 until arena.width) {
            if (arena[x, y] == Tile.CRATE) crateHp[y * arena.width + x] = Balance.CRATE_HP
        }
        for (team in fighters.map { it.team }.distinct()) {
            fighters.filter { it.team == team }.forEachIndexed { i, f ->
                f.spawnIndex = i
                placeAtSpawn(f)
            }
        }
    }

    fun fighter(id: Int): Fighter? = fighters.firstOrNull { it.id == id }

    fun step(dt: Float) {
        time += dt
        phaseTime += dt
        when (phase) {
            Phase.COUNTDOWN -> {
                val whole = (rules.countdownSeconds - phaseTime).toInt()
                if (whole != lastCountdownWhole && whole >= 0) {
                    lastCountdownWhole = whole
                    events += GameEvent.CountdownTick
                }
                for (f in fighters) { f.prevX = f.x; f.prevY = f.y; f.control.clear() }
                if (phaseTime >= rules.countdownSeconds) {
                    phase = Phase.PLAYING
                    phaseTime = 0f
                    events += GameEvent.MatchStart
                }
                return
            }
            Phase.ENDED -> {
                for (f in fighters) { f.prevX = f.x; f.prevY = f.y; f.vx = 0f; f.vy = 0f }
                stepProjectiles(dt)
                return
            }
            Phase.PLAYING -> Unit
        }

        // The Entity's uprising: while it lasts, it is all that happens (the clock, the smoke and the boss wait).
        uprising?.let { u ->
            if (u.phase == UprisingPhase.WAITING && phaseTime >= u.startAt) beginUprising(u)
            if (u.active) { stepUprising(u, dt); return }
        }

        for (f in fighters) stepFighter(f, dt)
        stepConcealment(dt)
        stepProjectiles(dt)
        stepPlanted(dt)
        stepLobs(dt)
        stepHazards(dt)
        stepPickups(dt)

        timeLeft -= dt
        if (phase == Phase.PLAYING && timeLeft <= 0f) {
            timeLeft = 0f
            end(if (score[0] > score[1]) 0 else if (score[1] > score[0]) 1 else -1)
        }
    }

    // ------------------------------------------------------------------ the Entity's uprising

    private fun uprisingPhase(u: Uprising, phase: UprisingPhase) {
        u.phase = phase
        u.t = 0f
        events += GameEvent.Uprising(phase.ordinal)
    }

    private fun beginUprising(u: Uprising) {
        // Everyone comes: whoever was waiting to come back is brought back now.
        for (f in fighters) if (!f.alive && !f.eliminated) respawn(f)
        projectiles.clear(); hazards.clear(); lobs.clear()
        val centre = arena.nearestOpen(arena.width / 2f, arena.height / 2f, 1f)
        u.cx = centre.x; u.cy = centre.y
        u.members.clear()
        for (f in fighters) if (f.alive) {
            u.members += f.id
            // (A hypercharge running when it comes is put away, ready to use again afterwards.)
            if (f.hyperActive) { endHyper(f); f.hyperCharge = 1f }
            u.saved[f.id] = floatArrayOf(f.x, f.y, f.hp.toFloat(), f.superCharge, f.hyperCharge, f.ammo, f.edge, f.entityPull)
            // Whatever they were in the middle of ends.
            f.tempestLeft = 0f; f.dashTime = 0f; f.leapTime = 0f
            f.hexes.clear(); f.poisonBy = -1
            f.glitchLeft = 0f
            f.control.clear()
        }
        // Each has a place on a ring round the middle, facing in.
        val n = u.members.size
        val ring = 2.2f + 0.25f * n
        for ((k, id) in u.members.withIndex()) {
            val a = k * (6.2831855f / n) - 1.5707964f
            val spot = arena.nearestOpen(u.cx + cos(a) * ring, u.cy + sin(a) * ring, fighter(id)?.radius ?: 0.4f)
            u.slotX[id] = spot.x; u.slotY[id] = spot.y
        }
        uprisingPhase(u, UprisingPhase.GAZE)
    }

    private fun stepUprising(u: Uprising, dt: Float) {
        // SKIP (any real player, while it holds them): straight to its portal, everyone in their places.
        if (u.phase == UprisingPhase.GAZE || u.phase == UprisingPhase.MARCH || u.phase == UprisingPhase.BOW) {
            if (fighters.any { !it.isBot && it.control.skip }) {
                for (id in u.members) fighter(id)?.let { f -> f.x = u.slotX.getValue(id); f.y = u.slotY.getValue(id); f.prevX = f.x; f.prevY = f.y; f.vx = 0f; f.vy = 0f }
                uprisingPhase(u, UprisingPhase.ENTER)
                u.skipped = true
            }
        }
        u.t += dt
        if (u.phase != UprisingPhase.SKY && u.phase != UprisingPhase.RETURN) u.heldFor += dt
        if (u.phase != UprisingPhase.SKY) for (f in fighters) { f.prevX = f.x; f.prevY = f.y; f.control.clear() }
        when (u.phase) {
            UprisingPhase.GAZE -> {
                for (id in u.members) fighter(id)?.let { it.vx = 0f; it.vy = 0f; it.facing = -1.5707964f }
                if (u.t >= Balance.UPRISING_GAZE) uprisingPhase(u, UprisingPhase.MARCH)
            }
            UprisingPhase.MARCH -> {
                var all = true
                for (id in u.members) {
                    val f = fighter(id) ?: continue
                    // An entranced walk, paced to reach its circle as its calling ends (in its fight everyone starts
                    // close, and a full-speed walk was over before "Come to me" was said).
                    val left = (Balance.UPRISING_MARCH_PACE - u.t).coerceAtLeast(0.25f)
                    val dist = hypot(u.slotX.getValue(id) - f.x, u.slotY.getValue(id) - f.y)
                    val pace = (dist / left).coerceIn(0.15f, f.def.moveSpeed)
                    if (!walkTo(f, u.slotX.getValue(id), u.slotY.getValue(id), dt, drag = u.t >= Balance.UPRISING_PULL, speed = pace)) all = false
                    else f.facing = atan2(u.cy - f.y, u.cx - f.x)
                }
                // (Its calling runs its full length even when everyone is already there.)
                if ((all && u.t >= Balance.UPRISING_MARCH_PACE) || u.t >= Balance.UPRISING_MARCH) {
                    for (id in u.members) fighter(id)?.let { f -> f.x = u.slotX.getValue(id); f.y = u.slotY.getValue(id) }
                    u.bowLength = maxOf(Balance.UPRISING_BOW, Balance.UPRISING_HOLD - Balance.UPRISING_ENTER - u.heldFor)
                    uprisingPhase(u, UprisingPhase.BOW)
                }
            }
            UprisingPhase.BOW -> {
                for (id in u.members) fighter(id)?.let { f -> f.vx = 0f; f.vy = 0f; f.facing = atan2(u.cy - f.y, u.cx - f.x) }
                if (u.t >= u.bowLength) uprisingPhase(u, UprisingPhase.ENTER)
            }
            UprisingPhase.ENTER -> {
                for (id in u.members) fighter(id)?.let { f -> walkTo(f, u.cx, u.cy, dt, drag = true) }
                if (u.t >= Balance.UPRISING_ENTER) beginSky(u)
            }
            UprisingPhase.SKY -> stepSky(u, dt)
            UprisingPhase.RETURN -> if (u.t >= Balance.UPRISING_RETURN) {
                projectiles.clear(); hazards.clear()
                uprisingPhase(u, UprisingPhase.DONE)
            }
            UprisingPhase.WAITING, UprisingPhase.DONE -> Unit
        }
    }

    /**
     * Walks [f] toward (x, y) at its own pace, sliding along walls; with [drag] the Entity pulls it straight there,
     * through anything. True once it is there.
     */
    private fun walkTo(f: Fighter, x: Float, y: Float, dt: Float, drag: Boolean, speed: Float = f.def.moveSpeed): Boolean {
        val dx = x - f.x; val dy = y - f.y
        val d = hypot(dx, dy)
        if (d < 0.08f) { f.vx = 0f; f.vy = 0f; return true }
        val step = minOf(d, speed * (if (drag) 1.6f else 1f) * dt)
        if (drag) { f.x += dx / d * step; f.y += dy / d * step }
        else { arena.moveCircle(f.x, f.y, f.radius, dx / d * step, dy / d * step, tmp); f.x = tmp[0]; f.y = tmp[1] }
        f.walkCycle += hypot(f.x - f.prevX, f.y - f.prevY) * 3.2f
        f.facing = atan2(dy, dx)
        f.vx = (f.x - f.prevX) / dt; f.vy = (f.y - f.prevY) / dt
        return false
    }

    /** In the Entity's dimension, fighting it. */
    val inSky get() = uprising?.phase == UprisingPhase.SKY

    /** In the Entity's dimension: whether a circle at (x, y) of radius [r] reaches the shadow under it (what hits it). */
    private fun onEntity(x: Float, y: Float, r: Float): Boolean {
        val u = uprising ?: return false
        return u.phase == UprisingPhase.SKY && u.hp > 0 && hypot(x - u.ex, y - u.ey) < Balance.UPRISING_ENTITY_RADIUS + r
    }

    /**
     * Whether the line from [f] along (dx, dy) passes over the Entity's shadow within [range]; if so, how far along the
     * line its near edge is (below 0: it doesn't).
     */
    private fun entityOnLine(f: Fighter, dx: Float, dy: Float, range: Float): Float {
        val u = uprising ?: return -1f
        if (u.phase != UprisingPhase.SKY || u.hp <= 0) return -1f
        val ox = u.ex - f.x; val oy = u.ey - f.y
        val on = ox * dx + oy * dy
        val big = Balance.UPRISING_ENTITY_RADIUS
        if (on <= 0f || on - big > range || kotlin.math.abs(ox * dy - oy * dx) > big + f.radius) return -1f
        return maxOf(0f, on - big * 0.6f)
    }

    private fun beginSky(u: Uprising) {
        // Through the portal and into its dimension: its own platform, floating in its void. Everyone there fights on
        // one side (their teams are kept to give back), set down in an arc in front of it.
        u.home = arena
        arena = Arenas.entityRealm()
        val c = arena.width / 2f
        u.ex = c; u.ey = c - 2.5f
        u.hexes.clear()
        val n = u.members.size
        var hp = 0
        var k = 0
        for (id in u.members) {
            val f = fighter(id) ?: continue
            u.teams[id] = f.team
            f.team = 0
            f.control.clear()
            // (Bots come in with nothing charged: they earn their supers and hypers by hitting the Entity. The user's
            // balance call. What anyone had is given back afterwards.)
            if (f.isBot) { f.superCharge = 0f; f.hyperCharge = 0f }
            val a = 3.1415927f * (k++ + 0.5f) / maxOf(1, n)
            val spot = arena.nearestOpen(c + cos(a) * 5f, u.ey + 1f + sin(a) * 5f, f.radius)
            f.x = spot.x; f.y = spot.y; f.prevX = f.x; f.prevY = f.y; f.vx = 0f; f.vy = 0f
            f.facing = atan2(u.ey - f.y, u.ex - f.x)
            f.pending.clear()
            hp += f.attackDamage * maxOf(1, f.def.attack.projectiles)
        }
        u.maxHp = maxOf(1, (hp * Balance.UPRISING_HP_SHOTS * (if (u.boss) Balance.UPRISING_BOSS_HP else 1f)).toInt())
        u.flares.clear()
        u.hp = u.maxHp
        u.strikeIn = Balance.UPRISING_STRIKE_GAP
        uprisingPhase(u, UprisingPhase.SKY)
    }

    private fun stepSky(u: Uprising, dt: Float) {
        // Everyone fights as they always do: their attacks stay on the ground, and whatever reaches the shadow under
        // the Entity hits it (see [onEntity]); nobody can hurt anybody else in here, as they are all on one side.
        for (id in u.members) {
            val f = fighter(id) ?: continue
            stepFighter(f, dt)
            f.control.detonate = false
        }
        stepProjectiles(dt)
        stepHazards(dt)
        // Hexes bite it as they would anyone.
        var i = 0
        while (i < u.hexes.size) {
            val h = u.hexes[i]
            h.left -= dt; h.tick -= dt
            if (h.tick <= 0f) {
                h.tick += Balance.HEX_TICK_SECONDS
                fighter(h.by)?.let { strikeEntity(it, h.damage, false, u.ex, u.ey, charge = false) }
            }
            if (h.left <= 0f) u.hexes.removeAt(i) else i++
        }
        // It feeds on everyone it holds (the user's design: it starves them to make itself stronger): it heals, and
        // (see [markEntity]) it hits harder.
        val held = u.members.count { fighter(it)?.trapped == true }
        if (held > 0 && u.hp > 0) {
            u.fed += Balance.ENTITY_FEED_HEAL * u.maxHp * held * dt
            val whole = u.fed.toInt()
            if (whole > 0) { u.fed -= whole; u.hp = minOf(u.maxHp, u.hp + whole) }
        }
        stepDash(u, dt)
        stepSpin(u, dt)
        // It drifts after the fight, over the middle of everyone (a little ahead of them), never off its platform
        // (not while it dashes or spins: those move it themselves).
        var sx = 0f; var sy = 0f; var cnt = 0
        for (id in u.members) fighter(id)?.let { if (it.alive) { sx += it.x; sy += it.y; cnt++ } }
        if (cnt > 0 && u.dashT < 0f && u.spinT < 0f) {
            val dx = sx / cnt - u.ex; val dy = sy / cnt - 2f - u.ey
            val d = hypot(dx, dy)
            if (d > 0.3f) { val step = minOf(d, Balance.UPRISING_ENTITY_SPEED * dt); u.ex += dx / d * step; u.ey += dy / d * step }
            val c = arena.width / 2f
            val lim = Arenas.ENTITY_REALM_RADIUS - Balance.UPRISING_ENTITY_RADIUS
            val od = hypot(u.ex - c, u.ey - c)
            if (od > lim) { u.ex = c + (u.ex - c) / od * lim; u.ey = c + (u.ey - c) / od * lim }
        }
        entityMoves(u, dt)
        stepChase(u, dt)
        stepFlares(u, dt)
        // Everyone in its world pulled into it: in its fight, that is the match lost on the spot.
        if (u.boss && u.hp > 0 && u.members.isNotEmpty() && u.members.all { fighter(it)?.trapped != false }) {
            projectiles.clear(); hazards.clear(); u.hexes.clear(); u.flares.clear()
            for (id in u.members) fighter(id)?.let { f -> u.teams[id]?.let { f.team = it }; f.control.clear() }
            end(1)
            return
        }
        // Whoever it corrupted is theirs again when it wears off.
        for (id in u.members) fighter(id)?.let { f ->
            if (f.corruptLeft > 0f) { f.corruptLeft -= dt; f.glitchLeft = maxOf(f.glitchLeft, f.corruptLeft); if (f.corruptLeft <= 0f) uncorrupt(f) }
        }
        if (u.hp <= 0 || u.t >= (if (u.boss) Balance.UPRISING_BOSS_SECONDS else Balance.UPRISING_SKY)) {
            u.beaten = u.hp <= 0
            for (id in u.members) fighter(id)?.let { if (it.corruptLeft > 0f) uncorrupt(it) }
            // Beaten, it lets go of everyone it pulled in. (Not beaten in its fight, it keeps them: "Lose, and I keep
            // a piece of you". Out of a match's uprising, everyone goes back as they were.)
            if (u.beaten || !u.boss) for (id in u.members) fighter(id)?.let { if (it.trapped) free(it, u) }
            if (u.boss) {
                // The Entity's fight is the match: won if it is beaten, lost if time runs out. Everyone stays in its
                // world for the end of it.
                projectiles.clear(); hazards.clear(); u.hexes.clear(); u.flares.clear()
                for (id in u.members) fighter(id)?.let { f -> u.teams[id]?.let { f.team = it }; f.control.clear() }
                end(if (u.beaten) 0 else 1)
                return
            }
            // Back in the arena, where they were, as they were, each on their own team again.
            projectiles.clear(); hazards.clear(); u.hexes.clear()
            u.home?.let { arena = it }
            u.home = null
            for (id in u.members) {
                val f = fighter(id) ?: continue
                u.teams[id]?.let { f.team = it }
                val s = u.saved[id] ?: continue
                f.x = s[0]; f.y = s[1]; f.prevX = f.x; f.prevY = f.y; f.vx = 0f; f.vy = 0f
                // Exactly as they were: what they charged fighting the Entity stays in its dimension (coming back with
                // every super and hypercharge full had everyone unloading at once, as if the bots had got smarter).
                if (f.hyperActive) endHyper(f)
                if (f.alive) f.hp = s[2].toInt().coerceIn(1, f.maxHp)
                f.superCharge = s[3]; f.hyperCharge = s[4]; f.ammo = s[5]; f.edge = s[6]; f.entityPull = s[7]
                f.tempestLeft = 0f; f.dashTime = 0f; f.leapTime = 0f; f.leapTarget = -1
                f.pending.clear(); f.hexes.clear(); f.slowLeft = 0f
                f.control.clear()
            }
            uprisingPhase(u, UprisingPhase.RETURN)
        }
    }

    /**
     * The Entity's moves in its world, one after another (the next always the same, never a roll; sooner and harder as
     * it weakens, in its fight): marked strikes under some of them, a ring all round it, a line swept out at one of
     * them, its flares (which pull whoever they land on into it), and since build 111 (the user asked for "way more
     * abilities"): spokes turning round it, a rain of falling souls over the platform, its hand slamming down,
     * shockwaves rolling out from it and a chase of strikes on someone's heels. Everything lands on its platform:
     * nothing is marked past the edge (the user's ask, "only within the borders of the map").
     * In Boss Mode they knock out; in a match's uprising they never do.
     */
    private fun entityMoves(u: Uprising, dt: Float) {
        u.strikeIn -= dt
        if (u.strikeIn > 0f) return
        val frac = u.hp.toFloat() / u.maxHp
        val stage = if (!u.boss || frac > 0.66f) 0 else if (frac > 0.33f) 1 else 2
        u.strikeIn = Balance.UPRISING_STRIKE_GAP * (if (stage == 0) 1f else if (stage == 1) 0.8f else 0.65f)
        val alive = u.members.mapNotNull { fighter(it) }.filter { it.alive && it.corruptLeft <= 0f }
        // (Never two of its own movements at once: a dash or a spin still going waits for the next move.)
        var move = ENTITY_MOVES[u.strikes % ENTITY_MOVES.size]
        if ((move == EntityMove.DASH || move == EntityMove.SPIN) && (u.dashT >= 0f || u.spinT >= 0f)) move = EntityMove.STRIKES
        u.strikes++
        if (alive.isEmpty()) return
        events += GameEvent.EntityAttack(move.ordinal)
        fun mark(x: Float, y: Float, r: Float, delay: Float, damage: Int) = markEntity(u, x, y, r, delay, damage)
        when (move) {
            EntityMove.STRIKES -> for (j in 0 until 1 + alive.size / 3 + stage) {
                val f = alive[(u.strikes + j) % alive.size]
                mark(f.x + f.vx * 0.4f, f.y + f.vy * 0.4f, Balance.UPRISING_STRIKE_RADIUS, Balance.UPRISING_STRIKE_DELAY, Balance.UPRISING_STRIKE_DAMAGE)
            }
            EntityMove.RING -> {
                // A ring of strikes all round it, and (harder) a second, further out, a beat later.
                for (ring in 0..(if (stage > 0) 1 else 0)) {
                    val r = 3.6f + ring * 2.4f
                    val n = 10 + ring * 4
                    for (k in 0 until n) {
                        val a = k * (6.2831855f / n) + ring * 0.2f
                        mark(u.ex + cos(a) * r, u.ey + sin(a) * r, 0.95f, Balance.UPRISING_STRIKE_DELAY + ring * 0.6f, Balance.UPRISING_STRIKE_DAMAGE)
                    }
                }
            }
            EntityMove.SWEEP -> {
                // A line of strikes swept out from under it toward one of them, one after another.
                val f = alive[u.strikes % alive.size]
                val dx = f.x - u.ex; val dy = f.y - u.ey
                val d = hypot(dx, dy).coerceAtLeast(0.1f)
                for (k in 0 until 9) {
                    val at = Balance.UPRISING_ENTITY_RADIUS + 0.4f + k * 1.15f
                    mark(u.ex + dx / d * at, u.ey + dy / d * at, 0.9f, Balance.UPRISING_STRIKE_DELAY * 0.8f + k * 0.12f, Balance.UPRISING_STRIKE_DAMAGE)
                }
            }
            EntityMove.FLARE -> {
                // Its blue flares lock onto one of them (two, at the end), follow them, and come down: whoever is
                // under one when it lands is corrupted.
                for (k in 0 until (if (stage == 2) 2 else 1)) {
                    val f = alive[(u.flaresFired + k) % alive.size]
                    u.flares += EntityFlare(f.id, f.x, f.y)
                    events += GameEvent.EntityFlare(f.id, f.x, f.y, landed = false)
                }
                u.flaresFired++
            }
            EntityMove.SPOKES -> {
                // Spokes of strikes from under it out to the edge, going off one spoke after the next round it.
                val n = 5 + stage
                val turn = u.strikes * 0.7f
                for (s in 0 until n) for (k in 0 until 8) {
                    val a = turn + s * (6.2831855f / n)
                    val at = Balance.UPRISING_ENTITY_RADIUS + 0.5f + k * 1.1f
                    mark(u.ex + cos(a) * at, u.ey + sin(a) * at, 0.8f, Balance.UPRISING_STRIKE_DELAY + s * 0.32f, Balance.UPRISING_STRIKE_DAMAGE)
                }
            }
            EntityMove.RAIN -> {
                // Souls falling all over its platform, in three waves: spots spread by a fixed pattern (never a roll).
                val c = arena.width / 2f
                val n = 14 + stage * 4
                for (wave in 0 until 3) for (k in 0 until n) {
                    val h = ((u.strikes * 7919 + wave * 104729 + k * 15485863) and 0x7fffffff)
                    val a = (h % 3600) / 3600f * 6.2831855f
                    val r = kotlin.math.sqrt(((h / 3600) % 1000) / 1000f) * (Arenas.ENTITY_REALM_RADIUS - 1f)
                    mark(c + cos(a) * r, c + sin(a) * r, 0.75f, Balance.UPRISING_STRIKE_DELAY + wave * 0.7f, Balance.UPRISING_STRIKE_DAMAGE * 3 / 4)
                }
            }
            EntityMove.GRASP -> {
                // Its hand comes down on one of them: a great circle, slower to land, that hits much harder.
                for (k in 0 until (if (stage == 2) 2 else 1)) {
                    val f = alive[(u.strikes + k) % alive.size]
                    mark(f.x + f.vx * 0.6f, f.y + f.vy * 0.6f, Balance.ENTITY_GRASP_RADIUS, Balance.UPRISING_STRIKE_DELAY + 0.6f, Balance.UPRISING_STRIKE_DAMAGE * 2)
                }
            }
            EntityMove.PULSE -> {
                // Shockwaves rolling out from under it: rings one after another, further out each time.
                for (w in 0 until 5 + stage) {
                    val r = Balance.UPRISING_ENTITY_RADIUS + 0.8f + w * 1.5f
                    val n = (r * 3.4f).toInt()
                    for (k in 0 until n) {
                        val a = k * (6.2831855f / n) + w * 0.3f
                        mark(u.ex + cos(a) * r, u.ey + sin(a) * r, 0.85f, Balance.UPRISING_STRIKE_DELAY + w * 0.5f, Balance.UPRISING_STRIKE_DAMAGE)
                    }
                }
            }
            EntityMove.DASH -> {
                // It charges at one of them: its lane is marked at once, and it comes down it after a wind-up, hitting
                // whoever is still in it as it passes (the marks go off as it reaches each one).
                val f = alive[u.strikes % alive.size]
                val c = arena.width / 2f
                val lim = Arenas.ENTITY_REALM_RADIUS - Balance.UPRISING_ENTITY_RADIUS
                val dx = f.x - u.ex; val dy = f.y - u.ey
                val d = hypot(dx, dy).coerceAtLeast(0.1f)
                var tx = f.x + dx / d * 2.5f; var ty = f.y + dy / d * 2.5f
                val od = hypot(tx - c, ty - c)
                if (od > lim) { tx = c + (tx - c) / od * lim; ty = c + (ty - c) / od * lim }
                u.dashFromX = u.ex; u.dashFromY = u.ey; u.dashToX = tx; u.dashToY = ty; u.dashT = 0f
                val len = hypot(tx - u.ex, ty - u.ey)
                val n = (len / 0.9f).toInt().coerceAtLeast(1)
                for (k in 0..n) {
                    val s = k / n.toFloat()
                    mark(lerp(u.ex, tx, s), lerp(u.ey, ty, s), Balance.UPRISING_ENTITY_RADIUS * 0.75f,
                        Balance.ENTITY_DASH_WIND + s * len / Balance.ENTITY_DASH_SPEED, Balance.UPRISING_STRIKE_DAMAGE * 3 / 2)
                }
            }
            EntityMove.SPIN -> {
                // Its sword (its main attack): it winds up, then spins like a tornado, the blade cutting everyone in its
                // reach again and again, and drifts toward one of them as it goes.
                u.spinT = 0f
                u.spinTick = Balance.ENTITY_SPIN_WIND
                u.spinTarget = alive[u.strikes % alive.size].id
            }
            EntityMove.CHASE -> {
                // Strikes come down on one of them, one after another, wherever they are: they have to keep moving.
                u.chaseTarget = alive[u.strikes % alive.size].id
                u.chaseLeft = 6 + stage * 2
                u.chaseIn = 0f
            }
        }
    }

    /** How hard the Entity hits now: harder in its fight, and harder for every fighter it is feeding on. */
    private fun entityHard(u: Uprising): Float =
        (if (u.boss) Balance.UPRISING_BOSS_DAMAGE else 1f) * (1f + Balance.ENTITY_FEED_DAMAGE * u.members.count { fighter(it)?.trapped == true })

    /** Its dash: once wound up, it moves down its lane at [Balance.ENTITY_DASH_SPEED] (its marks do the hurting). */
    private fun stepDash(u: Uprising, dt: Float) {
        if (u.dashT < 0f) return
        u.dashT += dt
        val len = hypot(u.dashToX - u.dashFromX, u.dashToY - u.dashFromY).coerceAtLeast(0.01f)
        val s = ((u.dashT - Balance.ENTITY_DASH_WIND) * Balance.ENTITY_DASH_SPEED / len).coerceIn(0f, 1f)
        u.ex = lerp(u.dashFromX, u.dashToX, s); u.ey = lerp(u.dashFromY, u.dashToY, s)
        if (s >= 1f) u.dashT = -1f
    }

    /** Its sword spin: after the wind-up, a cut every [Balance.ENTITY_SPIN_TICK] across its whole reach. */
    private fun stepSpin(u: Uprising, dt: Float) {
        if (u.spinT < 0f) return
        u.spinT += dt
        if (u.spinT > Balance.ENTITY_SPIN_WIND) {
            // Drifting after its target as it spins, never off its platform.
            fighter(u.spinTarget)?.takeIf { it.alive }?.let { f ->
                val dx = f.x - u.ex; val dy = f.y - u.ey
                val d = hypot(dx, dy)
                if (d > 0.3f) { val step = minOf(d, Balance.ENTITY_SPIN_MOVE * dt); u.ex += dx / d * step; u.ey += dy / d * step }
                val c = arena.width / 2f
                val lim = Arenas.ENTITY_REALM_RADIUS - Balance.UPRISING_ENTITY_RADIUS
                val od = hypot(u.ex - c, u.ey - c)
                if (od > lim) { u.ex = c + (u.ex - c) / od * lim; u.ey = c + (u.ey - c) / od * lim }
            }
        }
        if (u.spinT >= u.spinTick && u.spinT <= Balance.ENTITY_SPIN_WIND + Balance.ENTITY_SPIN_SECONDS) {
            u.spinTick += Balance.ENTITY_SPIN_TICK
            val dmg = (Balance.ENTITY_SPIN_DAMAGE * entityHard(u)).toInt()
            for (id in u.members) fighter(id)?.let { f ->
                if (f.alive && hypot(f.x - u.ex, f.y - u.ey) < Balance.ENTITY_SPIN_RADIUS + f.radius * 0.3f) damage(f, null, dmg, true, f.x, f.y, lethal = u.boss)
            }
        }
        if (u.spinT > Balance.ENTITY_SPIN_WIND + Balance.ENTITY_SPIN_SECONDS) u.spinT = -1f
    }

    /** One of the Entity's marked strikes, at (x, y): never past the edge of its platform. */
    private fun markEntity(u: Uprising, x: Float, y: Float, r: Float, delay: Float, damage: Int) {
        val c = arena.width / 2f
        if (hypot(x - c, y - c) > Arenas.ENTITY_REALM_RADIUS - r * 0.5f) return
        val hard = entityHard(u)
        hazards += Hazard(-1, -1, x, y, r, delay, (damage * hard).toInt(), HazardKind.RIFT, lethal = u.boss)
    }

    /** Its chase: the next strike comes down where its target is now, a little ahead of where they are going. */
    private fun stepChase(u: Uprising, dt: Float) {
        if (u.chaseLeft <= 0) return
        u.chaseIn -= dt
        if (u.chaseIn > 0f) return
        val f = fighter(u.chaseTarget)
        if (f == null || !f.alive) { u.chaseLeft = 0; return }
        markEntity(u, f.x + f.vx * 0.3f, f.y + f.vy * 0.3f, 1.1f, 0.9f, Balance.UPRISING_STRIKE_DAMAGE)
        u.chaseLeft--
        u.chaseIn = Balance.ENTITY_CHASE_GAP
    }

    /** The Entity's flares: each follows its target while it locks on, then holds where it is and comes down there. */
    private fun stepFlares(u: Uprising, dt: Float) {
        val it = u.flares.iterator()
        while (it.hasNext()) {
            val fl = it.next()
            fl.age += dt
            val t = fighter(fl.target)
            if (fl.age < Balance.FLARE_LOCK && t != null && t.alive) { fl.x = t.x; fl.y = t.y }
            if (fl.age < Balance.FLARE_LOCK + Balance.FLARE_FALL) continue
            it.remove()
            events += GameEvent.EntityFlare(fl.target, fl.x, fl.y, landed = true)
            for (id in u.members) fighter(id)?.let { f ->
                if (f.alive && f.corruptLeft <= 0f && hypot(f.x - fl.x, f.y - fl.y) < Balance.FLARE_RADIUS + f.radius * 0.3f) trap(f)
            }
        }
    }

    /**
     * One of its flares came down on [f]: the Entity pulls them into itself, among the souls it carries (the user's
     * design, in place of corrupting them). They are out of the fight until it is beaten.
     */
    private fun trap(f: Fighter) {
        f.trapped = true
        f.alive = false
        f.respawnTimer = 0f
        f.pending.clear(); f.dashTime = 0f; f.leapTime = 0f; f.leapTarget = -1
        f.hexes.clear(); f.slowLeft = 0f
        f.latchedTo = -1
        release(f)
        cure(f)
        if (f.hyperActive) endHyper(f)
        if (f.inTempest) endTempest(f)
        f.control.clear()
        events += GameEvent.Trapped(f.id, true)
    }

    /** The Entity lets [f] go: back on its platform, whole, clear of everyone (`landingClear`). */
    private fun free(f: Fighter, u: Uprising) {
        f.trapped = false
        f.alive = true
        f.hp = f.maxHp
        f.vx = 0f; f.vy = 0f
        val c = arena.width / 2f
        // Set down just in front of where it was, searching outward for open ground.
        var placed = false
        var ring = 0
        while (!placed && ring < 8) {
            val r = Balance.UPRISING_ENTITY_RADIUS + 1.2f + ring * 0.8f
            var k = 0
            while (!placed && k < 12) {
                val a = 1.5707964f + (if (k % 2 == 0) 1 else -1) * (k / 2) * 0.5236f
                val x = u.ex + cos(a) * r; val y = u.ey + sin(a) * r
                if (hypot(x - c, y - c) < Arenas.ENTITY_REALM_RADIUS - 1f && landingClear(f, x, y)) { f.x = x; f.y = y; placed = true }
                k++
            }
            ring++
        }
        if (!placed) { f.x = c; f.y = c }
        f.prevX = f.x; f.prevY = f.y
        f.control.clear()
        events += GameEvent.Trapped(f.id, false)
    }

    /** The Entity has [f]: for a while it fights for the Entity, against its own side (the user's idea). */
    private fun corrupt(f: Fighter) {
        f.corruptLeft = Balance.CORRUPT_SECONDS
        f.glitchLeft = Balance.CORRUPT_SECONDS
        f.team = 1
        f.control.clear()
        events += GameEvent.Corrupted(f.id, true)
    }

    private fun uncorrupt(f: Fighter) {
        f.corruptLeft = 0f
        f.glitchLeft = 0f
        f.team = 0
        f.control.clear()
        events += GameEvent.Corrupted(f.id, false)
    }

    /** [f] lands a hit on the Entity, at (x, y): it charges their super and hyper as a hit on anyone would. */
    private fun strikeEntity(f: Fighter, damage: Int, isSuper: Boolean, x: Float, y: Float, charge: Boolean = true, chargeShare: Float = 1f) {
        val u = uprising ?: return
        // (Nobody the Entity has corrupted hurts it.)
        if (u.hp <= 0 || f.corruptLeft > 0f) return
        val dealt = minOf(damage, u.hp)
        u.hp -= dealt
        f.damageDealt += dealt
        if (!isSuper && charge) chargeFrom(f, chargeShare)
        events += GameEvent.EntityHit(f.id, isSuper, dealt, x, y)
    }

    /** [team] gives up (in a 1v1: its player left): the other team wins on the spot. */
    fun forfeit(team: Int) {
        if (phase != Phase.ENDED) end(1 - team)
    }

    /** The match is called off with no winner (a 1v1 whose two devices no longer agree). */
    fun abandon() {
        if (phase != Phase.ENDED) end(-1)
    }

    /**
     * A number that sums up where the match stands. Two devices running the same match get the same number on
     * the same tick, and almost certainly a different one the moment they disagree about anything.
     */
    fun checksum(): Int {
        var h = 17
        fun mix(v: Int) { h = h * 31 + v }
        for (f in fighters) {
            mix(f.x.toRawBits()); mix(f.y.toRawBits()); mix(f.hp); mix(f.kos)
            mix(f.superCharge.toRawBits()); mix(f.hyperCharge.toRawBits()); mix(f.ammo.toRawBits()); mix(f.poisonBy); mix(f.leapTime.toRawBits()); mix(f.latchedTo); mix(f.thrallOf); mix(f.hexes.size); mix(f.realm); mix(f.edge.toRawBits()); mix(f.slowLeft.toRawBits()); mix(f.tempestLeft.toRawBits()); mix(if (f.trapped) 1 else 0)
        }
        for (p in projectiles) { mix(p.x.toRawBits()); mix(p.y.toRawBits()) }
        mix(projectiles.size); mix(hazards.size); mix(planted.size); mix(lobs.size); mix(score[0]); mix(score[1]); mix(phase.ordinal)
        uprising?.let { mix(it.phase.ordinal); mix(it.hp); mix(it.flares.size) }
        return h
    }

    private fun end(winner: Int) {
        phase = Phase.ENDED
        phaseTime = 0f
        winningTeam = winner
        events += GameEvent.MatchEnd(winner)
    }

    // ------------------------------------------------------------------ fighters

    private fun stepFighter(f: Fighter, dt: Float) {
        f.prevX = f.x
        f.prevY = f.y
        val c = f.control
        if (f.eliminated || f.trapped) { c.attack = false; c.superAttack = false; c.hyper = false; c.detonate = false; return }
        if (!f.alive) {
            f.respawnTimer -= dt
            if (f.respawnTimer <= 0f) respawn(f)
            c.attack = false; c.superAttack = false; c.hyper = false; c.detonate = false
            return
        }

        f.sinceDamaged += dt
        f.sinceAttack += dt
        f.revealTimer = (f.revealTimer - dt).coerceAtLeast(0f)
        f.hitFlash = (f.hitFlash - dt).coerceAtLeast(0f)
        f.shield = (f.shield - dt).coerceAtLeast(0f)
        f.attackCooldown = (f.attackCooldown - dt).coerceAtLeast(0f)
        f.slowLeft = (f.slowLeft - dt).coerceAtLeast(0f)
        // (A blade's bar fills by itself, whatever its owner is doing.)
        if (f.def.attack.edgeSeconds > 0f && f.edge < 1f) f.edge = (f.edge + dt / f.def.attack.edgeSeconds).coerceAtMost(1f)

        // --- movement
        if (f.latched) stepLatch(f)
        if (f.heldInRealm) {
            // (Taken into a realm that holds its captive: held where it stands until it is over.)
        } else if (f.enthralled) {
            stepThrall(f, dt)
        } else if (f.latched) {
            // (Riding: [stepLatch] has already put it where its host is.)
        } else if (f.isLeaping) {
            stepLeap(f, dt)
        } else if (f.isDashing) {
            stepDash(f, dt)
        } else {
            var mx = if (f.rooted) 0f else c.moveX
            var my = if (f.rooted) 0f else c.moveY
            val len = hypot(mx, my)
            if (len > 1f) { mx /= len; my /= len }
            val speed = f.def.moveSpeed * (if (f.slowed) Balance.SLOW_FACTOR else 1f) * (if (f.captive) Balance.REALM_CAPTIVE_SPEED else 1f) *
                (if (f.inTempest) Balance.TEMPEST_SPEED else 1f)
            // (A tempest flies: over walls and water alike, only kept inside the arena.)
            val blocked = if (f.inTempest) {
                f.x = (f.x + mx * speed * dt).coerceIn(f.radius, arena.width - f.radius)
                f.y = (f.y + my * speed * dt).coerceIn(f.radius, arena.height - f.radius)
                false
            } else arena.moveCircle(f.x, f.y, f.radius, mx * speed * dt, my * speed * dt, tmp)
            if (!f.inTempest) { f.x = tmp[0]; f.y = tmp[1] }
            if (len > 0.05f) f.walkCycle += hypot(f.x - f.prevX, f.y - f.prevY) * 3.2f
            if (!blocked && len < 0.05f) f.walkCycle = 0f
        }
        f.vx = (f.x - f.prevX) / dt
        f.vy = (f.y - f.prevY) / dt

        // A fighter that is busy on someone's back, or is not its own any more, does nothing it is told to.
        val idle = f.latched || f.enthralled || f.heldInRealm
        if (idle) { c.attack = false; c.superAttack = false; c.hyper = false; c.detonate = false }

        // --- facing
        if (idle) Unit
        else if (c.aiming && (c.aimX != 0f || c.aimY != 0f)) f.facing = atan2(c.aimY, c.aimX)
        else if (hypot(c.moveX, c.moveY) > 0.1f && !f.isDashing) f.facing = atan2(c.moveY, c.moveX)

        // --- main attack
        // (A storm can't shoot: it is the attack.)
        if (c.attack && f.ammo >= 1f && f.attackCooldown <= 0f && f.pending.isEmpty() && !f.isDashing && !f.isLeaping && !idle && !f.inTempest) {
            val (dx, dy) = aimDirection(f)
            f.facing = atan2(dy, dx)
            f.ammo -= 1f
            f.attackCooldown = 0.28f
            f.sinceAttack = 0f
            f.revealTimer = maxOf(f.revealTimer, 1.2f)
            val a = f.def.attack
            when (a.shape) {
                AttackShape.BURST -> for (i in 0 until a.projectiles) {
                    val jitter = (random.nextFloat() - 0.5f) * Math.toRadians(a.spreadDegrees.toDouble()).toFloat()
                    val ang = atan2(dy, dx) + jitter
                    f.pending += PendingShot(i * a.burstInterval, cos(ang), sin(ang))
                }
                AttackShape.ROCKETS -> for (i in 0 until a.projectiles) {
                    f.pending += PendingShot((i / a.lanes) * a.burstInterval, dx, dy, (i % a.lanes - (a.lanes - 1) / 2f) * Balance.ROCKET_LANE)
                }
                // One after another, from alternate hands.
                AttackShape.SMASH, AttackShape.PAWS -> for (i in 0 until a.projectiles) {
                    f.pending += PendingShot(i * a.burstInterval, dx, dy, if (a.projectiles == 1) 0f else (0.5f - i % 2) * f.radius)
                }
                AttackShape.PLANT -> plant(f, dx, dy)
                AttackShape.STAR -> lob(f, dx, dy)
                AttackShape.SLASH -> slash(f, dx, dy)
                else -> f.pending += PendingShot(0f, dx, dy)
            }
            events += GameEvent.Shot(f.id, false, f.x, f.y, dx, dy)
        }
        c.attack = false

        // --- planted rockets: all of them that have landed go off at once
        if (c.detonate) setOff(f)
        c.detonate = false

        // --- pending (burst) shots
        if (f.pending.isNotEmpty()) {
            val it = f.pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.delay -= dt
                if (p.delay <= 0f) {
                    fireMain(f, p.dirX, p.dirY, p.side)
                    it.remove()
                }
            }
        }

        // --- super
        // (Any super can miss: one that needs somebody on the line it is aimed along is spent all the same with nobody there.)
        if (c.superAttack && f.superReady && !f.isDashing && !f.isLeaping && !idle) {
            val (dx, dy) = aimDirection(f)
            f.facing = atan2(dy, dx)
            fireSuper(f, dx, dy)
            f.superCharge = 0f
            f.sinceAttack = 0f
            f.revealTimer = maxOf(f.revealTimer, 1.5f)
            events += GameEvent.Shot(f.id, true, f.x, f.y, dx, dy)
        }
        c.superAttack = false

        // --- hyper
        if (c.hyper && f.hyperReady) startHyper(f)
        c.hyper = false
        if (f.hyperActive) {
            f.hyperTime -= dt
            if (f.hyperTime <= 0f) endHyper(f)
        }

        if (f.realm == f.id) stepRealm(f, dt)
        if (f.inTempest) stepTempest(f, dt)

        // --- reload (paused while a burst is still firing; quicker for whoever opened the realm they are in)
        if (f.ammo < f.def.ammoMax && f.pending.isEmpty()) {
            val quick = if (f.realm == f.id) Balance.REALM_RELOAD else 1f
            f.ammo = (f.ammo + dt * quick / f.def.reloadSeconds).coerceAtMost(f.def.ammoMax.toFloat())
        }

        if (f.hexed) stepHex(f, dt)
        // Malformed code stops its victim healing while it lasts.
        if (f.poisoned) stepPoison(f, dt) else regenerate(f, dt)
    }

    /**
     * Who [f] would leap onto (or take into its realm), aiming along ([dx], [dy]) (a unit vector): the first enemy it
     * can see on that line, within the super's reach. Neither is stopped by walls, so they don't count.
     */
    fun corruptTarget(f: Fighter, dx: Float, dy: Float): Fighter? {
        var best: Fighter? = null
        var bestOn = f.def.superSpec.range
        val realm = f.def.superSpec.kind == SuperKind.REALM
        for (o in fighters) {
            if (o.team == f.team || !isVisibleTo(o, f.team) || o.realm != f.realm) continue
            // (Nobody is taken out of the air.)
            if (realm && o.isLeaping) continue
            val ox = o.x - f.x
            val oy = o.y - f.y
            val on = ox * dx + oy * dy
            // (A little is forgiven either side of the line.)
            if (on <= 0f || on > bestOn || kotlin.math.abs(ox * dy - oy * dx) > o.radius + f.radius + 0.25f) continue
            best = o; bestOn = on
        }
        return best
    }

    private fun stepPoison(f: Fighter, dt: Float) {
        if (phase != Phase.PLAYING) return
        f.poisonLeft -= dt
        f.poisonTick -= dt
        if (f.poisonTick <= 0f) {
            f.poisonTick += Balance.POISON_TICK_SECONDS
            // (Code that does no damage just runs its course.)
            if (f.poisonDamage > 0) damage(f, fighter(f.poisonBy), (f.poisonDamage * Balance.POISON_TICK_SECONDS).toInt().coerceAtLeast(1), true, f.x, f.y)
        }
        if (f.alive && f.poisonLeft <= 0f) {
            // It has run its course, and whoever came through it is corrupted.
            val by = fighter(f.poisonBy)
            cure(f)
            if (by != null && by.alive && by.def.superSpec.kind == SuperKind.CORRUPT) enthrall(f, by)
        }
    }

    private fun enthrall(f: Fighter, by: Fighter) {
        f.thrallOf = by.id
        f.thrallLeft = Balance.THRALL_SECONDS
        f.dashTime = 0f
        f.pending.clear()
        f.revealTimer = maxOf(f.revealTimer, 1.5f)
        events += GameEvent.Enthralled(by.id, f.id)
    }

    private fun release(f: Fighter) {
        f.thrallOf = -1
        f.thrallLeft = 0f
        f.thrallMoving = false
    }

    /** A corrupted fighter: over to whoever did it, and stock-still in front of them, until it wears off. */
    private fun stepThrall(f: Fighter, dt: Float) {
        val m = fighter(f.thrallOf)
        f.thrallLeft -= dt
        if (m == null || !m.alive || f.thrallLeft <= 0f) { release(f); return }
        val dx = m.x - f.x
        val dy = m.y - f.y
        val d = hypot(dx, dy)
        val stop = f.radius + m.radius + 0.35f
        if (d > 1e-3f) f.facing = atan2(dy, dx)
        f.thrallMoving = !f.rooted && d > stop + 0.02f
        if (!f.thrallMoving) return
        val step = minOf(f.def.moveSpeed * dt, d - stop)
        arena.moveCircle(f.x, f.y, f.radius, dx / d * step, dy / d * step, tmp)
        f.x = tmp[0]; f.y = tmp[1]
        f.walkCycle += hypot(f.x - f.prevX, f.y - f.prevY) * 3.2f
    }

    /** A fighter latched onto someone's back rides wherever they go, for as long as its code is going into them. */
    private fun stepLatch(f: Fighter) {
        val host = fighter(f.latchedTo)
        if (host == null || !host.alive || host.poisonBy != f.id || host.realm != f.realm) { unlatch(f); return }
        f.x = host.x - cos(host.facing) * host.radius * 0.7f
        f.y = host.y - sin(host.facing) * host.radius * 0.7f
        f.facing = host.facing
    }

    /** Lets go, and steps down onto open ground. */
    private fun unlatch(f: Fighter) {
        f.latchedTo = -1
        val spot = arena.nearestOpen(f.x, f.y, f.radius)
        f.x = spot.x; f.y = spot.y
    }

    /** Every hex on [f] bites on its own clock: its damage again, every [Balance.HEX_TICK_SECONDS], until its time is up. */
    private fun stepHex(f: Fighter, dt: Float) {
        if (phase != Phase.PLAYING) return
        var i = 0
        while (i < f.hexes.size) {
            val h = f.hexes[i]
            h.left -= dt
            h.tick -= dt
            if (h.tick <= 0f) {
                h.tick += Balance.HEX_TICK_SECONDS
                // (Only the bolt itself charges the super: the bites that follow don't.)
                damage(f, fighter(h.by), h.damage, false, f.x, f.y, charge = false)
                // (A knockout lifts every hex at once.)
                if (!f.alive) return
            }
            if (h.left <= 0f) f.hexes.removeAt(i) else i++
        }
    }

    // ------------------------------------------------------------------ planted rockets and the realm

    /**
     * Lobs a rocket at the spot [f] aimed at: for this attack the aim is how far away, in tiles, as well as which way
     * (with no length to speak of it goes most of the way out). It goes over walls and sticks in the nearest open ground.
     */
    private fun plant(f: Fighter, dx: Float, dy: Float) {
        val a = f.def.attack
        val reach = hypot(f.control.aimX, f.control.aimY).coerceAtMost(a.range)
        val d = if (reach > 1e-3f) reach else a.range * 0.6f
        val spot = arena.nearestOpen(f.x + dx * d, f.y + dy * d, 0.2f)
        // No more than a handful in the ground: one too many sets the oldest off.
        var mine = 0
        var oldest: Planted? = null
        for (p in planted) if (p.ownerId == f.id) { if (oldest == null) oldest = p; mine++ }
        if (mine >= Balance.PLANT_MAX && oldest != null) burst(oldest, f)
        planted += Planted(f.id, f.team, f.x, f.y, spot.x, spot.y, Balance.PLANT_FLIGHT_SECONDS / hyperShotSpeed(f), a.blast)
    }

    /**
     * A swing of a blade: every enemy within reach, inside the arc and not behind a wall, is hit at once (and so is any
     * Spark Crate). With the blade's bar full this one reaches further, and empties it.
     */
    private fun slash(f: Fighter, dx: Float, dy: Float) {
        val a = f.def.attack
        val charged = f.edgeFull
        val reach = a.range * (if (charged) Balance.EDGE_REACH else 1f)
        if (charged) f.edge = 0f
        events += GameEvent.Slash(f.id, f.x, f.y, dx, dy, reach, a.spreadDegrees, charged)
        if (phase != Phase.PLAYING) return
        val edge = cos(Math.toRadians(a.spreadDegrees / 2.0)).toFloat()
        val hit = f.attackDamage
        for (o in fighters) {
            if (o.team == f.team || !o.alive || o.realm != f.realm) continue
            val ox = o.x - f.x
            val oy = o.y - f.y
            val d = hypot(ox, oy)
            if (d > reach + o.radius) continue
            // (Whoever is right up against the blade is caught whichever way it is swung.)
            if (d > f.radius + o.radius && (ox * dx + oy * dy) / d < edge) continue
            if (!arena.shotClear(f.x, f.y, o.x, o.y)) continue
            damage(o, f, hit, false, o.x, o.y)
        }
        // (The blade reaches the Entity where its arc reaches the shadow under it.)
        uprising?.let { u ->
            if (u.phase != UprisingPhase.SKY || u.hp <= 0) return@let
            val ox = u.ex - f.x; val oy = u.ey - f.y
            val d = hypot(ox, oy)
            val big = Balance.UPRISING_ENTITY_RADIUS
            if (d > reach + big) return@let
            val widen = kotlin.math.asin((big / maxOf(d, big)).coerceAtMost(1f))
            val off = kotlin.math.acos(((ox * dx + oy * dy) / maxOf(d, 1e-3f)).coerceIn(-1f, 1f))
            if (d > big + f.radius && off - widen > Math.toRadians(a.spreadDegrees / 2.0).toFloat()) return@let
            val at = minOf(reach, maxOf(0f, d - big * 0.5f))
            strikeEntity(f, hit, false, f.x + dx * at, f.y + dy * at)
        }
        val r = reach + 1f
        for (ty in kotlin.math.floor(f.y - r).toInt()..kotlin.math.floor(f.y + r).toInt()) {
            for (tx in kotlin.math.floor(f.x - r).toInt()..kotlin.math.floor(f.x + r).toInt()) {
                if (tx < 0 || ty < 0 || tx >= arena.width || ty >= arena.height || !crateHp.containsKey(ty * arena.width + tx)) continue
                val ox = tx + 0.5f - f.x
                val oy = ty + 0.5f - f.y
                val d = hypot(ox, oy)
                if (d <= reach + 0.5f && (d < 1f || (ox * dx + oy * dy) / d >= edge)) damageCrate(tx, ty, hit)
            }
        }
    }

    /** [f] sets off every rocket of its own that has landed. */
    private fun setOff(f: Fighter) {
        var any = false
        var i = 0
        while (i < planted.size) {
            val p = planted[i]
            if (p.ownerId == f.id && p.landed) { burst(p, f); any = true } else i++
        }
        if (!any) return
        f.sinceAttack = 0f
        f.revealTimer = maxOf(f.revealTimer, 1.2f)
    }

    /** One planted rocket goes off: every enemy in its blast is hit for its owner's attack, and so is any Spark Crate. */
    private fun burst(p: Planted, owner: Fighter) {
        planted.remove(p)
        events += GameEvent.Burst(p.x, p.y, p.blast)
        if (phase != Phase.PLAYING) return
        val hit = owner.attackDamage
        for (f in fighters) {
            if (f.team == p.team || !f.alive || f.realm != owner.realm) continue
            if (hypot(f.x - p.x, f.y - p.y) < p.blast + f.radius * 0.5f) damage(f, owner, hit, false, f.x, f.y)
        }
        for (ty in kotlin.math.floor(p.y - p.blast).toInt()..kotlin.math.floor(p.y + p.blast).toInt()) {
            for (tx in kotlin.math.floor(p.x - p.blast).toInt()..kotlin.math.floor(p.x + p.blast).toInt()) {
                if (tx < 0 || ty < 0 || tx >= arena.width || ty >= arena.height || !crateHp.containsKey(ty * arena.width + tx)) continue
                if (hypot(tx + 0.5f - p.x, ty + 0.5f - p.y) < p.blast + 0.3f) damageCrate(tx, ty, hit)
            }
        }
    }

    /**
     * Lobs a star at the spot [f] aimed at, like [plant]: the aim is how far away as well as which way, and it goes over
     * walls. It bursts where it comes down.
     */
    private fun lob(f: Fighter, dx: Float, dy: Float) {
        val a = f.def.attack
        val reach = hypot(f.control.aimX, f.control.aimY).coerceAtMost(a.range)
        val d = if (reach > 1e-3f) reach else a.range * 0.6f
        val spot = arena.nearestOpen(f.x + dx * d, f.y + dy * d, 0.2f)
        lobs += Lob(f.id, f.team, f.x, f.y, spot.x, spot.y, Balance.STAR_FLIGHT_SECONDS / hyperShotSpeed(f), a.blast, a.slowSeconds)
    }

    private fun stepLobs(dt: Float) {
        var i = 0
        while (i < lobs.size) {
            val l = lobs[i]
            l.age += dt
            if (l.age < l.flight) { i++; continue }
            lobs.removeAt(i)
            val owner = fighter(l.ownerId) ?: continue
            starBurst(l, owner)
        }
    }

    /** A lobbed star comes down: every enemy in its burst is hit for its owner's attack and slowed, and so is any Spark Crate hit. */
    private fun starBurst(l: Lob, owner: Fighter) {
        events += GameEvent.StarBurst(l.ownerId, l.x, l.y, l.blast)
        if (phase != Phase.PLAYING) return
        val hit = owner.attackDamage
        for (f in fighters) {
            if (f.team == l.team || !f.alive || f.realm != owner.realm) continue
            if (hypot(f.x - l.x, f.y - l.y) >= l.blast + f.radius * 0.5f) continue
            damage(f, owner, hit, false, f.x, f.y)
            if (l.slow > 0f && f.alive && f.shield <= 0f && !f.isLeaping) f.slowLeft = maxOf(f.slowLeft, l.slow)
        }
        for (ty in kotlin.math.floor(l.y - l.blast).toInt()..kotlin.math.floor(l.y + l.blast).toInt()) {
            for (tx in kotlin.math.floor(l.x - l.blast).toInt()..kotlin.math.floor(l.x + l.blast).toInt()) {
                if (tx < 0 || ty < 0 || tx >= arena.width || ty >= arena.height || !crateHp.containsKey(ty * arena.width + tx)) continue
                if (hypot(tx + 0.5f - l.x, ty + 0.5f - l.y) < l.blast + 0.3f) damageCrate(tx, ty, hit)
            }
        }
    }

    private fun stepPlanted(dt: Float) {
        for (p in planted) {
            val flying = !p.landed
            p.age += dt
            if (flying && p.landed) events += GameEvent.Armed(p.ownerId, p.x, p.y)
        }
    }

    /** [f] takes [t] away into a realm of its own. Nobody outside can touch either of them until it is over. */
    private fun openRealm(f: Fighter, t: Fighter) {
        f.realm = f.id
        f.realmLeft = f.def.superSpec.realmSeconds.takeIf { it > 0f } ?: Balance.REALM_SECONDS
        // (The first comes down half a beat in, so a whole realm sees one for every second of it.)
        f.realmTick = realmBeat(f) / 2f
        f.realmStrikes = 0
        if (t.latched) unlatch(t)
        t.realm = f.id
        t.realmFree = f.def.superSpec.captiveFree
        t.dashTime = 0f
        t.pending.clear()
        t.revealTimer = maxOf(t.revealTimer, 1.5f)
        events += GameEvent.Realm(f.id, t.id, true)
    }

    /**
     * Time between two hits in the realm [f] opens: one rocket every [Balance.REALM_ROCKET_SECONDS], or, with shadows
     * ([io.github.projectwip.data.SuperSpec.shadows]), each shadow strikes that often, in turn.
     */
    private fun realmBeat(f: Fighter): Float = Balance.REALM_ROCKET_SECONDS / maxOf(1, f.def.superSpec.shadows)

    /** The realm [f] opened: its own rocket (or its shadows) hits the captive every [realmBeat], until the time is up. */
    private fun stepRealm(f: Fighter, dt: Float) {
        val t = fighters.firstOrNull { it.realm == f.id && it !== f }
        f.realmLeft -= dt
        if (t == null || !t.alive || f.realmLeft <= 0f) { closeRealm(f.id); return }
        f.realmTick -= dt
        if (f.realmTick > 0f) return
        f.realmTick += realmBeat(f)
        f.realmStrikes++
        events += GameEvent.Burst(t.x, t.y, t.radius + if (f.def.superSpec.shadows > 0) 0.2f else 0.5f)
        // (It charges the super like an attack of the owner's, so one realm can follow another; a shadow's strike only by
        // a small share of one. A knockout closes the realm by itself.)
        damage(t, f, f.superDamage, false, t.x, t.y, chargeShare = if (f.def.superSpec.shadows > 0) Balance.REALM_SHADOW_CHARGE else 1f)
    }

    /**
     * [f] is a tempest: every [Balance.TEMPEST_TICK_SECONDS] each enemy within the super's radius is hit for its damage and
     * flung outward, until the time is up. (A giant or a fighter fixed to the spot is hit but not flung.)
     */
    private fun stepTempest(f: Fighter, dt: Float) {
        f.tempestLeft -= dt
        if (f.tempestLeft <= 0f) { endTempest(f); return }
        // Lightning: every so often a bolt comes down on (or near) the nearest enemy in reach.
        f.tempestBolt -= dt
        if (f.tempestBolt <= 0f) {
            f.tempestBolt += Balance.TEMPEST_BOLT_SECONDS
            var t: Fighter? = null
            var best = Balance.TEMPEST_BOLT_RANGE
            for (o in fighters) {
                if (o.team == f.team || !o.alive || o.realm != f.realm || o.isLeaping || !isVisibleTo(o, f.team)) continue
                val d = hypot(o.x - f.x, o.y - f.y)
                if (d < best) { t = o; best = d }
            }
            val u = uprising
            if (t == null && u != null && u.phase == UprisingPhase.SKY && u.hp > 0 &&
                hypot(u.ex - f.x, u.ey - f.y) < Balance.TEMPEST_BOLT_RANGE + Balance.UPRISING_ENTITY_RADIUS) {
                // (In the Entity's dimension the lightning goes into it, somewhere in its shadow.)
                val a = random.nextFloat() * 6.2832f
                val off = random.nextFloat() * Balance.UPRISING_ENTITY_RADIUS * 0.7f
                val bx = u.ex + cos(a) * off
                val by = u.ey + sin(a) * off
                events += GameEvent.TempestBolt(f.id, bx, by)
                strikeEntity(f, (f.superDamage * Balance.TEMPEST_BOLT_DAMAGE).toInt(), false, bx, by, chargeShare = Balance.TEMPEST_CHARGE)
            }
            if (t != null) {
                val a = random.nextFloat() * 6.2832f
                val off = random.nextFloat() * Balance.TEMPEST_BOLT_SCATTER
                val bx = t.x + cos(a) * off
                val by = t.y + sin(a) * off
                events += GameEvent.TempestBolt(f.id, bx, by)
                val hit = (f.superDamage * Balance.TEMPEST_BOLT_DAMAGE).toInt()
                for (o in fighters) {
                    if (o.team == f.team || !o.alive || o.realm != f.realm || o.isLeaping) continue
                    if (hypot(o.x - bx, o.y - by) > Balance.TEMPEST_BOLT_RADIUS + o.radius) continue
                    damage(o, f, hit, false, o.x, o.y, chargeShare = Balance.TEMPEST_CHARGE)
                }
            }
        }
        f.tempestTick -= dt
        if (f.tempestTick > 0f) return
        f.tempestTick += Balance.TEMPEST_TICK_SECONDS
        val s = f.def.superSpec
        // (The funnel tears into the Entity wherever its foot is over the shadow.)
        if (onEntity(f.x, f.y, s.radius + f.radius)) {
            val u = uprising!!
            val d = hypot(u.ex - f.x, u.ey - f.y)
            val k = if (d > 1e-3f) minOf(1f, (s.radius + f.radius) / d) else 0f
            strikeEntity(f, f.superDamage, false, f.x + (u.ex - f.x) * k, f.y + (u.ey - f.y) * k, chargeShare = Balance.TEMPEST_CHARGE)
            events += GameEvent.TempestHit(f.id, -1, f.x + (u.ex - f.x) * k, f.y + (u.ey - f.y) * k)
        }
        for (o in fighters) {
            if (o.team == f.team || !o.alive || o.realm != f.realm || o.isLeaping) continue
            val ox = o.x - f.x
            val oy = o.y - f.y
            val d = hypot(ox, oy)
            // (Anyone the funnel touches: its foot reaches the super's radius out past her own edge.)
            if (d > s.radius + f.radius + o.radius) continue
            // (Counted like the realm's strikes, as hits that charge the super, but only by a small share of one.)
            damage(o, f, f.superDamage, false, o.x, o.y, chargeShare = Balance.TEMPEST_CHARGE)
            events += GameEvent.TempestHit(f.id, o.id, o.x, o.y)
            if (o.alive && !o.rooted && o.scale <= 1f && !o.latched && !o.heldInRealm) {
                // (Straight out from the eye; anyone standing right in it goes the way the tempest is facing.)
                val (ux, uy) = if (d > 1e-3f) (ox / d) to (oy / d) else cos(f.facing) to sin(f.facing)
                arena.moveCircle(o.x, o.y, o.radius, ux * s.knockback, uy * s.knockback, tmp)
                o.x = tmp[0]; o.y = tmp[1]
            }
        }
    }

    private fun endTempest(f: Fighter) {
        events += GameEvent.Tempest(f.id, false)
        f.tempestLeft = 0f
        f.tempestTick = 0f
        f.tempestBolt = 0f
        // (She flew: she comes down on open ground, and never inside another fighter, where she would be hidden in
        // them: a tornado driven straight through someone used to end with her standing inside them.)
        if (!f.alive) return
        if (!landingClear(f, f.x, f.y)) {
            // The nearest spot round her that is open ground and well clear of everyone (a ring at a time).
            var found = false
            var ring = 1
            while (!found && ring <= 24) {
                val r = ring * 0.25f
                val steps = 8 + ring * 2
                for (k in 0 until steps) {
                    val a = k * (6.2831855f / steps)
                    val x = f.x + kotlin.math.cos(a) * r; val y = f.y + kotlin.math.sin(a) * r
                    if (landingClear(f, x, y)) { f.x = x; f.y = y; found = true; break }
                }
                ring++
            }
            if (!found && arena.circleBlocked(f.x, f.y, f.radius)) {
                val spot = arena.nearestOpen(f.x, f.y, f.radius)
                f.x = spot.x; f.y = spot.y
            }
        }
        f.prevX = f.x; f.prevY = f.y
    }

    /** Open ground for [f] to come down on at (x, y): no wall, and no other fighter close enough to hide her. */
    private fun landingClear(f: Fighter, x: Float, y: Float): Boolean {
        if (arena.circleBlocked(x, y, f.radius)) return false
        for (o in fighters) {
            if (o === f || !o.alive) continue
            if (kotlin.math.hypot(o.x - x, o.y - y) < o.radius + f.radius + Balance.TEMPEST_LANDING_GAP) return false
        }
        return true
    }

    /** The realm [id] opened is over: everyone in it is back in the arena, where they stood. */
    private fun closeRealm(id: Int) {
        for (o in fighters) {
            if (o.realm != id) continue
            if (o.captive) events += GameEvent.Realm(id, o.id, false)
            o.realm = -1
            o.realmLeft = 0f
            o.realmFree = false
        }
    }

    private fun cure(f: Fighter) {
        f.poisonBy = -1
        f.poisonDamage = 0
    }

    /**
     * Healing. Nobody heals in the middle of a fight: a fighter has to go [Balance.REGEN_DELAY_SECONDS] without
     * attacking or being hit first, and then health comes back steadily. Bots heal at half a player's pace, and a
     * giant far more slowly.
     */
    fun regenerate(f: Fighter, dt: Float) {
        if (f.hp >= f.maxHp || f.sinceDamaged <= Balance.REGEN_DELAY_SECONDS || f.sinceAttack <= Balance.REGEN_DELAY_SECONDS) return
        val rate = when {
            f.scale > 1f -> Balance.GIANT_REGEN_FRACTION_PER_SECOND
            f.isBot -> Balance.BOT_REGEN_FRACTION_PER_SECOND
            else -> Balance.REGEN_FRACTION_PER_SECOND
        }
        f.hp = (f.hp + (f.maxHp * rate * dt).toInt().coerceAtLeast(1)).coerceAtMost(f.maxHp)
    }

    /** Switches a charged hyper on: more damage (see [Fighter.damageMultiplier]) and more health, for a few seconds. */
    private fun startHyper(f: Fighter) {
        f.hyperCharge = 0f
        f.hyperTime = f.hyperSeconds
        f.hyperHpBonus = (f.maxHp * Balance.HYPER_HEALTH_BONUS).toInt()
        f.maxHp += f.hyperHpBonus
        f.hp += f.hyperHpBonus
        f.revealTimer = maxOf(f.revealTimer, 1.5f)
        events += GameEvent.Hyper(f.id)
    }

    private fun endHyper(f: Fighter) {
        f.hyperTime = 0f
        f.maxHp -= f.hyperHpBonus
        f.hyperHpBonus = 0
        f.hp = f.hp.coerceAtMost(f.maxHp)
    }

    /** Tracks who is tucked away in a thicket and which teams have spotted them up close. */
    private fun stepConcealment(dt: Float) {
        for (f in fighters) {
            // Leaving the grass, attacking or getting hit all break cover: the fighter has to settle in again.
            if (!f.alive || !arena.inThicket(f.x, f.y)) {
                f.concealTime = 0f
                f.spottedBy.fill(0f)
                continue
            }
            if (f.revealTimer > 0f) { f.concealTime = 0f; continue }
            f.concealTime += dt
            val spotted = f.spottedBy
            for (t in spotted.indices) spotted[t] = (spotted[t] - dt).coerceAtLeast(0f)
            for (o in fighters) {
                if (o.team == f.team || !o.alive) continue
                if (hypot(o.x - f.x, o.y - f.y) < Balance.THICKET_SPOT_RADIUS) spotted[o.team] = Balance.THICKET_SPOT_LINGER_SECONDS
            }
        }
    }

    private fun aimDirection(f: Fighter): Pair<Float, Float> {
        val c = f.control
        val len = hypot(c.aimX, c.aimY)
        return if (len > 1e-4f) (c.aimX / len) to (c.aimY / len) else cos(f.facing) to sin(f.facing)
    }

    private fun fireMain(f: Fighter, dx: Float, dy: Float, side: Float = 0f) {
        val a = f.def.attack
        val style = when (a.shape) {
            AttackShape.BURST -> ShotStyle.SPARK
            AttackShape.SPREAD -> if (a.bits) ShotStyle.BIT else ShotStyle.PELLET
            AttackShape.LANCE -> ShotStyle.PRISM
            AttackShape.ROCKETS -> ShotStyle.ROCKET
            AttackShape.SMASH -> ShotStyle.COMPUTER
            AttackShape.PAWS -> ShotStyle.PAW
            AttackShape.HEX -> ShotStyle.HEX
            AttackShape.PLANT -> ShotStyle.ROCKET
            AttackShape.SLASH -> ShotStyle.PELLET
            AttackShape.STAR -> ShotStyle.STAR
            // (Swish's Frost Gale; the giant arrow it replaced, [ShotStyle.ARROW], is kept with nobody using it.)
            AttackShape.ARROW -> ShotStyle.GUST
        }
        val baseAng = atan2(dy, dx)
        val n = if (a.shape == AttackShape.SPREAD) a.projectiles else 1
        val spread = Math.toRadians(a.spreadDegrees.toDouble()).toFloat()
        for (i in 0 until n) {
            val ang = if (n == 1) baseAng else baseAng - spread / 2 + spread * i / (n - 1)
            spawnProjectile(f, ang, a.speed * hyperShotSpeed(f), a.radius, f.attackDamage, a.range, a.pierce, false, style, side = side, blast = a.blast, share = a.healthShare * f.damageMultiplier, hex = a.hexSeconds, slow = a.slowSeconds,
                homing = Math.toRadians(a.homing.toDouble()).toFloat(), knock = a.knockback)
        }
    }

    /** How much faster [f]'s shots fly right now: its own hyper may speed them up. */
    private fun hyperShotSpeed(f: Fighter) = if (f.hyperActive) f.def.hyper?.shotSpeed ?: 1f else 1f

    private fun fireSuper(f: Fighter, dx: Float, dy: Float) {
        val s = f.def.superSpec
        val baseAng = atan2(dy, dx)
        when (s.kind) {
            SuperKind.VOLLEY -> {
                val spread = Math.toRadians(s.spreadDegrees.toDouble()).toFloat()
                for (i in 0 until s.projectiles) {
                    val ang = baseAng - spread / 2 + spread * i / (s.projectiles - 1)
                    spawnProjectile(f, ang, s.speed, s.radius, f.superDamage, s.range, false, true, if (f.def.attack.bits) ShotStyle.BIT else ShotStyle.VOLLEY, knock = s.knockback)
                }
            }
            SuperKind.PIERCE -> spawnProjectile(f, baseAng, s.speed, s.radius, f.superDamage, s.range, true, true, ShotStyle.LANCE)
            // The hammer flies until it meets an enemy, a wall or the end of its reach, and the quake is its blast.
            SuperKind.QUAKE -> spawnProjectile(f, baseAng, s.speed, 0.42f, f.superDamage, s.range, false, true, ShotStyle.HAMMER, blast = s.radius)
            // One flask, thrown to where the fighter aimed (as with a rain of rockets, the aim is how far as well as
            // which way). What it does when it bursts is in [stepHazards].
            SuperKind.BREW -> {
                val reach = hypot(f.control.aimX, f.control.aimY).coerceAtMost(s.range)
                val d = if (reach > 1e-3f) reach else s.range * 0.5f
                hazards += Hazard(f.id, f.team, f.x + dx * d, f.y + dy * d, s.radius, Balance.BREW_DELAY_SECONDS, f.superDamage, HazardKind.BREW)
            }
            SuperKind.SWARM -> {
                // The rockets go up, and come down one after another inside one circle where the fighter aimed. For
                // this super the aim is not just a direction: it is how far away, in tiles, the circle is.
                val reach = hypot(f.control.aimX, f.control.aimY).coerceAtMost(s.range)
                val d = if (reach > 1e-3f) reach else s.range * 0.5f
                val scatter = (s.radius - Balance.RAIN_BLAST).coerceAtLeast(0f)
                for (i in 0 until s.projectiles) {
                    // The first lands dead centre; the rest spiral out from it to fill the circle.
                    val out = scatter * kotlin.math.sqrt(i.toFloat() / (s.projectiles - 1).coerceAtLeast(1))
                    val delay = (Balance.RAIN_DELAY_SECONDS + i * Balance.RAIN_GAP_SECONDS) / hyperShotSpeed(f)
                    hazards += Hazard(f.id, f.team, f.x + dx * d + cos(i * 2.4f) * out, f.y + dy * d + sin(i * 2.4f) * out,
                        Balance.RAIN_BLAST, delay, f.superDamage, HazardKind.ROCKET, lethal = false)
                }
                events += GameEvent.Launch(f.id, s.projectiles)
            }
            SuperKind.RAM -> if (!f.rooted) {
                f.dashTime = s.range / s.speed
                f.dashDirX = dx
                f.dashDirY = dy
                f.dashHits.clear()
                f.pending.clear()
                events += GameEvent.Dash(f.id)
            }
            // Gone, and there: beside the first enemy on the line (behind them, facing them), with everything ready. With
            // nobody on the line it is the end of the super's reach, and nothing is ready that wasn't.
            SuperKind.BLINK -> if (!f.rooted) {
                val t = corruptTarget(f, dx, dy)
                // (In the Entity's dimension: under its edge on the line, cutting into it as he arrives.)
                val onIt = if (t == null) entityOnLine(f, dx, dy, s.range) else -1f
                if (onIt >= 0f) {
                    val fromX = f.x; val fromY = f.y
                    val spot = arena.nearestOpen(f.x + dx * onIt, f.y + dy * onIt, f.radius)
                    f.x = spot.x; f.y = spot.y; f.prevX = f.x; f.prevY = f.y
                    f.pending.clear()
                    uprising?.let { u -> f.facing = atan2(u.ey - f.y, u.ex - f.x) }
                    f.ammo = f.def.ammoMax.toFloat()
                    if (f.def.attack.edgeSeconds > 0f) f.edge = 1f
                    events += GameEvent.Blink(f.id, fromX, fromY, f.x, f.y)
                    strikeEntity(f, f.superDamage, true, f.x, f.y)
                    return
                }
                val fromX = f.x
                val fromY = f.y
                val spot = if (t != null) {
                    val reach = t.radius + f.radius + 0.1f
                    arena.nearestOpen(t.x - cos(t.facing) * reach, t.y - sin(t.facing) * reach, f.radius)
                } else arena.nearestOpen(f.x + dx * s.range, f.y + dy * s.range, f.radius)
                f.x = spot.x; f.y = spot.y
                f.prevX = f.x; f.prevY = f.y
                f.pending.clear()
                if (t != null) {
                    f.facing = atan2(t.y - f.y, t.x - f.x)
                    f.ammo = f.def.ammoMax.toFloat()
                    if (f.def.attack.edgeSeconds > 0f) f.edge = 1f
                }
                events += GameEvent.Blink(f.id, fromX, fromY, f.x, f.y)
                // (He cuts them as he arrives.)
                if (t != null) damage(t, f, f.superDamage, true, t.x, t.y)
            }
            // She becomes the tempest (or, already one, starts it over).
            SuperKind.TEMPEST -> {
                if (!f.inTempest) events += GameEvent.Tempest(f.id, true)
                f.tempestLeft = s.tempestSeconds
                f.tempestTick = Balance.TEMPEST_TICK_SECONDS / 2f
                f.tempestBolt = Balance.TEMPEST_BOLT_SECONDS / 2f
                f.pending.clear()
            }
            // (With nobody on the line it takes nobody: a miss.)
            SuperKind.REALM -> corruptTarget(f, dx, dy)?.let { t -> openRealm(f, t) }
            // Up and over, whatever is in the way: the code goes in when the leaper lands (see [stepLeap]). With nobody
            // on the line he leaps all the same, as far as the super reaches, and comes down on nothing: a miss.
            SuperKind.CORRUPT -> if (!f.rooted) {
                val t = corruptTarget(f, dx, dy)
                f.leapTotal = Balance.LEAP_SECONDS
                f.leapTime = Balance.LEAP_SECONDS
                f.leapFromX = f.x; f.leapFromY = f.y
                f.leapTarget = t?.id ?: -1
                f.pending.clear()
                // (In the Entity's dimension he leaps up into it instead, and comes down under it.)
                val onIt = if (t == null) entityOnLine(f, dx, dy, s.range) else -1f
                if (t != null) {
                    f.facing = atan2(t.y - f.y, t.x - f.x)
                    aimLeap(f, t)
                } else if (onIt >= 0f) {
                    f.leapTarget = ENTITY_TARGET
                    val spot = arena.nearestOpen(f.x + dx * (onIt + 0.8f), f.y + dy * (onIt + 0.8f), f.radius)
                    f.leapToX = spot.x; f.leapToY = spot.y
                } else {
                    val spot = arena.nearestOpen(f.x + dx * s.range, f.y + dy * s.range, f.radius)
                    f.leapToX = spot.x; f.leapToY = spot.y
                }
                events += GameEvent.Leap(f.id)
            }
        }
    }

    private fun spawnProjectile(
        f: Fighter, ang: Float, speed: Float, radius: Float, damage: Int, range: Float,
        pierce: Boolean, isSuper: Boolean, style: ShotStyle, side: Float = 0f, blast: Float = 0f, knock: Float = 0f, share: Float = 0f, hex: Float = 0f, slow: Float = 0f,
        homing: Float = 0f,
    ) {
        val dx = cos(ang)
        val dy = sin(ang)
        // Spawn slightly in front of the fighter, but never inside a wall.
        val off = f.radius * 0.6f
        val sx = f.x + dx * off - dy * side
        val sy = f.y + dy * off + dx * side
        val p = Projectile(f.id, f.team, sx, sy, dx * speed, dy * speed, radius, damage, range - off, pierce, isSuper, style, blast, knock, share, hex, slow, homing)
        if (arena.tileAt(sx, sy).blocksShots) {
            events += GameEvent.WallHit(sx, sy, style)
            return
        }
        projectiles += p
    }

    /** A rocket goes off (or a hammer comes down) at ([x], [y]): every enemy within its blast is hit, once. */
    private fun detonate(p: Projectile, x: Float, y: Float) {
        events += if (p.style == ShotStyle.HAMMER) GameEvent.Quake(x, y, p.blast) else GameEvent.Burst(x, y, p.blast)
        if (phase != Phase.PLAYING) return
        val owner = fighter(p.ownerId)
        for (f in fighters) {
            if (f.team == p.team || !f.alive) continue
            if (hypot(f.x - x, f.y - y) < p.blast + f.radius) damage(f, owner, p.damage, p.isSuper, f.x, f.y)
        }
    }

    /** Where a leaper comes down: open ground right behind its target. */
    private fun aimLeap(f: Fighter, t: Fighter) {
        val reach = t.radius + f.radius
        val spot = arena.nearestOpen(t.x - cos(t.facing) * reach, t.y - sin(t.facing) * reach, f.radius)
        f.leapToX = spot.x; f.leapToY = spot.y
    }

    /** A leaper in the air: it follows its target, so it always lands on their back, and then the code goes in. */
    private fun stepLeap(f: Fighter, dt: Float) {
        val t = fighter(f.leapTarget)
        if (t != null && t.alive) aimLeap(f, t)
        f.leapTime -= dt
        val u = (1f - f.leapTime / f.leapTotal).coerceIn(0f, 1f)
        f.x = f.leapFromX + (f.leapToX - f.leapFromX) * u
        f.y = f.leapFromY + (f.leapToY - f.leapFromY) * u
        if (f.leapTime > 0f) return
        f.leapTime = 0f
        if (f.leapTarget == ENTITY_TARGET) {
            f.leapTarget = -1
            strikeEntity(f, f.attackDamage * maxOf(1, f.def.attack.projectiles) * Balance.UPRISING_SUPER_SHOTS, true, f.x, f.y)
            return
        }
        f.leapTarget = -1
        if (t == null || !t.alive || t.realm != f.realm) return
        f.facing = atan2(t.y - f.y, t.x - f.x)
        t.poisonBy = f.id
        t.poisonDamage = f.superDamage
        t.poisonTick = Balance.POISON_TICK_SECONDS
        t.poisonLeft = if (t.scale > 1f) Balance.POISON_GIANT_SECONDS else Balance.POISON_SECONDS
        t.revealTimer = maxOf(t.revealTimer, 1.5f)
        release(t)
        f.latchedTo = t.id
        stepLatch(f)
        events += GameEvent.Corrupt(f.id, t.id)
    }

    private fun stepDash(f: Fighter, dt: Float) {
        val s = f.def.superSpec
        val step = minOf(dt, f.dashTime)
        f.dashTime -= dt
        val blocked = arena.moveCircle(f.x, f.y, f.radius, f.dashDirX * s.speed * step, f.dashDirY * s.speed * step, tmp)
        val movedX = tmp[0] - f.x
        val movedY = tmp[1] - f.y
        f.x = tmp[0]; f.y = tmp[1]
        f.walkCycle += hypot(movedX, movedY) * 3.2f
        // Hit everyone we ram into, once each (the Entity too, charging in under it).
        if (ENTITY_TARGET !in f.dashHits && onEntity(f.x, f.y, f.radius + 0.25f)) {
            f.dashHits += ENTITY_TARGET
            strikeEntity(f, f.superDamage, true, f.x, f.y)
        }
        for (o in fighters) {
            if (o.team == f.team || !o.alive || o.id in f.dashHits || o.realm != f.realm) continue
            if (hypot(o.x - f.x, o.y - f.y) < f.radius + o.radius + 0.25f) {
                f.dashHits += o.id
                damage(o, f, f.superDamage, true, o.x, o.y)
                if (o.alive && !o.rooted) {
                    arena.moveCircle(o.x, o.y, o.radius, f.dashDirX * 1.6f, f.dashDirY * 1.6f, tmp)
                    o.x = tmp[0]; o.y = tmp[1]
                }
            }
        }
        // Stop when we hit a wall head-on (we barely moved compared to what we tried).
        if (blocked && hypot(movedX, movedY) < s.speed * step * 0.3f) f.dashTime = 0f
        if (f.dashTime <= 0f) f.dashTime = 0f
    }

    // ------------------------------------------------------------------ projectiles

    // What a boss's script uses to act on the world.
    internal val rng get() = random
    internal fun announce(e: GameEvent) { events += e }
    internal fun bossShot(f: Fighter, ang: Float, speed: Float, radius: Float, damage: Int, range: Float, pierce: Boolean, style: ShotStyle) =
        spawnProjectile(f, ang, speed, radius, damage, range, pierce, true, style)

    internal fun startDash(f: Fighter, dx: Float, dy: Float) {
        val s = f.def.superSpec
        f.dashTime = s.range / s.speed
        f.dashDirX = dx
        f.dashDirY = dy
        f.dashHits.clear()
        events += GameEvent.Dash(f.id)
    }

    private fun stepHazards(dt: Float) {
        var i = 0
        while (i < hazards.size) {
            val h = hazards[i]
            h.age += dt
            if (h.age < h.delay) { i++; continue }
            hazards.removeAt(i)
            events += GameEvent.Blast(h.x, h.y, h.radius, h.kind)
            val owner = fighter(h.ownerId)
            // (A fighter's own blast that covers part of the Entity's shadow hits it.)
            if (owner != null && h.ownerId >= 0 && onEntity(h.x, h.y, h.radius)) strikeEntity(owner, h.damage, true, h.x, h.y)
            for (f in fighters) {
                if (f.team == h.team || !f.alive) continue
                // Caught if the middle of the fighter is inside the mark (a little is forgiven at the very edge).
                if (hypot(f.x - h.x, f.y - h.y) < h.radius + f.radius * 0.3f) damage(f, owner, h.damage, true, f.x, f.y, lethal = h.lethal)
            }
            // A brew gives back what it takes: to whoever threw it, wherever they are, and to any ally in the circle.
            if (h.kind == HazardKind.BREW && phase == Phase.PLAYING) for (f in fighters) {
                if (f.team != h.team || !f.alive) continue
                if (f !== owner && hypot(f.x - h.x, f.y - h.y) >= h.radius + f.radius * 0.3f) continue
                val gain = minOf(h.damage, f.maxHp - f.hp)
                if (gain <= 0) continue
                f.hp += gain
                events += GameEvent.Heal(f.id, gain, f.x, f.y)
            }
        }
    }

    private fun stepProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.prevX = p.x
            p.prevY = p.y
            p.age += dt
            if (p.homing > 0f) steer(p, dt)
            val speed = hypot(p.vx, p.vy)
            val total = speed * dt
            val sub = maxOf(1, (total / 0.15f).toInt() + 1)
            val sdx = p.vx * dt / sub
            val sdy = p.vy * dt / sub
            // (A shot only meets fighters in the realm its owner is in: the arena, for nearly everyone.)
            val realm = fighter(p.ownerId)?.realm ?: -1
            loop@ for (s in 0 until sub) {
                p.x += sdx
                p.y += sdy
                p.rangeLeft -= total / sub
                if (arena.tileAt(p.x, p.y).blocksShots) {
                    p.alive = false
                    events += GameEvent.WallHit(p.x - sdx, p.y - sdy, p.style)
                    if (p.blast > 0f) detonate(p, p.x - sdx, p.y - sdy)
                    if (arena.tileAt(p.x, p.y) == Tile.CRATE) damageCrate(kotlin.math.floor(p.x).toInt(), kotlin.math.floor(p.y).toInt(), p.damage)
                    break@loop
                }
                // (In the Entity's dimension, a shot that reaches the shadow under it hits it.)
                if (ENTITY_TARGET !in p.hit && onEntity(p.x, p.y, p.radius)) {
                    p.hit += ENTITY_TARGET
                    fighter(p.ownerId)?.let { o ->
                        strikeEntity(o, if (p.share > 0f) p.damage * Balance.SHARE_GIANT_HITS else p.damage, p.isSuper, p.x, p.y)
                        if (p.hex > 0f) uprising?.hexes?.add(Hex(p.ownerId, p.damage, Balance.HEX_TICK_SECONDS, p.hex - Balance.HEX_TICK_SECONDS / 2f))
                    }
                    if (!p.pierce || p.blast > 0f) { p.alive = false; break@loop }
                }
                if (phase == Phase.PLAYING) for (f in fighters) {
                    if (f.team == p.team || !f.alive || f.id in p.hit || f.realm != realm) continue
                    val rr = f.radius + p.radius
                    if ((f.x - p.x) * (f.x - p.x) + (f.y - p.y) * (f.y - p.y) < rr * rr) {
                        p.hit += f.id
                        if (p.blast > 0f) { detonate(p, p.x, p.y); p.alive = false; break@loop }
                        val owner = fighter(p.ownerId)
                        damage(f, owner, shotDamage(p, f), p.isSuper, p.x, p.y)
                        // The hit was the first bite of a hex; the rest follow.
                        if (p.hex > 0f && f.alive && f.shield <= 0f && !f.isLeaping) {
                            f.hexes += Hex(p.ownerId, p.damage, Balance.HEX_TICK_SECONDS, p.hex - Balance.HEX_TICK_SECONDS / 2f)
                        }
                        if (p.slow > 0f && f.alive && f.shield <= 0f && !f.isLeaping) f.slowLeft = maxOf(f.slowLeft, p.slow)
                        if (p.knock > 0f && f.alive && !f.rooted && speed > 0f) {
                            arena.moveCircle(f.x, f.y, f.radius, p.vx / speed * p.knock, p.vy / speed * p.knock, tmp)
                            f.x = tmp[0]; f.y = tmp[1]
                        }
                        if (!p.pierce) { p.alive = false; break@loop }
                    }
                }
                if (p.rangeLeft <= 0f) {
                    if (p.blast > 0f) detonate(p, p.x, p.y)
                    p.alive = false; break@loop
                }
            }
            if (!p.alive) it.remove()
        }
    }

    /**
     * A homing shot turns toward the nearest enemy it can see within [Balance.ARROW_SEEK_RADIUS], and ahead of it (it
     * never doubles back), at most [Projectile.homing] radians a second. Its speed stays the same.
     */
    private fun steer(p: Projectile, dt: Float) {
        val speed = hypot(p.vx, p.vy)
        if (speed <= 0f) return
        val hx = p.vx / speed
        val hy = p.vy / speed
        val realm = fighter(p.ownerId)?.realm ?: -1
        var best: Fighter? = null
        var bestD = Balance.ARROW_SEEK_RADIUS
        for (f in fighters) {
            if (f.team == p.team || !f.alive || f.id in p.hit || f.realm != realm || f.isLeaping || !isVisibleTo(f, p.team)) continue
            val ox = f.x - p.x
            val oy = f.y - p.y
            val d = hypot(ox, oy)
            if (d >= bestD || ox * hx + oy * hy <= 0f) continue
            best = f; bestD = d
        }
        val tx: Float; val ty: Float
        if (best != null) { tx = best.x; ty = best.y }
        else {
            // (In the Entity's dimension it turns toward the Entity, over the middle of its shadow.)
            val u = uprising
            if (u == null || u.phase != UprisingPhase.SKY || ENTITY_TARGET in p.hit) return
            val ox = u.ex - p.x; val oy = u.ey - p.y
            if (hypot(ox, oy) > Balance.ARROW_SEEK_RADIUS + Balance.UPRISING_ENTITY_RADIUS || ox * hx + oy * hy <= 0f) return
            tx = u.ex; ty = u.ey
        }
        val want = atan2(ty - p.y, tx - p.x)
        val now = atan2(p.vy, p.vx)
        var turn = want - now
        while (turn > Math.PI) turn -= (2 * Math.PI).toFloat()
        while (turn < -Math.PI) turn += (2 * Math.PI).toFloat()
        val ang = now + turn.coerceIn(-p.homing * dt, p.homing * dt)
        p.vx = cos(ang) * speed
        p.vy = sin(ang) * speed
    }

    // ------------------------------------------------------------------ damage & respawn

    /** What [p] does to [f]: its own damage, or a share of the health [f] has left when the shot takes one. */
    private fun shotDamage(p: Projectile, f: Fighter): Int = when {
        p.share <= 0f -> p.damage
        f.scale > 1f -> p.damage * Balance.SHARE_GIANT_HITS
        else -> maxOf(p.damage, (f.hp * p.share).toInt())
    }

    /** [lethal] false: the hit can take the target down to its last point of health, but never knocks it out. */
    private fun damage(target: Fighter, source: Fighter?, wanted: Int, isSuper: Boolean, x: Float, y: Float, lethal: Boolean = true, charge: Boolean = true, chargeShare: Float = 1f) {
        // (Nothing crosses from the arena into a realm, or out of one; and in the Entity's dimension nobody hurts anybody
        // but the Entity.)
        if (source != null && source.realm != target.realm) return
        if (source != null && inSky && source.team == target.team) return
        // (In a match's uprising nothing knocks anyone out: only in the Entity's own fight.)
        val lethal = lethal && !(inSky && uprising?.boss != true)
        // (A captive that may fight back in there hits softer.)
        val wantedHere = if (source != null && source.captive) (wanted * Balance.REALM_CAPTIVE_DAMAGE).toInt() else wanted
        val amount = if (lethal) wantedHere else minOf(wantedHere, target.hp - 1)
        // (Nothing reaches a fighter in the middle of a leap.)
        if (target.shield > 0f || target.isLeaping || amount <= 0) {
            events += GameEvent.Blocked(x, y)
            return
        }
        val dealt = minOf(amount, target.hp)
        target.hp -= dealt
        target.sinceDamaged = 0f
        target.hitFlash = 0.12f
        target.revealTimer = maxOf(target.revealTimer, 1.0f)
        target.lastAttackerId = source?.id ?: -1
        if (source != null) {
            source.damageDealt += dealt
            if (!isSuper && charge) chargeFrom(source, chargeShare)
        }
        events += GameEvent.Hit(target.id, source?.id ?: -1, amount, x, y, isSuper)
        if (target.hp <= 0) knockOut(target, source)
    }

    /** A hit by [source] charges its super (and its hyper) by a hit's worth, times [chargeShare]. */
    private fun chargeFrom(source: Fighter, chargeShare: Float) {
        val before = source.superReady
        val faster = (if (source.hyperActive) source.def.hyper?.superCharge ?: 1f else 1f) * (if (source.realm == source.id) Balance.REALM_CHARGE else 1f)
        source.superCharge = (source.superCharge + source.def.superChargePerHit * faster * chargeShare).coerceAtMost(1f)
        if (!before && source.superReady) events += GameEvent.SuperReady(source.id)
        // Hits charge the hyper even while one is running, so the next can follow straight on.
        if (source.hyperCharge < 1f) {
            source.hyperCharge = (source.hyperCharge + source.def.superChargePerHit * chargeShare * Balance.HYPER_CHARGE_RATE * (source.def.hyper?.charge ?: 1f)).coerceAtMost(1f)
            if (source.hyperCharge >= 1f) events += GameEvent.HyperReady(source.id)
        }
    }

    private fun knockOut(victim: Fighter, killer: Fighter?) {
        victim.alive = false
        victim.hp = 0
        victim.deaths++
        victim.respawnTimer = Balance.RESPAWN_SECONDS
        victim.pending.clear()
        victim.dashTime = 0f
        victim.leapTime = 0f
        victim.hexes.clear()
        victim.slowLeft = 0f
        planted.removeAll { it.ownerId == victim.id }
        if (victim.inRealm) closeRealm(victim.realm)
        victim.latchedTo = -1
        release(victim)
        cure(victim)
        if (victim.hyperActive) endHyper(victim)
        if (victim.inTempest) endTempest(victim)
        // The super and hyper charges are kept: whatever was charged is still there after the respawn.
        events += GameEvent.Ko(killer?.id ?: -1, victim.id, victim.x, victim.y)
        killer?.let { it.kos++ }
        // (A fighter the Entity had corrupted is its own again when it goes down.)
        if (victim.corruptLeft > 0f) uncorrupt(victim)
        run {
            val scoringTeam = 1 - victim.team
            score[scoringTeam]++
            val target = if (scoringTeam == 0) rules.koTarget else rules.enemyKoTarget
            if (phase == Phase.PLAYING && score[scoringTeam] >= target) end(scoringTeam)
        }
    }

    private fun damageCrate(tx: Int, ty: Int, amount: Int) {
        val key = ty * arena.width + tx
        val hp = (crateHp[key] ?: return) - amount
        if (hp > 0) {
            crateHp[key] = hp
            events += GameEvent.CrateHit(tx, ty)
            return
        }
        crateHp.remove(key)
        arena[tx, ty] = Tile.FLOOR
        pickups += Pickup(tx + 0.5f, ty + 0.5f)
        events += GameEvent.CrateBroken(tx, ty)
    }

    private fun stepPickups(dt: Float) {
        if (pickups.isEmpty()) return
        for (p in pickups) {
            if (!p.alive) continue
            p.age += dt
            if (p.age < 0.4f) continue // let it pop out before it can be grabbed
            for (f in fighters) {
                if (!f.alive || hypot(f.x - p.x, f.y - p.y) > f.radius + 0.4f) continue
                p.alive = false
                f.cells++
                val bonus = (f.baseMaxHp * Balance.CELL_HEALTH_BONUS).toInt()
                f.maxHp += bonus
                f.hp += bonus
                events += GameEvent.CellPicked(f.id, p.x, p.y)
                break
            }
        }
        pickups.removeAll { !it.alive }
    }

    private fun respawn(f: Fighter) {
        placeAtSpawn(f)
        f.alive = true
        f.hp = f.maxHp
        f.ammo = f.def.ammoMax.toFloat()
        f.shield = Balance.SPAWN_SHIELD_SECONDS
        f.sinceDamaged = 99f
        f.concealTime = 0f
        events += GameEvent.Spawned(f.id)
    }

    private fun placeAtSpawn(f: Fighter) {
        // (A map with no team spawns, or more teams than it has, uses its free-for-all spawns.)
        val list = arena.spawns.getOrNull(f.team)?.takeIf { it.isNotEmpty() } ?: arena.ffaSpawns.ifEmpty { arena.spawns.first { it.isNotEmpty() } }
        val s = list[(f.spawnIndex + (if (list === arena.ffaSpawns) f.id else 0)) % list.size]
        f.x = s.x; f.y = s.y; f.prevX = s.x; f.prevY = s.y
        f.vx = 0f; f.vy = 0f
        // Face the middle of the arena.
        f.facing = kotlin.math.atan2(arena.height / 2f - s.y, arena.width / 2f - s.x)
    }

    // ------------------------------------------------------------------ queries (used by AI, HUD, auto-aim)

    /** Fair visibility: what [team] can currently see of [target]. Shared team vision. */
    fun isVisibleTo(target: Fighter, team: Int): Boolean {
        if (!target.alive) return false
        if (target.team == team) return true
        if (target.concealTime < Balance.THICKET_CONCEAL_SECONDS || target.revealTimer > 0f) return true
        return team in target.spottedBy.indices && target.spottedBy[team] > 0f
    }

    /**
     * Auto-aim target: the nearest visible enemy, preferring one with a clear shot when several are close.
     * Returns null if nobody visible is within [maxRange].
     */
    fun nearestVisibleEnemy(f: Fighter, maxRange: Float): Fighter? = fighters
        .filter { it.team != f.team && it.realm == f.realm && isVisibleTo(it, f.team) && hypot(it.x - f.x, it.y - f.y) <= maxRange }
        .minByOrNull { autoAimScore(f, it) }

    /** Lower is a better auto-aim target: close, hittable, hurt, and not behind a spawn shield. */
    private fun autoAimScore(f: Fighter, e: Fighter): Float {
        var s = hypot(e.x - f.x, e.y - f.y)
        if (!arena.shotClear(f.x, f.y, e.x, e.y)) s += 6f
        if (e.shield > 0f) s += 4f
        s -= (1f - e.hpFraction) * 2f
        return s
    }

    /**
     * Auto-aim for crates: the nearest Spark Crate within [maxRange] that a shot from [f] would actually reach
     * (nothing but the crate itself in the way). Returns its tile key (y * width + x), or -1.
     */
    fun nearestHittableCrate(f: Fighter, maxRange: Float): Int {
        var best = -1
        var bestD = maxRange
        for (key in crateHp.keys) {
            val cx = key % arena.width + 0.5f
            val cy = key / arena.width + 0.5f
            val d = hypot(cx - f.x, cy - f.y)
            if (d >= bestD) continue
            val hit = arena.shotBlockedAt(f.x, f.y, cx, cy)
            if (hit < 0f) continue
            // The first thing the shot runs into has to be this crate.
            val hx = f.x + (cx - f.x) / d * (hit + 0.02f)
            val hy = f.y + (cy - f.y) / d * (hit + 0.02f)
            if (kotlin.math.floor(hx).toInt() == key % arena.width && kotlin.math.floor(hy).toInt() == key / arena.width) { best = key; bestD = d }
        }
        return best
    }

    /**
     * Where to aim so a projectile of [speed] meets [target] if it keeps its current velocity.
     * Writes the (unnormalised) aim direction into [out].
     */
    fun leadAim(shooter: Fighter, target: Fighter, speed: Float, out: FloatArray) {
        val rx = target.x - shooter.x
        val ry = target.y - shooter.y
        val vx = target.vx
        val vy = target.vy
        // Solve |r + v t| = s t  ->  (v.v - s^2) t^2 + 2 (r.v) t + r.r = 0
        val a = vx * vx + vy * vy - speed * speed
        val b = 2f * (rx * vx + ry * vy)
        val c = rx * rx + ry * ry
        var t = -1f
        if (kotlin.math.abs(a) < 1e-4f) {
            if (kotlin.math.abs(b) > 1e-4f) t = -c / b
        } else {
            val disc = b * b - 4 * a * c
            if (disc >= 0f) {
                val sq = kotlin.math.sqrt(disc)
                val t1 = (-b - sq) / (2 * a)
                val t2 = (-b + sq) / (2 * a)
                t = if (t1 > 0f && (t2 <= 0f || t1 < t2)) t1 else if (t2 > 0f) t2 else -1f
            }
        }
        if (t <= 0f || t > 2f) { out[0] = rx; out[1] = ry; return }
        out[0] = rx + vx * t
        out[1] = ry + vy * t
    }

    /** Most valuable player = highest contribution score across both teams. */
    fun mvp(): Fighter? = fighters.maxByOrNull { it.kos * 3f + it.damageDealt / 1600f - it.deaths * 0.5f }
}

/** Stands for the Entity in a shot's or a charge's list of who it has hit, and as a leap's target (its dimension). */
const val ENTITY_TARGET = -100

/** The Entity's moves in its world, in the order it makes them, round and round. */
enum class EntityMove { STRIKES, RING, SWEEP, FLARE, SPOKES, RAIN, GRASP, PULSE, CHASE, DASH, SPIN }
// (Its sword spin is its main attack, the user's: it comes round most often.)
private val ENTITY_MOVES = listOf(
    EntityMove.SPIN, EntityMove.STRIKES, EntityMove.GRASP, EntityMove.DASH, EntityMove.FLARE,
    EntityMove.SPIN, EntityMove.RING, EntityMove.CHASE, EntityMove.SPOKES, EntityMove.DASH,
    EntityMove.SPIN, EntityMove.RAIN, EntityMove.FLARE, EntityMove.SWEEP, EntityMove.GRASP,
    EntityMove.SPIN, EntityMove.PULSE, EntityMove.DASH, EntityMove.STRIKES, EntityMove.CHASE,
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
