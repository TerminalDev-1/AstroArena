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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
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
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Seconds of the cut to black, the blinding light and the TV switching off, before the scene. */
private const val TRANSITION = 1.5f

private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

private fun hash(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

/** The colours of one dimension's scene: its sky, its glow, and the light of the Void's eyes. */
private class Mood(val top: Color, val mid: Color, val glow: Color, val accent: Color)

private val MOODS = listOf(
    Mood(Color(0xFF1B0B3A), Color(0xFF07020F), Color(0xFF7A3CFF), Color(0xFFCFE6FF)),
    Mood(Color(0xFF2A0A44), Color(0xFF0A0316), Color(0xFFB24CFF), Color(0xFFFFE6FF)),
    Mood(Color(0xFF032234), Color(0xFF01070D), Color(0xFF2CB8FF), Color(0xFFC9FBFF)),
    Mood(Color(0xFF331606), Color(0xFF0C0402), Color(0xFFFF7A2C), Color(0xFFFFE2B8)),
    Mood(Color(0xFF34302A), Color(0xFF0D0B09), Color(0xFFE8DCC4), Color(0xFFFFFFFF)),
    Mood(Color(0xFF06260E), Color(0xFF010803), Color(0xFF4CFF7A), Color(0xFFD9FFE2)),
    Mood(Color(0xFF3A0620), Color(0xFF0E0108), Color(0xFFFF3C8E), Color(0xFFFFD6E8)),
    Mood(Color(0xFF040408), Color(0xFF000000), Color(0xFF5A4C8C), Color(0xFFFFFFFF)),
)

/**
 * Entering a dimension (Dimension mode, the user's design): the screen cuts to black with a blinding white light in
 * the middle, collapses like an old TV switching off (to a line, then a dot), and then the Void and the fighter talk.
 * Since build 104 the scene is a staged one: letterbox bars, a title card naming the dimension, a sky of its own
 * colours with drifting nebulae, light shafts, a vortex turning behind the Void, debris hanging at different depths
 * and a floor running off to a glowing horizon; the Void with tendrils, orbiting shards and pulses as it speaks; the
 * fighter backlit; a lit glass dialogue panel; and, deeper down, the picture glitching. Tap to go on.
 */
@Composable
fun DimensionCutscene(entered: Int, fighter: FighterId, skin: Int, sfx: Sfx?, onDone: () -> Unit) {
    val lines = remember(entered, fighter) { Lore.scene(entered, fighter) }
    val mood = MOODS[if (entered < MOODS.size) entered else 1 + (entered - 1) % (MOODS.size - 1)]
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
    val voidTalking = line.speaker == Lore.Speaker.VOID
    // Past the first dimension the picture glitches now and then, for a moment: creepy, not constant.
    val glitch = if (entered >= 1 && ((time + entered * 1.7f) % 4f) < 0.32f) 0.6f else 0f

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
        if (sceneIn > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = sceneIn }) {
            // ---- The stage behind everything, pushed in slowly like a camera move.
            Canvas(Modifier.fillMaxSize().graphicsLayer { val k = 1.03f + 0.015f * sin(time * 0.2f); scaleX = k; scaleY = k }) {
                drawBackdrop(time, mood, entered)
            }
            // ---- The two of them, on the stage.
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(top = 84.dp, bottom = 30.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.fillMaxHeight().width(320.dp).graphicsLayer {
                        val k = if (voidTalking) 1.04f else 0.95f; scaleX = k; scaleY = k; alpha = if (voidTalking) 1f else 0.55f
                        translationY = 6.dp.toPx() * sin(time * 0.9f)
                    }) {
                        VoidStage(time, mood, if (voidTalking) line.soul else null, Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxHeight(0.86f).width(250.dp).graphicsLayer {
                        val k = if (!voidTalking) 1.04f else 0.95f; scaleX = k; scaleY = k; alpha = if (!voidTalking) 1f else 0.55f
                    }) {
                        // A backlight behind the fighter, and their shadow on the floor.
                        Canvas(Modifier.fillMaxSize()) {
                            val c = Offset(size.width / 2, size.height * 0.45f)
                            drawCircle(Brush.radialGradient(listOf(mood.glow.copy(alpha = 0.45f), mood.glow.copy(alpha = 0.12f), Color.Transparent), c, size.width * 0.62f), size.width * 0.62f, c)
                            drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), Offset(size.width / 2, size.height * 0.95f), size.width * 0.42f),
                                Offset(size.width * 0.08f, size.height * 0.9f), Size(size.width * 0.84f, size.height * 0.1f))
                        }
                        FighterView(Balance.fighter(fighter), skin, Modifier.fillMaxSize(), pedestal = true)
                    }
                }
                val (name, who) = speakerPlate(line, Balance.fighter(fighter).name, time)
                DialoguePanel(line, typed, index, lines.size, name, who, voidTalking, mood, time, "TAP TO STEP IN")
            }
            // ---- The title card: which dimension this is, fading in and then settling small at the top.
            val card = sceneIn
            Column(Modifier.align(Alignment.TopCenter).padding(top = 30.dp).graphicsLayer { alpha = card }, horizontalAlignment = Alignment.CenterHorizontally) {
                PlainText("DIMENSION ${entered + 1}", Type.Eyebrow.copy(letterSpacing = Type.Eyebrow.fontSize * 0.6f, color = mood.accent.copy(alpha = 0.8f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(46.dp).height(1.5.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, mood.glow))))
                    Spacer(Modifier.width(10.dp))
                    GameText(Lore.dimensionName(entered + 1).uppercase(), Type.Heading, color = Color.White, outline = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(46.dp).height(1.5.dp).background(Brush.horizontalGradient(listOf(mood.glow, Color.Transparent))))
                }
            }
            // ---- Letterbox bars, and the picture glitching the deeper it is.
            Canvas(Modifier.fillMaxSize()) {
                val bar = 26.dp.toPx() * sceneIn
                drawRect(Color.Black, Offset.Zero, Size(size.width, bar))
                drawRect(Color.Black, Offset(0f, size.height - bar), Size(size.width, bar))
                if (entered >= 1) { var y = 0f; while (y < size.height) { drawRect(Color.Black.copy(alpha = 0.06f), Offset(0f, y), Size(size.width, 1.dp.toPx())); y += 3.dp.toPx() } }
                drawGlitch(time, glitch)
                // A vignette pulling the eye to the middle.
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.6f)), Offset(size.width / 2, size.height / 2), size.maxDimension * 0.7f))
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

