package io.github.projectwip.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.BuildConfig
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterDef
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Shown while the game gets ready: it connects to the game server, checks GitHub for a newer release and builds
 * its sounds and music. [progress] is real (0..1 of that work), and [status] says which part is happening.
 * [onSkip], when given, offers a way to stop waiting for the server.
 *
 * A night sky over an arena floor that rolls toward the player, one fighter at a time in a spotlight, the boot
 * steps ticking off, and a tip.
 */
@Composable
fun LoadingScreen(progress: Float, status: String, onSkip: (() -> Unit)? = null) {
    val time by rememberAnimTime()
    val roomy = LocalUi.current.roomy
    val fighters = Balance.fighters
    BoxWithConstraints(
        // Swallows touches so nothing underneath can be pressed while loading.
        Modifier.fillMaxSize().background(Color(0xFF050B14)).clickable(remember { MutableInteractionSource() }, null) { },
    ) {
        val w = maxWidth
        val h = maxHeight
        Backdrop(time)

        // Left: the spotlight, a new fighter every few seconds.
        val slot = floor(time / SPOT_SECONDS).toInt()
        val featured = fighters[slot % fighters.size]
        val phase = (time / SPOT_SECONDS) - slot
        val fighterSize = min(h.value * 0.66f, w.value * 0.38f).dp
        Column(
            Modifier.width(w * 0.46f).fillMaxHeight().padding(top = h * 0.04f, bottom = h * 0.05f),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.size(fighterSize * 1.25f, fighterSize), contentAlignment = Alignment.BottomCenter) {
                Spotlight(featured, time, phase, Modifier.fillMaxSize())
                Crossfade(featured, animationSpec = tween(420), label = "featured") { def ->
                    FighterView(def, 0, Modifier.size(fighterSize).graphicsLayer {
                        translationY = 6.dp.toPx() * sin(time * 2.2f)
                    }, pedestal = false)
                }
            }
            // The models stand at about 80% of their view, so the name moves up under the pad.
            Crossfade(featured, Modifier.offset(y = -fighterSize * 0.1f), animationSpec = tween(420), label = "featuredName") { def -> FeaturedName(def, roomy) }
            Spacer(Modifier.height(8.dp))
            // Which fighter of the roster is showing.
            Row(Modifier.offset(y = -fighterSize * 0.1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                fighters.forEachIndexed { i, def ->
                    val on = def == featured
                    Box(Modifier.size(if (on) 22.dp else 9.dp, 9.dp).background(
                        if (on) Color(def.rarity.color) else Color.White.copy(alpha = 0.28f), RoundedCornerShape(5.dp)))
                }
            }
        }

        // Right: the name of the game, what is happening, and the bar.
        Column(
            Modifier.align(Alignment.CenterEnd).width(w * 0.52f).padding(end = if (roomy) 40.dp else 22.dp, top = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(time, roomy)
            Spacer(Modifier.height(if (roomy) 26.dp else 12.dp))
            Steps(stepOf(status), time)
            Spacer(Modifier.height(if (roomy) 16.dp else 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlowBar(progress, time, Modifier.weight(1f).height(if (roomy) 30.dp else 24.dp))
                Spacer(Modifier.width(12.dp))
                GameText("${(progress * 100).toInt()}%", Type.Title, color = Palette.Gold, outline = 3.5.dp, modifier = Modifier.width(76.dp))
            }
            Spacer(Modifier.height(6.dp))
            PlainText(status.trimEnd('…', '.', '!').uppercase() + if (status == "Ready!") "!" else ".".repeat(1 + (time * 2.5f).toInt() % 3),
                Type.Label, Modifier.background(Color(0xB0050B14), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
                color = Palette.TextDim, maxLines = 1)
            Spacer(Modifier.height(if (roomy) 22.dp else 10.dp))
            TipCard(time, roomy)
        }

        // Spark in the spotlight takes the whole screen over: it goes red and glitches.
        val glitch = io.github.projectwip.ui.rememberSparkGlitch { featured.id == io.github.projectwip.data.FighterId.BYTE }
        io.github.projectwip.ui.SparkGlitchOverlay(glitch)

        if (onSkip != null) ChunkyButton(onSkip, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 18.dp).size(240.dp, 50.dp), ButtonStyle.GREY, lip = 4.dp) {
            GameText("PLAY OFFLINE (DEV)", Type.Label, outline = 2.dp)
        }
        PlainText(BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE, Type.Small, Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Palette.TextDim)
    }
}

/** Seconds each fighter stays in the spotlight. */
private const val SPOT_SECONDS = 3.2f

/** The steps of the boot, in the order the game does them; the status line says which one is running. */
private val STEPS = listOf("SERVER", "UPDATES", "SOUNDS", "MUSIC")

private fun stepOf(status: String) = when {
    status.startsWith("Connecting") -> 0
    status.startsWith("Checking") -> 1
    status.startsWith("Building") -> 2
    status.startsWith("Composing") -> 3
    else -> STEPS.size
}

/** Sky, stars, a drifting glow, and the arena floor rolling in under a lit horizon. */
@Composable
private fun Backdrop(time: Float) {
    val stars = remember {
        val r = java.util.Random(7)
        List(110) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.8f, r.nextFloat() * 6.28f, 0.004f + r.nextFloat() * 0.012f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val horizon = size.height * 0.73f
        drawRect(Brush.verticalGradient(0f to Color(0xFF07101E), 0.73f to Color(0xFF17406A), 0.731f to Color(0xFF0B2236), 1f to Color(0xFF040A12)))
        // Two slow clouds of light.
        val drift = sin(time * 0.15f)
        drawCircle(Brush.radialGradient(listOf(Palette.Cyan.copy(alpha = 0.20f), Color.Transparent),
            Offset(size.width * (0.72f + 0.05f * drift), size.height * 0.28f), size.width * 0.38f),
            size.width * 0.38f, Offset(size.width * (0.72f + 0.05f * drift), size.height * 0.28f))
        drawCircle(Brush.radialGradient(listOf(Palette.PrismDeep.copy(alpha = 0.18f), Color.Transparent),
            Offset(size.width * (0.18f - 0.04f * drift), size.height * 0.18f), size.width * 0.32f),
            size.width * 0.32f, Offset(size.width * (0.18f - 0.04f * drift), size.height * 0.18f))
        // Stars drift slowly across and twinkle.
        for (s in stars) {
            val x = ((s[0] - time * s[4]) % 1f + 1f) % 1f * size.width
            val y = s[1] * horizon * 0.96f
            val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * 1.7f + s[3]))
            drawCircle(Color.White.copy(alpha = a * 0.85f), s[2].dp.toPx() * 0.6f, Offset(x, y))
        }
        // A shooting star now and then.
        val shot = (time % 7f) / 0.9f
        if (shot < 1f) {
            val sx = size.width * (0.95f - 0.5f * shot); val sy = size.height * (0.06f + 0.22f * shot)
            drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f)), Offset(sx + 120.dp.toPx(), sy - 52.dp.toPx()), Offset(sx, sy)),
                Offset(sx + 120.dp.toPx(), sy - 52.dp.toPx()), Offset(sx, sy), 2.dp.toPx(), StrokeCap.Round)
        }
        arenaFloor(horizon, time)
    }
}

