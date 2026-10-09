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
/** [ROAD] is the Spark Road: a road off to one side of the lobby with every fighter standing along it. */
enum class LobbyShot { HOME, FIGHTER, BACKDROP, ROAD }

/** What the lobby shows. Written by the UI thread, read by the GL thread. */
class LobbyParams {
    @Volatile var fighter = FighterId.BYTE
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

    // The Spark Road (the ROAD shot). Stop 0 is the starting fighter, the rest follow in the order they unlock.
    /** A bit per stop: set once that stop's fighter is unlocked. */
    @Volatile var roadUnlocked = 1
    /** The stop the Credits are filling (-1 once the road is finished), and how full it is, 0..1. */
    @Volatile var roadNext = -1
    @Volatile var roadFill = 0f
    /** Where along the road the camera is, in stops. */
    @Volatile var roadScroll = 0f

    // Spark Capsule opening (see Capsule3D): the menu sets these, the GL thread animates from them.
    @Volatile var capsuleShown = false
    @Volatile var capsuleColor = 0
    @Volatile var capsuleKnockAt = 0L
    @Volatile var capsuleChargeAt = 0L
    /** 0 while the capsule is closed. */
    @Volatile var capsuleOpenAt = 0L
    /** When the capsule last split (0 = it hasn't), and how many capsules there are now. */
    @Volatile var capsuleSplitAt = 0L
    @Volatile var capsulePieces = 1
    /** How unstable the capsule is, 0..1: how often and how hard it glitches while it waits. */
    @Volatile var capsuleGlitch = 0f
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
    private val capsule = Capsule3D()
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

