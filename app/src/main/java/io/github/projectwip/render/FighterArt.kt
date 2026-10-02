package io.github.projectwip.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.projectwip.data.FighterDef
import io.github.projectwip.data.FighterId
import kotlin.math.cos
import kotlin.math.sin

/**
 * Programmatic, original character art. Drawn in a local unit space (≈ -1..1) and scaled by `size`,
 * so the same code renders a 40px fighter in the arena and a 400px hero on the upgrade screen.
 *
 * Not thread-safe (reuses Paint/Path objects): give each render thread its own instance.
 */
class FighterArt {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = OUTLINE
    }
    private val path = Path()
    private val rect = RectF()

    /**
     * @param facing radians; the character mirrors to face left/right and points its weapon along it.
     * @param walk   walk cycle phase (radians); 0 = standing.
     * @param time   seconds, for idle animation.
     * @param flash  0..1 white hit flash.
     */
    fun draw(
        c: Canvas, cx: Float, cy: Float, size: Float, def: FighterDef, skinIndex: Int,
        facing: Float, walk: Float, time: Float, flash: Float = 0f, alpha: Int = 255,
    ) {
        val skin = def.skins[skinIndex.coerceIn(0, def.skins.lastIndex)]
        val primary = skin.primary.toInt()
        val secondary = skin.secondary.toInt()
        val accent = skin.accent.toInt()
        val flip = if (cos(facing) < 0f) -1f else 1f
        val bob = if (walk != 0f) kotlin.math.abs(sin(walk)) * 0.07f else sin(time * 2.2f) * 0.025f

        fill.alpha = 255
        stroke.strokeWidth = 0.085f
        c.save()
        c.translate(cx, cy)
        c.scale(size, size)
        if (alpha < 255) c.saveLayerAlpha(-2f, -2.5f, 2f, 1.6f, alpha)

        // Shadow
        fill.color = Color.argb(70, 0, 0, 0)
        rect.set(-0.75f, 0.68f, 0.75f, 1.0f)
        c.drawOval(rect, fill)

        c.translate(0f, -bob)
        when (def.id) {
            FighterId.JUNO -> drawJuno(c, flip, facing, walk, time, primary, secondary, accent)
            FighterId.BRAKK -> drawBrakk(c, flip, facing, walk, time, primary, secondary, accent)
            FighterId.MIRA -> drawMira(c, flip, facing, walk, time, primary, secondary, accent)
        }
        if (flash > 0f) {
            // Cheap hit flash: white glow over the silhouette area.
            fill.color = Color.argb((flash * 170).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawCircle(0f, -0.25f, 0.95f, fill)
        }
        if (alpha < 255) c.restore()
        c.restore()
    }

    // ---------------------------------------------------------------- Juno: courier with coil blaster

    private fun drawJuno(c: Canvas, flip: Float, facing: Float, walk: Float, t: Float, primary: Int, secondary: Int, accent: Int) {
        legs(c, walk, 0.28f, 0.62f, darken(primary, 0.45f))
        // Backpack
        shape(c, darken(secondary, 0.7f)) { rect.set(-0.62f * flip - 0.18f, -0.25f, -0.62f * flip + 0.18f, 0.35f); path.addRoundRect(rect, 0.12f, 0.12f, Path.Direction.CW) }
        // Torso / jacket
        shape(c, primary) { rect.set(-0.58f, -0.3f, 0.58f, 0.66f); path.addRoundRect(rect, 0.38f, 0.38f, Path.Direction.CW) }
        // Jacket stripe
        fill.color = secondary
        c.drawRect(-0.56f, 0.18f, 0.56f, 0.32f, fill)
        // Weapon behind/in front depending on facing
        weaponArm(c, facing, 0.95f, 0.13f, darken(secondary, 0.6f), accent, coils = true)
        // Head
        shape(c, SKIN) { path.addCircle(0f, -0.72f, 0.56f, Path.Direction.CW) }
        // Hair tuft + helmet
        shape(c, darken(primary, 0.55f)) {
            path.moveTo(-0.56f, -0.72f)
            path.cubicTo(-0.6f, -1.42f, 0.6f, -1.42f, 0.56f, -0.72f)
            path.lineTo(0.4f, -0.86f); path.lineTo(-0.4f, -0.86f); path.close()
        }
        // Visor
        shape(c, secondary) { rect.set(-0.5f, -0.86f, 0.5f, -0.6f); path.addRoundRect(rect, 0.13f, 0.13f, Path.Direction.CW) }
        fill.color = Color.WHITE
        c.drawCircle(0.18f * flip, -0.75f, 0.06f, fill)
        c.drawCircle(0.36f * flip, -0.75f, 0.045f, fill)
        // Antenna with spark
        stroke.strokeWidth = 0.07f
        c.drawLine(-0.25f * flip, -1.22f, -0.38f * flip, -1.55f, stroke)
        val spark = 0.1f + 0.03f * sin(t * 9f)
        fill.color = accent
        c.drawCircle(-0.38f * flip, -1.58f, spark, fill)
        stroke.strokeWidth = 0.05f
        c.drawCircle(-0.38f * flip, -1.58f, spark, stroke)
        stroke.strokeWidth = 0.085f
        // Smile
        stroke.strokeWidth = 0.05f
        rect.set(0.02f * flip - 0.14f, -0.6f, 0.02f * flip + 0.24f, -0.38f)
        c.drawArc(rect, 20f, 140f, false, stroke)
        stroke.strokeWidth = 0.085f
    }

    // ---------------------------------------------------------------- Brakk: scrap-built bruiser

    private fun drawBrakk(c: Canvas, flip: Float, facing: Float, walk: Float, t: Float, primary: Int, secondary: Int, accent: Int) {
        legs(c, walk, 0.38f, 0.66f, darken(primary, 0.4f), wide = true)
        // Exhaust pipes
        shape(c, darken(primary, 0.5f)) {
            rect.set(-0.75f * flip - 0.12f, -1.05f, -0.75f * flip + 0.12f, -0.2f)
            path.addRoundRect(rect, 0.06f, 0.06f, Path.Direction.CW)
        }
        val puff = (t * 1.3f) % 1f
        fill.color = Color.argb((120 * (1 - puff)).toInt(), 200, 200, 210)
        c.drawCircle(-0.75f * flip - 0.1f * flip * puff, -1.15f - puff * 0.5f, 0.12f + puff * 0.18f, fill)
        // Body block
        shape(c, primary) { rect.set(-0.82f, -0.75f, 0.82f, 0.7f); path.addRoundRect(rect, 0.28f, 0.28f, Path.Direction.CW) }
        // Chest plate
        shape(c, secondary) { rect.set(-0.48f, -0.2f, 0.48f, 0.46f); path.addRoundRect(rect, 0.12f, 0.12f, Path.Direction.CW) }
        // Hazard stripes on plate
        fill.color = darken(secondary, 0.6f)
        for (i in 0..2) {
            val x = -0.36f + i * 0.3f
            path.reset(); path.moveTo(x, 0.4f); path.lineTo(x + 0.12f, 0.4f); path.lineTo(x + 0.26f, -0.14f); path.lineTo(x + 0.14f, -0.14f); path.close()
            c.drawPath(path, fill)
        }
        // Rivets
        fill.color = accent
        for ((rx, ry) in listOf(-0.66f to -0.58f, 0.66f to -0.58f, -0.66f to 0.52f, 0.66f to 0.52f)) c.drawCircle(rx, ry, 0.065f, fill)
        // Head dome with single eye
        shape(c, darken(primary, 0.8f)) {
            rect.set(-0.42f, -1.22f, 0.42f, -0.55f)
            path.addRoundRect(rect, 0.3f, 0.3f, Path.Direction.CW)
        }
        val blink = if ((t % 3.1f) < 0.12f) 0.3f else 1f
        fill.color = accent
        rect.set(0.12f * flip - 0.17f, -0.98f - 0.1f * blink, 0.12f * flip + 0.17f, -0.98f + 0.1f * blink)
        c.drawOval(rect, fill)
        fill.color = Color.WHITE
        c.drawCircle(0.16f * flip, -1.0f, 0.04f, fill)
        // Cannon
        weaponArm(c, facing, 1.05f, 0.24f, darken(primary, 0.55f), secondary, coils = false, muzzle = 0.3f)
    }

    // ---------------------------------------------------------------- Mira: prism sniper

    private fun drawMira(c: Canvas, flip: Float, facing: Float, walk: Float, t: Float, primary: Int, secondary: Int, accent: Int) {
        legs(c, walk, 0.2f, 0.6f, darken(primary, 0.35f))
        // Cloak
        shape(c, primary) {
            path.moveTo(0f, -0.75f)
            path.cubicTo(0.75f, -0.5f, 0.78f, 0.35f, 0.62f, 0.68f)
            path.lineTo(-0.62f, 0.68f)
            path.cubicTo(-0.78f, 0.35f, -0.75f, -0.5f, 0f, -0.75f)
            path.close()
        }
        // Cloak trim
        stroke.color = secondary
        stroke.strokeWidth = 0.07f
        c.drawLine(-0.6f, 0.58f, 0.6f, 0.58f, stroke)
        stroke.color = OUTLINE
        stroke.strokeWidth = 0.085f
        // Rifle
        weaponArm(c, facing, 1.3f, 0.1f, darken(primary, 0.4f), secondary, coils = false, crystalTip = true)
        // Head + hood
        shape(c, SKIN) { path.addCircle(0f, -0.78f, 0.5f, Path.Direction.CW) }
        shape(c, darken(primary, 0.7f)) {
            path.moveTo(-0.55f, -0.62f)
            path.cubicTo(-0.65f, -1.45f, 0.65f, -1.45f, 0.55f, -0.62f)
            path.cubicTo(0.35f, -1.0f, -0.35f, -1.0f, -0.55f, -0.62f)
            path.close()
        }
        // Eyes
        fill.color = OUTLINE
        c.drawCircle(0.08f * flip, -0.74f, 0.065f, fill)
        c.drawCircle(0.3f * flip, -0.74f, 0.065f, fill)
        fill.color = Color.WHITE
        c.drawCircle(0.1f * flip, -0.76f, 0.025f, fill)
        c.drawCircle(0.32f * flip, -0.76f, 0.025f, fill)
        // Floating prism
        val fy = -1.62f + sin(t * 2.6f) * 0.08f
        val rot = t * 1.4f
        shape(c, secondary) {
            path.moveTo(0f, fy - 0.26f)
            path.lineTo(0.18f * cos(rot), fy)
            path.lineTo(0f, fy + 0.26f)
            path.lineTo(-0.18f * cos(rot), fy)
            path.close()
        }
        fill.color = accent
        c.drawCircle(0.04f * cos(rot), fy - 0.06f, 0.05f, fill)
    }

    // ---------------------------------------------------------------- shared parts

    private fun legs(c: Canvas, walk: Float, spread: Float, y: Float, color: Int, wide: Boolean = false) {
        val step = if (walk != 0f) sin(walk) * 0.12f else 0f
        val w = if (wide) 0.26f else 0.2f
        for (side in listOf(-1f, 1f)) {
            val dy = step * side
            shape(c, color) { rect.set(side * spread - w, y - 0.22f + dy, side * spread + w, y + 0.12f + dy); path.addRoundRect(rect, 0.12f, 0.12f, Path.Direction.CW) }
        }
    }

    private fun weaponArm(
        c: Canvas, facing: Float, length: Float, thick: Float, body: Int, trim: Int,
        coils: Boolean, muzzle: Float = 0f, crystalTip: Boolean = false,
    ) {
        c.save()
        c.translate(0f, 0.05f)
        c.rotate(Math.toDegrees(facing.toDouble()).toFloat())
        shape(c, body) {
            rect.set(0.1f, -thick, length, thick)
            path.addRoundRect(rect, thick * 0.8f, thick * 0.8f, Path.Direction.CW)
        }
        if (coils) {
            fill.color = trim
            for (i in 0..2) c.drawRect(0.42f + i * 0.17f, -thick - 0.05f, 0.5f + i * 0.17f, thick + 0.05f, fill)
        }
        if (muzzle > 0f) shape(c, trim) { rect.set(length - 0.18f, -muzzle, length + 0.08f, muzzle); path.addRoundRect(rect, 0.08f, 0.08f, Path.Direction.CW) }
        if (crystalTip) shape(c, trim) {
            path.moveTo(length + 0.28f, 0f); path.lineTo(length, -0.13f); path.lineTo(length - 0.1f, 0f); path.lineTo(length, 0.13f); path.close()
        }
        // Hand
        shape(c, SKIN) { path.addCircle(0.22f, 0f, 0.15f, Path.Direction.CW) }
        c.restore()
    }

    private inline fun shape(c: Canvas, color: Int, build: () -> Unit) {
        path.reset()
        build()
        fill.color = color
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
    }

    companion object {
        val OUTLINE = Color.rgb(27, 16, 53)
        val SKIN = Color.rgb(255, 211, 176)

        fun darken(color: Int, f: Float): Int =
            Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())
    }
}
