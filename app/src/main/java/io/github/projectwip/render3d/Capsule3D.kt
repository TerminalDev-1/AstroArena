package io.github.projectwip.render3d

import android.opengl.GLES30
import android.opengl.Matrix
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Spark Capsule being opened, drawn in 3D in front of the lobby with its own fixed camera: the lobby is
 * dimmed, rays turn behind it, and the capsule shakes when knocked, pops when it charges up a tier and splits
 * in two when it opens. The menu only sets timestamps and a colour in [LobbyParams]; all motion happens here.
 */
class Capsule3D {
    private val shell: Mesh
    private val collarTop: Mesh
    private val base: Mesh
    private val core: Mesh
    private val light: Mesh
    private val rays: Mesh
    private val screen: Mesh
    private val particles = Particles3D(400)
    private val rng = Random(21)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val root = FloatArray(16)
    private val model = FloatArray(16)
    private val eye = floatArrayOf(0f, 0f, 6.2f)
    private val col = floatArrayOf(0.6f, 0.65f, 0.75f)
    private var fade = 0f
    private var lastCharge = 0L
    private var lastOpen = 0L

    init {
        fun MeshBuilder.rivets(y: Float) {
            for (k in 0 until 8) {
                val a = k * (Math.PI / 4)
                with { translate((cos(a) * 0.57).toFloat(), y, (sin(a) * 0.57).toFloat()); sphere(0.045f, 6, 8) }
            }
        }
        shell = MeshBuilder().apply {
            color(1f, 1f, 1f)
            with { translate(0f, 0.37f, 0f); cylinder(0.5f, 0.5f, 28) }
            with { translate(0f, 0.62f, 0f); ellipsoid(0.5f, 0.5f, 0.5f, 10, 28, 0f, 0.5f) }
        }.build()
        collarTop = MeshBuilder().apply {
            color(0.93f, 0.91f, 1f); with { translate(0f, 0.1f, 0f); cylinder(0.57f, 0.16f, 28) }
            color(0.106f, 0.063f, 0.208f); rivets(0.1f)
        }.build()
        base = MeshBuilder().apply {
            color(0.3f, 0.22f, 0.66f)
            with { translate(0f, -0.37f, 0f); cylinder(0.5f, 0.5f, 28) }
            with { translate(0f, -0.62f, 0f); ellipsoid(0.5f, 0.5f, 0.5f, 10, 28, 0.5f, 1f) }
            color(0.72f, 0.68f, 0.92f); with { translate(0f, -0.1f, 0f); cylinder(0.57f, 0.16f, 28) }
            color(0.106f, 0.063f, 0.208f); rivets(-0.1f)
        }.build()
        // The glowing seam between the halves, with a lens on the front and back.
        core = MeshBuilder().apply {
            color(1f, 1f, 1f)
            cylinder(0.54f, 0.07f, 28)
            for (z in listOf(0.5f, -0.5f)) with { translate(0f, 0f, z); sphere(0.17f, 8, 12) }
        }.build()
        light = MeshBuilder().apply { color(1f, 1f, 1f); sphere(0.4f, 10, 14) }.build()
        rays = MeshBuilder().apply {
            color(1f, 1f, 1f)
            for (k in 0 until 14) {
                val a0 = k * (2 * Math.PI / 14)
                val a1 = a0 + 0.15
                val c = vertex(0f, 0f, 0f, 0f, 0f, 1f)
                val p0 = vertex((cos(a0) * 8).toFloat(), (sin(a0) * 8).toFloat(), 0f, 0f, 0f, 1f)
                val p1 = vertex((cos(a1) * 8).toFloat(), (sin(a1) * 8).toFloat(), 0f, 0f, 0f, 1f)
                tri(c, p0, p1)
            }
        }.build()
        screen = MeshBuilder().apply {
            color(1f, 1f, 1f)
            quad(vertex(-1f, -1f, 0f, 0f, 0f, 1f), vertex(1f, -1f, 0f, 0f, 0f, 1f), vertex(1f, 1f, 0f, 0f, 0f, 1f), vertex(-1f, 1f, 0f, 0f, 0f, 1f))
        }.build()
    }

