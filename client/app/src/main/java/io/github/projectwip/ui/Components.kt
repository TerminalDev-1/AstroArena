package io.github.projectwip.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.FighterDef
import io.github.projectwip.render3d.FighterStageView
import io.github.projectwip.render3d.Portraits
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------- text

/**
 * Display text: a thin ink edge and a soft shadow under it, so it reads over the 3D lobby without the heavy
 * cartoon outline. [outline] is the old outline weight; the edge drawn is a fraction of it.
 */
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
    val px = with(LocalDensity.current) { outline.toPx() } * 0.42f
    Box(modifier) {
        BasicText(
            text, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            style = style.copy(
                color = Palette.Ink.copy(alpha = 0.9f), textAlign = align,
                drawStyle = Stroke(width = px * 2, join = StrokeJoin.Round),
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, px * 2.4f), px * 3.5f),
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

/**
 * The game's one shape: rounded corners with a diagonal notch cut out of the top right (and a small one bottom left),
 * like a panel off a console.
 */
class NotchShape(private val cut: Dp, private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val half = minOf(size.width, size.height) * 0.5f
        val c = with(density) { cut.toPx() }.coerceIn(0f, half)
        val r = with(density) { radius.toPx() }.coerceIn(0f, half)
        val s = (c * 0.45f).coerceAtMost(half)
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(r, 0f)
            lineTo(w - c, 0f); lineTo(w, c)
            lineTo(w, h - r); quadraticTo(w, h, w - r, h)
            lineTo(s, h); lineTo(0f, h - s)
            lineTo(0f, r); quadraticTo(0f, 0f, r, 0f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(p)
    }
}

fun plateShape(big: Dp = 12.dp, small: Dp = 5.dp): Shape = NotchShape(big, (small * 1.3f).coerceAtLeast(3.dp))

/** [rim] is the light that catches the plate's edges (and glows round it). */
enum class ButtonStyle(val top: Color, val bottom: Color, val lip: Color, val rim: Color, val text: Color = Color.White) {
    ORANGE(Palette.Orange, Palette.OrangeDeep, Palette.OrangeLip, Color(0xFFFFE2A8)),
    CYAN(Palette.Cyan, Palette.CyanDeep, Palette.CyanLip, Color(0xFFC8F6FF)),
    GREEN(Palette.Green, Palette.GreenDeep, Palette.GreenLip, Color(0xFFD2FFE0)),
    GREY(Palette.Grey, Palette.GreyDeep, Palette.GreyLip, Color(0xFFDDE6EE)),
    RED(Palette.Red, Palette.RedDeep, Palette.RedLip, Color(0xFFFFC7CE)),
    /** Solid steel blue, for secondary choices. */
    PURPLE(Palette.PanelLight, Color(0xFF1E4468), Color(0xFF0B1C2E), Palette.Rim),
    GOLD(Color(0xFFFFE066), Palette.Gold, Color(0xFF9A5A06), Color(0xFFFFF6C8)),
    /** Dark steel glass that lets the 3D lobby show through, with an accent edge down its left side. */
    GLASS(Color(0xD8234A72), Color(0xE80B1D30), Color(0xFF050D16), Palette.Rim),
}

/** Soft drop shadow under a plate, so it reads as sitting above the scene. */
private fun DrawScope.plateShadow(path: Path, depth: Float) {
    translate(top = depth) {
        drawPath(path, Color.Black.copy(alpha = 0.16f), style = Stroke(depth * 2.2f, join = StrokeJoin.Round))
        drawPath(path, Color.Black.copy(alpha = 0.34f))
    }
}

/**
 * A plate's face: its fill, shade gathering low, a crisp split highlight across the top half ([gloss]), a bright
 * line of [rim] light along the top edge, a hairline of it all round just inside, and a thin ink edge outside.
 */
private fun DrawScope.plate(shape: Shape, size: Size, brush: Brush, ink: Color, inkWidth: Float, gloss: Boolean, rim: Color = Palette.Rim) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawPath(path, brush)
    clipPath(path) {
        drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.24f), startY = 0f, endY = size.height), size = size)
        if (gloss) drawRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.2f), 0.48f to Color.White.copy(alpha = 0.06f), 0.48f to Color.Transparent, startY = 0f, endY = size.height),
            size = size,
        )
        // A hairline of rim light just inside the edge (the stroke is centred on it, so the clip keeps the inner half).
        drawPath(path, Brush.verticalGradient(0f to rim.copy(alpha = 0.55f), 0.5f to rim.copy(alpha = 0.18f), 1f to rim.copy(alpha = 0.08f), startY = 0f, endY = size.height), style = Stroke(1.5.dp.toPx() * 2))
        drawLine(
            Brush.horizontalGradient(0f to rim.copy(alpha = 0.95f), 0.6f to rim.copy(alpha = 0.5f), 1f to Color.Transparent, startX = 0f, endX = size.width),
            Offset(0f, 0.75.dp.toPx()), Offset(size.width, 0.75.dp.toPx()), 1.5.dp.toPx(),
        )
    }
    drawPath(path, ink, style = Stroke(inkWidth, join = StrokeJoin.Round))
}

