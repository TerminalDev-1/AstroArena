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

enum class IconKind { SPARK, CUP, BOLT, PRISM, GEAR, SHOP, FIGHTERS, TRACK, LOCK, CHECK, STAR, BACK, PLAY, GIFT, SWORDS, SKULL, PLUS, CAPSULE }

/** Original vector icon set. Each icon is drawn in a 0..1 unit square with an ink outline. */
@Composable
fun GameIcon(kind: IconKind, modifier: Modifier = Modifier, tint: Color? = null) {
    Canvas(modifier) {
        withTransform({ scale(size.width, size.height, Offset.Zero) }) {
            drawIconUnit(kind, tint)
        }
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
            // A hex nut — the soft currency.
            val hex = hexagon(0.5f, 0.52f, 0.42f, pointy = false)
            drawPath(hex, Brush.verticalGradient(listOf(Color(0xFFDDF7FF), Palette.Bolt, Palette.BoltDeep), 0.1f, 0.95f))
            outline(hex)
            drawCircle(Palette.Ink, 0.17f, Offset(0.5f, 0.52f))
            drawCircle(Color(0xFF2B5D8A), 0.12f, Offset(0.5f, 0.52f))
            drawArc(Color.White.copy(alpha = 0.7f), 200f, 70f, false, Offset(0.25f, 0.27f), Size(0.5f, 0.5f), style = Stroke(0.05f, cap = StrokeCap.Round))
        }
        IconKind.PRISM -> {
            val outer = poly(0.5f, 0.06f, 0.88f, 0.4f, 0.5f, 0.95f, 0.12f, 0.4f)
            drawPath(outer, Brush.linearGradient(listOf(Color(0xFFFFB8FF), Palette.Prism, Palette.PrismDeep), Offset(0.2f, 0.1f), Offset(0.8f, 0.9f)))
            val facetL = poly(0.5f, 0.06f, 0.36f, 0.4f, 0.5f, 0.95f, 0.12f, 0.4f)
            drawPath(facetL, Color.White.copy(alpha = 0.25f))
            val facetTop = poly(0.36f, 0.4f, 0.5f, 0.06f, 0.64f, 0.4f)
            drawPath(facetTop, Color.White.copy(alpha = 0.35f))
            drawLine(INK.copy(alpha = 0.5f), Offset(0.12f, 0.4f), Offset(0.88f, 0.4f), 0.03f)
            outline(outer)
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
            val box = poly(0.14f, 0.42f, 0.86f, 0.42f, 0.86f, 0.9f, 0.14f, 0.9f)
            drawPath(box, Palette.Prism); outline(box)
            val lid = poly(0.08f, 0.28f, 0.92f, 0.28f, 0.92f, 0.44f, 0.08f, 0.44f)
            drawPath(lid, Color(0xFFFF8BFF)); outline(lid)
            drawRect(Palette.Gold, Offset(0.43f, 0.28f), Size(0.14f, 0.62f))
            drawLine(INK, Offset(0.43f, 0.28f), Offset(0.43f, 0.9f), 0.04f); drawLine(INK, Offset(0.57f, 0.28f), Offset(0.57f, 0.9f), 0.04f)
            val bowL = Path().apply { moveTo(0.5f, 0.28f); cubicTo(0.2f, 0.0f, 0.15f, 0.3f, 0.5f, 0.28f) }
            val bowR = Path().apply { moveTo(0.5f, 0.28f); cubicTo(0.8f, 0.0f, 0.85f, 0.3f, 0.5f, 0.28f) }
            drawPath(bowL, Palette.Gold); outline(bowL, 0.05f)
            drawPath(bowR, Palette.Gold); outline(bowR, 0.05f)
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
 * A Spark Capsule in a unit square: a coloured shell over a dark base, joined by a collar with a glowing core.
 * [split] (0..1) pulls the halves apart as it opens; [glow] (0..1) is how much light leaks out around it.
 */
fun DrawScope.drawCapsuleUnit(color: Color, split: Float = 0f, glow: Float = 0f) {
    val c = Offset(0.5f, 0.5f)
    val gap = split * 0.17f
    if (glow > 0f) {
        val g = glow.coerceAtMost(1f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * g), color.copy(alpha = 0.55f * g), Color.Transparent), c, 0.62f), 0.62f, c)
    }
    if (split > 0f) drawCircle(Brush.radialGradient(listOf(Color.White, color, Color.Transparent), c, 0.2f + split * 0.35f), 0.2f + split * 0.35f, c)

    translate(top = gap) {
        val base = Path().apply { moveTo(0.24f, 0.52f); lineTo(0.24f, 0.66f); cubicTo(0.24f, 1.0f, 0.76f, 1.0f, 0.76f, 0.66f); lineTo(0.76f, 0.52f); close() }
        drawPath(base, Brush.verticalGradient(listOf(Color(0xFF5444B0), Color(0xFF231650)), 0.52f, 0.95f)); outline(base)
        val collar = poly(0.19f, 0.51f, 0.81f, 0.51f, 0.81f, 0.6f, 0.19f, 0.6f)
        drawPath(collar, Color(0xFFB9ADEB)); outline(collar, 0.05f)
        drawCircle(INK, 0.02f, Offset(0.28f, 0.555f)); drawCircle(INK, 0.02f, Offset(0.72f, 0.555f))
    }
    translate(top = -gap) {
        val shell = Path().apply { moveTo(0.24f, 0.48f); lineTo(0.24f, 0.34f); cubicTo(0.24f, 0.0f, 0.76f, 0.0f, 0.76f, 0.34f); lineTo(0.76f, 0.48f); close() }
        drawPath(shell, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.6f), color, lerp(color, INK, 0.25f)), 0.06f, 0.5f)); outline(shell)
        drawLine(Color.White.copy(alpha = 0.6f), Offset(0.335f, 0.36f), Offset(0.345f, 0.24f), 0.05f, cap = StrokeCap.Round)
        val collar = poly(0.19f, 0.4f, 0.81f, 0.4f, 0.81f, 0.49f, 0.19f, 0.49f)
        drawPath(collar, Color(0xFFEDE8FF)); outline(collar, 0.05f)
        drawCircle(INK, 0.02f, Offset(0.28f, 0.445f)); drawCircle(INK, 0.02f, Offset(0.72f, 0.445f))
    }
    if (split < 0.5f) {
        drawCircle(INK, 0.13f, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, lerp(color, Color.White, 0.3f), color), c, 0.1f), 0.1f, c)
        fun at(u: Float) = 0.5f + (u - 0.5f) * 0.21f
        val bolt = poly(at(0.58f), at(0.12f), at(0.3f), at(0.55f), at(0.48f), at(0.55f), at(0.4f), at(0.9f), at(0.72f), at(0.42f), at(0.53f), at(0.42f))
        drawPath(bolt, INK)
    }
}
