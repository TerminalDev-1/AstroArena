package io.github.projectwip.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.FighterDef
import io.github.projectwip.render.FighterArt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------- text

/** Display text with a thick ink outline and drop shadow — the game's signature type treatment. */
@Composable
fun GameText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = style.color,
    outline: Dp = 3.dp,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    val px = with(LocalDensity.current) { outline.toPx() }
    Box(modifier) {
        BasicText(
            text, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            style = style.copy(
                color = Palette.Ink, textAlign = align,
                drawStyle = Stroke(width = px * 2, join = StrokeJoin.Round),
                shadow = Shadow(Palette.Ink, Offset(0f, px * 1.1f), 0f),
            ),
        )
        BasicText(text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style.copy(color = color, textAlign = align))
    }
}

@Composable
fun PlainText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = style.color, align: TextAlign = TextAlign.Start, maxLines: Int = 3) {
    BasicText(text, modifier, style = style.copy(color = color, textAlign = align), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// ---------------------------------------------------------------------------------------------- plates & buttons

fun plateShape(big: Dp = 12.dp, small: Dp = 5.dp): Shape =
    CutCornerShape(topStart = big, topEnd = small, bottomEnd = big, bottomStart = small)

enum class ButtonStyle(val top: Color, val bottom: Color, val lip: Color, val text: Color = Color.White) {
    ORANGE(Palette.Orange, Palette.OrangeDeep, Palette.OrangeLip),
    CYAN(Palette.Cyan, Palette.CyanDeep, Palette.CyanLip),
    GREEN(Palette.Green, Palette.GreenDeep, Palette.GreenLip),
    GREY(Palette.Grey, Palette.GreyDeep, Palette.GreyLip),
    RED(Palette.Red, Palette.RedDeep, Palette.RedLip),
    PURPLE(Palette.PanelLight, Palette.Panel, Palette.PanelDark),
    GOLD(Color(0xFFFFE066), Palette.Gold, Color(0xFFA8650A)),
}

private fun DrawScope.plate(shape: Shape, size: Size, brush: Brush, ink: Color, inkWidth: Float, gloss: Boolean) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawPath(path, brush)
    if (gloss) clipPath(path) {
        drawRect(Color.White.copy(alpha = 0.22f), topLeft = Offset(0f, 0f), size = Size(size.width, size.height * 0.42f))
    }
    drawPath(path, ink, style = Stroke(inkWidth, join = StrokeJoin.Round))
}

/**
 * The main button: a chamfered plate with a 3D lip that physically presses down.
 * Plays a tap sound; never looks like a stock Android button.
 */
@Composable
fun ChunkyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.ORANGE,
    enabled: Boolean = true,
    cut: Dp = 12.dp,
    lip: Dp = 5.dp,
    sound: Sound = Sound.TAP,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(70), label = "press")
    val sfx = LocalSfx.current
    val s = if (enabled) style else ButtonStyle.GREY
    val shape = remember(cut) { plateShape(cut, cut * 0.4f) }
    Box(
        modifier
            .clickable(interaction, indication = null) {
                if (enabled) { sfx?.play(sound); onClick() } else sfx?.play(Sound.DENIED, 0.6f)
            }
            .drawBehind {
                val lipPx = lip.toPx()
                val ink = 2.5.dp.toPx()
                val pressPx = lipPx * press
                plate(shape, size, Brush.verticalGradient(listOf(s.lip, s.lip)), Palette.Ink, ink, gloss = false)
                translate(top = pressPx) {
                    plate(shape, Size(size.width, size.height - lipPx), Brush.verticalGradient(listOf(s.top, s.bottom)), Palette.Ink, ink, gloss = true)
                }
            }
            .padding(top = lip * press, bottom = lip * (1f - press)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A raised panel plate. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = Palette.Panel,
    colorBottom: Color = Palette.PanelDark,
    cut: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(cut) { plateShape(cut, cut * 0.35f) }
    Box(
        modifier.drawBehind {
            val ink = 3.dp.toPx()
            plate(shape, size, Brush.verticalGradient(listOf(color, colorBottom)), Palette.Ink, ink, gloss = false)
            // inner rim highlight
            val inset = 4.dp.toPx()
            translate(inset, inset) {
                val inner = Size(size.width - inset * 2, size.height - inset * 2)
                val o = plateShape(cut - 2.dp, (cut * 0.35f - 1.dp).coerceAtLeast(1.dp)).createOutline(inner, layoutDirection, this)
                drawPath(Path().apply { addOutline(o) }, Color.White.copy(alpha = 0.08f), style = Stroke(1.5.dp.toPx()))
            }
        },
        content = content,
    )
}

/** Small tag/badge, e.g. "LV 5", "NEW", "!" */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Palette.Red, textColor: Color = Color.White) {
    Box(
        modifier
            .drawBehind {
                val o = plateShape(6.dp, 2.dp).createOutline(size, layoutDirection, this)
                val p = Path().apply { addOutline(o) }
                drawPath(p, color)
                drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { GameText(text, Type.Label, color = textColor, outline = 2.dp) }
}

// ---------------------------------------------------------------------------------------------- currency

@Composable
fun CurrencyPill(icon: IconKind, value: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shown by animateIntAsState(value, tween(700), label = "currency")
    Box(modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .padding(start = 16.dp)
                .widthIn(min = 92.dp)
                .height(32.dp)
                .drawBehind {
                    val o = plateShape(8.dp, 3.dp).createOutline(size, layoutDirection, this)
                    val p = Path().apply { addOutline(o) }
                    drawPath(p, Palette.PanelInset)
                    drawPath(p, Palette.Ink, style = Stroke(2.5.dp.toPx()))
                }
                .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null) { onClick() } else Modifier)
                .padding(start = 28.dp, end = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) { GameText("%,d".format(shown), Type.Heading, outline = 2.5.dp) }
        GameIcon(icon, Modifier.size(40.dp))
    }
}

