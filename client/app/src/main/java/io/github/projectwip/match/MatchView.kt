package io.github.projectwip.match

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.widget.FrameLayout
import io.github.projectwip.audio.Sfx
import io.github.projectwip.data.MatchReport
import io.github.projectwip.data.Settings
import io.github.projectwip.gl.GlThread
import io.github.projectwip.render3d.MatchRenderer
import io.github.projectwip.sim.Match

/**
 * A match on screen: a SurfaceView rendered by [MatchRenderer] on its own GL thread (which also steps
 * the simulation), with [HudView] layered on top for HUD and touch input.
 */
@SuppressLint("ViewConstructor")
class MatchView(
    context: Context,
    match: Match,
    private val settings: Settings,
    sfx: Sfx,
    private val matchesPlayed: Int,
    onPauseRequested: () -> Unit,
    onFinished: (MatchReport) -> Unit,
) : FrameLayout(context), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private val controls = TouchControls(resources.displayMetrics.density)
    private val runner = MatchRunner(match, settings, sfx, controls,
        onPause = { main.post(onPauseRequested) },
        onFinished = { r -> main.post { onFinished(r) } })
    private val channel = HudChannel()
    private val surface = SurfaceView(context)
    private val hud = HudView(context, channel, runner)
    private var thread: GlThread? = null

    var paused: Boolean
        get() = runner.paused
        set(v) { runner.paused = v }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surface.holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Ask for exactly what the panel runs at: 120 fps content on a 144 Hz screen judders.
            val hz = if (settings.highFrameRate) display?.refreshRate ?: 120f else 60f
            holder.surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        thread = GlThread(holder.surface, MatchRenderer(runner, channel, matchesPlayed), "match-gl").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        thread?.resize(width, height)
        var left = 0
        var right = 0
        if (Build.VERSION.SDK_INT >= 30) rootWindowInsets?.let { wi ->
            val ins = wi.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            left = ins.left; right = ins.right
        }
        controls.configure(settings, width, height, left, right)
        // Keep edge-swipe system gestures from stealing thumbs near the sticks.
        if (Build.VERSION.SDK_INT >= 29) {
            val d = resources.displayMetrics.density
            val band = (200 * d).toInt()
            hud.systemGestureExclusionRects = listOf(
                Rect(0, height - band, (40 * d).toInt(), height),
                Rect(width - (40 * d).toInt(), height - band, width, height),
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.shutdown()
        thread = null
    }

    fun resumeGame() {
        controls.reset()
        runner.paused = false
    }
}
