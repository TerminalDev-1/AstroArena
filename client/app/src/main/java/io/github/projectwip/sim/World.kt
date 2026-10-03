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
 * Match rules.
 *  - Teams ("Knockout Rush"): two teams, respawns, first to [koTarget] KOs or most when time runs out.
 *  - Free-for-all ("Last Spark"): every fighter is its own team, one life, a shrinking Static Storm,
 *    last fighter standing wins; everyone gets a finishing place.
 */
data class MatchRules(
    val freeForAll: Boolean = false,
    val koTarget: Int = Balance.KO_TARGET,
    val durationSeconds: Float = Balance.MATCH_SECONDS,
    val countdownSeconds: Float = 3f,
    /** KOs the second team (index 1) needs to win, when that differs from [koTarget]. */
    val enemyKoTarget: Int = koTarget,
    /** Boss Mode: one giant against the player; shown differently on the HUD. */
    val boss: Boolean = false,
    /** Training Area: no clock and no score to reach, so it only ends when the player leaves. */
    val practice: Boolean = false,
) {
    val respawn get() = !freeForAll

    companion object {
        fun knockoutRush() = MatchRules()
        fun lastSpark() = MatchRules(freeForAll = true, durationSeconds = Float.MAX_VALUE)
        /** The player wins by knocking out the boss, and has unlimited lives to do it. */
        fun bossMode() = MatchRules(koTarget = 1, enemyKoTarget = Int.MAX_VALUE, durationSeconds = Float.MAX_VALUE, boss = true)
        fun training() = MatchRules(koTarget = Int.MAX_VALUE, enemyKoTarget = Int.MAX_VALUE, durationSeconds = Float.MAX_VALUE, practice = true)
    }
}

/** The Static Storm: a circle that waits, then shrinks; standing outside hurts more the longer it runs. */
class Storm(val cx: Float, val cy: Float, val startRadius: Float) {
    var radius = startRadius
        private set
    val finalRadius = Balance.STORM_FINAL_RADIUS
    var elapsed = 0f
        private set

    fun update(dt: Float) {
        elapsed += dt
        val t = ((elapsed - Balance.STORM_DELAY_SECONDS) / Balance.STORM_SHRINK_SECONDS).coerceIn(0f, 1f)
        radius = startRadius + (finalRadius - startRadius) * t
    }

    fun contains(x: Float, y: Float) = hypot(x - cx, y - cy) <= radius
    fun distanceToEdge(x: Float, y: Float) = radius - hypot(x - cx, y - cy)

    /** Damage per second as a fraction of max health. */
    fun damageFraction(): Float {
        val shrinking = (elapsed - Balance.STORM_DELAY_SECONDS).coerceAtLeast(0f)
        return Balance.STORM_DAMAGE_BASE + Balance.STORM_DAMAGE_GROWTH * shrinking
    }
}

/**
 * The authoritative simulation. Advanced in fixed steps by [step]; has no Android dependencies so
 * it can run in unit tests, headless bot-vs-bot balance runs, or a future server.
 */
