package io.github.projectwip.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.projectwip.ui.Palette
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.projectwip.BuildConfig
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.sin

/**
 * Shown while the game gets ready: it connects to the game server, checks GitHub for a newer release and builds
 * its sounds and music. [progress] is real (0..1 of that work), and [status] says which part is happening.
 *
 * Plain, by the user's decision (build 111): black, and the white ASTROARENA title from the end of the opening,
 * fading and easing in the same way, then breathing gently. The loading is a thin dim line and a few small words at
 * the bottom. [onSkip] (dev builds) stops waiting for the server; [onIntro] plays the opening.
 */
@Composable
fun LoadingScreen(progress: Float, status: String, onSkip: (() -> Unit)? = null, onIntro: (() -> Unit)? = null) {
    val roomy = LocalUi.current.roomy
    Box(
        // Swallows touches so nothing underneath can be pressed while loading.
        Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) { },
    ) {
        // The title, one letter at a time (the user's ask): each fades up and drops into place from a little larger,
        // then the whole word breathes. (No `continue` in this loop: Compose can't skip a group mid-loop.)
        Title(Type.Display.copy(fontSize = if (roomy) 76.sp else 54.sp), Modifier.align(Alignment.Center))

        // The loading: a thin dim line, and what is happening, small, under it.
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (roomy) 38.dp else 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.width(if (roomy) 360.dp else 260.dp).height(3.dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.6f), size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = r)
            }
            Spacer(Modifier.height(8.dp))
            StatusLine(status)
        }

        if (onSkip != null) GhostButton("PLAY OFFLINE (DEV)", onSkip, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 18.dp))
        // The opening, any time (the user's ask): in every build, release too.
        if (onIntro != null) GhostButton("PLAY INTRO", onIntro, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 18.dp))
        PlainText(BuildConfig.VERSION_NAME + " · build " + BuildConfig.BUILD, Type.Small, Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Color.White.copy(alpha = 0.3f))
    }
}

private const val LETTERS = "ASTROARENA"
private const val LETTER_GAP = 0.2f
// A breath of black before the first letter (the clock itself waits for the app to settle).
private const val TITLE_DELAY = 0.2f
private const val LETTER_IN = 0.5f
private const val FLASH = 0.6f
private val NEON = Color(0xFFC57BFF)

private fun flash(t: Float) = if (t <= 0f) 0f else (1f - (t - 0.3f) / FLASH).coerceIn(0f, 1f)

/**
 * Seconds of smooth animation, counted frame by frame. It only starts once the app has settled (the first second
 * or two after launch stall while it starts up, and the title stuttered through them): after [SETTLE_SECONDS]
 * without a slow frame, or [SETTLE_LIMIT] seconds at the most. Each step is capped, so a frame that is still held
 * up slows the title for a moment instead of making it jump ahead.
 */
@Composable
private fun rememberSteadyTime(): State<Float> = produceState(0f) {
    val first = withFrameNanos { it }
    var last = first
    var smoothSince = first
    while ((last - smoothSince) / 1e9f < SETTLE_SECONDS && (last - first) / 1e9f < SETTLE_LIMIT) withFrameNanos { now ->
        if (now - last >= 25_000_000L) smoothSince = now
        last = now
    }
    while (true) withFrameNanos { now ->
        value += ((now - last) / 1e9f).coerceAtMost(1f / 40f)
        last = now
    }
}

private const val SETTLE_SECONDS = 0.5f
private const val SETTLE_LIMIT = 2.8f

/** What is loading, with dots that count; its own scope, so the dots recompose only this line. */
@Composable
private fun StatusLine(status: String) {
    val time by rememberAnimTime()
    PlainText(status.trimEnd('…', '.', '!').uppercase() + if (status == "Ready!") "" else ".".repeat(1 + (time * 2.5f).toInt() % 3),
        Type.Small.copy(letterSpacing = Type.Small.fontSize * 0.25f), color = Color.White.copy(alpha = 0.45f), maxLines = 1)
}

/** A slim see-through button for the black loading screen: a thin light outline and small spaced letters. */
@Composable
private fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.3f), shape)
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)) {
        PlainText(label, Type.Small.copy(letterSpacing = Type.Small.fontSize * 0.2f), color = Color.White.copy(alpha = 0.8f), maxLines = 1)
    }
}

/**
 * The title, one letter at a time, drawn on one canvas: each letter is laid out once, and every frame only draws
 * them (as layers and text nodes, 60 of them, the frames came out uneven). The clock is read only while drawing,
 * so nothing recomposes while it animates, and the loading line's updates skip this composable.
 * Each letter goes solid fast (so the purple is at full strength), drops into place from a little larger, holds
 * neon purple with a glow round it a moment, and cools to white; then the word breathes.
 */
@Composable
private fun Title(style: TextStyle, modifier: Modifier) {
    val clock = rememberSteadyTime()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val outline = with(density) { 3.dp.toPx() }
    // Laid out once: each letter's fill, and its ink outline (a stroke with a hard drop under it, as GameText).
    val letters = remember(style, density) {
        LETTERS.map { c ->
            measurer.measure(c.toString(), style) to measurer.measure(c.toString(), style.copy(
                color = Palette.Ink, drawStyle = Stroke(width = outline * 2, join = StrokeJoin.Round),
                shadow = Shadow(Palette.Ink, Offset(0f, outline * 1.1f), 0f)))
        }
    }
    val word = remember(letters) { measurer.measure(LETTERS, style) }
    // (Each draw names its style: the text paint keeps the last one, and the fills came out as outlines.)
    val inkStroke = remember(outline) { Stroke(width = outline * 2, join = StrokeJoin.Round) }
    val glow = remember(style, density) { LETTERS.map { measurer.measure(it.toString(), style.copy(color = NEON)) } }
    Canvas(modifier.size(with(density) { word.size.width.toDp() + 8.dp }, with(density) { word.size.height.toDp() + 8.dp })) {
        val time = clock.value
        val all = ((time - TITLE_DELAY - LETTERS.length * LETTER_GAP) / 0.5f).coerceIn(0f, 1f)
        val breathe = 1f + 0.012f * sin(time * 1.5f) * all
        val total = letters.sumOf { it.first.size.width }.toFloat()
        scale(breathe) {
            var x = (size.width - total) / 2f
            val y = (size.height - word.size.height) / 2f
            for ((i, pair) in letters.withIndex()) {
                val (fill, ink) = pair
                val w = fill.size.width.toFloat()
                val t = time - TITLE_DELAY - i * LETTER_GAP
                val shown = (t / 0.18f).coerceIn(0f, 1f)
                if (shown > 0f) {
                    val k = (t / LETTER_IN).coerceIn(0f, 1f)
                    val eased = 1f - (1f - k) * (1f - k)
                    val f = flash(t)
                    val pivot = Offset(x + w / 2f, y + fill.size.height / 2f)
                    translate(0f, -18f * (1f - eased)) {
                        scale(1.35f - 0.35f * eased, pivot) {
                            if (f > 0f) scale(1f + 0.35f * (1f - f), pivot) {
                                drawText(glow[i], topLeft = Offset(x, y), alpha = f * 0.7f * shown, drawStyle = Fill)
                            }
                            drawText(ink, topLeft = Offset(x, y), alpha = shown * shown * shown, drawStyle = inkStroke)
                            drawText(fill, color = lerp(Color.White, NEON, f), topLeft = Offset(x, y), alpha = shown, drawStyle = Fill)
                        }
                    }
                }
                x += w
            }
        }
    }
}