private fun DrawScope.arenaFloor(horizon: Float, time: Float) {
    val line = Palette.Cyan
    val depth = size.height - horizon
    // Rows come toward the viewer, closer together near the horizon.
    val rows = 12
    val roll = (time * 0.45f) % 1f
    for (k in 0..rows) {
        val t = (k + roll) / rows
        val y = horizon + depth * t * t
        drawLine(line.copy(alpha = 0.06f + 0.30f * t), Offset(0f, y), Offset(size.width, y), (0.6f + 1.4f * t).dp.toPx())
    }
    // Lanes run out from the middle of the horizon.
    val vx = size.width / 2
    for (i in -14..14) {
        val bx = vx + i * size.width * 0.11f
        drawLine(Brush.verticalGradient(listOf(line.copy(alpha = 0.03f), line.copy(alpha = 0.32f)), horizon, size.height),
            Offset(vx + i * size.width * 0.012f, horizon), Offset(bx, size.height), 1.2.dp.toPx())
    }
    // The lit horizon.
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, line.copy(alpha = 0.35f), Color.Transparent), horizon - 26.dp.toPx(), horizon + 26.dp.toPx()),
        Offset(0f, horizon - 26.dp.toPx()), Size(size.width, 52.dp.toPx()))
    drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, horizon), Offset(size.width, horizon), 1.5.dp.toPx())
}

