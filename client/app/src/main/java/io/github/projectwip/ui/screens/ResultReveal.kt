package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.MatchOutcome
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** "1ST", "2ND", "3RD", "4TH"... */
fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "TH"
    n % 10 == 1 -> "ST"
    n % 10 == 2 -> "ND"
    n % 10 == 3 -> "RD"
    else -> "TH"
}

/**
 * The moment a match ends, before its stats: the screen goes dark, light sweeps in, the result slams down letter by
 * letter (in Last Spark, the place you finished, in a medallion), and your fighter rises into the light and celebrates
 * (or, beaten, shakes their head). Then it all sweeps away to the stats. A tap skips it. Our own take on a familiar
 * idea: the look, the words and the timing are the game's.
 */
@Composable
fun ResultReveal(summary: MatchSummary, onDone: () -> Unit) {
    val r = summary.report
    val sfx = LocalSfx.current
    val ffa = r.mode == GameMode.LAST_SPARK
    val won = if (ffa) r.placement == 1 else r.outcome == MatchOutcome.VICTORY
    val podium = ffa && r.placement in 2..4
    val (word, color) = when {
        ffa -> ordinal(r.placement) to when {
            r.placement == 1 -> Palette.Gold
            r.placement <= 4 -> Palette.Cyan
            else -> Palette.Red
        }
        r.outcome == MatchOutcome.VICTORY -> "VICTORY" to Palette.Gold
        r.outcome == MatchOutcome.DRAW -> "DRAW" to Color.White
        else -> "DEFEAT" to Palette.Red
    }
    val line = when {
        ffa && r.placement == 1 -> "YOU ARE FIRST!"
        ffa -> "YOU PLACED ${ordinal(r.placement)}"
        r.mode == GameMode.BOSS && won -> "THE BOSS IS DOWN!"
        won -> "YOUR TEAM WINS!"
        r.outcome == MatchOutcome.DRAW -> "NOBODY WINS THIS ONE"
        else -> "BETTER LUCK NEXT TIME"
    }
    val me = summary.players.firstOrNull { it.isPlayer }

    val dark = remember { Animatable(0f) }
    val beams = remember { Animatable(0f) }
    var lettersIn by remember { mutableIntStateOf(0) }
    val letterDrop = remember { List(word.length) { Animatable(0f) } }
    val shake = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val sub = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    var celebrate by remember { mutableIntStateOf(0) }
    var sulk by remember { mutableIntStateOf(0) }
    val leave = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    fun finish() { if (!done) { done = true; onDone() } }

    LaunchedEffect(Unit) {
        launch { dark.animateTo(1f, tween(260)) }
        sfx?.play(Sound.WHOOSH, 0.8f, 0.8f)
        launch { beams.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(220)
        // The letters slam down one after another; the last brings the shake and the flash.
        for (i in word.indices) {
            lettersIn = i + 1
            launch { letterDrop[i].animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)) }
            sfx?.play(Sound.POP, 0.7f, if (won) 0.9f + i * 0.06f else 0.8f - i * 0.03f)
            delay(if (word.length > 4) 70 else 110)
        }
        sfx?.play(Sound.BANNER, pitch = if (won) 1f else if (podium) 0.88f else 0.72f)
        sfx?.buzz(70, 230)
        launch { flash.snapTo(if (won) 1f else 0.45f); flash.animateTo(0f, tween(450)) }
        launch {
            shake.snapTo(1f); shake.animateTo(0f, tween(420, easing = LinearEasing))
        }
        launch { sub.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        delay(250)
        // The fighter rises into the light...
        sfx?.play(Sound.WHOOSH, 0.6f, 1.3f)
        rise.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
        // ...and celebrates, or takes it badly.
        if (won || podium) {
            celebrate++
            sfx?.play(Sound.CHING, 0.9f, 1.1f); sfx?.play(Sound.REWARD, 0.7f)
            delay(1100)
            if (won) { celebrate++; sfx?.play(Sound.CHING, 0.8f, 1.25f); delay(900) }
        } else {
            sulk++
            delay(1500)
        }
        // Away it all goes, to the stats.
        leave.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        finish()
    }

    val time by rememberAnimTime()
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - leave.value; scaleX = 1f + leave.value * 0.15f; scaleY = 1f + leave.value * 0.15f }
            .clickable(remember { MutableInteractionSource() }, null) { finish() },
    ) {
        // The dark, the beams of light sweeping round behind the fighter, and the flash when the result lands.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF070B14).copy(alpha = 0.97f * dark.value))
            val c = Offset(size.width * 0.5f, size.height * 0.62f)
            val reach = size.maxDimension * 1.2f
            rotate(time * 14f, c) {
                for (i in 0 until 14) {
                    val a0 = i * (2 * PI / 14)
                    val a1 = a0 + 0.13
                    val p = Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x + (cos(a0) * reach * beams.value).toFloat(), c.y + (sin(a0) * reach * beams.value).toFloat())
                        lineTo(c.x + (cos(a1) * reach * beams.value).toFloat(), c.y + (sin(a1) * reach * beams.value).toFloat())
                        close()
                    }
                    drawPath(p, color.copy(alpha = (if (won) 0.13f else 0.07f) * beams.value))
                }
            }
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * rise.value), Color.Transparent), c, size.minDimension * 0.55f), size.minDimension * 0.55f, c)
            // A ring of light the fighter stands on.
            drawOval(color.copy(alpha = 0.55f * rise.value), Offset(c.x - size.width * 0.13f, c.y + size.height * 0.27f), androidx.compose.ui.geometry.Size(size.width * 0.26f, size.height * 0.06f), style = Stroke(5.dp.toPx()))
            if (flash.value > 0f) drawRect(Color.White.copy(alpha = 0.55f * flash.value))
        }

        // The fighter, rising into the light (live, so they can celebrate).
        if (me != null) {
            val def = me.boss?.let { Balance.boss(it) } ?: Balance.fighter(me.fighter)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.42f).fillMaxHeight(0.66f)
                    .graphicsLayer {
                        translationY = (1f - rise.value) * size.height * 0.9f
                        alpha = rise.value.coerceIn(0f, 1f)
                    },
            ) {
                io.github.projectwip.ui.FighterView(def, me.skin, Modifier.fillMaxSize(), pedestal = true, celebrateKey = celebrate, sulkKey = sulk)
            }
        }

        // The result: a medallion with the place in Last Spark, the word otherwise, then the line under it.
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                .offset(x = (sin(shake.value * 60f) * 14f * shake.value).dp, y = (cos(shake.value * 47f) * 8f * shake.value).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (ffa) PlaceMedallion(color, time, Modifier.size(150.dp).graphicsLayer { val k = letterDrop.last().value; scaleX = k; scaleY = k; alpha = k.coerceIn(0f, 1f) })
                Row(horizontalArrangement = Arrangement.Center) {
                    for (i in word.indices) {
                        val k = if (i < lettersIn) letterDrop[i].value else 0f
                        GameText(
                            word[i].toString(),
                            Type.Display.copy(fontSize = Type.Display.fontSize * (if (ffa) 2.1f else 2.4f)),
                            color = color, outline = 6.dp,
                            modifier = Modifier.graphicsLayer {
                                // Each letter drops in from big and high, overshooting as it lands.
                                val s = 3f - 2f * k
                                scaleX = s; scaleY = s
                                translationY = (1f - k) * -260f
                                alpha = k.coerceIn(0f, 1f)
                                rotationZ = (1f - k) * (if (i % 2 == 0) -24f else 24f)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            GameText(line, Type.Title, color = Color.White, outline = 4.dp,
                modifier = Modifier.graphicsLayer { alpha = sub.value; translationY = (1f - sub.value) * 40f })
            if (r.mvp && won) {
                Spacer(Modifier.height(6.dp))
                GameText("★ MVP ★", Type.Heading, color = Palette.Gold, outline = 3.dp,
                    modifier = Modifier.graphicsLayer { alpha = sub.value; scaleX = 0.6f + 0.4f * sub.value; scaleY = 0.6f + 0.4f * sub.value })
            }
        }
        PlainText("Tap to skip", Type.Small, color = Palette.TextDim, modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).graphicsLayer { alpha = 0.7f * dark.value })
    }
}

/** A star-burst medallion behind the place you finished in Last Spark: points turning slowly, a ring, and a glow. */
@Composable
private fun PlaceMedallion(color: Color, time: Float, modifier: Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), c, r), r, c)
        rotate(time * 20f, c) {
            val p = Path()
            val points = 12
            for (i in 0 until points * 2) {
                val a = i * PI / points
                val rr = if (i % 2 == 0) r * 0.95f else r * 0.7f
                val x = c.x + (cos(a) * rr).toFloat()
                val y = c.y + (sin(a) * rr).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, Color(0xFF101828).copy(alpha = 0.85f))
            drawPath(p, color, style = Stroke(4.dp.toPx()))
        }
        drawCircle(color.copy(alpha = 0.9f), r * 0.6f, c, style = Stroke(3.dp.toPx()))
    }
}
