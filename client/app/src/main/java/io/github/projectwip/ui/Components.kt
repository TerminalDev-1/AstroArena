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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    /** Translucent plate that lets the 3D lobby show through. */
    GLASS(Color(0xD834628C), Color(0xE0173550), Color(0xFF0B1A28)),
}

/** Soft drop shadow under a plate, so it reads as sitting above the scene. */
private fun DrawScope.plateShadow(path: Path, depth: Float) {
    translate(top = depth) {
        drawPath(path, Color.Black.copy(alpha = 0.2f), style = Stroke(depth * 1.8f, join = StrokeJoin.Round))
        drawPath(path, Color.Black.copy(alpha = 0.42f))
    }
}

/**
 * A plate with depth: gradient body, a curved gloss on the upper half, shade gathering at the bottom,
 * and a bevel (lit top edge, dark bottom edge) just inside the ink outline.
 */
private fun DrawScope.plate(shape: Shape, size: Size, brush: Brush, ink: Color, inkWidth: Float, gloss: Boolean) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawPath(path, brush)
    clipPath(path) {
        if (gloss) {
            drawRect(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.46f), 0.46f to Color.White.copy(alpha = 0.1f), 0.5f to Color.Transparent, startY = 0f, endY = size.height),
                size = size,
            )
        }
        drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f), startY = 0f, endY = size.height), size = size)
        // Bevel: the stroke is centred on the edge, so the clip leaves only its inner half.
        drawPath(
            path,
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.6f), 0.5f to Color.White.copy(alpha = 0.06f), 1f to Color.Black.copy(alpha = 0.4f), startY = 0f, endY = size.height),
            style = Stroke(inkWidth * 3.2f, join = StrokeJoin.Round),
        )
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
    Box(
        modifier
            .graphicsLayer { val k = 1f - 0.03f * press; scaleX = k; scaleY = k }
            .clickable(interaction, indication = null) {
                if (enabled) { sfx?.play(sound); onClick() } else sfx?.play(Sound.DENIED, 0.6f)
            }
            .drawBehind {
                val lipPx = lip.toPx()
                val ink = 2.5.dp.toPx()
                val pressPx = lipPx * press
                val whole = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                plateShadow(whole, lipPx * (1.1f - 0.6f * press))
                drawPath(whole, Brush.verticalGradient(listOf(s.lip, lerp(s.lip, Color.Black, 0.35f))))
                drawPath(whole, Palette.Ink, style = Stroke(ink, join = StrokeJoin.Round))
                translate(top = pressPx) {
                    val face = Size(size.width, size.height - lipPx)
                    plate(shape, face, Brush.verticalGradient(listOf(lerp(s.top, Color.White, 0.18f), s.top, s.bottom)), Palette.Ink, ink, gloss = true)
                    if (time != null) {
                        // A band of light sweeps across every few seconds.
                        val t = (time.value % 3.2f) / 0.7f
                        if (t < 1f) clipPath(Path().apply { addOutline(shape.createOutline(face, layoutDirection, this@drawBehind)) }) {
                            val x = -face.height + (face.width + face.height * 2) * t
                            val band = Path().apply {
                                moveTo(x, face.height); lineTo(x + face.height * 0.7f, 0f); lineTo(x + face.height * 1.15f, 0f); lineTo(x + face.height * 0.45f, face.height); close()
                            }
                            drawPath(band, Color.White.copy(alpha = 0.3f))
                        }
                    }
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
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            // A panel is a slab: its side shows under the face, and it throws a shadow past that.
            val slab = 5.dp.toPx()
            plateShadow(path, slab + 6.dp.toPx())
            translate(top = slab) {
                drawPath(path, Brush.verticalGradient(listOf(lerp(colorBottom, Color.Black, 0.25f), lerp(colorBottom, Color.Black, 0.6f))))
                drawPath(path, Palette.Ink, style = Stroke(ink, join = StrokeJoin.Round))
            }
            plate(shape, size, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.26f), color, colorBottom)), Palette.Ink, ink, gloss = false)
            clipPath(path) {
                // Faint diagonal brushing, and a sheen across the top, so large panels aren't a flat fill.
                val step = 22.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.035f), Offset(x, size.height), Offset(x + size.height, 0f), step * 0.45f)
                    x += step
                }
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent), 0f, minOf(size.height * 0.4f, 110.dp.toPx())), size = size)
                // A warm light catches the top edge.
                drawLine(Palette.Orange.copy(alpha = 0.55f), Offset(0f, ink * 1.4f), Offset(size.width, ink * 1.4f), 2.dp.toPx())
            }
            // inner rim highlight
            val inset = 5.dp.toPx()
            translate(inset, inset) {
                val inner = Size(size.width - inset * 2, size.height - inset * 2)
                val o = plateShape(cut - 2.dp, (cut * 0.35f - 1.dp).coerceAtLeast(1.dp)).createOutline(inner, layoutDirection, this)
                drawPath(Path().apply { addOutline(o) }, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.03f))), style = Stroke(1.5.dp.toPx()))
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
                plateShadow(p, 2.5.dp.toPx())
                drawPath(p, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.3f), color, lerp(color, Color.Black, 0.25f))))
                clipPath(p) { drawRect(Color.White.copy(alpha = 0.25f), size = Size(size.width, size.height * 0.42f)) }
                drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { GameText(text, Type.Label, color = textColor, outline = 2.dp) }
}

