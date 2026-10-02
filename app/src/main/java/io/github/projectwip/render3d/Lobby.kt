package io.github.projectwip.render3d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.TextureView
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.gl.GlRenderer
import io.github.projectwip.gl.GlThread
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import io.github.projectwip.gl.Shaders
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Camera framings the menus can ask for. */
enum class LobbyShot { HOME, FIGHTER, BACKDROP }

/** What the lobby shows. Written by the UI thread, read by the GL thread. */
class LobbyParams {
    @Volatile var fighter = FighterId.JUNO
    @Volatile var skin = 0
    @Volatile var locked = false
    @Volatile var showFighter = true
    @Volatile var shot = LobbyShot.HOME
    /** Where on screen (0..1 of the width) the fighter should stand. */
    @Volatile var fighterScreenX = 0.5f
    /** Where on screen (0..1 from the top) the fighter should be centred (FIGHTER shot). */
    @Volatile var fighterScreenY = 0.5f
    @Volatile var dragYaw = 0f
    @Volatile var celebrateAt = 0L
    @Volatile var cheerAt = 0L
}

/**
 * The menu backdrop: a stylised 3D lobby — sky dome, glowing floor, neon pillars, a rotating emblem,
 * floating crates/bolts/cells — with the selected fighter on a pedestal. One persistent instance sits behind
 * every menu screen; the camera glides between [LobbyShot]s.
 */