/** The words: a lit glass panel with a nameplate for whoever speaks, the line typed out, and how far the scene has got. */
@Composable
private fun DialoguePanel(line: Lore.Line, typed: Int, index: Int, count: Int, name: String, who: Color, left: Boolean, mood: Mood, time: Float, lastPrompt: String) {
    val voidTalking = left
    // A chained fighter's scream shakes.
    val scream = line.speaker == Lore.Speaker.CAPTIVE && line.text.contains("HELP")
    Box(Modifier.fillMaxWidth().height(124.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cut = 16.dp.toPx()
            val top = 14.dp.toPx()
            val shape = Path().apply {
                moveTo(cut, top); lineTo(size.width - cut, top); lineTo(size.width, top + cut)
                lineTo(size.width, size.height - cut); lineTo(size.width - cut, size.height); lineTo(cut, size.height)
                lineTo(0f, size.height - cut); lineTo(0f, top + cut); close()
            }
            drawPath(shape, Brush.verticalGradient(listOf(Color(0xE6170C33), Color(0xF00A0519)), top, size.height))
            // A slow sheen sliding across the glass.
            val sx = ((time * 0.15f) % 1.6f - 0.3f) * size.width
            clipPath(shape) {
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent), Offset(sx - 80.dp.toPx(), 0f), Offset(sx + 80.dp.toPx(), size.height)))
                drawRect(Brush.verticalGradient(listOf(mood.glow.copy(alpha = 0.22f), Color.Transparent), top, top + 40.dp.toPx()))
            }
            drawPath(shape, Brush.horizontalGradient(listOf(who.copy(alpha = 0.9f), mood.glow.copy(alpha = 0.6f), Color.White.copy(alpha = 0.15f))), style = Stroke(2.dp.toPx()))
            // The nameplate tab.
            val tabW = 150.dp.toPx(); val tabH = 26.dp.toPx(); val tx = if (voidTalking) 22.dp.toPx() else size.width - 22.dp.toPx() - tabW
            val tab = Path().apply {
                moveTo(tx + 8.dp.toPx(), 0f); lineTo(tx + tabW, 0f); lineTo(tx + tabW - 8.dp.toPx(), tabH); lineTo(tx, tabH); close()
            }
            drawPath(tab, Brush.horizontalGradient(listOf(who.copy(alpha = 0.85f), who.copy(alpha = 0.45f)), tx, tx + tabW))
            drawPath(tab, Color.Black.copy(alpha = 0.8f), style = Stroke(2.dp.toPx()))
            // Which line of the scene this is.
            for (i in 0 until count) {
                val r = if (i == index) 4.dp.toPx() else 2.5.dp.toPx()
                drawCircle(if (i <= index) who else Color.White.copy(alpha = 0.25f), r, Offset(size.width / 2 + (i - (count - 1) / 2f) * 14.dp.toPx(), size.height - 10.dp.toPx()))
            }
        }
        PlainText(name, Type.Eyebrow.copy(color = Color.White, letterSpacing = Type.Eyebrow.fontSize * 0.3f),
            Modifier.align(if (voidTalking) Alignment.TopStart else Alignment.TopEnd).padding(horizontal = 42.dp, vertical = 5.dp))
        val caret = if (typed < line.text.length || (time * 2f).toInt() % 2 == 0) "▌" else " "
        PlainText(line.text.take(typed) + caret, Type.Heading.copy(color = if (line.speaker == Lore.Speaker.VOID) Color(0xFFE6DCFF) else Color.White,
            fontStyle = if (line.speaker == Lore.Speaker.VOID) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal), maxLines = 2,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 38.dp).graphicsLayer {
                if (scream) { translationX = 3.dp.toPx() * sin(time * 53f); translationY = 2.dp.toPx() * sin(time * 41f) }
            })
        if (typed >= line.text.length) PlainText(if (index == count - 1) lastPrompt else "TAP ▶",
            Type.Eyebrow.copy(color = Color.White.copy(alpha = 0.5f + 0.5f * sin(time * 4f))), Modifier.align(Alignment.BottomEnd).padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

/** The stage: sky, nebulae, light shafts, the vortex behind the Void, debris at three depths, the floor and the motes. */
private fun DrawScope.drawBackdrop(time: Float, mood: Mood, entered: Int) {
    val w = size.width; val h = size.height
    val horizon = h * 0.66f
    drawRect(Brush.verticalGradient(listOf(mood.top, mood.mid, Color.Black), 0f, h))
    // Nebulae: great soft clouds of colour drifting slowly.
    for (i in 0 until 5) {
        val cx = w * (0.1f + 0.2f * i) + w * 0.05f * sin(time * 0.05f + i * 1.7f)
        val cy = h * (0.18f + 0.22f * hash(i, entered)) + h * 0.03f * cos(time * 0.07f + i)
        val r = w * (0.22f + 0.12f * hash(i, entered + 9))
        val col = if (i % 2 == 0) mood.glow else mood.accent
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.16f), col.copy(alpha = 0.05f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
    // Far stars, twinkling.
    for (i in 0 until 70) {
        val sx = hash(i, 1) * w; val sy = hash(i, 2) * horizon
        val a = 0.15f + 0.45f * (0.5f + 0.5f * sin(time * (0.8f + hash(i, 3) * 2f) + i))
        drawCircle(Color.White.copy(alpha = a), (0.6f + hash(i, 4) * 1.2f).dp.toPx(), Offset(sx, sy))
    }
    // The vortex turning slowly behind where the Void stands.
    val vc = Offset(w * 0.24f, h * 0.36f)
    rotate(time * 8f, vc) {
        for (arm in 0 until 5) {
            val p = Path()
            for (s in 0..40) {
                val u = s / 40f
                val ang = arm * (2 * PI / 5).toFloat() + u * 4.2f
                val rad = w * 0.04f + u * w * 0.36f
                val x = vc.x + cos(ang) * rad; val y = vc.y + sin(ang) * rad * 0.62f
                if (s == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, mood.glow.copy(alpha = 0.16f), style = Stroke((10 - arm).dp.toPx(), cap = StrokeCap.Round))
            drawPath(p, mood.accent.copy(alpha = 0.12f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color.Black, Color.Black.copy(alpha = 0.6f), Color.Transparent), vc, w * 0.09f), w * 0.09f, vc)
    // Light shafts falling from above, swaying.
    for (i in 0 until 4) {
        val x0 = w * (0.3f + 0.16f * i) + w * 0.03f * sin(time * 0.3f + i * 2f)
        val spread = w * (0.07f + 0.03f * i)
        val p = Path().apply { moveTo(x0 - w * 0.015f, 0f); lineTo(x0 + w * 0.015f, 0f); lineTo(x0 + spread, horizon); lineTo(x0 - spread * 0.6f, horizon); close() }
        val a = 0.05f + 0.03f * sin(time * 0.7f + i)
        drawPath(p, Brush.verticalGradient(listOf(mood.accent.copy(alpha = a * 1.6f), mood.glow.copy(alpha = a), Color.Transparent), 0f, horizon), blendMode = BlendMode.Plus)
    }
    // Debris hanging at three depths: far ones small and pale, near ones large and dark, all drifting and turning.
    for (layer in 0 until 3) {
        val n = 6 - layer
        for (i in 0 until n) {
            val seed = layer * 31 + i
            val speed = 6f + layer * 10f
            val x = ((hash(seed, 7) * w + time * speed) % (w * 1.2f)) - w * 0.1f
            val y = h * (0.12f + 0.45f * hash(seed, 8)) + 8.dp.toPx() * sin(time * 0.6f + seed)
            val s = (8f + layer * 12f + hash(seed, 9) * 10f).dp.toPx()
            val col = if (layer == 2) Color.Black.copy(alpha = 0.85f) else mood.glow.copy(alpha = 0.18f + 0.1f * layer)
            rotate(time * (10f + 20f * hash(seed, 10)) * (if (seed % 2 == 0) 1f else -1f), Offset(x, y)) {
                val sides = 3 + (seed % 3)
                val p = Path()
                for (k in 0 until sides) {
                    val a = k * (2 * PI / sides).toFloat()
                    val r = s * (0.7f + 0.3f * hash(seed, 11 + k))
                    if (k == 0) p.moveTo(x + cos(a) * r, y + sin(a) * r) else p.lineTo(x + cos(a) * r, y + sin(a) * r)
                }
                p.close()
                drawPath(p, col)
                drawPath(p, mood.accent.copy(alpha = 0.1f + 0.1f * layer), style = Stroke(1.dp.toPx()))
            }
        }
    }
    // The floor: a dark plane running off to a glowing horizon, its grid lines rushing slowly toward us.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Black), horizon, h), Offset(0f, horizon), Size(w, h - horizon))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, mood.glow.copy(alpha = 0.55f), Color.Transparent), horizon - 6.dp.toPx(), horizon + 6.dp.toPx()), Offset(0f, horizon - 6.dp.toPx()), Size(w, 12.dp.toPx()))
    val vp = Offset(w / 2, horizon)
    for (k in -12..12) {
        val xb = w / 2 + k * w * 0.12f
        drawLine(mood.glow.copy(alpha = 0.18f), vp, Offset(xb, h), 1.dp.toPx())
    }
    for (k in 0 until 9) {
        val u = ((k + (time * 0.25f) % 1f) / 9f)
        val y = horizon + (h - horizon) * u * u
        drawLine(mood.glow.copy(alpha = 0.06f + 0.22f * u), Offset(0f, y), Offset(w, y), 1.dp.toPx())
    }
    // A pool of the Void's light on the floor under it.
    drawOval(Brush.radialGradient(listOf(mood.glow.copy(alpha = 0.35f), Color.Transparent), Offset(w * 0.2f, h * 0.86f), w * 0.2f), Offset(w * 0.02f, h * 0.8f), Size(w * 0.36f, h * 0.12f))
    // Motes rising through all of it.
    for (i in 0 until 50) {
        val sx = hash(i, 21) * w
        val sp = 0.02f + hash(i, 22) * 0.05f
        val sy = (1f - ((time * sp + hash(i, 23)) % 1f)) * h
        drawCircle(mood.accent.copy(alpha = 0.15f + 0.4f * hash(i, 24)), (1f + 1.6f * hash(i, 25)).dp.toPx(), Offset(sx + 6.dp.toPx() * sin(time + i), sy))
    }
}

/** The picture breaking up, more the deeper the dimension: scanlines, torn colour bands, blocks of dead pixels. */
private fun DrawScope.drawGlitch(time: Float, k: Float) {
    if (k <= 0f) return
    val w = size.width; val h = size.height
    var y = 0f
    while (y < h) { drawRect(Color.Black.copy(alpha = 0.05f + 0.12f * k), Offset(0f, y), Size(w, 1.dp.toPx())); y += 3.dp.toPx() }
    val beat = (time * 10f).toInt()
    if (hash(beat / 2, 77) > 0.1f + 0.35f * k) return
    for (i in 0 until 1 + (k * 5).toInt()) {
        val by = hash(beat, i * 3) * h
        val bh = (2f + hash(beat, i * 3 + 1) * 18f).dp.toPx()
        val off = (hash(beat, i * 3 + 2) - 0.5f) * 50.dp.toPx() * k
        drawRect(Color(0xFFFF2A6A).copy(alpha = 0.25f + 0.25f * k), Offset(off, by), Size(w, bh / 2), blendMode = BlendMode.Plus)
        drawRect(Color(0xFF2AE6FF).copy(alpha = 0.25f + 0.25f * k), Offset(-off, by + bh / 2), Size(w, bh / 2), blendMode = BlendMode.Plus)
    }
    for (i in 0 until (k * 10).toInt()) {
        val bx = hash(beat, 100 + i) * w; val by = hash(beat, 200 + i) * h
        drawRect(if (i % 2 == 0) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.35f), Offset(bx, by), Size((6f + hash(beat, 300 + i) * 50f).dp.toPx(), (3f + hash(beat, 400 + i) * 12f).dp.toPx()))
    }
}

/**
 * The Void, in 3D (the user asked for a model): its robe, hood, hollow face and lit eyes on a live stage, glitching
 * now and then into one of the forgotten fighters whose souls it carries ([SoulView]); while it speaks with one's
 * voice, that one shows through. Behind it, its glow and a broken halo turning.
 */
@Composable
private fun VoidStage(time: Float, mood: Mood, soul: Lore.Soul?, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val c = Offset(w / 2, h * 0.42f)
            drawCircle(Brush.radialGradient(listOf(mood.glow.copy(alpha = 0.5f), mood.glow.copy(alpha = 0.12f), Color.Transparent), c, w * 0.62f), w * 0.62f, c)
            for (k in 0 until 3) {
                val r = w * (0.3f + k * 0.04f)
                drawArc(mood.accent.copy(alpha = 0.5f - k * 0.12f), time * (18f + k * 9f) * (if (k % 2 == 0) 1f else -1f) + k * 120f, 70f + k * 25f, false,
                    Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), Offset(w / 2, h * 0.95f), w * 0.4f),
                Offset(w * 0.1f, h * 0.9f), Size(w * 0.8f, h * 0.1f))
        }
        io.github.projectwip.ui.SoulView(Modifier.fillMaxSize(), voidCycle = true, voidSoul = soul)
    }
}