/** A soft glow in [color] round [path], drawn behind it. */
private fun DrawScope.glow(path: Path, color: Color, strength: Float = 1f) {
    for (k in 1..3) drawPath(path, color.copy(alpha = 0.06f * strength), style = Stroke(k * 5.dp.toPx(), join = StrokeJoin.Round))
}

/**
 * The main button: a notched slab whose face sits on a visible side and presses down onto it.
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
    /** A band of light sweeps across now and then. It redraws the button every frame, so use it sparingly. */
    sheen: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(70), label = "press")
    val sfx = LocalSfx.current
    val s = if (enabled) style else ButtonStyle.GREY
    val shape = remember(cut) { plateShape(cut, cut * 0.4f) }
    val time = if (sheen && enabled) rememberAnimTime() else null
    val glass = s == ButtonStyle.GLASS || s == ButtonStyle.PURPLE
    Box(
        modifier
            .graphicsLayer { val k = 1f - 0.03f * press; scaleX = k; scaleY = k }
            .clickable(interaction, indication = null) {
                if (enabled) { sfx?.play(sound); onClick() } else sfx?.play(Sound.DENIED, 0.6f)
            }
            .drawBehind {
                val lipPx = lip.toPx() * 0.8f
                val ink = 1.5.dp.toPx()
                val pressPx = lipPx * press
                val whole = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                if (enabled && !glass) glow(whole, s.rim, 0.8f + press)
                plateShadow(whole, lipPx * (1.2f - 0.6f * press))
                // The side of the slab.
                drawPath(whole, Brush.verticalGradient(listOf(lerp(s.lip, s.bottom, 0.25f), lerp(s.lip, Color.Black, 0.3f))))
                drawPath(whole, Palette.Ink, style = Stroke(ink, join = StrokeJoin.Round))
                translate(top = pressPx) {
                    val face = Size(size.width, size.height - lipPx)
                    val faceShape = Path().apply { addOutline(shape.createOutline(face, layoutDirection, this@drawBehind)) }
                    plate(shape, face, Brush.verticalGradient(listOf(lerp(s.top, Color.White, if (glass) 0.04f else 0.14f), s.top, s.bottom)), Palette.Ink, ink, gloss = !glass, rim = s.rim)
                    if (glass) clipPath(faceShape) {
                        // The accent edge down the left side, and a cool wash of light from it.
                        drawRect(Brush.verticalGradient(listOf(s.rim, s.rim.copy(alpha = 0.35f))), Offset.Zero, Size(3.dp.toPx(), face.height))
                        drawRect(Brush.horizontalGradient(listOf(s.rim.copy(alpha = 0.14f), Color.Transparent), 0f, minOf(face.width * 0.5f, 120.dp.toPx())), size = face)
                    }
                    if (time != null) {
                        // A band of light sweeps across every few seconds.
                        val t = (time.value % 3.2f) / 0.7f
                        if (t < 1f) clipPath(faceShape) {
                            val x = -face.height + (face.width + face.height * 2) * t
                            val band = Path().apply {
                                moveTo(x, face.height); lineTo(x + face.height * 0.7f, 0f); lineTo(x + face.height * 1.15f, 0f); lineTo(x + face.height * 0.45f, face.height); close()
                            }
                            drawPath(band, Color.White.copy(alpha = 0.26f))
                        }
                    }
                }
            }
            .padding(top = lip * 0.8f * press, bottom = lip * 0.8f * (1f - press)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A raised panel: a slab of dark steel glass with its side showing, a faint grid, and an accent along its top. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = Palette.Panel,
    colorBottom: Color = Palette.PanelDark,
    cut: Dp = 16.dp,
    rim: Color = Palette.Rim,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(cut) { plateShape(cut, cut * 0.35f) }
    Box(
        modifier.drawBehind {
            val ink = 1.5.dp.toPx()
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            // A panel is a slab: its side shows under the face, and it throws a shadow past that.
            val slab = 4.dp.toPx()
            plateShadow(path, slab + 5.dp.toPx())
            translate(top = slab) {
                drawPath(path, Brush.verticalGradient(listOf(lerp(colorBottom, Color.Black, 0.3f), lerp(colorBottom, Color.Black, 0.65f))))
                drawPath(path, Palette.Ink, style = Stroke(ink, join = StrokeJoin.Round))
            }
            plate(shape, size, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.08f), color, colorBottom)), Palette.Ink, ink, gloss = false, rim = rim)
            clipPath(path) {
                // A faint grid, so large panels aren't a flat fill.
                val step = 24.dp.toPx()
                var x = step
                while (x < size.width) { drawLine(Color.White.copy(alpha = 0.025f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = step
                while (y < size.height) { drawLine(Color.White.copy(alpha = 0.025f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
                // A cool pool of light in the top corner, as if a lamp hung over it.
                val reach = minOf(size.maxDimension * 0.6f, 260.dp.toPx())
                drawRect(Brush.radialGradient(listOf(rim.copy(alpha = 0.12f), Color.Transparent), Offset(size.width * 0.15f, 0f), reach), size = size)
                // The accent: a short bright bar along the top edge.
                val bar = minOf(size.width * 0.3f, 110.dp.toPx())
                val from = cut.toPx() * 0.4f
                drawRect(Brush.horizontalGradient(listOf(rim, rim.copy(alpha = 0f)), from, from + bar), Offset(from, 0f), Size(bar, 3.dp.toPx()))
            }
        },
        content = content,
    )
}

/** Small tag/badge, e.g. "LV 5", "NEW", "!": a lit pill. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Palette.Red, textColor: Color = Color.White, small: Boolean = false) {
    Box(
        modifier
            .drawBehind {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                for (k in 1..2) {
                    val g = k * 2.dp.toPx()
                    drawRoundRect(color.copy(alpha = 0.12f), Offset(-g, -g), Size(size.width + g * 2, size.height + g * 2), androidx.compose.ui.geometry.CornerRadius(size.height / 2 + g))
                }
                drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 2.dp.toPx()), size, r)
                drawRoundRect(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.25f), color, lerp(color, Color.Black, 0.2f))), cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(size.height * 0.3f, 1.5.dp.toPx()), Size((size.width - size.height * 0.6f).coerceAtLeast(0f), size.height * 0.3f), r)
                drawRoundRect(Palette.Ink, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
            }
            .padding(horizontal = if (small) 6.dp else 9.dp, vertical = if (small) 1.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) { GameText(text, if (small) Type.Eyebrow.copy(color = Color.White, letterSpacing = 1.sp) else Type.Label, color = textColor, outline = 1.5.dp) }
}

// ---------------------------------------------------------------------------------------------- currency

/** A wallet total, short enough to fit its counter whatever it grows to: 98,765 · 123.4K · 60.3M · 1.2B. */
fun compactNumber(n: Int): String = when {
    n < 100_000 -> "%,d".format(n)
    n < 1_000_000 -> "%.1fK".format(Math.floor(n / 100.0) / 10)
    n < 1_000_000_000 -> "%.1fM".format(Math.floor(n / 100_000.0) / 10)
    else -> "%.1fB".format(Math.floor(n / 100_000_000.0) / 10)
}

/** A recessed capsule of dark glass for a figure, with rim light along its floor. */
private fun DrawScope.wellCapsule() {
    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
    drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(0f, 2.dp.toPx()), size, r)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xF2050E18), Color(0xE60E2338))), cornerRadius = r)
    drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Palette.Rim.copy(alpha = 0.55f))), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
}