// ---------------------------------------------------------------------------------------------- currency

/** A wallet total, short enough to fit its counter whatever it grows to: 98,765 · 123.4K · 60.3M · 1.2B. */
fun compactNumber(n: Int): String = when {
    n < 100_000 -> "%,d".format(n)
    n < 1_000_000 -> "%.1fK".format(Math.floor(n / 100.0) / 10)
    n < 1_000_000_000 -> "%.1fM".format(Math.floor(n / 100_000.0) / 10)
    else -> "%.1fB".format(Math.floor(n / 100_000_000.0) / 10)
}

/**
 * Where Credits go. They are not held in a wallet: this shows how far along the Spark Road the fighter being
 * unlocked is ([value] of [goal]).
 */
@Composable
fun RoadMeter(value: Int, goal: Int, modifier: Modifier = Modifier) {
    val shown by animateIntAsState(value, tween(500), label = "road")
    val shape = plateShape(8.dp, 3.dp)
    Box(modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.padding(start = 16.dp).widthIn(min = 96.dp).height(32.dp)
                .background(Palette.PanelInset, shape).border(2.5.dp, Palette.Ink, shape).padding(start = 30.dp, end = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            GameText(if (goal > 0) "${"%,d".format(shown)} / ${"%,d".format(goal)}" else compactNumber(shown), Type.Label, outline = 2.dp)
        }
        GameIcon(IconKind.CREDIT, Modifier.size(40.dp))
    }
}

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
                    plateShadow(p, 3.dp.toPx())
                    drawPath(p, Brush.verticalGradient(listOf(Color(0xFF091928), Palette.PanelInset, Color(0xFF23476A))))
                    clipPath(p) {
                        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), 0f, size.height * 0.4f), size = size)
                        drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, size.height - 2.dp.toPx()), Offset(size.width, size.height - 2.dp.toPx()), 2.dp.toPx())
                    }
                    drawPath(p, Palette.Ink, style = Stroke(2.5.dp.toPx()))
                }
                .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null) { onClick() } else Modifier)
                .padding(start = 28.dp, end = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) { GameText(compactNumber(shown), Type.Heading, outline = 2.5.dp) }
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
        ChunkyButton(onBack, Modifier.size(52.dp, 50.dp), ButtonStyle.PURPLE, sound = Sound.UI_BACK) { GameIcon(IconKind.BACK, Modifier.size(26.dp)) }
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

/** Top/bottom darkening so UI over the 3D lobby stays readable. */
@Composable
fun LobbyVignette(strength: Float = 1f) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xCC091520).copy(alpha = 0.8f * strength), Color.Transparent), 0f, size.height * 0.22f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC091520).copy(alpha = 0.85f * strength)), size.height * 0.7f, size.height))
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