class World(
    val arena: Arena,
    val fighters: List<Fighter>,
    val rules: MatchRules = MatchRules(),
    private val random: Random = Random.Default,
) {
    val projectiles = ArrayList<Projectile>(64)
    val pickups = ArrayList<Pickup>()
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

    /** Free-for-all only. */
    val storm: Storm? = if (rules.freeForAll)
        Storm(arena.width / 2f, arena.height / 2f, hypot(arena.width / 2f, arena.height / 2f) + 1f) else null

    val aliveCount get() = fighters.count { !it.eliminated }

    init {
        val teams = (fighters.maxOfOrNull { it.team } ?: 0) + 1
        for (f in fighters) f.spottedBy = FloatArray(teams)
        for (y in 0 until arena.height) for (x in 0 until arena.width) {
            if (arena[x, y] == Tile.CRATE) crateHp[y * arena.width + x] = Balance.CRATE_HP
        }
        if (rules.freeForAll) {
            fighters.forEachIndexed { i, f -> f.spawnIndex = i % arena.ffaSpawns.size; placeAtSpawn(f) }
        } else {
            for (team in 0..1) {
                fighters.filter { it.team == team }.forEachIndexed { i, f ->
                    f.spawnIndex = i % arena.spawns[team].size
                    placeAtSpawn(f)
                }
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

        for (f in fighters) stepFighter(f, dt)
        stepConcealment(dt)
        stepProjectiles(dt)
        stepPickups(dt)
        storm?.let { stepStorm(it, dt) }

        timeLeft -= dt
        if (phase == Phase.PLAYING && timeLeft <= 0f) {
            timeLeft = 0f
            end(if (score[0] > score[1]) 0 else if (score[1] > score[0]) 1 else -1)
        }
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
        if (f.eliminated) { c.attack = false; c.superAttack = false; return }
        if (!f.alive) {
            f.respawnTimer -= dt
            if (f.respawnTimer <= 0f) respawn(f)
            c.attack = false; c.superAttack = false
            return
        }

        f.sinceDamaged += dt
        f.sinceAttack += dt
        f.revealTimer = (f.revealTimer - dt).coerceAtLeast(0f)
        f.hitFlash = (f.hitFlash - dt).coerceAtLeast(0f)
        f.shield = (f.shield - dt).coerceAtLeast(0f)
        f.attackCooldown = (f.attackCooldown - dt).coerceAtLeast(0f)

        // --- movement
        if (f.isDashing) {
            stepDash(f, dt)
        } else {
            var mx = if (f.rooted) 0f else c.moveX
            var my = if (f.rooted) 0f else c.moveY
            val len = hypot(mx, my)
            if (len > 1f) { mx /= len; my /= len }
            val speed = f.def.moveSpeed
            val blocked = arena.moveCircle(f.x, f.y, f.radius, mx * speed * dt, my * speed * dt, tmp)
            f.x = tmp[0]; f.y = tmp[1]
            if (len > 0.05f) f.walkCycle += hypot(f.x - f.prevX, f.y - f.prevY) * 3.2f
            if (!blocked && len < 0.05f) f.walkCycle = 0f
        }
        f.vx = (f.x - f.prevX) / dt
        f.vy = (f.y - f.prevY) / dt

        // --- facing
        if (c.aiming && (c.aimX != 0f || c.aimY != 0f)) f.facing = atan2(c.aimY, c.aimX)
        else if (hypot(c.moveX, c.moveY) > 0.1f && !f.isDashing) f.facing = atan2(c.moveY, c.moveX)

        // --- main attack
        if (c.attack && f.ammo >= 1f && f.attackCooldown <= 0f && f.pending.isEmpty() && !f.isDashing) {
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
                else -> f.pending += PendingShot(0f, dx, dy)
            }
            events += GameEvent.Shot(f.id, false, f.x, f.y, dx, dy)
        }
        c.attack = false

        // --- pending (burst) shots
        if (f.pending.isNotEmpty()) {
            val it = f.pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.delay -= dt
                if (p.delay <= 0f) {
                    fireMain(f, p.dirX, p.dirY)
                    it.remove()
                }
            }
        }

        // --- super
        if (c.superAttack && f.superReady && !f.isDashing) {
            val (dx, dy) = aimDirection(f)
            f.facing = atan2(dy, dx)
            fireSuper(f, dx, dy)
            f.superCharge = 0f
            f.sinceAttack = 0f
            f.revealTimer = maxOf(f.revealTimer, 1.5f)
            events += GameEvent.Shot(f.id, true, f.x, f.y, dx, dy)
        }
        c.superAttack = false

        // --- reload (paused while a burst is still firing)
        if (f.ammo < f.def.ammoMax && f.pending.isEmpty()) {
            f.ammo = (f.ammo + dt / f.def.reloadSeconds).coerceAtMost(f.def.ammoMax.toFloat())
        }

        // --- regeneration when out of combat
        if (f.sinceDamaged > Balance.REGEN_DELAY_SECONDS && f.sinceAttack > Balance.REGEN_DELAY_SECONDS && f.hp < f.maxHp) {
            f.hp = (f.hp + (f.maxHp * Balance.REGEN_FRACTION_PER_SECOND * dt).toInt().coerceAtLeast(1)).coerceAtMost(f.maxHp)
        }
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

    private fun fireMain(f: Fighter, dx: Float, dy: Float) {
        val a = f.def.attack
        val style = when (a.shape) {
            AttackShape.BURST -> ShotStyle.SPARK
            AttackShape.SPREAD -> ShotStyle.PELLET
            AttackShape.LANCE -> ShotStyle.PRISM
        }
        val baseAng = atan2(dy, dx)
        val n = if (a.shape == AttackShape.SPREAD) a.projectiles else 1
        val spread = Math.toRadians(a.spreadDegrees.toDouble()).toFloat()
        for (i in 0 until n) {
            val ang = if (n == 1) baseAng else baseAng - spread / 2 + spread * i / (n - 1)
            spawnProjectile(f, ang, a.speed, a.radius, f.attackDamage, a.range, a.pierce, false, style)
        }
    }

    private fun fireSuper(f: Fighter, dx: Float, dy: Float) {
        val s = f.def.superSpec
        val baseAng = atan2(dy, dx)
        when (s.kind) {
            SuperKind.VOLLEY -> {
                val spread = Math.toRadians(s.spreadDegrees.toDouble()).toFloat()
                for (i in 0 until s.projectiles) {
                    val ang = baseAng - spread / 2 + spread * i / (s.projectiles - 1)
                    spawnProjectile(f, ang, s.speed, s.radius, f.superDamage, s.range, false, true, ShotStyle.VOLLEY)
                }
            }
            SuperKind.PIERCE -> spawnProjectile(f, baseAng, s.speed, s.radius, f.superDamage, s.range, true, true, ShotStyle.LANCE)
            SuperKind.RAM -> if (!f.rooted) {
                f.dashTime = s.range / s.speed
                f.dashDirX = dx
                f.dashDirY = dy
                f.dashHits.clear()
                f.pending.clear()
                events += GameEvent.Dash(f.id)
            }
        }
    }

    private fun spawnProjectile(
        f: Fighter, ang: Float, speed: Float, radius: Float, damage: Int, range: Float,
        pierce: Boolean, isSuper: Boolean, style: ShotStyle,
    ) {
        val dx = cos(ang)
        val dy = sin(ang)
        // Spawn slightly in front of the fighter, but never inside a wall.
        val off = f.radius * 0.6f
        val sx = f.x + dx * off
        val sy = f.y + dy * off
        val p = Projectile(f.id, f.team, sx, sy, dx * speed, dy * speed, radius, damage, range - off, pierce, isSuper, style)
        if (arena.tileAt(sx, sy).blocksShots) {
            events += GameEvent.WallHit(sx, sy, style)
            return
        }
        projectiles += p
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
        // Hit everyone we ram into, once each.
        for (o in fighters) {
            if (o.team == f.team || !o.alive || o.id in f.dashHits) continue
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

    private fun stepProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.prevX = p.x
            p.prevY = p.y
            p.age += dt
            val speed = hypot(p.vx, p.vy)
            val total = speed * dt
            val sub = maxOf(1, (total / 0.15f).toInt() + 1)
            val sdx = p.vx * dt / sub
            val sdy = p.vy * dt / sub
            loop@ for (s in 0 until sub) {
                p.x += sdx
                p.y += sdy
                p.rangeLeft -= total / sub
                if (arena.tileAt(p.x, p.y).blocksShots) {
                    p.alive = false
                    events += GameEvent.WallHit(p.x - sdx, p.y - sdy, p.style)
                    if (arena.tileAt(p.x, p.y) == Tile.CRATE) damageCrate(kotlin.math.floor(p.x).toInt(), kotlin.math.floor(p.y).toInt(), p.damage)
                    break@loop
                }
                if (phase == Phase.PLAYING) for (f in fighters) {
                    if (f.team == p.team || !f.alive || f.id in p.hit) continue
                    val rr = f.radius + p.radius
                    if ((f.x - p.x) * (f.x - p.x) + (f.y - p.y) * (f.y - p.y) < rr * rr) {
                        p.hit += f.id
                        val owner = fighter(p.ownerId)
                        damage(f, owner, p.damage, p.isSuper, p.x, p.y)
                        if (!p.pierce) { p.alive = false; break@loop }
                    }
                }
                if (p.rangeLeft <= 0f) { p.alive = false; break@loop }
            }
            if (!p.alive) it.remove()
        }
    }

    // ------------------------------------------------------------------ damage & respawn

    private fun damage(target: Fighter, source: Fighter?, amount: Int, isSuper: Boolean, x: Float, y: Float) {
        if (target.shield > 0f) {
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
            if (!isSuper) {
                val before = source.superReady
                source.superCharge = (source.superCharge + source.def.superChargePerHit).coerceAtMost(1f)
                if (!before && source.superReady) events += GameEvent.SuperReady(source.id)
            }
        }
        events += GameEvent.Hit(target.id, source?.id ?: -1, amount, x, y, isSuper)
        if (target.hp <= 0) knockOut(target, source)
    }

    private fun knockOut(victim: Fighter, killer: Fighter?) {
        victim.alive = false
        victim.hp = 0
        victim.deaths++
        victim.respawnTimer = Balance.RESPAWN_SECONDS
        victim.pending.clear()
        victim.dashTime = 0f
        victim.superCharge *= 0.5f
        events += GameEvent.Ko(killer?.id ?: -1, victim.id, victim.x, victim.y)
        killer?.let { it.kos++ }
        if (rules.freeForAll) {
            // Drop collected Power Cells (at least one) around the knockout spot.
            val drops = maxOf(1, victim.cells)
            for (i in 0 until drops) {
                val a = i * 2.4f
                val d = if (drops == 1) 0f else 0.6f
                val spot = arena.nearestOpen(victim.x + kotlin.math.cos(a) * d, victim.y + kotlin.math.sin(a) * d, 0.3f)
                pickups += Pickup(spot.x, spot.y)
            }
            victim.eliminated = true
            victim.placement = aliveCount + 1
            events += GameEvent.Eliminated(victim.id, victim.placement)
            if (phase == Phase.PLAYING && aliveCount <= 1) {
                val winner = fighters.firstOrNull { !it.eliminated }
                winner?.placement = 1
                end(winner?.team ?: -1)
            }
        } else {
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

    private fun stepStorm(st: Storm, dt: Float) {
        st.update(dt)
        for (f in fighters) {
            if (!f.alive || st.contains(f.x, f.y)) { f.stormTick = 0f; continue }
            f.stormTick -= dt
            if (f.stormTick > 0f) continue
            f.stormTick = 0.5f
            val dmg = (f.maxHp * st.damageFraction() * 0.5f).toInt().coerceAtLeast(1)
            val dealt = minOf(dmg, f.hp)
            f.hp -= dealt
            f.sinceDamaged = 0f
            f.hitFlash = 0.12f
            events += GameEvent.StormHit(f.id, dmg, f.x, f.y)
            if (f.hp <= 0) knockOut(f, null)
        }
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
        val s = if (rules.freeForAll) arena.ffaSpawns[f.spawnIndex] else arena.spawns[f.team][f.spawnIndex]
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
        .filter { it.team != f.team && isVisibleTo(it, f.team) && hypot(it.x - f.x, it.y - f.y) <= maxRange }
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