/**
 * Where Credits go. They are not held in a wallet: this shows how far along the Spark Road the fighter being
 * unlocked is ([value] of [goal]).
 */
@Composable
fun RoadMeter(value: Int, goal: Int, modifier: Modifier = Modifier) {
    val shown by animateIntAsState(value, tween(500), label = "road")
    Box(modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.padding(start = 16.dp).widthIn(min = 96.dp).height(30.dp)
                .drawBehind { wellCapsule() }.padding(start = 30.dp, end = 14.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            GameText(if (goal > 0) "${"%,d".format(shown)} / ${"%,d".format(goal)}" else compactNumber(shown), Type.Label, outline = 2.dp)
        }
        GameIcon(IconKind.CREDIT, Modifier.size(40.dp))
    }
}

/** A currency total in a glass capsule, its icon over the left end; with [onClick], a "+" at the right end. */
@Composable
fun CurrencyPill(icon: IconKind, value: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shown by animateIntAsState(value, tween(700), label = "currency")
    Box(modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .padding(start = 16.dp)
                .widthIn(min = 92.dp)
                .height(30.dp)
                .drawBehind { wellCapsule() }
                .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null) { onClick() } else Modifier)
                .padding(start = 28.dp, end = if (onClick != null) 3.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            GameText(compactNumber(shown), Type.Heading, outline = 2.dp)
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(24.dp).drawBehind {
                    drawCircle(Brush.verticalGradient(listOf(Palette.Green, Palette.GreenDeep)))
                    drawCircle(Palette.Ink, style = Stroke(1.2.dp.toPx()))
                    val a = size.width * 0.27f
                    drawLine(Color.White, Offset(center.x - a, center.y), Offset(center.x + a, center.y), 2.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Color.White, Offset(center.x, center.y - a), Offset(center.x, center.y + a), 2.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                })
            }
        }
        GameIcon(icon, Modifier.size(40.dp))
    }
}

