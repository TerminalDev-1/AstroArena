package io.github.projectwip.match

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.View
import io.github.projectwip.sim.Phase
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 2D overlay drawn on the UI thread above the 3D view: overhead bars, damage numbers, score, kill feed,
 * countdown/banners and the touch controls. Reads [HudChannel] snapshots published by the renderer.
 */
@SuppressLint("ViewConstructor")
class HudView(
    context: Context,
    private val channel: HudChannel,
    private val runner: MatchRunner,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val controls = runner.controls
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val textStroke = Paint(text).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = INK }
    private val path = Path()
    private val rect = RectF()
    private val rng = Random(3)
    private val v4 = FloatArray(4)
    private val o4 = FloatArray(4)

    private class Floater(val x: Float, val y: Float, var lift: Float, val text: String, val color: Int, var life: Float, val big: Boolean, val jitter: Float)
    private class Feed(val killer: String, val killerTeam: Int, val victim: String, val victimTeam: Int, var life: Float)
    private val floaters = ArrayList<Floater>()
    private val feed = ArrayList<Feed>()
    private var pop = 0f
    private var bannerStart = -1f
    private var last = 0L
    private var time = 0f
    private val snap = HudSnapshot()

    private fun dp(v: Float) = v * density

    init { setWillNotDraw(false) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = controls.onTouch(event)

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        time += dt
        postInvalidateOnAnimation()
        if (!channel.hasFrame) return
        channel.read { snap.copyFrom(it) }

        while (true) {
            when (val e = runner.hudEvents.poll() ?: break) {
                is HudEvent.Damage -> floaters += Floater(e.x, e.y, 0f, e.amount.toString(),
                    if (e.mine) Color.WHITE else Color.rgb(255, 92, 92), 0.8f, e.big, (rng.nextFloat() - 0.5f) * dp(30f))
                is HudEvent.Ko -> { feed.add(0, Feed(e.killer, e.killerTeam, e.victim, e.victimTeam, 4.5f)); while (feed.size > 4) feed.removeAt(feed.lastIndex) }
                HudEvent.Pop -> pop = 1f
            }
        }

        drawOverheads(c)
        drawFloaters(c, dt)
        drawHud(c, dt)
        if (snap.phase != Phase.ENDED) controls.draw(c, snap.ammo, snap.ammoMax, snap.superCharge, snap.playerAlive && snap.phase == Phase.PLAYING, time)
        drawCoach(c)
    }

    // ------------------------------------------------------------------ overheads

    private fun drawOverheads(c: Canvas) {
        val s = snap
        for (i in 0 until s.n) {
            if (!s.visible[i]) continue
            val x = s.sx[i]
            val y = s.sy[i]
            val bw = dp(78f)
            val bh = dp(13f)
            val col = when (s.relation[i]) { 0 -> PLAYER; 1 -> ALLY; else -> ENEMY }
            text.textSize = dp(13f)
            outlined(c, s.names[i] ?: "", x, y - bh - dp(5f), if (s.relation[i] == 0) Color.rgb(255, 245, 160) else Color.WHITE, dp(3f))
            if (s.cells[i] > 0) {
                // Power Cell count badge to the left of the bar
                val bx = x - bw / 2 - dp(15f)
                val by = y - bh / 2
                fill.color = INK
                c.drawCircle(bx, by, dp(12f), fill)
                fill.color = Color.rgb(255, 214, 64)
                c.drawCircle(bx, by, dp(9.5f), fill)
                text.textSize = dp(12f)
                outlined(c, s.cells[i].toString(), bx, by + dp(4.3f), Color.WHITE, dp(2.5f))
            }
            rect.set(x - bw / 2, y - bh, x + bw / 2, y)
            fill.color = INK
            c.drawRoundRect(rect.left - dp(2.5f), rect.top - dp(2.5f), rect.right + dp(2.5f), rect.bottom + dp(2.5f), bh, bh, fill)
            fill.color = Color.rgb(50, 30, 70)
            c.drawRoundRect(rect, bh / 2, bh / 2, fill)
            val fr = (s.hp[i].toFloat() / s.maxHp[i]).coerceIn(0f, 1f)
            if (fr > 0f) {
                fill.color = col
                c.drawRoundRect(rect.left, rect.top, rect.left + bw * fr, rect.bottom, bh / 2, bh / 2, fill)
                fill.color = Color.argb(70, 255, 255, 255)
                c.drawRoundRect(rect.left + dp(2f), rect.top + dp(1.5f), rect.left + bw * fr - dp(2f), rect.top + bh * 0.42f, bh / 3, bh / 3, fill)
            }
            text.textSize = bh * 0.95f
            outlined(c, s.hp[i].toString(), x, rect.bottom - bh * 0.15f, Color.WHITE, dp(2.5f))
            if (s.relation[i] == 0) {
                val segW = (bw - dp(4f)) / s.ammoMax
                val ay = rect.bottom + dp(5f)
                for (k in 0 until s.ammoMax) {
                    val l = rect.left + k * (segW + dp(2f))
                    fill.color = INK
                    c.drawRoundRect(l - dp(1.5f), ay - dp(1.5f), l + segW + dp(1.5f), ay + dp(7.5f), dp(3f), dp(3f), fill)
                    val a = (s.ammo - k).coerceIn(0f, 1f)
                    fill.color = if (a >= 1f) Color.rgb(255, 159, 28) else Color.rgb(140, 110, 90)
                    if (a > 0f) c.drawRoundRect(l, ay, l + segW * a, ay + dp(6f), dp(2f), dp(2f), fill)
                }
            }
            if (s.ids[i] == s.autoTargetId) {
                // Auto-aim reticle tag over the enemy a tap would hit.
                val r = dp(11f) + sin(time * 8f) * dp(1.5f)
                stroke.color = INK; stroke.strokeWidth = dp(6f)
                c.drawCircle(x + bw / 2 + dp(16f), y - bh / 2, r, stroke)
                stroke.color = Color.rgb(255, 214, 64); stroke.strokeWidth = dp(3f)
                c.drawCircle(x + bw / 2 + dp(16f), y - bh / 2, r, stroke)
                c.drawLine(x + bw / 2 + dp(16f) - r * 1.4f, y - bh / 2, x + bw / 2 + dp(16f) - r * 0.5f, y - bh / 2, stroke)
                c.drawLine(x + bw / 2 + dp(16f) + r * 0.5f, y - bh / 2, x + bw / 2 + dp(16f) + r * 1.4f, y - bh / 2, stroke)
            }
        }
    }

    private fun project(x: Float, y: Float, z: Float): Boolean {
        v4[0] = x; v4[1] = y; v4[2] = z; v4[3] = 1f
        Matrix.multiplyMV(o4, 0, snap.viewProj, 0, v4, 0)
        if (o4[3] <= 0f) return false
        o4[0] = (o4[0] / o4[3] * 0.5f + 0.5f) * snap.width
        o4[1] = (1f - (o4[1] / o4[3] * 0.5f + 0.5f)) * snap.height
        return true
    }

    private fun drawFloaters(c: Canvas, dt: Float) {
        val it = floaters.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.life -= dt
            f.lift += dt * dp(70f)
            if (f.life <= 0f) { it.remove(); continue }
            if (!project(f.x, 1.6f, f.y)) continue
            val popS = if (f.life > 0.62f) 1f + (f.life - 0.62f) * 3.5f else 1f
            text.textSize = (if (f.big) dp(30f) else dp(22f)) * popS
            val a = (f.life / 0.3f).coerceIn(0f, 1f)
            textStroke.alpha = (a * 255).toInt()
            outlined(c, f.text, o4[0] + f.jitter, o4[1] - f.lift, withAlpha(f.color, a), dp(4.5f))
            textStroke.alpha = 255
        }
    }

    // ------------------------------------------------------------------ HUD

    private fun drawHud(c: Canvas, dt: Float) {
        val s = snap
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val top = dp(10f)
        val panelW = dp(260f)
        val panelH = dp(58f)
        chamfer(c, cx - panelW / 2, top, cx + panelW / 2, top + panelH, dp(13f), Color.argb(235, 34, 22, 84), INK)
        if (s.bossMode) {
            // Lives left, and the boss's health across a wide bar under the panel.
            chamfer(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + dp(92f), top + panelH - dp(6f), dp(9f), Color.rgb(28, 110, 200), null)
            text.textSize = dp(30f)
            outlined(c, s.livesLeft.toString(), cx - panelW / 2 + dp(52f), top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            text.textSize = dp(17f)
            outlined(c, if (s.livesLeft == 1) "LIFE LEFT" else "LIVES LEFT", cx + dp(40f), top + dp(27f), Color.WHITE, dp(3.5f))
            text.textSize = dp(12f)
            outlined(c, "BOSS MODE", cx + dp(40f), top + dp(46f), Color.rgb(255, 214, 64), dp(3f))
            val bw = dp(420f)
            val by = top + panelH + dp(26f)
            val fr = (s.bossHp.toFloat() / s.bossMaxHp).coerceIn(0f, 1f)
            fill.color = INK
            c.drawRoundRect(cx - bw / 2 - dp(3f), by - dp(3f), cx + bw / 2 + dp(3f), by + dp(19f), dp(10f), dp(10f), fill)
            fill.color = Color.rgb(50, 30, 70)
            c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + dp(16f), dp(8f), dp(8f), fill)
            if (fr > 0f) {
                fill.color = ENEMY
                c.drawRoundRect(cx - bw / 2, by, cx - bw / 2 + bw * fr, by + dp(16f), dp(8f), dp(8f), fill)
                fill.color = Color.argb(70, 255, 255, 255)
                c.drawRoundRect(cx - bw / 2 + dp(3f), by + dp(2f), cx - bw / 2 + bw * fr - dp(3f), by + dp(7f), dp(4f), dp(4f), fill)
            }
            text.textSize = dp(13f)
            outlined(c, "${(s.bossName ?: "BOSS").uppercase()}  ·  %,d".format(s.bossHp), cx, by + dp(13f), Color.WHITE, dp(3f))
        } else if (s.freeForAll) {
            // Fighters left + mode name
            chamfer(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + dp(92f), top + panelH - dp(6f), dp(9f), Color.rgb(200, 40, 64), null)
            text.textSize = dp(30f)
            outlined(c, s.aliveCount.toString(), cx - panelW / 2 + dp(52f), top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            text.textSize = dp(19f)
            outlined(c, "LEFT", cx + dp(30f), top + dp(29f), Color.WHITE, dp(3.5f))
            text.textSize = dp(11f)
            outlined(c, "LAST SPARK", cx + dp(30f), top + dp(47f), Color.rgb(255, 214, 64), dp(3f))
        } else {
            val boxW = dp(72f)
            chamfer(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + boxW, top + panelH - dp(6f), dp(9f), Color.rgb(28, 110, 200), null)
            chamfer(c, cx + panelW / 2 - dp(6f) - boxW, top + dp(6f), cx + panelW / 2 - dp(6f), top + panelH - dp(6f), dp(9f), Color.rgb(200, 40, 64), null)
            text.textSize = dp(30f)
            outlined(c, s.myScore.toString(), cx - panelW / 2 + dp(6f) + boxW / 2, top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            outlined(c, s.theirScore.toString(), cx + panelW / 2 - dp(6f) - boxW / 2, top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            val secs = ceil(s.timeLeft).toInt()
            text.textSize = dp(23f)
            outlined(c, "%d:%02d".format(secs / 60, secs % 60), cx, top + dp(29f), if (secs <= 15 && s.phase == Phase.PLAYING) Color.rgb(255, 120, 100) else Color.WHITE, dp(3.5f))
            text.textSize = dp(11f)
            outlined(c, "FIRST TO ${s.koTarget}", cx, top + dp(47f), Color.rgb(255, 214, 64), dp(3f))
        }

        // Storm warnings
        if (s.freeForAll && s.phase == Phase.PLAYING) {
            val delay = io.github.projectwip.data.Balance.STORM_DELAY_SECONDS
            if (s.stormElapsed in (delay - 5f)..(delay + 3f)) {
                text.textSize = dp(20f)
                val flash = if ((time * 3f).toInt() % 2 == 0) Color.rgb(200, 150, 255) else Color.WHITE
                outlined(c, "THE STATIC STORM IS CLOSING IN!", cx, top + panelH + dp(32f), flash, dp(4f))
            }
            if (s.playerOutsideStorm) {
                val a = (0.35f + 0.2f * sin(time * 8f))
                fill.shader = android.graphics.RadialGradient(cx, h / 2, maxOf(w, h) * 0.7f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((a * 255).toInt(), 120, 40, 200)), floatArrayOf(0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                text.textSize = dp(19f)
                outlined(c, "YOU'RE IN THE STORM — GET BACK INSIDE!", cx, h * 0.24f, Color.rgb(230, 190, 255), dp(4f))
            }
        }

        // Kill feed
        var fy = top + dp(18f)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(15f)
        val it = feed.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.life -= dt
            if (e.life <= 0f) { it.remove(); continue }
            val a = (e.life / 0.5f).coerceIn(0f, 1f)
            val right = w - dp(20f)
            textStroke.alpha = (a * 255).toInt()
            val vw = text.measureText(e.victim)
            outlined(c, e.victim, right, fy, withAlpha(teamCol(e.victimTeam, s.playerTeam), a), dp(3f))
            outlined(c, " ✕ ", right - vw, fy, withAlpha(Color.WHITE, a), dp(3f))
            outlined(c, e.killer, right - vw - text.measureText(" ✕ "), fy, withAlpha(teamCol(e.killerTeam, s.playerTeam), a), dp(3f))
            textStroke.alpha = 255
            fy += dp(22f)
        }
        text.textAlign = Paint.Align.CENTER

        pop = max(0f, pop - dt * 2.2f)
        val big = 1f + pop * 0.6f
        if (s.phase == Phase.COUNTDOWN) {
            val n = ceil(s.countdownSeconds - s.phaseTime).toInt().coerceAtLeast(1)
            text.textSize = dp(96f) * big
            outlined(c, n.toString(), cx, h * 0.45f, Color.WHITE, dp(9f))
            text.textSize = dp(21f)
            outlined(c, if (s.bossMode) "BOSS MODE · KNOCK OUT THE GIANT BEFORE YOU RUN OUT OF LIVES" else if (s.freeForAll) "LAST SPARK · LAST ONE STANDING WINS" else "KNOCKOUT RUSH · FIRST TO ${s.koTarget} KOs", cx, h * 0.45f + dp(48f), Color.rgb(255, 214, 64), dp(4.5f))
        } else if (s.phase == Phase.PLAYING && s.phaseTime < 0.9f) {
            text.textSize = dp(80f) * big
            outlined(c, "FIGHT!", cx, h * 0.45f, Color.rgb(255, 159, 28), dp(9f))
        }

        if (!s.playerAlive && s.phase == Phase.PLAYING && !s.freeForAll) {
            text.textSize = dp(28f)
            outlined(c, "KNOCKED OUT", cx, h * 0.4f, Color.rgb(255, 92, 92), dp(5f))
            text.textSize = dp(19f)
            outlined(c, "Back in ${ceil(s.respawnTimer).toInt().coerceAtLeast(1)}…", cx, h * 0.4f + dp(32f), Color.WHITE, dp(4f))
        }

        val ffaOut = s.freeForAll && s.placement > 0
        if (ffaOut && bannerStart < 0f) bannerStart = time
        if (s.phase == Phase.ENDED || ffaOut) {
            val slide = if (ffaOut) ((time - bannerStart) / 0.35f).coerceIn(0f, 1f) else (s.phaseTime / 0.35f).coerceIn(0f, 1f)
            val (label, col) = when {
                s.freeForAll && s.placement == 1 -> "VICTORY! #1" to Color.rgb(255, 214, 64)
                s.freeForAll -> "#${s.placement} PLACE" to if (s.placement <= 4) Color.rgb(120, 230, 255) else Color.rgb(255, 92, 92)
                s.winningTeam == s.playerTeam -> "VICTORY!" to Color.rgb(255, 214, 64)
                s.winningTeam == -1 -> "DRAW" to Color.WHITE
                else -> "DEFEAT" to Color.rgb(255, 92, 92)
            }
            fill.color = Color.argb((170 * slide).toInt(), 10, 5, 30)
            c.drawRect(0f, h * 0.34f, w, h * 0.34f + dp(124f), fill)
            text.textSize = dp(86f) * (1.4f - 0.4f * slide)
            textStroke.alpha = (255 * slide).toInt()
            outlined(c, label, cx, h * 0.34f + dp(90f), withAlpha(col, slide), dp(10f))
            textStroke.alpha = 255
        }

        if (runner.settings.showFps) {
            text.textAlign = Paint.Align.LEFT
            text.textSize = dp(12f)
            outlined(c, "${s.fps} FPS", dp(80f), dp(34f), Color.rgb(160, 255, 160), dp(3f))
            text.textAlign = Paint.Align.CENTER
        }
    }

    /** First few matches: teach the two attack gestures right next to the stick. */
    private fun drawCoach(c: Canvas) {
        val s = snap
        if (s.matchesPlayed >= 3 || s.phase != Phase.PLAYING || s.phaseTime > 14f || !s.playerAlive) return
        val a = ((14f - s.phaseTime) / 1.5f).coerceIn(0f, 1f) * (s.phaseTime / 0.6f).coerceIn(0f, 1f)
        val x = controls.attackCx
        val y = controls.attackCy - controls.attackRadius * 2.05f
        text.textSize = dp(15f)
        textStroke.alpha = (a * 255).toInt()
        outlined(c, "TAP = AUTO-AIM", x - dp(10f), y, withAlpha(Color.rgb(255, 214, 64), a), dp(3.5f))
        outlined(c, "DRAG = AIM · RELEASE = FIRE", x - dp(10f), y + dp(20f), withAlpha(Color.WHITE, a), dp(3.5f))
        if (s.freeForAll) {
            text.textSize = dp(17f)
            outlined(c, "BREAK GLOWING CRATES FOR POWER CELLS · LAST ONE STANDING WINS", width / 2f, dp(100f), withAlpha(Color.rgb(255, 214, 64), a), dp(4f))
        }
        textStroke.alpha = 255
    }

    private fun chamfer(c: Canvas, l: Float, t: Float, r: Float, b: Float, cut: Float, color: Int, outline: Int?) {
        path.reset()
        path.moveTo(l + cut, t); path.lineTo(r - cut * 0.4f, t); path.lineTo(r, t + cut * 0.4f); path.lineTo(r, b - cut)
        path.lineTo(r - cut, b); path.lineTo(l + cut * 0.4f, b); path.lineTo(l, b - cut * 0.4f); path.lineTo(l, t + cut); path.close()
        fill.color = color
        c.drawPath(path, fill)
        val mid = maxOf(t + cut, t + (b - t) * 0.45f)
        path.reset()
        path.moveTo(l + cut, t); path.lineTo(r - cut * 0.4f, t); path.lineTo(r, t + cut * 0.4f); path.lineTo(r, mid); path.lineTo(l, mid); path.lineTo(l, t + cut); path.close()
        fill.color = Color.argb(46, 255, 255, 255)
        c.drawPath(path, fill)
        val low = minOf(b - cut, b - (b - t) * 0.25f)
        path.reset()
        path.moveTo(l, low); path.lineTo(r, low); path.lineTo(r, b - cut); path.lineTo(r - cut, b); path.lineTo(l + cut * 0.4f, b); path.lineTo(l, b - cut * 0.4f); path.close()
        fill.color = Color.argb(60, 0, 0, 0)
        c.drawPath(path, fill)
        path.reset()
        path.moveTo(l + cut, t); path.lineTo(r - cut * 0.4f, t); path.lineTo(r, t + cut * 0.4f); path.lineTo(r, b - cut)
        path.lineTo(r - cut, b); path.lineTo(l + cut * 0.4f, b); path.lineTo(l, b - cut * 0.4f); path.lineTo(l, t + cut); path.close()
        if (outline != null) { stroke.color = outline; stroke.strokeWidth = dp(3f); c.drawPath(path, stroke) }
    }

    private fun outlined(c: Canvas, s: String, x: Float, y: Float, color: Int, outline: Float) {
        textStroke.textSize = text.textSize
        textStroke.textAlign = text.textAlign
        textStroke.strokeWidth = outline
        c.drawText(s, x, y, textStroke)
        text.color = color
        c.drawText(s, x, y, text)
    }

    private fun teamCol(team: Int, mine: Int) = when (team) {
        -2 -> Color.rgb(200, 150, 255) // the storm
        mine -> ALLY
        else -> ENEMY
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        val INK = Color.rgb(27, 16, 53)
        val PLAYER = Color.rgb(92, 255, 122)
        val ALLY = Color.rgb(63, 182, 255)
        val ENEMY = Color.rgb(255, 77, 94)
    }
}
