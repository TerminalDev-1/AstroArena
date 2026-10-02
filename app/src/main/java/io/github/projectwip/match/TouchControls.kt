package io.github.projectwip.match

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import io.github.projectwip.data.MoveStickMode
import io.github.projectwip.data.Settings
import kotlin.math.hypot
import kotlin.math.min

/**
 * Twin-stick touch controls.
 *
 *  - Move stick (left): floating by default — appears under the thumb, follows it if dragged past the rim.
 *  - Attack stick (right): drag to aim (aim line shown in the arena), release to fire.
 *    Quick tap = auto-aim at nearest visible enemy. Drag back into the centre before releasing = cancel.
 *  - Super stick: same gestures, only live when the super is charged.
 *
 * Touch events arrive on the UI thread; the game thread reads via [poll]. All access is synchronized.
 */
class TouchControls(private val density: Float) {
    class Stick {
        var pointerId = -1
        var baseX = 0f
        var baseY = 0f
        var knobX = 0f
        var knobY = 0f
        var maxDrag = 0f
        val active get() = pointerId != -1
        fun reset() { pointerId = -1; maxDrag = 0f }
    }

    enum class FireMode { NONE, AIMED, AUTO }

    /** What the game thread consumes each tick. */
    class Input {
        var moveX = 0f
        var moveY = 0f
        var aimingAttack = false
        var aimingSuper = false
        var aimX = 0f
        var aimY = 0f
        var attack = FireMode.NONE
        var attackX = 0f
        var attackY = 0f
        var superFire = FireMode.NONE
        var superX = 0f
        var superY = 0f
        var pause = false
    }

    private val move = Stick()
    private val attack = Stick()
    private val superStick = Stick()

    private var width = 1f
    private var height = 1f
    private var safeLeft = 0f
    private var safeRight = 0f

    var moveRadius = 0f; private set
    var attackRadius = 0f; private set
    var superRadius = 0f; private set
    private var fixedMoveX = 0f
    private var fixedMoveY = 0f
    var attackCx = 0f; private set
    var attackCy = 0f; private set
    var superCx = 0f; private set
    var superCy = 0f; private set
    val pauseRect = RectF()

    private var settings = Settings()

    // Pending one-shot actions (set on UI thread, consumed on game thread)
    private var pendingAttack = FireMode.NONE
    private var pendingAttackX = 0f
    private var pendingAttackY = 0f
    private var pendingSuper = FireMode.NONE
    private var pendingSuperX = 0f
    private var pendingSuperY = 0f
    private var pendingPause = false

    /** Set by the game thread so the super stick knows whether to grab touches. */
    @Volatile var superReady = false

    private fun dp(v: Float) = v * density

    @Synchronized
    fun configure(s: Settings, w: Int, h: Int, insetLeft: Int, insetRight: Int) {
        settings = s
        width = w.toFloat(); height = h.toFloat()
        safeLeft = insetLeft.toFloat(); safeRight = insetRight.toFloat()
        // Keep controls thumb-sized in physical terms, but never let them crowd a small screen.
        val k = s.controlScale * min(1f, height / dp(360f)).coerceAtLeast(0.8f)
        moveRadius = dp(64f) * k
        attackRadius = dp(60f) * k
        superRadius = dp(42f) * k
        fixedMoveX = safeLeft + dp(44f) + moveRadius * 1.15f
        fixedMoveY = height - dp(36f) - moveRadius * 1.15f
        attackCx = width - safeRight - dp(48f) - attackRadius * 1.25f
        attackCy = height - dp(36f) - attackRadius * 1.25f
        superCx = attackCx - attackRadius * 1.9f
        superCy = attackCy - attackRadius * 1.05f
        val ps = dp(46f)
        pauseRect.set(safeLeft + dp(16f), dp(14f), safeLeft + dp(16f) + ps, dp(14f) + ps)
    }