    fun render(aspect: Float, p: LobbyParams, time: Float, dt: Float, lit: Program, sprite: Program, sprites: SpriteBatch) {
        fade += ((if (p.capsuleShown) 1f else 0f) - fade) * (1f - exp(-dt * 12f))
        particles.update(dt)
        if (fade < 0.02f) return

        val now = System.currentTimeMillis()
        val knock = (now - p.capsuleKnockAt) / 1000f
        val charge = (now - p.capsuleChargeAt) / 1000f
        val open = if (p.capsuleOpenAt == 0L) -1f else (now - p.capsuleOpenAt) / 1000f
        val c = p.capsuleColor
        val k = 1f - exp(-dt * 12f)
        col[0] += (((c shr 16) and 0xFF) / 255f - col[0]) * k
        col[1] += (((c shr 8) and 0xFF) / 255f - col[1]) * k
        col[2] += ((c and 0xFF) / 255f - col[2]) * k

        val shake = if (knock in 0f..0.42f) sin(knock * 34f) * 15f * (1f - knock / 0.42f) else 0f
        val pop = if (charge in 0f..0.6f) 1f + 0.35f * exp(-charge * 7f) * cos(charge * 20f) else 1f
        val flash = if (charge in 0f..0.35f) 1f - charge / 0.35f else 0f
        val gap = if (open >= 0f) smooth(open / 0.28f) * 1.6f else 0f
        val solid = open < 0.3f

        if (p.capsuleChargeAt != lastCharge) {
            lastCharge = p.capsuleChargeAt
            if (charge < 0.2f) burst(46, 3.5f, c)
        }
        if (p.capsuleOpenAt != lastOpen) {
            lastOpen = p.capsuleOpenAt
            if (open in 0f..0.2f) { burst(90, 6f, c); burst(40, 4f, 0xFFFFFFFF.toInt()) }
        }
        if (solid && rng.nextFloat() < 0.5f) {
            val a = rng.nextFloat() * 6.28f
            particles.spawn(cos(a) * 0.75f, -0.6f + rng.nextFloat() * 1.2f, sin(a) * 0.75f, 0f, 0.5f + rng.nextFloat() * 0.5f, 0f, 0.9f, 0.07f, c, 0.9f)
        }

        Matrix.perspectiveM(proj, 0, 32f, aspect, 0.3f, 50f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // Dim the lobby, then the rays behind the capsule.
        Toon.setup(lit, Toon.IDENTITY, Toon.IDENTITY, eye, time, null)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        lit.i("uMode", 1)
        lit.v4("uTint", 0.02f, 0.01f, 0.07f, 0.84f * fade)
        screen.draw()
        lit.mat4("uViewProj", viewProj)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -2f)
        Matrix.rotateM(model, 0, time * 18f, 0f, 0f, 1f)
        lit.mat4("uModel", model)
        lit.v4("uTint", col[0], col[1], col[2], (0.17f + 0.2f * flash) * fade)
        rays.draw()
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)

        if (solid) {
            val s = 0.62f * pop * (0.6f + 0.4f * fade)
            Matrix.setIdentityM(root, 0)
            Matrix.translateM(root, 0, 0f, sin(time * 2.6f) * 0.05f + (if (knock in 0f..0.42f) 0.08f * (1f - knock / 0.42f) else 0f), 0f)
            Matrix.rotateM(root, 0, shake + sin(time * 2.2f) * 3f, 0f, 0f, 1f)
            Matrix.rotateM(root, 0, 10f, 1f, 0f, 0f)
            Matrix.rotateM(root, 0, time * 50f, 0f, 1f, 0f)
            Matrix.scaleM(root, 0, s, s, s)

            lit.i("uMode", 0)
            lit.f("uRim", 0.5f)
            lit.f("uFlash", flash * 0.6f)
            drawHalves(lit, gap, outline = false)
            lit.f("uFlash", 0f)
            // Seam glow, and the light that pours out as the halves part.
            lit.mat4("uModel", root)
            lit.f("uEmissive", 1f)
            lit.v4("uTint", 0.5f + col[0] * 0.5f, 0.5f + col[1] * 0.5f, 0.5f + col[2] * 0.5f, 1f)
            if (gap < 0.3f) core.draw()
            if (gap > 0f) {
                System.arraycopy(root, 0, model, 0, 16)
                val g = 1f + gap * 1.6f
                Matrix.scaleM(model, 0, g, g, g)
                lit.mat4("uModel", model)
                lit.v4("uTint", 1f, 1f, 1f, 1f)
                light.draw()
            }
            lit.f("uEmissive", 0f)

            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_FRONT)
            lit.i("uMode", 1)
            lit.f("uOutline", 0.035f)
            drawHalves(lit, gap, outline = true)
            lit.f("uOutline", 0f)
            lit.i("uMode", 0)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
        }

        // Glow and sparks.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        sprites.begin()
        sprites.add(0f, 0f, -1.2f, 2.6f + flash, col[0], col[1], col[2], (0.5f + 0.4f * flash) * fade)
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawHalves(lit: Program, gap: Float, outline: Boolean) {
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.translateM(model, 0, 0f, gap, 0f)
        lit.mat4("uModel", model)
        if (outline) lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f) else lit.v4("uTint", col[0], col[1], col[2], 1f)
        shell.draw()
        if (!outline) lit.v4("uTint", 1f, 1f, 1f, 1f)
        collarTop.draw()
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.translateM(model, 0, 0f, -gap, 0f)
        lit.mat4("uModel", model)
        base.draw()
    }

    private fun burst(count: Int, speed: Float, color: Int) = repeat(count) {
        val a = rng.nextFloat() * 6.28f
        val up = rng.nextFloat() * 2f - 1f
        val sp = speed * (0.4f + rng.nextFloat() * 0.6f)
        particles.spawn(cos(a) * 0.3f, up * 0.4f, sin(a) * 0.3f, cos(a) * sp, up * sp, sin(a) * sp * 0.5f, 0.6f + rng.nextFloat() * 0.5f, 0.1f, color, 1f)
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
}