// ---------------------------------------------------------------------------------------------- background

@Composable
fun rememberAnimTime(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    while (true) withFrameNanos { value = (it - start) / 1e9f }
}

/** Animated backdrop: deep steel gradient, a slow-drifting grid, and a cool light from above. */
@Composable
fun GameBackground(modifier: Modifier = Modifier, tint: Color = Palette.BgTop) {
    val time by rememberAnimTime()
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(tint, Palette.BgBottom)))
        drawRect(Brush.radialGradient(listOf(Palette.Rim.copy(alpha = 0.16f), Color.Transparent), Offset(size.width * 0.5f, -size.height * 0.1f), size.maxDimension * 0.6f))
        val step = 40.dp.toPx()
        val shift = (time * 6.dp.toPx()) % step
        var x = -step + shift
        while (x < size.width + step) { drawLine(Color.White.copy(alpha = 0.035f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = -step + shift
        while (y < size.height + step) { drawLine(Color.White.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), center = center, radius = size.maxDimension * 0.75f))
    }
}

// ---------------------------------------------------------------------------------------------- fighters

/**
 * A fighter in 3D. With [pedestal] it's a live, lit 3D stage (drag to spin, tap to cheer, celebrates when
 * [celebrateKey] changes). Without, it shows a pre-rendered 3D portrait (cheap enough for lists).
 */
@Composable
fun FighterView(
    def: FighterDef,
    skin: Int,
    modifier: Modifier = Modifier,
    pedestal: Boolean = true,
    rays: Boolean = false,
    locked: Boolean = false,
    celebrateKey: Int = 0,
) {
    val accent = Color(def.skins[skin.coerceIn(0, def.skins.lastIndex)].secondary)
    Box(modifier) {
        if (rays) {
            val time by rememberAnimTime()
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height * 0.45f)
                rotate(time * 12f, c) {
                    for (i in 0 until 12) {
                        val a0 = i * (2 * PI / 12)
                        val a1 = a0 + 0.16
                        val r = size.maxDimension
                        val p = Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x + (cos(a0) * r).toFloat(), c.y + (sin(a0) * r).toFloat())
                            lineTo(c.x + (cos(a1) * r).toFloat(), c.y + (sin(a1) * r).toFloat())
                            close()
                        }
                        drawPath(p, accent.copy(alpha = 0.10f))
                    }
                }
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), c, size.minDimension * 0.6f), size.minDimension * 0.6f, c)
            }
        }
        if (pedestal) {
            var view by remember { mutableStateOf<FighterStageView?>(null) }
            AndroidView(
                factory = { ctx -> FighterStageView(ctx).also { view = it } },
                update = { v -> v.params.fighter = def.id; v.params.skin = skin; v.params.locked = locked },
                modifier = Modifier.fillMaxSize(),
            )
            val first = remember { booleanArrayOf(true) }
            LaunchedEffect(celebrateKey) {
                if (first[0]) { first[0] = false; return@LaunchedEffect }
                view?.params?.celebrateAt = System.currentTimeMillis()
            }
        } else {
            val images by Portraits.images.collectAsState()
            val bosses by Portraits.bosses.collectAsState()
            // A boss has a portrait of its own. Portraits are always fitted into the space, never stretched to it.
            (def.boss?.let { bosses[it] } ?: if (def.boss == null) images[def.id to skin.coerceIn(0, def.skins.lastIndex)] else null)?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), contentDescription = def.name, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    colorFilter = if (locked) ColorFilter.tint(Color(0xFF1A2F45), BlendMode.SrcIn) else null,
                )
            }
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
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Color(0xE6050E18), cornerRadius = r)
        drawRoundRect(Palette.Rim.copy(alpha = 0.3f), cornerRadius = r, style = Stroke(1.dp.toPx()))
        val inset = 1.5.dp.toPx()
        val inner = Size(size.width - inset * 2, size.height - inset * 2)
        if (f > 0f) {
            val w = (inner.width * f).coerceAtLeast(inner.height)
            drawRoundRect(Brush.horizontalGradient(listOf(fillDeep, fill), inset, inset + w), Offset(inset, inset), Size(w, inner.height), r)
            drawRoundRect(Color.White.copy(alpha = 0.32f), Offset(inset + inner.height * 0.4f, inset + 1f), Size((w - inner.height * 0.8f).coerceAtLeast(0f), inner.height * 0.28f), r)
            // A bright tip where the fill ends.
            if (f < 1f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f), fill.copy(alpha = 0f)), Offset(inset + w - inner.height / 2, size.height / 2), inner.height), inner.height, Offset(inset + w - inner.height / 2, size.height / 2))
        }
    }
}

