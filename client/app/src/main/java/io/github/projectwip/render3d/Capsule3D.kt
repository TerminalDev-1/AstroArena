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
 * The Arena Box being opened, drawn in 3D in front of the lobby with its own fixed camera: the lobby is
 * dimmed, rays turn behind it, and the capsule is unstable: it glitches (tears sideways, leaves cyan and magenta
 * ghosts, throws off scan bars) on its own and with every knock. A knock jolts it, a charge spins it right round,
 * and to open it winds up, collapses, and blows apart behind a shockwave. The menu only sets timestamps, a colour
 * and how unstable it is in [LobbyParams]; all motion happens here.
 */
class Capsule3D {
    private val shell: Mesh
    private val base: Mesh
    private val core: Mesh
    private val light: Mesh
    private val rays: Mesh
    private val ring: Mesh
    private val screen: Mesh
    private val particles = Particles3D(400)
    private val rng = Random(21)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val root = FloatArray(16)
    private val model = FloatArray(16)
    private val eye = floatArrayOf(0f, 0f, 6.2f)
    private val col = floatArrayOf(0.31f, 0.53f, 1f)
    private var fade = 0f
    private var lastCharge = 0L
    private var lastOpen = 0L
    private var blown = 0L
    /** How far each half has turned as the capsule blows apart, in degrees. */
    private var halfSpin = 0f
    private var lastSplit = 0L
    /** The extra capsules from a split are fresh Scrap ones. */
    private val TWIN = floatArrayOf(0.31f, 0.53f, 1f)