// ---------------------------------------------------------------------------------------------- background

@Composable
fun rememberAnimTime(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    while (true) withFrameNanos { value = (it - start) / 1e9f }
}

/** Animated backdrop: deep gradient, slow diagonal stripes and a hex-dot field. */
@Composable
fun GameBackground(modifier: Modifier = Modifier, tint: Color = Palette.BgTop) {
    val time by rememberAnimTime()
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(tint, Palette.BgBottom)))
        val stripe = 46.dp.toPx()
        val shift = (time * 14.dp.toPx()) % (stripe * 2)
        rotate(-28f) {
            var x = -size.maxDimension + shift
            while (x < size.maxDimension * 2) {
                drawRect(Color.White.copy(alpha = 0.035f), Offset(x, -size.maxDimension), Size(stripe, size.maxDimension * 3))
                x += stripe * 2
            }
        }
        val step = 34.dp.toPx()
        val r = 2.dp.toPx()
        var row = 0
        var y = 0f
        while (y < size.height + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < size.width + step) {
                val wave = (sin(time * 1.2f + x * 0.004f + y * 0.006f) + 1f) / 2f
                drawCircle(Color.White.copy(alpha = 0.03f + 0.05f * wave), r, Offset(x, y))
                x += step
            }
            y += step * 0.87f; row++
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), center = center, radius = size.maxDimension * 0.75f))
    }
}

// ---------------------------------------------------------------------------------------------- fighters

