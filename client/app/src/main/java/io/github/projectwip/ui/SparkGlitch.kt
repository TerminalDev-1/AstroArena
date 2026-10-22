package io.github.projectwip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Spark's hold on the screen: whenever he is on it, the scene behind the menus takes on his chromatic blue and now and then glitches
 * (part of his lore: he is the Spark Drop that woke up and won't go). It is a hint, not a takeover: it is drawn under
 * the menus, so buttons, text and currencies keep their colours. [strength] eases in and out with him.
 */
class SparkGlitch {
    var strength by mutableFloatStateOf(0f)
        internal set
    var time by mutableFloatStateOf(0f)
        internal set

    /** 0..1: how hard a burst of glitching is hitting at the moment. A short one every few seconds, none in between. */
    val burst: Float get() {
        val beat = floor(time / BURST_EVERY)
        val into = time - beat * BURST_EVERY - hash(beat) * (BURST_EVERY - BURST_LENGTH)
        if (into < 0f || into > BURST_LENGTH) return 0f
        return (1f - into / BURST_LENGTH) * strength
    }

    private companion object {
        const val BURST_EVERY = 3.5f
        const val BURST_LENGTH = 0.22f
    }
}

internal fun hash(x: Float): Float {
    val s = sin(x * 12.9898f + 78.233f) * 43758.547f
    return s - floor(s)
}

/** Keeps a [SparkGlitch] running: it fades in while [active] says Spark is on screen, and out when he isn't. */
@Composable
fun rememberSparkGlitch(active: () -> Boolean): SparkGlitch {
    val g = remember { SparkGlitch() }
    val on by rememberUpdatedState(active)
    LaunchedEffect(g) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                g.time += dt
                val target = if (on()) 1f else 0f
                g.strength += (target - g.strength) * (1f - kotlin.math.exp(-dt * 5f))
                if (g.strength < 0.003f && target == 0f) g.strength = 0f
            }
        }
    }
    return g
}

/** Draws [g] over the scene under it: a faint blue light, deeper blue at the edges, and a few torn bars while a burst is on. */
@Composable
fun SparkGlitchOverlay(g: SparkGlitch, modifier: Modifier = Modifier) {
    if (g.strength <= 0f) return
    Canvas(modifier.fillMaxSize()) {
        val s = g.strength
        val t = g.time
        val b = g.burst
        // A blue light over the scene, a little stronger at the edges, breathing slowly.
        drawRect(Color(0xFF2E8BFF), alpha = (0.09f + 0.025f * sin(t * 2f) + 0.06f * b) * s)
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                0.5f to Color.Transparent, 1f to Color(0xFF1838C0),
                center = center, radius = size.maxDimension * 0.62f,
            ),
            alpha = 0.45f * s,
        )
        // Faint scanlines, rolling slowly down.
        val gap = 5.dp.toPx()
        var y = (t * 14f) % gap
        while (y < size.height) {
            drawRect(Color.Black, Offset(0f, y), Size(size.width, 1.dp.toPx()), alpha = 0.06f * s)
            y += gap
        }
        // A burst: a few thin bars torn sideways, cyan one way and violet the other.
        if (b > 0f) {
            val seed = floor(t * 24f)
            val bars = 2 + (hash(seed) * 3f).toInt()
            for (i in 0 until bars) {
                val by = hash(seed + i * 3.1f) * size.height
                val bh = (3f + hash(seed + i * 5.7f) * 14f).dp.toPx()
                val dx = (hash(seed + i * 9.3f) - 0.5f) * 60.dp.toPx() * b
                drawRect(Color(0xFF30E8FF), Offset(dx, by), Size(size.width, bh), alpha = 0.22f * b)
                drawRect(Color(0xFFB070FF), Offset(-dx * 0.6f, by + bh * 0.35f), Size(size.width, bh * 0.3f), alpha = 0.14f * b)
            }
        }
    }
}
