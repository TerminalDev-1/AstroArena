package io.github.projectwip.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.Lore
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.SoulView
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.sin

/** How long the hijack cutscene runs: the Entity reaching in, taking hold, and the fighter bursting back out. */
private const val HIJACK_SECONDS = 4.2f
private const val TAKE_HOLD = 2.7f
private const val BURST = 3.5f

private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

private fun hash(a: Int, b: Int): Float {
    var h = a * 73856093 xor b * 19349663
    h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
    return (h and 0xFFFF) / 65535f
}

/**
 * The Entity taking the player's fighter over (the user's sequence, after its comet has come down into them): the
 * match is held still behind this. On the left the Entity, in 3D, glitching through the souls it carries, leans in;
 * blue streaks reach out of it into the fighter on the right, who shakes and tears with glitches as it takes hold
 * and speaks; then the fighter bursts out toward the camera in a flash, and play goes on with the Entity at the
 * controls. A tap skips it. [index] picks the line it says.
 */
@Composable
fun EntityHijack(fighter: FighterId, skin: Int, index: Int, sfx: Sfx?, onDone: () -> Unit) {
    val clock by rememberAnimTime()
    val line = remember(index) { Lore.hijackLine(index) }
    val startedAt = remember { System.currentTimeMillis() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sfx?.play(Sound.VOID)
        // (No glitch blip as it takes hold, or when the Entity flickers into another soul: the user hated it.)
        kotlinx.coroutines.delay((BURST * 1000).toLong())
        if (!done) sfx?.play(Sound.WHOOSH)
        kotlinx.coroutines.delay(((HIJACK_SECONDS - BURST) * 1000).toLong())
        if (!done) { done = true; onDone() }
    }
    val t = clock
    val hold = ease((t - 0.6f) / (TAKE_HOLD - 0.6f))
    val out = ease((t - BURST) / (HIJACK_SECONDS - BURST))
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { if (clock > 0.8f && !done) { done = true; onDone() } }
    }) {
        // The dark it happens in, closing in from the match behind.
        Canvas(Modifier.fillMaxSize()) {
            val a = ease(t / 0.35f) * (1f - out)
            drawRect(Brush.radialGradient(listOf(Color(0xFF2A1260).copy(alpha = a), Color(0xFF07020F).copy(alpha = a)),
                Offset(size.width * 0.5f, size.height * 0.45f), size.width * 0.75f))
        }
        // The Entity, leaning in from the left.
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart).graphicsLayer {
            alpha = ease(t / 0.5f) * (1f - out)
            translationX = size.width * (-0.15f + 0.12f * hold)
            val k = 1.05f + 0.1f * hold; scaleX = k; scaleY = k
        }) {
            SoulView(Modifier.fillMaxSize(), voidCycle = true, talking = t in 0.7f..2.6f)
        }
        // The fighter, held, shaking as it takes hold, then bursting out toward the camera.
        Box(Modifier.fillMaxHeight(0.86f).fillMaxWidth(0.66f).align(Alignment.CenterEnd).graphicsLayer {
            val shake = if (t > 0.9f) (2f + 10f * hold) * (1f - out) else 0f
            val beat = (t * 30f).toInt()
            translationX = (hash(beat, 1) - 0.5f) * shake * density + (-size.width * 0.35f * out)
            translationY = (hash(beat, 2) - 0.5f) * shake * 0.6f * density
            val k = 1f + 0.9f * out; scaleX = k; scaleY = k
            alpha = ease(t / 0.3f) * (1f - ease((t - HIJACK_SECONDS + 0.3f) / 0.3f))
        }) {
            // (Its comets pour into them in 3D on their own stage, as in a match: `StageScene.addHijackComets`.)
            FighterView(Balance.fighter(fighter), skin, Modifier.fillMaxSize(), pedestal = true, hijackAt = startedAt)
        }
        // What it says, on the game's own plate.
        val typed = ((t - 0.7f) * 26f).toInt().coerceIn(0, line.length)
        if (t > 0.6f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.72f).padding(bottom = 22.dp).height(110.dp).graphicsLayer {
            alpha = ease((t - 0.6f) / 0.25f) * (1f - ease((t - BURST) / 0.25f))
        }) {
            Panel(Modifier.fillMaxWidth().height(86.dp).align(Alignment.BottomCenter), color = Color(0xFF2D1D74), colorBottom = Color(0xFF1D1252), cut = 16.dp) {
                GameText(line.take(typed), Type.Heading.copy(fontSize = Type.Heading.fontSize * 1.15f, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = Color.White, outline = 3.dp, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 26.dp))
            }
            Box(Modifier.align(Alignment.TopStart).padding(start = 26.dp)) {
                Panel(color = Color(0xFF7A3CDC), colorBottom = Color(0xFF4A2290), cut = 9.dp) {
                    GameText("THE ENTITY", Type.Heading, color = Color.White, outline = 2.5.dp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                }
            }
        }
        // Tearing glitches as it takes hold, and the flash it bursts out in.
        Canvas(Modifier.fillMaxSize()) {
            drawTear(t, hold * 0.5f + ease((t - TAKE_HOLD) / 0.4f) * 0.6f * (1f - out))
            val flash = maxOf(1f - ease(t / 0.35f), ease((t - BURST) / 0.35f) * (1f - ease((t - HIJACK_SECONDS + 0.35f) / 0.35f)))
            if (flash > 0f) drawRect(Color(0xFFD8ECFF).copy(alpha = flash * 0.9f))
        }
    }
}

/** Torn colour bands and blocks across the picture, [k] from none to a lot. */
private fun DrawScope.drawTear(t: Float, k: Float) {
    if (k <= 0.02f) return
    val w = size.width; val h = size.height
    val beat = (t * 14f).toInt()
    if (hash(beat / 2, 77) > 0.25f + 0.7f * k) return
    for (i in 0 until 1 + (k * 6).toInt()) {
        val by = hash(beat, i * 3) * h
        val bh = (3f + hash(beat, i * 3 + 1) * 22f).dp.toPx()
        val off = (hash(beat, i * 3 + 2) - 0.5f) * 60.dp.toPx() * k
        drawRect(Color(0xFFFF2A9A).copy(alpha = 0.3f * k), Offset(off, by), Size(w, bh / 2), blendMode = BlendMode.Plus)
        drawRect(Color(0xFF2AE6FF).copy(alpha = 0.3f * k), Offset(-off, by + bh / 2), Size(w, bh / 2), blendMode = BlendMode.Plus)
    }
    for (i in 0 until (k * 12).toInt()) {
        val bx = hash(beat, 100 + i) * w; val by = hash(beat, 200 + i) * h
        drawRect(if (i % 2 == 0) Color.Black.copy(alpha = 0.6f * k) else Color.White.copy(alpha = 0.3f * k), Offset(bx, by), Size((6f + hash(beat, 300 + i) * 60f).dp.toPx(), (3f + hash(beat, 400 + i) * 14f).dp.toPx()))
    }
}