/** Rays in the fighter's rarity colour, and a lit pad under their feet that rings out when a new one steps on. */
@Composable
private fun Spotlight(def: FighterDef, time: Float, phase: Float, modifier: Modifier) {
    val glow = Color(def.rarity.color)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * 0.45f)
        val r = size.maxDimension * 0.62f
        rotate(time * 9f, c) {
            for (i in 0 until 12) {
                val a0 = i * (2 * PI / 12)
                val a1 = a0 + 0.17
                val p = Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(c.x + (cos(a0) * r).toFloat(), c.y + (sin(a0) * r).toFloat())
                    lineTo(c.x + (cos(a1) * r).toFloat(), c.y + (sin(a1) * r).toFloat())
                    close()
                }
                drawPath(p, Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent), c, r))
            }
        }
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), Color.Transparent), c, r * 0.55f), r * 0.55f, c)
        // The pad.
        val pad = Offset(size.width / 2, size.height * 0.81f)
        val pw = size.width * 0.56f; val ph = pw * 0.24f
        drawOval(Brush.radialGradient(listOf(glow.copy(alpha = 0.75f), glow.copy(alpha = 0.15f), Color.Transparent), pad, pw / 2),
            Offset(pad.x - pw / 2, pad.y - ph / 2), Size(pw, ph))
        drawOval(Color.White.copy(alpha = 0.55f + 0.25f * sin(time * 4f)), Offset(pad.x - pw * 0.4f, pad.y - ph * 0.4f), Size(pw * 0.8f, ph * 0.8f),
            style = Stroke(2.dp.toPx()))
        // The ring that goes out as a fighter arrives.
        if (phase < 0.45f) {
            val k = phase / 0.45f
            val rw = pw * (0.6f + 0.9f * k)
            drawOval(glow.copy(alpha = 1f - k), Offset(pad.x - rw / 2, pad.y - rw * 0.12f), Size(rw, rw * 0.24f), style = Stroke((4f * (1f - k) + 1f).dp.toPx()))
        }
    }
}

@Composable
private fun FeaturedName(def: FighterDef, roomy: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GameText(def.name.uppercase(), if (roomy) Type.Display.copy(fontSize = Type.Display.fontSize * 1.25f) else Type.Display, outline = 4.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Color(def.rarity.color), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                PlainText(def.rarity.label.uppercase(), Type.Small, color = Palette.Ink, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            PlainText(def.title, Type.Label, color = Color.White, maxLines = 1)
        }
    }
}

/** ASTRO in white, ARENA in gold, a light running along the bar beneath. */
@Composable
private fun Logo(time: Float, roomy: Boolean) {
    val big = Type.Display.copy(fontSize = Type.Display.fontSize * if (roomy) 1.6f else 1.2f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
        val s = 1f + 0.015f * sin(time * 1.6f); scaleX = s; scaleY = s
    }) {
        Row {
            GameText("ASTRO", big, color = Color.White, outline = 6.dp)
            GameText("ARENA", big, color = Palette.Gold, outline = 6.dp)
        }
        Canvas(Modifier.width(if (roomy) 420.dp else 300.dp).height(6.dp)) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Palette.GoldDeep, Palette.Gold, Palette.GoldDeep, Color.Transparent)))
            val x = ((time * 0.45f) % 1.4f - 0.2f) * size.width
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent), x - 40.dp.toPx(), x + 40.dp.toPx()),
                Offset(x - 40.dp.toPx(), 0f), Size(80.dp.toPx(), size.height))
        }
        Spacer(Modifier.height(4.dp))
        PlainText("BETA", Type.Label.copy(letterSpacing = Type.Label.fontSize * 0.45f), color = Palette.TextDim, maxLines = 1)
    }
}

