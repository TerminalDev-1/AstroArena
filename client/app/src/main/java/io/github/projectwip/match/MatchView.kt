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
    duel: io.github.projectwip.net.DuelLink? = null,
    team: io.github.projectwip.net.TeamLink? = null,
) : FrameLayout(context), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private val controls = TouchControls(resources.displayMetrics.density)
    private val runner = MatchRunner(match, settings, sfx, controls,
        onPause = { main.post(onPauseRequested) },
        onFinished = { r -> main.post { onFinished(r) } }, duel = duel, team = team)
    private val channel = HudChannel()
    private val surface = SurfaceView(context)
    private val hud = HudView(context, channel, runner)
    private var thread: GlThread? = null
    private var renderer: MatchRenderer? = null
    /** The graphics preset this match is drawn with (taken when it starts). */
    private val gfx = io.github.projectwip.gl.GraphicsProfile.current

    var paused: Boolean
        get() = runner.paused
        set(v) { runner.paused = v }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The format the game's graphics are set up for (8 bits a channel). Left to Android, a SurfaceView may be given
        // another, and some phones then refuse to make the match's drawing surface at all.
        surface.holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
        surface.holder.addCallback(this)
        keepScreenOn = true
    }

    /** Stops listening to [io.github.projectwip.gl.PowerWatch] when the match goes. */
    private var unlisten: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The phone warming up (or cooling down, or being plugged in) mid-match: draw it smaller or larger and at the
        // frame rate that goes with it, without waiting for the next match.
        unlisten = io.github.projectwip.gl.PowerWatch.listen {
            if (width > 0 && height > 0) applyBuffer(width, height)
            applyFrameRate()
        }
    }

    override fun onDetachedFromWindow() {
        unlisten?.invoke(); unlisten = null
        super.onDetachedFromWindow()
    }

    private fun applyFrameRate() {
        val fps = io.github.projectwip.gl.PowerWatch.cap(settings.frameRate, menu = false)
        if (Build.VERSION.SDK_INT >= 30) try {
            if (surface.holder.surface.isValid) surface.holder.surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        } catch (_: Throwable) {}
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is io.github.projectwip.MainActivity) c = c.baseContext
        (c as? io.github.projectwip.MainActivity)?.applyRefreshRate(fps)
    }

    private var bufW = 0
    private var bufH = 0

    /** The match's buffer for a view of [w] x [h] (smaller on a tall screen, on battery or when the phone is warm). */
    private fun applyBuffer(w: Int, h: Int) {
        val (bw, bh) = gfx.bufferSize(w, h)
        if (bw == bufW && bh == bufH) return
        bufW = bw; bufH = bh
        if (bw != w || bh != h) surface.holder.setFixedSize(bw, bh) else surface.holder.setSizeFromLayout()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            holder.surface.setFrameRate(io.github.projectwip.gl.PowerWatch.cap(settings.frameRate, menu = false).toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        val r = MatchRenderer(runner, channel, matchesPlayed, gfx).also { it.viewWidth = width; it.viewHeight = height }
        renderer = r
        thread = GlThread(holder.surface, r, "match-gl", msaa = gfx.msaa, menu = false, onFailure = {
            main.post {
                android.widget.Toast.makeText(context, "The graphics couldn't start on this phone. A report was saved: pause to leave the match.", android.widget.Toast.LENGTH_LONG).show()
            }
        }).also { it.maxFps = settings.frameRate; it.start() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A tall screen is drawn smaller and scaled up by the display hardware (the graphics preset's render scale).
        applyBuffer(w, h)
        renderer?.let { it.viewWidth = w; it.viewHeight = h }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, bufferWidth: Int, bufferHeight: Int) {
        thread?.resize(bufferWidth, bufferHeight)
        // Touch and the HUD work in the view's pixels, which the buffer may be smaller than.
        val width = if (this.width > 0) this.width else bufferWidth
        val height = if (this.height > 0) this.height else bufferHeight
        renderer?.let { it.viewWidth = width; it.viewHeight = height }
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

    /** What the screen inside the Entity shows (the player is trapped in it): read from the match as it runs. */
    class InsideInfo(val hp: Int, val maxHp: Int, val held: List<Pair<io.github.projectwip.data.FighterId, Int>>, val team: List<Mate>)
    class Mate(val name: String, val hp: Int, val maxHp: Int, val alive: Boolean, val trapped: Boolean)

    /** The player is held inside the Entity, with the match still going. */
    val playerTrapped: Boolean get() = runner.match.player.trapped && runner.match.world.phase == io.github.projectwip.sim.Phase.PLAYING

    fun insideInfo(): InsideInfo? {
        val w = runner.match.world
        val u = w.uprising ?: return null
        val p = runner.match.player
        val members = u.members.mapNotNull { w.fighter(it) }
        val held = (listOf(p) + members.filter { it !== p }).filter { it.trapped }.map { it.def.id to it.skin }
        val team = members.filter { it !== p }.map { Mate(it.name, it.hp, it.maxHp, it.alive, it.trapped) }
        return InsideInfo(u.hp, u.maxHp, held, team)
    }

    /** How many times the Dragon's neck jewel has been shattered (each plays its 3D cutscene). */
    val jewelBreaks: Int get() = runner.jewelBreaks
    /** How many times its red flares have drained the player (removed). */
    val drains: Int get() = runner.drains
    /** The last time, whether this player's fighter gives in rather than passes out (fixed by the match). */
    val obeys: Boolean get() = (runner.match.config.seed and 1L) == 1L

    /** How many times the Entity has corrupted the player (each plays its cutscene). */
    val corruptions: Int get() = runner.corruptions

    /** The Entity's hijack (see `MatchRunner.hijack`): 2 when its cutscene should play. */
    val hijack: Int get() = runner.hijack

    /** The hijack cutscene is over: play goes on, with the Entity at the controls. */
    fun endHijack() {
        runner.hijack = 0
        resumeGame()
    }

    fun resumeGame() {
        controls.reset()
        runner.paused = false
    }
}