/** Renders the shared [FighterArt] inside Compose, idling on a pedestal. */
@Composable
fun FighterView(
    def: FighterDef,
    skin: Int,
    modifier: Modifier = Modifier,
    pedestal: Boolean = true,
    rays: Boolean = false,
    locked: Boolean = false,
    facingLeft: Boolean = false,
) {
    val art = remember { FighterArt() }
    val time by rememberAnimTime()
    val accent = Color(def.skins[skin.coerceIn(0, def.skins.lastIndex)].secondary)
    Canvas(modifier) {
        val unit = size.minDimension / 3.4f
        val cx = size.width / 2
        val cy = size.height * 0.6f
        if (rays) {
            rotate(time * 12f, Offset(cx, cy - unit * 0.6f)) {
                for (i in 0 until 12) {
                    val a0 = i * (2 * PI / 12)
                    val a1 = a0 + 0.16
                    val r = size.maxDimension
                    val p = Path().apply {
                        moveTo(cx, cy - unit * 0.6f)
                        lineTo(cx + (cos(a0) * r).toFloat(), cy - unit * 0.6f + (sin(a0) * r).toFloat())
                        lineTo(cx + (cos(a1) * r).toFloat(), cy - unit * 0.6f + (sin(a1) * r).toFloat())
                        close()
                    }
                    drawPath(p, accent.copy(alpha = 0.10f))
                }
            }
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(cx, cy - unit * 0.6f), radius = unit * 2.2f), unit * 2.2f, Offset(cx, cy - unit * 0.6f))
        }
        if (pedestal) {
            val pw = unit * 1.55f
            val ph = unit * 0.5f
            val top = cy + unit * 0.78f
            drawOval(Palette.Ink, Offset(cx - pw - 3, top - ph / 2 + unit * 0.16f), Size(pw * 2 + 6, ph + 6))
            drawOval(Brush.verticalGradient(listOf(Palette.PanelLight, Palette.PanelDark), startY = top - ph / 2, endY = top + ph / 2 + unit * 0.16f),
                Offset(cx - pw, top - ph / 2 + unit * 0.16f), Size(pw * 2, ph))
            drawOval(Brush.verticalGradient(listOf(Color(0xFF6A56D8), Palette.Panel), startY = top - ph / 2, endY = top + ph / 2),
                Offset(cx - pw, top - ph / 2), Size(pw * 2, ph))
            drawOval(accent.copy(alpha = 0.55f + 0.25f * sin(time * 2.5f)), Offset(cx - pw * 0.8f, top - ph * 0.4f), Size(pw * 1.6f, ph * 0.8f), style = Stroke(3.dp.toPx()))
        }
        drawIntoCanvas { c ->
            art.draw(c.nativeCanvas, cx, cy, unit, def, skin, if (facingLeft) PI.toFloat() * 0.85f else -0.35f, 0f, time,
                alpha = if (locked) 255 else 255)
        }
        if (locked) {
            drawCircle(Palette.BgBottom.copy(alpha = 0.55f), unit * 1.9f, Offset(cx, cy - unit * 0.3f))
        }
    }
}

// ---------------------------------------------------------------------------------------------- misc

@Composable
fun ProgressBar(
    fraction: Float, modifier: Modifier = Modifier, fill: Color = Palette.Gold, fillDeep: Color = Palette.GoldDeep,
    animate: Boolean = true,
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(if (animate) 900 else 0), label = "bar")
    Canvas(modifier) {
        val r = size.height / 2
        drawRoundRect(Palette.Ink, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        val inset = 2.5.dp.toPx()
        val inner = Size(size.width - inset * 2, size.height - inset * 2)
        drawRoundRect(Palette.PanelInset, Offset(inset, inset), inner, androidx.compose.ui.geometry.CornerRadius(r, r))
        if (f > 0f) {
            drawRoundRect(Brush.verticalGradient(listOf(fill, fillDeep)), Offset(inset, inset), Size(inner.width * f, inner.height), androidx.compose.ui.geometry.CornerRadius(r, r))
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(inset + 3, inset + 2), Size((inner.width * f - 6).coerceAtLeast(0f), inner.height * 0.3f), androidx.compose.ui.geometry.CornerRadius(r, r))
        }
    }
}

/** Header row used by every sub-screen: back button, title, and wallet. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, bolts: Int?, prisms: Int?, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChunkyButton(onBack, Modifier.size(52.dp, 50.dp), ButtonStyle.PURPLE) { GameIcon(IconKind.BACK, Modifier.size(26.dp)) }
        Spacer(Modifier.width(14.dp))
        GameText(title, Type.Title, outline = 3.5.dp)
        Spacer(Modifier.width(14.dp))
        extra()
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (bolts != null) CurrencyPill(IconKind.BOLT, bolts)
            if (prisms != null) CurrencyPill(IconKind.PRISM, prisms)
        }
    }
}

/** A brief scale "pop" whenever [key] changes. */
@Composable
fun Modifier.popOnChange(key: Any?): Modifier {
    val anim = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        anim.snapTo(1.35f)
        anim.animateTo(1f, tween(380, easing = LinearEasing))
    }
    return this.graphicsLayer { scaleX = anim.value; scaleY = anim.value }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) = Box(modifier.offset().drawBehind { drawCircle(color) })
