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
    private var lastSplit = 0L
    /** The extra capsules from a split are fresh Scrap ones. */
    private val TWIN = floatArrayOf(0.6f, 0.65f, 0.75f)

    init {
// A Spark Drop: a puffy five-pointed star. The front half lifts off the back half when it opens.
        fun MeshBuilder.starHalf(front: Boolean) {
            val s = if (front) 1f else -1f
            val pos = ArrayList<FloatArray>()
            fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int { pos += floatArrayOf(x, y, z); return vertex(x, y, z, nx, ny, nz) }
            val first = v(0f, 0f, 0.4f * s, 0f, 0f, s)
            // Every triangle is wound to face away from the middle of the star: the ink outline is an inverted
            // hull drawn with front faces culled, so a wrong winding paints the whole thing black.
            fun face(i0: Int, i1: Int, i2: Int) {
                val p0 = pos[i0 - first]; val p1 = pos[i1 - first]; val p2 = pos[i2 - first]
                val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]
                val wx = p2[0] - p0[0]; val wy = p2[1] - p0[1]; val wz = p2[2] - p0[2]
                val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
                val cx = (p0[0] + p1[0] + p2[0]) / 3f; val cy = (p0[1] + p1[1] + p2[1]) / 3f; val cz = (p0[2] + p1[2] + p2[2]) / 3f
                if (nx * cx + ny * cy + nz * (cz + 0.2f * s) >= 0f) tri(i0, i1, i2) else tri(i0, i2, i1)
            }
            val rim = IntArray(10)
            val edge = IntArray(10)
            for (i in 0 until 10) {
                val a = -Math.PI / 2 + i * Math.PI / 5
                val r = if (i % 2 == 0) 0.9f else 0.46f
                val x = (cos(a) * r).toFloat(); val y = -(sin(a) * r).toFloat()
                val len = kotlin.math.sqrt(x * x + y * y + 0.2f)
                rim[i] = v(x, y, 0.1f * s, x / len, y / len, 0.45f * s / len)
                edge[i] = v(x, y, 0f, x / r, y / r, 0f)
            }
            for (i in 0 until 10) {
                val j = (i + 1) % 10
                face(first, rim[i], rim[j])
                face(rim[i], rim[j], edge[j]); face(rim[i], edge[j], edge[i])
            }
        }
        shell = MeshBuilder().apply { color(1f, 1f, 1f); starHalf(front = true) }.build()
        // A glint on the upper-left of the face.
        collarTop = MeshBuilder().apply { color(1f, 1f, 1f); with { translate(-0.2f, 0.26f, 0.3f); rotate(35f, 0f, 0f, 1f); ellipsoid(0.13f, 0.05f, 0.03f, 6, 10) } }.build()
        base = MeshBuilder().apply { color(1f, 1f, 1f); starHalf(front = false) }.build()
        // The glowing heart of the star (seen from both sides), and a thin halo that circles it.
        core = MeshBuilder().apply {
            color(1f, 1f, 1f)
            for (z in listOf(0.3f, -0.3f)) with { translate(0f, 0f, z); ellipsoid(0.2f, 0.2f, 0.12f, 8, 12) }
            with { rotate(72f, 1f, 0f, 0.2f); torus(1.08f, 0.022f, 44, 6) }
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

        // A capsule that split: two sit side by side; four or eight ring the original, which stays in the middle.
        val pieces = p.capsulePieces.coerceIn(1, 8)
        val split = if (p.capsuleSplitAt == 0L || pieces < 2) 0f else smooth((now - p.capsuleSplitAt) / 350f)
        if (p.capsuleSplitAt != lastSplit) {
            lastSplit = p.capsuleSplitAt
            if (p.capsuleSplitAt != 0L && now - p.capsuleSplitAt < 200) burst(30 + pieces * 8, 4.5f, 0xFFFFFFFF.toInt())
        }
        val base = 0.62f * (0.6f + 0.4f * fade)
        val mainScale = base * when { pieces >= 8 -> 0.66f; pieces >= 4 -> 0.74f; pieces == 2 -> 0.8f; else -> 1f }
        if (solid) {
            val lift = if (knock in 0f..0.42f) 0.08f * (1f - knock / 0.42f) else 0f
            drawCapsule(lit, if (pieces == 2) -0.62f * split else 0f, sin(time * 2.6f) * 0.05f + lift, shake + sin(time * 2.2f) * 3f, sin(time * 1.5f) * 38f, mainScale * pop, gap, flash, col)
        }
        if (pieces == 2) {
            drawCapsule(lit, 0.62f * split, sin(time * 2.6f + 1.4f) * 0.05f, sin(time * 2.2f + 1f) * 3f, sin(time * 1.5f + 1.2f) * 38f, mainScale * split, 0f, 0f, TWIN)
        } else if (pieces > 2) {
            val twinScale = base * (if (pieces >= 8) 0.36f else 0.44f) * (0.5f + 0.5f * split)
            for (i in 0 until pieces - 1) {
                val a = i * 6.2832f / (pieces - 1) + time * 0.35f
                drawCapsule(lit, cos(a) * 1.5f * split, sin(a) * 0.82f * split + sin(time * 2.6f + i) * 0.03f, sin(time * 2.2f + i) * 4f, sin(time * 1.5f + i) * 38f, twinScale, 0f, 0f, TWIN)
            }
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

    /** One whole capsule: lit body, glowing seam, the light inside as it opens, and its ink outline. */
    private fun drawCapsule(lit: Program, x: Float, y: Float, tilt: Float, yaw: Float, scale: Float, gap: Float, flash: Float, tint: FloatArray) {
        Matrix.setIdentityM(root, 0)
        Matrix.translateM(root, 0, x, y, 0f)
        Matrix.rotateM(root, 0, tilt, 0f, 0f, 1f)
        Matrix.rotateM(root, 0, 10f, 1f, 0f, 0f)
        Matrix.rotateM(root, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(root, 0, scale, scale, scale)

        lit.i("uMode", 0)
        lit.f("uRim", 0.5f)
        lit.f("uFlash", flash * 0.6f)
        drawHalves(lit, gap, tint, outline = false)
        lit.f("uFlash", 0f)
        // Seam glow, and the light that pours out as the halves part.
        lit.mat4("uModel", root)
        lit.f("uEmissive", 1f)
        lit.v4("uTint", 0.5f + tint[0] * 0.5f, 0.5f + tint[1] * 0.5f, 0.5f + tint[2] * 0.5f, 1f)
        if (gap < 0.2f) core.draw()
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
        lit.f("uOutline", 0.028f)
        drawHalves(lit, gap, tint, outline = true)
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    private fun drawHalves(lit: Program, gap: Float, tint: FloatArray, outline: Boolean) {
        // The halves part front-to-back.
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.translateM(model, 0, 0f, 0f, gap * 0.6f)
        lit.mat4("uModel", model)
        if (outline) lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f) else lit.v4("uTint", tint[0], tint[1], tint[2], 1f)
        shell.draw()
        if (!outline) { lit.v4("uTint", 1f, 1f, 1f, 1f); collarTop.draw() }
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.translateM(model, 0, 0f, 0f, -gap * 0.6f)
        lit.mat4("uModel", model)
        // The bottom half is the same colour, a shade deeper.
        if (!outline) lit.v4("uTint", tint[0] * 0.72f, tint[1] * 0.72f, tint[2] * 0.78f, 1f)
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
