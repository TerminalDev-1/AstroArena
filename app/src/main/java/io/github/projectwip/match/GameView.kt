package io.github.projectwip.match

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.MatchReport
import io.github.projectwip.data.Settings
import io.github.projectwip.render.GameRenderer
import io.github.projectwip.sim.GameEvent
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.Phase
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Hosts a [Match]. A dedicated thread runs the simulation at a fixed 60 Hz and renders every vsync
 * with interpolation, so motion is smooth on 90/120/144 Hz screens.
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val match: Match,
    private val settings: Settings,
    private val sfx: Sfx,
    private val onPauseRequested: () -> Unit,
    private val onFinished: (MatchReport) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val density = resources.displayMetrics.density
    private val controls = TouchControls(density)
    private val renderer = GameRenderer(density)
    private val input = TouchControls.Input()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile var paused = false
    private var thread: Thread? = null
    private var finishedReported = false

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            holder.surface.setFrameRate(if (settings.highFrameRate) 120f else 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        var left = 0
        var right = 0
        rootWindowInsets?.let { wi ->
            if (Build.VERSION.SDK_INT >= 30) {
                val ins = wi.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemGestures())
                left = ins.left; right = ins.right
            }
        }
        controls.configure(settings, width, height, left, right)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    private fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "game-loop").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = controls.onTouch(event)

    private fun loop() {
        var last = System.nanoTime()
        var acc = 0f
        var fpsFrames = 0
        var fpsTime = 0f
        var fps = 0
        while (running) {
            val now = System.nanoTime()
            val frameDt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            if (!paused) {
                acc += frameDt
                while (acc >= Match.STEP) {
                    tick()
                    acc -= Match.STEP
                }
            } else {
                controls.poll(input) // drain taps made while paused
            }
            fpsFrames++
            fpsTime += frameDt
            if (fpsTime >= 0.5f) { fps = (fpsFrames / fpsTime).toInt(); fpsFrames = 0; fpsTime = 0f }

            val canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) { null }
            if (canvas == null) { Thread.sleep(8); continue }
            try {
                renderer.draw(canvas, match, if (paused) 1f else acc / Match.STEP, if (paused) 0f else frameDt, controls, input, settings, fps)
            } catch (e: Exception) {
                Log.e("GameView", "render failed", e)
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
            }
        }
    }

    private fun tick() {
        val p = match.player
        controls.superReady = p.superReady && p.alive
        controls.poll(input)
        if (input.pause) main.post(onPauseRequested)

        // Human input → the same Control struct bots write to.
        val c = p.control
        c.moveX = input.moveX
        c.moveY = input.moveY
        c.aiming = input.aimingAttack || input.aimingSuper
        if (c.aiming) { c.aimX = input.aimX; c.aimY = input.aimY }
        if (input.attack != TouchControls.FireMode.NONE) {
            setAim(input.attack, input.attackX, input.attackY, p.def.attack.range)
            c.attack = true
        }
        if (input.superFire != TouchControls.FireMode.NONE && p.superReady) {
            setAim(input.superFire, input.superX, input.superY, p.def.superSpec.range)
            c.superAttack = true
        }

        val ammoBefore = p.ammo
        val triedAttack = c.attack
        match.step(Match.STEP)
        if (triedAttack && ammoBefore < 1f && p.alive && match.world.phase == Phase.PLAYING) {
            // Tried to shoot with an empty magazine.
            sfx.play(Sound.DENIED, 0.4f)
        }

        for (e in match.world.events) {
            renderer.onEvent(e, match, settings)
            playFeedback(e)
        }
        match.world.events.clear()

        if (match.isOver && match.world.phaseTime > 2.6f && !finishedReported) {
            finishedReported = true
            val report = match.report()
            main.post { onFinished(report) }
        }
    }

    /** Resolves a fire gesture into an aim direction. Auto = nearest visible enemy, else facing. */
    private fun setAim(mode: TouchControls.FireMode, ax: Float, ay: Float, range: Float) {
        val p = match.player
        val c = p.control
        if (mode == TouchControls.FireMode.AUTO || hypot(ax, ay) < 0.01f) {
            val t = match.world.nearestVisibleEnemy(p, range + 0.5f)
            if (t != null) { c.aimX = t.x - p.x; c.aimY = t.y - p.y }
            else { c.aimX = cos(p.facing); c.aimY = sin(p.facing) }
        } else {
            c.aimX = ax; c.aimY = ay
        }
    }

    private fun playFeedback(e: GameEvent) {
        val pid = match.player.id
        val world = match.world
        when (e) {
            is GameEvent.Shot -> {
                val f = world.fighter(e.fighterId) ?: return
                val near = 1f / (1f + hypot(f.x - match.player.x, f.y - match.player.y) * 0.15f)
                val gain = if (e.fighterId == pid) 1f else near * 0.6f
                if (e.isSuper) sfx.play(Sound.SUPER, gain)
                else sfx.play(when (f.def.attack.shape) {
                    AttackShape.BURST -> Sound.SHOOT_SPARK
                    AttackShape.SPREAD -> Sound.SHOOT_HEAVY
                    AttackShape.LANCE -> Sound.SHOOT_PRISM
                }, gain, 0.95f + (e.x % 0.1f))
                if (e.fighterId == pid) sfx.buzz(if (e.isSuper) 40 else 12, if (e.isSuper) 200 else 60)
            }
            is GameEvent.Hit -> when {
                e.targetId == pid -> { sfx.play(Sound.HURT, 0.9f); sfx.buzz(30, 140) }
                e.sourceId == pid -> sfx.play(Sound.HIT, 0.8f, if (e.isSuper) 0.8f else 1.1f)
            }
            is GameEvent.Ko -> {
                if (e.killerId == pid) { sfx.play(Sound.KO, 1f); sfx.buzz(60, 220) }
                else if (e.victimId == pid) { sfx.play(Sound.KO, 0.9f, 0.7f); sfx.buzz(120, 255) }
                else sfx.play(Sound.KO, 0.35f)
            }
            is GameEvent.SuperReady -> if (e.fighterId == pid) { sfx.play(Sound.SUPER_READY); sfx.buzz(25, 120) }
            is GameEvent.CountdownTick -> sfx.play(Sound.TICK)
            is GameEvent.MatchStart -> sfx.play(Sound.GO)
            is GameEvent.MatchEnd -> sfx.play(if (e.winningTeam == match.player.team) Sound.VICTORY else Sound.DEFEAT)
            else -> Unit
        }
    }

    fun resumeGame() {
        controls.reset()
        paused = false
    }
}