    @Synchronized
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) drag(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i), cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> {
                move.reset(); attack.reset(); superStick.reset()
            }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val pr = RectF(pauseRect).apply { inset(-dp(10f), -dp(10f)) }
        if (pr.contains(x, y)) { pendingPause = true; return }

        if (!superStick.active && superReady && hypot(x - superCx, y - superCy) < superRadius * 1.5f) {
            grab(superStick, id, superCx, superCy, x, y); return
        }
        if (!attack.active && hypot(x - attackCx, y - attackCy) < attackRadius * 1.7f) {
            grab(attack, id, attackCx, attackCy, x, y); return
        }
        if (!move.active && x < width * 0.5f) {
            if (settings.moveStickMode == MoveStickMode.FIXED) {
                grab(move, id, fixedMoveX, fixedMoveY, x, y)
            } else {
                val bx = x.coerceIn(safeLeft + moveRadius + dp(8f), width * 0.5f - moveRadius)
                val by = y.coerceIn(moveRadius + dp(60f), height - moveRadius - dp(8f))
                grab(move, id, bx, by, x, y)
            }
        }
    }

    private fun grab(s: Stick, id: Int, bx: Float, by: Float, x: Float, y: Float) {
        s.pointerId = id
        s.baseX = bx; s.baseY = by
        s.maxDrag = 0f
        setKnob(s, x, y, s === move)
    }

    private fun drag(id: Int, x: Float, y: Float) {
        when (id) {
            move.pointerId -> setKnob(move, x, y, follow = settings.moveStickMode == MoveStickMode.FLOATING)
            attack.pointerId -> setKnob(attack, x, y, false)
            superStick.pointerId -> setKnob(superStick, x, y, false)
        }
    }

    private fun radiusOf(s: Stick) = when (s) { move -> moveRadius; attack -> attackRadius; else -> superRadius * 1.4f }

    private fun setKnob(s: Stick, x: Float, y: Float, follow: Boolean) {
        val r = radiusOf(s)
        var dx = x - s.baseX
        var dy = y - s.baseY
        val d = hypot(dx, dy)
        if (d > r) {
            if (follow) {
                // Base trails the thumb so you never "run out" of stick.
                s.baseX = x - dx / d * r
                s.baseY = y - dy / d * r
            }
            dx = dx / d * r; dy = dy / d * r
        }
        s.knobX = dx / r
        s.knobY = dy / r
        s.maxDrag = maxOf(s.maxDrag, hypot(s.knobX, s.knobY))
    }

    private fun up(id: Int, x: Float, y: Float, cancelled: Boolean) {
        when (id) {
            move.pointerId -> move.reset()
            attack.pointerId -> {
                val (mode, ax, ay) = release(attack)
                if (!cancelled && mode != FireMode.NONE) { pendingAttack = mode; pendingAttackX = ax; pendingAttackY = ay }
                attack.reset()
            }
            superStick.pointerId -> {
                val (mode, ax, ay) = release(superStick)
                if (!cancelled && mode != FireMode.NONE) { pendingSuper = mode; pendingSuperX = ax; pendingSuperY = ay }
                superStick.reset()
            }
        }
    }

    private fun release(s: Stick): Triple<FireMode, Float, Float> {
        val cur = hypot(s.knobX, s.knobY)
        return when {
            s.maxDrag < TAP_THRESHOLD -> Triple(if (settings.tapToAutoAim) FireMode.AUTO else FireMode.AIMED, 0f, 0f)
            cur < CANCEL_THRESHOLD -> Triple(FireMode.NONE, 0f, 0f)
            else -> Triple(FireMode.AIMED, s.knobX, s.knobY)
        }
    }

    /** Called by the game thread once per tick. */
    @Synchronized
    fun poll(out: Input) {
        if (move.active) {
            val mag = hypot(move.knobX, move.knobY)
            // Dead zone, then quickly ramp to full speed: precise but never sluggish.
            val k = if (mag < 0.12f) 0f else min(1f, (mag - 0.12f) / 0.45f + 0.35f) / mag
            out.moveX = move.knobX * k
            out.moveY = move.knobY * k
        } else {
            out.moveX = 0f; out.moveY = 0f
        }
        out.aimingAttack = attack.active && attack.maxDrag >= TAP_THRESHOLD && hypot(attack.knobX, attack.knobY) >= CANCEL_THRESHOLD
        out.aimingSuper = superStick.active && superStick.maxDrag >= TAP_THRESHOLD && hypot(superStick.knobX, superStick.knobY) >= CANCEL_THRESHOLD
        when {
            out.aimingSuper -> { out.aimX = superStick.knobX; out.aimY = superStick.knobY }
            out.aimingAttack -> { out.aimX = attack.knobX; out.aimY = attack.knobY }
        }
        out.attack = pendingAttack; out.attackX = pendingAttackX; out.attackY = pendingAttackY
        out.superFire = pendingSuper; out.superX = pendingSuperX; out.superY = pendingSuperY
        out.pause = pendingPause
        pendingAttack = FireMode.NONE
        pendingSuper = FireMode.NONE
        pendingPause = false
    }

    @Synchronized
    fun reset() {
        move.reset(); attack.reset(); superStick.reset()
        pendingAttack = FireMode.NONE; pendingSuper = FireMode.NONE; pendingPause = false
    }

    // ------------------------------------------------------------------ drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val arc = RectF()

    /** Draws controls in screen space. [ammo] is 0..ammoMax, [superCharge] 0..1. */
    @Synchronized
    fun draw(c: Canvas, ammo: Float, ammoMax: Int, superCharge: Float, alive: Boolean, time: Float) {
        val a = (settings.controlOpacity * 255).toInt()

        // ---- Move stick
        val mx = if (move.active) move.baseX else fixedMoveX
        val my = if (move.active) move.baseY else fixedMoveY
        val showMove = move.active || settings.moveStickMode == MoveStickMode.FIXED
        if (showMove) {
            ring(c, mx, my, moveRadius, Color.argb(a * 45 / 255, 20, 10, 50), Color.argb(a * 140 / 255, 255, 255, 255))
            fill.color = Color.argb(a * 230 / 255, 46, 196, 241)
            c.drawCircle(mx + move.knobX * moveRadius, my + move.knobY * moveRadius, moveRadius * 0.42f, fill)
            line.color = Color.argb(a, 11, 6, 32); line.strokeWidth = dp(3f)
            c.drawCircle(mx + move.knobX * moveRadius, my + move.knobY * moveRadius, moveRadius * 0.42f, line)
        } else {
            // Hint where to put the thumb.
            fill.color = Color.argb(a * 35 / 255, 255, 255, 255)
            c.drawCircle(fixedMoveX, fixedMoveY, moveRadius * 0.42f, fill)
        }

        // ---- Attack stick
        val ready = ammo >= 1f && alive
        ring(c, attackCx, attackCy, attackRadius, Color.argb(a * 60 / 255, 40, 10, 10), Color.argb(a * 150 / 255, 255, 255, 255))
        // Ammo segments around the rim
        val segs = ammoMax
        val gap = 8f
        val sweep = (360f - gap * segs) / segs
        arc.set(attackCx - attackRadius * 1.12f, attackCy - attackRadius * 1.12f, attackCx + attackRadius * 1.12f, attackCy + attackRadius * 1.12f)
        line.strokeWidth = dp(7f)
        for (i in 0 until segs) {
            val start = -90f + i * (sweep + gap) + gap / 2
            line.color = Color.argb(a * 120 / 255, 11, 6, 32)
            c.drawArc(arc, start, sweep, false, line)
            val f = (ammo - i).coerceIn(0f, 1f)
            if (f > 0f) {
                line.color = if (f >= 1f) Color.argb(a, 255, 159, 28) else Color.argb(a * 200 / 255, 255, 220, 160)
                c.drawArc(arc, start, sweep * f, false, line)
            }
        }
        val kx = attackCx + attack.knobX * attackRadius
        val ky = attackCy + attack.knobY * attackRadius
        fill.color = if (ready) Color.argb(a, 255, 122, 26) else Color.argb(a * 160 / 255, 120, 110, 130)
        c.drawCircle(kx, ky, attackRadius * 0.46f, fill)
        line.color = Color.argb(a, 11, 6, 32); line.strokeWidth = dp(3f)
        c.drawCircle(kx, ky, attackRadius * 0.46f, line)
        crosshair(c, kx, ky, attackRadius * 0.2f, Color.argb(a, 255, 255, 255))

        // ---- Super stick
        val sk = superStick
        val sx = superCx + sk.knobX * superRadius * 1.4f
        val sy = superCy + sk.knobY * superRadius * 1.4f
        val sr = superRadius
        if (superCharge >= 1f && alive) {
            val pulse = 1f + 0.06f * kotlin.math.sin(time * 6f)
            fill.color = Color.argb(a * 90 / 255, 255, 214, 64)
            c.drawCircle(superCx, superCy, sr * 1.35f * pulse, fill)
            if (sk.active) ring(c, superCx, superCy, sr * 1.4f, Color.argb(a * 50 / 255, 60, 40, 0), Color.argb(a * 160 / 255, 255, 230, 120))
            fill.color = Color.argb(a, 255, 205, 40)
            c.drawCircle(sx, sy, sr, fill)
        } else {
            fill.color = Color.argb(a * 150 / 255, 40, 30, 70)
            c.drawCircle(superCx, superCy, sr, fill)
            line.color = Color.argb(a, 255, 205, 40); line.strokeWidth = dp(5f)
            arc.set(superCx - sr * 0.82f, superCy - sr * 0.82f, superCx + sr * 0.82f, superCy + sr * 0.82f)
            c.drawArc(arc, -90f, 360f * superCharge.coerceIn(0f, 1f), false, line)
        }
        line.color = Color.argb(a, 11, 6, 32); line.strokeWidth = dp(3f)
        c.drawCircle(if (superCharge >= 1f) sx else superCx, if (superCharge >= 1f) sy else superCy, sr, line)
        // Star glyph
        star(c, if (superCharge >= 1f) sx else superCx, if (superCharge >= 1f) sy else superCy, sr * 0.45f,
            if (superCharge >= 1f) Color.argb(a, 60, 30, 0) else Color.argb(a * 160 / 255, 255, 255, 255))

        // ---- Pause
        fill.color = Color.argb(200, 20, 12, 50)
        c.drawRoundRect(pauseRect, dp(10f), dp(10f), fill)
        line.color = Color.argb(255, 11, 6, 32); line.strokeWidth = dp(3f)
        c.drawRoundRect(pauseRect, dp(10f), dp(10f), line)
        fill.color = Color.WHITE
        val pw = pauseRect.width()
        c.drawRoundRect(pauseRect.left + pw * 0.3f, pauseRect.top + pw * 0.27f, pauseRect.left + pw * 0.43f, pauseRect.bottom - pw * 0.27f, dp(2f), dp(2f), fill)
        c.drawRoundRect(pauseRect.left + pw * 0.57f, pauseRect.top + pw * 0.27f, pauseRect.left + pw * 0.7f, pauseRect.bottom - pw * 0.27f, dp(2f), dp(2f), fill)
    }

    private fun ring(c: Canvas, x: Float, y: Float, r: Float, inner: Int, rim: Int) {
        fill.color = inner
        c.drawCircle(x, y, r, fill)
        line.color = rim; line.strokeWidth = dp(3f)
        c.drawCircle(x, y, r, line)
    }

    private fun crosshair(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        line.color = color; line.strokeWidth = dp(3f)
        c.drawLine(x - r, y, x - r * 0.4f, y, line)
        c.drawLine(x + r * 0.4f, y, x + r, y, line)
        c.drawLine(x, y - r, x, y - r * 0.4f, line)
        c.drawLine(x, y + r * 0.4f, x, y + r, line)
    }

    private val starPath = android.graphics.Path()
    private fun star(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        starPath.reset()
        for (i in 0 until 10) {
            val ang = -Math.PI / 2 + i * Math.PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val px = x + (kotlin.math.cos(ang) * rr).toFloat()
            val py = y + (kotlin.math.sin(ang) * rr).toFloat()
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        fill.color = color
        c.drawPath(starPath, fill)
    }

    companion object {
        const val TAP_THRESHOLD = 0.28f
        const val CANCEL_THRESHOLD = 0.2f
    }
}
