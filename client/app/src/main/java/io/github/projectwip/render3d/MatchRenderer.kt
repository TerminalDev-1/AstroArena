package io.github.projectwip.render3d

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.SuperKind
import io.github.projectwip.gl.GlRenderer
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import io.github.projectwip.gl.Shaders
import io.github.projectwip.match.HudChannel
import io.github.projectwip.match.MatchRunner
import io.github.projectwip.sim.Fighter
import io.github.projectwip.sim.GameEvent
import io.github.projectwip.sim.Phase
import io.github.projectwip.sim.ShotStyle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders a match in stylised 3D: tilted perspective camera, toon lighting with real-time shadows,
 * inked outlines, swaying bushes, animated coolant, glowing projectiles and particles.
 * Sim coordinates (x, y) map to world (x, 0, y).
 */
class MatchRenderer(
    private val runner: MatchRunner,
    private val hud: HudChannel,
    private val matchesPlayed: Int,
) : GlRenderer {
    private lateinit var lit: Program
    private lateinit var depth: Program
    /** The lit shader with a dissolve, used only for fighters that are mid-fade. */
    private lateinit var litFade: Program
    private lateinit var water: Program
    private lateinit var sprite: Program
    private lateinit var models: FighterModels
    private lateinit var arena: ArenaModel
    private lateinit var sprites: SpriteBatch
    private val particles = Particles3D()

    private lateinit var sphere: Mesh
    private lateinit var octa: Mesh
    private lateinit var disc: Mesh
    private lateinit var ring: Mesh
    private lateinit var dashRing: Mesh
    private lateinit var rect: Mesh
    private lateinit var arrow: Mesh
    private lateinit var crate: Mesh
    private lateinit var crateCore: Mesh
    private lateinit var cell: Mesh
    private lateinit var cellCaps: Mesh
    private lateinit var stormWall: Mesh
    private lateinit var stormFloor: Mesh
    private val crateHitAt = HashMap<Int, Float>()
    private val sectors = HashMap<Int, Mesh>()

    private lateinit var shadow: ShadowMap
    private val eye = FloatArray(3)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val model = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val reveal = FloatArray(9)

    private var width = 1
    private var height = 1
    private var aspect = 1.6f
    private var camX = Float.NaN
    private var camZ = 0f
    private var lookX = 0f
    private var lookZ = 0f
    private var eyeX = 0f; private var eyeY = 0f; private var eyeZ = 0f
    private var shake = 0f
    private var time = 0f
    private val rng = Random(11)

    private val anim = FighterAnim()
    private val shownFacing = FloatArray(io.github.projectwip.match.HudSnapshot.MAX)
    private val shownMoving = FloatArray(io.github.projectwip.match.HudSnapshot.MAX)
    /** 0..1 how solid each fighter is drawn: eases toward what the player's team can see, so cover fades rather than pops. */
    private val shownVis = FloatArray(io.github.projectwip.match.HudSnapshot.MAX)

    private var fpsFrames = 0
    private var fpsTime = 0f
    private var fps = 0
    private val perfLog = Log.isLoggable("Perf", Log.DEBUG)
    private var simWorst = 0f

    private val match get() = runner.match
    private val world get() = runner.match.world

    override fun onCreated() {
        lit = Program(Shaders.LIT_VS, Shaders.LIT_FS, "lit")
        depth = Program(Shaders.LIT_VS, Shaders.DEPTH_FS, "depth")
        litFade = Program(Shaders.LIT_VS, Shaders.LIT_FADE_FS, "lit-fade")
        water = Program(Shaders.LIT_VS, Shaders.WATER_FS, "water")
        sprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "sprite")
        models = FighterModels()
        val t0 = System.nanoTime()
        arena = ArenaModel(world.arena)
        Log.i("MatchRenderer", "arena built in ${(System.nanoTime() - t0) / 1_000_000} ms")
        sprites = SpriteBatch()
        sphere = MeshBuilder().apply { sphere(1f, 10, 14) }.build()
        octa = MeshBuilder().apply { ellipsoid(1f, 1f, 1f, 2, 4) }.build()
        disc = MeshBuilder().apply { ring(0f, 1f, 40) }.build()
        ring = MeshBuilder().apply { ring(0.82f, 1f, 48) }.build()
        dashRing = MeshBuilder().apply { for (k in 0 until 8) ring(0.9f, 1f, 6, k * 45f, k * 45f + 28f) }.build()
        rect = MeshBuilder().apply { groundQuad(0f, -0.5f, 1f, 0.5f) }.build()
        arrow = MeshBuilder().apply { with { rotate(180f, 1f, 0f, 0f); cylinder(0.22f, 0.36f, 12, topRadius = 0f) } }.build()
        // Spark Crate: banded metal box with a glowing energy core showing through the slats.
        crate = MeshBuilder().apply {
            color(0.93f, 0.55f, 0.18f); with { translate(0f, 0.42f, 0f); roundedBox(0.86f, 0.84f, 0.86f, 0.1f, 2) }
            color(0.3f, 0.22f, 0.4f)
            for (yy in listOf(0.16f, 0.68f)) with { translate(0f, yy, 0f); roundedBox(0.9f, 0.1f, 0.9f, 0.04f, 1) }
            with { translate(0f, 0.86f, 0f); roundedBox(0.7f, 0.06f, 0.7f, 0.03f, 1) }
        }.build()
        crateCore = MeshBuilder().apply {
            color(1f, 0.92f, 0.35f)
            for (side in listOf(-1f, 1f)) {
                with { translate(0.44f * side, 0.42f, 0f); box(0.03f, 0.32f, 0.5f) }
                with { translate(0f, 0.42f, 0.44f * side); box(0.5f, 0.32f, 0.03f) }
            }
        }.build()
        // Power Cell: a chunky glowing battery.
        cell = MeshBuilder().apply { color(1f, 0.85f, 0.25f); cylinder(0.16f, 0.36f, 14) }.build()
        cellCaps = MeshBuilder().apply {
            color(0.22f, 0.16f, 0.36f)
            with { translate(0f, 0.2f, 0f); cylinder(0.17f, 0.07f, 14) }
            with { translate(0f, -0.2f, 0f); cylinder(0.17f, 0.07f, 14) }
            with { translate(0f, 0.26f, 0f); cylinder(0.06f, 0.06f, 10) }
        }.build()
        // Static Storm: an open cylinder wall (unit radius/height) and a ground shadow outside it.
        stormWall = MeshBuilder().apply { color(1f, 1f, 1f); with { translate(0f, 0.5f, 0f); cylinder(1f, 1f, 72, caps = false) } }.build()
        stormFloor = MeshBuilder().apply { color(1f, 1f, 1f); ring(1f, 6f, 72) }.build()
        shadow = ShadowMap(SHADOW_SIZE)
        for (f in world.fighters) { shownFacing[f.id] = f.facing; shownVis[f.id] = 1f }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    }

    override fun onSize(width: Int, height: Int) {
        this.width = width; this.height = height
        aspect = width.toFloat() / height
        Matrix.perspectiveM(proj, 0, FOV, aspect, 1f, 120f)
    }

    // ------------------------------------------------------------------ frame

    override fun onFrame(dt: Float) {
        time += dt
        fpsFrames++; fpsTime += dt
        if (fpsTime >= 0.5f) { fps = (fpsFrames / fpsTime).toInt(); fpsFrames = 0; fpsTime = 0f }

        val s0 = System.nanoTime()
        val alpha = runner.update(dt)
        if (perfLog) {
            val simMs = (System.nanoTime() - s0) / 1e6f
            if (simMs > simWorst) simWorst = simMs
            if (fpsTime == 0f) { Log.i("Perf", "sim worst=${"%.1f".format(simWorst)}ms"); simWorst = 0f }
        }
        for (e in runner.frameEvents) onEvent(e)
        particles.update(dt)
        updateFighterAnims(dt)
        updateCamera(dt, alpha)

        renderShadows(alpha)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.15f, 0.11f, 0.3f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        renderScene(alpha)
        publishHud(alpha)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun updateCamera(dt: Float, alpha: Float) {
        val p = match.player
        val inp = runner.input
        val aiming = inp.aimingAttack || inp.aimingSuper
        // The look-ahead while aiming eases in and out...
        val ka = 1f - exp(-dt * 8f)
        lookX += ((if (aiming) inp.aimX * 1.5f else 0f) - lookX) * ka
        lookZ += ((if (aiming) inp.aimY * 1.1f else 0f) - lookZ) * ka
        val tx = lerp(p.prevX, p.x, alpha) + lookX
        val tz = lerp(p.prevY, p.y, alpha) + lookZ
        val dist = distance()
        // ...but the camera itself is locked to you: a soft follow made your own movement feel late.
        // (Stiff rather than rigid, so a respawn glides across instead of cutting.)
        if (camX.isNaN()) { camX = tx; camZ = tz }
        val k = 1f - exp(-dt * 40f)
        camX += (tx - camX) * k
        camZ += (tz - camZ) * k
        shake = max(0f, shake - dt)
        val sx = if (shake > 0f) (rng.nextFloat() - 0.5f) * shake * 0.9f else 0f
        val sz = if (shake > 0f) (rng.nextFloat() - 0.5f) * shake * 0.9f else 0f
        val pitch = Math.toRadians(PITCH.toDouble())
        eyeX = camX + sx
        eyeY = (dist * sin(pitch)).toFloat()
        eyeZ = camZ + (dist * cos(pitch)).toFloat() + sz
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, camX + sx, 0f, camZ + sz, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // Shadow camera follows the view target.
        Matrix.setLookAtM(lightView, 0, camX - LIGHT[0] * 30f, -LIGHT[1] * 30f, camZ - LIGHT[2] * 30f, camX, 0f, camZ, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -20f, 20f, -20f, 20f, 1f, 80f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    /** Same distance on every device: equal vertical view for fairness; wider screens just see more sideways. */
    private fun distance() = CAMERA_DISTANCE

    // ------------------------------------------------------------------ passes

    private fun renderShadows(alpha: Float) {
        shadow.begin()
        depth.use()
        depth.mat4("uViewProj", lightVP)
        depth.mat4("uLightVP", lightVP)
        depth.f("uOutline", 0f)
        depth.f("uTime", time)
        depth.f("uSway", 0f)
        depth.mat4("uModel", identity)
        arena.solids.draw()
        depth.f("uSway", 1f)
        arena.grass.draw()
        depth.f("uSway", 0f)
        drawCrates(depth, shadowPass = true)
        forEachSolidFighter(alpha) { f, x, z, facing -> models.draw(depth, f.def, f.skin, x, z, facing, anim, Pass.SHADOW) }
        shadow.end()
    }

    private fun setupLit(p: Program) {
        eye[0] = eyeX; eye[1] = eyeY; eye[2] = eyeZ
        Toon.setup(p, viewProj, lightVP, eye, time, shadow)
    }

    private fun renderScene(alpha: Float) {
        setupLit(lit)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)

        // Environment
        lit.f("uRim", 0.08f)
        arena.ground.draw()
        lit.f("uRim", 0.25f)
        arena.solids.draw()
        drawCrates(lit, shadowPass = false)
        drawCells()

        // X-ray silhouettes: allies hidden behind walls still show as a tinted shape.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthFunc(GLES30.GL_GREATER)
        GLES30.glDepthMask(false)
        lit.i("uMode", 2)
        forEachSolidFighter(alpha) { f, x, z, facing ->
            if (f.team != match.player.team) return@forEachSolidFighter
            if (f === match.player) lit.v4("uTint", 0.36f, 1f, 0.48f, 0.55f) else lit.v4("uTint", 0.25f, 0.7f, 1f, 0.45f)
            models.draw(lit, f.def, f.skin, x, z, facing, anim, Pass.SILHOUETTE)
        }
        lit.i("uMode", 0)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Fighters
        lit.f("uRim", 0.45f)
        forEachSolidFighter(alpha) { f, x, z, facing -> models.draw(lit, f.def, f.skin, x, z, facing, anim, Pass.COLOR) }
        lit.f("uFlash", 0f)
        lit.f("uEmissive", 0f)

        // Outlines (inverted hull)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", INK[0], INK[1], INK[2], 1f)
        lit.f("uOutline", 0.028f)
        lit.mat4("uModel", identity)
        arena.solids.draw()
        drawCrates(lit, shadowPass = false, outline = true)
        lit.f("uOutline", FighterModels.OUTLINE)
        forEachSolidFighter(alpha) { f, x, z, facing -> models.draw(lit, f.def, f.skin, x, z, facing, anim, Pass.OUTLINE) }
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        drawFadingFighters(alpha)

        // Projectiles (solid cores)
        drawProjectileCores(alpha)

        // Bushes (fade near friendly fighters)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        var k = 0
        for (f in world.fighters) {
            if (f.team != match.player.team || k >= 3) continue
            reveal[k * 3] = lerp(f.prevX, f.x, alpha); reveal[k * 3 + 1] = if (f.alive) 1f else 0f; reveal[k * 3 + 2] = lerp(f.prevY, f.y, alpha)
            k++
        }
        while (k < 3) { reveal[k * 3 + 1] = 0f; k++ }
        lit.v3a("uReveal", reveal, 3)
        lit.f("uRevealOn", 1f)
        lit.f("uSway", 1f)
        lit.f("uRim", 0.2f)
        lit.mat4("uModel", identity)
        lit.v4("uTint", 1f, 1f, 1f, 1f)
        arena.grass.draw()
        lit.f("uRevealOn", 0f)
        lit.f("uSway", 0f)

        // Coolant
        water.use()
        water.mat4("uViewProj", viewProj)
        water.mat4("uLightVP", lightVP)
        water.mat4("uModel", identity)
        water.f("uTime", time)
        water.f("uOutline", 0f)
        water.f("uSway", 0f)
        water.v3("uCamPos", eyeX, eyeY, eyeZ)
        water.v3("uLightDir", LIGHT[0], LIGHT[1], LIGHT[2])
        arena.water.draw()

        // Static Storm
        world.storm?.let { drawStorm(it) }

        // Ground decals
        setupLit(lit)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        lit.i("uMode", 1)
        drawDecals(alpha)
        lit.i("uMode", 0)

        // Shields
        lit.f("uEmissive", 0.6f)
        for (f in world.fighters) {
            if (f.shield <= 0f || !shown(f)) continue
            setModel(lerp(f.prevX, f.x, alpha), 0.7f * f.scale, lerp(f.prevY, f.y, alpha), 0.95f * f.scale, 0.95f * f.scale, 0.95f * f.scale)
            lit.v4("uTint", 0.55f, 0.9f, 1f, 0.22f + 0.06f * sin(time * 6f))
            sphere.draw()
        }
        lit.f("uEmissive", 0f)

        // Glow sprites
        drawSprites(alpha)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Solid enough to carry HUD markers (rings, bars, shields). */
    private fun shown(f: Fighter) = f.alive && shownVis[f.id] > 0.5f

    /** Fighters drawn fully solid (the normal case). Ones that are mid-fade are drawn by [drawFadingFighters]. */
    private inline fun forEachSolidFighter(alpha: Float, block: (Fighter, Float, Float, Float) -> Unit) {
        for (f in world.fighters) {
            if (!f.alive || shownVis[f.id] < 0.999f || !inView(f.x, f.y)) continue
            poseFor(f)
            block(f, lerp(f.prevX, f.x, alpha), lerp(f.prevY, f.y, alpha), shownFacing[f.id])
        }
    }

    /**
     * Whether a spot on the ground can be on screen (with room for tall things and their shadows). The arena is
     * several screens wide, so skipping fighters and crates outside this saves most of their draw calls.
     */
    private fun inView(x: Float, z: Float): Boolean {
        val dz = z - camZ
        return kotlin.math.abs(x - camX) < 10f + 8f * aspect && dz > -17f && dz < 11f
    }

    private fun poseFor(f: Fighter) {
        val i = f.id
        anim.walk = f.walkCycle * 0.9f
        anim.moving = shownMoving[i]
        anim.recoil = (1f - f.sinceAttack / 0.16f).coerceIn(0f, 1f)
        anim.flash = (f.hitFlash / 0.12f).coerceIn(0f, 1f) * 0.8f
        anim.time = time + i * 0.7f
        anim.jump = if (f.isDashing) 0.12f else 0f
        anim.scale = FIGHTER_SCALE * f.scale
    }

    /** Fighters slipping into or out of cover: colour and outline through the dissolve shader. */
    private fun drawFadingFighters(alpha: Float) {
        if (world.fighters.none { it.alive && shownVis[it.id] > 0.02f && shownVis[it.id] < 0.999f }) return
        setupLit(litFade)
        litFade.f("uRim", 0.45f)
        for (pass in 0..1) {
            if (pass == 1) {
                GLES30.glEnable(GLES30.GL_CULL_FACE)
                GLES30.glCullFace(GLES30.GL_FRONT)
                litFade.i("uMode", 1)
                litFade.f("uFlash", 0f); litFade.f("uEmissive", 0f)
                litFade.f("uOutline", FighterModels.OUTLINE)
            }
            for (f in world.fighters) {
                val v = shownVis[f.id]
                if (!f.alive || v <= 0.02f || v >= 0.999f || !inView(f.x, f.y)) continue
                poseFor(f)
                litFade.f("uDissolve", 1f - v)
                models.draw(litFade, f.def, f.skin, lerp(f.prevX, f.x, alpha), lerp(f.prevY, f.y, alpha), shownFacing[f.id], anim, if (pass == 0) Pass.COLOR else Pass.OUTLINE)
            }
        }
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        lit.use()
    }

    /** Smooth facing and movement amount once per frame (frame-rate independent). */
    private fun updateFighterAnims(dt: Float) {
        val k = 1f - exp(-dt * 22f)
        val km = 1f - exp(-dt * 12f)
        for (f in world.fighters) {
            val i = f.id
            var d = f.facing - shownFacing[i]
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            shownFacing[i] += d * k
            val speed = if (f.alive) (hypot(f.vx, f.vy) / f.def.moveSpeed).coerceIn(0f, 1f) else 0f
            shownMoving[i] += (speed - shownMoving[i]) * km
            // Appear quickly, slip away a little slower; a respawn starts solid.
            val vis = f.alive && world.isVisibleTo(f, match.player.team)
            shownVis[i] = if (!f.alive) 1f
                else if (vis) min(1f, shownVis[i] + dt / VIS_FADE_IN) else max(0f, shownVis[i] - dt / VIS_FADE_OUT)
            if (f.isDashing && rng.nextFloat() < 0.8f) {
                particles.spawn(f.x - f.dashDirX * 0.4f, 0.15f, f.y - f.dashDirY * 0.4f, -f.dashDirX, 0.6f, -f.dashDirY, 0.5f, 0.3f, 0xFFDCCFB4.toInt(), 0.5f, growth = 0.6f, add = false)
            }
        }
    }

    private fun drawCrates(p: Program, shadowPass: Boolean, outline: Boolean = false) {
        if (world.crateHp.isEmpty()) return
        val w = world.arena.width
        for (key in world.crateHp.keys) {
            val x = key % w + 0.5f
            val z = key / w + 0.5f
            if (!inView(x, z)) continue
            val hitAge = time - (crateHitAt[key] ?: -10f)
            val wob = if (hitAge < 0.25f) sin(hitAge * 60f) * 0.06f * (1f - hitAge / 0.25f) else 0f
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x + wob, 0f, z)
            Matrix.scaleM(model, 0, 1f + wob, 1f - wob, 1f + wob)
            p.mat4("uModel", model)
            if (!shadowPass && !outline) {
                p.v4("uTint", 1f, 1f, 1f, 1f)
                p.f("uFlash", if (hitAge < 0.1f) 0.5f else 0f)
            }
            crate.draw()
            if (!shadowPass && !outline) {
                p.f("uEmissive", 0.8f + 0.2f * sin(time * 4f + key))
                crateCore.draw()
                p.f("uEmissive", 0f)
                p.f("uFlash", 0f)
            }
        }
        p.mat4("uModel", identity)
    }

    private fun drawCells() {
        if (world.pickups.isEmpty()) return
        for (pk in world.pickups) {
            if (!inView(pk.x, pk.y)) continue
            val y = 0.55f + sin(time * 3f + pk.x) * 0.12f + (0.4f - pk.age).coerceAtLeast(0f) * 2f
            setModel(pk.x, y, pk.y, 1f, 1f, 1f, time * 90f)
            lit.v4("uTint", 1f, 1f, 1f, 1f)
            lit.f("uEmissive", 0.85f)
            cell.draw()
            lit.f("uEmissive", 0f)
            cellCaps.draw()
        }
        lit.mat4("uModel", identity)
    }

    private fun drawStorm(st: io.github.projectwip.sim.Storm) {
        setupLit(lit)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        lit.i("uMode", 1)
        // Darken everything outside the safe circle.
        setModel(st.cx, 0.06f, st.cy, st.radius, 1f, st.radius)
        lit.v4("uTint", 0.28f, 0.1f, 0.55f, 0.42f)
        stormFloor.draw()
        // Shimmering wall at the edge.
        val pulse = 0.22f + 0.06f * sin(time * 5f)
        setModel(st.cx, 0f, st.cy, st.radius, 3.2f, st.radius, time * 25f)
        lit.v4("uTint", 0.62f, 0.4f, 1f, pulse)
        stormWall.draw()
        setModel(st.cx, 0f, st.cy, st.radius, 0.6f, st.radius)
        lit.v4("uTint", 0.85f, 0.7f, 1f, 0.5f)
        stormWall.draw()
        lit.i("uMode", 0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Crackles along the edge near the camera.
        repeat(3) {
            val a = rng.nextFloat() * 6.283f
            val ex = st.cx + cos(a) * st.radius
            val ez = st.cy + sin(a) * st.radius
            if (hypot(ex - camX, ez - camZ) < 16f) {
                particles.spawn(ex, 0.2f + rng.nextFloat() * 2.5f, ez, 0f, 0.8f, 0f, 0.5f, 0.16f, 0xFFC08CFF.toInt(), 0.9f)
            }
        }
    }

    private fun setModel(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, yawDeg: Float = 0f) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (yawDeg != 0f) Matrix.rotateM(model, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        lit.mat4("uModel", model)
    }

    private fun drawDecals(alpha: Float) {
        val p = match.player
        for (f in world.fighters) {
            if (!shown(f)) continue
            val x = lerp(f.prevX, f.x, alpha)
            val z = lerp(f.prevY, f.y, alpha)
            val r = f.radius * 1.3f
            when {
                f === p -> {
                    lit.v4("uTint", 0.36f, 1f, 0.48f, 0.28f); setModel(x, 0.025f, z, r, 1f, r); disc.draw()
                    lit.v4("uTint", 0.36f, 1f, 0.48f, 0.95f)
                }
                f.team == p.team -> lit.v4("uTint", 0.25f, 0.71f, 1f, 0.9f)
                else -> lit.v4("uTint", 1f, 0.3f, 0.37f, 0.9f)
            }
            setModel(x, 0.03f, z, r, 1f, r)
            ring.draw()
            if (f.superReady) {
                lit.v4("uTint", 1f, 0.84f, 0.25f, 0.9f)
                setModel(x, 0.035f, z, r * 1.25f, 1f, r * 1.25f, time * 90f)
                dashRing.draw()
            }
        }

        // Auto-aim target marker
        runner.autoTarget?.let { t ->
            val x = lerp(t.prevX, t.x, alpha)
            val z = lerp(t.prevY, t.y, alpha)
            val pulse = 1f + 0.12f * sin(time * 9f)
            lit.v4("uTint", 1f, 0.85f, 0.25f, 0.85f)
            setModel(x, 0.04f, z, t.radius * 1.75f * pulse, 1f, t.radius * 1.75f * pulse, -time * 120f)
            dashRing.draw()
        }

        // ...or on the Spark Crate a tap would shoot.
        if (runner.autoCrate >= 0) {
            val x = runner.autoCrate % world.arena.width + 0.5f
            val z = runner.autoCrate / world.arena.width + 0.5f
            val pulse = 1f + 0.1f * sin(time * 9f)
            lit.v4("uTint", 1f, 0.85f, 0.25f, 0.85f)
            setModel(x, 0.04f, z, 0.85f * pulse, 1f, 0.85f * pulse, -time * 120f)
            dashRing.draw()
        }

        // Aim indicator
        val inp = runner.input
        if (p.alive && (inp.aimingAttack || inp.aimingSuper)) {
            val len = hypot(inp.aimX, inp.aimY)
            if (len > 0.01f) {
                val dx = inp.aimX / len
                val dz = inp.aimY / len
                val px = lerp(p.prevX, p.x, alpha)
                val pz = lerp(p.prevY, p.y, alpha)
                val yaw = -Math.toDegrees(atan2(dz, dx).toDouble()).toFloat()
                if (inp.aimingSuper) lit.v4("uTint", 1f, 0.84f, 0.25f, 0.42f) else lit.v4("uTint", 1f, 1f, 1f, 0.32f)
                val a = world.arena
                if (inp.aimingSuper) {
                    val s = p.def.superSpec
                    when (s.kind) {
                        SuperKind.VOLLEY -> { setModel(px, 0.05f, pz, s.range, 1f, s.range, yaw); sector(s.spreadDegrees + 8f).draw() }
                        SuperKind.PIERCE -> { val l = clip(a, px, pz, dx, dz, s.range); setModel(px, 0.05f, pz, l, 1f, s.radius * 3.2f, yaw); rect.draw() }
                        SuperKind.RAM -> { setModel(px, 0.05f, pz, s.range, 1f, p.radius * 2.2f, yaw); rect.draw() }
                    }
                } else {
                    val at = p.def.attack
                    if (at.shape == AttackShape.SPREAD) {
                        setModel(px, 0.05f, pz, at.range, 1f, at.range, yaw); sector(at.spreadDegrees + 8f).draw()
                    } else {
                        val l = clip(a, px, pz, dx, dz, at.range)
                        setModel(px, 0.05f, pz, l, 1f, if (at.shape == AttackShape.BURST) 0.55f else 0.34f, yaw); rect.draw()
                    }
                }
            }
        }

        // Bobbing arrow above the auto-aim target (lit, so it reads as a 3D object)
        val t = runner.autoTarget
        if (t != null || runner.autoCrate >= 0) {
            val ax = if (t != null) lerp(t.prevX, t.x, alpha) else runner.autoCrate % world.arena.width + 0.5f
            val az = if (t != null) lerp(t.prevY, t.y, alpha) else runner.autoCrate / world.arena.width + 0.5f
            val ay = if (t != null) headHeight(t.def.id) * t.scale + 0.9f else 1.7f
            lit.i("uMode", 0)
            lit.f("uEmissive", 0.7f)
            lit.v4("uTint", 1f, 0.8f, 0.2f, 1f)
            setModel(ax, ay + sin(time * 5f) * 0.12f, az, 1f, 1f, 1f, time * 120f)
            arrow.draw()
            lit.f("uEmissive", 0f)
            lit.i("uMode", 1)
        }
    }

    private fun clip(a: io.github.projectwip.sim.Arena, x: Float, z: Float, dx: Float, dz: Float, range: Float): Float {
        val hit = a.shotBlockedAt(x, z, x + dx * range, z + dz * range)
        return if (hit < 0f) range else max(0.5f, hit)
    }

    private fun sector(spread: Float): Mesh = sectors.getOrPut(spread.toInt()) { MeshBuilder().apply { sector(1f, spread.toInt().toFloat(), 28) }.build() }

    private fun drawProjectileCores(alpha: Float) {
        lit.f("uEmissive", 0.85f)
        lit.f("uRim", 0.2f)
        for (pr in world.projectiles) {
            val owner = world.fighter(pr.ownerId) ?: continue
            val skin = owner.def.skins[owner.skin]
            val x = lerp(pr.prevX, pr.x, alpha)
            val z = lerp(pr.prevY, pr.y, alpha)
            val yaw = -Math.toDegrees(atan2(pr.vy, pr.vx).toDouble()).toFloat()
            when (pr.style) {
                ShotStyle.SPARK, ShotStyle.VOLLEY -> {
                    tint(if (pr.style == ShotStyle.VOLLEY) 0xFFFFD640 else skin.accent)
                    setModel(x, 0.7f, z, pr.radius * 1.6f, pr.radius * 1.1f, pr.radius * 1.1f, yaw); sphere.draw()
                }
                ShotStyle.PELLET -> {
                    tint(skin.secondary)
                    setModel(x, 0.7f, z, pr.radius * 1.1f, pr.radius * 1.1f, pr.radius * 1.1f); sphere.draw()
                }
                ShotStyle.PRISM, ShotStyle.LANCE -> {
                    tint(skin.secondary)
                    val big = pr.style == ShotStyle.LANCE
                    setModel(x, 0.75f, z, if (big) 0.85f else 0.42f, pr.radius * 0.9f, pr.radius * 0.9f, yaw); octa.draw()
                }
            }
        }
        lit.f("uEmissive", 0f)
    }

    private fun tint(c: Long, a: Float = 1f) {
        val v = c.toInt()
        lit.v4("uTint", ((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, a)
    }

    private fun drawSprites(alpha: Float) {
        // Trails behind projectiles
        for (pr in world.projectiles) {
            val owner = world.fighter(pr.ownerId) ?: continue
            val c = colorOf(pr.style, owner)
            val x = lerp(pr.prevX, pr.x, alpha); val z = lerp(pr.prevY, pr.y, alpha)
            if (rng.nextFloat() < 0.9f) particles.spawn(x, 0.72f, z, 0f, 0.1f, 0f, 0.18f, pr.radius * 2.2f, c, 0.6f)
        }
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        // Camera basis for billboards
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        sprites.begin()
        for (pr in world.projectiles) {
            val owner = world.fighter(pr.ownerId) ?: continue
            val c = colorOf(pr.style, owner)
            val x = lerp(pr.prevX, pr.x, alpha); val z = lerp(pr.prevY, pr.y, alpha)
            val s = if (pr.style == ShotStyle.LANCE) 1.3f else pr.radius * 4.5f
            sprites.add(x, 0.72f, z, s, r(c), g(c), b(c), 0.85f)
        }
        for (pk in world.pickups) sprites.add(pk.x, 0.6f + sin(time * 3f + pk.x) * 0.12f, pk.y, 0.9f, 1f, 0.85f, 0.3f, 0.6f)
        for (l in arena.lamps) sprites.add(l[0], l[1], l[2], 1.1f + 0.05f * sin(time * 3f + l[0]), 1f, 0.9f, 0.55f, 0.55f)
        // Super-ready shimmer on the player
        val p = match.player
        if (p.alive && p.superReady) {
            repeat(1) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(p.x + cos(a) * 0.5f, 0.2f, p.y + sin(a) * 0.5f, 0f, 1.6f, 0f, 0.6f, 0.12f, 0xFFFFD640.toInt(), 0.9f)
            }
        }
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        sprites.begin()
        particles.emit(sprites, false)
        sprites.flush()
    }

    private fun colorOf(style: ShotStyle, f: Fighter): Int {
        val s = f.def.skins[f.skin]
        return when (style) {
            ShotStyle.VOLLEY -> 0xFFFFD640.toInt()
            ShotStyle.SPARK -> s.accent.toInt()
            else -> s.secondary.toInt()
        }
    }

    private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f

    // ------------------------------------------------------------------ events → effects

    private fun onEvent(e: GameEvent) {
        val pid = match.player.id
        when (e) {
            is GameEvent.Shot -> {
                val f = world.fighter(e.fighterId) ?: return
                val c = if (e.isSuper) 0xFFFFD640.toInt() else f.def.skins[f.skin].accent.toInt()
                val mx = e.x + e.dirX * 0.8f
                val mz = e.y + e.dirY * 0.8f
                particles.spawn(mx, 0.72f, mz, 0f, 0f, 0f, 0.09f, if (e.isSuper) 1.4f else 0.65f, c, 1f)
                repeat(if (e.isSuper) 12 else 5) {
                    val a = atan2(e.dirY, e.dirX) + (rng.nextFloat() - 0.5f) * 1.1f
                    val sp = 3f + rng.nextFloat() * 4f
                    particles.spawn(mx, 0.72f, mz, cos(a) * sp, 1f + rng.nextFloat() * 2f, sin(a) * sp, 0.25f, 0.09f, c, 1f, grav = 9f)
                }
            }
            is GameEvent.Hit -> {
                val t = world.fighter(e.targetId)
                val c = if (t?.team == match.player.team) 0xFFFF5A5A.toInt() else 0xFFFFE680.toInt()
                particles.spawn(e.x, 0.75f, e.y, 0f, 0f, 0f, 0.12f, if (e.isSuper) 1.3f else 0.8f, c, 1f)
                repeat(9) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 2f + rng.nextFloat() * 4f
                    particles.spawn(e.x, 0.75f, e.y, cos(a) * sp, 2f + rng.nextFloat() * 3f, sin(a) * sp, 0.4f, 0.08f, c, 1f, grav = 12f)
                }
                if (e.targetId == pid) shake = max(shake, 0.16f)
            }
            is GameEvent.Blocked -> particles.spawn(e.x, 0.7f, e.y, 0f, 0f, 0f, 0.3f, 1.2f, 0xFF8CE6FF.toInt(), 0.8f)
            is GameEvent.WallHit -> repeat(6) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(e.x, 0.7f, e.y, cos(a) * 2.5f, 1.5f + rng.nextFloat() * 2f, sin(a) * 2.5f, 0.35f, 0.07f, 0xFFF0E6C8.toInt(), 1f, grav = 10f)
            }
            is GameEvent.Ko -> {
                val v = world.fighter(e.victimId)
                val c = v?.def?.skins?.get(v.skin)?.primary?.toInt() ?: -1
                particles.spawn(e.x, 0.8f, e.y, 0f, 0f, 0f, 0.35f, 3.2f, 0xFFFFFFFF.toInt(), 0.9f)
                repeat(28) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 3f + rng.nextFloat() * 6f
                    particles.spawn(e.x, 0.8f, e.y, cos(a) * sp, 3f + rng.nextFloat() * 5f, sin(a) * sp, 0.8f + rng.nextFloat() * 0.4f, 0.14f, c, 1f, grav = 14f)
                }
                repeat(8) {
                    particles.spawn(e.x + rng.nextFloat() - 0.5f, 0.5f, e.y + rng.nextFloat() - 0.5f, 0f, 1.2f + rng.nextFloat(), 0f,
                        1.1f, 0.45f, 0xFF3C3250.toInt(), 0.55f, growth = 0.9f, add = false)
                }
                if (e.victimId == pid) shake = 0.35f else if (e.killerId == pid) shake = max(shake, 0.12f)
            }
            is GameEvent.Spawned -> {
                val f = world.fighter(e.fighterId) ?: return
                val c = if (f.team == match.player.team) 0xFF3FB6FF.toInt() else 0xFFFF4D5E.toInt()
                repeat(18) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(f.x + cos(a) * 0.45f, 0.1f, f.y + sin(a) * 0.45f, 0f, 2.5f + rng.nextFloat() * 2f, 0f, 0.7f, 0.13f, c, 1f)
                }
            }
            is GameEvent.Dash -> shake = max(shake, 0.1f)
            is GameEvent.CrateHit -> crateHitAt[e.ty * world.arena.width + e.tx] = time
            is GameEvent.CrateBroken -> {
                val x = e.tx + 0.5f; val z = e.ty + 0.5f
                particles.spawn(x, 0.6f, z, 0f, 0f, 0f, 0.3f, 2.2f, 0xFFFFE066.toInt(), 0.9f)
                repeat(22) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 2f + rng.nextFloat() * 4f
                    particles.spawn(x, 0.6f, z, cos(a) * sp, 3f + rng.nextFloat() * 4f, sin(a) * sp, 0.9f, 0.13f,
                        if (it % 3 == 0) 0xFFFFE066.toInt() else 0xFFEE8C2E.toInt(), 1f, grav = 14f)
                }
            }
            is GameEvent.CellPicked -> {
                particles.spawn(e.x, 0.7f, e.y, 0f, 0f, 0f, 0.35f, 1.6f, 0xFFFFE066.toInt(), 0.9f)
                repeat(14) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(e.x + cos(a) * 0.3f, 0.3f, e.y + sin(a) * 0.3f, cos(a) * 1.2f, 3f + rng.nextFloat() * 2f, sin(a) * 1.2f, 0.6f, 0.1f, 0xFFFFE066.toInt(), 1f)
                }
            }
            is GameEvent.StormHit -> {
                repeat(4) { particles.spawn(e.x + rng.nextFloat() - 0.5f, 0.4f + rng.nextFloat(), e.y + rng.nextFloat() - 0.5f, 0f, 1.5f, 0f, 0.4f, 0.12f, 0xFFC08CFF.toInt(), 1f) }
                if (e.targetId == pid) shake = max(shake, 0.08f)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ HUD hand-off

    private val v4 = FloatArray(4)
    private val o4 = FloatArray(4)

    private fun publishHud(alpha: Float) {
        val s = hud.back
        val w = world
        val p = match.player
        s.width = width; s.height = height
        System.arraycopy(viewProj, 0, s.viewProj, 0, 16)
        s.phase = w.phase; s.phaseTime = w.phaseTime; s.countdownSeconds = w.rules.countdownSeconds; s.timeLeft = w.timeLeft
        s.freeForAll = w.rules.freeForAll
        s.bossMode = w.rules.boss
        s.timesDown = p.deaths
        s.practice = w.rules.practice
        s.damage = p.damageDealt
        val giant = if (w.rules.boss) w.fighters.firstOrNull { it.team != p.team } else null
        s.bossHp = giant?.hp ?: 0; s.bossMaxHp = giant?.maxHp ?: 1; s.bossName = giant?.name
        s.aliveCount = w.aliveCount
        s.placement = if (p.placement > 0) p.placement else if (w.phase == Phase.ENDED && w.rules.freeForAll) 1 else 0
        s.stormElapsed = w.storm?.elapsed ?: -1f
        s.playerOutsideStorm = w.storm?.let { p.alive && !it.contains(p.x, p.y) } ?: false
        s.myScore = if (w.rules.freeForAll) 0 else w.score[p.team]
        s.theirScore = if (w.rules.freeForAll) 0 else w.score[1 - p.team]
        s.koTarget = w.rules.koTarget
        s.winningTeam = w.winningTeam; s.playerTeam = p.team
        s.playerAlive = p.alive; s.respawnTimer = p.respawnTimer; s.ammo = p.ammo; s.ammoMax = p.def.ammoMax; s.superCharge = p.superCharge
        s.autoTargetId = runner.autoTarget?.id ?: -1
        s.matchesPlayed = matchesPlayed
        s.fps = fps
        s.n = min(w.fighters.size, io.github.projectwip.match.HudSnapshot.MAX)
        for (i in 0 until s.n) {
            val f = w.fighters[i]
            s.ids[i] = f.id
            val vis = shown(f)
            var onScreen = false
            if (vis) {
                v4[0] = lerp(f.prevX, f.x, alpha); v4[1] = headHeight(f.def.id) * f.scale; v4[2] = lerp(f.prevY, f.y, alpha); v4[3] = 1f
                Matrix.multiplyMV(o4, 0, viewProj, 0, v4, 0)
                if (o4[3] > 0f) {
                    s.sx[i] = (o4[0] / o4[3] * 0.5f + 0.5f) * width
                    s.sy[i] = (1f - (o4[1] / o4[3] * 0.5f + 0.5f)) * height
                    onScreen = true
                }
            }
            s.visible[i] = vis && onScreen
            s.hp[i] = f.hp; s.maxHp[i] = f.maxHp
            s.relation[i] = if (f === p) 0 else if (f.team == p.team) 1 else 2
            s.names[i] = f.name
            s.superReady[i] = f.superReady
            s.cells[i] = f.cells
        }
        hud.publish()
    }

    private fun headHeight(id: FighterId) = FIGHTER_SCALE * when (id) {
        FighterId.JUNO -> 1.85f
        FighterId.BRAKK -> 1.8f
        FighterId.MIRA -> 2.0f
        FighterId.KITO -> 1.9f
        FighterId.PIP -> 1.6f
        FighterId.DOZER -> 1.85f
        FighterId.NOVA -> 2.3f
        FighterId.FENN -> 1.95f
        FighterId.VOLT -> 2.0f
        FighterId.ONYX -> 2.0f
        FighterId.AURA -> 2.05f
        FighterId.ZERO -> 1.9f
    }

    override fun onDestroyed() {}

    companion object {
        const val FOV = 36f
        const val CAMERA_DISTANCE = 18f
        /** Fighters are drawn a bit larger than their collision radius for readability (genre convention). */
        const val FIGHTER_SCALE = 1.25f
        const val PITCH = 57f
        const val SHADOW_SIZE = 2048
        const val VIS_FADE_IN = 0.12f
        const val VIS_FADE_OUT = 0.3f
        val LIGHT = Toon.LIGHT
        val INK = Toon.INK
    }
}