    // The Spark Road: the starting fighter, then every fighter in the order the road unlocks them.
    private val roadStops: List<FighterId> = listOf(FighterId.BYTE) + io.github.projectwip.data.SparkRoad.steps.map { it.fighter }
    private val roadGround: Mesh
    private val roadBand: Mesh
    private val roadDashes: Mesh
    private val roadLit: Mesh
    private val roadAnim = FighterAnim()
    private var wasRoad = false

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
                color(0.12f * (1 - t) + 0.03f * t, 0.4f * (1 - t) + 0.08f * t, 0.62f * (1 - t) + 0.18f * t)
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
            color(0.18f, 0.3f, 0.42f); ring(0f, 26f, 64)
            // Radial spokes and tile rings for a sense of depth.
            color(0.23f, 0.38f, 0.52f)
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
                color(0.28f, 0.46f, 0.62f)
                with { translate(x, 3.5f, z); roundedBox(1.0f, 7f, 1.0f, 0.18f, 2) }
                color(0.2f, 0.33f, 0.45f)
                with { translate(x, 0.3f, z); roundedBox(1.4f, 0.6f, 1.4f, 0.12f, 2) }
                pillarTops += floatArrayOf(x, 7.2f, z)
            }
            // Back wall arc
            color(0.14f, 0.25f, 0.36f)
            for (k in 0 until 12) {
                val a = Math.toRadians(-165.0 + k * (150.0 / 11))
                with { translate((cos(a) * 13.5).toFloat(), 4f, (sin(a) * 13.5).toFloat()); rotate(-Math.toDegrees(a).toFloat() - 90f, 0f, 1f, 0f); roundedBox(4.2f, 8f, 0.6f, 0.15f, 1) }
            }
        }.build()
        neon = MeshBuilder().apply {
            for (k in 0 until 7) {
                val a = Math.toRadians(-160.0 + k * (140.0 / 6))
                val x = (cos(a) * 11).toFloat(); val z = (sin(a) * 11).toFloat()
                if (k % 2 == 0) color(0.2f, 0.85f, 1f) else color(1f, 0.66f, 0.2f)
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
        pedestal = MeshBuilder().apply { color(0.26f, 0.47f, 0.66f); with { translate(0f, -0.2f, 0f); cylinder(1.25f, 0.4f, 40) } }.build()
        pedestalTop = MeshBuilder().apply {
            color(0.47f, 0.72f, 0.95f); with { translate(0f, 0.005f, 0f); cylinder(1.08f, 0.02f, 40) }
            color(0.62f, 0.55f, 1f); with { translate(0f, 0.02f, 0f); ring(0.55f, 0.62f, 40) }
        }.build()
        pedestalRim = MeshBuilder().apply { torus(1.17f, 0.06f, 48, 8) }.build()
        crate = MeshBuilder().apply {
            color(0.93f, 0.55f, 0.18f); roundedBox(0.7f, 0.7f, 0.7f, 0.09f, 2)
            color(0.24f, 0.31f, 0.4f); for (y in listOf(-0.22f, 0.22f)) with { translate(0f, y, 0f); roundedBox(0.74f, 0.08f, 0.74f, 0.03f, 1) }
        }.build()
        bolt = MeshBuilder().apply {
            color(0.61f, 0.9f, 1f); with { rotate(90f, 1f, 0f, 0f); cylinder(0.32f, 0.16f, 6) }
            color(0.17f, 0.36f, 0.54f); with { translate(0f, 0f, 0.0f); rotate(90f, 1f, 0f, 0f); cylinder(0.13f, 0.18f, 12) }
        }.build()
        cell = MeshBuilder().apply {
            color(1f, 0.85f, 0.25f); cylinder(0.16f, 0.36f, 14)
            color(0.18f, 0.27f, 0.36f); with { translate(0f, 0.2f, 0f); cylinder(0.17f, 0.07f, 14) }; with { translate(0f, -0.2f, 0f); cylinder(0.17f, 0.07f, 14) }
        }.build()

        val roadEnd = (roadStops.size - 1) * ROAD_SPACING
        roadGround = MeshBuilder().apply {
            color(0.13f, 0.23f, 0.33f); groundQuad(-16f, -12f, roadEnd + 16f, 10f, -0.03f)
            color(0.18f, 0.3f, 0.42f)
            for (k in -3..(roadEnd / 2f).toInt() + 3) groundQuad(k * 2f - 0.03f, -12f, k * 2f + 0.03f, 10f, -0.025f)
        }.build()
        roadBand = MeshBuilder().apply {
            color(0.08f, 0.15f, 0.22f); with { translate(roadEnd / 2f, 0f, 0f); box(roadEnd + 3.4f, 0.06f, 2.9f) }
            color(0.28f, 0.49f, 0.68f); with { translate(roadEnd / 2f, 0.04f, 0f); box(roadEnd + 3f, 0.06f, 2.5f) }
        }.build()
        roadDashes = MeshBuilder().apply {
            color(1f, 1f, 1f)
            var x = -1.2f
            while (x < roadEnd + 1.2f) { with { translate(x, 0.08f, 0f); box(0.5f, 0.012f, 0.09f) }; x += 1f }
        }.build()
        // One unit of lit road, stretched to however far the Credits have reached.
        roadLit = MeshBuilder().apply { color(1f, 1f, 1f); with { translate(0.5f, 0.078f, 0f); box(1f, 0.012f, 2.1f) } }.build()
    }

    private fun aimLight(x: Float, z: Float, reach: Float) {
        Matrix.setLookAtM(lightView, 0, x - Toon.LIGHT[0] * 14f, -Toon.LIGHT[1] * 14f, z - Toon.LIGHT[2] * 14f, x, 0f, z, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -reach, reach, -reach, reach, 1f, 40f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    private fun shotEye(p: LobbyParams): FloatArray = when (p.shot) {
        LobbyShot.HOME -> floatArrayOf(0f, 2.3f, 7.6f)
        LobbyShot.FIGHTER -> floatArrayOf(0.4f, 2.4f, 8.0f)
        LobbyShot.BACKDROP -> floatArrayOf(0f, 3.4f, 9.5f)
        LobbyShot.ROAD -> floatArrayOf(p.roadScroll * ROAD_SPACING, 3.3f, ROAD_Z + 9.6f)
    }

    private fun shotTarget(p: LobbyParams): FloatArray = when (p.shot) {
        LobbyShot.HOME -> floatArrayOf(0f, 1.2f, 0f)
        LobbyShot.FIGHTER -> floatArrayOf(0f, 0.95f, 0f)
        LobbyShot.BACKDROP -> floatArrayOf(0f, 3.6f, -8f)
        LobbyShot.ROAD -> floatArrayOf(p.roadScroll * ROAD_SPACING, 1.5f, ROAD_Z)
    }

    /** The stops near enough to the camera to be worth drawing. */
    private inline fun forEachRoadStop(block: (index: Int, id: FighterId, x: Float) -> Unit) {
        for ((i, id) in roadStops.withIndex()) {
            val x = i * ROAD_SPACING
            if (abs(x - target[0]) < 13f) block(i, id, x)
        }
    }

    private fun roadFighter(prog: Program, p: LobbyParams, i: Int, id: FighterId, x: Float, time: Float, pass: Pass) {
        val isNext = i == p.roadNext
        roadAnim.time = time + i * 1.7f; roadAnim.walk = 0f; roadAnim.moving = 0f; roadAnim.recoil = 0f; roadAnim.flash = 0f; roadAnim.spin = 0f
        roadAnim.scale = if (isNext) 1.12f else 1f
        roadAnim.jump = 0.1f + if (isNext) abs(sin(time * 2.2f)) * 0.1f else 0f
        models.draw(prog, Balance.fighter(id), 0, x, ROAD_Z, (PI / 2 - 0.3).toFloat() + sin(time * 0.6f + i) * 0.1f, roadAnim, pass)
    }

    /** The road itself: the ground it runs over, the band, how far it is lit, and a marker under every fighter. */
    private fun drawRoad(p: LobbyParams, time: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, ROAD_Z)
        lit.mat4("uModel", model)
        lit.f("uRim", 0.05f)
        roadGround.draw()
        roadBand.draw()
        lit.f("uEmissive", 0.5f)
        roadDashes.draw()
        // Lit as far as the Credits have reached: all the way to the last unlocked stop, and part of the way to the next.
        val reached = if (p.roadNext < 0) (roadStops.size - 1).toFloat() else (p.roadNext - 1 + p.roadFill.coerceIn(0f, 1f)).coerceAtLeast(0f)
        if (reached > 0f) {
            Matrix.scaleM(model, 0, reached * ROAD_SPACING, 1f, 1f)
            lit.mat4("uModel", model)
            lit.v4("uTint", 0.25f, 0.92f, 0.55f, 1f)
            lit.f("uEmissive", 0.75f + 0.15f * sin(time * 3f))
            roadLit.draw()
            lit.v4("uTint", 1f, 1f, 1f, 1f)
        }
        lit.f("uEmissive", 0f)
        lit.f("uRim", 0.25f)
        forEachRoadStop { i, id, x ->
            val unlocked = (p.roadUnlocked shr i) and 1 != 0
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, 0.1f, ROAD_Z)
            Matrix.scaleM(model, 0, 0.78f, 0.6f, 0.78f)
            lit.mat4("uModel", model)
            pedestal.draw(); pedestalTop.draw()
            // The rim is the fighter's rarity: bright once reached, dim before.
            val c = Balance.fighter(id).rarity.color.toInt()
            val glow = if (unlocked || i == p.roadNext) 1f else 0.35f
            lit.v4("uTint", r(c) * glow, g(c) * glow, b(c) * glow, 1f)
            lit.f("uEmissive", if (i == p.roadNext) 0.7f + 0.3f * sin(time * 4f) else if (unlocked) 0.6f else 0f)
            pedestalRim.draw()
            lit.f("uEmissive", 0f)
            lit.v4("uTint", 1f, 1f, 1f, 1f)
        }
        lit.mat4("uModel", Toon.IDENTITY)
    }

    fun render(width: Int, height: Int, p: LobbyParams, time: Float, dt: Float) {
        val def = Balance.fighter(p.fighter)
        val skin = def.skins[p.skin.coerceIn(0, def.skins.lastIndex)]
        val aspect = width.toFloat() / height

        // Camera glides between shots.
        val k = 1f - exp(-dt * 3.2f)
        val e = shotEye(p); val t = shotTarget(p)
        val road = p.shot == LobbyShot.ROAD
        // The road is a place of its own, a long way from the pedestal: the camera cuts to it rather than flying there.
        if (road != wasRoad) { wasRoad = road; for (i in 0..2) { eye[i] = e[i]; target[i] = t[i] } }
        for (i in 0..2) { eye[i] += (e[i] - eye[i]) * k; target[i] += (t[i] - target[i]) * k }
        if (road) aimLight(target[0], ROAD_Z, 9f) else aimLight(0f, 0f, 6f)
        val wantShift = if (p.shot == LobbyShot.BACKDROP || road) 0f else (p.fighterScreenX * 2f - 1f)
        shift += (wantShift - shift) * k
        val wantShiftY = if (p.shot == LobbyShot.FIGHTER) (1f - 2f * p.fighterScreenY) * 0.9f else 0f
        shiftY += (wantShiftY - shiftY) * k
        val wantAlpha = if (p.showFighter && p.shot != LobbyShot.BACKDROP && !road) 1f else 0f
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
        anim.time = time; anim.walk = 0f; anim.moving = 0f; anim.recoil = 0f; anim.flash = 0f; anim.scale = 1f; anim.jump = 0f; anim.spin = 0f; anim.throwL = 0f; anim.throwR = 0f; anim.swing = 0f
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
        if (road) forEachRoadStop { i, id, x -> roadFighter(depth, p, i, id, x, time, Pass.SHADOW) }
        else drawProps(depth, time, shadowPass = true)
        shadow.end()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.06f, 0.13f, 0.2f, 1f)
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
        if (road) {
            drawRoad(p, time)
            lit.f("uRim", 0.5f)
            forEachRoadStop { i, id, x ->
                if ((p.roadUnlocked shr i) and 1 != 0) roadFighter(lit, p, i, id, x, time, Pass.COLOR)
                else {
                    // Not unlocked yet: only its shape, dark.
                    lit.i("uMode", 2); lit.v4("uTint", 0.11f, 0.2f, 0.28f, 1f)
                    roadFighter(lit, p, i, id, x, time, Pass.SILHOUETTE)
                    lit.i("uMode", 0); lit.v4("uTint", 1f, 1f, 1f, 1f)
                }
            }
            lit.f("uFlash", 0f); lit.f("uEmissive", 0f)
        }
        // Fighter
        if (drawFighter) {
            lit.f("uRim", 0.5f)
            if (p.locked) {
                lit.i("uMode", 2); lit.v4("uTint", 0.11f, 0.2f, 0.28f, 1f)
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
        if (road) {
            lit.f("uOutline", FighterModels.OUTLINE)
            forEachRoadStop { i, id, x -> if ((p.roadUnlocked shr i) and 1 != 0) roadFighter(lit, p, i, id, x, time, Pass.OUTLINE) }
        }
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
        // A beacon over the fighter the road is filling.
        if (road && p.roadNext >= 0) sprites.add(p.roadNext * ROAD_SPACING, 1.4f, ROAD_Z, 3.4f + 0.3f * sin(time * 3f), 0.25f, 0.95f, 0.55f, 0.22f)
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        capsule.render(aspect, p, time, dt, lit, sprite, sprites)
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

    private companion object {
        /** Where the Spark Road runs (along X at this Z), and how far apart its stops are. */
        const val ROAD_Z = 40f
        const val ROAD_SPACING = 4.2f
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