private inline fun DrawScope.translateBy(x: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(x, 0f)
    block()
    drawContext.transform.translate(-x, 0f)
}

/** The nameplate for a line: the Void (flickering to the soul whose voice it is using), or the fighter. */
private fun speakerPlate(line: Lore.Line, fighterName: String, time: Float): Pair<String, Color> = when (line.speaker) {
    Lore.Speaker.VOID -> {
        val soul = line.soul
        if (soul != null && (time * 2.5f).toInt() % 3 != 0) "THE VOID · ${soul.title.uppercase()}" to Color(soul.color)
        else "THE VOID" to Color(0xFFB98CFF)
    }
    Lore.Speaker.CAPTIVE -> fighterName.uppercase() to (line.soul?.let { Color(it.color) } ?: Color(0xFFFF8A8A))
    Lore.Speaker.FIGHTER -> fighterName.uppercase() to Palette.Gold
}

/**
 * Talking to Dimension's chained fighter (the user's idea): when the player walks up to them the match pauses, the
 * world dims, and the player asks what needs to be done; the captive, in chains, screams for help and then says
 * (the echoes break the chains). The player stands on the left, the captive on the right, rattling their chains.
 */
@Composable
fun CaptiveTalk(dimension: Int, player: FighterId, playerSkin: Int, sfx: Sfx?, onDone: () -> Unit) {
    val captive = remember(dimension) { Lore.captive(dimension) }
    val lines = remember(captive, player) { Lore.captiveTalk(captive, player) }
    val mood = MOODS[if (dimension - 1 < MOODS.size) dimension - 1 else 1 + (dimension - 2) % (MOODS.size - 1)]
    var index by remember { mutableIntStateOf(0) }
    var typed by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    val fade = remember { Animatable(0f) }
    val time by rememberAnimTime()
    val line = lines[index]
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(300)) }
    LaunchedEffect(index) {
        typed = 0
        if (line.speaker == Lore.Speaker.CAPTIVE && line.text.contains("HELP")) sfx?.play(Sound.RIFT, 0.5f, 0.7f)
        if (line.speaker == Lore.Speaker.FIGHTER && player == FighterId.BUDDY) sfx?.say(line.text, VoiceStyle.MACHINE)
        while (typed < line.text.length) { kotlinx.coroutines.delay(if (line.speaker == Lore.Speaker.CAPTIVE) 22L else 28L); typed++ }
    }
    LaunchedEffect(leaving) { if (leaving) { fade.animateTo(0f, tween(300)); onDone() } }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black.copy(alpha = 0.72f)).pointerInput(Unit) {
            detectTapGestures {
                if (leaving) return@detectTapGestures
                val now = lines[index]
                when {
                    typed < now.text.length -> typed = now.text.length
                    index < lines.lastIndex -> index++
                    else -> leaving = true
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(top = 30.dp, bottom = 30.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                val asking = line.speaker == Lore.Speaker.FIGHTER
                Box(Modifier.fillMaxHeight(0.86f).width(250.dp).graphicsLayer { alpha = if (asking) 1f else 0.55f }) {
                    FighterView(Balance.fighter(player), playerSkin, Modifier.fillMaxSize(), pedestal = true)
                }
                Box(Modifier.fillMaxHeight(0.92f).width(280.dp).graphicsLayer {
                    alpha = if (!asking) 1f else 0.6f
                    // Struggling against the chains.
                    translationX = 3.dp.toPx() * sin(time * 23f) * (if (!asking) 1f else 0.3f)
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2, size.height * 0.45f)
                        drawCircle(Brush.radialGradient(listOf(Color(0x66FF4C6A), Color.Transparent), c, size.width * 0.6f), size.width * 0.6f, c)
                        // The pillar behind them.
                        drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1F3A), Color(0xFF120C1C)), 0f, size.height), Offset(size.width * 0.42f, 0f), Size(size.width * 0.16f, size.height))
                    }
                    io.github.projectwip.ui.SoulView(Modifier.fillMaxSize(), soul = captive)
                    // The chains, over them: from the pillar out to their wrists and waist, rattling.
                    Canvas(Modifier.fillMaxSize()) {
                        for (k in 0 until 3) {
                            val y = size.height * (0.4f + 0.12f * k)
                            val sag = 10.dp.toPx() + 4.dp.toPx() * sin(time * 9f + k)
                            for (side in floatArrayOf(-1f, 1f)) {
                                val x0 = size.width / 2; val x1 = size.width / 2 + side * size.width * (0.18f + 0.03f * k)
                                for (l in 0..7) {
                                    val u = l / 7f
                                    val x = x0 + (x1 - x0) * u
                                    val yy = y + sag * sin(u * 3.14f) + 1.5.dp.toPx() * sin(time * 30f + l)
                                    drawOval(Color(0xFF9A93A8), Offset(x - 4.dp.toPx(), yy - 3.dp.toPx()), Size(8.dp.toPx(), 6.dp.toPx()), style = Stroke(2.dp.toPx()))
                                }
                            }
                        }
                    }
                }
            }
            val (name, who) = speakerPlate(line, if (line.speaker == Lore.Speaker.CAPTIVE) captive.title else Balance.fighter(player).name, time)
            DialoguePanel(line, typed, index, lines.size, name, who, line.speaker != Lore.Speaker.CAPTIVE, mood, time, "TAP TO GO")
        }
    }
}
