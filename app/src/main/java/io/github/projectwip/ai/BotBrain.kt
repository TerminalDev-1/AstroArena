package io.github.projectwip.ai

import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.SuperKind
import io.github.projectwip.sim.Fighter
import io.github.projectwip.sim.Phase
import io.github.projectwip.sim.Projectile
import io.github.projectwip.sim.World
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One bot's mind. Layers, from slow to fast:
 *  1. think()   — every [BotProfile.thinkInterval]: perception, target choice, intent, goal, path.
 *  2. steer()   — every tick: follow the path, strafe, avoid teammates, wander.
 *  3. dodge()   — every tick: react to incoming projectiles.
 *  4. combat()  — every tick: aim (with lead + error) and decide whether to shoot / use super.
 *
 * The brain only ever writes the fighter's [io.github.projectwip.sim.Control] — the same input a human uses.
 * New behaviours slot in as additional intents or as extra terms in the target/goal scoring.
 */
class BotBrain(
    private val me: Fighter,
    val profile: BotProfile,
    private val world: World,
    private val pathfinder: Pathfinder,
    private val rng: Random,
) {
    enum class Intent { ENGAGE, RETREAT, ADVANCE }

    var intent = Intent.ADVANCE
        private set
    var target: Fighter? = null
        private set

    private var seenTime = 0f
    private var thinkTimer = rng.nextFloat() * 0.3f
    private var fireTimer = 0.5f + rng.nextFloat()
    private var superDelay = -1f

    private var goalX = me.x
    private var goalY = me.y
    private var path: List<FloatArray> = emptyList()
    private var pathIndex = 0
    private var pathGoalX = -99f
    private var pathGoalY = -99f
    private var repathTimer = 0f

    private var strafeSign = if (rng.nextBoolean()) 1f else -1f
    private var strafeTimer = 1f

    private var dodgeX = 0f
    private var dodgeY = 0f
    private var dodgeTimer = 0f
    private val judged = HashSet<Projectile>()

    private var lastKnownX = 0f
    private var lastKnownY = 0f
    private var lastKnownAge = 99f
    private var patrolX = world.arena.width / 2f
    private var patrolY = world.arena.height / 2f
    private var wanderAngle = rng.nextFloat() * 6.28f

    private var stuckTimer = 0f
    private var stuckCheckX = me.x
    private var stuckCheckY = me.y

    private val arena = world.arena

    fun update(dt: Float) {
        val c = me.control
        c.moveX = 0f; c.moveY = 0f; c.aiming = false
        if (!me.alive || world.phase != Phase.PLAYING) {
            target = null; seenTime = 0f; path = emptyList(); superDelay = -1f
            return
        }
        thinkTimer -= dt
        fireTimer -= dt
        dodgeTimer -= dt
        repathTimer -= dt
        strafeTimer -= dt
        lastKnownAge += dt

        target?.let { t ->
            if (t.alive && world.isVisibleTo(t, me.team)) {
                seenTime += dt
                lastKnownX = t.x; lastKnownY = t.y; lastKnownAge = 0f
            } else {
                seenTime = 0f
                if (!t.alive) target = null
            }
        }
        if (strafeTimer <= 0f) {
            strafeSign = -strafeSign
            strafeTimer = 0.7f + rng.nextFloat() * 1.1f
        }
        if (thinkTimer <= 0f) {
            thinkTimer = profile.thinkInterval * (0.8f + rng.nextFloat() * 0.4f)
            think()
        }
        steer(dt)
        dodge()
        combat(dt)
    }

    // ------------------------------------------------------------------ decide

    private fun think() {
        val enemies = world.fighters.filter { it.team != me.team && it.alive && world.isVisibleTo(it, me.team) }

        // Target choice: nearest by default; smarter bots weigh health, line of fire and spawn shields.
        val best = enemies.minByOrNull { e ->
            var s = dist(e)
            if (profile.focusWeakest) s += e.hpFraction * 5f
            if (e === target) s -= 1.5f
            if (profile.shotDiscipline && !arena.shotClear(me.x, me.y, e.x, e.y)) s += 3f
            if (profile.shotDiscipline && e.shield > 0f) s += 6f
            s
        }
        if (best !== target) {
            target = best
            seenTime = 0f
        }

        val nearestEnemy = enemies.minOfOrNull { dist(it) } ?: 99f
        val retreatExit = minOf(0.9f, profile.retreatBelow + 0.4f)
        intent = when {
            profile.retreatBelow > 0f && me.hpFraction < profile.retreatBelow && nearestEnemy < 8f -> Intent.RETREAT
            intent == Intent.RETREAT && me.hpFraction < retreatExit && nearestEnemy > 2.5f -> Intent.RETREAT
            target != null -> Intent.ENGAGE
            else -> Intent.ADVANCE
        }

        when (intent) {
            Intent.ENGAGE -> chooseEngageGoal(target!!)
            Intent.RETREAT -> {
                val s = arena.spawns[me.team][me.spawnIndex]
                goalX = s.x; goalY = s.y
            }
            Intent.ADVANCE -> chooseAdvanceGoal()
        }
        planPath()
    }

    private fun preferredRangeFraction() = when (me.def.attack.shape) {
        AttackShape.BURST -> 0.62f
        AttackShape.SPREAD -> 0.4f
        AttackShape.LANCE -> 0.78f
    }

    private fun chooseEngageGoal(t: Fighter) {
        val range = me.def.attack.range
        val desired = lerp(0.9f, range * preferredRangeFraction(), profile.rangeDiscipline)
        if (arena.shotClear(me.x, me.y, t.x, t.y)) {
            var dx = me.x - t.x
            var dy = me.y - t.y
            val d = hypot(dx, dy).coerceAtLeast(0.01f)
            dx /= d; dy /= d
            val strafe = 1.4f * profile.rangeDiscipline * strafeSign
            var gx = t.x + dx * desired - dy * strafe
            var gy = t.y + dy * desired + dx * strafe
            if (arena.circleBlocked(gx, gy, me.radius)) {
                gx = t.x + dx * desired; gy = t.y + dy * desired
                if (arena.circleBlocked(gx, gy, me.radius)) { gx = t.x; gy = t.y }
            }
            goalX = gx; goalY = gy
        } else {
            // No line of fire: go find one.
            goalX = t.x; goalY = t.y
        }
    }

    private fun chooseAdvanceGoal() {
        if (lastKnownAge < 4f) {
            goalX = lastKnownX; goalY = lastKnownY
            return
        }
        // Teamwork: tag along with the most advanced living teammate.
        if (rng.nextFloat() < profile.teamwork) {
            val lead = world.fighters.filter { it.team == me.team && it.alive && it !== me }
                .maxByOrNull { if (me.team == 0) it.x else -it.x }
            if (lead != null && dist(lead) > 2.5f) {
                goalX = lead.x; goalY = lead.y
                return
            }
        }
        if (hypot(patrolX - me.x, patrolY - me.y) < 1.5f || rng.nextFloat() < 0.05f) pickPatrolPoint()
        goalX = patrolX; goalY = patrolY
    }

    private fun pickPatrolPoint() {
        val w = arena.width.toFloat()
        val h = arena.height.toFloat()
        val enemySide = if (me.team == 0) 0.68f else 0.32f
        val options = listOf(
            w * 0.5f to h * 0.5f,
            w * 0.5f to h * 0.22f,
            w * 0.5f to h * 0.78f,
            w * enemySide to h * 0.5f,
            w * enemySide to h * 0.3f,
            w * enemySide to h * 0.7f,
        )
        val (x, y) = options[rng.nextInt(options.size)]
        patrolX = x; patrolY = y
    }

    private fun planPath() {
        if (arena.walkClear(me.x, me.y, goalX, goalY, me.radius * 0.95f)) {
            path = listOf(floatArrayOf(goalX, goalY))
            pathIndex = 0
            return
        }
        val goalMoved = hypot(goalX - pathGoalX, goalY - pathGoalY) > 1.2f
        if (path.isEmpty() || goalMoved || repathTimer <= 0f || pathIndex >= path.size) {
            path = pathfinder.find(me.x, me.y, goalX, goalY, me.radius * 0.95f)
            pathIndex = 0
            pathGoalX = goalX; pathGoalY = goalY
            repathTimer = 0.9f
        }
    }

    // ------------------------------------------------------------------ move

    private fun steer(dt: Float) {
        val c = me.control
        var mx = 0f
        var my = 0f
        while (pathIndex < path.size && hypot(path[pathIndex][0] - me.x, path[pathIndex][1] - me.y) < 0.3f) pathIndex++
        if (pathIndex < path.size) {
            val wp = path[pathIndex]
            val dx = wp[0] - me.x
            val dy = wp[1] - me.y
            val d = hypot(dx, dy)
            val isLast = pathIndex == path.lastIndex
            val speed = if (isLast) (d / 0.6f).coerceAtMost(1f) else 1f
            mx = dx / d * speed; my = dy / d * speed
        }

        // Keep some space from teammates so one shot doesn't hit everyone.
        for (o in world.fighters) {
            if (o === me || o.team != me.team || !o.alive) continue
            val dx = me.x - o.x
            val dy = me.y - o.y
            val d = hypot(dx, dy)
            if (d in 0.01f..1.2f) { mx += dx / d * 0.5f; my += dy / d * 0.5f }
        }

        if (profile.wander > 0f) {
            wanderAngle += (rng.nextFloat() - 0.5f) * 4f * dt
            mx += cos(wanderAngle) * profile.wander
            my += sin(wanderAngle) * profile.wander
        }

        if (dodgeTimer > 0f) {
            mx = dodgeX + mx * 0.25f
            my = dodgeY + my * 0.25f
        }

        val len = hypot(mx, my)
        if (len > 1f) { mx /= len; my /= len }
        c.moveX = mx
        c.moveY = my

        // Stuck detection: wanted to move, barely did → re-plan with a nudge.
        stuckTimer += dt
        if (stuckTimer > 0.6f) {
            val moved = hypot(me.x - stuckCheckX, me.y - stuckCheckY)
            if (len > 0.3f && moved < 0.15f) {
                repathTimer = 0f
                path = emptyList()
                wanderAngle = rng.nextFloat() * 6.28f
                thinkTimer = 0f
                pickPatrolPoint()
            }
            stuckTimer = 0f
            stuckCheckX = me.x; stuckCheckY = me.y
        }
    }

    private fun dodge() {
        if (profile.dodgeChance <= 0f) return
        if (judged.size > 48) judged.retainAll(world.projectiles.toSet())
        for (p in world.projectiles) {
            if (p.team == me.team || p in judged) continue
            val rx = me.x - p.x
            val ry = me.y - p.y
            if (rx * rx + ry * ry > 36f) continue
            val v2 = p.vx * p.vx + p.vy * p.vy
            val t = (rx * p.vx + ry * p.vy) / v2
            if (t < 0f || t > 0.7f) continue
            val cx = rx - p.vx * t
            val cy = ry - p.vy * t
            if (sqrt(cx * cx + cy * cy) > me.radius + p.radius + 0.25f) continue
            judged += p
            if (rng.nextFloat() >= profile.dodgeChance) continue
            // Side-step perpendicular to the shot, away from its line.
            val vl = sqrt(v2)
            var px = -p.vy / vl
            var py = p.vx / vl
            if (px * cx + py * cy < 0f) { px = -px; py = -py }
            if (arena.circleBlocked(me.x + px * 0.8f, me.y + py * 0.8f, me.radius)) { px = -px; py = -py }
            dodgeX = px; dodgeY = py
            dodgeTimer = 0.32f
        }
    }

    // ------------------------------------------------------------------ fight

    private fun combat(dt: Float) {
        val t = target ?: return
        if (!t.alive || !world.isVisibleTo(t, me.team) || seenTime < profile.reactionTime) return
        val c = me.control
        val d = dist(t)
        val clear = arena.shotClear(me.x, me.y, t.x, t.y)

        // ---- super
        if (me.superReady) {
            if (superDelay < 0f) superDelay = (1f - profile.superSkill) * rng.nextFloat() * 2.5f
            superDelay -= dt
            if (superDelay <= 0f && wantsSuper(t, d, clear)) {
                aimAt(t, superProjectileSpeed())
                c.superAttack = true
                superDelay = -1f
                return
            }
        }

        // ---- main attack
        val range = me.def.attack.range
        val inRange = if (profile.shotDiscipline) d <= range * 0.92f else d <= range * 1.15f
        if (!inRange) return
        if (profile.shotDiscipline && (!clear || t.shield > 0f)) return
        if (fireTimer > 0f || me.ammo < 1f || me.pending.isNotEmpty()) return
        // Skilled bots don't waste their last shots on long, low-odds pokes.
        if (profile.superSkill > 0.7f && me.ammo < 2f && d > range * 0.8f && t.hpFraction > 0.4f) return
        aimAt(t, me.def.attack.speed)
        c.attack = true
        fireTimer = 0.3f + rng.nextFloat() * profile.fireHesitation
    }

    private fun wantsSuper(t: Fighter, d: Float, clear: Boolean): Boolean {
        val s = me.def.superSpec
        val smart = profile.superSkill >= 0.5f
        return when (s.kind) {
            SuperKind.VOLLEY -> clear && d <= s.range * (if (smart) 0.7f else 1.1f)
            SuperKind.PIERCE -> clear && d <= s.range * 0.95f && (!smart || t.hp <= me.superDamage * 1.1f || lineHitsTwo(t))
            SuperKind.RAM -> d <= s.range * 0.85f && arena.walkClear(me.x, me.y, t.x, t.y, me.radius * 0.9f) &&
                (!smart || t.hp <= me.superDamage * 1.3f || d < 2.5f)
        }
    }

    /** True if another enemy stands close to the line toward [t] — worth a piercing shot. */
    private fun lineHitsTwo(t: Fighter): Boolean {
        val dx = t.x - me.x
        val dy = t.y - me.y
        val len = hypot(dx, dy)
        return world.fighters.any { o ->
            if (o === t || o.team == me.team || !o.alive) return@any false
            val proj = ((o.x - me.x) * dx + (o.y - me.y) * dy) / len
            if (proj < 0f || proj > me.def.superSpec.range) return@any false
            val perp = kotlin.math.abs((o.x - me.x) * dy - (o.y - me.y) * dx) / len
            perp < 0.8f
        }
    }

    private fun superProjectileSpeed() = me.def.superSpec.speed

    private fun aimAt(t: Fighter, projectileSpeed: Float) {
        val c = me.control
        val d = dist(t)
        val lead = d / projectileSpeed * profile.leadFactor
        val px = t.x + t.vx * lead
        val py = t.y + t.vy * lead
        val errRad = gaussian() * Math.toRadians(profile.aimErrorDegrees.toDouble()).toFloat()
        val ang = atan2(py - me.y, px - me.x) + errRad
        c.aimX = cos(ang)
        c.aimY = sin(ang)
        c.aiming = true
    }

    private fun gaussian(): Float {
        // Box–Muller
        val u1 = rng.nextFloat().coerceAtLeast(1e-6f)
        val u2 = rng.nextFloat()
        return (sqrt(-2f * kotlin.math.ln(u1)) * cos(2f * Math.PI.toFloat() * u2)).coerceIn(-2.5f, 2.5f)
    }

    private fun dist(o: Fighter) = hypot(o.x - me.x, o.y - me.y)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
