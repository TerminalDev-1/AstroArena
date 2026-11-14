package io.github.projectwip.render3d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.TextureView
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.Lore
import io.github.projectwip.gl.GlRenderer
import io.github.projectwip.gl.GlThread
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import io.github.projectwip.gl.Shaders
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Inside the Entity, where it keeps whoever its flares pulled in (the user's design, build 111): a ruin like a hell,
 * dark and crimson, broken pillars and fallen stone over a cracked floor glowing from below, embers drifting up.
 * Everyone it holds is chained there, struggling: the player's fighter at the front, any teammates it has beside
 * them, and the forgotten fighters it has carried for longer chained further back. It starves them to make itself
 * stronger: their energy is drawn up out of each of them in streams to its heart far above, where its eyes watch.
 * Shown full screen while the player is trapped; the match goes on underneath.
 */
class EntityInsideParams {
    @Volatile var time = 0f
    /** Who it holds from this match, the player first: (fighter, skin). */
    @Volatile var held: List<Pair<FighterId, Int>> = emptyList()
}

class EntityInsideScene {
    private val lit = Program(Shaders.LIT_VS, Shaders.LIT_FS, "inside-lit")
    private val sprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "inside-sprite")
    private val models = FighterModels()
    private val sprites = SpriteBatch(4096)
    private val particles = Particles3D(512)
    private val rng = Random(29)

    private val floor: Mesh = MeshBuilder().apply { color(1f, 1f, 1f); cylinder(17f, 0.4f, 48) }.build()
    private val column: Mesh = MeshBuilder().apply { color(1f, 1f, 1f); cylinder(0.75f, 1f, 10, topRadius = 0.62f) }.build()
    private val block: Mesh = MeshBuilder().apply { color(1f, 1f, 1f); box(1f, 1f, 1f) }.build()
    private val link: Mesh = MeshBuilder().apply { color(1f, 1f, 1f); torus(0.075f, 0.024f, 12, 6) }.build()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val eye = FloatArray(3)
    private val m = FloatArray(16)
    private val anim = FighterAnim()

    /** The broken pillars round the ruin: (x, z, height, lean degrees). */
    private val pillars = List(11) { i ->
        val a = i * (6.2831855f / 11) + 0.3f
        val r = 10.5f + hash(i, 1) * 2.5f
        floatArrayOf(cos(a) * r, sin(a) * r, 1.5f + hash(i, 2) * 5.5f, 0f)
    }
    /** Fallen stone on the floor: (x, z, size, turn degrees). */
    private val rubble = List(26) { i ->
        val a = hash(i, 11) * 6.2832f
        val r = 4.5f + hash(i, 12) * 9f
        floatArrayOf(cos(a) * r, sin(a) * r, 0.35f + hash(i, 13) * 0.9f, hash(i, 14) * 90f)
    }
    /** Glowing cracks in the floor: (x0, z0, x1, z1). */
    private val cracks = List(18) { i ->
        val a = hash(i, 21) * 6.2832f
        val r = 1.5f + hash(i, 22) * 11f
        val x = cos(a) * r; val z = sin(a) * r
        val b = a + (hash(i, 23) - 0.5f) * 2.5f
        val l = 1.2f + hash(i, 24) * 2.8f
        floatArrayOf(x, z, x + cos(b) * l, z + sin(b) * l)
    }

    init {
        Matrix.setLookAtM(lightView, 0, -Toon.LIGHT[0] * 20f, -Toon.LIGHT[1] * 20f, -Toon.LIGHT[2] * 20f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -16f, 16f, -16f, 16f, 1f, 60f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    private fun hash(i: Int, k: Int): Float {
        var h = i * 73856093 xor (k * 83492791)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    /** Where each prisoner is: the held from this match in a row at the front, the forgotten souls further back. */
    private fun place(k: Int, front: Int, out: FloatArray) {
        if (k < front) {
            out[0] = (k - (front - 1) / 2f) * 2.3f; out[1] = 1.2f - kotlin.math.abs(k - (front - 1) / 2f) * 0.6f
        } else {
            val j = k - front
            val n = Lore.Soul.entries.size
            val a = 3.1416f + 0.45f + j * ((3.1416f - 0.9f) / (n - 1))
            out[0] = cos(a) * 5.8f; out[1] = sin(a) * 4.2f - 1.2f
        }
    }
    private val at = FloatArray(2)

    fun render(width: Int, height: Int, p: EntityInsideParams, dt: Float) {
        val t = p.time
        val held = p.held
        val front = held.size.coerceAtLeast(1)
        // The camera drifts slowly round the front of the prisoners, low, looking up a little into the dark.
        val a = 0.22f * sin(t * 0.12f)
        eye[0] = sin(a) * 8.5f; eye[1] = 2.6f + 0.25f * sin(t * 0.2f); eye[2] = cos(a) * 8.5f + 1f
        Matrix.perspectiveM(proj, 0, 44f, width.toFloat() / height, 0.3f, 200f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 2.1f, -1f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.025f, 0.004f, 0.015f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)
        Toon.setup(lit, viewProj, lightVP, eye, t, null)

        // ---- the ruin: scorched floor, broken pillars, fallen stone (lit dim and red, as if by the fire below)
        lit.f("uRim", 0.15f)
        lit.v4("uTint", 0.07f, 0.018f, 0.03f, 1f)
        Matrix.setIdentityM(m, 0); Matrix.translateM(m, 0, 0f, -0.4f, 0f)
        lit.mat4("uModel", m); floor.draw()
        lit.f("uRim", 0.35f)
        for ((i, pl) in pillars.withIndex()) {
            lit.v4("uTint", 0.12f + 0.04f * hash(i, 4), 0.045f, 0.06f, 1f)
            Matrix.setIdentityM(m, 0); Matrix.translateM(m, 0, pl[0], 0f, pl[1])
            Matrix.rotateM(m, 0, pl[3], 1f, 0f, 0.4f)
            Matrix.scaleM(m, 0, 1f, pl[2], 1f)
            lit.mat4("uModel", m); column.draw()
            // A broken cap, tipped over on top.
            Matrix.setIdentityM(m, 0); Matrix.translateM(m, 0, pl[0], pl[2] * 0.98f, pl[1])
            Matrix.rotateM(m, 0, 18f + 30f * hash(i, 5), 0.3f, 0.5f, 1f)
            Matrix.scaleM(m, 0, 1.5f, 0.45f, 1.5f)
            lit.mat4("uModel", m); block.draw()
        }
        for ((i, r) in rubble.withIndex()) {
            lit.v4("uTint", 0.1f + 0.04f * hash(i, 15), 0.035f, 0.05f, 1f)
            Matrix.setIdentityM(m, 0); Matrix.translateM(m, 0, r[0], r[2] * 0.4f, r[1])
            Matrix.rotateM(m, 0, r[3], 0.2f, 1f, 0.3f)
            Matrix.scaleM(m, 0, r[2] * 1.4f, r[2], r[2])
            lit.mat4("uModel", m); block.draw()
        }

        // ---- the prisoners, chained to posts, struggling
        val total = front + Lore.Soul.entries.size
        for (k in 0 until total) {
            place(k, front, at)
            val x = at[0]; val z = at[1]
            // Their post behind them.
            lit.f("uRim", 0.3f); lit.v4("uTint", 0.09f, 0.03f, 0.04f, 1f)
            Matrix.setIdentityM(m, 0); Matrix.translateM(m, 0, x, 1.3f, z - 0.55f); Matrix.scaleM(m, 0, 0.45f, 2.6f, 0.45f)
            lit.mat4("uModel", m); block.draw()
            drawChains(x, z, t, k)
            anim.time = t + k; anim.walk = 0f; anim.moving = 0f; anim.spin = 7f * sin(t * (2.2f + k * 0.3f) + k)
            anim.recoil = 0.3f + 0.3f * sin(t * (5f + k) + k * 2f); anim.bow = 10f + 6f * sin(t * 0.9f + k)
            anim.jump = 0f; anim.talk = 0f; anim.scale = EntityScene.FIGHTER_SCALE * (if (k < front) 1f else 0.95f)
            // Drained: they flicker pale as it drinks.
            anim.flash = 0.12f + 0.1f * sin(t * 6f + k * 1.7f)
            lit.f("uRim", 0.6f)
            lit.v4("uTint", 0.62f, 0.42f, 0.55f, 1f)
            if (k < held.size) {
                val (id, skin) = held[k]
                models.draw(lit, Balance.fighter(id), skin, x, z, 1.5707964f, anim, Pass.COLOR)
            } else if (k >= front) models.drawSoul(lit, Lore.Soul.entries[k - front], x, z, 1.5707964f, anim, Pass.COLOR)
        }
        lit.f("uFlash", 0f); lit.v4("uTint", 1f, 1f, 1f, 1f); lit.mat4("uModel", Toon.IDENTITY)
        // Outlines.
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f)
        lit.f("uOutline", FighterModels.OUTLINE)
        for (k in 0 until total) {
            place(k, front, at)
            anim.time = t + k; anim.spin = 7f * sin(t * (2.2f + k * 0.3f) + k)
            anim.recoil = 0.3f + 0.3f * sin(t * (5f + k) + k * 2f); anim.bow = 10f + 6f * sin(t * 0.9f + k)
            anim.scale = EntityScene.FIGHTER_SCALE * (if (k < front) 1f else 0.95f)
            if (k < held.size) { val (id, skin) = held[k]; models.draw(lit, Balance.fighter(id), skin, at[0], at[1], 1.5707964f, anim, Pass.OUTLINE) }
            else if (k >= front) models.drawSoul(lit, Lore.Soul.entries[k - front], at[0], at[1], 1.5707964f, anim, Pass.OUTLINE)
        }
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // ---- light: the fire in the cracks, embers, its heart and eyes, and the energy it drinks
        particles.update(dt)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        sprites.begin()
        // The cracks glow from below, breathing.
        for ((i, c) in cracks.withIndex()) {
            val glow = 0.55f + 0.45f * sin(t * (0.8f + hash(i, 25)) + i)
            for (k in 0..10) {
                val s = k / 10f
                sprites.add(c[0] + (c[2] - c[0]) * s, 0.04f, c[1] + (c[3] - c[1]) * s, 0.5f, 1f, 0.28f, 0.08f, 0.85f * glow)
            }
        }
        // A red haze low over the floor, and murk higher up.
        for (i in 0 until 40) {
            val ang = hash(i, 31) * 6.2832f + t * 0.03f
            val r = 2f + hash(i, 32) * 13f
            sprites.add(cos(ang) * r, 0.4f + hash(i, 33) * 1.2f, sin(ang) * r, 3.5f + hash(i, 34) * 3f, 0.7f, 0.1f, 0.06f, 0.06f)
        }
        // Embers rising.
        for (i in 0 until 90) {
            val life = (hash(i, 41) + t * (0.05f + 0.06f * hash(i, 42))) % 1f
            val ang = hash(i, 43) * 6.2832f
            val r = hash(i, 44) * 13f
            sprites.add(cos(ang) * r + 0.6f * sin(t + i), life * 14f, sin(ang) * r, 0.1f + 0.08f * hash(i, 45),
                1f, 0.45f + 0.3f * hash(i, 46), 0.15f, 0.8f * sin(life * 3.1416f))
        }
        // Its heart, high in the dark, pulsing as it feeds; its eyes above, watching, now and then closing.
        val hx = 0f; val hy = 5.6f; val hz = -9f
        val beat = 0.75f + 0.25f * sin(t * 4.2f) * sin(t * 4.2f)
        sprites.add(hx, hy, hz, 7f * beat, 0.55f, 0.08f, 0.35f, 0.35f)
        sprites.add(hx, hy, hz, 2.6f * beat, 0.95f, 0.35f, 0.75f, 0.6f)
        val blink = if ((t % 7f) < 0.18f) 0.1f else 1f
        for (side in listOf(-1f, 1f)) {
            sprites.add(hx + side * 2.6f, hy + 2.2f, hz - 2f, 3.2f, 0.7f, 0.5f, 1f, 0.3f * blink)
            sprites.add(hx + side * 2.6f, hy + 2.2f, hz - 2f, 1.3f, 1f, 0.95f, 1f, 0.85f * blink)
        }
        // The energy it drinks: streams drawn up out of every prisoner into its heart, faster from the newest.
        for (k in 0 until total) {
            place(k, front, at)
            val x0 = at[0]; val y0 = 1.3f; val z0 = at[1]
            val n = if (k < front) 26 else 14
            for (j in 0 until n) {
                val v = ((t * (if (k < front) 0.55f else 0.35f) + j / n.toFloat() + k * 0.13f) % 1f)
                val curl = sin(v * 3.1416f) * (1.2f + 0.3f * k % 3)
                val x = x0 + (hx - x0) * v + curl * sin(t * 0.7f + k)
                val y = y0 + (hy - y0) * v
                val z = z0 + (hz - z0) * v + curl * 0.4f
                val warm = k < front
                sprites.add(x, y, z, 0.22f + 0.18f * v, if (warm) 0.45f else 0.6f, if (warm) 0.85f else 0.4f, 1f, (if (warm) 0.75f else 0.4f) * (1f - v * 0.5f))
            }
            // Where it leaves them, a glow on their chest.
            sprites.add(x0, 1.25f, z0 + 0.3f, if (k < front) 0.9f else 0.6f, 0.45f, 0.8f, 1f, 0.35f + 0.15f * sin(t * 6f + k))
            if (k < front && rng.nextFloat() < 0.25f) spark(x0, 1.2f, z0 + 0.2f)
        }
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Chains round a prisoner at (x, z), from one side of their post to the other, rattling as they pull. */
    private fun drawChains(x: Float, z: Float, t: Float, k: Int) {
        lit.f("uRim", 0.5f); lit.f("uGloss", 0.5f)
        lit.v4("uTint", 0.32f, 0.27f, 0.3f, 1f)
        for (c in 0 until 3) {
            val h = 0.55f + c * 0.42f
            val r = 0.5f - c * 0.04f
            for (j in 0 until 16) {
                val u = j / 15f
                val ang = (-200f + 220f * u) * (3.1416f / 180f)
                val rr = r + 0.02f * sin(t * 18f + j + c + k)
                Matrix.setIdentityM(m, 0)
                Matrix.translateM(m, 0, x + cos(ang) * rr, h + 0.02f * sin(t * 11f + j * 0.7f + k), z - sin(ang) * rr * 1.1f - 0.15f)
                Matrix.rotateM(m, 0, -ang * 57.29578f + 90f, 0f, 1f, 0f)
                Matrix.rotateM(m, 0, if (j % 2 == 0) 90f else 0f, 1f, 0f, 0f)
                lit.mat4("uModel", m); link.draw()
            }
        }
        lit.f("uGloss", 0f)
    }

    private fun spark(x: Float, y: Float, z: Float) {
        val a = rng.nextFloat() * 6.28f
        val sp = 0.8f + rng.nextFloat()
        particles.spawn(x, y, z, cos(a) * sp, 1f + rng.nextFloat() * 2f, sin(a) * sp, 0.7f, 0.08f, 0xFF5CC8FF.toInt(), 1f)
    }
}

/** Hosts [EntityInsideScene] (opaque, full screen). The screen around it keeps [params] up to date. */
@SuppressLint("ViewConstructor")
class EntityInsideView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    val params = EntityInsideParams()
    private var thread: GlThread? = null

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val renderer = object : GlRenderer {
            private var scene: EntityInsideScene? = null
            private var w = width
            private var h = height
            override fun onCreated() { scene = EntityInsideScene() }
            override fun onSize(width: Int, height: Int) { w = width; h = height }
            override fun onFrame(dt: Float) { params.time += dt; scene?.render(w, h, params, dt) }
        }
        val gfx = io.github.projectwip.gl.GraphicsProfile.current
        thread = GlThread(st, renderer, "inside-gl", transparent = false, msaa = gfx.msaa).also { it.resize(width, height); it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread?.shutdown(); thread = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