/** One of the sections a sub-screen's header can jump straight to. */
private data class Section(val icon: IconKind, val label: String, val screen: () -> Screen, val isHere: (Screen) -> Boolean)

private val SECTIONS = listOf(
    Section(IconKind.SHOP, "SHOP", { Screen.Shop }, { it is Screen.Shop }),
    Section(IconKind.FIGHTERS, "FIGHTERS", { Screen.Fighters() }, { it is Screen.Fighters }),
    Section(IconKind.TRACK, "ROAD", { Screen.Road }, { it is Screen.Road }),
    Section(IconKind.CUP, "CUPS", { Screen.CupTrack }, { it is Screen.CupTrack }),
    Section(IconKind.RANK, "RANKS", { Screen.Leaderboard }, { it is Screen.Leaderboard }),
    Section(IconKind.NEWS, "NEWS", { Screen.News }, { it is Screen.News }),
)

/**
 * The section switcher in a sub-screen's header: a glass strip of icons, the current one lit with its name.
 * Shown only on the screens it lists.
 */
@Composable
private fun SectionTabs() {
    val here = LocalScreen.current ?: return
    val go = LocalNav.current ?: return
    if (SECTIONS.none { it.isHere(here) }) return
    val sfx = LocalSfx.current
    val ui = LocalUi.current
    Row(
        Modifier.height(44.dp).drawBehind {
            val r = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
            drawRoundRect(Color(0xCC07121F), cornerRadius = r)
            drawRoundRect(Palette.Hairline, cornerRadius = r, style = Stroke(1.dp.toPx()))
        }.padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in SECTIONS) {
            val on = s.isHere(here)
            val lit by animateFloatAsState(if (on) 1f else 0f, tween(220), label = "tab")
            Row(
                Modifier.height(38.dp)
                    .drawBehind {
                        if (lit > 0f) {
                            val r = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
                            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A79B0), Color(0xFF1C4670))), alpha = lit, cornerRadius = r)
                            drawRoundRect(Palette.Rim.copy(alpha = 0.6f * lit), cornerRadius = r, style = Stroke(1.dp.toPx()))
                            drawRect(Palette.Rim.copy(alpha = lit), Offset(size.width * 0.25f, size.height - 2.5.dp.toPx()), Size(size.width * 0.5f, 2.dp.toPx()))
                        }
                    }
                    .clickable(remember { MutableInteractionSource() }, null) { if (!on) { sfx?.play(Sound.TAP); go(s.screen()) } }
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameIcon(s.icon, Modifier.size(27.dp).graphicsLayer { alpha = 0.6f + 0.4f * lit })
                if (on && ui.wide) {
                    Spacer(Modifier.width(6.dp))
                    GameText(s.label, Type.Label, outline = 2.dp)
                }
            }
        }
    }
}

