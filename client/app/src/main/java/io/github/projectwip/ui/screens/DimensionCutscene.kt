package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.Lore
import io.github.projectwip.data.VoiceStyle
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.PI
import kotlin.math.sin

/** Seconds of the cut to black, the blinding light and the TV switching off, before the scene. */
private const val TRANSITION = 1.5f

private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/**
 * Entering a dimension (Dimension mode, 1.0, the user's design): the screen cuts to black with a blinding white light in
 * the middle, collapses like an old TV switching off (to a line, then a dot), and then the Void and the fighter talk:
 * the Void a drawn figure on the left that whispers, the fighter on the right. Tap to go on. The match waits behind it.
 */
@Composable
fun DimensionCutscene(entered: Int, fighter: FighterId, skin: Int, sfx: Sfx?, onDone: () -> Unit) {
    val lines = remember(entered, fighter) { Lore.scene(entered, fighter) }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        sfx?.play(Sound.PORTAL_ENTER)
        clock.animateTo(TRANSITION + 0.45f, tween(((TRANSITION + 0.45f) * 1000).toInt(), easing = LinearEasing))
    }
    val t = clock.value
    val sceneIn = ease((t - TRANSITION) / 0.45f)
    var index by remember { mutableIntStateOf(0) }
    var typed by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }
    val line = lines[index]
    LaunchedEffect(index, sceneIn >= 1f) {
        if (sceneIn < 1f) return@LaunchedEffect
        typed = 0
        if (line.speaker == Lore.Speaker.VOID) { sfx?.play(Sound.VOID); sfx?.say(line.text, VoiceStyle.VOID) }
        else if (fighter == FighterId.BUDDY) sfx?.say(line.text, VoiceStyle.MACHINE)
        while (typed < line.text.length) { kotlinx.coroutines.delay(if (line.speaker == Lore.Speaker.VOID) 42L else 26L); typed++ }
    }
    LaunchedEffect(leaving) { if (leaving) { fade.animateTo(0f, tween(450)); onDone() } }
    val time by rememberAnimTime()

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black).pointerInput(Unit) {
            detectTapGestures {
                if (clock.value < TRANSITION + 0.4f || leaving) return@detectTapGestures
                val now = lines[index]
                when {
                    typed < now.text.length -> typed = now.text.length
                    index < lines.lastIndex -> index++
                    else -> leaving = true
                }
            }
        },
    ) {
        // ---- The scene: the dark of the Void, motes drifting up through it.
        if (sceneIn > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = sceneIn }) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(listOf(Color(0xFF26104A), Color(0xFF0B0418), Color.Black), Offset(size.width * 0.35f, size.height * 0.4f), size.maxDimension * 0.7f))
                for (i in 0 until 40) {
                    val sx = ((i * 7919) % 1000) / 1000f
                    val sp = 0.02f + ((i * 104729) % 100) / 2500f
                    val sy = 1f - ((time * sp + i * 0.137f) % 1f)
                    drawCircle(Color(0xFFC9B0FF).copy(alpha = 0.15f + 0.35f * ((i * 31) % 10) / 10f), 1.2.dp.toPx() + (i % 3).dp.toPx() * 0.6f, Offset(sx * size.width, sy * size.height))
                }
            }
            val voidTalking = line.speaker == Lore.Speaker.VOID
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
                PlainText("DIMENSION ${entered + 1}", Type.Eyebrow.copy(letterSpacing = Type.Eyebrow.fontSize * 0.5f, color = Color(0xFFB79CFF)), Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.fillMaxHeight().width(300.dp).graphicsLayer { val k = if (voidTalking) 1f else 0.94f; scaleX = k; scaleY = k; alpha = if (voidTalking) 1f else 0.45f }) {
                        VoidPortrait(time, voidTalking && typed < line.text.length, Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxHeight(0.85f).width(240.dp).graphicsLayer { val k = if (!voidTalking) 1f else 0.94f; scaleX = k; scaleY = k; alpha = if (!voidTalking) 1f else 0.45f }) {
                        FighterView(Balance.fighter(fighter), skin, Modifier.fillMaxSize(), pedestal = true)
                    }
                }
                // The words, typed out; the Void's a pale violet, the fighter's white.
                Panel(Modifier.fillMaxWidth().height(118.dp), color = Color(0xFF1A0E3A), colorBottom = Color(0xFF0D0622), cut = 16.dp) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        GameText(if (voidTalking) "THE VOID" else Balance.fighter(fighter).name.uppercase(), Type.Heading,
                            color = if (voidTalking) Color(0xFFB98CFF) else Palette.Gold, outline = 2.5.dp)
                        Spacer(Modifier.height(4.dp))
                        PlainText(line.text.take(typed), Type.Heading.copy(color = if (voidTalking) Color(0xFFE6DCFF) else Color.White,
                            fontStyle = if (voidTalking) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal), maxLines = 2)
                    }
                    if (typed >= line.text.length) PlainText(if (index == lines.lastIndex) "TAP TO STEP IN" else "TAP ▶", Type.Eyebrow.copy(color = Color.White.copy(alpha = 0.5f + 0.5f * sin(time * 4f))),
                        Modifier.align(Alignment.BottomEnd).padding(14.dp))
                }
            }
        }
        // ---- The way in: black, a blinding light, and the picture switching off like an old TV.
        if (t < TRANSITION + 0.45f) Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - sceneIn }) {
            drawRect(Color.Black)
            val c = Offset(size.width / 2, size.height / 2)
            when {
                t < 0.1f -> Unit
                t < 0.55f -> {
                    val k = ease((t - 0.1f) / 0.45f)
                    val r = 6.dp.toPx() + size.maxDimension * 0.75f * k
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.9f), Color(0x88E6DCFF), Color.Transparent), c, r), r, c)
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent)), Offset(0f, c.y - 4.dp.toPx()), Size(size.width, 8.dp.toPx()), alpha = k)
                }
                t < 1.0f -> {
                    val k = ease((t - 0.55f) / 0.45f)
                    val h = size.height * (1f - k) + 3.dp.toPx() * k
                    drawRect(Color.White, Offset(0f, c.y - h / 2), Size(size.width, h))
                    drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, c.y - h / 2 - 6.dp.toPx()), Size(size.width, h + 12.dp.toPx()))
                }
                t < 1.25f -> {
                    val k = ease((t - 1f) / 0.25f)
                    val w = size.width * (1f - k) + 6.dp.toPx() * k
                    drawRect(Color.White, Offset(c.x - w / 2, c.y - 1.5.dp.toPx()), Size(w, 3.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.4f), 10.dp.toPx(), c)
                }
                else -> {
                    val k = ((t - 1.25f) / 0.25f).coerceIn(0f, 1f)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), c, 14.dp.toPx()), 14.dp.toPx(), c, alpha = 1f - k)
                    drawCircle(Color.White, 3.dp.toPx(), c, alpha = 1f - k)
                }
            }
        }
    }
}