/** One chip per boot step: ticked when done, a spinner on the one running, dim for the ones to come. */
@Composable
private fun Steps(current: Int, time: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STEPS.forEachIndexed { i, label ->
            val done = i < current
            val now = i == current
            Row(
                Modifier.background(
                    when { done -> Palette.GreenLip.copy(alpha = 0.75f); now -> Palette.Panel; else -> Palette.PanelInset.copy(alpha = 0.7f) },
                    RoundedCornerShape(7.dp),
                ).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val r = size.minDimension / 2
                    when {
                        done -> {
                            drawCircle(Palette.Green, r)
                            val p = Path().apply { moveTo(r * 0.5f, r * 1.05f); lineTo(r * 0.88f, r * 1.42f); lineTo(r * 1.5f, r * 0.62f) }
                            drawPath(p, Palette.Ink, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                        }
                        now -> {
                            drawCircle(Color.White.copy(alpha = 0.2f), r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                            drawArc(Palette.Gold, time * 400f, 100f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                        else -> drawCircle(Color.White.copy(alpha = 0.3f), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    }
                }
                Spacer(Modifier.width(6.dp))
                PlainText(label, Type.Small, color = if (done || now) Color.White else Palette.TextDim, maxLines = 1)
            }
        }
    }
}

/** A slanted bar: gold fill with stripes rolling along it, a sheen passing over, and a glow at its tip. */
@Composable
private fun GlowBar(fraction: Float, time: Float, modifier: Modifier) {
    Canvas(modifier) {
        val slant = size.height * 0.55f
        val track = Path().apply {
            moveTo(slant, 0f); lineTo(size.width, 0f); lineTo(size.width - slant, size.height); lineTo(0f, size.height); close()
        }
        drawPath(track, Color(0xFF0A1826))
        val fw = slant + (size.width - slant) * fraction.coerceIn(0f, 1f)
        clipPath(track) {
            drawRect(Brush.verticalGradient(listOf(Palette.Gold, Palette.GoldDeep)), size = Size(fw, size.height))
            // Stripes.
            val gap = 18.dp.toPx()
            var x = -gap * 2 + (time * 30.dp.toPx()) % gap
            while (x < fw) {
                val p = Path().apply {
                    moveTo(x + slant, 0f); lineTo(x + slant + gap * 0.45f, 0f); lineTo(x + gap * 0.45f, size.height); lineTo(x, size.height); close()
                }
                clipRect(right = fw) { drawPath(p, Color.White.copy(alpha = 0.16f)) }
                x += gap
            }
            drawRect(Color.White.copy(alpha = 0.28f), size = Size(fw, size.height * 0.32f))
            // A sheen running along the fill.
            val sx = (time * 0.6f % 1.3f) * fw
            clipRect(right = fw) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent), sx - 30.dp.toPx(), sx + 30.dp.toPx()),
                    Offset(sx - 30.dp.toPx(), 0f), Size(60.dp.toPx(), size.height))
            }
        }
        drawPath(track, Palette.PanelLight, style = Stroke(2.dp.toPx()))
        // The tip glows.
        if (fraction > 0.01f) {
            val tip = Offset(fw - slant / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Palette.Gold.copy(alpha = 0.3f), Color.Transparent), tip, size.height * 1.1f), size.height * 1.1f, tip)
        }
    }
}

/** A tip in a panel, a new one every few seconds. */
@Composable
private fun TipCard(time: Float, roomy: Boolean) {
    val tip = TIPS[(time / 5f).toInt() % TIPS.size].removePrefix("Tip: ")
    Panel(Modifier.fillMaxWidth(), cut = 12.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = if (roomy) 12.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Palette.Gold, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                GameText("TIP", Type.Label, color = Palette.Ink, outline = 0.dp)
            }
            Spacer(Modifier.width(12.dp))
            Crossfade(tip, animationSpec = tween(350), label = "tip") { t ->
                PlainText(t, Type.Body, color = Color.White, align = TextAlign.Start, maxLines = 2)
            }
        }
    }
}