/**
 * Header row used by every sub-screen: a back key, the title over an accent bar, the section switcher, and the
 * wallet.
 */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, bolts: Int?, prisms: Int?, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp).enterFrom(0, dy = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
        ChunkyButton(onBack, Modifier.size(50.dp, 48.dp), ButtonStyle.GLASS, lip = 4.dp, cut = 10.dp, sound = Sound.UI_BACK) { GameIcon(IconKind.BACK, Modifier.size(24.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            GameText(title, Type.Title, outline = 3.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(44.dp, 3.dp)) {
                    drawRect(Brush.horizontalGradient(listOf(Palette.Orange, Palette.Orange.copy(alpha = 0f))))
                }
                // Whether the server is there: online, or the offline profile (Chaos Mode).
                val status = LocalServer.current?.status?.collectAsState()?.value
                val online = status?.online == true && !LocalOfflineMode.current
                Spacer(Modifier.width(6.dp))
                PlainText(if (online) "● ONLINE" else "● OFFLINE", Type.Eyebrow.copy(letterSpacing = 1.sp), color = if (online) Palette.Positive else Palette.TextDim, maxLines = 1)
            }
        }
        Spacer(Modifier.width(16.dp))
        SectionTabs()
        Spacer(Modifier.width(12.dp))
        extra()
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (bolts != null) CurrencyPill(IconKind.BOLT, bolts)
            if (prisms != null) CurrencyPill(IconKind.PRISM, prisms)
        }
    }
}

/**
 * Slides and fades its content in when it first appears, [delayMs] after the screen does, from [dx], [dy] away.
 * Screens stagger their parts with it so they arrive in order rather than all at once.
 */
@Composable
fun Modifier.enterFrom(delayMs: Int = 0, dx: Dp = 0.dp, dy: Dp = 14.dp): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        a.animateTo(1f, tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val k = 1f - a.value
        translationX = dx.toPx() * k
        translationY = dy.toPx() * k
        alpha = a.value
    }
}

/**
 * Tells the 3D lobby what this screen wants behind it. [heroCenterX] (0..1 of the screen width) is where the
 * fighter should stand; measure it with [Modifier.lobbyAnchor].
 */
@Composable
fun LobbyShotEffect(
    shot: io.github.projectwip.render3d.LobbyShot,
    fighter: io.github.projectwip.data.FighterId? = null,
    skin: Int = 0,
    locked: Boolean = false,
    celebrateKey: Int = 0,
) {
    val lobby = LocalLobby.current
    androidx.compose.runtime.SideEffect {
        lobby.shot = shot
        lobby.showFighter = fighter != null
        if (fighter != null) { lobby.fighter = fighter; lobby.skin = skin; lobby.locked = locked }
    }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(celebrateKey) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        lobby.celebrateAt = System.currentTimeMillis()
    }
}

/** Reports this element's horizontal centre to the lobby so the 3D fighter stands right here. */
@Composable
fun Modifier.lobbyAnchor(): Modifier {
    val lobby = LocalLobby.current
    return this.onGloballyPositioned { c ->
        val root = c.findRootCoordinates().size.width.toFloat().coerceAtLeast(1f)
        val pos = c.positionInRoot().x + c.size.width / 2f
        lobby.fighterScreenX = (pos / root).coerceIn(0.1f, 0.9f)
        val rootH = c.findRootCoordinates().size.height.toFloat().coerceAtLeast(1f)
        lobby.fighterScreenY = ((c.positionInRoot().y + c.size.height / 2f) / rootH).coerceIn(0.25f, 0.75f)
    }
}

/** Translucent wash over the 3D lobby for content-heavy screens. */
val SCRIM = Color(0xB00E1E2E)

/** Darkening at the top, the bottom and the left edge, so UI over the 3D lobby stays readable. */
@Composable
fun LobbyVignette(strength: Float = 1f) {
    Canvas(Modifier.bleed().fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xE6050E18).copy(alpha = 0.85f * strength), Color.Transparent), 0f, size.height * 0.24f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6050E18).copy(alpha = 0.9f * strength)), size.height * 0.66f, size.height))
        drawRect(Brush.horizontalGradient(listOf(Color(0xE6050E18).copy(alpha = 0.7f * strength), Color.Transparent), 0f, size.width * 0.22f))
    }
}

/**
 * Lets a screen's background run under a phone's camera cut-out. The menus are laid out clear of the cut-out
 * ([LocalCutout]); a background that stopped there too would leave a strip of bare lobby down the edge.
 * Goes first in the chain, before `fillMaxSize()`.
 */
@Composable
fun Modifier.bleed(): Modifier {
    val (left, right) = LocalCutout.current
    if (left == 0 && right == 0) return this
    return this.layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + left + right, maxWidth = constraints.maxWidth + left + right))
        layout(p.width - left - right, p.height) { p.place(-left, 0) }
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
