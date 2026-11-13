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
    /** The Entity whispering in the player's head (the user's lore): what, how long ago, and when next. */
    private var whisper: String? = null
    private var whisperAge = 0f
    private var nextWhisper = 25f
    private var whispers = 0
    private var lastSuper = 0f
    private var lastHyper = 0f
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
        drawWhisper(c, dt)
        drawPossessed(c)
        if (snap.portalRun) drawDimension(c)
        drawHud(c, dt)
        if (snap.phase != Phase.ENDED) controls.draw(c, snap.ammo, snap.ammoMax, snap.superCharge, snap.hyperCharge, snap.hyperLeft, snap.playerAlive && snap.phase == Phase.PLAYING, time)
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
            if (s.poisoned[i]) {
                // Malformed code: zeros and ones streaming up off their head for as long as the poison runs.
                text.textSize = dp(15f)
                for (k in 0 until 7) {
                    val run = time * 0.85f + k * 0.37f + i * 0.21f
                    val step = run.toInt()
                    val up = run - step
                    val alpha = (sin(up * 3.1416f) * 255f).toInt().coerceIn(0, 255)
                    val digit = if ((step * 31 + k * 17 + i * 7) % 3 == 0) "0" else "1"
                    val dx = (k - 3) * dp(12f) + sin(run * 2.3f + k) * dp(3f)
                    val dy = y - bh - dp(22f) - up * dp(40f)
                    textStroke.alpha = alpha
                    outlined(c, digit, x + dx, dy, Color.argb(alpha, 96, 255, 130), dp(2.5f))
                }
                textStroke.alpha = 255
                text.textSize = dp(13f)
            }
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
            // A running hyper turns the bar's rim violet.
            fill.color = if (s.hyper[i]) Color.rgb(190, 96, 255) else INK
            c.drawRoundRect(rect.left - dp(2.5f), rect.top - dp(2.5f), rect.right + dp(2.5f), rect.bottom + dp(2.5f), bh, bh, fill)
            fill.color = Color.rgb(22, 40, 62)
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
                var below = rect.bottom + dp(5f)
                if (s.edge >= 0f) {
                    // The blade's bar, between the health and the ammo: it fills by itself, and glows when it is full.
                    val full = s.edge >= 1f
                    fill.color = INK
                    c.drawRoundRect(rect.left - dp(1.5f), below - dp(1.5f), rect.right + dp(1.5f), below + dp(7.5f), dp(3f), dp(3f), fill)
                    fill.color = if (full) Color.rgb(150, 255, 240) else Color.rgb(60, 190, 255)
                    if (full) fill.alpha = (200 + 55 * sin(time * 9f)).toInt()
                    c.drawRoundRect(rect.left, below, rect.left + bw * s.edge.coerceIn(0f, 1f), below + dp(6f), dp(2f), dp(2f), fill)
                    fill.alpha = 255
                    below += dp(11f)
                }
                val segW = (bw - dp(4f)) / s.ammoMax
                val ay = below
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

    // ------------------------------------------------------------------ Dimension

    /**
     * The Entity whispering to the player (the user's lore: it lives in every fighter's mind and gives them their
     * power, and taunts them for it): now and then on its own, and when they use a super or a hypercharge. Faint,
     * blue, under the top of the screen; a letter or two comes out wrong. Not in Dimension, where it already talks.
     */
    private fun drawWhisper(c: Canvas, dt: Float) {
        val s = snap
        if (s.portalRun || s.phase != Phase.PLAYING) { lastSuper = s.superCharge; lastHyper = s.hyperLeft; return }
        val usedSuper = lastSuper >= 0.999f && s.superCharge < 0.5f
        val usedHyper = lastHyper <= 0f && s.hyperLeft > 0f
        lastSuper = s.superCharge; lastHyper = s.hyperLeft
        nextWhisper -= dt
        if ((usedSuper || usedHyper) && (whisper == null || whisperAge > 2.5f)) {
            whisper = io.github.projectwip.data.Lore.powerWhisper(whispers++); whisperAge = 0f; nextWhisper = maxOf(nextWhisper, 18f)
        } else if (nextWhisper <= 0f) {
            whisper = io.github.projectwip.data.Lore.whisper(whispers++); whisperAge = 0f; nextWhisper = 30f + rng.nextFloat() * 30f
        }
        val line = whisper ?: return
        whisperAge += dt
        if (whisperAge > 3.6f) { whisper = null; return }
        val a = minOf(whisperAge / 0.5f, 1f, (3.6f - whisperAge) / 0.9f).coerceIn(0f, 1f) * 0.85f
        // On the game's own plate (the user asked): purple, ink outline, white words, a nameplate tab on top.
        val jitter = if ((time * 13f).toInt() % 9 == 0) dp(2f) else 0f
        entityPlate(c, glitchText(line, if ((time * 7f).toInt() % 5 == 0) 1f else 0f), null, width / 2f + jitter, height * 0.3f, a)
    }

    /**
     * The player glitching (the user's lore: the Entity takes over and fights through them for a few seconds): a blue
     * edge closing in, torn bands, and the Entity's words with the seconds left.
     */
    private fun drawPossessed(c: Canvas) {
        val left = snap.playerGlitch
        if (left <= 0f || snap.phase != Phase.PLAYING || runner.hijack != 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val a = minOf(1f, left / 0.4f).coerceIn(0f, 1f)
        fill.shader = android.graphics.RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.65f, intArrayOf(Color.TRANSPARENT, Color.argb((40 * a).toInt(), 40, 140, 255), Color.argb((170 * a).toInt(), 20, 90, 255)), floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        val beat = (time * 12f).toInt()
        for (i in 0 until 3) {
            val y = ((beat * 7919 + i * 104729) % 1000) / 1000f * h
            fill.color = Color.argb((70 * a).toInt(), 90, 200, 255)
            c.drawRect(0f, y, w, y + dp(3f), fill)
        }
        entityPlate(c, glitchText("THE ENTITY HAS YOU", 1f), "IT FIGHTS FOR YOU · %.1f".format(left), w / 2 + (if (beat % 4 == 0) dp(3f) else 0f), h * 0.64f, a)
    }

    /**
     * The Entity's words on AstroArena's own plate (the user asked for the game's theme): a chamfered purple plate
     * with a thick ink outline and a gloss, a brighter purple "THE ENTITY" nameplate standing on its top edge, and the
     * words in white with an ink outline; [sub] is a smaller second line. [y] is the middle of the plate.
     */
    private fun entityPlate(c: Canvas, words: String, sub: String?, x: Float, y: Float, a: Float) {
        if (a <= 0f) return
        val alpha = (255 * a).toInt().coerceIn(0, 255)
        text.textSize = dp(20f)
        val wMain = text.measureText(words)
        var wSub = 0f
        if (sub != null) { text.textSize = dp(13f); wSub = text.measureText(sub) }
        val pw = maxOf(wMain, wSub) + dp(48f)
        val ph = if (sub != null) dp(62f) else dp(42f)
        val l = x - pw / 2; val t = y - ph / 2
        val ink = Color.argb(alpha, 27, 16, 53)
        chamfer(c, l, t, l + pw, t + ph, dp(11f), Color.argb((235 * a).toInt(), 45, 29, 116), ink)
        // The nameplate tab.
        text.textSize = dp(11f)
        val tag = "THE ENTITY"
        val tw = text.measureText(tag) + dp(22f)
        chamfer(c, l + dp(14f), t - dp(12f), l + dp(14f) + tw, t + dp(10f), dp(6f), Color.argb(alpha, 120, 60, 220), ink)
        outlined(c, tag, l + dp(14f) + tw / 2, t + dp(3f), Color.argb(alpha, 255, 255, 255), dp(2.5f))
        text.textSize = dp(20f)
        outlined(c, words, x, if (sub != null) y - dp(2f) else y + dp(7f), Color.argb(alpha, 255, 255, 255), dp(3.5f))
        if (sub != null) {
            text.textSize = dp(13f)
            outlined(c, sub, x, y + dp(20f), Color.argb(alpha, 214, 200, 255), dp(2.5f))
        }
    }

    /** During one of the world's glitch moments, a few letters of a word come out wrong. */
    private fun glitchText(s: String, glitch: Float): String {
        if (glitch <= 0f) return s
        val beat = (time * 12f).toInt()
        val glyphs = "#%&?/\\|*01"
        val b = StringBuilder(s)
        for (k in 0 until maxOf(1, s.length / 6)) {
            val i = ((beat * 31 + k * 17) and 0x7FFF) % b.length
            if (b[i] != ' ') b.setCharAt(i, glyphs[(beat + k * 5) % glyphs.length])
        }
        return b.toString()
    }

    /**
     * Dimension's layer over the world: a pointer toward the nearest echo (or the open portal), the chained fighter
     * screaming for help, the Void's taunts (in one of its souls' voices now and then), a dark vignette that closes in
     * the deeper it goes, and, only during the world's rare glitch moments, a quiet tear across the picture. Creepy,
     * never loud: the user found constant glitching too much.
     */
    private fun drawDimension(c: Canvas) {
        val s = snap
        val w = width.toFloat(); val h = height.toFloat()
        val depth = s.portalDimension
        val glitch = s.portalGlitch

        // The vignette first, under everything else on the HUD: darker the deeper it goes, up to a point.
        val k = (0.25f + 0.07f * (depth - 1)).coerceAtMost(0.7f)
        fill.shader = android.graphics.RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.72f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((255 * k).toInt(), 6, 2, 14)), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // The chained fighter, screaming for help now and then.
        if (s.captiveOn && s.phase == Phase.PLAYING && project(s.captiveX, 2.1f, s.captiveZ)) {
            val cycle = time % 3.2f
            if (cycle < 1.3f) {
                val a = (minOf(cycle / 0.12f, 1f, (1.3f - cycle) / 0.3f)).coerceIn(0f, 1f)
                val jx = (rng.nextFloat() - 0.5f) * dp(4f); val jy = (rng.nextFloat() - 0.5f) * dp(3f)
                text.textSize = dp(22f + 3f * sin(time * 20f))
                outlined(c, "HELP!", o4[0] + jx, o4[1] + jy, withAlpha(Color.rgb(255, 236, 236), a), dp(4f))
            }
            text.textSize = dp(11f)
            outlined(c, s.captiveName.uppercase(), o4[0], o4[1] + dp(20f), withAlpha(Color.rgb(205, 180, 255), 0.85f), dp(2.5f))
        }

        // The freed soul, talking as they follow: a speech bubble over their head, typed out, in their colour.
        val said = s.followerLine
        if (s.followerOn && said != null && s.phase == Phase.PLAYING && project(s.followerX, 2.2f, s.followerZ)) {
            val a = (minOf(s.followerAge / 0.25f, 1f, (4.2f - s.followerAge) / 0.4f)).coerceIn(0f, 1f)
            val shown = said.take((s.followerAge * 34f).toInt().coerceAtMost(said.length))
            text.textSize = dp(15f)
            val tw = text.measureText(said)
            val bx = o4[0]; val by = o4[1] - dp(26f)
            val pad = dp(10f)
            fill.color = withAlpha(Color.rgb(16, 8, 30), 0.85f * a)
            c.drawRoundRect(bx - tw / 2 - pad, by - dp(22f), bx + tw / 2 + pad, by + dp(8f), dp(10f), dp(10f), fill)
            fill.style = Paint.Style.STROKE; fill.strokeWidth = dp(2f); fill.color = withAlpha(s.followerColor or 0xFF000000.toInt(), a)
            c.drawRoundRect(bx - tw / 2 - pad, by - dp(22f), bx + tw / 2 + pad, by + dp(8f), dp(10f), dp(10f), fill)
            fill.style = Paint.Style.FILL
            text.textAlign = Paint.Align.LEFT; textStroke.textAlign = Paint.Align.LEFT
            outlined(c, shown, bx - tw / 2, by, withAlpha(Color.WHITE, a), dp(3f))
            text.textAlign = Paint.Align.CENTER; textStroke.textAlign = Paint.Align.CENTER
            text.textSize = dp(11f)
            outlined(c, s.followerName.uppercase(), bx, by - dp(28f), withAlpha(s.followerColor or 0xFF000000.toInt(), a), dp(2.5f))
        }

        // The pointer: an arrow at the edge of a ring round the player, toward the target, while it is off to one side.
        if (s.portalTargetOn && s.phase == Phase.PLAYING && project(s.portalPlayerX, 0.6f, s.portalPlayerZ)) {
            val px = o4[0]; val py = o4[1]
            if (project(s.portalTargetX, 0.6f, s.portalTargetZ)) {
                val tx = o4[0]; val ty = o4[1]
                val dx = tx - px; val dy = ty - py
                val d = kotlin.math.hypot(dx, dy)
                if (d > dp(120f)) {
                    val ux = dx / d; val uy = dy / d
                    val r = dp(78f) + dp(6f) * sin(time * 5f)
                    val ax = px + ux * r; val ay = py + uy * r
                    val path = android.graphics.Path().apply {
                        moveTo(ax + ux * dp(14f), ay + uy * dp(14f))
                        lineTo(ax - uy * dp(9f) - ux * dp(6f), ay + ux * dp(9f) - uy * dp(6f))
                        lineTo(ax - ux * dp(1f), ay - uy * dp(1f))
                        lineTo(ax + uy * dp(9f) - ux * dp(6f), ay - ux * dp(9f) - uy * dp(6f))
                        close()
                    }
                    fill.style = Paint.Style.FILL
                    fill.color = if (s.portalOpen) Color.rgb(196, 150, 255) else Color.rgb(150, 236, 255)
                    c.drawPath(path, fill)
                    fill.style = Paint.Style.STROKE; fill.strokeWidth = dp(2.5f); fill.color = INK
                    c.drawPath(path, fill)
                    fill.style = Paint.Style.FILL
                }
            }
        }

        // The Void's taunt: whispered in under the top panel, fading after a few seconds. When it speaks with one of
        // its souls' voices, the name flickers between the Void and that fighter's.
        val line = s.voidLine
        if (line != null && s.voidAge in 0f..5f) {
            val a = (minOf(s.voidAge / 0.4f, 1f, (5f - s.voidAge) / 0.8f)).coerceIn(0f, 1f)
            val shown = line.take((s.voidAge * 38f).toInt().coerceAtMost(line.length))
            text.textSkewX = -0.2f
            textStroke.textSkewX = -0.2f
            val y = h * 0.27f
            fill.color = Color.argb((a * 110).toInt(), 10, 2, 24)
            c.drawRect(0f, y - dp(30f), w, y + dp(14f), fill)
            text.textSize = dp(11f)
            val soul = s.voidSoul
            val asSoul = soul != null && ((time * 2.5f).toInt() % 3 != 0)
            outlined(c, if (asSoul) "THE ENTITY · ${soul!!.uppercase()}" else "THE ENTITY", w / 2, y - dp(14f),
                withAlpha(if (asSoul) s.voidSoulColor or 0xFF000000.toInt() else Color.rgb(185, 140, 255), a), dp(2.5f))
            text.textSize = dp(19f)
            outlined(c, shown, w / 2, y + dp(7f), withAlpha(Color.rgb(232, 220, 255), a), dp(3.5f))
            text.textSkewX = 0f
            textStroke.textSkewX = 0f
        }

        // From the second dimension a faint scanline lies over everything; a glitch moment adds a dark flicker,
        // a few torn bands split into red and cyan, and a few dead blocks. Over in a moment.
        if (depth >= 2) {
            fill.color = Color.argb(10 + minOf(depth, 8), 0, 0, 0)
            var yy = 0f
            while (yy < h) { c.drawRect(0f, yy, w, yy + dp(1f), fill); yy += dp(3f) }
        }
        if (glitch <= 0f) return
        val beat = (time * 14f).toInt()
        fun hash(a: Int, b: Int): Float { var x = a * 374761393 + b * 668265263; x = (x xor (x ushr 13)) * 1274126177; return ((x xor (x ushr 16)) and 0xFFFF) / 65535f }
        fill.color = Color.argb((60 * glitch).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, fill)
        for (i in 0 until 2 + (glitch * 3).toInt()) {
            val by = hash(beat, i * 3 + 1) * h
            val bh = dp(3f) + hash(beat, i * 3 + 2) * dp(14f)
            val off = (hash(beat, i * 3 + 3) - 0.5f) * dp(40f)
            fill.color = Color.argb((80 * glitch).toInt(), 255, 50, 100)
            c.drawRect(off, by, w + off, by + bh * 0.5f, fill)
            fill.color = Color.argb((80 * glitch).toInt(), 50, 220, 255)
            c.drawRect(-off, by + bh * 0.5f, w - off, by + bh, fill)
        }
        for (i in 0 until (glitch * 6).toInt()) {
            val bx = hash(beat, 100 + i) * w; val by = hash(beat, 200 + i) * h
            fill.color = if (i % 2 == 0) Color.argb(140, 8, 0, 18) else Color.argb(90, 220, 200, 255)
            c.drawRect(bx, by, bx + dp(10f) + hash(beat, 300 + i) * dp(50f), by + dp(4f) + hash(beat, 400 + i) * dp(12f), fill)
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
        if (s.duelNotice != 0) {
            // A 1v1 held up, or called off: said plainly, across the middle.
            text.textSize = dp(22f)
            outlined(c, when (s.duelNotice) {
                2 -> "THE DEVICES FELL OUT OF STEP · MATCH CALLED OFF"
                3 -> "CONNECTION LOST · MATCH CALLED OFF"
                4 -> "WAITING FOR YOUR TEAM…"
                else -> "WAITING FOR YOUR OPPONENT…"
            }, cx, h * 0.3f, if (s.duelNotice == 1 || s.duelNotice == 4) Color.WHITE else Color.rgb(255, 92, 92), dp(4f))
        }
        val top = dp(10f)
        val panelW = dp(260f)
        val panelH = dp(58f)
        chamfer(c, cx - panelW / 2, top, cx + panelW / 2, top + panelH, dp(13f), Color.argb(235, 20, 44, 72), INK)
        if (s.practice) {
            // Knockouts so far + running damage total: it is a practice ground, so show the numbers.
            chamfer(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + dp(92f), top + panelH - dp(6f), dp(9f), Color.rgb(28, 110, 200), null)
            text.textSize = dp(30f)
            outlined(c, s.myScore.toString(), cx - panelW / 2 + dp(52f), top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            text.textSize = dp(15f)
            outlined(c, "TRAINING AREA", cx + dp(40f), top + dp(27f), Color.WHITE, dp(3.5f))
            text.textSize = dp(12f)
            outlined(c, "%,d DAMAGE".format(s.damage), cx + dp(40f), top + dp(46f), Color.rgb(255, 214, 64), dp(3f))
        } else if (s.portalRun) {
            // Which dimension (by name), and how many of its echoes are found; when the portal opens, say so.
            chamfer(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + dp(92f), top + panelH - dp(6f), dp(9f), Color.rgb(120, 60, 220), null)
            text.textSize = dp(30f)
            outlined(c, s.portalDimension.toString(), cx - panelW / 2 + dp(52f), top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            text.textSize = dp(15f)
            outlined(c, glitchText(io.github.projectwip.data.Lore.dimensionName(s.portalDimension).uppercase(), s.portalGlitch), cx + dp(40f), top + dp(27f), Color.WHITE, dp(3.5f))
            text.textSize = dp(12f)
            val shards = io.github.projectwip.data.Balance.PORTAL_SHARDS
            outlined(c, when {
                s.portalTravel -> "FALLING…"
                s.portalOpen -> "THE PORTAL IS OPEN"
                else -> "ECHOES " + "◆".repeat(s.portalFound) + "◇".repeat(shards - s.portalFound)
            }, cx + dp(40f), top + dp(46f), Color.rgb(200, 170, 255), dp(3f))
            if (s.phase == Phase.PLAYING && !s.portalTravel) {
                val pulse = 0.75f + 0.25f * sin(time * 4f)
                text.textSize = dp(17f)
                outlined(c, if (s.portalOpen) "THE ENTITY WAITS AT THE PORTAL · STEP INSIDE" else "${s.captiveName.uppercase()} IS CHAINED · FIND THE ECHOES",
                    cx, top + panelH + dp(30f), withAlpha(Color.rgb(205, 180, 255), if (s.portalOpen) pulse else 0.55f), dp(3.5f))
            }
        } else if (s.bossMode) {
            // Lives left, and the boss's health across a wide bar under the panel.
            chamfer(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + dp(92f), top + panelH - dp(6f), dp(9f), Color.rgb(28, 110, 200), null)
            text.textSize = dp(30f)
            outlined(c, s.timesDown.toString(), cx - panelW / 2 + dp(52f), top + panelH / 2 + dp(11f), Color.WHITE, dp(4f))
            text.textSize = dp(17f)
            outlined(c, if (s.timesDown == 1) "TIME DOWN" else "TIMES DOWN", cx + dp(40f), top + dp(27f), Color.WHITE, dp(3.5f))
            text.textSize = dp(12f)
            outlined(c, "BOSS MODE", cx + dp(40f), top + dp(46f), Color.rgb(255, 214, 64), dp(3f))
            val bw = dp(420f)
            val by = top + panelH + dp(26f)
            val fr = (s.bossHp.toFloat() / s.bossMaxHp).coerceIn(0f, 1f)
            fill.color = INK
            c.drawRoundRect(cx - bw / 2 - dp(3f), by - dp(3f), cx + bw / 2 + dp(3f), by + dp(19f), dp(10f), dp(10f), fill)
            fill.color = Color.rgb(22, 40, 62)
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
                val flash = if ((time * 3f).toInt() % 2 == 0) Color.rgb(150, 240, 100) else Color.WHITE
                outlined(c, "THE POISON SMOKE IS CLOSING IN!", cx, top + panelH + dp(32f), flash, dp(4f))
            }
            if (s.playerOutsideStorm) {
                val a = (0.35f + 0.2f * sin(time * 8f))
                fill.shader = android.graphics.RadialGradient(cx, h / 2, maxOf(w, h) * 0.7f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((a * 255).toInt(), 50, 150, 30)), floatArrayOf(0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                text.textSize = dp(19f)
                outlined(c, "YOU'RE IN THE POISON SMOKE — GET BACK INSIDE!", cx, h * 0.24f, Color.rgb(170, 255, 120), dp(4f))
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
            outlined(c, if (s.practice) "TRAINING AREA · PRACTICE, NOTHING AT STAKE" else if (s.portalRun) "DIMENSION · YOU AND THE ENTITY · ONE LIFE" else if (s.bossMode) "BOSS MODE · KNOCK OUT THE BOSS · UNLIMITED LIVES" else if (s.freeForAll) "LAST SPARK · LAST ONE STANDING WINS" else "KNOCKOUT RUSH · FIRST TO ${s.koTarget} KOs", cx, h * 0.45f + dp(48f), Color.rgb(255, 214, 64), dp(4.5f))
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
        -2 -> Color.rgb(150, 235, 100) // the poison smoke
        mine -> ALLY
        else -> ENEMY
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        val INK = Color.rgb(20, 36, 53)
        val PLAYER = Color.rgb(92, 255, 122)
        val ALLY = Color.rgb(63, 182, 255)
        val ENEMY = Color.rgb(255, 77, 94)
    }
}