class LobbyScene {
    private val lit = Program(Shaders.LIT_VS, Shaders.LIT_FS, "lobby-lit")
    private val depth = Program(Shaders.LIT_VS, Shaders.DEPTH_FS, "lobby-depth")
    private val sprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "lobby-sprite")
    private val models = FighterModels()
    private val shadow = ShadowMap(1024)
    private val sprites = SpriteBatch(512)
    private val particles = Particles3D(512)
    private val rng = Random(9)

    private val sky: Mesh
    private val floor: Mesh
    private val floorGlow: Mesh
    private val pillars: Mesh
    private val neon: Mesh
    private val emblem: Mesh
    private val emblemCore: Mesh
    private val pedestal: Mesh
    private val pedestalTop: Mesh
    private val pedestalRim: Mesh
    private val crate: Mesh
    private val bolt: Mesh
    private val cell: Mesh
    private val pillarTops = ArrayList<FloatArray>()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val model = FloatArray(16)
    private val eye = floatArrayOf(0f, 2.2f, 7.8f)
    private val target = floatArrayOf(0f, 1.15f, 0f)
    private var shift = 0f
    private var shiftY = 0f
    private var fighterAlpha = 1f
    private val anim = FighterAnim()
    private var shownYaw = 0f
    private var lastCelebrate = 0L

    init {
        sky = MeshBuilder().apply {
            // Inside of a big sphere with a vertical gradient (vertex colours, drawn unlit).
            val lat = 16; val lon = 32; val r = 70f
            val base = 0
            for (j in 0..lat) {
                val phi = PI * j / lat
                val y = cos(phi).toFloat(); val s = sin(phi).toFloat()
                val t = (y * 0.5f + 0.5f)
                color(0.42f * (1 - t) + 0.07f * t, 0.18f * (1 - t) + 0.04f * t, 0.6f * (1 - t) + 0.22f * t)
                for (i in 0..lon) {
                    val th = 2 * PI * i / lon
                    vertex((cos(th) * s).toFloat() * r, y * r, (sin(th) * s).toFloat() * r, 0f, -y, 0f)
                }
            }
            for (j in 0 until lat) for (i in 0 until lon) {
                val i0 = base + j * (lon + 1) + i
                quad(i0, i0 + lon + 1, i0 + lon + 2, i0 + 1)
            }
        }.build()
        floor = MeshBuilder().apply {
            color(0.2f, 0.15f, 0.42f); ring(0f, 26f, 64)
            // Radial spokes and tile rings for a sense of depth.
            color(0.26f, 0.2f, 0.52f)
            for (k in 0 until 24) with { rotate(k * 15f, 0f, 1f, 0f); groundQuad(1.6f, -0.03f, 26f, 0.03f, 0.004f) }
            for (r in listOf(4.5f, 7f, 10f, 14f)) with { translate(0f, 0.005f, 0f); ring(r - 0.04f, r + 0.04f, 64) }
        }.build()
        floorGlow = MeshBuilder().apply {
            color(1f, 1f, 1f)
            for (r in listOf(2.1f, 3.2f)) with { translate(0f, 0.01f, 0f); ring(r - 0.05f, r + 0.05f, 64) }
        }.build()
        pillars = MeshBuilder().apply {
            for (k in 0 until 7) {
                val a = Math.toRadians(-160.0 + k * (140.0 / 6))
                val x = (cos(a) * 11).toFloat(); val z = (sin(a) * 11).toFloat()
                color(0.3f, 0.24f, 0.62f)
                with { translate(x, 3.5f, z); roundedBox(1.0f, 7f, 1.0f, 0.18f, 2) }
                color(0.22f, 0.17f, 0.45f)
                with { translate(x, 0.3f, z); roundedBox(1.4f, 0.6f, 1.4f, 0.12f, 2) }
                pillarTops += floatArrayOf(x, 7.2f, z)
            }
            // Back wall arc
            color(0.17f, 0.12f, 0.36f)
            for (k in 0 until 12) {
                val a = Math.toRadians(-165.0 + k * (150.0 / 11))
                with { translate((cos(a) * 13.5).toFloat(), 4f, (sin(a) * 13.5).toFloat()); rotate(-Math.toDegrees(a).toFloat() - 90f, 0f, 1f, 0f); roundedBox(4.2f, 8f, 0.6f, 0.15f, 1) }
            }
        }.build()
        neon = MeshBuilder().apply {
            for (k in 0 until 7) {
                val a = Math.toRadians(-160.0 + k * (140.0 / 6))
                val x = (cos(a) * 11).toFloat(); val z = (sin(a) * 11).toFloat()
                if (k % 2 == 0) color(0.2f, 0.85f, 1f) else color(1f, 0.35f, 0.85f)
                with { translate(x + 0.52f * cos(a + PI / 2).toFloat() * 0f, 3.5f, z); roundedBox(1.08f, 0.16f, 1.08f, 0.06f, 1) }
                with { translate(x, 5.6f, z); roundedBox(1.08f, 0.1f, 1.08f, 0.04f, 1) }
                with { translate(x, 1.4f, z); roundedBox(1.08f, 0.1f, 1.08f, 0.04f, 1) }
            }
        }.build()
        emblem = MeshBuilder().apply {
            color(1f, 0.72f, 0.2f)
            with { rotate(90f, 1f, 0f, 0f); rotate(30f, 0f, 1f, 0f); cylinder(2.2f, 0.4f, 6) }
            color(0.85f, 0.42f, 0.08f)
            with { translate(0f, 0f, 0.05f); rotate(90f, 1f, 0f, 0f); rotate(30f, 0f, 1f, 0f); cylinder(1.75f, 0.42f, 6) }
        }.build()
        emblemCore = MeshBuilder().apply {
            // A lightning spark across the emblem
            color(0.2f, 0.95f, 0.88f)
            with { translate(0f, 0f, 0.3f); rotate(-20f, 0f, 0f, 1f); roundedBox(0.45f, 2.4f, 0.2f, 0.08f, 1) }
        }.build()
        pedestal = MeshBuilder().apply { color(0.3f, 0.22f, 0.66f); with { translate(0f, -0.2f, 0f); cylinder(1.25f, 0.4f, 40) } }.build()
        pedestalTop = MeshBuilder().apply {
            color(0.5f, 0.42f, 0.95f); with { translate(0f, 0.005f, 0f); cylinder(1.08f, 0.02f, 40) }
            color(0.62f, 0.55f, 1f); with { translate(0f, 0.02f, 0f); ring(0.55f, 0.62f, 40) }
        }.build()
        pedestalRim = MeshBuilder().apply { torus(1.17f, 0.06f, 48, 8) }.build()
        crate = MeshBuilder().apply {
            color(0.93f, 0.55f, 0.18f); roundedBox(0.7f, 0.7f, 0.7f, 0.09f, 2)
            color(0.3f, 0.22f, 0.4f); for (y in listOf(-0.22f, 0.22f)) with { translate(0f, y, 0f); roundedBox(0.74f, 0.08f, 0.74f, 0.03f, 1) }
        }.build()
        bolt = MeshBuilder().apply {
            color(0.61f, 0.9f, 1f); with { rotate(90f, 1f, 0f, 0f); cylinder(0.32f, 0.16f, 6) }
            color(0.17f, 0.36f, 0.54f); with { translate(0f, 0f, 0.0f); rotate(90f, 1f, 0f, 0f); cylinder(0.13f, 0.18f, 12) }
        }.build()
        cell = MeshBuilder().apply {
            color(1f, 0.85f, 0.25f); cylinder(0.16f, 0.36f, 14)
            color(0.22f, 0.16f, 0.36f); with { translate(0f, 0.2f, 0f); cylinder(0.17f, 0.07f, 14) }; with { translate(0f, -0.2f, 0f); cylinder(0.17f, 0.07f, 14) }
        }.build()

        Matrix.setLookAtM(lightView, 0, -Toon.LIGHT[0] * 14f, -Toon.LIGHT[1] * 14f, -Toon.LIGHT[2] * 14f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -6f, 6f, -6f, 6f, 1f, 40f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    private fun shotEye(s: LobbyShot): FloatArray = when (s) {
        LobbyShot.HOME -> floatArrayOf(0f, 2.3f, 7.6f)
        LobbyShot.FIGHTER -> floatArrayOf(0.4f, 2.4f, 8.0f)
        LobbyShot.BACKDROP -> floatArrayOf(0f, 3.4f, 9.5f)
    }

    private fun shotTarget(s: LobbyShot): FloatArray = when (s) {
        LobbyShot.HOME -> floatArrayOf(0f, 1.2f, 0f)
        LobbyShot.FIGHTER -> floatArrayOf(0f, 0.95f, 0f)
        LobbyShot.BACKDROP -> floatArrayOf(0f, 3.6f, -8f)
    }

    fun render(width: Int, height: Int, p: LobbyParams, time: Float, dt: Float) {
        val def = Balance.fighter(p.fighter)
        val skin = def.skins[p.skin.coerceIn(0, def.skins.lastIndex)]
        val aspect = width.toFloat() / height

        // Camera glides between shots.
        val k = 1f - exp(-dt * 3.2f)
        val e = shotEye(p.shot); val t = shotTarget(p.shot)
        for (i in 0..2) { eye[i] += (e[i] - eye[i]) * k; target[i] += (t[i] - target[i]) * k }
        val wantShift = if (p.shot == LobbyShot.BACKDROP) 0f else (p.fighterScreenX * 2f - 1f)
        shift += (wantShift - shift) * k
        val wantShiftY = if (p.shot == LobbyShot.FIGHTER) (1f - 2f * p.fighterScreenY) * 0.9f else 0f
        shiftY += (wantShiftY - shiftY) * k
        val wantAlpha = if (p.showFighter && p.shot != LobbyShot.BACKDROP) 1f else 0f
        fighterAlpha += (wantAlpha - fighterAlpha) * (1f - exp(-dt * 6f))
        val sway = sin(time * 0.25f) * 0.25f

        Matrix.perspectiveM(proj, 0, 32f, aspect, 0.3f, 200f)
        proj[8] = -shift // lens shift: puts the fighter where the layout wants it
        proj[9] = -shiftY
        Matrix.setLookAtM(view, 0, eye[0] + sway, eye[1], eye[2], target[0] + sway * 0.3f, target[1], target[2], 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // Fighter animation
        val now = System.currentTimeMillis()
        val ct = (now - p.celebrateAt) / 1000f
        val cheer = (now - p.cheerAt) / 1000f
        if (p.celebrateAt != lastCelebrate && ct < 0.1f) {
            lastCelebrate = p.celebrateAt
            repeat(46) {
                val a = rng.nextFloat() * 6.28f
                val sp = 1.5f + rng.nextFloat() * 2.5f
                particles.spawn(cos(a) * 0.4f, 0.6f + rng.nextFloat(), sin(a) * 0.4f, cos(a) * sp, 2f + rng.nextFloat() * 3f, sin(a) * sp,
                    1.2f, 0.12f, if (it % 2 == 0) skin.accent.toInt() else 0xFFFFD640.toInt(), 1f, grav = 6f)
            }
        }
        shownYaw += (p.dragYaw - shownYaw) * (1f - exp(-dt * 10f))
        anim.time = time; anim.walk = 0f; anim.moving = 0f; anim.recoil = 0f; anim.flash = 0f; anim.scale = 1f; anim.jump = 0f; anim.spin = 0f
        if (ct in 0f..0.9f) {
            anim.jump = sin(ct / 0.9f * PI.toFloat()) * 0.7f
            anim.spin = 360f * smooth(ct / 0.9f)
        } else if (cheer in 0f..0.5f) {
            anim.jump = sin(cheer / 0.5f * PI.toFloat()) * 0.35f
            anim.recoil = sin(cheer / 0.5f * PI.toFloat())
        }
        val facing = (PI / 2 - 0.62).toFloat() + sin(time * 0.6f) * 0.08f - Math.toRadians(shownYaw.toDouble()).toFloat()
        val drawFighter = fighterAlpha > 0.02f

        // ---- shadows
        shadow.begin()
        depth.use()
        depth.mat4("uViewProj", lightVP); depth.mat4("uLightVP", lightVP); depth.f("uOutline", 0f); depth.f("uSway", 0f); depth.f("uTime", time)
        if (drawFighter) models.draw(depth, def, p.skin, 0f, 0f, facing, anim, Pass.SHADOW)
        drawProps(depth, time, shadowPass = true)
        shadow.end()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.08f, 0.05f, 0.2f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)

        Toon.setup(lit, viewProj, lightVP, eye, time, shadow)
        // Sky (unlit vertex colours)
        lit.i("uMode", 3)
        sky.draw()
        lit.i("uMode", 0)
        lit.f("uRim", 0.05f)
        floor.draw()
        lit.f("uEmissive", 0.9f)
        lit.v4("uTint", 0.35f, 0.85f, 1f, 1f)
        floorGlow.draw()
        lit.v4("uTint", 1f, 1f, 1f, 1f)
        lit.f("uEmissive", 0f)
        lit.f("uRim", 0.2f)
        pillars.draw()
        lit.f("uEmissive", 0.85f + 0.15f * sin(time * 2f))
        neon.draw()
        lit.f("uEmissive", 0f)
        // Emblem on the back wall
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 5.6f, -12.4f)
        Matrix.rotateM(model, 0, sin(time * 0.5f) * 12f, 0f, 1f, 0f)
        lit.mat4("uModel", model)
        lit.f("uRim", 0.4f)
        emblem.draw()
        lit.f("uEmissive", 0.9f)
        emblemCore.draw()
        lit.f("uEmissive", 0f)
        lit.mat4("uModel", Toon.IDENTITY)
        // Pedestal
        lit.f("uRim", 0.25f)
        pedestal.draw(); pedestalTop.draw()
        val sc = skin.secondary.toInt()
        lit.v4("uTint", r(sc), g(sc), b(sc), 1f)
        lit.f("uEmissive", 0.55f + 0.2f * sin(time * 2.4f))
        pedestalRim.draw()
        lit.f("uEmissive", 0f)
        lit.v4("uTint", 1f, 1f, 1f, 1f)
        drawProps(lit, time, shadowPass = false)
        // Fighter
        if (drawFighter) {
            lit.f("uRim", 0.5f)
            if (p.locked) {
                lit.i("uMode", 2); lit.v4("uTint", 0.13f, 0.09f, 0.28f, 1f)
                models.draw(lit, def, p.skin, 0f, 0f, facing, anim, Pass.SILHOUETTE)
                lit.i("uMode", 0)
            } else models.draw(lit, def, p.skin, 0f, 0f, facing, anim, Pass.COLOR)
            lit.f("uFlash", 0f); lit.f("uEmissive", 0f)
        }
        // Outlines
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f)
        if (drawFighter) { lit.f("uOutline", FighterModels.OUTLINE); models.draw(lit, def, p.skin, 0f, 0f, facing, anim, Pass.OUTLINE) }
        lit.f("uOutline", 0.03f)
        lit.mat4("uModel", Toon.IDENTITY)
        pedestal.draw()
        pillars.draw()
        drawProps(lit, time, shadowPass = true)
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Motes and pillar-top glows
        particles.update(dt)
        if (rng.nextFloat() < 0.5f) {
            val a = rng.nextFloat() * 6.28f
            val d = 1.5f + rng.nextFloat() * 9f
            val c = if (rng.nextBoolean()) 0xFF60D8FF.toInt() else 0xFFFF7AE0.toInt()
            particles.spawn(cos(a) * d, 0.05f, sin(a) * d - 2f, 0f, 0.5f + rng.nextFloat() * 0.6f, 0f, 3.5f, 0.07f, c, 0.8f)
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        sprites.begin()
        for ((i, tp) in pillarTops.withIndex()) sprites.add(tp[0], tp[1], tp[2], 1.4f + 0.1f * sin(time * 2f + i), if (i % 2 == 0) 0.3f else 1f, if (i % 2 == 0) 0.8f else 0.4f, 1f, 0.5f)
        sprites.add(0f, 5.6f, -12f, 4.5f, 1f, 0.7f, 0.3f, 0.25f)
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Floating crates, bolts and Power Cells, kept to the sides so the fighter stays clear. */
    private val propSpots = arrayOf(
        floatArrayOf(-6.2f, 2.0f, -2.5f), floatArrayOf(-4.6f, 3.7f, -6.5f), floatArrayOf(-8.4f, 1.5f, -6.5f),
        floatArrayOf(6.0f, 2.3f, -3.0f), floatArrayOf(4.8f, 4.0f, -7.0f), floatArrayOf(8.6f, 1.7f, -6.0f),
        floatArrayOf(-3.6f, 5.0f, -10f), floatArrayOf(3.8f, 5.2f, -10.5f),
    )

    private fun drawProps(p: Program, time: Float, shadowPass: Boolean) {
        for ((i, spot) in propSpots.withIndex()) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, spot[0] + sin(time * 0.4f + i) * 0.2f, spot[1] + sin(time * 1.1f + i) * 0.25f, spot[2])
            Matrix.rotateM(model, 0, time * 22f + i * 40f, 0.3f, 1f, 0.2f)
            p.mat4("uModel", model)
            val mesh = when (i % 3) { 0 -> crate; 1 -> bolt; else -> cell }
            if (!shadowPass) p.f("uEmissive", if (i % 3 == 2) 0.6f else 0f)
            mesh.draw()
        }
        if (!shadowPass) p.f("uEmissive", 0f)
        p.mat4("uModel", Toon.IDENTITY)
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
    private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f
}

/** Full-screen 3D lobby behind the menus. Drag empty space to spin the fighter; tap to make them cheer. */
@SuppressLint("ViewConstructor")
class LobbyView(context: Context, val params: LobbyParams) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: GlThread? = null
    private var downX = 0f
    private var startYaw = 0f
    private var moved = false

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val renderer = object : GlRenderer {
            private var scene: LobbyScene? = null
            private var w = width
            private var h = height
            private var time = 0f
            override fun onCreated() { scene = LobbyScene() }
            override fun onSize(width: Int, height: Int) { w = width; h = height }
            override fun onFrame(dt: Float) { time += dt; scene?.render(w, h, params, time, dt) }
        }
        thread = GlThread(st, renderer, "lobby-gl").also { it.resize(width, height); it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.shutdown(); thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun setPaused(p: Boolean) { thread?.paused = p }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; startYaw = params.dragYaw; moved = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (abs(dx) > 12f) moved = true
                params.dragYaw = startYaw + dx / width * 540f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved) { params.cheerAt = System.currentTimeMillis(); performClick() }
                params.dragYaw = 0f
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