/**
 * The Void, as drawn for its scenes: a tall hooded figure cut out of a starry dark, a hollow where a face should be
 * with two pale eyes in it, a broken halo turning behind its head, and wisps coming off its hem. Its eyes brighten
 * while it speaks, and it blinks now and then.
 */
@Composable
private fun VoidPortrait(time: Float, speaking: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2; val headY = h * 0.3f
        val breathe = 1f + 0.015f * sin(time * 1.3f)
        drawCircle(Brush.radialGradient(listOf(Color(0x667A3CFF), Color.Transparent), Offset(cx, headY), w * 0.55f), w * 0.55f, Offset(cx, headY))
        // The broken halo.
        for (k in 0 until 3) {
            val r = w * (0.27f + k * 0.035f)
            drawArc(Color(0xFFD9C8FF).copy(alpha = 0.55f - k * 0.13f), time * (18f + k * 9f) * (if (k % 2 == 0) 1f else -1f) + k * 120f, 70f + k * 25f, false,
                Offset(cx - r, headY - r), Size(r * 2, r * 2), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        // The robe.
        val robe = Path().apply {
            moveTo(cx, h * 0.08f)
            cubicTo(cx + w * 0.2f, h * 0.08f, cx + w * 0.26f, h * 0.3f, cx + w * 0.3f * breathe, h * 0.46f)
            cubicTo(cx + w * 0.36f, h * 0.65f, cx + w * 0.44f, h * 0.85f, cx + w * 0.46f, h)
            val waves = 6
            for (i in 1..waves) {
                val x = cx + w * 0.46f - w * 0.92f * i / waves
                lineTo(x, h - (if (i % 2 == 0) 0f else h * 0.03f) * (1f + 0.5f * sin(time * 2f + i)))
            }
            cubicTo(cx - w * 0.44f, h * 0.85f, cx - w * 0.36f, h * 0.65f, cx - w * 0.3f * breathe, h * 0.46f)
            cubicTo(cx - w * 0.26f, h * 0.3f, cx - w * 0.2f, h * 0.08f, cx, h * 0.08f)
            close()
        }
        drawPath(robe, Brush.verticalGradient(listOf(Color(0xFF1C0C38), Color(0xFF0A0418), Color(0xFF05020C)), 0f, h))
        clipPath(robe) {
            for (i in 0 until 30) {
                val sx = cx - w * 0.45f + w * 0.9f * (((i * 7907) % 1000) / 1000f)
                val sy = h * 0.15f + h * 0.85f * (((i * 6271) % 1000) / 1000f)
                drawCircle(Color.White.copy(alpha = 0.2f + 0.5f * (0.5f + 0.5f * sin(time * 2f + i))), (0.8f + (i % 3) * 0.5f).dp.toPx(), Offset(sx, sy))
            }
        }
        drawPath(robe, Brush.verticalGradient(listOf(Color(0xCCB98CFF), Color(0x337A3CFF), Color.Transparent), 0f, h), style = Stroke(2.dp.toPx()))
        // The hollow where a face should be.
        val fw = w * 0.3f; val fh = h * 0.17f
        drawOval(Color.Black, Offset(cx - fw / 2, headY - fh / 2), Size(fw, fh))
        drawOval(Brush.radialGradient(listOf(Color.Transparent, Color(0x557A3CFF)), Offset(cx, headY), fw * 0.6f), Offset(cx - fw / 2, headY - fh / 2), Size(fw, fh))
        // Its eyes: brighter as it speaks; a blink every few seconds.
        val blink = if ((time % 4.3f) > 4.15f) 0.15f else 1f
        val glow = if (speaking) 0.75f + 0.25f * sin(time * 9f) else 0.55f
        for (s in floatArrayOf(-1f, 1f)) {
            val ex = cx + s * w * 0.065f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFCFE6FF).copy(alpha = glow), Color.Transparent), Offset(ex, headY), w * 0.07f), w * 0.07f, Offset(ex, headY))
            val ew = w * 0.05f; val eh = h * 0.022f * blink
            drawOval(Color(0xFFF2F6FF), Offset(ex - ew / 2, headY - eh / 2), Size(ew, eh), alpha = 0.6f + 0.4f * glow)
        }
        // Wisps rising off its hem.
        for (i in 0 until 5) {
            val x0 = cx - w * 0.35f + w * 0.7f * i / 4f
            val p = Path().apply {
                moveTo(x0, h * 0.97f)
                cubicTo(x0 + w * 0.06f * sin(time + i), h * 0.88f, x0 - w * 0.05f * sin(time * 1.3f + i), h * 0.8f, x0 + w * 0.03f * sin(time * 0.7f + i), h * 0.72f)
            }
            drawPath(p, Color(0xFF9B7BFF).copy(alpha = 0.18f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        @Suppress("UNUSED_VARIABLE") val unused = PI
    }
}
