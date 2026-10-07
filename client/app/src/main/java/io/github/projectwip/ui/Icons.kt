package io.github.projectwip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class IconKind { SPARK, CUP, BOLT, PRISM, GEAR, SHOP, FIGHTERS, TRACK, LOCK, CHECK, STAR, BACK, PLAY, GIFT, SWORDS, SKULL, PLUS, CAPSULE, CREDIT, HEART }

/** Original vector icon set. Each icon is drawn in a 0..1 unit square with an ink outline. */
@Composable
fun GameIcon(kind: IconKind, modifier: Modifier = Modifier, tint: Color? = null) {
    // CPU Chips are unstable: their icon glitches wherever it is shown, however small.
    if (kind == IconKind.PRISM) { GlitchIcon(kind, modifier, tint); return }
    Canvas(modifier) {
        withTransform({ scale(size.width, size.height, Offset.Zero) }) {
            drawIconUnit(kind, tint)
        }
    }
}

/** A fixed scramble of [n] into 0..1, so a glitch frame looks the same however often it is redrawn. */
private fun iconScramble(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

/**
 * An icon that glitches. Three ghosts of it, each a single colour that runs round the rainbow, circle just behind
 * it; every so often it tears, and for a few frames the ghosts are thrown wide and the icon itself jumps.
 * The clock is read while drawing, so only the drawing is redone each frame.
 */
@Composable
fun GlitchIcon(kind: IconKind, modifier: Modifier = Modifier, tint: Color? = null) {
    val time = rememberAnimTime()
    val paint = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Paint() }
    Canvas(modifier) {
        val t = time.value
        val frame = (t * 20f).toInt()
        val torn = iconScramble(frame / 4 * 13 + kind.ordinal) < 0.3f
        val reach = if (torn) 0.07f + 0.16f * iconScramble(frame) else 0.035f
        val room = androidx.compose.ui.geometry.Rect(-size.width, -size.height, size.width * 2, size.height * 2)
        for (k in 0 until 3) {
            val a = t * 2.4f + k * 2.094f
            paint.colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.hsv((t * 140f + k * 120f) % 360f, 0.9f, 1f), androidx.compose.ui.graphics.BlendMode.SrcIn)
            paint.alpha = if (torn) 0.9f else 0.65f
            drawContext.canvas.saveLayer(room, paint)
            withTransform({ translate(cos(a) * reach * size.width, sin(a) * reach * size.height * 0.5f); scale(size.width, size.height, Offset.Zero) }) { drawIconUnit(kind, tint) }
            drawContext.canvas.restore()
        }
        val jump = if (torn) (iconScramble(frame + 3) - 0.5f) * 0.12f * size.width else 0f
        withTransform({ translate(jump, 0f); scale(size.width, size.height, Offset.Zero) }) { drawIconUnit(kind, tint) }
    }
}

private val INK = Palette.Ink
private const val W = 0.07f

private fun DrawScope.outline(p: Path, w: Float = W) =
    drawPath(p, INK, style = Stroke(w, join = StrokeJoin.Round, cap = StrokeCap.Round))

private fun poly(vararg pts: Float): Path = Path().apply {
    moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    close()
}

