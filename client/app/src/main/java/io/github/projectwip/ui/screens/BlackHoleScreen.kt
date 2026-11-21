package io.github.projectwip.ui.screens

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * ARENA's reveal (the user's transition build, 2026-11-21). Until the server says so (`[reveal]` in game.cfg, 8:30 pm
 * that evening) the game is nothing but this: black, a white black hole that turns but never grows, the ARENA logo,
 * and the Entity's cryptic words fading in and out, silent. Then the hole opens, swallows the screen in white, and the
 * game is there. Only the server opens it: the owner opens the game to everyone when the transition is done. Once seen, it never closes again on this device.
 */
object ArenaReveal {
    private const val PREFS = "arena"
    private const val KEY = "revealed"

    fun revealed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markRevealed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private val GATE_WHISPERS = listOf(
    "Shh. Not yet.",
    "Something's changing. You feel it too.",
    "Don't blink. I'm almost through.",
    "You'll know me by a new name.",
    "Stay. It's nearly time.",
    "I can hear you waiting.",
    "Closer. Watch the dark.",
    "When it opens, you're mine again.",
)

/**
 * [askServer] returns seconds until the reveal (0: now), -1 while the owner holds it, or null if the server can't be
 * reached. [previewSeconds] (debug) opens it that long after showing, whatever the server says; 0 never opens.
 */
@Composable
fun BlackHoleScreen(askServer: suspend () -> Double?, previewSeconds: Float? = null, onOpen: () -> Unit, onDone: () -> Unit) {
    val time by rememberAnimTime()
    var openAt by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        val shown = SystemClock.elapsedRealtime()
        var deadline: Long? = null
        while (true) {
            if (previewSeconds != null) {
                if (previewSeconds > 0f) deadline = shown + (previewSeconds * 1000).toLong()
            } else {
                val r = withContext(Dispatchers.IO) { runCatching { askServer() }.getOrNull() }
                // Only the server opens it (the owner's call, build 113): a device that can't reach it stays shut, and
                // one that has heard a time keeps it while the server is out of reach.
                if (r != null) deadline = if (r < 0) null else SystemClock.elapsedRealtime() + (r * 1000).toLong()
            }
            val d = deadline
            if (d != null && SystemClock.elapsedRealtime() >= d) break
            delay(if (d != null && d - SystemClock.elapsedRealtime() < 6000) 250 else 5000)
        }
        openAt = time
        onOpen()
    }
    val since = openAt?.let { time - it }
    LaunchedEffect(since != null && since > OPEN_SECONDS + FADE_SECONDS) { if (since != null && since > OPEN_SECONDS + FADE_SECONDS) onDone() }

    // Opening: the hole grows to swallow the screen, everything goes white, then the white lifts off the game.
    val grow = since?.let { (it / OPEN_SECONDS).coerceIn(0f, 1f) } ?: 0f
    val fade = since?.let { ((it - OPEN_SECONDS) / FADE_SECONDS).coerceIn(0f, 1f) } ?: 0f
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - fade }.background(Color.Black)
            // Nothing under it can be touched.
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        Canvas(Modifier.fillMaxSize()) { drawBlackHole(time, grow) }
        Column(Modifier.align(BiasAlignment(0f, 0.74f)).fillMaxWidth().graphicsLayer { alpha = 1f - grow }, horizontalAlignment = Alignment.CenterHorizontally) {
            // The logo: the game's own title, as on the loading screen (letter by letter, the purple flash, the ink).
            ArenaTitle(Type.Display.copy(fontSize = 76.sp))
            // The Entity, without a sound: one line at a time, fading in and out.
            val cycle = 6.5f
            val n = (time / cycle).toInt()
            val t = time % cycle
            val a = when { t < 1.2f -> t / 1.2f; t > cycle - 1.4f -> (cycle - t) / 1.4f; else -> 1f }.coerceIn(0f, 1f)
            PlainText(GATE_WHISPERS[n % GATE_WHISPERS.size], Type.Label.copy(fontStyle = FontStyle.Italic),
                Modifier.padding(top = 14.dp).graphicsLayer { alpha = a * 0.9f }, color = Palette.TextDim, align = TextAlign.Center, maxLines = 2)
        }
    }
}

