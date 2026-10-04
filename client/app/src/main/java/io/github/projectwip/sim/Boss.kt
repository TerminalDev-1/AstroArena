package io.github.projectwip.sim

import io.github.projectwip.data.BossKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What kind of ground attack a [Hazard] is, for drawing it. */
enum class HazardKind { ROCKET, MINE, SLAM }

/**
 * A marked patch of ground that goes off after [delay] seconds, hurting everyone of the other team standing in
 * it. The mark is there from the start, so it can always be walked out of.
 */
class Hazard(
    val ownerId: Int, val team: Int, val x: Float, val y: Float, val radius: Float, val delay: Float, val damage: Int, val kind: HazardKind,
) {
    var age = 0f
}

/**
 * How a Boss Mode boss fights. A boss doesn't use a fighter's attack and super: it works through a set of moves
 * of its own, one after another, each announced before it lands (a marked patch of ground, a visible wind-up).
 * Below half health it is enraged: it moves on to the next one sooner, and each move is bigger.
 *
 * The bot brain still walks the boss about; this decides everything it does to the player. It only draws on the
 * world's own random numbers, so the server's replay of a fight goes exactly as the device's did.
 */
internal class BossScript(private val w: World, private val me: Fighter) {
    private class Timed(val at: Float, val run: () -> Unit)

    private var clock = 0f
    private var nextMove = 2.2f
    private var turn = 0
    private val queue = ArrayList<Timed>()
    /** Where the target was last seen: a boss fires at that when its target is hidden. */
    private var seenX = w.arena.width / 2f
    private var seenY = w.arena.height / 2f

    val enraged get() = me.hp < me.maxHp / 2

    fun step(dt: Float) {
        clock += dt
        val target = w.fighters.filter { it.team != me.team && it.alive }.minByOrNull { hypot(it.x - me.x, it.y - me.y) }
        if (target != null && w.isVisibleTo(target, me.team)) { seenX = target.x; seenY = target.y }
        var i = 0
        while (i < queue.size) {
            if (queue[i].at <= clock) queue.removeAt(i).run() else i++
        }
        if (me.isDashing || clock < nextMove) return
        if (target == null) { nextMove = clock + 0.5f; return }
        val pace = when (me.def.boss) {
            BossKind.BARRAGE -> barrage(target)
            BossKind.SWEEPER -> sweeper()
            BossKind.STAMPEDE -> stampede()
            null -> 1f
        }
        turn++
        nextMove = clock + pace * (if (enraged) 0.65f else 1f)
    }

    private fun after(seconds: Float, run: () -> Unit) { queue += Timed(clock + seconds, run) }
    private fun aim() = atan2(seenY - me.y, seenX - me.x)
    private fun face(angle: Float) { me.facing = angle }

    private fun mark(x: Float, y: Float, radius: Float, delay: Float, damage: Int, kind: HazardKind) {
        val a = w.arena
        w.hazards += Hazard(me.id, me.team, x.coerceIn(1.5f, a.width - 1.5f), y.coerceIn(1.5f, a.height - 1.5f), radius, delay, damage, kind)
    }

    /** A fan of [count] shots across [degrees], centred on [angle]. */
    private fun fan(angle: Float, count: Int, degrees: Float, speed: Float, radius: Float, damage: Int, range: Float, style: ShotStyle) {
        val spread = Math.toRadians(degrees.toDouble()).toFloat()
        for (i in 0 until count) w.bossShot(me, angle - spread / 2 + spread * i / (count - 1), speed, radius, damage, range, false, style)
        w.announce(GameEvent.Shot(me.id, false, me.x, me.y, cos(angle), sin(angle)))
    }

    // ------------------------------------------------------------------ Hailstorm: rockets from above

