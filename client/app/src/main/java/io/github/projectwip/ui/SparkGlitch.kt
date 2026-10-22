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
 * Spark's hold on the screen: whenever he is on it, the whole screen goes red and glitches (part of his lore: he is
 * the Spark Drop that woke up and won't go). [strength] eases in and out with him; [shift] is how far the screen is
 * torn sideways right now, for whatever sits under the overlay to follow.
 */
class SparkGlitch {
    var strength by mutableFloatStateOf(0f)
        internal set
    var time by mutableFloatStateOf(0f)
        internal set

    /** 0..1: how hard a burst of glitching is hitting at the moment (most of the time, none). */
    val burst: Float get() {
        val beat = floor(time * 6f)
        if (hash(beat) > 0.24f) return 0f
        return (1f - (time * 6f - beat)) * strength
    }

    /** Sideways tear in pixels, at full strength. */
    fun shift(px: Float): Float = burst * px * (hash(floor(time * 30f) + 7f) - 0.5f) * 2f
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

/** Draws [g] over everything under it: a red wash, scanlines, and bars of torn colour while a burst is on. */
@Composable
fun SparkGlitchOverlay(g: SparkGlitch, modifier: Modifier = Modifier) {
    if (g.strength <= 0f) return
    Canvas(modifier.fillMaxSize()) {
        val s = g.strength
        val t = g.time
        val b = g.burst
        // Everything goes red: what is under it is multiplied down to its red, and a red glow laid on top.
        drawRect(Color(1f, 0.22f + 0.08f * sin(t * 3f), 0.26f), blendMode = BlendMode.Multiply, alpha = 0.75f * s)
        drawRect(Color(0xFFFF1A2E), alpha = (0.1f + 0.12f * b + 0.03f * sin(t * 9f)) * s)
        // Scanlines, rolling slowly down.
        val gap = 4.dp.toPx()
        var y = (t * 18f) % gap
        while (y < size.height) {
            drawRect(Color.Black, Offset(0f, y), Size(size.width, 1.dp.toPx()), alpha = 0.16f * s)
            y += gap
        }
        // A burst: bars torn sideways, red one way and cyan the other, and a strip of static.
        if (b > 0f) {
            val seed = floor(t * 30f)
            val bars = 3 + (hash(seed) * 5f).toInt()
            for (i in 0 until bars) {
                val by = hash(seed + i * 3.1f) * size.height
                val bh = (4f + hash(seed + i * 5.7f) * 34f).dp.toPx()
                val dx = (hash(seed + i * 9.3f) - 0.5f) * 90.dp.toPx() * b
                drawRect(Color(0xFFFF2040), Offset(dx, by), Size(size.width, bh), alpha = 0.42f * b)
                drawRect(Color(0xFF30F0FF), Offset(-dx * 0.6f, by + bh * 0.35f), Size(size.width, bh * 0.3f), alpha = 0.3f * b)
            }
            val sy = hash(seed + 41f) * size.height
            val cell = 6.dp.toPx()
            var x = 0f
            var k = 0
            while (x < size.width) {
                drawRect(Color.White, Offset(x, sy), Size(cell, cell * 1.4f), alpha = hash(seed + k * 0.37f) * 0.5f * b)
                x += cell; k++
            }
        }
    }
}
