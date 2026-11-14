package io.github.projectwip.render3d

import io.github.projectwip.audio.Sound
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
import io.github.projectwip.sim.HazardKind
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
    /** The graphics preset: what is drawn and how finely, never what happens. */
    private val gfx: io.github.projectwip.gl.GraphicsProfile = io.github.projectwip.gl.GraphicsProfile.current,
) : GlRenderer {
    /** The match view's size on screen, which the HUD is laid out in (the buffer drawn may be smaller). */
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0
    private lateinit var lit: Program
    private lateinit var depth: Program
    /** The lit shader with a dissolve, used only for fighters that are mid-fade. */
    private lateinit var litFade: Program
    private lateinit var water: Program
    private lateinit var sprite: Program
    private lateinit var models: FighterModels
    private lateinit var arena: ArenaModel
    /** The match's own arena, and the Entity's platform while everyone is in its dimension (built when it is first needed). */
    private lateinit var homeModel: ArenaModel
    private var realmModel: ArenaModel? = null
    private lateinit var sprites: SpriteBatch
    private val particles = Particles3D()

    private lateinit var sphere: Mesh
    private lateinit var sword: Mesh
    private lateinit var octa: Mesh
    private lateinit var bit: Mesh
    /** A puffy star: what Spark throws. */
    private lateinit var starShot: Mesh
    /** A rocket lying along +X, one unit long: the body, and (drawn in a second colour) its nose cone, fins and nozzle. */
    private lateinit var rocketBody: Mesh
    private lateinit var rocketTrim: Mesh
    /** The same rocket standing on its nose, leaning a little: one stuck in the ground, or coming straight down. */
    private lateinit var rocketDownBody: Mesh
    private lateinit var rocketDownTrim: Mesh
    private lateinit var thinRing: Mesh
    /** An open-ended tube, one unit across and one high: a slice of a tempest's funnel. */
    private lateinit var funnel: Mesh
    /** Lightning a tempest brought down, still showing: x, z, when, from x, from z, seed, whose. */
    private val tempestBolts = ArrayList<FloatArray>()
    /** Swings of a blade still fading: x, z, yaw in degrees, reach, arc in degrees, when, charged (1 or 0), colour. */
    private val slashes = ArrayList<FloatArray>()
    /** 0..1: how far the arena has gone over to the look of a realm (the player is in one). */
    private var realmFade = 0f
    /** The light of the dimension being shown (Portal Run), eased toward the one the player is in. */
    private val dimLight = floatArrayOf(1f, 1f, 1f)
    private var dimWarp = 0f
    private var dimGlitch = 0f
    /** Dimension: seconds until the next glitch moment, and how long the current one has left. */
    private var glitchIn = 6f
    private var glitchLeft = 0f
    private var frameDt = 0f
    /** Dimension: the chained fighter's pose. */
    private val captiveAnim = FighterAnim()
    private val voidAnim = FighterAnim()
    private lateinit var disc: Mesh
    private lateinit var ring: Mesh
    private lateinit var dashRing: Mesh
    private lateinit var rect: Mesh
    private lateinit var arrow: Mesh
    private lateinit var crate: Mesh
    private lateinit var crateCore: Mesh
    private lateinit var cell: Mesh
    private lateinit var cellCaps: Mesh
    private lateinit var stormFloor: Mesh
    /** Dimension: the gate's ring, and an echo shard and the beam over one. */
    private lateinit var portalRing: Mesh
    private lateinit var shardGem: Mesh
    private lateinit var beam: Mesh
    /** One clump of the poison smoke: a few low, overlapping puffs, about two tiles across. */
    private lateinit var smokeClump: Mesh
    private val crateHitAt = HashMap<Int, Float>()
    private val sectors = HashMap<Int, Mesh>()

    /** Null on presets without shadows. */
    private var shadow: ShadowMap? = null
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
    /** How far the view has slid toward the Entity in its dimension. */
    private var frameX = 0f
    private var frameZ = 0f
    private var eyeX = 0f; private var eyeY = 0f; private var eyeZ = 0f
    private var shake = 0f
    /** Ground a hammer has cracked: x, z, how far the cracks reach, when it came down, and a seed for their shape. */
    private val quakes = ArrayList<FloatArray>()
    /** When each fighter last hurled a hammer, by id: it leaps and swings as it lets go. */
    private val hammerAt = HashMap<Int, Float>()
    /** When each fighter latched onto the back it is on, by id: its terminal has been open since then. */
    private val latchAt = HashMap<Int, Float>()
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
        homeModel = arena
        Log.i("MatchRenderer", "arena built in ${(System.nanoTime() - t0) / 1_000_000} ms")
        sprites = SpriteBatch(4096)
        sphere = MeshBuilder().apply { sphere(1f, 10, 14) }.build()
        // The Entity's sword, one unit long along +x from the hilt (scaled to its reach): a dark violet blade with a
        // pale edge, a guard and a grip.
        sword = MeshBuilder().apply {
            color(0.16f, 0.1f, 0.26f); push(); translate(-0.05f, 0f, 0f); box(0.12f, 0.035f, 0.035f); pop()
            color(0.55f, 0.35f, 0.95f); push(); translate(0.025f, 0f, 0f); box(0.03f, 0.05f, 0.26f); pop()
            color(0.62f, 0.5f, 0.95f); push(); translate(0.5f, 0f, 0f); box(0.92f, 0.03f, 0.11f); pop()
            color(1f, 1f, 1f); push(); translate(0.5f, 0f, 0.055f); box(0.92f, 0.035f, 0.02f); pop()
            push(); translate(0.5f, 0f, -0.055f); box(0.92f, 0.035f, 0.02f); pop()
            color(0.62f, 0.5f, 0.95f); push(); translate(0.975f, 0f, 0f); scale(1f, 0.4f, 1f); ellipsoid(0.06f, 0.06f, 0.06f, 4, 6); pop()
        }.build()
        octa = MeshBuilder().apply { ellipsoid(1f, 1f, 1f, 2, 4) }.build()
        bit = MeshBuilder().apply { box(1f, 1f, 1f) }.build()
        starShot = MeshBuilder().apply { puffyStar(1f, 0.5f) }.build()
        val body: MeshBuilder.() -> Unit = { with { rotate(-90f, 0f, 0f, 1f); cylinder(0.14f, 0.56f, 12) } }
        val trim: MeshBuilder.() -> Unit = {
            with { translate(0.42f, 0f, 0f); rotate(-90f, 0f, 0f, 1f); cylinder(0.14f, 0.28f, 12, topRadius = 0f) }
            with { translate(-0.33f, 0f, 0f); rotate(-90f, 0f, 0f, 1f); cylinder(0.07f, 0.1f, 10, topRadius = 0.11f) }
            with { translate(0.05f, 0f, 0f); rotate(-90f, 0f, 0f, 1f); cylinder(0.15f, 0.07f, 12) }
            with { translate(-0.2f, 0f, 0f); roundedBox(0.22f, 0.5f, 0.045f, 0.015f) }
            with { translate(-0.2f, 0f, 0f); roundedBox(0.22f, 0.045f, 0.5f, 0.015f) }
        }
        rocketBody = MeshBuilder().apply(body).build()
        rocketTrim = MeshBuilder().apply(trim).build()
        rocketDownBody = MeshBuilder().apply { with { rotate(9f, 1f, 0f, 0f); rotate(-90f, 0f, 0f, 1f); body() } }.build()
        rocketDownTrim = MeshBuilder().apply { with { rotate(9f, 1f, 0f, 0f); rotate(-90f, 0f, 0f, 1f); trim() } }.build()
        thinRing = MeshBuilder().apply { ring(0.94f, 1f, 48) }.build()
        funnel = MeshBuilder().apply { cylinder(1f, 1f, 18, caps = false) }.build()
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
        // The closing zone's poison smoke: a green ground haze outside it, and clumps of low smoke over that.
        smokeClump = MeshBuilder().apply {
            color(0.62f, 0.95f, 0.42f); ellipsoid(1f, 0.55f, 1f, 7, 10)
            color(0.5f, 0.86f, 0.36f); with { translate(0.62f, -0.08f, 0.3f); ellipsoid(0.68f, 0.42f, 0.68f, 6, 9) }
            color(0.7f, 1f, 0.5f); with { translate(-0.55f, -0.04f, 0.42f); ellipsoid(0.62f, 0.46f, 0.62f, 6, 9) }
            color(0.46f, 0.8f, 0.34f); with { translate(-0.2f, -0.1f, -0.66f); ellipsoid(0.7f, 0.4f, 0.7f, 6, 9) }
            color(0.66f, 0.98f, 0.46f); with { translate(0.35f, 0.18f, -0.25f); ellipsoid(0.5f, 0.4f, 0.5f, 6, 9) }
        }.build()
        stormFloor = MeshBuilder().apply { color(1f, 1f, 1f); ring(1f, 6f, 72) }.build()
        portalRing = MeshBuilder().apply { color(1f, 1f, 1f); torus(1.62f, 0.15f, 48, 10) }.build()
        shardGem = MeshBuilder().apply {
            color(1f, 1f, 1f); ellipsoid(0.26f, 0.62f, 0.26f, 2, 4)
            with { translate(0.26f, -0.2f, 0.05f); rotate(25f, 0f, 0f, 1f); ellipsoid(0.11f, 0.28f, 0.11f, 2, 4) }
            with { translate(-0.22f, -0.28f, -0.08f); rotate(-30f, 0f, 0f, 1f); ellipsoid(0.09f, 0.22f, 0.09f, 2, 4) }
        }.build()
        beam = MeshBuilder().apply { color(1f, 1f, 1f); with { translate(0f, 0.5f, 0f); cylinder(1f, 1f, 18, topRadius = 0.55f, caps = false) } }.build()
        shadow = if (gfx.shadowSize > 0) ShadowMap(gfx.shadowSize) else null
        particles.density = gfx.effects
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
        frameDt = dt
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
        syncArenaModel()
        stepHijack()
        particles.update(dt)
        updateFighterAnims(dt)
        updateCamera(dt, alpha)

        renderShadows(alpha)
        GLES30.glViewport(0, 0, width, height)
        run {
            // (The Entity's uprising darkens the sky toward the void's own colour, flickering when it glitches.)
            val g = if (dimGlitch > 0f && (time * 30f).toInt() % 2 == 0) 0.06f else 0f
            GLES30.glClearColor(lerp(0.15f, 0.035f + g, upDark), lerp(0.11f, 0.01f, upDark), lerp(0.3f, 0.09f + g, upDark), 1f)
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        renderScene(alpha)
        publishHud(alpha)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun updateCamera(dt: Float, alpha: Float) {
        // Pulled into the Entity, the player watches their team fight to free them: the camera is on one of them.
        val p = if (match.player.trapped) world.uprising?.members?.firstNotNullOfOrNull { id -> world.fighter(id)?.takeIf { it.alive } } ?: match.player else match.player
        val inp = runner.input
        val aiming = inp.aimingAttack || inp.aimingSuper
        // The look-ahead while aiming eases in and out...
        val ka = 1f - exp(-dt * 8f)
        lookX += ((if (aiming) inp.aimX * 1.5f else 0f) - lookX) * ka
        lookZ += ((if (aiming) inp.aimY * 1.1f else 0f) - lookZ) * ka
        // In the Entity's dimension the view slides (it never tilts) part of the way toward the Entity, so it and the
        // player are both on screen.
        val upr0 = world.uprising
        var fx = 0f; var fz = 0f
        if (upr0 != null && upr0.phase == io.github.projectwip.sim.UprisingPhase.SKY) {
            val ox = entityX - p.x; val oz = entityZ - 1.6f - p.y
            val od = hypot(ox, oz)
            if (od > 1e-3f) { val k = minOf(od * 0.45f, 3.2f) / od; fx = ox * k; fz = oz * k }
        }
        val kf = 1f - exp(-dt * 2.5f)
        frameX += (fx - frameX) * kf; frameZ += (fz - frameZ) * kf
        val tx = lerp(p.prevX, p.x, alpha) + lookX + frameX
        val tz = lerp(p.prevY, p.y, alpha) + lookZ + frameZ
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
        // The camera's look up into the sky (the user's idea): after the player's super, and now and then after a plain
        // attack, it drops low behind the fighter and turns straight up to watch the streak climb, then settles back.
        var w = 0f
        val px = lerp(p.prevX, p.x, alpha); val pz = lerp(p.prevY, p.y, alpha)
        val st = skyLookStreak
        val sl = time - skyLookAt
        if (st != null && sl >= 0f && sl <= skyLookLen) {
            val hold = skyLookLen * 0.62f
            w = smooth(when { sl < 0.35f -> sl / 0.35f; sl < hold -> 1f; else -> 1f - (sl - hold) / (skyLookLen - hold) })
            skyHead(st, sl, camAt)
            camUp[0] = st[0]; camUp[1] = 1.7f; camUp[2] = st[1] + 6.5f
        }
        // The Entity's hijack (the user's sequence): the same look up, following its comet into the sky and back down
        // into the fighter; then in close on them while it takes hold (the cutscene plays over that), then back out.
        val ht = time - hijackAt
        if (runner.hijack != 0) {
            if (ht < HIJACK_HIT) {
                w = smooth(ht / 0.4f)
                hijackComet(ht, px, pz, camAt)
                camUp[0] = px; camUp[1] = 1.7f; camUp[2] = pz + 6.5f
            } else {
                w = 1f
                val k = smooth((ht - HIJACK_HIT) / 0.45f)
                camUp[0] = px; camUp[1] = lerp(1.7f, 3.4f, k); camUp[2] = pz + lerp(6.5f, 4.6f, k)
                camAt[0] = px; camAt[1] = 1f; camAt[2] = pz
            }
        } else if (time - hijackBackAt < 0.8f) {
            w = 1f - smooth((time - hijackBackAt) / 0.8f)
            camUp[0] = px; camUp[1] = 3.4f; camUp[2] = pz + 4.6f
            camAt[0] = px; camAt[1] = 1f; camAt[2] = pz
        }
        // The Entity's uprising has the camera (smoothly): up at it as it rises, then over everyone gathered below
        // it and into its portal. In its dimension the fight has the game's own camera (the user's call: nothing tilted
        // up at the sky).
        val upr = world.uprising
        val upWant = if (upr != null && upr.active && upr.phase != io.github.projectwip.sim.UprisingPhase.RETURN &&
            upr.phase != io.github.projectwip.sim.UprisingPhase.SKY) 1f else 0f
        upCamW += (upWant - upCamW) * (1f - exp(-dt * 2.5f))
        if (upr != null && upCamW > 0.001f) {
            entityPoint(upr)
            val ex = entityAt[0]; val ey = entityAt[1]; val ez = entityAt[2]
            val head = ENTITY_SCALE * 1.4f
            when (upr.phase) {
                io.github.projectwip.sim.UprisingPhase.GAZE -> { upWantEye(px, 1.7f, pz + 6.5f); upWantAt(ex, ey + head, ez) }
                // The calling walk: behind the player as they walk to its circle, looking ahead at where they are going
                // (the user didn't want a shot tilted up at the sky here).
                io.github.projectwip.sim.UprisingPhase.MARCH -> { upWantEye(px, 3.6f, pz + 7f); upWantAt(lerp(px, upr.cx, 0.4f), 1.2f, lerp(pz, upr.cy, 0.4f) - 1.2f) }
                io.github.projectwip.sim.UprisingPhase.BOW -> ritualShot(upr, ex, ey, ez, head)
                io.github.projectwip.sim.UprisingPhase.ENTER -> { upWantEye(upr.cx, 3.2f, upr.cy + 7f); upWantAt(upr.cx, 1.4f, upr.cy) }
                else -> Unit
            }
            // (The first frame jumps straight there; after that it eases.)
            val k = if (upCamFresh) 1f else 1f - exp(-dt * 3f)
            upCamFresh = false
            for (i in 0..2) { upEye[i] += (upEyeWant[i] - upEye[i]) * k; upAt[i] += (upAtWant[i] - upAt[i]) * k }
            for (i in 0..2) { camUp[i] = lerp(camUp[i], upEye[i], if (w > 0f) upCamW else 1f); camAt[i] = lerp(camAt[i], upAt[i], if (w > 0f) upCamW else 1f) }
            w = maxOf(w, upCamW)
        } else upCamFresh = true
        lookUp = w
        val pitch = Math.toRadians(PITCH.toDouble())
        eyeX = lerp(camX + sx, camUp[0], w)
        eyeY = lerp((dist * sin(pitch)).toFloat(), camUp[1], w)
        eyeZ = lerp(camZ + (dist * cos(pitch)).toFloat() + sz, camUp[2], w)
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, lerp(camX + sx, camAt[0], w), lerp(0f, camAt[1], w), lerp(camZ + sz, camAt[2], w), 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // Shadow camera follows the view target.
        Matrix.setLookAtM(lightView, 0, camX - LIGHT[0] * 30f, -LIGHT[1] * 30f, camZ - LIGHT[2] * 30f, camX, 0f, camZ, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -20f, 20f, -20f, 20f, 1f, 80f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    /** Same distance on every device: equal vertical view for fairness; wider screens just see more sideways. */
    private fun distance() = CAMERA_DISTANCE
    /** When the player last fired a super (the camera's look up to the sky, see [updateCamera]). */
    private var skyLookAt = -100f
    /** How long that look lasts, how far it goes, and whether it is the silly wobbling one (after a plain attack). */
    private var skyLookLen = SKY_STREAK_SUPER
    /** The streak the camera is watching. */
    private var skyLookStreak: FloatArray? = null
    /** Where the camera goes and what it looks at while it looks up, and how far it has (0..1). */
    private val camUp = FloatArray(3)
    private val camAt = FloatArray(3)
    private var lookUp = 0f

    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /** Where a sky streak's head is [age] seconds after it was fired (the same path [addSkyStreaks] draws). */
    private fun skyHead(st: FloatArray, age: Float, out: FloatArray) {
        val big = st[3] >= 1f
        val sideAng = st[5] + st[4] * 1.5708f
        val x0 = st[0] + cos(sideAng) * 0.38f; val z0 = st[1] + sin(sideAng) * 0.38f; val y0 = 1.25f
        val x1 = x0 + cos(sideAng) * 0.8f; val z1 = z0 - (if (big) 2.5f else 2f); val y1 = if (big) 16f else 8f
        val u = (age / (if (big) 0.55f else 0.28f)).coerceIn(0f, 1f)
        out[0] = x0 + (x1 - x0) * u; out[1] = y0 + (y1 - y0) * u; out[2] = z0 + (z1 - z0) * u
    }

    // ------------------------------------------------------------------ the Entity's uprising

    /** Draws whichever arena the match is in: its own, or the Entity's platform in its dimension. */
    private fun syncArenaModel() {
        val a = world.arena
        if (arena.arena === a) return
        arena = if (a === homeModel.arena) homeModel
            else realmModel?.takeIf { it.arena === a } ?: ArenaModel(a, realm = true).also { realmModel = it }
        // (Straight there: the camera doesn't glide across from where it was.)
        camX = Float.NaN
    }

    /** How far the world has gone over to the Entity's dark (0..1), the camera's share of its shots, and its poses. */
    private var upDark = 0f
    private var upCamW = 0f
    private var upCamFresh = true
    private val upEye = FloatArray(3)
    private val upAt = FloatArray(3)
    private val upEyeWant = FloatArray(3)
    private val upAtWant = FloatArray(3)
    private val entityAt = FloatArray(3)
    private val entityAnim = FighterAnim()
    /**
     * Its giant sword (its main attack, the user's): held point-down at its side, and in a spin swept flat round it
     * at the height of a fighter, the blade exactly as long as the reach it cuts (`ENTITY_SPIN_RADIUS`).
     */
    private val swordM = FloatArray(16)
    private fun swordAngle(u: io.github.projectwip.sim.Uprising): Float {
        val wind = io.github.projectwip.data.Balance.ENTITY_SPIN_WIND
        if (u.spinT < 0f) return 1.5707964f
        // Drawn back as it winds up, then round and round.
        return if (u.spinT < wind) 1.5707964f + 0.7f * smooth(u.spinT / wind) else 1.5707964f + 0.7f - (u.spinT - wind) * 13f
    }
    private fun drawSword(u: io.github.projectwip.sim.Uprising, pass: Pass) {
        val r = io.github.projectwip.data.Balance.ENTITY_SPIN_RADIUS
        Matrix.setIdentityM(swordM, 0)
        if (u.spinT >= 0f) {
            val wind = u.spinT < io.github.projectwip.data.Balance.ENTITY_SPIN_WIND
            Matrix.translateM(swordM, 0, entityX, if (wind) lerp(2.2f, 1.05f, smooth(u.spinT / io.github.projectwip.data.Balance.ENTITY_SPIN_WIND)) else 1.05f, entityZ)
            Matrix.rotateM(swordM, 0, swordAngle(u) * 57.29578f, 0f, 1f, 0f)
        } else {
            // At its side, point down toward the floor.
            Matrix.translateM(swordM, 0, entityX + 1.9f, entityAt[1] + 1.2f, entityZ + 0.6f)
            Matrix.rotateM(swordM, 0, -72f, 0f, 0f, 1f)
            Matrix.rotateM(swordM, 0, 8f * sin(time * 1.3f), 1f, 0f, 0f)
        }
        Matrix.scaleM(swordM, 0, r, r, r)
        if (pass == Pass.COLOR) { lit.f("uRim", 0.9f); lit.f("uGloss", 0.8f); lit.f("uEmissive", 0.4f); lit.v4("uTint", 1f, 1f, 1f, 1f) }
        lit.mat4("uModel", swordM)
        sword.draw()
        if (pass == Pass.COLOR) { lit.f("uRim", 0.45f); lit.f("uGloss", 0f); lit.f("uEmissive", 0f) }
        lit.mat4("uModel", identity)
    }

    /** Fighters it has pulled in: when each was taken (they fly up into its chest), drawn pressed inside it. */
    private val trapAt = HashMap<Int, Float>()
    private val trapAnim = FighterAnim()

    /**
     * Where trapped fighter [f] is drawn at this moment ([out]: x, height, z, scale): flying from where the flare took
     * them up into the Entity's chest, then held there, side by side with anyone else it has, drifting.
     */
    private fun trappedPlace(f: io.github.projectwip.sim.Fighter, k: Int, n: Int, big: Float, out: FloatArray) {
        val since = time - (trapAt[f.id] ?: -10f)
        val pull = smooth(since / 1.4f)
        val cx = entityAt[0] + (k - (n - 1) / 2f) * big * 0.42f + 0.06f * sin(time * 1.3f + k)
        val cy = entityAt[1] + big * 0.95f + 0.08f * sin(time * 1.7f + k * 2f)
        val cz = entityAt[2] + big * 0.42f
        out[0] = lerp(f.x, cx, pull)
        // (An arc: up first, then in.)
        out[1] = lerp(0f, cy, smooth(since / 0.9f))
        out[2] = lerp(f.y, cz, pull)
        out[3] = lerp(1f, 0.72f, pull)
    }
    private val trapPlace = FloatArray(4)

    /** Everyone it has pulled in, inside it: held at its chest, struggling, lit with its blue. */
    private fun drawTrapped(u: io.github.projectwip.sim.Uprising, pass: Pass) {
        if (u.phase != io.github.projectwip.sim.UprisingPhase.SKY) return
        val held = u.members.mapNotNull { id -> world.fighter(id)?.takeIf { it.trapped } }
        if (held.isEmpty()) return
        val big = entityScale(u)
        for ((k, f) in held.withIndex()) {
            trappedPlace(f, k, held.size, big, trapPlace)
            val since = time - (trapAt[f.id] ?: -10f)
            trapAnim.time = time; trapAnim.walk = time * (if (since < 1.4f) 14f else 3f); trapAnim.moving = if (since < 1.4f) 1f else 0.35f
            trapAnim.recoil = 0f; trapAnim.spin = 0f; trapAnim.bow = -12f; trapAnim.talk = 0f
            trapAnim.jump = trapPlace[1]; trapAnim.scale = trapPlace[3]
            trapAnim.flash = 0.35f + 0.15f * sin(time * 5f + k)
            if (pass == Pass.COLOR) { lit.f("uRim", 1f); lit.f("uEmissive", 0.25f); lit.v4("uTint", 0.55f, 0.75f, 1.15f, 1f) }
            models.draw(lit, f.def, f.skin, trapPlace[0], trapPlace[2], 1.5707964f, trapAnim, pass)
        }
        if (pass == Pass.COLOR) { lit.f("uRim", 0.45f); lit.f("uEmissive", 0f); lit.f("uFlash", 0f); lit.v4("uTint", 1f, 1f, 1f, 1f); lit.mat4("uModel", identity) }
    }
    private var upGlitchIn = 1f
    private var upGlitchLeft = 0f

    private fun upWantEye(x: Float, y: Float, z: Float) { upEyeWant[0] = x; upEyeWant[1] = y; upEyeWant[2] = z }
    private fun upWantAt(x: Float, y: Float, z: Float) { upAtWant[0] = x; upAtWant[1] = y; upAtWant[2] = z }

    /**
     * Where the Entity stands in the sky (its feet): behind the middle of the arena, high up, rising out of the dark
     * as it comes (the first seconds), and in its own sky over everyone, nearer and bigger, the only thing up there.
     */
    private fun entityPoint(u: io.github.projectwip.sim.Uprising) {
        if (u.phase == io.github.projectwip.sim.UprisingPhase.SKY) {
            // In its dimension it hovers over its platform, right over the shadow that attacks hit, bobbing.
            entityAt[0] = entityX; entityAt[2] = entityZ
            entityAt[1] = ENTITY_SKY_LIFT + 0.35f * sin(time * 1.1f)
            return
        }
        val rise = if (u.phase == io.github.projectwip.sim.UprisingPhase.GAZE) smooth(u.t / 3.5f) else 1f
        entityAt[0] = u.cx
        entityAt[1] = lerp(-24f, 5f, rise) + 0.4f * sin(time * 0.9f)
        entityAt[2] = u.cy - 26f
    }

    /** The shot of the ritual the camera was last on (a new one cuts straight to it). */
    private var ritualCue = -1

    /**
     * Where the camera is while the Entity speaks to them all (the user's ask): a slow push in on whoever the line is
     * for, cutting with each line: its face, the player's face, another fighter's face, or everyone.
     */
    private fun ritualShot(u: io.github.projectwip.sim.Uprising, ex: Float, ey: Float, ez: Float, head: Float) {
        val cue = io.github.projectwip.match.UprisingScript.cue(u.phase, u.t, u.bowLength, u.beaten, match.player.name)
        val push = if (cue == null) 0f else smooth(cue.since / cue.length.coerceAtLeast(0.5f))
        if (cue != null && cue.id != ritualCue) { ritualCue = cue.id; upCamFresh = true }
        fun face(f: Fighter) {
            // In front of their face, a little below it, looking up into it as they bow; pushing in.
            val a = shownFacing[f.id]
            val hx = f.x + cos(a) * 0.32f; val hz = f.y + sin(a) * 0.32f
            val d = lerp(2.7f, 1.9f, push)
            upWantEye(hx + cos(a) * d + sin(a) * 0.8f, 0.85f, hz + sin(a) * d - cos(a) * 0.8f)
            upWantAt(hx, 1.2f, hz)
        }
        when (cue?.shot) {
            io.github.projectwip.data.Lore.RitualShot.ENTITY -> {
                // Its face, filling the screen, from over the arena: it is talking to whoever is looking.
                val d = lerp(19f, 13f, push)
                upWantEye(ex, ey + head * 0.85f, ez + d); upWantAt(ex, ey + head * 0.88f, ez)
            }
            io.github.projectwip.data.Lore.RitualShot.PLAYER -> face(match.player)
            io.github.projectwip.data.Lore.RitualShot.KNEEL -> {
                // Low, from the open side of the circle, drifting round it: everyone going down before the Entity
                // looming behind them.
                val a = lerp(-0.35f, 0.35f, push)
                val d = lerp(12.5f, 11f, push)
                upWantEye(u.cx + sin(a) * d, lerp(3.6f, 3.1f, push), u.cy + cos(a) * d)
                upWantAt(u.cx, 1.1f, u.cy - 0.8f)
            }
            io.github.projectwip.data.Lore.RitualShot.OTHER -> {
                val others = u.members.mapNotNull { world.fighter(it) }.filter { it !== match.player && it.alive }
                if (others.isEmpty()) face(match.player) else face(others[cue.index.coerceAtLeast(0) % others.size])
            }
            else -> {
                val d = lerp(13f, 10f, push)
                upWantEye(u.cx, 6.5f, u.cy + d); upWantAt(u.cx, 4f, u.cy - 3f)
            }
        }
    }

    /** How big the Entity is drawn: enormous behind the arena, smaller (but still a giant) over its own platform. */
    private fun entityScale(u: io.github.projectwip.sim.Uprising) =
        if (u.phase == io.github.projectwip.sim.UprisingPhase.SKY) ENTITY_SKY_SCALE else ENTITY_SCALE

    /** Where the Entity is drawn in its dimension: eased after the simulation's point, so its drift is smooth. */
    private var entityPlaced = false
    private var entityX = 0f
    private var entityZ = 0f
    /** When something last hit it (it flashes). */
    private var entityHitAt = -10f

    /**
     * The Entity's shadow in its dimension: the circle that attacks hit (`Balance.UPRISING_ENTITY_RADIUS`, exactly), a
     * pool of its dark with a turning violet edge, and a glow down to it from the Entity, so it reads as what is under it.
     */
    private fun drawEntityShadow() {
        val u = world.uprising ?: return
        if (u.phase != io.github.projectwip.sim.UprisingPhase.SKY) return
        val r = io.github.projectwip.data.Balance.UPRISING_ENTITY_RADIUS
        val hit = (1f - (time - entityHitAt) / 0.25f).coerceIn(0f, 1f)
        lit.v4("uTint", 0.03f, 0f, 0.08f, 0.62f)
        setModel(entityX, 0.046f, entityZ, r, 1f, r); sector(360f).draw()
        lit.v4("uTint", 0.3f + 0.5f * hit, 0.12f + 0.5f * hit, 0.7f + 0.3f * hit, 0.35f)
        setModel(entityX, 0.05f, entityZ, r * 0.62f, 1f, r * 0.62f); sector(360f).draw()
        lit.v4("uTint", 0.75f, 0.45f, 1f, 0.95f)
        setModel(entityX, 0.054f, entityZ, r, 1f, r, time * 40f); dashRing.draw()
        lit.v4("uTint", 0.4f, 0.95f, 1f, 0.85f)
        setModel(entityX, 0.056f, entityZ, r * 0.98f, 1f, r * 0.98f, -time * 60f); dashRing.draw()
        // Its flares' marks: a turning blue ring on whoever one is locked onto (following them), that fills in as it
        // comes down: out of it in time, and it misses. Exactly the circle that corrupts (FLARE_RADIUS).
        val bal = io.github.projectwip.data.Balance
        for (fl in u.flares) {
            val fr = bal.FLARE_RADIUS
            val falling = fl.age >= bal.FLARE_LOCK
            val k = if (falling) ((fl.age - bal.FLARE_LOCK) / bal.FLARE_FALL).coerceIn(0f, 1f) else 0f
            val pulse = 0.6f + 0.4f * sin(time * 14f)
            lit.v4("uTint", 0.02f, 0.08f, 0.2f, 0.45f + 0.35f * k)
            setModel(fl.x, 0.047f, fl.y, fr, 1f, fr); sector(360f).draw()
            if (falling) { lit.v4("uTint", 0.45f, 0.85f, 1f, 0.75f); setModel(fl.x, 0.05f, fl.y, fr * k, 1f, fr * k); sector(360f).draw() }
            lit.v4("uTint", 0.45f, 0.9f, 1f, if (falling) 1f else pulse)
            setModel(fl.x, 0.054f, fl.y, fr, 1f, fr, time * 150f); dashRing.draw()
            setModel(fl.x, 0.055f, fl.y, fr * 0.6f, 1f, fr * 0.6f, -time * 220f); dashRing.draw()
        }
    }

    /** Its dimension's own light: the void glittering below the platform, and the light round the Entity. */
    private fun addRealmSprites() {
        val u = world.uprising ?: return
        if (u.phase != io.github.projectwip.sim.UprisingPhase.SKY) return
        val c = world.arena.width / 2f
        // Motes drifting in the void, below and round the edge of the platform.
        for (i in 0 until 70) {
            val a = siteHash(i, 1, 11) * 6.2832f + time * 0.03f * (0.5f + siteHash(i, 2, 11))
            val d = 10.5f + siteHash(i, 3, 11) * 16f
            val y = -1.5f - siteHash(i, 4, 11) * 9f + 0.4f * sin(time * 0.6f + i)
            val tw = 0.6f + 0.4f * sin(time * (1.2f + siteHash(i, 5, 11) * 2f) + i)
            val violet = i % 3 != 0
            sprites.add(c + cos(a) * d, y, c + sin(a) * d, 0.3f + 0.5f * siteHash(i, 6, 11),
                if (violet) 0.7f else 0.45f, if (violet) 0.4f else 0.9f, 1f, 0.55f * tw)
        }
        // Its flares: blinding blue streaks from its chest down to whoever they are locked onto, thickening as they fall.
        run {
            val bal = io.github.projectwip.data.Balance
            val cy0 = ENTITY_SKY_LIFT + 0.35f * sin(time * 1.1f) + ENTITY_SKY_SCALE * 0.95f
            for ((n, fl) in u.flares.withIndex()) {
                val falling = fl.age >= bal.FLARE_LOCK
                val k = if (falling) ((fl.age - bal.FLARE_LOCK) / bal.FLARE_FALL).coerceIn(0f, 1f) else (fl.age / bal.FLARE_LOCK).coerceIn(0f, 1f)
                for (strand in 0 until 4) {
                    for (j in 0 until 22) {
                        val v = ((time * 1.3f + j / 22f + strand * 0.25f + n * 0.13f) % 1f)
                        val arc = sin(v * 3.1416f) * (1.6f + strand * 0.35f)
                        val side = (strand - 1.5f) * 0.35f * sin(v * 3.1416f)
                        val x = lerp(entityX, fl.x, v) + side
                        val y = lerp(cy0, 0.9f, v) + arc * (1f - 0.6f * k)
                        val z = lerp(entityZ + 0.8f, fl.y, v)
                        sprites.add(x, y, z, (0.22f + 0.25f * k) * (0.6f + 0.4f * v), 0.45f, 0.82f, 1f, (0.35f + 0.5f * k) * (0.5f + 0.5f * v))
                    }
                }
                sprites.add(fl.x, 0.9f, fl.y, 1.2f + 1.6f * k, 0.5f, 0.85f, 1f, 0.35f + 0.4f * k)
            }
        }
        // A pale column of its light from the Entity down to its shadow.
        val top = ENTITY_SKY_LIFT + 0.35f * sin(time * 1.1f)
        for (k in 0..6) {
            val y = top * k / 6f
            sprites.add(entityX, y + 0.1f, entityZ, 1.4f + 0.3f * k, 0.55f, 0.35f, 1f, 0.07f)
        }
    }


    /** The light and the sky while the Entity is up: dark and violet, glitching now and then (often, in its sky). */
    private fun stepUprisingLook() {
        world.uprising?.let { up ->
            if (up.phase == io.github.projectwip.sim.UprisingPhase.SKY && !entityPlaced) { entityX = up.ex; entityZ = up.ey; entityPlaced = true }
            else if (up.phase == io.github.projectwip.sim.UprisingPhase.SKY) {
                // (Charging, it is drawn where it is: the easing would leave it behind.)
                val k = if (up.dashT >= 0f) 1f else 1f - exp(-frameDt * 6f)
                entityX += (up.ex - entityX) * k; entityZ += (up.ey - entityZ) * k
            } else entityPlaced = false
        }
        val u = world.uprising
        val on = u != null && u.active && u.phase != io.github.projectwip.sim.UprisingPhase.RETURN
        upDark += ((if (on) 1f else 0f) - upDark) * (1f - exp(-frameDt * (if (on) 1.2f else 2.5f)))
        val sky = u?.phase == io.github.projectwip.sim.UprisingPhase.SKY
        val want0 = lerp(1f, if (sky) 0.5f else 0.62f, upDark); val want1 = lerp(1f, if (sky) 0.4f else 0.5f, upDark); val want2 = lerp(1f, if (sky) 0.75f else 0.8f, upDark)
        dimLight[0] = want0; dimLight[1] = want1; dimLight[2] = want2
        if (upDark > 0.05f) {
            upGlitchIn -= frameDt
            if (upGlitchIn <= 0f) { upGlitchLeft = 0.15f + rng.nextFloat() * 0.35f; upGlitchIn = (if (sky) 1.2f else 2.2f) + rng.nextFloat() * 2f }
            upGlitchLeft -= frameDt
            dimGlitch = if (upGlitchLeft > 0f) 0.55f * upDark else 0f
            dimWarp = 0.06f * upDark
        } else { dimGlitch = 0f; dimWarp = 0f }
    }

    /** The Entity itself, enormous in the sky, glitching into the souls it carries now and then. */
    private fun drawSkyEntity(pass: Pass) {
        val u = world.uprising ?: return
        if (!u.active || u.phase == io.github.projectwip.sim.UprisingPhase.RETURN || upDark < 0.05f) return
        entityPoint(u)
        val souls = io.github.projectwip.data.Lore.Soul.entries
        val soul = if (upGlitchLeft > 0f && (time * 18f).toInt() % 3 != 0) souls[(time * 0.7f).toInt() % souls.size] else null
        entityAnim.time = time; entityAnim.walk = 0f; entityAnim.moving = 0f; entityAnim.recoil = 0f; entityAnim.bow = 0f
        entityAnim.spin = if (u.phase == io.github.projectwip.sim.UprisingPhase.SKY && u.spinT >= 0f) (swordAngle(u) - 1.5707964f) * 57.29578f else 0f
        val hit = (1f - (time - entityHitAt) / 0.12f).coerceIn(0f, 1f)
        entityAnim.flash = maxOf(if (upGlitchLeft > 0f) 0.4f else 0f, 0.3f * hit)
        entityAnim.talk = if (u.phase == io.github.projectwip.sim.UprisingPhase.MARCH || u.phase == io.github.projectwip.sim.UprisingPhase.BOW) 0.5f + 0.5f * sin(time * 9f) else 0f
        entityAnim.jump = entityAt[1]
        entityAnim.scale = entityScale(u)
        if (pass == Pass.COLOR) { lit.f("uRim", 0.9f); lit.f("uEmissive", (if (u.phase == io.github.projectwip.sim.UprisingPhase.SKY) 0.05f else 0.15f) * upDark) }
        // (It looks down at everyone: straight toward the camera's side of the arena.)
        models.drawSoul(lit, soul, entityAt[0], entityAt[2], 1.5707964f, entityAnim, pass)
        if (pass == Pass.COLOR) { lit.f("uRim", 0.45f); lit.f("uGloss", 0f); lit.f("uEmissive", 0f); lit.f("uFlash", 0f); lit.v4("uTint", 1f, 1f, 1f, 1f); lit.mat4("uModel", identity) }
        if (u.phase == io.github.projectwip.sim.UprisingPhase.SKY) drawSword(u, pass)
        drawTrapped(u, pass)
    }

    /** How far [f] is bent in its bow to the Entity (degrees). */
    private fun uprisingBow(f: io.github.projectwip.sim.Fighter): Float {
        val u = world.uprising ?: return 0f
        if (f.id !in u.members) return 0f
        return when (u.phase) {
            // Standing while it says "Kneel.", then going down one after another round the circle (the kneel beat),
            // slowly, deeper than a nod.
            io.github.projectwip.sim.UprisingPhase.BOW -> {
                val at = io.github.projectwip.match.UprisingScript.kneelAt(u.bowLength) + 0.5f + u.members.indexOf(f.id) * 0.28f
                48f * smooth((u.t - at) / 1.3f)
            }
            io.github.projectwip.sim.UprisingPhase.ENTER -> 48f * (1f - smooth(u.t / 0.6f))
            else -> 0f
        }
    }

    /** Through the Entity's portal: someone who has walked into it is gone from sight. */
    private fun intoPortal(f: io.github.projectwip.sim.Fighter): Boolean {
        val u = world.uprising ?: return false
        return u.phase == io.github.projectwip.sim.UprisingPhase.ENTER && f.id in u.members && hypot(f.x - u.cx, f.y - u.cy) < 1f
    }

    /** The uprising's glow: its portal, the light round the Entity, the strikes flying up into it. */
    private fun addUprisingSprites() {
        val u = world.uprising
        if (u != null && u.active) {
            // Its portal in the middle, standing up, rainbow round a violet vortex: from the last seconds of the bow
            // until everyone is through.
            val open = when (u.phase) {
                io.github.projectwip.sim.UprisingPhase.BOW -> smooth((u.t - (u.bowLength - 3f)) / 2f)
                io.github.projectwip.sim.UprisingPhase.ENTER -> 1f
                else -> 0f
            }
            if (open > 0.01f) {
                val rx = 1.7f * open; val ry = 1.35f * open; val cy = 1.45f
                for (k in 0 until 64) {
                    val a = k * (6.2831855f / 64) + time * 1.5f
                    val col = hue(k / 64f + time * 0.25f)
                    sprites.add(u.cx + cos(a) * rx, cy + sin(a) * ry, u.cy, 0.42f, col[0], col[1], col[2], 0.75f * open)
                }
                for (k in 0 until 40) {
                    val r = (k / 40f)
                    val a = -time * 3f + r * 9f
                    sprites.add(u.cx + cos(a) * rx * r, cy + sin(a) * ry * r, u.cy + 0.02f, 0.5f * (1.2f - r), 0.55f, 0.25f, 1f, 0.35f * open)
                }
                sprites.add(u.cx, cy, u.cy, 2.6f * open, 0.6f, 0.3f, 1f, 0.35f * open)
            }
            // In its world: a trail behind anyone it is pulling into itself. (No soul wisps in its body: the user had
            // them taken out.) And its sword's reach, ringed on the floor, while it winds up and spins.
            if (u.phase == io.github.projectwip.sim.UprisingPhase.SKY && upDark > 0.05f) {
                entityPoint(u)
                val big = entityScale(u)
                if (u.spinT >= 0f) {
                    val wind = u.spinT < io.github.projectwip.data.Balance.ENTITY_SPIN_WIND
                    val r = io.github.projectwip.data.Balance.ENTITY_SPIN_RADIUS
                    val pulse = if (wind) 0.55f + 0.45f * sin(time * 18f) else 0.9f
                    for (k in 0 until 72) {
                        val a = k * (6.2831855f / 72)
                        sprites.add(entityX + cos(a) * r, 0.12f, entityZ + sin(a) * r, 0.34f, 1f, 0.3f, 0.75f, 0.7f * pulse)
                    }
                    // The blade's sweep: a smear of light behind it as it turns.
                    if (!wind) {
                        val ang = swordAngle(u)
                        for (j2 in 0 until 10) {
                            val back = ang + j2 * 0.09f
                            for (k in 1..8) {
                                val d = r * k / 8f
                                sprites.add(entityX + cos(back) * d, 1.05f, entityZ - sin(back) * d, 0.32f, 0.75f, 0.55f, 1f, 0.45f * (1f - j2 / 10f))
                            }
                        }
                    }
                }
                val held = u.members.mapNotNull { id -> world.fighter(id)?.takeIf { it.trapped } }
                for ((k, f) in held.withIndex()) {
                    trappedPlace(f, k, held.size, big, trapPlace)
                    val since = time - (trapAt[f.id] ?: -10f)
                    if (since < 1.6f) for (j in 0 until 14) {
                        val back = j / 14f * 0.5f
                        val s = smooth(((since - back) / 1.4f).coerceIn(0f, 1f))
                        sprites.add(lerp(f.x, trapPlace[0], s), lerp(0.5f, trapPlace[1] + 0.8f, s), lerp(f.y, trapPlace[2], s), 0.5f * (1f - j / 14f), 0.4f, 0.8f, 1f, 0.6f * (1f - j / 14f))
                    }
                }
            }
            // A cold light round the Entity, so its shape reads against the dark.
            if (u.phase != io.github.projectwip.sim.UprisingPhase.RETURN && upDark > 0.05f) {
                entityPoint(u)
                val big = entityScale(u)
                sprites.add(entityAt[0], entityAt[1] + big * 1.1f, entityAt[2] - 0.5f, big * 3.2f, 0.35f, 0.2f, 0.9f, 0.22f * upDark)
                sprites.add(entityAt[0], entityAt[1] + big * 1.4f, entityAt[2] + 0.5f, big * 0.9f, 0.6f, 0.85f, 1f, 0.25f * upDark)
            }
        }
    }

    // ------------------------------------------------------------------ the Entity's hijack

    /**
     * The Entity taking the player over (the user's sequence): a comet leaves them, climbs into the sky with the
     * camera looking up after it, turns over and comes straight back down into their torso; the match is held still
     * (unless other real players are in it) and the camera closes in while they glitch; then `MatchScreen` plays the
     * hijack cutscene, and play goes on with the Entity at the controls.
     */
    private var hijackAt = -100f
    private var hijackHit = false
    private var hijackBackAt = -100f
    private var hijackWas = 0
    private val hijackOut = FloatArray(3)

    private fun startHijack() {
        hijackAt = time; hijackHit = false
        if (!runner.shared) runner.paused = true
        runner.hijack = 1
        runner.sound(Sound.WHOOSH, 1f)
    }

    /** Where the comet is [t] seconds in: up from the shoulder into the sky, over, and straight down into the torso. */
    private fun hijackComet(t: Float, px: Float, pz: Float, out: FloatArray) {
        if (t < HIJACK_TOP) {
            val u = 1f - (1f - (t / HIJACK_TOP).coerceIn(0f, 1f)).let { it * it }
            out[0] = px + 0.3f + 0.9f * u + sin(u * 3.1416f) * 1.2f
            out[1] = 1.3f + (HIJACK_APEX - 1.3f) * u
            out[2] = pz - 3f * u
        } else {
            val u = ((t - HIJACK_TOP) / (HIJACK_HIT - HIJACK_TOP)).coerceIn(0f, 1f).let { it * it }
            out[0] = px + 1.2f * (1f - u) - sin(u * 3.1416f) * 0.8f
            out[1] = HIJACK_APEX + (0.95f - HIJACK_APEX) * u
            out[2] = pz - 3f * (1f - u)
        }
    }

    private fun stepHijack() {
        if (hijackWas == 2 && runner.hijack == 0) hijackBackAt = time
        hijackWas = runner.hijack
        if (runner.hijack == 0) return
        val p = match.player
        val pid = p.id
        val t = time - hijackAt
        if (!hijackHit && t >= HIJACK_HIT) {
            hijackHit = true
            energyFlare(pid, 1f, sky = false)
            shake = max(shake, 0.45f)
            runner.sound(Sound.SHOCK, 1f)
            repeat(60) {
                val a = rng.nextFloat() * 6.283f; val sp = 2f + rng.nextFloat() * 5f
                particles.spawn(p.x, 1f, p.y, cos(a) * sp, 1f + rng.nextFloat() * 5f, sin(a) * sp, 0.7f, 0.12f, if (it % 3 == 0) 0xFFFFFFFF.toInt() else 0xFF7FC8FF.toInt(), 1f)
            }
            particles.spawn(p.x, 1f, p.y, 0f, 0f, 0f, 0.5f, 4f, 0xFFBFE4FF.toInt(), 0.9f)
        }
        // Once it is in them, they glitch hard until the Entity lets go of the camera.
        if (hijackHit && pid in attackGlitchAt.indices) { attackGlitchAt[pid] = time; attackGlitchSize[pid] = 1f }
        if (runner.hijack != 1) return
        if (!hijackHit && rng.nextFloat() < 0.7f) {
            hijackComet(t, p.x, p.y, hijackOut)
            particles.spawn(hijackOut[0], hijackOut[1], hijackOut[2], (rng.nextFloat() - 0.5f) * 3f, (rng.nextFloat() - 0.5f) * 3f, (rng.nextFloat() - 0.5f) * 3f, 0.7f, 0.12f, 0xFF8FD8FF.toInt(), 1f)
        }
        if (t >= HIJACK_LEN) {
            if (runner.shared) { runner.hijack = 0; hijackBackAt = time; hijackWas = 0 } else runner.hijack = 2
        }
    }

    // ------------------------------------------------------------------ passes

    private fun renderShadows(alpha: Float) {
        val shadow = shadow ?: return
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

        // Environment. In a realm the whole arena goes dark and violet: only the fighters keep their colours.
        stepUprisingLook()
        lit.f("uWarp", dimWarp)
        lit.f("uGlitch", dimGlitch)
        lit.f("uGlitchF", dimGlitch)
        lit.f("uGlitchClock", time)
        val er = lerp(1f, 0.2f, realmFade) * dimLight[0]
        val eg = lerp(1f, 0.15f, realmFade) * dimLight[1]
        val eb = lerp(1f, 0.46f, realmFade) * dimLight[2]
        lit.f("uRim", 0.08f)
        lit.v4("uTint", er, eg, eb, 1f)
        arena.ground.draw()
        lit.f("uRim", 0.25f)
        arena.solids.draw()
        lit.v4("uTint", 1f, 1f, 1f, 1f)
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
        forEachRealmShadow(alpha) { f, x, z, facing -> models.draw(lit, f.def, f.skin, x, z, facing, anim, Pass.COLOR, shade = SHADOW_SHADE) }
        drawSkyEntity(Pass.COLOR)
        lit.f("uFlash", 0f)
        lit.f("uEmissive", 0f)

        // Outlines (inverted hull)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", INK[0], INK[1], INK[2], 1f)
        lit.f("uOutline", 0.028f)
        lit.mat4("uModel", identity)
        if (gfx.arenaOutlines) {
            arena.solids.draw()
            drawCrates(lit, shadowPass = false, outline = true)
        }
        lit.f("uOutline", FighterModels.OUTLINE)
        forEachSolidFighter(alpha) { f, x, z, facing -> models.draw(lit, f.def, f.skin, x, z, facing, anim, Pass.OUTLINE) }
        forEachRealmShadow(alpha) { f, x, z, facing -> models.draw(lit, f.def, f.skin, x, z, facing, anim, Pass.OUTLINE) }
        drawSkyEntity(Pass.OUTLINE)
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        drawFadingFighters(alpha)
        drawGlitchGhosts(alpha)

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
        lit.v4("uTint", er, eg, eb, 1f)
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

        // Ground decals
        setupLit(lit)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        lit.i("uMode", 1)
        drawDecals(alpha)
        drawEntityShadow()
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
            if (!f.alive || shownVis[f.id] < 0.999f || !inView(f.x, f.y) || f.inTempest) continue
            poseFor(f)
            block(f, lerp(f.prevX, f.x, alpha) + glitchJolt(f.id), lerp(f.prevY, f.y, alpha), shownFacing[f.id])
        }
    }

    /**
     * The shadows of a realm that has them (`SuperSpec.shadows`), seen from inside it: they stand in a ring round the
     * captive, turning slowly, and each lunges in as it strikes (they take turns, see `World.stepRealm`).
     */
    private inline fun forEachRealmShadow(alpha: Float, block: (Fighter, Float, Float, Float) -> Unit) {
        for (f in world.fighters) {
            val n = f.def.superSpec.shadows
            if (n <= 0 || f.realm != f.id || !f.alive || f.realm != match.player.realm) continue
            val t = world.fighters.firstOrNull { it.realm == f.id && it !== f && it.alive } ?: continue
            val beat = io.github.projectwip.data.Balance.REALM_ROCKET_SECONDS / n
            val since = beat - f.realmTick
            val last = ((f.realmStrikes - 1) % n + n) % n
            val tx = lerp(t.prevX, t.x, alpha); val tz = lerp(t.prevY, t.y, alpha)
            for (k in 0 until n) {
                val lunge = if (f.realmStrikes > 0 && k == last && since < SHADOW_LUNGE) sin(since / SHADOW_LUNGE * 3.1416f) else 0f
                val a = k * 6.2832f / n + time * 0.5f
                val d = (1.55f - 0.8f * lunge) * t.scale.coerceAtLeast(1f)
                val x = tx + cos(a) * d; val z = tz + sin(a) * d
                poseFor(f)
                anim.scale = FIGHTER_SCALE * 0.85f
                anim.moving = 0.3f
                anim.walk = time * 4f + k
                anim.recoil = lunge
                anim.jump = lunge * 0.25f
                anim.flash = 0f
                anim.time = time + k * 1.3f
                block(f, x, z, atan2(tz - z, tx - x))
            }
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
        anim.bow = uprisingBow(f)
        anim.spin = 0f; anim.swing = 0f; anim.throwL = 0f; anim.throwR = 0f; anim.slash = -1f
        // A sword swing: the blade sweeps across, and he turns into it and steps up off the ground a little.
        if (f.def.attack.shape == AttackShape.SLASH && f.sinceAttack < SLASH_SECONDS) {
            val u = f.sinceAttack / SLASH_SECONDS
            anim.slash = u
            anim.recoil = 0f
            anim.spin = (0.5f - u) * 50f * (1f - u)
            anim.jump = sin(u * 3.1416f) * 0.1f
        }
        // Paw prints: one front leg is flung forward for each, left then right.
        if (f.def.attack.shape == AttackShape.PAWS) {
            val gap = f.def.attack.burstInterval
            anim.throwL = fling(f.sinceAttack / 0.2f)
            anim.throwR = fling((f.sinceAttack - gap) / 0.2f)
            anim.recoil = max(anim.throwL, anim.throwR)
        }
        // A hammer throw: up off the ground, the arm over the top, and back down as the hammer leaves.
        // On someone's back, hammering code into them: up off the ground, arms going.
        if (f.latched) {
            val host = world.fighter(f.latchedTo)
            anim.jump = 0.5f * (host?.scale ?: 1f) + 0.04f * sin(time * 21f)
            anim.recoil = kotlin.math.abs(sin(time * 19f))
            anim.moving = 0f
        }
        // Corrupted. On the way over it lurches; once it has stopped it goes berserk on the spot: legs running nowhere,
        // arms flailing, jerking round, jumping, flashing.
        if (f.enthralled) {
            if (f.thrallMoving) {
                anim.spin = sin(time * 11f + i) * 14f
                anim.flash = max(anim.flash, 0.25f * kotlin.math.abs(sin(time * 9f)))
            } else {
                anim.moving = 1f
                anim.walk = time * 34f + i
                anim.spin = sin(time * 23f + i) * 55f + sin(time * 7.3f) * 40f
                anim.jump = kotlin.math.abs(sin(time * 15f + i)) * 0.32f
                anim.swing = (time * 3.4f + i * 0.3f) % 1f
                anim.recoil = kotlin.math.abs(sin(time * 29f))
                anim.throwL = fling((time * 4.1f) % 1f)
                anim.throwR = fling((time * 4.1f + 0.5f) % 1f)
                anim.flash = max(anim.flash, 0.55f * kotlin.math.abs(sin(time * 13f + i)))
            }
        }
        // A leap onto someone's back: high over everything, turning right round on the way.
        if (f.isLeaping) {
            val u = (1f - f.leapTime / f.leapTotal).coerceIn(0f, 1f)
            anim.jump = sin(u * 3.1416f) * 3.2f
            anim.spin = 360f * u
        }
        hammerAt[i]?.let { at ->
            val t = (time - at) / HAMMER_LEAP
            if (t in 0f..1f) { anim.jump = sin(t * 3.1416f) * 0.9f; anim.swing = t; anim.spin = -30f * sin(t * 3.1416f) }
        }
        // In the eye of her tempest: lifted a little off the ground and turning fast.
        if (f.inTempest) {
            anim.spin = (time * 720f + i * 40f) % 360f
            anim.jump = 0.35f + 0.08f * sin(time * 6f + i)
            anim.moving = 0f
        }
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
                if (!f.alive || v <= 0.02f || v >= 0.999f || !inView(f.x, f.y) || f.inTempest) continue
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
        val me = match.player
        realmFade += ((if (me.inRealm && me.alive) 1f else 0f) - realmFade) * (1f - exp(-dt * 7f))
        // Stars drift up all around while the player is in a realm.
        if (realmFade > 0.3f) repeat(2) {
            val a = rng.nextFloat() * 6.28f
            val d = 1f + rng.nextFloat() * 11f
            particles.spawn(me.x + cos(a) * d, 0.2f + rng.nextFloat() * 3f, me.y + sin(a) * d * 0.7f, 0f, 0.3f + rng.nextFloat() * 0.5f, 0f, 1.1f, 0.1f + rng.nextFloat() * 0.1f,
                if (rng.nextFloat() < 0.5f) 0xFFFFFFFF.toInt() else if (rng.nextFloat() < 0.5f) 0xFF7CE8FF.toInt() else 0xFFFF9AE0.toInt(), 0.9f)
        }
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
            // (Whoever is in a realm the player isn't in can't be seen from here, and the other way round.)
            val vis = f.alive && f.realm == match.player.realm && world.isVisibleTo(f, match.player.team) && !intoPortal(f)
            shownVis[i] = if (!f.alive) 1f
                else if (vis) min(1f, shownVis[i] + dt / VIS_FADE_IN) else max(0f, shownVis[i] - dt / VIS_FADE_OUT)
            if (f.isDashing && rng.nextFloat() < 0.8f) {
                particles.spawn(f.x - f.dashDirX * 0.4f, 0.15f, f.y - f.dashDirY * 0.4f, -f.dashDirX, 0.6f, -f.dashDirY, 0.5f, 0.3f, 0xFFDCCFB4.toInt(), 0.5f, growth = 0.6f, add = false)
            }
        }
    }

    private fun drawCrates(p: Program, shadowPass: Boolean, outline: Boolean = false) {
        if (world.crateHp.isEmpty() || world.inSky) return
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
        if (world.pickups.isEmpty() || world.inSky) return
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

    /** A colour round the hue wheel, as r, g, b in 0..1 ([h] in turns). */
    private fun hue(h: Float, out: FloatArray = hueOut): FloatArray {
        val x = ((h % 1f) + 1f) % 1f * 6f
        val f = x - kotlin.math.floor(x)
        when (x.toInt()) {
            0 -> { out[0] = 1f; out[1] = f; out[2] = 0f }
            1 -> { out[0] = 1f - f; out[1] = 1f; out[2] = 0f }
            2 -> { out[0] = 0f; out[1] = 1f; out[2] = f }
            3 -> { out[0] = 0f; out[1] = 1f - f; out[2] = 1f }
            4 -> { out[0] = f; out[1] = 0f; out[2] = 1f }
            else -> { out[0] = 1f; out[1] = 0f; out[2] = 1f - f }
        }
        return out
    }
    private val hueOut = FloatArray(3)

    /** Like [setModel], but stands a ground-plane mesh up to face the camera (+Z), turned by [rollDeg] in that plane. */
    private fun setModelUpright(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, rollDeg: Float = 0f) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rollDeg != 0f) Matrix.rotateM(model, 0, rollDeg, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, 90f, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        lit.mat4("uModel", model)
    }

    /**
     * Like [setModelUpright] for Dimension's portal ring: stood up, its middle [y] above the ground under ([x], [z]),
     * then leaned back by [PORTAL_LEAN] about that ground line, so that from the game's camera (looking down at
     * [PITCH]) it faces the player instead of looking squashed (the user found the straight-up ring "too straight and
     * too down"). [dz] moves it toward the camera before the lean.
     */
    private fun setModelPortal(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, rollDeg: Float = 0f, dz: Float = 0f) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, 0f, z)
        Matrix.rotateM(model, 0, -PORTAL_LEAN, 1f, 0f, 0f)
        Matrix.translateM(model, 0, 0f, y, dz)
        if (rollDeg != 0f) Matrix.rotateM(model, 0, rollDeg, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, 90f, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        lit.mat4("uModel", model)
    }

    /** Where a point on the portal's plane ([py] up, [pz] out of it) ends up once the ring is leaned back, as (y, z offset). */
    private fun leanPoint(py: Float, pz: Float, out: FloatArray = leanOut): FloatArray {
        val l = Math.toRadians(PORTAL_LEAN.toDouble())
        out[0] = (py * kotlin.math.cos(l) + pz * kotlin.math.sin(l)).toFloat()
        out[1] = (-py * kotlin.math.sin(l) + pz * kotlin.math.cos(l)).toFloat()
        return out
    }
    private val leanOut = FloatArray(2)

    private var followFor = Float.NaN
    private var followX = 0f
    private var followZ = 0f
    private var followFacing = 0f
    private var followWalk = 0f
    private var followSpeed = 0f


    // ------------------------------------------------------------------ the Entity's energy (the user's lore, build 108)

    /** How brightly each fighter's streaks are flaring (1 just after a super or a hypercharge), fading. */
    private val flare = FloatArray(io.github.projectwip.match.HudSnapshot.MAX)
    /** Streaks fired up into the sky by an attack or a super: x, z, start time, strength, and the shoulder side. */
    private val skyStreaks = ArrayList<FloatArray>()
    /** A round tube along Y (one unit long, one unit across) for the streaks. */
    private val tube: Mesh by lazy { MeshBuilder().apply { color(1f, 1f, 1f); cylinder(0.5f, 1f, 8) }.build() }

    /** When each fighter last attacked, and how hard (a super is 1): the glitch flicker on every attack. */
    private val attackGlitchAt = FloatArray(io.github.projectwip.match.HudSnapshot.MAX) { -100f }
    private val attackGlitchSize = FloatArray(io.github.projectwip.match.HudSnapshot.MAX)

    private fun energyFlare(id: Int, amount: Float, sky: Boolean) {
        if (id !in flare.indices) return
        flare[id] = maxOf(flare[id], amount)
        if (sky) { attackGlitchAt[id] = time; attackGlitchSize[id] = amount }
        // (No streak into the sky and no look up for an attack or a super: the user wants those only for the Entity's
        // takeover, its comet up and back down into the player, see [startHijack].)
        val f = world.fighter(id) ?: return
        if (!inView(f.x, f.y)) return
        // The flare bursts out of the streaks themselves: sparks thrown off along the four lines.
        val n = (8 + 22 * amount).toInt()
        for (k in 0 until n) {
            streakPoint(f, f.x, f.y, shownFacing[id], k % 4, 0.3f + 0.7f * rng.nextFloat())
            val ox = streakOut[0] - f.x; val oz = streakOut[2] - f.y
            val d = hypot(ox, oz).coerceAtLeast(0.01f)
            val sp = 2f + rng.nextFloat() * 3.5f * amount
            particles.spawn(streakOut[0], streakOut[1], streakOut[2], ox / d * sp, 0.5f + rng.nextFloat() * 2.5f, oz / d * sp, 0.35f + 0.3f * amount, 0.07f + 0.05f * amount,
                if (k % 3 == 0) 0xFFFFFFFF.toInt() else 0xFF5CC8FF.toInt(), 1f)
        }
        // And a streak fires up into the sky (the user's idea), from a shoulder, bigger for a super.

    }

    /**
     * How hard a fighter is glitching right now (0..1): every attack (a quarter of a second) and every super (more than
     * half a second, harder) glitches them, the user's lore; and a fighter the Entity has taken over (`glitchLeft`)
     * glitches on and off the whole time.
     */
    private fun glitchAmount(id: Int): Float {
        var g = 0f
        if (id in attackGlitchAt.indices) {
            // A plain attack only flickers them (the user found it too vivid); a super still tears them apart.
            val a = time - attackGlitchAt[id]
            val sz = attackGlitchSize[id] * attackGlitchSize[id]
            val len = 0.15f + 0.45f * sz
            if (a in 0f..len) g = (0.22f + 0.55f * sz) * (1f - a / len * 0.5f)
        }
        val f = world.fighter(id)
        if (f != null && f.glitchLeft > 0f && (time * 7f + id).toInt() % 3 != 0) g = maxOf(g, 0.8f)
        if (f != null && idleGlitch(f)) g = maxOf(g, 0.34f)
        return g
    }

    /**
     * The Entity is in every one of them, so every fighter glitches for a moment now and then, even standing still (the
     * user's ask): short and faint, a few seconds apart, at a different moment for each.
     */
    private fun idleGlitch(f: Fighter): Boolean {
        if (!f.alive) return false
        val t = time + f.id * 1.37f
        val cycle = (t / IDLE_GLITCH_EVERY).toInt()
        val at = siteHash(f.id, cycle, 8) * (IDLE_GLITCH_EVERY - 0.4f)
        val len = 0.1f + 0.14f * siteHash(f.id, cycle, 9)
        val local = t - cycle * IDLE_GLITCH_EVERY
        return local in at..at + len
    }

    /**
     * The glitch, drawn: the fighter torn into colour, a cyan copy of them pulled one way and a magenta copy the other,
     * jumping about, for as long as [glitchAmount] says. (Flat see-through silhouettes over the fighter, like the
     * x-ray pass.)
     */
    private fun drawGlitchGhosts(alpha: Float) {
        var any = false
        for (f in world.fighters) if (f.alive && glitchAmount(f.id) > 0f) { any = true; break }
        if (!any) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        lit.i("uMode", 2)
        forEachSolidFighter(alpha) { f, x, z, facing ->
            val g = glitchAmount(f.id)
            if (g <= 0f) return@forEachSolidFighter
            val tick = (time * 24f).toInt()
            val jump = 0.15f + 0.3f * g * siteHash(f.id, tick, 4)
            val dz = (siteHash(f.id, tick, 5) - 0.5f) * 0.3f * g
            lit.v4("uTint", 0.1f, 0.9f, 1f, 0.75f * g)
            models.draw(lit, f.def, f.skin, x + jump, z + dz, facing, anim, Pass.SILHOUETTE)
            lit.v4("uTint", 1f, 0.15f, 0.8f, 0.7f * g)
            models.draw(lit, f.def, f.skin, x - jump, z - dz, facing, anim, Pass.SILHOUETTE)
        }
        lit.i("uMode", 0)
        lit.v4("uTint", 1f, 1f, 1f, 1f)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** A glitching fighter (the Entity has them, `Fighter.glitchLeft`) jolts sideways, on and off. */
    private fun glitchJolt(id: Int): Float {
        // Every attack and super glitches the fighter for a blink (longer for a super): the Entity is in them.
        if (id in attackGlitchAt.indices) {
            val a = time - attackGlitchAt[id]
            val sz = attackGlitchSize[id] * attackGlitchSize[id]
            val len = 0.15f + 0.45f * sz
            if (a in 0f..len) return (0.04f + 0.16f * sz) * (if ((a * 30f).toInt() % 2 == 0) 1f else -1f)
        }
        val f = world.fighter(id) ?: return 0f
        if (idleGlitch(f)) return 0.06f * (if ((time * 30f).toInt() % 2 == 0) 1f else -1f)
        if (f.glitchLeft <= 0f) return 0f
        return if ((time * 9f + id).toInt() % 3 == 0) 0.14f * sin(time * 95f) else 0f
    }

    private val streakOut = FloatArray(3)

    /**
     * A point on one of a fighter's four streak lines ([line] 0..3; two to each shoulder), [u] along it (the user's
     * design): from 0 to [CONVERGE] the line comes down out of the air above them (thin feeder streaks converge into
     * it there, see [drawEnergyStreaks]) to the top of a shoulder; to [AROUND] it goes once round that shoulder; then
     * it swirls down round the torso to the waist, wide enough to stay clear of the body. Turns with the fighter.
     */
    private fun streakPoint(f: Fighter, x: Float, z: Float, facing: Float, line: Int, u: Float, spread: Float = 0f) {
        val sc = FIGHTER_SCALE * f.scale
        val side = if (line < 2) 1f else -1f
        val sub = if (line % 2 == 0) 1f else -1f
        val sideAng = facing + side * 1.5708f
        val sx = x + cos(sideAng) * 0.3f * sc; val sz = z + sin(sideAng) * 0.3f * sc; val sy = 0.95f * sc
        when {
            u <= CONVERGE -> {
                // From high above and out to that side, curving down onto the shoulder.
                val v = u / CONVERGE
                val back = facing + 3.1416f
                val tx = sx + cos(sideAng) * 0.12f * sc + cos(back) * (0.18f + 0.12f * sub) * sc
                val tz = sz + sin(sideAng) * 0.12f * sc + sin(back) * (0.18f + 0.12f * sub) * sc
                val ty = sy + 1.25f * sc
                val w = 1f - (1f - v) * (1f - v)
                val drift = (1f - v) * spread * 0.18f * sc
                streakOut[0] = tx + (sx - tx) * w + cos(facing) * drift + 0.03f * sin(time * 2.5f + line + v * 6f)
                streakOut[1] = ty + (sy + 0.12f * sc - ty) * v
                streakOut[2] = tz + (sz - tz) * w + sin(facing) * drift
            }
            u <= AROUND -> {
                // Once round the shoulder: a loop about the shoulder's own axis.
                val v = (u - CONVERGE) / (AROUND - CONVERGE)
                val a = v * 6.2832f * sub + 1.5708f
                val r = 0.16f * sc
                val fx = cos(facing); val fz = sin(facing)
                streakOut[0] = sx + fx * cos(a) * r + cos(sideAng) * 0.05f * sc
                streakOut[1] = sy + sin(a) * r
                streakOut[2] = sz + fz * cos(a) * r + sin(sideAng) * 0.05f * sc
            }
            else -> {
                // Down round the torso, a turn and a quarter, flowing; the shoulder end stays put.
                val v = (u - AROUND) / (1f - AROUND)
                val ang = sideAng + sub * v * (7.8f + 0.4f * sin(time * 0.7f + line)) + sub * v * time * 0.35f
                val r = sc * (0.38f + 0.16f * v) * (1f + 0.03f * sin(time * 3f + v * 10f + line))
                streakOut[0] = x + cos(ang) * r
                streakOut[1] = sy - v * 0.5f * sc
                streakOut[2] = z + sin(ang) * r
            }
        }
    }

    /**
     * The Entity's energy in every fighter (the user's design), as glow (one additive sprite batch, so it costs next to
     * nothing): each of the four lines is a trail of soft blue light, two faint feeder streaks converging into it above
     * the shoulder; and comets ride them, bright blue heads with white-hot cores and fading tails, rising from the waist
     * round the torso, up through the shoulder and away into the air. They flare when a power is used and blaze while
     * the fighter is glitching. An attack or a super fires a streak up into the sky.
     */
    private fun addEnergySprites(alpha: Float) {
        val dt = frameDt.coerceAtMost(0.05f)
        for (i in flare.indices) flare[i] = maxOf(0f, flare[i] - dt * 1.6f)
        for (f in world.fighters) {
            if (!f.alive || shownVis[f.id] < 0.5f || f.inTempest || f.isLeaping) continue
            val x = lerp(f.prevX, f.x, alpha) + glitchJolt(f.id)
            val z = lerp(f.prevY, f.y, alpha)
            if (!inView(x, z)) continue
            val sc = FIGHTER_SCALE * f.scale
            val glitching = if (f.glitchLeft > 0f) 0.8f + 0.2f * sin(time * 30f) else 0f
            val boost = maxOf(flare[f.id], if (f.hyperActive) 0.5f else 0f, glitching)
            val facing = shownFacing[f.id]
            for (line in 0..3) {
                // The line: a trail of soft light along the whole path.
                val n = 11
                for (k in 0..n) {
                    val u = CONVERGE * 0.35f + (1f - CONVERGE * 0.35f) * k / n
                    streakPoint(f, x, z, facing, line, u)
                    sprites.add(streakOut[0], streakOut[1], streakOut[2], 0.18f * sc, 0.25f, 0.55f, 1f, 0.09f + 0.14f * boost)
                }
                // The feeders, converging into it above the shoulder.
                for (feeder in floatArrayOf(-1f, 1f)) for (k in 0..2) {
                    streakPoint(f, x, z, facing, line, CONVERGE * k / 3f, feeder)
                    sprites.add(streakOut[0], streakOut[1], streakOut[2], 0.11f * sc, 0.3f, 0.6f, 1f, 0.07f + 0.12f * boost)
                }
                // A comet, rising: from the waist (u = 1) round the body, up through the shoulder and away (u -> 0).
                val head = 1f - ((time * (0.28f + 0.35f * boost) + line * 0.25f + f.id * 0.17f) % 1f)
                for (k in 0..7) {
                    val tu = (head + k * 0.03f).coerceAtMost(1f)
                    streakPoint(f, x, z, facing, line, tu)
                    val fade = 1f - k / 8f
                    sprites.add(streakOut[0], streakOut[1], streakOut[2], (0.12f + 0.16f * fade) * sc, 0.35f + 0.4f * fade, 0.7f + 0.25f * fade, 1f, (0.08f + 0.22f * fade) * (0.8f + 0.2f * boost))
                }
                streakPoint(f, x, z, facing, line, head)
                sprites.add(streakOut[0], streakOut[1], streakOut[2], 0.38f * sc * (1f + 0.3f * boost), 0.3f, 0.6f, 1f, 0.14f + 0.12f * boost)
                sprites.add(streakOut[0], streakOut[1], streakOut[2], 0.14f * sc, 0.75f, 0.92f, 1f, 0.42f)
            }
        }
    }

    /**
     * Streaks fired into the sky, drawn in a batch of their own with no depth test, so nothing (Swish's tornado, a
     * wall, the fighter) hides them on their way up.
     */
    private fun addSkyStreaks() {
        addUprisingSprites()
        // Stars come out over the arena while the camera is looking up.
        val starsOut = maxOf(lookUp, upDark)
        if (starsOut > 0.01f) for (i in 0 until 90) {
            val x = camX + (siteHash(i, 1, 7) - 0.5f) * 80f
            val y = 20f + siteHash(i, 2, 7) * 24f
            val z = camZ - 6f - siteHash(i, 3, 7) * 40f
            val tw = 0.65f + 0.35f * sin(time * (1.5f + siteHash(i, 4, 7) * 3f) + i)
            sprites.add(x, y, z, 0.2f + 0.35f * siteHash(i, 5, 7), 0.8f, 0.88f, 1f, starsOut * 0.75f * tw)
        }
        // The Entity's comet, on its way up and back down into the player.
        if (runner.hijack == 1) {
            val t = time - hijackAt
            if (t < HIJACK_HIT + 0.04f) {
                val px = match.player.x; val pz = match.player.y
                for (k in 40 downTo 1) {
                    val tt = t - k * 0.018f
                    if (tt < 0f) continue
                    hijackComet(tt, px, pz, hijackOut)
                    val fade = 1f - k / 41f
                    sprites.add(hijackOut[0], hijackOut[1], hijackOut[2], 0.25f + 0.8f * fade, 0.3f + 0.5f * fade, 0.6f + 0.35f * fade, 1f, 0.12f + 0.55f * fade)
                }
                hijackComet(t.coerceAtMost(HIJACK_HIT), px, pz, hijackOut)
                sprites.add(hijackOut[0], hijackOut[1], hijackOut[2], 2.6f, 0.35f, 0.7f, 1f, 0.5f)
                sprites.add(hijackOut[0], hijackOut[1], hijackOut[2], 1.0f, 0.85f, 0.95f, 1f, 0.95f)
            }
        }
        // Streaks fired into the sky: a bolt of blue light leaving the shoulder and rising almost straight up, a bright
        // head and a long tail, sparks shed along it (the user's idea; the camera looks up to it after a super).
        var i = 0
        while (i < skyStreaks.size) {
            val st = skyStreaks[i]
            val age = time - st[2]
            val life = if (st[3] >= 1f) SKY_STREAK_SUPER else 0.7f
            if (age > life) { skyStreaks.removeAt(i); continue }
            i++
            val big = st[3] >= 1f
            val sideAng = st[5] + st[4] * 1.5708f
            val x0 = st[0] + cos(sideAng) * 0.38f; val z0 = st[1] + sin(sideAng) * 0.38f; val y0 = 1.25f
            val top = if (big) 16f else 8f
            val x1 = x0 + cos(sideAng) * 0.8f; val z1 = z0 - (if (big) 2.5f else 2f); val y1 = top
            val rise = if (big) 0.55f else 0.28f
            val headU = (age / rise).coerceAtMost(1f)
            val tailU = ((age - rise * 0.5f) / (life - rise * 0.5f)).coerceIn(0f, 1f)
            val a = (1f - ((age - rise) / (life - rise)).coerceIn(0f, 1f) * 0.9f)
            val segs = if (big) 40 else 18
            for (k in 0..segs) {
                val u = tailU + (headU - tailU) * k / segs
                val nx = x0 + (x1 - x0) * u + 0.08f * sin(u * 18f + st[2] * 5f); val ny = y0 + (y1 - y0) * u; val nz = z0 + (z1 - z0) * u
                val near = k / segs.toFloat()
                val w = if (big) 0.45f + 0.5f * near else 0.25f + 0.25f * near
                sprites.add(nx, ny, nz, w, 0.45f + 0.5f * near, 0.8f + 0.2f * near, 1f, a * (0.35f + 0.65f * near))
            }
            // The head.
            val hx = x0 + (x1 - x0) * headU; val hy = y0 + (y1 - y0) * headU; val hz = z0 + (z1 - z0) * headU
            sprites.add(hx, hy, hz, if (big) 2.2f else 1f, 0.5f, 0.85f, 1f, a * 0.6f)
            sprites.add(hx, hy, hz, if (big) 0.8f else 0.4f, 1f, 1f, 1f, a)
            if (big && headU < 1f && rng.nextFloat() < 0.8f)
                particles.spawn(hx, hy, hz, (rng.nextFloat() - 0.5f) * 3f, -1f, (rng.nextFloat() - 0.5f) * 3f, 0.8f, 0.12f, 0xFF8FD8FF.toInt(), 1f)
        }
    }

    /** A round bar from one point to another (the streaks' segments). */
    private fun tubeSegment(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, thick: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, (x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f)
        Matrix.rotateM(model, 0, -Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(atan2(dy.toDouble(), hypot(dx, dz).toDouble())).toFloat() - 90f, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, thick, len + thick * 0.8f, thick)
        lit.mat4("uModel", model)
        tube.draw()
    }

    /**
     * Dimension: the ground tearing open. A black hole in the floor exactly as wide as what it hits, its edge a broken
     * ring of cyan and violet, filling in toward the moment it goes off, with fragments of floor flickering over it.
     */
    private fun drawRift(x: Float, z: Float, r: Float, t: Float) {
        val flick = if ((time * 20f).toInt() % 3 == 0) 0.75f else 1f
        lit.v4("uTint", 0.02f, 0f, 0.06f, (0.45f + 0.45f * t) * flick)
        setModel(x, 0.046f, z, r, 1f, r); sector(360f).draw()
        lit.v4("uTint", 0.45f, 0.2f, 0.9f, 0.5f * t)
        setModel(x, 0.05f, z, r * t, 1f, r * t); sector(360f).draw()
        lit.v4("uTint", 0.4f, 0.95f, 1f, 0.95f)
        setModel(x, 0.054f, z, r, 1f, r, time * 90f); dashRing.draw()
        lit.v4("uTint", 0.8f, 0.45f, 1f, 0.9f)
        setModel(x, 0.056f, z, r * 0.97f, 1f, r * 0.97f, -time * 130f); dashRing.draw()
        // Square fragments jumping about over it.
        val tick = (time * 14f).toInt()
        for (k in 0 until 6) {
            val a = siteHash(tick, k, 1) * 6.283f; val d = siteHash(tick, k, 2) * r
            val s = 0.08f + 0.18f * siteHash(tick, k, 3)
            lit.v4("uTint", if (k % 2 == 0) 0.4f else 0.85f, if (k % 2 == 0) 0.95f else 0.5f, 1f, 0.8f)
            setModel(x + cos(a) * d, 0.06f, z + sin(a) * d, s, 1f, s * 0.5f); rect.draw()
        }
    }

    /** A fixed pseudo-random number in 0..1 for grid site ([i], [j]) and channel [k]. */
    private fun siteHash(i: Int, j: Int, k: Int): Float {
        var h = (i * 73856093) xor (j * 19349663) xor (k * 83492791)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    /** Lightning: a jagged line of bits from ([x0], [y0], [z0]) down to ([x1], [y1], [z1]), its kinks set by [seed]. */
    private fun jagged(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, seed: Int, thick: Float) {
        val steps = 7
        var px = x0; var py = y0; var pz = z0
        for (k in 1..steps) {
            val u = k / steps.toFloat()
            val h = ((seed * 7919 + k * 104729) and 0x7FFFFFFF)
            val kink = if (k == steps) 0f else 0.35f * (1f - u * 0.5f)
            val nx = x0 + (x1 - x0) * u + (((h % 200) - 100) / 100f) * kink
            val nz = z0 + (z1 - z0) * u + ((((h / 200) % 200) - 100) / 100f) * kink
            val ny = y0 + (y1 - y0) * u
            val dx = nx - px; val dy = ny - py; val dz = nz - pz
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, (px + nx) / 2f, (py + ny) / 2f, (pz + nz) / 2f)
            // (Turn the bit's X axis along the stroke.)
            Matrix.rotateM(model, 0, -Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat(), 0f, 1f, 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(atan2(dy.toDouble(), hypot(dx, dz).toDouble())).toFloat(), 0f, 0f, 1f)
            Matrix.scaleM(model, 0, len + thick, thick, thick)
            lit.mat4("uModel", model)
            bit.draw()
            px = nx; py = ny; pz = nz
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
            if (f.hyperActive) {
                // Scorched ground under a hyper: a glowing patch that breathes, and two rings turning against each other.
                val beat = 0.5f + 0.5f * sin(time * 11f)
                lit.v4("uTint", 1f, 0.42f, 0.1f, 0.3f + 0.15f * beat)
                setModel(x, 0.036f, z, r * 1.7f, 1f, r * 1.7f); sector(360f).draw()
                lit.v4("uTint", 1f, 0.8f, 0.25f, 0.95f)
                setModel(x, 0.04f, z, r * (1.55f + 0.1f * beat), 1f, r * (1.55f + 0.1f * beat), -time * 200f); dashRing.draw()
                lit.v4("uTint", 0.85f, 0.35f, 1f, 0.95f)
                setModel(x, 0.042f, z, r * (1.95f - 0.1f * beat), 1f, r * (1.95f - 0.1f * beat), time * 140f); dashRing.draw()
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

        // Cracked ground where a hammer came down: the cracks shoot out glowing, then stay as scars for the match.
        for (q in quakes) {
            val age = time - q[3]
            val grow = (age / 0.22f).coerceIn(0f, 1f)
            val glow = (1f - age / 1.6f).coerceIn(0f, 1f)
            for (k in 0 until 8) {
                var ang = q[4] + k * 0.785f + (noise(q[4], k, 9) - 0.5f) * 0.4f
                var cx = q[0]; var cz = q[1]
                val piece = q[2] * (0.62f + 0.38f * noise(q[4], k, 7)) * grow / 3f
                for (sg in 0 until 3) {
                    ang += (noise(q[4], k, sg) - 0.5f) * 0.9f
                    lit.v4("uTint", 0.07f + 0.93f * glow, 0.04f + 0.5f * glow, 0.1f * (1f - glow), 0.82f)
                    setModel(cx, 0.032f, cz, piece, 1f, 0.13f - sg * 0.035f, -Math.toDegrees(ang.toDouble()).toFloat()); rect.draw()
                    cx += cos(ang) * piece; cz += sin(ang) * piece
                }
            }
        }

        // Marked ground (Boss Mode): where something is about to land. The patch fills in as its time runs out.
        for (h in world.hazards) {
            val t = (h.age / h.delay).coerceIn(0f, 1f)
            if (h.kind == HazardKind.RIFT) { drawRift(h.x, h.y, h.radius, t); continue }
            // Red for what is coming down on the player's side; gold for what their own side called in.
            val mine = h.team == p.team
            if (mine) lit.v4("uTint", 1f, 0.8f, 0.2f, 0.2f + 0.08f * sin(time * 14f)) else lit.v4("uTint", 1f, 0.16f, 0.12f, 0.26f + 0.1f * sin(time * 14f))
            setModel(h.x, 0.046f, h.y, h.radius, 1f, h.radius); sector(360f).draw()
            if (mine) lit.v4("uTint", 1f, 0.85f, 0.3f, 0.6f) else lit.v4("uTint", 1f, 0.3f, 0.16f, 0.7f)
            setModel(h.x, 0.05f, h.y, h.radius * t, 1f, h.radius * t); sector(360f).draw()
            lit.v4("uTint", 1f, 1f, 1f, 0.95f)
            setModel(h.x, 0.054f, h.y, h.radius, 1f, h.radius); ring.draw()
        }

        // A blade's swing: the arc it cut, bright for a moment and gone.
        var si = 0
        while (si < slashes.size) {
            val s = slashes[si]
            val u = (time - s[5]) / 0.22f
            if (u >= 1f) { slashes.removeAt(si); continue }
            val c = s[7].toRawBits()
            val grow = 0.75f + 0.25f * min(1f, u * 4f)
            lit.v4("uTint", r(c), g(c), b(c), 0.55f * (1f - u))
            setModel(s[0], 0.06f, s[1], s[3] * grow, 1f, s[3] * grow, s[2]); sector(s[4]).draw()
            lit.v4("uTint", 1f, 1f, 1f, (if (s[6] > 0f) 0.95f else 0.7f) * (1f - u))
            setModel(s[0], 0.064f, s[1], s[3] * grow, 1f, s[3] * grow, s[2] + s[4] * (0.5f - u)); sector(12f).draw()
            si++
        }

        // Rockets in the ground: a ring shows what each will reach when it goes off. Gold for the player's side, red for the other.
        for (pl in world.planted) {
            if (!pl.landed) continue
            val mine = pl.team == p.team
            val beat = 0.5f + 0.5f * sin(time * 6f + pl.x)
            if (mine) lit.v4("uTint", 1f, 0.8f, 0.2f, 0.07f + 0.04f * beat) else lit.v4("uTint", 1f, 0.16f, 0.12f, 0.1f + 0.05f * beat)
            setModel(pl.x, 0.046f, pl.y, pl.blast, 1f, pl.blast); sector(360f).draw()
            if (mine) lit.v4("uTint", 1f, 0.85f, 0.3f, 0.55f + 0.3f * beat) else lit.v4("uTint", 1f, 0.3f, 0.16f, 0.6f + 0.3f * beat)
            setModel(pl.x, 0.052f, pl.y, pl.blast, 1f, pl.blast); thinRing.draw()
        }

        // Stars in the air: where each will burst, a ring that tightens as it comes down. Gold for the player's side, red for the other.
        for (l in world.lobs) {
            val u = (l.age / l.flight).coerceIn(0f, 1f)
            val mine = l.team == p.team
            if (mine) lit.v4("uTint", 1f, 0.8f, 0.2f, 0.08f + 0.12f * u) else lit.v4("uTint", 1f, 0.16f, 0.12f, 0.1f + 0.14f * u)
            setModel(l.x, 0.046f, l.y, l.blast, 1f, l.blast); sector(360f).draw()
            val r = l.blast * (1.6f - 0.6f * u)
            if (mine) lit.v4("uTint", 1f, 0.85f, 0.3f, 0.7f) else lit.v4("uTint", 1f, 0.3f, 0.16f, 0.75f)
            setModel(l.x, 0.052f, l.y, r, 1f, r); thinRing.draw()
        }

        // A realm seen from outside: a turning rift on the ground where each of the two in it stands.
        for (f in world.fighters) {
            if (!f.alive || !f.inRealm || f.realm == p.realm) continue
            val r = f.radius * (1.5f + 0.2f * sin(time * 5f + f.id))
            lit.v4("uTint", 0.1f, 0.05f, 0.3f, 0.55f)
            setModel(f.x, 0.046f, f.y, r, 1f, r); sector(360f).draw()
            lit.v4("uTint", 0.7f, 0.45f, 1f, 0.85f)
            setModel(f.x, 0.052f, f.y, r, 1f, r, time * 140f); ring.draw()
            lit.v4("uTint", 0.5f, 0.95f, 1f, 0.8f)
            setModel(f.x, 0.056f, f.y, r * 0.6f, 1f, r * 0.6f, -time * 200f); thinRing.draw()
            if (rng.nextFloat() < 0.25f) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(f.x + cos(a) * r, 0.1f, f.y + sin(a) * r, -cos(a) * 1.2f, 1.5f + rng.nextFloat() * 1.5f, -sin(a) * 1.2f, 0.5f, 0.14f, 0xFFB98CFF.toInt(), 0.9f)
            }
        }
        // A tempest: she is the tornado. One wide dark whirlwind whose foot covers exactly the ground it strikes (the
        // user wanted no tornado inside a bigger circle: what it touches is what it hits), flaring a little toward the top,
        // bending as it goes and leaning back from where it is heading, with pale streaks whipping round it, dust torn
        // up at its foot, and lightning flickering inside it.
        for (f in world.fighters) {
            if (!f.alive || !f.inTempest || f.realm != p.realm) continue
            val fx = lerp(f.prevX, f.x, alpha)
            val fz = lerp(f.prevY, f.y, alpha)
            val reach = f.def.superSpec.radius + f.radius
            val skin = f.def.skins.getOrElse(f.skin) { f.def.skins[0] }
            val mine = f.team == p.team
            val grow = ((f.def.superSpec.tempestSeconds - f.tempestLeft) / 0.35f).coerceIn(0f, 1f)
            val fade = (f.tempestLeft / 0.4f).coerceIn(0f, 1f)
            val k0 = grow * fade
            // (Its shadow on the ground is its own foot, tinted for whose side it is on.)
            if (mine) lit.v4("uTint", 0.2f, 0.3f, 0.45f, 0.35f * k0) else lit.v4("uTint", 0.45f, 0.12f, 0.12f, 0.4f * k0)
            setModel(fx, 0.046f, fz, reach, 1f, reach); sector(360f).draw()
            val height = 3.4f * k0
            val top = reach * 1.35f
            // (The cloud lights up from inside when lightning goes.)
            val lastBolt = tempestBolts.lastOrNull { it[6].toInt() == f.id }
            val flick = ((time * 4.1f + f.id * 0.37f) % 1f)
            val flash = maxOf(if (lastBolt != null) (1f - (time - lastBolt[2]) / 0.25f).coerceIn(0f, 1f) else 0f, if (flick < 0.12f) 0.4f else 0f)
            val lean = 0.16f
            val segs = 12
            for (k in 0 until segs) {
                val u = (k + 0.5f) / segs
                val y = 0.05f + u * height
                val r = (reach + (top - reach) * u * kotlin.math.sqrt(u)) * (0.6f + 0.4f * k0)
                val bend = 0.4f * u * u
                val cx = fx + sin(time * 2.3f + u * 2.8f) * bend - f.vx * lean * u
                val cz = fz + cos(time * 1.9f + u * 2.4f) * bend - f.vy * lean * u
                val h = height / segs * 1.7f
                // One wall of storm cloud, dusty at the foot and darker the higher it goes, lighting up with the lightning.
                val dark = 0.42f - 0.22f * u + 0.25f * flash
                lit.v4("uTint", dark * 1.05f, dark, dark * 1.15f, k0)
                setModel(cx, y, cz, r, h, r, time * 900f); funnel.draw()
                // Streaks whipping round it, fast.
                for (st in 0 until 5) {
                    val a = time * 15f + k * 0.85f + st * 1.2566f
                    val rr = r * 1.04f
                    lit.v4("uTint", 0.86f, 0.9f, 1f, 0.75f * k0)
                    setModel(cx + cos(a) * rr, y + 0.05f * sin(a * 2f), cz + sin(a) * rr, 0.42f * r + 0.12f, 0.05f, 0.07f,
                        -Math.toDegrees(a.toDouble()).toFloat() - 90f); bit.draw()
                }
            }
            // The thundercloud it hangs from: a churning ring of dark puffs round the top, over a dark core, turning.
            val capX = fx + sin(time * 2.3f + 2.8f) * 0.35f - f.vx * lean
            val capZ = fz + cos(time * 1.9f + 2.4f) * 0.35f - f.vy * lean
            val capY = height + 0.15f
            val cd = 0.2f + 0.3f * flash
            for (n in 0 until 9) {
                val a = -time * 4f + n * 0.698f
                val pr = top * (0.9f + 0.1f * sin(time * 3f + n * 1.7f))
                val ps = top * (0.22f + 0.04f * sin(time * 4f + n))
                val c = 0.24f + 0.06f * (n % 3) + 0.35f * flash
                lit.v4("uTint", c * 0.85f, c * 0.88f, c * 1.1f, 0.8f * k0)
                setModel(capX + cos(a) * pr, capY + 0.1f * sin(time * 5f + n), capZ + sin(a) * pr, ps, ps * 0.62f, ps); sphere.draw()
                val c2 = c + 0.12f
                lit.v4("uTint", c2 * 0.85f, c2 * 0.88f, c2 * 1.1f, 0.8f * k0)
                setModel(capX + cos(a + 0.35f) * pr * 0.8f, capY + 0.22f, capZ + sin(a + 0.35f) * pr * 0.8f, ps * 0.6f, ps * 0.42f, ps * 0.6f); sphere.draw()
            }
            // Pale streaks spiralling into the middle of the cloud.
            for (n in 0 until 6) {
                val a = -time * 5f + n * 1.047f
                val rr = top * 0.7f
                lit.v4("uTint", 0.8f, 0.84f, 0.95f, 0.6f * k0)
                setModel(capX + cos(a) * rr, capY + 0.2f, capZ + sin(a) * rr, top * 0.35f, 0.04f, 0.07f, -Math.toDegrees(a.toDouble()).toFloat() - 70f); bit.draw()
            }
            // Lightning flickering inside the funnel, top to bottom.
            if (flick < 0.22f && k0 > 0.5f) {
                lit.v4("uTint", 0.85f, 0.97f, 1f, 1f)
                jagged(capX, height, capZ, fx + sin(time * 7f) * 0.3f, 0.2f, fz + cos(time * 5f) * 0.3f, (time * 4.1f).toInt() * 31 + f.id, 0.05f)
                tint(skin.accent, 0.9f)
                jagged(capX + 0.3f, height * 0.8f, capZ, capX + cos(time * 9f) * top, height * 0.6f, capZ + sin(time * 9f) * top, (time * 4.1f).toInt() * 17 + f.id, 0.035f)
            }
            // Dust torn up at its foot, cloud flung off its top, and sparks in the wind.
            for (n in 0 until 2) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(fx + cos(a) * reach, 0.1f, fz + sin(a) * reach, -sin(a) * 5f + cos(a) * 1.5f, 0.8f + rng.nextFloat(), cos(a) * 5f + sin(a) * 1.5f, 0.45f, 0.16f, 0xFFB9B39F.toInt(), 0.75f)
            }
            if (rng.nextFloat() < 0.7f) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(fx + cos(a) * top, height, fz + sin(a) * top, -sin(a) * 4f, 0.3f, cos(a) * 4f, 0.6f, 0.24f, 0xFF6D7391.toInt(), 0.7f)
            }
            if (rng.nextFloat() < 0.5f) {
                val a = rng.nextFloat() * 6.28f
                val uu = rng.nextFloat()
                val rr = reach + (top - reach) * uu * kotlin.math.sqrt(uu)
                particles.spawn(fx + cos(a) * rr, uu * height, fz + sin(a) * rr, -sin(a) * 6f, 1f, cos(a) * 6f, 0.3f, 0.1f, skin.accent.toInt(), 1f)
            }
        }
        // Its lightning bolts: a jagged stroke from high above down to where each landed, and a flash on the ground.
        run {
            val bi = tempestBolts.iterator()
            while (bi.hasNext()) {
                val b = bi.next()
                val age = time - b[2]
                if (age > 0.3f || age < 0f) { bi.remove(); continue }
                val k = 1f - age / 0.3f
                lit.v4("uTint", 0.75f, 0.95f, 1f, 0.5f * k)
                val rr = io.github.projectwip.data.Balance.TEMPEST_BOLT_RADIUS * (0.6f + 0.6f * age / 0.3f)
                setModel(b[0], 0.055f, b[1], rr, 1f, rr); sector(360f).draw()
                if (age < 0.18f) {
                    // (A glow round the stroke, then the bright core.)
                    lit.v4("uTint", 0.5f, 0.85f, 1f, 0.45f * k)
                    jagged(b[3], 4.1f, b[4], b[0], 0.05f, b[1], b[5].toInt(), 0.2f)
                    lit.v4("uTint", 0.95f, 1f, 1f, 1f)
                    jagged(b[3], 4.1f, b[4], b[0], 0.05f, b[1], b[5].toInt(), 0.08f)
                }
            }
        }
        // And from inside: rings of light run out from under the captive, across the dark floor.
        if (p.inRealm) for (f in world.fighters) {
            if (!f.alive || !f.captive || f.realm != p.realm) continue
            for (j in 0 until 3) {
                val u = (time * 0.5f + j / 3f) % 1f
                val r = f.radius + 0.4f + u * 5f
                lit.v4("uTint", 0.55f, 0.85f, 1f, 0.55f * (1f - u))
                setModel(f.x, 0.05f, f.y, r, 1f, r); thinRing.draw()
            }
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
                val a = world.arena
                // The reticle is frosted glass laid on the floor: a see-through fill that grows a little stronger
                // toward the far end, a crisp bright outline with a faint shadow just outside it (so it reads on pale
                // floors too), and faint ticks flowing outward along it. White for the attack, gold for the super.
                val pulse = 0.5f + 0.5f * sin(time * 7f)
                val gold = inp.aimingSuper
                fun col(alpha: Float) = if (gold) lit.v4("uTint", 1f, 0.8f, 0.16f, alpha) else lit.v4("uTint", 1f, 1f, 1f, alpha)
                fun tint(layer: Int) = when (layer) {
                    0 -> lit.v4("uTint", 0.05f, 0.03f, 0.12f, 0.3f)
                    2 -> col(0.95f)
                    else -> col(0.38f + 0.08f * pulse)
                }
                val edge = 0.07f
                val tickGap = 1.1f
                val flow = (time * 2.4f) % tickGap
                fun beam(length: Float, width: Float) {
                    val nx = -dz
                    val nz = dx
                    val half = width / 2f
                    // The fill, in three bands that strengthen toward the far end.
                    val band = length / 3f
                    for (k in 0..2) {
                        col(0.3f + 0.09f * k + 0.05f * pulse)
                        setModel(px + dx * band * k, 0.05f, pz + dz * band * k, band, 1f, width, yaw); rect.draw()
                    }
                    // Ticks flowing out along it.
                    var u = flow + 0.3f
                    while (u < length - 0.2f) {
                        col(0.3f * (1f - u / length) + 0.22f)
                        setModel(px + dx * u, 0.052f, pz + dz * u, 0.1f, 1f, width * 0.7f, yaw); rect.draw()
                        u += tickGap
                    }
                    // The outline down both sides and across the far end, each with its shadow outside it.
                    for (side in intArrayOf(-1, 1)) {
                        val so = (half + edge * 0.55f) * side
                        val bo = (half - edge * 0.5f) * side
                        tint(0); setModel(px + nx * so, 0.051f, pz + nz * so, length, 1f, edge, yaw); rect.draw()
                        col(0.88f); setModel(px + nx * bo, 0.054f, pz + nz * bo, length - edge, 1f, edge, yaw); rect.draw()
                    }
                    tint(0); setModel(px + dx * length, 0.051f, pz + dz * length, edge, 1f, width + edge * 2.1f, yaw); rect.draw()
                    tint(2); setModel(px + dx * (length - edge), 0.055f, pz + dz * (length - edge), edge, 1f, width, yaw); rect.draw()
                }
                fun fan(range: Float, degrees: Float) {
                    col(0.34f + 0.06f * pulse); setModel(px, 0.05f, pz, range, 1f, range, yaw); sector(degrees).draw()
                    // A stronger band round the far edge, then ticks flowing out as arcs.
                    col(0.2f); setModel(px, 0.051f, pz, range, 1f, range, yaw); arc(degrees, 0.22f).draw()
                    var u = flow + 0.45f
                    while (u < range - 0.25f) {
                        col(0.3f * (1f - u / range) + 0.22f)
                        setModel(px, 0.052f, pz, u, 1f, u, yaw); arc(degrees, 0.03f).draw()
                        u += tickGap
                    }
                    // The outline: both straight edges and the curved far end, with shadows outside them.
                    for (side in floatArrayOf(-1f, 1f)) {
                        tint(0); setModel(px, 0.051f, pz, range, 1f, edge * 1.9f, yaw + side * degrees / 2f); rect.draw()
                        col(0.88f); setModel(px, 0.054f, pz, range, 1f, edge, yaw + side * degrees / 2f); rect.draw()
                    }
                    tint(0); setModel(px, 0.051f, pz, range + edge, 1f, range + edge, yaw); arc(degrees, edge * 1.6f / (range + edge)).draw()
                    tint(2); setModel(px, 0.055f, pz, range, 1f, range, yaw); arc(degrees, edge / range).draw()
                }
                if (inp.aimingSuper) {
                    val s = p.def.superSpec
                    when (s.kind) {
                        SuperKind.VOLLEY -> fan(s.range, s.spreadDegrees + 8f)
                        SuperKind.PIERCE -> beam(clip(a, px, pz, dx, dz, s.range), s.radius * 3.2f)
                        SuperKind.RAM -> beam(s.range, p.radius * 2.2f)
                        // The hammer's path, and the ground that will quake where it comes down.
                        SuperKind.QUAKE -> {
                            // It comes down on the first enemy in its way (or a wall, or the end of its reach), so
                            // that is where the reticle stops: the circle is the ground that will quake.
                            var reach = clip(a, px, pz, dx, dz, s.range)
                            for (o in world.fighters) {
                                if (o.team == p.team || !shown(o)) continue
                                val ox = o.x - px
                                val oz = o.y - pz
                                val on = ox * dx + oz * dz
                                val off = kotlin.math.abs(ox * dz - oz * dx)
                                if (on > 0f && off < o.radius + 0.42f) reach = min(reach, on)
                            }
                            beam(reach, 0.5f)
                            val tx = px + dx * reach
                            val tz = pz + dz * reach
                            lit.v4("uTint", 1f, 0.78f, 0.1f, 0.2f + 0.1f * pulse)
                            setModel(tx, 0.05f, tz, s.radius, 1f, s.radius); sector(360f).draw()
                            tint(2); setModel(tx, 0.056f, tz, s.radius, 1f, s.radius); ring.draw()
                        }
                        // The line he will leap along (over walls), and a mark on whoever he will come down on.
                        SuperKind.CORRUPT, SuperKind.REALM, SuperKind.BLINK -> {
                            val t = world.corruptTarget(p, dx, dz)
                            val reach = if (t != null) (t.x - px) * dx + (t.y - pz) * dz else s.range
                            beam(reach.coerceAtLeast(0.3f), p.radius * 2f)
                            if (t != null) {
                                val tx = lerp(t.prevX, t.x, alpha)
                                val tz = lerp(t.prevY, t.y, alpha)
                                val r = t.radius * (1.5f + 0.25f * pulse)
                                lit.v4("uTint", 1f, 0.78f, 0.1f, 0.3f + 0.15f * pulse)
                                setModel(tx, 0.05f, tz, r, 1f, r); sector(360f).draw()
                                tint(2); setModel(tx, 0.056f, tz, r, 1f, r); ring.draw()
                            }
                        }
                        // The tempest needs no aim: it is a ring round her, as far as it reaches.
                        SuperKind.TEMPEST -> {
                            lit.v4("uTint", 0.5f, 0.85f, 1f, 0.18f + 0.1f * pulse)
                            setModel(px, 0.05f, pz, s.radius + p.radius, 1f, s.radius + p.radius); sector(360f).draw()
                            tint(2); setModel(px, 0.056f, pz, s.radius + p.radius, 1f, s.radius + p.radius, time * 120f); ring.draw()
                        }
                        SuperKind.SWARM, SuperKind.BREW -> {
                            // The rockets go up and come down: an arc from the fighter, up and over, to one big
                            // circle where they will land. How far the stick is pushed is how far away that is.
                            val reach = min(1f, len) * s.range
                            val tx = px + dx * reach
                            val tz = pz + dz * reach
                            lit.v4("uTint", 1f, 0.78f, 0.1f, 0.26f + 0.12f * pulse)
                            setModel(tx, 0.05f, tz, s.radius, 1f, s.radius); sector(360f).draw()
                            tint(2); setModel(tx, 0.056f, tz, s.radius, 1f, s.radius); ring.draw()
                            tint(1); setModel(tx, 0.058f, tz, s.radius * (0.3f + 0.7f * pulse), 1f, s.radius * (0.3f + 0.7f * pulse)); ring.draw()
                            tint(2); setModel(tx, 0.06f, tz, 0.22f, 1f, 0.22f); sector(360f).draw()
                            // The arc: beads strung along the rockets' path, running from the fighter to the circle.
                            lit.i("uMode", 0)
                            lit.f("uEmissive", 0.9f)
                            val beads = 16
                            val peak = 2.2f + reach * 0.3f
                            for (i in 0..beads) {
                                val u = ((i + (time * 1.5f) % 1f) / (beads + 1)).coerceIn(0f, 1f)
                                val size = 0.1f + 0.07f * sin(u * 3.1416f)
                                lit.v4("uTint", 1f, 0.82f, 0.2f, 1f)
                                setModel(px + dx * reach * u, 1f + peak * 4f * u * (1f - u) - u, pz + dz * reach * u, size, size, size); sphere.draw()
                            }
                            lit.f("uEmissive", 0f)
                            lit.i("uMode", 1)
                        }
                    }
                } else {
                    val at = p.def.attack
                    if (at.shape == AttackShape.PLANT || at.shape == AttackShape.STAR) {
                        // Where the rocket will stick in the ground, and everything it will reach when it is set off.
                        val reach = min(1f, len) * at.range
                        val tx = px + dx * reach
                        val tz = pz + dz * reach
                        lit.v4("uTint", 1f, 1f, 1f, 0.2f + 0.1f * pulse)
                        setModel(tx, 0.05f, tz, at.blast, 1f, at.blast); sector(360f).draw()
                        tint(0); setModel(tx, 0.054f, tz, at.blast + 0.06f, 1f, at.blast + 0.06f); thinRing.draw()
                        tint(1); setModel(tx, 0.056f, tz, at.blast, 1f, at.blast); thinRing.draw()
                        tint(2); setModel(tx, 0.06f, tz, 0.2f, 1f, 0.2f); sector(360f).draw()
                        tint(1); setModel(px, 0.05f, pz, reach, 1f, 0.08f, yaw); rect.draw()
                    }
                    else if (at.shape == AttackShape.SLASH) fan(at.range * (if (p.edgeFull) io.github.projectwip.data.Balance.EDGE_REACH else 1f), at.spreadDegrees)
                    else if (at.shape == AttackShape.SPREAD) fan(at.range, at.spreadDegrees + 8f)
                    else if (at.shape == AttackShape.ROCKETS) beam(clip(a, px, pz, dx, dz, at.range), io.github.projectwip.data.Balance.ROCKET_LANE * (at.projectiles - 1) + 0.4f)
                    else beam(clip(a, px, pz, dx, dz, at.range), if (at.shape == AttackShape.SMASH) 0.95f else if (at.shape == AttackShape.BURST || at.shape == AttackShape.PAWS) 0.55f else 0.34f)
                }
            }
        }

        // Bobbing arrow above the auto-aim target (lit, so it reads as a 3D object)
        val t = runner.autoTarget
        if (t != null || runner.autoCrate >= 0) {
            val ax = if (t != null) lerp(t.prevX, t.x, alpha) else runner.autoCrate % world.arena.width + 0.5f
            val az = if (t != null) lerp(t.prevY, t.y, alpha) else runner.autoCrate / world.arena.width + 0.5f
            val ay = if (t != null) headHeight(t.def) * t.scale + 0.9f else 1.7f
            lit.i("uMode", 0)
            lit.f("uEmissive", 0.7f)
            lit.v4("uTint", 1f, 0.8f, 0.2f, 1f)
            setModel(ax, ay + sin(time * 5f) * 0.12f, az, 1f, 1f, 1f, time * 120f)
            arrow.draw()
            lit.f("uEmissive", 0f)
            lit.i("uMode", 1)
        }
    }

    private val arcs = HashMap<Int, Mesh>()

    /** A band along the curved end of a fan [spread] degrees wide: unit outer radius, [thickness] of it deep. */
    private fun arc(spread: Float, thickness: Float): Mesh {
        val t = thickness.coerceIn(0.005f, 0.5f)
        return arcs.getOrPut(spread.toInt() * 1000 + (t * 999).toInt()) {
            MeshBuilder().apply { ring(1f - t, 1f, 28, -spread.toInt() / 2f, spread.toInt() / 2f) }.build()
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
        // A terminal hangs over whoever has someone on their back compiling code into them, for as long as it goes
        // on: the screen folds open and lines of code type themselves onto it, page after page.
        for (coder in world.fighters) {
            if (!coder.latched || !coder.alive) continue
            val victim = world.fighter(coder.latchedTo) ?: continue
            if (!shown(victim)) continue
            val age = time - (latchAt[coder.id] ?: time)
            val open = (age / 0.15f).coerceIn(0f, 1f)
            val page = age % 0.9f
            val x = lerp(victim.prevX, victim.x, alpha)
            val z = lerp(victim.prevY, victim.y, alpha)
            val y = (headHeight(victim.def) * victim.scale + 1.25f) + 0.05f * sin(age * 9f)
            val sk = coder.def.skins[coder.skin]
            tint(0xFF0A1420)
            setModel(x, y, z, 1.25f * open, 0.8f * open, 0.08f); bit.draw()
            tint(sk.secondary, 0.9f)
            setModel(x, y, z + 0.03f, 1.15f * open, 0.7f * open, 0.08f); bit.draw()
            tint(0xFF0A1420)
            for (line in 0 until 5) {
                val typed = ((page - line * 0.13f) / 0.11f).coerceIn(0f, 1f) * open
                if (typed <= 0f) continue
                val full = 0.9f - 0.17f * ((line * 3 + (age / 0.9f).toInt()) % 4)
                setModel(x - 0.5f + full * typed / 2f, y + 0.24f - line * 0.12f, z + 0.08f, full * typed, 0.05f, 0.03f); bit.draw()
            }
        }
        // Poison bubbles up off anyone who is hexed.
        for (f in world.fighters) {
            // (The more hexes are on them, the harder it boils.)
            if (!f.hexed || !f.alive || !shown(f) || rng.nextFloat() > 0.3f + 0.2f * f.hexes.size) continue
            val a = rng.nextFloat() * 6.28f
            val c = world.fighter(f.hexes.last().by)?.let { it.def.skins[it.skin].secondary.toInt() } ?: 0xFF7CFF4F.toInt()
            particles.spawn(f.x + cos(a) * 0.35f, 0.3f + rng.nextFloat() * 0.9f, f.y + sin(a) * 0.35f, 0f, 1.6f + rng.nextFloat() * 1.4f, 0f, 0.55f, 0.16f, c, 0.9f)
        }
        // Star dust sinks round anyone who is slowed.
        for (f in world.fighters) {
            if (!f.slowed || !f.alive || !shown(f) || rng.nextFloat() > 0.45f) continue
            val a = rng.nextFloat() * 6.28f
            particles.spawn(f.x + cos(a) * 0.45f, 0.9f + rng.nextFloat() * 0.8f, f.y + sin(a) * 0.45f, 0f, -1.2f - rng.nextFloat(), 0f, 0.6f, 0.12f, 0xFFFF4D6A.toInt(), 0.9f)
        }
        // Sparks of stray code come off anyone who is corrupted and standing there.
        for (f in world.fighters) {
            if (!f.enthralled || !f.alive || !shown(f) || rng.nextFloat() > 0.35f) continue
            val a = rng.nextFloat() * 6.28f
            val c = world.fighter(f.thrallOf)?.let { it.def.skins[it.skin].secondary.toInt() } ?: 0xFF29F0FF.toInt()
            particles.spawn(f.x + cos(a) * 0.4f, 0.4f + rng.nextFloat() * 1.2f, f.y + sin(a) * 0.4f, cos(a) * 1.5f, 1.5f + rng.nextFloat() * 2f, sin(a) * 1.5f, 0.4f, 0.13f, c, 0.95f)
        }

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
                ShotStyle.BIT -> {
                    // A square bit of light, tumbling as it flies.
                    tint(skin.accent)
                    val size = pr.radius * (if (pr.isSuper) 1.5f else 1.25f)
                    setModel(x, 0.7f, z, size, size, size, yaw + pr.age * 900f); bit.draw()
                }
                ShotStyle.COMPUTER -> {
                    // A whole desktop computer, spinning as it flies: the monitor with its lit screen showing through
                    // both sides, the stand and foot under it, and the keyboard trailing along below.
                    val size = pr.radius * 2.1f
                    val spin = yaw + pr.age * 540f
                    val y = 0.95f + 0.08f * sin(pr.age * 14f)
                    tint(0xFF6A7390)
                    setModel(x, y, z, size * 1.25f, size * 0.9f, size * 0.4f, spin); bit.draw()
                    tint(skin.secondary)
                    setModel(x, y, z, size * 1.05f, size * 0.7f, size * 0.46f, spin); bit.draw()
                    tint(0xFF474E66)
                    setModel(x, y - size * 0.57f, z, size * 0.2f, size * 0.26f, size * 0.16f, spin); bit.draw()
                    setModel(x, y - size * 0.73f, z, size * 0.7f, size * 0.08f, size * 0.45f, spin); bit.draw()
                    tint(0xFF8C95B4)
                    setModel(x, y - size * 0.95f, z, size * 1.05f, size * 0.07f, size * 0.36f, spin + 35f); bit.draw()
                }
                ShotStyle.ARROW -> {
                    // A giant arrow: a long shaft, fletching at the back, and a cut diamond for a tip, glinting as
                    // it turns toward whoever it is after.
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val dx = pr.vx / len; val dz = pr.vy / len
                    val y = 0.85f
                    tint(skin.secondary)
                    setModel(x - dx * 0.25f, y, z - dz * 0.25f, 1.15f, 0.06f, 0.06f, yaw); bit.draw()
                    tint(skin.primary)
                    for (k in 0 until 2) for (up in floatArrayOf(0.06f, -0.06f)) {
                        setModel(x - dx * (0.72f + k * 0.14f), y + up, z - dz * (0.72f + k * 0.14f), 0.13f, 0.11f, 0.02f, yaw); bit.draw()
                    }
                    lit.f("uEmissive", 0.6f)
                    tint(skin.accent)
                    setModel(x + dx * 0.42f, y, z + dz * 0.42f, 0.24f, 0.15f, 0.15f, yaw); octa.draw()
                    lit.f("uEmissive", 0f)
                }
                ShotStyle.GUST -> {
                    // A blast of cold wind: a curling crescent of frosty air at the front, as wide as what it hits,
                    // its puffs churning as it flies (the wisps streaming off behind it are in [drawSprites]).
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val dx = pr.vx / len; val dz = pr.vy / len
                    val sx = -dz; val sz = dx
                    val r = pr.radius
                    lit.f("uEmissive", 0.5f)
                    for (k in -2..2) {
                        val t = k / 2f
                        val back = t * t * r * 1.2f
                        val churn = sin(pr.age * 26f + k * 1.7f)
                        val s = r * (0.62f - 0.1f * kotlin.math.abs(t)) * (1f + 0.12f * churn)
                        tint(if (k == 0) 0xFFF6FCFF else if (k % 2 == 0) 0xFFBFE6FF else 0xFFDDF2FF)
                        setModel(x + sx * t * r - dx * back, 0.72f + 0.05f * churn, z + sz * t * r - dz * back, s * 1.25f, s * 0.6f, s, yaw + pr.age * 420f + k * 40f)
                        sphere.draw()
                    }
                    lit.f("uEmissive", 0f)
                }
                ShotStyle.STAR -> {
                    // A spite star: a puffy star spinning end over end as it flies, with a trail of little ones
                    // dwindling behind it.
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val dx = pr.vx / len; val dz = pr.vy / len
                    val k = pr.radius * 1.5f
                    val y = 0.8f + 0.05f * sin(pr.age * 15f)
                    tint(skin.accent)
                    setModel(x, y, z, k, k, k, yaw + 90f + pr.age * 1100f); starShot.draw()
                    tint(skin.primary)
                    for (j in 1..3) {
                        val m = k * (0.55f - j * 0.12f)
                        setModel(x - dx * k * (0.8f + j * 0.7f), y + sin(pr.age * 20f + j) * 0.06f, z - dz * k * (0.8f + j * 0.7f), m, m, m, pr.age * 700f + j * 40f); starShot.draw()
                    }
                }
                ShotStyle.HEX -> {
                    // A hex: a rune thrown flat, a triangle of light wheeling round a lidless eye, with a tail of
                    // dwindling drops strung out behind it.
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val dx = pr.vx / len; val dz = pr.vy / len
                    val k = pr.radius * (if (pr.isSuper) 1.15f else 1.4f)
                    val y = 0.78f + 0.05f * sin(pr.age * 17f)
                    val side = k * 3.1f
                    val inner = side / 3.4641f
                    tint(skin.accent)
                    for (j in 0 until 3) {
                        val a = pr.age * 9f + j * 2.0944f
                        setModel(x + cos(a) * inner, y, z + sin(a) * inner, side, k * 0.22f, k * 0.22f, -Math.toDegrees(a.toDouble()).toFloat() - 90f); bit.draw()
                    }
                    tint(skin.secondary)
                    setModel(x, y, z, k * 0.75f, k * 0.45f, k * 0.75f); sphere.draw()
                    tint(0xFF0A1420)
                    setModel(x + dx * k * 0.4f, y + k * 0.1f, z + dz * k * 0.4f, k * 0.3f, k * 0.3f, k * 0.3f); sphere.draw()
                    tint(skin.secondary)
                    for (j in 1..4) {
                        val drop = k * (0.5f - j * 0.09f)
                        val sway = sin(pr.age * 22f - j * 1.1f) * k * 0.35f
                        setModel(x - dx * k * (0.9f + j * 0.75f) - dz * sway, y - j * 0.03f, z - dz * k * (0.9f + j * 0.75f) + dx * sway, drop, drop, drop); sphere.draw()
                    }
                }
                ShotStyle.PAW -> {
                    // A paw print flying flat, toes first: the big pad, and four toes fanned out ahead of it.
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val dx = pr.vx / len; val dz = pr.vy / len
                    val k = pr.radius * (1.25f + 0.12f * sin(pr.age * 22f))
                    tint(skin.secondary)
                    setModel(x, 0.7f, z, k, k * 0.45f, k * 0.9f, yaw); sphere.draw()
                    for (i in 0 until 4) {
                        val side = (i - 1.5f) * 0.55f
                        val fwd = 1.2f - kotlin.math.abs(side) * 0.35f
                        setModel(x + (dx * fwd - dz * side) * k, 0.7f, z + (dz * fwd + dx * side) * k, k * 0.36f, k * 0.3f, k * 0.36f); sphere.draw()
                    }
                }
                ShotStyle.HAMMER -> {
                    // A giant hammer flying straight at whoever it was thrown at, head first, with its shadow under it.
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val hx = pr.vx / len * 0.5f
                    val hz = pr.vy / len * 0.5f
                    val y = 1f
                    tint(0xFF0A1420, 0.35f)
                    setModel(x, 0.06f, z, 1.5f, 0.02f, 0.9f, yaw); bit.draw()
                    tint(0xFF7A5A3A)
                    setModel(x - hx * 0.4f, y, z - hz * 0.4f, 1.4f, 0.14f, 0.14f, yaw); bit.draw()
                    tint(0xFF8C95B4)
                    setModel(x + hx, y, z + hz, 0.56f, 0.62f, 1.1f, yaw); bit.draw()
                    tint(skin.secondary)
                    setModel(x + hx, y, z + hz, 0.22f, 0.68f, 1.16f, yaw); bit.draw()
                    tint(skin.accent)
                    setModel(x - hx * 2.2f, y, z - hz * 2.2f, 0.13f, 0.22f, 0.22f, yaw); bit.draw()
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
                ShotStyle.ROCKET -> {
                    // A proper rocket: a body in the fighter's colour, a nose cone and fins in its second, and a flame at its tail.
                    val y = 0.72f
                    val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                    val dx = pr.vx / len; val dz = pr.vy / len
                    val k = pr.radius / 0.2f
                    val size = 0.85f * k
                    tint(skin.primary)
                    setModel(x, y, z, size, size, size, yaw); rocketBody.draw()
                    tint(skin.secondary)
                    setModel(x, y, z, size, size, size, yaw); rocketTrim.draw()
                    val flame = (0.16f + 0.06f * sin(time * 40f + pr.x * 9f)) * k
                    tint(0xFFFFE066L)
                    setModel(x - dx * 0.5f * size, y, z - dz * 0.5f * size, flame * 2f, flame, flame, yaw); sphere.draw()
                    tint(0xFFFF7A1FL)
                    setModel(x - dx * 0.68f * size, y, z - dz * 0.68f * size, flame * 1.6f, flame * 0.7f, flame * 0.7f, yaw); sphere.draw()
                }
            }
        }
        // Lobbed stars: spinning end over end along their arc, with their trail.
        for (l in world.lobs) {
            val owner = world.fighter(l.ownerId) ?: continue
            val skin = owner.def.skins[owner.skin]
            val u = (l.age / l.flight).coerceIn(0f, 1f)
            val x = lerp(l.fromX, l.x, u)
            val z = lerp(l.fromY, l.y, u)
            val y = 0.95f + (1.6f + hypot(l.x - l.fromX, l.y - l.fromY) * 0.3f) * 4f * u * (1f - u) - 0.75f * u
            val yaw = -Math.toDegrees(atan2(l.y - l.fromY, l.x - l.fromX).toDouble()).toFloat()
            val k = 0.42f
            tint(skin.accent)
            setModel(x, y, z, k, k, k, yaw + 90f + l.age * 900f); starShot.draw()
            if (rng.nextFloat() < 0.6f) particles.spawn(x, y, z, 0f, 0.2f, 0f, 0.3f, 0.16f, skin.accent.toInt(), 0.8f)
        }
        // Planted rockets: lobbed over to their spot, then standing on their nose in the ground with a light blinking at the tail.
        for (pl in world.planted) {
            val owner = world.fighter(pl.ownerId) ?: continue
            val skin = owner.def.skins[owner.skin]
            if (!pl.landed) {
                val u = (pl.age / pl.flight).coerceIn(0f, 1f)
                val x = lerp(pl.fromX, pl.x, u)
                val z = lerp(pl.fromY, pl.y, u)
                val y = 0.95f + (1.5f + hypot(pl.x - pl.fromX, pl.y - pl.fromY) * 0.25f) * 4f * u * (1f - u) - 0.5f * u
                val yaw = -Math.toDegrees(atan2(pl.y - pl.fromY, pl.x - pl.fromX).toDouble()).toFloat()
                setModel(x, y, z, 1f, 1f, 1f, yaw)
                // (Level on the way up, nose first on the way down.)
                tint(skin.primary); (if (u < 0.55f) rocketBody else rocketDownBody).draw()
                tint(skin.secondary); (if (u < 0.55f) rocketTrim else rocketDownTrim).draw()
                if (rng.nextFloat() < 0.7f) particles.spawn(x, y, z, 0f, 0.2f, 0f, 0.3f, 0.2f, 0xFFFFB03A.toInt(), 0.8f)
            } else {
                setModel(pl.x, 0.4f, pl.y, 1.05f, 1.05f, 1.05f, pl.x * 97f + pl.y * 41f)
                tint(skin.primary); rocketDownBody.draw()
                tint(skin.secondary); rocketDownTrim.draw()
                tint(if ((time * 5f + pl.x * 3f).toInt() % 2 == 0) skin.accent else 0xFFFFFFFFL)
                setModel(pl.x, 0.86f, pl.y, 0.1f, 0.1f, 0.1f); sphere.draw()
            }
        }
        // In a realm, a rocket of its own comes straight down on the captive every second.
        for (f in world.fighters) {
            if (f.realm != f.id || f.realm != match.player.realm || f.def.superSpec.shadows > 0) continue
            val t = world.fighters.firstOrNull { it.realm == f.id && it !== f } ?: continue
            val skin = f.def.skins[f.skin]
            val u = (1f - f.realmTick / io.github.projectwip.data.Balance.REALM_ROCKET_SECONDS).coerceIn(0f, 1f)
            val y = 1.1f * t.scale + 10f * (1f - u) * (1f - u)
            setModel(t.x, y, t.y, 1.3f, 1.3f, 1.3f, time * 200f)
            tint(skin.primary); rocketDownBody.draw()
            tint(skin.secondary); rocketDownTrim.draw()
            tint(0xFFFFE066L)
            setModel(t.x, y + 0.6f, t.y, 0.16f, 0.4f, 0.16f); sphere.draw()
        }
        // What is about to land on the marked ground: a rocket dropping out of the sky, or a mine blinking where it lies.
        for (h in world.hazards) {
            val t = (h.age / h.delay).coerceIn(0f, 1f)
            when (h.kind) {
                HazardKind.ROCKET -> {
                    val y = 0.5f + 11f * (1f - t) * (1f - t)
                    tint(0xFFFF7A1FL)
                    setModel(h.x, y, h.y, 0.24f, 0.7f, 0.24f); octa.draw()
                    tint(0xFFFFE066L)
                    setModel(h.x, y + 0.7f, h.y, 0.16f, 0.4f, 0.16f); sphere.draw()
                }
                HazardKind.MINE -> {
                    tint(if ((time * (4f + 10f * t)).toInt() % 2 == 0) 0xFFFF4FA3L else 0xFFFFFFFFL)
                    setModel(h.x, 0.22f, h.y, 0.3f, 0.2f, 0.3f); sphere.draw()
                }
                HazardKind.SLAM, HazardKind.RIFT -> Unit
                // A flask on its way: up from whoever threw it and down onto the mark, turning as it goes.
                HazardKind.BREW -> {
                    val from = world.fighter(h.ownerId)
                    val sk = from?.let { it.def.skins[it.skin] }
                    val fx = from?.x ?: h.x
                    val fz = from?.y ?: h.y
                    val x = lerp(fx, h.x, t)
                    val z = lerp(fz, h.y, t)
                    val y = 1.1f + (2.2f + hypot(h.x - fx, h.y - fz) * 0.25f) * 4f * t * (1f - t) - t * 0.6f
                    tint(sk?.secondary ?: 0xFF7CFF4FL)
                    setModel(x, y, z, 0.3f, 0.3f, 0.3f); sphere.draw()
                    tint(0xFFDDE7F2L)
                    setModel(x, y + 0.3f, z, 0.16f, 0.26f, 0.16f, time * 300f); bit.draw()
                    tint(sk?.accent ?: 0xFFFFD23FL)
                    setModel(x, y + 0.46f, z, 0.2f, 0.09f, 0.2f, time * 300f); bit.draw()
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
            if (pr.style == ShotStyle.GUST) {
                // Cold air streaming off the gust (soft wisps, not glow), and a glint of frost now and then.
                val len = hypot(pr.vx, pr.vy).coerceAtLeast(0.001f)
                val sx = -pr.vy / len; val sz = pr.vx / len
                val side = (rng.nextFloat() - 0.5f) * pr.radius * 2f
                particles.spawn(x + sx * side, 0.7f, z + sz * side, -pr.vx / len * 1.5f + sx * side * 2f, 0.15f, -pr.vy / len * 1.5f + sz * side * 2f,
                    0.38f, pr.radius * 0.9f, 0xFFE4F5FF.toInt(), 0.42f, growth = 0.9f, add = false)
                if (rng.nextFloat() < 0.4f) particles.spawn(x + sx * side, 0.75f, z + sz * side, 0f, 0.3f, 0f, 0.3f, 0.07f, 0xFFCFF0FF.toInt(), 0.9f)
            } else if (pr.style == ShotStyle.ROCKET) {
                // A rocket leaves fire behind it, and a puff of smoke that hangs in the air.
                particles.spawn(x, 0.72f, z, 0f, 0.2f, 0f, 0.2f, 0.3f, c, 0.6f)
                if (rng.nextFloat() < 0.5f) particles.spawn(x, 0.72f, z, 0f, 0.5f, 0f, 0.7f, 0.42f, 0xFFD8D2E6.toInt(), 0.35f)
            } else if (rng.nextFloat() < 0.9f) particles.spawn(x, 0.72f, z, 0f, 0.1f, 0f, 0.18f, if (pr.style == ShotStyle.COMPUTER) 0.3f else pr.radius * 2.2f, c, 0.6f)
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
            val s = if (pr.style == ShotStyle.LANCE) 1.3f else if (pr.style == ShotStyle.ROCKET) 0.8f else pr.radius * 4.5f
            // (A thrown computer only gets a faint glow, so the thing itself can be seen.)
            sprites.add(x, 0.72f, z, if (pr.style == ShotStyle.COMPUTER) 1.1f else s, r(c), g(c), b(c), if (pr.style == ShotStyle.COMPUTER) 0.22f else 0.85f)
        }
        if (!world.inSky) for (pk in world.pickups) sprites.add(pk.x, 0.6f + sin(time * 3f + pk.x) * 0.12f, pk.y, 0.9f, 1f, 0.85f, 0.3f, 0.6f)
        addRealmSprites()
        for (l in arena.lamps) sprites.add(l[0], l[1], l[2], 1.1f + 0.05f * sin(time * 3f + l[0]), 1f, 0.9f, 0.55f, 0.55f)
        addEnergySprites(alpha)
        // Super-ready shimmer on the player
        val p = match.player
        if (p.alive && p.superReady) {
            repeat(1) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(p.x + cos(a) * 0.5f, 0.2f, p.y + sin(a) * 0.5f, 0f, 1.6f, 0f, 0.6f, 0.12f, 0xFFFFD640.toInt(), 0.9f)
            }
        }
        // A running hyper sets the fighter alight: flames licking up all round it, hot at the root and violet at the
        // tips, embers flying off, and a glow over the whole thing.
        for (f in world.fighters) {
            if (!f.hyperActive || !shown(f)) continue
            val fx = lerp(f.prevX, f.x, alpha); val fz = lerp(f.prevY, f.y, alpha)
            val big = f.radius / 0.42f
            repeat(5) {
                val a = rng.nextFloat() * 6.28f
                val out = (0.3f + rng.nextFloat() * 0.3f) * big
                val hot = rng.nextFloat()
                val colour = if (hot < 0.35f) 0xFFFFE27A.toInt() else if (hot < 0.75f) 0xFFFF7A1F.toInt() else 0xFFC04DFF.toInt()
                particles.spawn(fx + cos(a) * out, 0.15f + rng.nextFloat() * 0.5f, fz + sin(a) * out, cos(a) * 0.3f, 2.6f + rng.nextFloat() * 2.4f, sin(a) * 0.3f,
                    0.35f + rng.nextFloat() * 0.3f, (0.3f + rng.nextFloat() * 0.25f) * big, colour, 0.85f)
            }
            if (rng.nextFloat() < 0.5f) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(fx, 1f, fz, cos(a) * 1.5f, 3f + rng.nextFloat() * 3f, sin(a) * 1.5f, 0.8f, 0.09f, 0xFFFFF1C2.toInt(), 1f)
            }
            sprites.add(fx, 0.7f, fz, 2.0f * big * (1f + 0.08f * sin(time * 17f)), 1f, 0.5f, 0.15f, 0.16f)
        }
        particles.emit(sprites, true)
        sprites.flush()
        if (skyStreaks.isNotEmpty() || lookUp > 0.01f || runner.hijack == 1 || upDark > 0.01f) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            sprites.begin()
            addSkyStreaks()
            sprites.flush()
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        sprites.begin()
        particles.emit(sprites, false)
        sprites.flush()
    }

    /** A quick fling out and back: 0 at rest, 1 at full stretch, over [t] from 0 to 1. */
    private fun fling(t: Float) = if (t <= 0f || t >= 1f) 0f else sin(t * 3.1416f)

    /** A steady 0..1 from a seed and two indices: the same crack is drawn the same way every frame. */
    private fun noise(seed: Float, a: Int, b: Int): Float {
        val v = sin(seed * 12.9898f + a * 78.233f + b * 37.719f) * 43758.547f
        return v - kotlin.math.floor(v)
    }

    private fun colorOf(style: ShotStyle, f: Fighter): Int {
        val s = f.def.skins[f.skin]
        return when (style) {
            ShotStyle.VOLLEY -> 0xFFFFD640.toInt()
            ShotStyle.SPARK, ShotStyle.BIT -> s.accent.toInt()
            ShotStyle.ROCKET -> 0xFFFF9A2E.toInt()
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
                energyFlare(e.fighterId, if (e.isSuper) 1f else 0.4f, sky = true)
                val f = world.fighter(e.fighterId) ?: return
                if (e.isSuper && f.def.superSpec.kind == SuperKind.QUAKE) hammerAt[f.id] = time
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
            // Healed: a soft flash, and green motes drifting up off them.
            is GameEvent.Heal -> {
                particles.spawn(e.x, 0.9f, e.y, 0f, 0f, 0f, 0.35f, 2.2f, 0xFF7CFF9B.toInt(), 0.6f)
                repeat(18) {
                    val a = rng.nextFloat() * 6.28f
                    val out = 0.2f + rng.nextFloat() * 0.5f
                    particles.spawn(e.x + cos(a) * out, 0.2f + rng.nextFloat() * 0.8f, e.y + sin(a) * out, 0f, 1.8f + rng.nextFloat() * 1.8f, 0f, 0.7f, 0.15f, 0xFF7CFF9B.toInt(), 0.95f)
                }
            }
            is GameEvent.Leap -> world.fighter(e.fighterId)?.let { f ->
                // Dust where it took off.
                repeat(12) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(f.x, 0.2f, f.y, cos(a) * 2.2f, 0.8f, sin(a) * 2.2f, 0.45f, 0.4f, 0xFFD8D2E6.toInt(), 0.45f)
                }
            }
            is GameEvent.Corrupt -> world.fighter(e.targetId)?.let { t ->
                latchAt[e.fighterId] = time
                val c = world.fighter(e.fighterId)?.let { it.def.skins[it.skin].secondary.toInt() } ?: 0xFF29F0FF.toInt()
                // The landing, and the first of the code going in.
                particles.spawn(t.x, 0.8f, t.y, 0f, 0f, 0f, 0.3f, 2.6f, c, 0.7f)
                repeat(26) {
                    val a = rng.nextFloat() * 6.28f
                    val out = 0.3f + rng.nextFloat() * 0.6f
                    particles.spawn(t.x + cos(a) * out, 0.3f + rng.nextFloat(), t.y + sin(a) * out, cos(a) * 0.6f, 2.5f + rng.nextFloat() * 3f, sin(a) * 0.6f, 0.6f, 0.16f, c, 0.95f)
                }
                val away = hypot(t.x - match.player.x, t.y - match.player.y)
                shake = max(shake, (0.22f - away * 0.03f).coerceAtLeast(0f))
            }
            is GameEvent.Launch -> world.fighter(e.fighterId)?.let { f ->
                // The salvo going up: streaks of fire climbing out of sight, and smoke where they left.
                shake = max(shake, 0.08f)
                repeat(e.count * 3) {
                    val a = rng.nextFloat() * 6.28f
                    val out = rng.nextFloat() * 0.35f
                    particles.spawn(f.x + cos(a) * out, 0.9f + rng.nextFloat(), f.y + sin(a) * out, cos(a) * 0.6f, 13f + rng.nextFloat() * 6f, sin(a) * 0.6f, 0.55f, 0.3f, 0xFFFF9A2E.toInt(), 0.95f)
                }
                repeat(10) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(f.x, 0.5f, f.y, cos(a) * 2f, 1f, sin(a) * 2f, 0.7f, 0.5f, 0xFFD8D2E6.toInt(), 0.4f)
                }
            }
            is GameEvent.StarBurst -> world.fighter(e.ownerId)?.let { f ->
                val skin = f.def.skins[f.skin]
                particles.spawn(e.x, 0.6f, e.y, 0f, 0f, 0f, 0.25f, e.radius * 2.2f, skin.accent.toInt(), 0.6f)
                repeat(12) {
                    val a = it * 0.5236f + rng.nextFloat() * 0.3f
                    val sp = 2.5f + rng.nextFloat() * 2.5f
                    particles.spawn(e.x, 0.5f, e.y, cos(a) * sp, 1f + rng.nextFloat() * 2f, sin(a) * sp, 0.35f + rng.nextFloat() * 0.2f, 0.16f, if (it % 2 == 0) skin.accent.toInt() else skin.primary.toInt(), 0.9f)
                }
            }
            is GameEvent.Burst -> {
                particles.spawn(e.x, 0.7f, e.y, 0f, 0f, 0f, 0.2f, e.radius * 2.2f, 0xFFFFC46A.toInt(), 0.45f)
                repeat(8) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 1.5f + rng.nextFloat() * 3.5f
                    particles.spawn(e.x, 0.6f, e.y, cos(a) * sp, 1f + rng.nextFloat() * 3f, sin(a) * sp, 0.35f + rng.nextFloat() * 0.2f, 0.2f, 0xFFFF8A1F.toInt(), 0.9f)
                }
            }
            is GameEvent.Slash -> world.fighter(e.fighterId)?.let { f ->
                val c = (if (e.charged) f.def.skins[f.skin].secondary else f.def.skins[f.skin].accent).toInt()
                val mid = atan2(e.dirY, e.dirX)
                slashes += floatArrayOf(e.x, e.y, -Math.toDegrees(mid.toDouble()).toFloat(), e.range, e.degrees, time, if (e.charged) 1f else 0f, Float.fromBits(c))
                // Sparks thrown off the tip all along the arc.
                val half = Math.toRadians(e.degrees / 2.0).toFloat()
                repeat(if (e.charged) 16 else 9) {
                    val a = mid - half + 2f * half * rng.nextFloat()
                    particles.spawn(e.x + cos(a) * e.range * 0.95f, 0.75f, e.y + sin(a) * e.range * 0.95f, cos(a) * 2.5f, 0.6f + rng.nextFloat(), sin(a) * 2.5f, 0.25f, if (e.charged) 0.18f else 0.12f, c, 0.95f)
                }
                if (e.charged && f === match.player) shake = max(shake, 0.07f)
            }
            // Gone in a puff where he stood, a streak of light along the way, and a flash where he is now.
            is GameEvent.Blink -> world.fighter(e.fighterId)?.let { f ->
                val c = f.def.skins[f.skin].accent.toInt()
                repeat(14) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(e.fromX + cos(a) * 0.3f, 0.3f + rng.nextFloat(), e.fromY + sin(a) * 0.3f, cos(a), 0.8f + rng.nextFloat(), sin(a), 0.6f, 0.4f, 0xFF3C3250.toInt(), 0.55f, growth = 0.8f, add = false)
                }
                val steps = (hypot(e.x - e.fromX, e.y - e.fromY) * 3f).toInt().coerceIn(1, 40)
                for (i in 0..steps) {
                    val u = i / steps.toFloat()
                    particles.spawn(lerp(e.fromX, e.x, u), 0.8f, lerp(e.fromY, e.y, u), 0f, 0.3f, 0f, 0.25f + 0.2f * u, 0.22f, c, 0.9f)
                }
                particles.spawn(e.x, 0.8f, e.y, 0f, 0f, 0f, 0.25f, 3f, c, 0.85f)
                repeat(18) {
                    val a = it * 0.349f
                    particles.spawn(e.x, 0.3f, e.y, cos(a) * 4f, 0.5f, sin(a) * 4f, 0.3f, 0.18f, c, 0.95f)
                }
            }
            is GameEvent.Armed -> repeat(5) {
                val a = rng.nextFloat() * 6.28f
                particles.spawn(e.x, 0.1f, e.y, cos(a) * 1.8f, 1.2f + rng.nextFloat(), sin(a) * 1.8f, 0.3f, 0.12f, 0xFFC9A46A.toInt(), 0.8f, grav = 9f)
            }
            // A realm opens (or lets go): a flash, a ring thrown outwards and a column of stars, on each of the two.
            is GameEvent.Realm -> for (id in intArrayOf(e.fighterId, e.targetId)) world.fighter(id)?.let { f ->
                val c = if (e.open) 0xFFB98CFF.toInt() else 0xFF7CE8FF.toInt()
                particles.spawn(f.x, 0.8f, f.y, 0f, 0f, 0f, 0.4f, 4f * f.scale, c, 0.85f)
                repeat(32) {
                    val a = it * 0.196f
                    particles.spawn(f.x, 0.3f, f.y, cos(a) * 5f, 0.5f, sin(a) * 5f, 0.45f, 0.24f, if (it % 2 == 0) c else 0xFFFFFFFF.toInt(), 0.95f)
                }
                repeat(20) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(f.x + cos(a) * 0.4f, 0.2f, f.y + sin(a) * 0.4f, 0f, 4f + rng.nextFloat() * 6f, 0f, 0.6f, 0.16f, 0xFFFFFFFF.toInt(), 0.9f)
                }
                if (f === match.player) shake = max(shake, 0.2f)
            }
            is GameEvent.Quake -> {
                quakes += floatArrayOf(e.x, e.y, e.radius, time, e.x * 3.7f + e.y * 1.3f)
                if (quakes.size > 24) quakes.removeAt(0)
                particles.spawn(e.x, 0.4f, e.y, 0f, 0f, 0f, 0.3f, e.radius * 2.4f, 0xFFFFE0B0.toInt(), 0.5f)
                // A ring of dirt thrown outwards, rubble kicked up, and dust hanging over it.
                repeat(36) {
                    val a = it * 0.1745f
                    val sp = e.radius * (2.6f + rng.nextFloat())
                    particles.spawn(e.x, 0.15f, e.y, cos(a) * sp, 0.5f, sin(a) * sp, 0.4f, 0.28f, 0xFFC9A46A.toInt(), 0.8f)
                }
                repeat(22) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = (1f + rng.nextFloat() * 3f) * e.radius * 0.6f
                    particles.spawn(e.x, 0.3f, e.y, cos(a) * sp, 3f + rng.nextFloat() * 5f, sin(a) * sp, 0.6f + rng.nextFloat() * 0.3f, 0.16f, 0xFF6B5A4A.toInt(), 1f, grav = 12f)
                }
                repeat(7) {
                    particles.spawn(e.x + (rng.nextFloat() - 0.5f) * e.radius * 1.4f, 0.3f, e.y + (rng.nextFloat() - 0.5f) * e.radius * 1.4f, 0f, 0.9f + rng.nextFloat(), 0f,
                        1.1f, 0.6f, 0xFF4A4038.toInt(), 0.45f, growth = 0.9f, add = false)
                }
                val away = hypot(e.x - match.player.x, e.y - match.player.y)
                shake = max(shake, (0.4f - away * 0.035f).coerceAtLeast(0f))
            }
            is GameEvent.Enthralled -> world.fighter(e.targetId)?.let { t ->
                val c = world.fighter(e.fighterId)?.let { it.def.skins[it.skin].secondary.toInt() } ?: 0xFF29F0FF.toInt()
                particles.spawn(t.x, 0.9f, t.y, 0f, 0f, 0f, 0.35f, 3.2f, c, 0.8f)
                repeat(30) {
                    val a = it * 0.21f
                    particles.spawn(t.x, 0.3f, t.y, cos(a) * 4f, 0.5f, sin(a) * 4f, 0.4f, 0.2f, c, 0.95f)
                }
            }
            is GameEvent.Hyper -> world.fighter(e.fighterId)?.also { energyFlare(it.id, 1f, sky = false) }?.let { f ->
                if (f === match.player) shake = max(shake, 0.12f)
                // It catches light: a flash, a ring of fire thrown outwards, and a column of flame going up.
                particles.spawn(f.x, 0.8f, f.y, 0f, 0f, 0f, 0.4f, 4.5f, 0xFFFFE27A.toInt(), 0.9f)
                repeat(40) {
                    val a = it * 0.157f
                    val sp = 5f + rng.nextFloat() * 2f
                    particles.spawn(f.x, 0.3f, f.y, cos(a) * sp, 0.6f, sin(a) * sp, 0.45f, 0.3f, if (it % 2 == 0) 0xFFFF7A1F.toInt() else 0xFFC04DFF.toInt(), 0.95f)
                }
                repeat(26) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(f.x + cos(a) * 0.3f, 0.2f, f.y + sin(a) * 0.3f, 0f, 5f + rng.nextFloat() * 7f, 0f, 0.6f, 0.4f, 0xFFFFB03A.toInt(), 0.9f)
                }
            }
            is GameEvent.Blast -> {
                particles.spawn(e.x, 0.5f, e.y, 0f, 0f, 0f, 0.3f, e.radius * 2.6f, 0xFFFFE0B0.toInt(), 0.5f)
                repeat((14 * e.radius).toInt()) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = (2f + rng.nextFloat() * 5f) * e.radius
                    particles.spawn(e.x, 0.4f, e.y, cos(a) * sp, 2f + rng.nextFloat() * 5f, sin(a) * sp, 0.5f + rng.nextFloat() * 0.3f, 0.14f,
                        if (e.kind == HazardKind.MINE) 0xFFFF4FA3.toInt() else if (e.kind == HazardKind.BREW) 0xFF7CFF4F.toInt() else if (e.kind == HazardKind.RIFT) (if (it % 2 == 0) 0xFF62F0FF.toInt() else 0xFFB46CFF.toInt()) else 0xFFFF8A1F.toInt(), 1f, grav = 12f)
                }
                repeat(5) {
                    particles.spawn(e.x + (rng.nextFloat() - 0.5f) * e.radius, 0.4f, e.y + (rng.nextFloat() - 0.5f) * e.radius, 0f, 1.2f + rng.nextFloat(), 0f,
                        1f, 0.5f, 0xFF3C3250.toInt(), 0.5f, growth = 0.9f, add = false)
                }
                val away = hypot(e.x - match.player.x, e.y - match.player.y)
                shake = max(shake, (0.3f - away * 0.03f).coerceAtLeast(0f))
            }
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
            is GameEvent.TempestBolt -> {
                val f = world.fighter(e.fighterId)
                val fx = f?.x ?: e.x
                val fz = f?.y ?: e.y
                tempestBolts += floatArrayOf(e.x, e.y, time, fx, fz, (time * 1000f).toInt().toFloat(), e.fighterId.toFloat())
                val c = (f?.def?.skins?.getOrElse(f.skin) { f.def.skins[0] }?.accent ?: 0xFF7DF9FF).toInt()
                repeat(10) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(e.x, 0.15f, e.y, cos(a) * 3.5f, 1.5f + rng.nextFloat() * 2f, sin(a) * 3.5f, 0.35f, 0.12f, c, 1f)
                }
                val pl = match.player
                if (hypot(e.x - pl.x, e.y - pl.y) < 6f) shake = max(shake, 0.1f)
            }
            is GameEvent.TempestHit -> {
                repeat(6) {
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(e.x, 0.5f + rng.nextFloat(), e.y, cos(a) * 3f, 1f, sin(a) * 3f, 0.3f, 0.1f, 0xFFDDF4FF.toInt(), 1f)
                }
                if (e.targetId == pid) shake = max(shake, 0.12f)
            }
            is GameEvent.EntityFlare -> if (e.landed) {
                // It comes down: a burst of its blue, and a jolt if it was on the player.
                repeat(26) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 2.5f + rng.nextFloat() * 4f
                    particles.spawn(e.x, 0.4f + rng.nextFloat() * 0.8f, e.y, cos(a) * sp, 1.5f + rng.nextFloat() * 3f, sin(a) * sp, 0.5f, 0.12f,
                        if (it % 3 == 0) 0xFFFFFFFF.toInt() else 0xFF5CC8FF.toInt(), 1f)
                }
                if (hypot(e.x - match.player.x, e.y - match.player.y) < 3f) shake = max(shake, 0.2f)
            }
            is GameEvent.Corrupted -> if (e.on) energyFlare(e.fighterId, 1f, sky = true)
            is GameEvent.Trapped -> if (e.on) trapAt[e.fighterId] = time else {
                trapAt.remove(e.fighterId)
                // Let go: a burst of its blue where they land.
                world.fighter(e.fighterId)?.let { f -> repeat(40) {
                    val a = rng.nextFloat() * 6.28f; val sp = 2f + rng.nextFloat() * 3.5f
                    particles.spawn(f.x, 0.6f + rng.nextFloat(), f.y, cos(a) * sp, 1f + rng.nextFloat() * 3f, sin(a) * sp, 0.6f, 0.12f, 0xFF5CC8FF.toInt(), 1f)
                } }
            }
            is GameEvent.EntityHit -> {
                // Where it landed on the Entity's shadow: a burst of its violet; the Entity flashes when the player hits it.
                if (e.fighterId == match.player.id) entityHitAt = time
                repeat(if (e.isSuper) 14 else 6) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 2f + rng.nextFloat() * (if (e.isSuper) 4f else 2.5f)
                    particles.spawn(e.x, 0.4f + rng.nextFloat() * 0.6f, e.y, cos(a) * sp, 1.5f + rng.nextFloat() * 2f, sin(a) * sp, 0.35f, 0.12f,
                        if (it % 2 == 0) 0xFFB46CFF.toInt() else 0xFF62F0FF.toInt(), 1f)
                }
                if (e.fighterId == match.player.id && e.isSuper) shake = max(shake, 0.25f)
            }
            is GameEvent.Uprising -> {
                if (e.phase == io.github.projectwip.sim.UprisingPhase.SKY.ordinal || e.phase == io.github.projectwip.sim.UprisingPhase.RETURN.ordinal) shake = max(shake, 0.4f)
            }
            is GameEvent.Glitched -> {
                if (e.fighterId == pid) startHijack() else energyFlare(e.fighterId, 1f, sky = false)
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
        // The HUD is laid out over the view, which may be bigger than the buffer drawn here: projected points scale up.
        val vw = viewWidth; val vh = viewHeight
        s.width = if (vw > 0) vw else width; s.height = if (vh > 0) vh else height
        System.arraycopy(viewProj, 0, s.viewProj, 0, 16)
        s.phase = w.phase; s.phaseTime = w.phaseTime; s.countdownSeconds = w.rules.countdownSeconds; s.timeLeft = w.timeLeft
        val upr = w.uprising
        s.uprisingPhase = if (upr != null && upr.active) upr.phase.ordinal else -1
        s.uprisingT = upr?.t ?: 0f; s.uprisingHeld = upr?.heldFor ?: 0f; s.uprisingBowLen = upr?.bowLength ?: 0f; s.uprisingHp = upr?.hp ?: 0; s.uprisingMaxHp = upr?.maxHp ?: 0; s.uprisingBeaten = upr?.beaten == true
        s.bossMode = w.rules.boss
        s.playerTrapped = p.trapped
        s.trappedNames = if (upr == null) "" else upr.members.mapNotNull { id -> w.fighter(id)?.takeIf { it.trapped && it !== p }?.name }.joinToString(", ")
        s.playerGlitch = p.glitchLeft
        s.timesDown = p.deaths
        s.practice = w.rules.practice
        s.damage = p.damageDealt
        s.myScore = w.score[p.team.coerceIn(0, 1)]
        s.theirScore = w.score[1 - p.team.coerceIn(0, 1)]
        s.koTarget = w.rules.koTarget
        s.winningTeam = w.winningTeam; s.playerTeam = p.team
        s.playerAlive = p.alive; s.respawnTimer = p.respawnTimer; s.ammo = p.ammo; s.ammoMax = p.def.ammoMax; s.superCharge = p.superCharge
        s.edge = if (p.def.attack.edgeSeconds > 0f) p.edge else -1f
        s.hyperCharge = p.hyperCharge; s.hyperLeft = p.hyperTime / p.hyperSeconds
        s.autoTargetId = runner.autoTarget?.id ?: -1
        s.matchesPlayed = matchesPlayed
        s.fps = fps
        s.duelNotice = if (runner.outOfStep) 2 else if (runner.connectionLost) 3 else if (runner.waitingForOpponent) (if (runner.teamMatch) 4 else 1) else 0
        s.n = min(w.fighters.size, io.github.projectwip.match.HudSnapshot.MAX)
        for (i in 0 until s.n) {
            val f = w.fighters[i]
            s.ids[i] = f.id
            val vis = shown(f)
            var onScreen = false
            if (vis) {
                v4[0] = lerp(f.prevX, f.x, alpha); v4[1] = headHeight(f.def) * f.scale; v4[2] = lerp(f.prevY, f.y, alpha); v4[3] = 1f
                Matrix.multiplyMV(o4, 0, viewProj, 0, v4, 0)
                if (o4[3] > 0f) {
                    s.sx[i] = (o4[0] / o4[3] * 0.5f + 0.5f) * s.width
                    s.sy[i] = (1f - (o4[1] / o4[3] * 0.5f + 0.5f)) * s.height
                    onScreen = true
                }
            }
            s.visible[i] = vis && onScreen
            s.hp[i] = f.hp; s.maxHp[i] = f.maxHp
            s.relation[i] = if (f === p) 0 else if (f.team == p.team) 1 else 2
            s.names[i] = f.name
            s.superReady[i] = f.superReady
            s.hyper[i] = f.hyperActive
            s.poisoned[i] = f.poisoned || f.enthralled
            s.cells[i] = f.cells
        }
        hud.publish()
    }

    private fun headHeight(def: io.github.projectwip.data.FighterDef) = headHeight(def.id)
    private fun headHeight(id: FighterId) = FIGHTER_SCALE * when (id) {
        FighterId.BYTE -> 2.05f
        FighterId.BRAKK -> 1.6f
        FighterId.KIRA -> 2.25f
        FighterId.KITO -> 1.9f
        FighterId.BUDDY -> 2.05f
    }

    override fun onDestroyed() {}

    companion object {
        const val FOV = 36f
        const val CAMERA_DISTANCE = 18f
        /** Fighters are drawn a bit larger than their collision radius for readability (genre convention). */
        const val FIGHTER_SCALE = 1.25f
        const val PITCH = 57f
        const val VIS_FADE_IN = 0.12f
        const val VIS_FADE_OUT = 0.3f
        val LIGHT = Toon.LIGHT
        val INK = Toon.INK
        /** How long a hammer thrower is off the ground. */
        const val HAMMER_LEAP = 0.5f
        /** How long a sword swing takes to draw. */
        const val SLASH_SECONDS = 0.26f
        /** The shadows of a realm: what they look like, and how long one takes to lunge in and back as it strikes. */
        const val SHADOW_SHADE = 0xFF1C1024L
        /** Dimension's portal ring: its half-width and half-height, and how high its middle stands. */
        const val PORTAL_RX = 2.15f
        const val PORTAL_RY = 1.4f
        const val PORTAL_CY = 1.42f
        /** How far the ring leans back from upright, toward the camera's line of sight (it looks down at [PITCH]). */
        const val PORTAL_LEAN = 30f
        /** How long a super's streak into the sky lasts (and the camera's look up to it). */
        const val SKY_STREAK_SUPER = 2.0f
        /** The hijack comet's timing: at the top of its climb, into the fighter, and the close-up after it. */
        const val HIJACK_TOP = 1.1f
        const val HIJACK_HIT = 2.0f
        const val HIJACK_LEN = 2.9f
        const val HIJACK_APEX = 15f
        /** How big the Entity stands in the sky in its uprising (a fighter is FIGHTER_SCALE). */
        const val ENTITY_SCALE = 6f
        /** Seconds between a fighter's idle glitches (see `idleGlitch`). */
        const val IDLE_GLITCH_EVERY = 4.5f
        /** In its own dimension: how big it is over its platform, and how high its feet hover (fighters pass under). */
        const val ENTITY_SKY_SCALE = 2.6f
        const val ENTITY_SKY_LIFT = 2.0f
        /** Where along a streak line it reaches the shoulder, and where it has gone round it (see `streakPoint`). */
        const val CONVERGE = 0.3f
        const val AROUND = 0.45f
        /** How long each thing a freed soul says stays up. */
        const val FOLLOW_LINE_SECONDS = 4.2f
        /** A freed soul starts talking once the Void's taunt at the moment of freeing has faded (it lasts 5 s). */
        const val FOLLOW_TALK_AFTER = 5.2f
        const val SHADOW_LUNGE = 0.3f
    }
}