    private fun barrage(target: Fighter): Float {
        when (turn % 3) {
            0 -> {
                // A salvo that lands where the target is heading, the first one dead on and the rest scattered round it.
                val rockets = if (enraged) 8 else 5
                val leadX = seenX + target.vx * 0.7f
                val leadY = seenY + target.vy * 0.7f
                for (i in 0 until rockets) {
                    val a = w.rng.nextFloat() * 6.283f
                    val r = if (i == 0) 0f else 0.8f + w.rng.nextFloat() * 1.9f
                    mark(leadX + cos(a) * r, leadY + sin(a) * r, 1.25f, 1.15f + i * 0.16f, 1100, HazardKind.ROCKET)
                }
                w.announce(GameEvent.Shot(me.id, true, me.x, me.y, 0f, 0f))
            }
            1 -> { face(aim()); fan(aim(), 11, 80f, 12f, 0.24f, 380, 9f, ShotStyle.PELLET) }
            else -> {
                // A line of rockets walking out from the boss toward the target.
                val a = aim()
                face(a)
                for (i in 1..(if (enraged) 9 else 7)) mark(me.x + cos(a) * (1.6f + i * 1.35f), me.y + sin(a) * (1.6f + i * 1.35f), 1.05f, 0.6f + i * 0.14f, 950, HazardKind.ROCKET)
                w.announce(GameEvent.Shot(me.id, true, me.x, me.y, cos(a), sin(a)))
            }
        }
        return 2.8f
    }

    // ------------------------------------------------------------------ Lighthouse: a beam that sweeps, rings that spread

    private fun sweeper(): Float {
        when (turn % 3) {
            0 -> {
                // One piercing bolt after another, the aim swinging across the target: step through it or out of reach.
                val base = aim()
                val dir = if ((turn / 3) % 2 == 0) 1f else -1f
                val bolts = if (enraged) 24 else 18
                for (i in 0 until bolts) after(0.4f + i * 0.06f) {
                    val angle = base + dir * (-0.9f + 1.8f * i / (bolts - 1))
                    face(angle)
                    w.bossShot(me, angle, 17f, 0.2f, 520, 12f, true, ShotStyle.PRISM)
                    if (i % 4 == 0) w.announce(GameEvent.Shot(me.id, false, me.x, me.y, cos(angle), sin(angle)))
                }
                return 3.4f
            }
            1 -> {
                // A slow ring going out in every direction, with gaps to slip through. Enraged, a second one fills them.
                val offset = w.rng.nextFloat() * 6.283f
                fun ring(shift: Float) {
                    for (i in 0 until 22) w.bossShot(me, offset + shift + i * 6.283f / 22, 8.5f, 0.24f, 400, 10f, false, ShotStyle.PELLET)
                    w.announce(GameEvent.Shot(me.id, true, me.x, me.y, 0f, 0f))
                }
                ring(0f)
                if (enraged) after(0.55f) { ring(6.283f / 44) }
            }
            else -> {
                // Mines laid in a circle round the target, and one underneath: there is a way out, for a moment.
                val mines = if (enraged) 9 else 6
                mark(seenX, seenY, 1.15f, 1.9f, 1000, HazardKind.MINE)
                for (i in 0 until mines) {
                    val a = i * 6.283f / mines
                    mark(seenX + cos(a) * 2.6f, seenY + sin(a) * 2.6f, 1.15f, 1.9f + (i % 3) * 0.2f, 1000, HazardKind.MINE)
                }
            }
        }
        return 3f
    }

    // ------------------------------------------------------------------ Ramrod: charges and ground slams

    private fun stampede(): Float {
        val distance = hypot(seenX - me.x, seenY - me.y)
        when {
            distance < 3.6f -> {
                // The target is close: it rears up and brings the floor down all round itself.
                mark(me.x, me.y, 3.2f, 0.85f, 1500, HazardKind.SLAM)
                return 2.2f
            }
            turn % 2 == 0 -> {
                // A charge at the target (three in a row when enraged), each ending in a slam where it stops.
                val charges = if (enraged) 3 else 1
                val spec = me.def.superSpec
                for (k in 0 until charges) after(0.35f + k * 1.2f) {
                    val a = aim()
                    face(a)
                    w.startDash(me, cos(a), sin(a))
                    after(spec.range / spec.speed) { mark(me.x, me.y, 2.4f, 0.4f, 1200, HazardKind.SLAM) }
                }
                return 1.2f + charges * 1.2f
            }
            else -> {
                // Three quick blasts of shrapnel.
                for (k in 0 until 3) after(k * 0.22f) { face(aim()); fan(aim(), 7, 50f, 14f, 0.22f, 420, 8f, ShotStyle.PELLET) }
            }
        }
        return 2.6f
    }
}