private fun hexagon(cx: Float, cy: Float, r: Float, pointy: Boolean): Path = Path().apply {
    for (i in 0 until 6) {
        val a = (if (pointy) -PI / 2 else 0.0) + i * PI / 3
        val x = cx + (cos(a) * r).toFloat()
        val y = cy + (sin(a) * r).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

fun DrawScope.drawIconUnit(kind: IconKind, tint: Color?) {
    when (kind) {
        IconKind.SPARK -> {
            // Lightning spark inside a glowing ring: the Last Spark mode icon.
            drawCircle(INK, 0.48f, Offset(0.5f, 0.5f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3A0), Palette.Gold, Palette.GoldDeep), Offset(0.45f, 0.4f), 0.55f), 0.41f, Offset(0.5f, 0.5f))
            val bolt = poly(0.58f, 0.12f, 0.3f, 0.55f, 0.48f, 0.55f, 0.4f, 0.9f, 0.72f, 0.42f, 0.53f, 0.42f)
            drawPath(bolt, Color.White); outline(bolt, 0.05f)
        }
        IconKind.CUP -> {
            // Hex badge + chalice + spark: the game's original Cup emblem.
            val hex = hexagon(0.5f, 0.5f, 0.47f, pointy = true)
            drawPath(hex, Brush.verticalGradient(listOf(Palette.Gold, Palette.GoldDeep), 0f, 1f))
            outline(hex)
            val inner = hexagon(0.5f, 0.5f, 0.37f, pointy = true)
            drawPath(inner, Color(0xFFD86A12))
            val bowl = Path().apply {
                moveTo(0.27f, 0.28f); lineTo(0.73f, 0.28f); lineTo(0.68f, 0.45f)
                quadraticTo(0.63f, 0.58f, 0.5f, 0.6f); quadraticTo(0.37f, 0.58f, 0.32f, 0.45f); close()
            }
            drawPath(bowl, Color(0xFFFFF4D6)); outline(bowl, 0.05f)
            val stem = poly(0.46f, 0.59f, 0.54f, 0.59f, 0.56f, 0.68f, 0.44f, 0.68f)
            drawPath(stem, Color(0xFFFFF4D6)); outline(stem, 0.045f)
            val base = poly(0.36f, 0.68f, 0.64f, 0.68f, 0.66f, 0.75f, 0.34f, 0.75f)
            drawPath(base, Color(0xFFFFF4D6)); outline(base, 0.045f)
            val bolt = poly(0.53f, 0.31f, 0.44f, 0.44f, 0.5f, 0.44f, 0.47f, 0.54f, 0.57f, 0.4f, 0.51f, 0.4f)
            drawPath(bolt, Color(0xFF2EE6D6)); outline(bolt, 0.03f)
        }
        IconKind.BOLT -> {
            // An Upgrade Credit: the Credit's card in amber, with an arrow pointing up in its window.
            val round = androidx.compose.ui.geometry.CornerRadius(0.09f)
            drawRoundRect(INK, Offset(0.03f, 0.17f), Size(0.94f, 0.68f), round)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFF1B8), Palette.Gold, Palette.GoldDeep), 0.2f, 0.84f),
                Offset(0.085f, 0.225f), Size(0.83f, 0.57f), androidx.compose.ui.geometry.CornerRadius(0.055f))
            drawRect(Color.White.copy(alpha = 0.35f), Offset(0.085f, 0.225f), Size(0.83f, 0.1f))
            drawRoundRect(INK, Offset(0.13f, 0.28f), Size(0.44f, 0.46f), androidx.compose.ui.geometry.CornerRadius(0.07f))
            drawRoundRect(Color(0xFF7A4A00), Offset(0.165f, 0.315f), Size(0.37f, 0.39f), androidx.compose.ui.geometry.CornerRadius(0.045f))
            drawPath(poly(0.35f, 0.34f, 0.5f, 0.52f, 0.41f, 0.52f, 0.41f, 0.68f, 0.29f, 0.68f, 0.29f, 0.52f, 0.2f, 0.52f), tint ?: Color.White)
            for (y in floatArrayOf(0.34f, 0.56f)) {
                drawRoundRect(INK, Offset(0.62f, y - 0.035f), Size(0.26f, 0.17f), androidx.compose.ui.geometry.CornerRadius(0.04f))
                drawRoundRect(Color(0xFFFFF7DC), Offset(0.65f, y - 0.005f), Size(0.2f, 0.11f), androidx.compose.ui.geometry.CornerRadius(0.025f))
            }
        }
        IconKind.HEART -> {
            // Health.
            val heart = Path().apply {
                moveTo(0.5f, 0.9f)
                cubicTo(0.08f, 0.62f, 0.02f, 0.3f, 0.26f, 0.18f)
                cubicTo(0.4f, 0.12f, 0.48f, 0.22f, 0.5f, 0.3f)
                cubicTo(0.52f, 0.22f, 0.6f, 0.12f, 0.74f, 0.18f)
                cubicTo(0.98f, 0.3f, 0.92f, 0.62f, 0.5f, 0.9f)
                close()
            }
            drawPath(heart, Brush.verticalGradient(listOf(Color(0xFFFF9AA8), Palette.Red, Palette.RedDeep), 0.1f, 0.9f))
            outline(heart)
        }
        IconKind.CREDIT -> {
            // A Credit: a pass card seen from the front. A spark emblem in a window on the left, two tabs on the right.
            val round = androidx.compose.ui.geometry.CornerRadius(0.09f)
            drawRoundRect(INK, Offset(0.03f, 0.17f), Size(0.94f, 0.68f), round)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFC9FFE2), Color(0xFF3FE08A), Color(0xFF17A568)), 0.2f, 0.84f),
                Offset(0.085f, 0.225f), Size(0.83f, 0.57f), androidx.compose.ui.geometry.CornerRadius(0.055f))
            drawRect(Color.White.copy(alpha = 0.35f), Offset(0.085f, 0.225f), Size(0.83f, 0.1f))
            // The window with the emblem.
            drawRoundRect(INK, Offset(0.13f, 0.28f), Size(0.44f, 0.46f), androidx.compose.ui.geometry.CornerRadius(0.07f))
            drawRoundRect(Color(0xFF0E6E49), Offset(0.165f, 0.315f), Size(0.37f, 0.39f), androidx.compose.ui.geometry.CornerRadius(0.045f))
            val spark = poly(0.35f, 0.34f, 0.395f, 0.465f, 0.51f, 0.51f, 0.395f, 0.555f, 0.35f, 0.68f, 0.305f, 0.555f, 0.19f, 0.51f, 0.305f, 0.465f)
            drawPath(spark, Color.White)
            // The tabs.
            for (y in floatArrayOf(0.34f, 0.56f)) {
                drawRoundRect(INK, Offset(0.62f, y - 0.035f), Size(0.26f, 0.17f), androidx.compose.ui.geometry.CornerRadius(0.04f))
                drawRoundRect(Color(0xFFE9FFF3), Offset(0.65f, y - 0.005f), Size(0.2f, 0.11f), androidx.compose.ui.geometry.CornerRadius(0.025f))
            }
        }
        IconKind.PRISM -> {
            // A CPU Chip: a square package with pins down every side and a bright die in the middle.
            for (i in 0 until 4) {
                val p = 0.26f + i * 0.16f
                drawRoundRect(INK, Offset(p - 0.045f, 0.02f), Size(0.09f, 0.96f), androidx.compose.ui.geometry.CornerRadius(0.03f))
                drawRoundRect(INK, Offset(0.02f, p - 0.045f), Size(0.96f, 0.09f), androidx.compose.ui.geometry.CornerRadius(0.03f))
                drawRect(Palette.Gold, Offset(p - 0.02f, 0.05f), Size(0.04f, 0.9f))
                drawRect(Palette.Gold, Offset(0.05f, p - 0.02f), Size(0.9f, 0.04f))
            }
            drawRoundRect(INK, Offset(0.13f, 0.13f), Size(0.74f, 0.74f), androidx.compose.ui.geometry.CornerRadius(0.09f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFFB8FF), Palette.Prism, Palette.PrismDeep), Offset(0.2f, 0.15f), Offset(0.8f, 0.85f)),
                Offset(0.18f, 0.18f), Size(0.64f, 0.64f), androidx.compose.ui.geometry.CornerRadius(0.06f))
            drawRect(Color.White.copy(alpha = 0.3f), Offset(0.18f, 0.18f), Size(0.64f, 0.12f))
            drawRoundRect(INK, Offset(0.33f, 0.33f), Size(0.34f, 0.34f), androidx.compose.ui.geometry.CornerRadius(0.05f))
            drawRoundRect(tint ?: Color.White, Offset(0.375f, 0.375f), Size(0.25f, 0.25f), androidx.compose.ui.geometry.CornerRadius(0.03f))
        }
        IconKind.GEAR -> {
            val c = Offset(0.5f, 0.5f)
            val p = Path()
            for (i in 0 until 16) {
                val a = i * PI / 8
                val r = if (i % 2 == 0) 0.46f else 0.36f
                val a0 = a - PI / 16 * 0.75
                val a1 = a + PI / 16 * 0.75
                val x0 = 0.5f + (cos(a0) * r).toFloat(); val y0 = 0.5f + (sin(a0) * r).toFloat()
                val x1 = 0.5f + (cos(a1) * r).toFloat(); val y1 = 0.5f + (sin(a1) * r).toFloat()
                if (i == 0) p.moveTo(x0, y0) else p.lineTo(x0, y0)
                p.lineTo(x1, y1)
            }
            p.close()
            drawPath(p, tint ?: Color(0xFFE6E0FF)); outline(p)
            drawCircle(INK, 0.15f, c)
            drawCircle(Palette.PanelLight, 0.1f, c)
        }
        IconKind.SHOP -> {
            val body = poly(0.14f, 0.42f, 0.86f, 0.42f, 0.86f, 0.9f, 0.14f, 0.9f)
            drawPath(body, Color(0xFFFFF4D6)); outline(body)
            val door = poly(0.4f, 0.6f, 0.6f, 0.6f, 0.6f, 0.9f, 0.4f, 0.9f)
            drawPath(door, Palette.CyanDeep); outline(door, 0.05f)
            // striped awning
            for (i in 0 until 4) {
                val x = 0.08f + i * 0.21f
                val s = poly(x, 0.18f, x + 0.21f, 0.18f, x + 0.21f, 0.42f, x, 0.42f)
                drawPath(s, if (i % 2 == 0) Palette.OrangeDeep else Color.White)
            }
            val aw = Path().apply {
                moveTo(0.08f, 0.18f); lineTo(0.92f, 0.18f); lineTo(0.92f, 0.42f)
                for (i in 3 downTo 0) { val x = 0.08f + i * 0.21f; quadraticTo(x + 0.105f, 0.54f, x, 0.42f) }
                close()
            }
            outline(aw)
        }
        IconKind.FIGHTERS -> {
            // Helmet with visor
            val helmet = Path().apply { moveTo(0.14f, 0.62f); cubicTo(0.1f, 0.05f, 0.9f, 0.05f, 0.86f, 0.62f); lineTo(0.86f, 0.82f); lineTo(0.14f, 0.82f); close() }
            drawPath(helmet, tint ?: Palette.Orange); outline(helmet)
            val visor = poly(0.22f, 0.45f, 0.78f, 0.45f, 0.72f, 0.64f, 0.28f, 0.64f)
            drawPath(visor, Palette.Cyan); outline(visor, 0.05f)
            drawLine(Color.White, Offset(0.3f, 0.5f), Offset(0.42f, 0.5f), 0.05f, cap = StrokeCap.Round)
        }
        IconKind.TRACK -> {
            val road = Path().apply { moveTo(0.1f, 0.85f); cubicTo(0.3f, 0.5f, 0.6f, 0.9f, 0.85f, 0.3f) }
            drawPath(road, INK, style = Stroke(0.2f, cap = StrokeCap.Round))
            drawPath(road, Palette.Gold, style = Stroke(0.1f, cap = StrokeCap.Round))
            val flag = poly(0.78f, 0.08f, 0.98f, 0.16f, 0.78f, 0.25f)
            drawPath(flag, Palette.Red); outline(flag, 0.04f)
            drawLine(INK, Offset(0.78f, 0.06f), Offset(0.78f, 0.36f), 0.05f, cap = StrokeCap.Round)
        }
        IconKind.LOCK -> {
            drawArc(INK, 180f, 180f, false, Offset(0.28f, 0.14f), Size(0.44f, 0.5f), style = Stroke(0.16f))
            drawArc(Color(0xFFCFC8EA), 180f, 180f, false, Offset(0.28f, 0.14f), Size(0.44f, 0.5f), style = Stroke(0.08f))
            val body = poly(0.2f, 0.42f, 0.8f, 0.42f, 0.8f, 0.9f, 0.2f, 0.9f)
            drawPath(body, Palette.Gold); outline(body)
            drawCircle(INK, 0.07f, Offset(0.5f, 0.62f))
            drawLine(INK, Offset(0.5f, 0.62f), Offset(0.5f, 0.77f), 0.06f, cap = StrokeCap.Round)
        }
        IconKind.CHECK -> {
            drawCircle(INK, 0.48f, Offset(0.5f, 0.5f))
            drawCircle(Palette.GreenDeep, 0.41f, Offset(0.5f, 0.5f))
            val p = Path().apply { moveTo(0.28f, 0.52f); lineTo(0.44f, 0.68f); lineTo(0.74f, 0.34f) }
            drawPath(p, Color.White, style = Stroke(0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        IconKind.STAR -> {
            val p = Path()
            for (i in 0 until 10) {
                val a = -PI / 2 + i * PI / 5
                val r = if (i % 2 == 0) 0.47f else 0.2f
                val x = 0.5f + (cos(a) * r).toFloat(); val y = 0.53f + (sin(a) * r).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, tint ?: Palette.Gold); outline(p)
        }
        IconKind.BACK -> {
            val p = poly(0.15f, 0.5f, 0.52f, 0.14f, 0.52f, 0.34f, 0.86f, 0.34f, 0.86f, 0.66f, 0.52f, 0.66f, 0.52f, 0.86f)
            drawPath(p, Color.White); outline(p)
        }
        IconKind.PLAY -> {
            val p = poly(0.24f, 0.12f, 0.88f, 0.5f, 0.24f, 0.88f)
            drawPath(p, Color.White); outline(p)
        }
        IconKind.GIFT -> {
            // A loot crate thrown open, with a Prism, a Bolt and a spark jumping out of it.
            drawCircle(Brush.radialGradient(listOf(Palette.Gold.copy(alpha = 0.55f), Color.Transparent), Offset(0.5f, 0.4f), 0.5f), 0.5f, Offset(0.5f, 0.4f))
            val lid = poly(0.2f, 0.52f, 0.27f, 0.2f, 0.73f, 0.2f, 0.8f, 0.52f)
            drawPath(lid, Brush.verticalGradient(listOf(Color(0xFF8E3F12), Color(0xFF5A2408)), 0.2f, 0.52f)); outline(lid)
            val spark = Path()
            for (i in 0 until 8) {
                val a = -PI / 2 + i * PI / 4
                val r = if (i % 2 == 0) 0.13f else 0.05f
                val x = 0.5f + (cos(a) * r).toFloat(); val y = 0.2f + (sin(a) * r).toFloat()
                if (i == 0) spark.moveTo(x, y) else spark.lineTo(x, y)
            }
            spark.close()
            drawPath(spark, Color(0xFFFFF3A0)); outline(spark, 0.035f)
            val nut = hexagon(0.34f, 0.38f, 0.13f, pointy = false)
            drawPath(nut, Brush.verticalGradient(listOf(Color(0xFFDDF7FF), Palette.Bolt, Palette.BoltDeep), 0.25f, 0.51f)); outline(nut, 0.04f)
            drawCircle(INK, 0.05f, Offset(0.34f, 0.38f))
            val gem = poly(0.66f, 0.2f, 0.79f, 0.33f, 0.66f, 0.54f, 0.53f, 0.33f)
            drawPath(gem, Brush.linearGradient(listOf(Color(0xFFFFB8FF), Palette.Prism, Palette.PrismDeep), Offset(0.55f, 0.2f), Offset(0.77f, 0.52f))); outline(gem, 0.04f)
            val body = poly(0.16f, 0.52f, 0.84f, 0.52f, 0.8f, 0.92f, 0.2f, 0.92f)
            drawPath(body, Brush.verticalGradient(listOf(Palette.Orange, Palette.OrangeDeep), 0.52f, 0.92f)); outline(body)
            for (x in listOf(0.27f, 0.67f)) {
                val band = poly(x, 0.52f, x + 0.06f, 0.52f, x + 0.06f, 0.92f, x, 0.92f)
                drawPath(band, Color(0xFF3A2F6B)); outline(band, 0.03f)
            }
            val rim = poly(0.11f, 0.47f, 0.89f, 0.47f, 0.89f, 0.58f, 0.11f, 0.58f)
            drawPath(rim, Brush.verticalGradient(listOf(Color(0xFFFFC56B), Palette.Orange), 0.47f, 0.58f)); outline(rim, 0.05f)
            val plate = poly(0.43f, 0.6f, 0.57f, 0.6f, 0.57f, 0.76f, 0.43f, 0.76f)
            drawPath(plate, Palette.Gold); outline(plate, 0.04f)
            drawCircle(INK, 0.028f, Offset(0.5f, 0.66f))
            drawLine(INK, Offset(0.5f, 0.66f), Offset(0.5f, 0.72f), 0.025f, cap = StrokeCap.Round)
        }
        IconKind.SWORDS -> {
            for (flip in listOf(false, true)) {
                withTransform({ if (flip) scale(-1f, 1f, Offset(0.5f, 0.5f)) }) {
                    drawLine(INK, Offset(0.18f, 0.18f), Offset(0.72f, 0.72f), 0.17f, cap = StrokeCap.Round)
                    drawLine(Color(0xFFE8F4FF), Offset(0.18f, 0.18f), Offset(0.72f, 0.72f), 0.09f, cap = StrokeCap.Round)
                    drawLine(INK, Offset(0.58f, 0.82f), Offset(0.82f, 0.58f), 0.14f, cap = StrokeCap.Round)
                    drawLine(Palette.Orange, Offset(0.58f, 0.82f), Offset(0.82f, 0.58f), 0.07f, cap = StrokeCap.Round)
                }
            }
        }
        IconKind.SKULL -> {
            val head = Path().apply { moveTo(0.18f, 0.55f); cubicTo(0.1f, 0.05f, 0.9f, 0.05f, 0.82f, 0.55f); lineTo(0.7f, 0.68f); lineTo(0.7f, 0.85f); lineTo(0.3f, 0.85f); lineTo(0.3f, 0.68f); close() }
            drawPath(head, Color.White); outline(head)
            drawCircle(INK, 0.1f, Offset(0.36f, 0.48f)); drawCircle(INK, 0.1f, Offset(0.64f, 0.48f))
        }
        IconKind.CAPSULE -> drawCapsuleUnit(tint ?: Palette.Cyan)
        IconKind.PLUS -> {
            drawLine(INK, Offset(0.5f, 0.15f), Offset(0.5f, 0.85f), 0.3f, cap = StrokeCap.Round)
            drawLine(INK, Offset(0.15f, 0.5f), Offset(0.85f, 0.5f), 0.3f, cap = StrokeCap.Round)
            drawLine(tint ?: Palette.Green, Offset(0.5f, 0.15f), Offset(0.5f, 0.85f), 0.17f, cap = StrokeCap.Round)
            drawLine(tint ?: Palette.Green, Offset(0.15f, 0.5f), Offset(0.85f, 0.5f), 0.17f, cap = StrokeCap.Round)
        }
    }
}

/**
 * A Glitch Drop in a unit square: a plump star in [color] with a glowing core. [glow] (0..1) adds light around it.
 */
fun DrawScope.drawCapsuleUnit(color: Color, @Suppress("UNUSED_PARAMETER") split: Float = 0f, glow: Float = 0f) {
    val c = Offset(0.5f, 0.54f)
    if (glow > 0f) {
        val g = glow.coerceAtMost(1f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * g), color.copy(alpha = 0.55f * g), Color.Transparent), c, 0.62f), 0.62f, c)
    }
    val star = Path()
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val r = if (i % 2 == 0) 0.44f else 0.24f
        val x = c.x + (cos(a) * r).toFloat(); val y = c.y + (sin(a) * r).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    // A fat round-joined stroke under the fill plumps the points up.
    drawPath(star, INK, style = Stroke(0.2f, join = StrokeJoin.Round))
    drawPath(star, lerp(color, INK, 0.25f), style = Stroke(0.1f, join = StrokeJoin.Round))
    drawPath(star, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.6f), color, lerp(color, INK, 0.25f)), 0.1f, 0.95f))
    drawLine(Color.White.copy(alpha = 0.7f), Offset(0.36f, 0.42f), Offset(0.45f, 0.3f), 0.05f, cap = StrokeCap.Round)
    drawCircle(INK, 0.115f, c)
    drawCircle(Brush.radialGradient(listOf(Color.White, lerp(color, Color.White, 0.3f), color), c, 0.09f), 0.09f, c)
    fun at(u: Float, o: Float) = o + (u - 0.5f) * 0.19f
    val bolt = poly(at(0.58f, c.x), at(0.12f, c.y), at(0.3f, c.x), at(0.55f, c.y), at(0.48f, c.x), at(0.55f, c.y), at(0.4f, c.x), at(0.9f, c.y), at(0.72f, c.x), at(0.42f, c.y), at(0.53f, c.x), at(0.42f, c.y))
    drawPath(bolt, INK)
}