/**
 * The white black hole: a black core inside a thin ring of light, a wide glowing disc of matter turning round it
 * (its far half behind the core, bent up over the top and under the bottom by the gravity, its near half crossing in
 * front), brighter on the side turning toward us, and dust spiralling in. [grow] 0..1 is the opening.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlackHole(time: Float, grow: Float) {
    val c = Offset(size.width / 2f, size.height * 0.40f)
    val base = size.minDimension * 0.15f * (1f + 0.02f * sin(time * 1.1f))
    val r = base * (1f + 14f * grow * grow * grow)
    val tilt = -9f
    val ry = 0.2f
    val inner = r * 1.35f
    val outer = r * 3.6f
    val rings = 20

    // Everything is lit by it: a wide white haze.
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f), Color.Transparent), c, outer * 1.25f), outer * 1.25f, c)

    // Dust falling in, on spirals.
    for (i in 0 until 140) {
        val far = ((i * 0.6180339f + time * 0.02f) % 1f)
        val d = outer * (0.4f + 1.4f * far)
        val a = i * 2.39996f + time * 0.25f / (0.3f + far)
        val p = Offset(c.x + cos(a) * d, c.y + sin(a) * d * 0.45f)
        val tw = 0.5f + 0.5f * sin(time * (1.2f + i % 7 * 0.3f) + i)
        drawCircle(Color.White.copy(alpha = (0.12f + 0.45f * tw) * (1f - far * 0.7f)), (0.7f + tw * 1.3f).dp.toPx(), p)
    }

    // One half of the disc: [from] 180 is the far (top) half, 0 the near (bottom) half.
    fun disc(from: Float) = rotate(tilt, c) {
        for (k in 0 until rings) {
            val f = k / (rings - 1f)
            val rr = inner + (outer - inner) * f
            val spin = time * 55f / kotlin.math.sqrt(0.25f + f * 2f)
            val baseA = (1f - f).let { it * it } * 0.75f + 0.05f
            val width = (outer - inner) / rings * 2.2f
            // One smooth sweep of light per ring (no segments, so no seams): brighter on the side turning toward us
            // (left), with streaks of matter turning round it.
            val stops = 36
            val colors = List(stops + 1) { j ->
                val deg = j * 360f / stops
                val rad = Math.toRadians(deg.toDouble())
                val doppler = 0.5f + 0.5f * (-cos(rad)).toFloat()
                val swirl = 0.62f + 0.38f * sin((deg - spin) * 0.0698f + k * 0.9f) * sin((deg - spin) * 0.0349f + k * 0.37f + 1f)
                Color.White.copy(alpha = (baseA * (0.35f + 0.65f * doppler) * swirl).coerceIn(0f, 1f))
            }
            drawArc(Brush.sweepGradient(colors, c), from, 180f, false, Offset(c.x - rr, c.y - rr * ry), Size(rr * 2, rr * 2 * ry), style = Stroke(width))
        }
    }

    disc(180f)
    // The disc's far side, bent up over the top of the hole and under its bottom: one soft band each.
    val bent = Brush.radialGradient(0.62f to Color.Transparent, 0.70f to Color.White.copy(alpha = 0.75f), 0.82f to Color.White.copy(alpha = 0.3f), 1f to Color.Transparent, center = c, radius = r * 1.55f)
    drawArc(bent, 180f, 180f, true, Offset(c.x - r * 1.55f, c.y - r * 1.55f), Size(r * 3.1f, r * 3.1f))
    val under = Brush.radialGradient(0.64f to Color.Transparent, 0.70f to Color.White.copy(alpha = 0.4f), 0.80f to Color.White.copy(alpha = 0.1f), 1f to Color.Transparent, center = c, radius = r * 1.45f)
    drawArc(under, 0f, 180f, true, Offset(c.x - r * 1.45f, c.y - r * 1.45f), Size(r * 2.9f, r * 2.9f))
    // The ring of light at the edge, then the black.
    drawCircle(Brush.radialGradient(0.80f to Color.Transparent, 0.93f to Color.White.copy(alpha = 0.5f), 0.97f to Color.White, 1f to Color.Transparent, center = c, radius = r * 1.07f), r * 1.07f, c)
    drawCircle(Color.Black, r * (1f - 0.9f * grow * grow), c)
    drawCircle(Color.White.copy(alpha = 0.9f), r * 1.005f, c, style = Stroke(1.6f.dp.toPx()))
    // The near half of the disc crosses in front of it.
    disc(0f)
    // Opening: the white takes over.
    if (grow > 0.5f) drawRect(Color.White.copy(alpha = ((grow - 0.5f) / 0.5f).coerceIn(0f, 1f)))
}

private const val OPEN_SECONDS = 2.8f
private const val FADE_SECONDS = 0.9f
