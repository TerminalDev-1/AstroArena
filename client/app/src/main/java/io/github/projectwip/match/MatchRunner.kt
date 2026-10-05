package io.github.projectwip.match

import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.MatchReport
import io.github.projectwip.data.Settings
import io.github.projectwip.data.SuperKind
import io.github.projectwip.data.VoiceCue
import io.github.projectwip.sim.Fighter
import io.github.projectwip.sim.GameEvent
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.Phase
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Things the 2D HUD needs to animate that happen at a moment in time. */
sealed interface HudEvent {
    data class Damage(val x: Float, val y: Float, val amount: Int, val mine: Boolean, val big: Boolean) : HudEvent
    data class Ko(val killer: String, val killerTeam: Int, val victim: String, val victimTeam: Int) : HudEvent
    data object Pop : HudEvent
}

/**
 * Runs a [Match] on the render thread: fixed 60 Hz steps, touch → [io.github.projectwip.sim.Control],
 * aim assist, sound/haptic feedback. Renderer-agnostic.
 */
class MatchRunner(
    val match: Match,
    val settings: Settings,
    private val sfx: Sfx,
    val controls: TouchControls,
    private val onPause: () -> Unit,
    private val onFinished: (MatchReport) -> Unit,
    /** In a 1v1 against a real player: the line to them. Ticks only run once both players' inputs are in. */
    private val duel: io.github.projectwip.net.DuelLink? = null,
) {
    /** The next tick the simulation will run (1v1 only). */
    private var simTick = 0
    /** Seconds spent waiting for the other player's inputs. */
    private var stalled = 0f
    /** True while a 1v1 is held up waiting for the other player: the HUD says so. */
    @Volatile var waitingForOpponent = false
        private set
    /** True once a 1v1 has been called off because the two devices no longer agreed about it. */
    @Volatile var outOfStep = false
        private set
    /** True once a 1v1 has been called off because the connection went. */
    @Volatile var connectionLost = false
        private set
    val input = TouchControls.Input()
    /** Events produced during the last [update] — consumed by the renderer for effects. */
    val frameEvents = ArrayList<GameEvent>()
    val hudEvents = ConcurrentLinkedQueue<HudEvent>()
    @Volatile var paused = false
    private var acc = 0f
    private var finished = false
    /** Who a tap on the attack stick would hit right now (drawn as a marker). */
    var autoTarget: Fighter? = null
        private set
    /** Tile key of the Spark Crate a tap would shoot instead (when no enemy can be hit), or -1. */
    var autoCrate = -1
        private set

    /** Advances the simulation. Returns the interpolation alpha for rendering. */
    fun update(dt: Float): Float {
        frameEvents.clear()
        if (paused) { controls.poll(input); return 1f }
        acc = acc.coerceAtMost(0.25f)
        acc += dt
        while (acc >= Match.STEP) {
            tick()
            acc -= Match.STEP
        }
        val p = match.player
        autoTarget = null; autoCrate = -1
        if (p.alive && match.world.phase == Phase.PLAYING) pickAuto(p.def.attack.range)
        return acc / Match.STEP
    }

    private fun tick() {
        val p = match.player
        controls.superReady = p.superReady && p.alive
        controls.hyperReady = p.hyperReady && p.alive
        controls.poll(input)
        if (input.pause) onPause()

        val c = p.control
        c.moveX = input.moveX
        c.moveY = input.moveY
        c.aiming = input.aimingAttack || input.aimingSuper
        if (c.aiming) { c.aimX = input.aimX; c.aimY = input.aimY }
        if (input.attack != TouchControls.FireMode.NONE) {
            setAim(input.attack, input.attackX, input.attackY, p.def.attack.range, p.def.attack.speed)
            c.attack = true
        }
        if (input.superFire != TouchControls.FireMode.NONE && p.superReady) {
            if (p.def.superSpec.kind == SuperKind.SWARM) aimRain(input.superFire, input.superX, input.superY, p.def.superSpec.range)
            else setAim(input.superFire, input.superX, input.superY, p.def.superSpec.range, p.def.superSpec.speed)
            c.superAttack = true
        }
        if (input.hyper && p.hyperReady) c.hyper = true

        val link = duel
        val them = match.opponent
        if (link != null && them != null && !match.isOver) {
            if (link.outOfStep) {
                // The two devices have computed the match differently. Neither can be trusted: it is called off.
                outOfStep = true
                match.world.abandon()
            } else if (link.remoteLeft) {
                // The lobby says the other player has gone: the match is this player's.
                match.world.forfeit(them.team)
            } else if (link.dropped) {
                // The lobby says it was this player who stopped responding: the match is the other's.
                match.world.forfeit(p.team)
            } else if (link.lost) {
                // The line went and nobody can say whose doing it was: called off.
                connectionLost = true
                match.world.abandon()
            } else {
                // What the player wants now is recorded, to be played a few ticks from now on both devices...
                if (link.sent <= simTick) link.sendLocal(c)
                // ...and this tick only runs if both players' inputs for it have arrived.
                if (!link.ready(simTick)) {
                    c.attack = false; c.superAttack = false; c.hyper = false
                    // Held up. What that means is the lobby's call (it can see both players); this device only gives
                    // up by itself if the lobby has gone quiet too.
                    stalled += Match.STEP
                    waitingForOpponent = stalled > 0.4f
                    if (stalled > 60f) link.close()
                    return
                }
                stalled = 0f
                waitingForOpponent = false
                // Twice a second the devices compare what they make of the match so far (before this tick runs).
                if (simTick % io.github.projectwip.net.DuelLink.CHECK_EVERY == 0) link.check(simTick, match.world.checksum())
                link.apply(simTick, c, them.control)
                simTick++
            }
        }

        val ammoBefore = p.ammo
        val tried = c.attack
        match.step(Match.STEP)
        if (tried && ammoBefore < 1f && p.alive && match.world.phase == Phase.PLAYING) sfx.play(Sound.DENIED, 0.4f)

        for (e in match.world.events) {
            frameEvents += e
            feedback(e)
        }
        match.world.events.clear()

        if (match.isOver && match.overFor > 2.8f && !finished) {
            finished = true
            onFinished(match.report())
        }
    }

    /**
     * What a tap should shoot, best first: an enemy in range with a clear shot, then a Spark Crate that can be
     * hit, then an enemy in range that is behind cover (so the shot at least goes their way).
     */
    private fun pickAuto(range: Float) {
        val p = match.player
        val w = match.world
        val enemy = w.nearestVisibleEnemy(p, range + 0.5f)
        autoTarget = enemy
        autoCrate = -1
        if (enemy != null && w.arena.shotClear(p.x, p.y, enemy.x, enemy.y)) return
        val crate = w.nearestHittableCrate(p, range + 0.4f)
        if (crate >= 0) { autoCrate = crate; autoTarget = null }
    }

    private val lead = FloatArray(2)

    /**
     * Aims a rain of rockets: the aim is the spot they land on, as an offset in tiles. Dragged, that is exactly
     * where the stick points (how far it is pushed is how far away, up to [range]) with no help. Tapped, it is
     * the nearest enemy in sight, or failing that a spot ahead.
     */
    private fun aimRain(mode: TouchControls.FireMode, ax: Float, ay: Float, range: Float) {
        val p = match.player
        val c = p.control
        if (mode == TouchControls.FireMode.AIMED && hypot(ax, ay) >= 0.01f) {
            val push = hypot(ax, ay).coerceAtMost(1f)
            c.aimX = ax / hypot(ax, ay) * push * range
            c.aimY = ay / hypot(ax, ay) * push * range
            return
        }
        val t = match.world.nearestVisibleEnemy(p, range)
        if (t != null) { c.aimX = t.x - p.x; c.aimY = t.y - p.y }
        else { c.aimX = cos(p.facing) * range * 0.5f; c.aimY = sin(p.facing) * range * 0.5f }
    }

    /**
     * Auto = lock onto the nearest visible enemy and lead the shot so it meets them.
     * Aimed shots get gentle aim assist (also leading) if enabled.
     */
    private fun setAim(mode: TouchControls.FireMode, ax: Float, ay: Float, range: Float, speed: Float) {
        val p = match.player
        val c = p.control
        val w = match.world
        if (mode == TouchControls.FireMode.AUTO || hypot(ax, ay) < 0.01f) {
            // In range first (an enemy, else a crate); otherwise still turn toward the nearest visible enemy just beyond it.
            pickAuto(range)
            if (autoCrate >= 0) {
                c.aimX = autoCrate % w.arena.width + 0.5f - p.x
                c.aimY = autoCrate / w.arena.width + 0.5f - p.y
                return
            }
            val t = autoTarget ?: w.nearestVisibleEnemy(p, range + 3f)
            if (t != null) {
                w.leadAim(p, t, speed, lead)
                c.aimX = lead[0]; c.aimY = lead[1]
            } else {
                c.aimX = cos(p.facing); c.aimY = sin(p.facing)
            }
            return
        }
        c.aimX = ax; c.aimY = ay
        if (settings.aimAssist) {
            val want = atan2(ay, ax)
            var best: Fighter? = null
            var bestDiff = Math.toRadians(ASSIST_DEGREES).toFloat()
            for (e in w.fighters) {
                if (e.team == p.team || !w.isVisibleTo(e, p.team)) continue
                if (hypot(e.x - p.x, e.y - p.y) > range + 0.5f) continue
                var d = atan2(e.y - p.y, e.x - p.x) - want
                while (d > Math.PI) d -= (2 * Math.PI).toFloat()
                while (d < -Math.PI) d += (2 * Math.PI).toFloat()
                if (abs(d) < bestDiff) { bestDiff = abs(d); best = e }
            }
            best?.let { w.leadAim(p, it, speed, lead); c.aimX = lead[0]; c.aimY = lead[1] }
        }
    }

    private fun feedback(e: GameEvent) {
        val pid = match.player.id
        val world = match.world
        when (e) {
            is GameEvent.Shot -> {
                if (e.isSuper && e.fighterId == pid) say(VoiceCue.SUPER)
                val f = world.fighter(e.fighterId) ?: return
                val near = 1f / (1f + hypot(f.x - match.player.x, f.y - match.player.y) * 0.15f)
                val gain = if (e.fighterId == pid) 1f else near * 0.6f
                if (e.isSuper) sfx.play(Sound.SUPER, gain)
                else sfx.play(when (f.def.attack.shape) {
                    AttackShape.BURST -> Sound.SHOOT_SPARK
                    AttackShape.SPREAD -> Sound.SHOOT_HEAVY
                    AttackShape.ROCKETS -> Sound.ROCKET
                    AttackShape.LANCE -> Sound.SHOOT_PRISM
                }, gain, 0.95f + (e.x % 0.1f))
                if (e.fighterId == pid) sfx.buzz(if (e.isSuper) 40 else 12, if (e.isSuper) 200 else 60)
            }
            is GameEvent.Hit -> {
                if (settings.showDamageNumbers && (e.sourceId == pid || e.targetId == pid)) {
                    hudEvents += HudEvent.Damage(e.x, e.y, e.damage, e.sourceId == pid, e.isSuper)
                }
                when {
                    e.targetId == pid -> { sfx.play(Sound.HURT, 0.9f); sfx.buzz(30, 140) }
                    e.sourceId == pid -> sfx.play(Sound.HIT, 0.8f, if (e.isSuper) 0.8f else 1.1f)
                }
            }
            is GameEvent.Ko -> {
                val k = world.fighter(e.killerId)
                val v = world.fighter(e.victimId)
                if (v != null) hudEvents += HudEvent.Ko(k?.name ?: if (match.freeForAll) "Static Storm" else "—", k?.team ?: -2, v.name, v.team)
                if (e.killerId == pid) { sfx.play(Sound.KO, 1f); sfx.buzz(60, 220); say(VoiceCue.KO) }
                else if (e.victimId == pid) { sfx.play(Sound.KO, 0.9f, 0.7f); sfx.buzz(120, 255); say(VoiceCue.DOWN) }
                else sfx.play(Sound.KO, 0.35f)
            }
            is GameEvent.CellPicked -> if (e.fighterId == pid) { sfx.play(Sound.PICKUP); sfx.buzz(18, 110) }
            is GameEvent.CrateBroken -> {
                val p = match.player
                sfx.play(Sound.CRATE_BREAK, 1f / (1f + hypot(e.tx + 0.5f - p.x, e.ty + 0.5f - p.y) * 0.2f))
            }
            is GameEvent.Blast -> {
                val p = match.player
                val near = 1f / (1f + hypot(e.x - p.x, e.y - p.y) * 0.2f)
                if (e.kind == io.github.projectwip.sim.HazardKind.ROCKET) sfx.play(Sound.ROCKET_BOOM, near, 0.9f)
                else { sfx.play(Sound.CRATE_BREAK, near, 0.7f); sfx.play(Sound.SHOOT_HEAVY, near * 0.8f, 0.6f) }
            }
            is GameEvent.SuperReady -> if (e.fighterId == pid) { sfx.play(Sound.SUPER_READY); sfx.buzz(25, 120) }
            is GameEvent.HyperReady -> if (e.fighterId == pid) { sfx.play(Sound.SUPER_READY, 1f, 1.35f); sfx.buzz(25, 120) }
            is GameEvent.Hyper -> {
                if (e.fighterId == pid) say(VoiceCue.HYPER)
                val f = world.fighter(e.fighterId) ?: return
                val gain = if (e.fighterId == pid) 1f else 0.5f / (1f + hypot(f.x - match.player.x, f.y - match.player.y) * 0.15f)
                sfx.play(Sound.HYPER, gain)
                if (e.fighterId == pid) sfx.buzz(70, 220)
            }
            is GameEvent.CountdownTick -> { sfx.play(Sound.TICK); hudEvents += HudEvent.Pop }
            is GameEvent.MatchStart -> { sfx.play(Sound.GO); hudEvents += HudEvent.Pop; say(VoiceCue.START) }
            is GameEvent.Spawned -> if (e.fighterId == pid) say(VoiceCue.BACK)
            is GameEvent.Burst -> {
                val p = match.player
                sfx.play(Sound.ROCKET_BOOM, 0.5f / (1f + hypot(e.x - p.x, e.y - p.y) * 0.2f), 1.15f + (e.x % 0.2f))
            }
            is GameEvent.Launch -> {
                val f = world.fighter(e.fighterId) ?: return
                sfx.play(Sound.ROCKET, (if (e.fighterId == pid) 1f else 0.6f) / (1f + hypot(f.x - match.player.x, f.y - match.player.y) * 0.15f), 0.72f)
            }
            is GameEvent.MatchEnd -> sfx.play(if (e.winningTeam == match.player.team) Sound.VICTORY else Sound.DEFEAT)
            is GameEvent.Eliminated -> if (e.fighterId == pid) sfx.play(Sound.DEFEAT)
            is GameEvent.StormHit -> if (e.targetId == pid) {
                sfx.play(Sound.HURT, 0.6f, 0.8f); sfx.buzz(20, 90)
                if (settings.showDamageNumbers) hudEvents += HudEvent.Damage(e.x, e.y, e.damage, false, false)
            }
            else -> Unit
        }
    }

    private var voiceTurn = 0

    /** The player's fighter says one of its lines for [cue], if it has a voice. */
    private fun say(cue: VoiceCue) {
        val lines = match.player.def.voice[cue] ?: return
        if (lines.isNotEmpty()) sfx.say(lines[voiceTurn++ % lines.size])
    }

    companion object {
        /** Aimed shots within this many degrees of a visible enemy snap onto it (when Aim Assist is on). */
        const val ASSIST_DEGREES = 12.0
    }
}