    init {
        // An Arena Box: a rounded crate with corner posts, under a lid that overhangs it. The lid is thrown off
        // one way and the crate the other when it opens.
        shell = MeshBuilder().apply {
            color(1f, 1f, 1f)
            with { translate(0f, 0.5f, 0f); roundedBox(1.42f, 0.36f, 1.42f, 0.1f) }
            // The clasp, on the front of the lid.
            with { translate(0f, 0.4f, 0.72f); roundedBox(0.34f, 0.3f, 0.1f, 0.04f) }
        }.build()
        base = MeshBuilder().apply {
            color(1f, 1f, 1f)
            with { translate(0f, -0.14f, 0f); roundedBox(1.24f, 0.96f, 1.24f, 0.1f) }
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) with { translate(sx * 0.6f, -0.14f, sz * 0.6f); roundedBox(0.2f, 1.02f, 0.2f, 0.05f) }
        }.build()
        // The glowing seam where the lid meets the crate.
        core = MeshBuilder().apply {
            color(1f, 1f, 1f)
            with { translate(0f, 0.32f, 0f); box(1.3f, 0.05f, 1.3f) }
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
        // The shockwave: a ring facing the camera, scaled up as it travels.
        ring = MeshBuilder().apply { color(1f, 1f, 1f); with { rotate(90f, 1f, 0f, 0f); torus(1f, 0.05f, 48, 6) } }.build()
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
        // Opening: it winds up and collapses on itself (0..WIND), then blows apart (WIND..GONE).
        val wind = if (open >= 0f) (open / WIND).coerceIn(0f, 1f) else 0f
        val blast = if (open >= WIND) ((open - WIND) / (GONE - WIND)).coerceIn(0f, 1f) else 0f
        val flash = maxOf(if (charge in 0f..0.35f) 1f - charge / 0.35f else 0f, wind * wind)
        val gap = blast * 3.4f
        halfSpin = blast * 260f
        val solid = open < GONE
        // Charging spins it right round once; winding up spins it faster and faster.
        val spin = (if (charge in 0f..0.5f) 360f * smooth(charge / 0.5f) else 0f) + wind * wind * 1080f
        val squeeze = 1f - 0.5f * smooth(wind) + 0.5f * blast

        // Glitch: bursts a few frames long. They come on their own (more often the more unstable the capsule
        // is), with every knock and charge, and all the way through the wind-up.
        val frame = (time * 24f).toInt()
        val idle = if (noise((time * 3.1f).toInt() * 7 + 1) < 0.16f + 0.34f * p.capsuleGlitch) 0.35f + 0.65f * p.capsuleGlitch else 0f
        val kicked = maxOf(if (knock in 0f..0.2f) 0.8f else 0f, if (charge in 0f..0.4f) 1f - charge / 0.4f else 0f)
        val glitch = if (blast > 0f) 0f else maxOf(idle, kicked, wind).coerceIn(0f, 1f)
        val tearX = (noise(frame) - 0.5f) * 0.55f * glitch
        val tearY = (noise(frame + 17) - 0.5f) * 0.14f * glitch
        // A torn frame is also the wrong shape: wider and flatter, or the other way round.
        val warp = (noise(frame + 31) - 0.5f) * 0.6f * glitch

        if (p.capsuleChargeAt != lastCharge) {
            lastCharge = p.capsuleChargeAt
            if (charge < 0.2f) burst(46, 3.5f, c)
        }
        if (p.capsuleOpenAt != lastOpen) {
            lastOpen = p.capsuleOpenAt
            blown = 0L
        }
        if (open >= WIND && blown != p.capsuleOpenAt) {
            blown = p.capsuleOpenAt
            if (open < WIND + 0.25f) { burst(120, 7f, c); burst(60, 5f, 0xFFFFFFFF.toInt()); burst(30, 9f, 0xFF29F0FF.toInt()); burst(30, 9f, 0xFFFF2BD6.toInt()) }
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
            val x = (if (pieces == 2) -0.62f * split else 0f) + tearX
            val y = sin(time * 2.6f) * 0.05f + lift + tearY
            val tilt = shake + sin(time * 2.2f) * 3f
            val yaw = sin(time * 1.5f) * 38f + spin
            val scale = mainScale * pop * squeeze
            drawCapsule(lit, x, y, tilt, yaw, scale, gap, flash, col, warp)
            if (glitch > 0.05f) {
                // The ghosts: the same shape in cyan and magenta, pulled apart sideways. They sit behind the real
                // one, so they show as a coloured fringe on either side of it.
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
                GLES30.glDepthMask(false)
                val pull = (0.08f + 0.3f * noise(frame + 5)) * glitch
                drawGhost(lit, x - pull, y, tilt, yaw, scale, warp, 0.16f, 0.94f, 1f, 0.5f * glitch)
                drawGhost(lit, x + pull, y + tearY, tilt, yaw, scale, warp, 1f, 0.17f, 0.84f, 0.5f * glitch)
                GLES30.glDepthMask(true)
                GLES30.glDisable(GLES30.GL_BLEND)
            }
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
        lit.i("uMode", 1)
        if (blast > 0f && blast < 1f) {
            // The shockwave races outwards and thins to nothing.
            for ((i, lag) in floatArrayOf(0f, 0.18f).withIndex()) {
                val t = ((blast - lag) / (1f - lag)).coerceIn(0f, 1f)
                if (t <= 0f) continue
                Matrix.setIdentityM(model, 0)
                val s = 0.4f + 5.5f * (1f - (1f - t) * (1f - t))
                Matrix.scaleM(model, 0, s, s, 1f)
                lit.mat4("uModel", model)
                if (i == 0) lit.v4("uTint", 1f, 1f, 1f, (1f - t) * fade) else lit.v4("uTint", col[0], col[1], col[2], (1f - t) * 0.8f * fade)
                ring.draw()
            }
        }
        if (glitch > 0.05f) {
            // Scan bars: thin strips of light knocked sideways across the capsule, a new set every frame.
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            lit.mat4("uViewProj", Toon.IDENTITY)
            val bars = 3 + (glitch * 5f).toInt()
            for (i in 0 until bars) {
                val n = frame * 13 + i * 101
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, (noise(n) - 0.5f) * 0.5f, (noise(n + 1) - 0.5f) * 1.1f, 0f)
                Matrix.scaleM(model, 0, 0.12f + 0.3f * noise(n + 2), 0.004f + 0.022f * noise(n + 3), 1f)
                lit.mat4("uModel", model)
                val a = (0.12f + 0.3f * noise(n + 4)) * glitch * fade
                when (i % 3) {
                    0 -> lit.v4("uTint", 0.16f, 0.94f, 1f, a)
                    1 -> lit.v4("uTint", 1f, 0.17f, 0.84f, a)
                    else -> lit.v4("uTint", col[0], col[1], col[2], a * 1.4f)
                }
                screen.draw()
            }
            lit.mat4("uViewProj", viewProj)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        lit.i("uMode", 0)
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
    private fun drawCapsule(lit: Program, x: Float, y: Float, tilt: Float, yaw: Float, scale: Float, gap: Float, flash: Float, tint: FloatArray, warp: Float = 0f) {
        place(x, y, tilt, yaw, scale, warp)

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
            val g = 1f + minOf(gap, 1.3f) * 1.6f
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

    /** Sets [root]: where one capsule stands, how it is turned, and (while it glitches) how far out of shape it is. */
    private fun place(x: Float, y: Float, tilt: Float, yaw: Float, scale: Float, warp: Float) {
        Matrix.setIdentityM(root, 0)
        Matrix.translateM(root, 0, x, y, 0f)
        Matrix.rotateM(root, 0, tilt, 0f, 0f, 1f)
        Matrix.rotateM(root, 0, 10f, 1f, 0f, 0f)
        // The warp stretches it across the screen, whichever way it happens to be facing.
        Matrix.scaleM(root, 0, 1f + warp, 1f - warp * 0.6f, 1f)
        Matrix.rotateM(root, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(root, 0, scale, scale, scale)
    }

    /** A flat, see-through copy of the closed capsule in one colour. Blending is the caller's to set up. */
    private fun drawGhost(lit: Program, x: Float, y: Float, tilt: Float, yaw: Float, scale: Float, warp: Float, r: Float, g: Float, b: Float, a: Float) {
        place(x, y, tilt, yaw, scale, warp)
        lit.i("uMode", 1)
        lit.v4("uTint", r, g, b, a)
        lit.mat4("uModel", root)
        shell.draw()
        base.draw()
        lit.i("uMode", 0)
    }

    private fun drawHalves(lit: Program, gap: Float, tint: FloatArray, outline: Boolean) {
        // The lid is thrown up and the crate drops away, each turning as it goes.
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.translateM(model, 0, -gap * 0.25f, gap * 0.7f, gap * 0.3f)
        Matrix.rotateM(model, 0, halfSpin, 0.3f, 1f, 0.5f)
        lit.mat4("uModel", model)
        if (outline) lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f) else lit.v4("uTint", tint[0], tint[1], tint[2], 1f)
        shell.draw()
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.translateM(model, 0, gap * 0.25f, -gap * 0.5f, -gap * 0.3f)
        Matrix.rotateM(model, 0, -halfSpin, 0.3f, 1f, 0.5f)
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

    /** A fixed scramble of [n] into 0..1: the same frame always tears the same way. */
    private fun noise(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }

    companion object {
        /** Seconds the capsule spends winding up before it blows, and when the last of it is gone. The menu waits for [GONE]. */
        const val WIND = 0.5f
        const val GONE = 0.95f
    }
}
