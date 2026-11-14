package io.github.projectwip.render3d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.TextureView
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.gl.GlRenderer
import io.github.projectwip.gl.GlThread
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import io.github.projectwip.gl.Shaders
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The opening (the user's idea): the first time a new player opens the game, they see how Swish came to the Entity.
 * She flies in over a dusk sea to a lone island; the Entity rises out of the water behind it; its blue flares stream
 * into her and corrupt her while she fights them; she is pulled to it, goes still, and kneels, its to command; and
 * then it turns to the one watching. The words, and when each is said, are in [IntroScript]; this draws it.
 */
object IntroScript {
    enum class Who { ENTITY, SWISH }
    enum class Shot { FLY, LAND, RISE, FLARES, PULL, KNEEL, ADDRESS, END }
    class Beat(val who: Who, val shot: Shot, val text: String)

    val BEATS = listOf(
        Beat(Who.ENTITY, Shot.FLY, "Long before you came here, she came to me."),
        Beat(Who.ENTITY, Shot.FLY, "Swish. A little storm, looking for somewhere to land."),
        Beat(Who.SWISH, Shot.LAND, "Hello? Is anyone out here?"),
        Beat(Who.SWISH, Shot.LAND, "This place feels... wrong."),
        Beat(Who.ENTITY, Shot.RISE, "There you are."),
        Beat(Who.ENTITY, Shot.RISE, "Don't be afraid. I only want to help."),
        Beat(Who.SWISH, Shot.FLARES, "What... what is this?"),
        Beat(Who.ENTITY, Shot.FLARES, "Power. All the power you will ever need."),
        Beat(Who.SWISH, Shot.FLARES, "Get out of my head!"),
        Beat(Who.ENTITY, Shot.PULL, "Shh. Stop fighting me."),
        Beat(Who.ENTITY, Shot.PULL, "You are mine now."),
        Beat(Who.SWISH, Shot.KNEEL, "Yes. What do you want me to do?"),
        Beat(Who.ENTITY, Shot.KNEEL, "Fight. Every match. Every attack. For me."),
        Beat(Who.SWISH, Shot.KNEEL, "I obey."),
        Beat(Who.ENTITY, Shot.ADDRESS, "And you. You will be the one holding her."),
        Beat(Who.ENTITY, Shot.ADDRESS, "Welcome to my arena."),
    )

    /** How long a line takes to say (measured on the tablet: a third of a second a word, a pause at every stop). */
    fun speaking(text: String): Float {
        val words = text.trim().split(Regex("\\s+")).size
        val breaks = text.trim().dropLast(1).count { it == '.' || it == ',' || it == '?' || it == '!' }
        return 1.3f + 0.34f * words + 0.32f * breaks
    }

    /** Where each line begins (seconds), with a breath after each, and a slow first moment. */
    val STARTS: FloatArray = FloatArray(BEATS.size).also { s ->
        var t = 1.4f
        for (i in BEATS.indices) {
            s[i] = t
            t += speaking(BEATS[i].text) + 0.45f
            // (A beat of silence where the scene turns.)
            if (i + 1 < BEATS.size && BEATS[i + 1].shot != BEATS[i].shot) t += 0.9f
        }
    }
    val END_AT = STARTS.last() + speaking(BEATS.last().text) + 1.2f
    /** The whole opening, to the last of the fade. */
    val LENGTH = END_AT + 3.2f

    /** The line being said at [t], or -1. */
    fun beatAt(t: Float): Int {
        var k = -1
        for (i in BEATS.indices) if (t >= STARTS[i]) k = i
        return if (k >= 0 && t < STARTS[k] + speaking(BEATS[k].text) + 0.45f) k else -1
    }

    /** The scene at [t], and where its shot began and ends. */
    fun shotAt(t: Float): Triple<Shot, Float, Float> {
        if (t >= END_AT) return Triple(Shot.END, END_AT, LENGTH)
        val shots = Shot.entries.dropLast(1)
        var cur = Shot.FLY
        for (s in shots) { val first = BEATS.indexOfFirst { it.shot == s }; if (first >= 0 && t >= STARTS[first] - (if (first == 0) 1.4f else 0.6f)) cur = s }
        val first = BEATS.indexOfFirst { it.shot == cur }
        val from = if (first == 0) 0f else STARTS[first] - 0.6f
        val nextShot = shots.getOrNull(cur.ordinal + 1)
        val to = nextShot?.let { n -> STARTS[BEATS.indexOfFirst { it.shot == n }] - 0.6f } ?: END_AT
        return Triple(cur, from, to)
    }
}

class IntroParams {
    /** Seconds into the opening (set by the screen, which keeps the clock). */
    @Volatile var time = 0f
}

/** Draws the opening. Built on the GL thread. */
class IntroScene {
    private val lit = Program(Shaders.LIT_VS, Shaders.LIT_FS, "intro-lit")
    private val sprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "intro-sprite")
    private val models = FighterModels()
    private val sprites = SpriteBatch(2048)
    private val particles = Particles3D(512)
    private val rng = Random(11)
    private val sea: Mesh
    private val island: Mesh
    private val solids: Mesh

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val eye = FloatArray(3)
    private val at = FloatArray(3)
    private val swishAnim = FighterAnim()
    private val entityAnim = FighterAnim()
    private val swish = Balance.fighter(FighterId.BYTE)
    private var lastT = 0f
    private var lastShot = IntroScript.Shot.FLY

    init {
        sea = MeshBuilder().apply {
            color(0.16f, 0.12f, 0.36f); with { translate(0f, -0.3f, 0f); cylinder(220f, 0.1f, 64) }
        }.build()
        val g = MeshBuilder()
        val s = MeshBuilder()
        // The island: a mound of pale sand, a patch of grass on top, and a ring of old dark stones round its middle.
        g.color(0.86f, 0.74f, 0.55f); g.with { translate(0f, -0.35f, 0f); cylinder(6.2f, 0.7f, 48, topRadius = 4.7f) }
        g.color(0.79f, 0.66f, 0.48f); g.with { translate(0f, -0.28f, 0f); cylinder(7.4f, 0.12f, 48) }
        g.color(0.3f, 0.56f, 0.34f); g.with { translate(0.3f, 0.01f, 0.4f); cylinder(3.1f, 0.03f, 32) }
        g.color(0.42f, 0.32f, 0.7f); g.with { translate(0f, 0.025f, -2.6f); ring(1.15f, 1.35f, 40) }
        for (k in 0 until 7) {
            val a = k * (6.2832f / 7) + 0.3f
            s.color(0.24f, 0.2f, 0.38f)
            s.with { translate(cos(a) * 2.25f, 0.55f, -2.6f + sin(a) * 1.6f); rotate(a * 57f, 0f, 1f, 0f); roundedBox(0.42f, 1.1f + (k % 3) * 0.25f, 0.32f, 0.08f, 2) }
        }
        // Rocks on the beach.
        val rr = Random(4)
        for (k in 0 until 9) {
            val a = rr.nextFloat() * 6.2832f; val d = 4.6f + rr.nextFloat() * 1.3f
            s.color(0.48f, 0.44f, 0.56f)
            s.with { translate(cos(a) * d, -0.05f, sin(a) * d); rotate(rr.nextFloat() * 360f, 0f, 1f, 0f); ellipsoid(0.35f + rr.nextFloat() * 0.4f, 0.25f + rr.nextFloat() * 0.2f, 0.3f + rr.nextFloat() * 0.3f, 6, 8) }
        }
        // Palms: a leaning trunk in rings, and fronds drooping out from the top.
        for ((px, pz, lean) in listOf(Triple(-3.3f, -0.6f, -14f), Triple(3.4f, -1.2f, 12f), Triple(2.7f, 2.4f, 16f), Triple(-2.6f, 2.8f, -10f))) {
            var x = px; var y = 0f
            val dx = sin(Math.toRadians(lean.toDouble())).toFloat() * 0.42f
            for (k in 0 until 6) {
                s.color(if (k % 2 == 0) 0.55f else 0.48f, 0.38f, 0.24f)
                s.with { translate(x, y + 0.22f, pz); rotate(-lean, 0f, 0f, 1f); cylinder(0.14f - k * 0.008f, 0.46f, 10) }
                x += dx * 0.5f; y += 0.44f
            }
            for (f in 0 until 7) {
                val a = f * (360f / 7)
                s.color(0.24f, 0.55f + (f % 2) * 0.08f, 0.3f)
                s.with { translate(x, y + 0.1f, pz); rotate(a, 0f, 1f, 0f); rotate(28f, 0f, 0f, 1f); translate(0.75f, 0f, 0f); ellipsoid(0.8f, 0.05f, 0.22f, 4, 8) }
            }
        }
        island = g.build(); solids = s.build()
        Matrix.setLookAtM(lightView, 0, -Toon.LIGHT[0] * 20f, -Toon.LIGHT[1] * 20f, -Toon.LIGHT[2] * 20f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -10f, 10f, -10f, 10f, 1f, 50f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Where the Entity is (its feet) and how big. It rises out of the sea behind the island. */
    private fun entityY(t: Float): Float {
        val riseAt = IntroScript.STARTS[IntroScript.BEATS.indexOfFirst { it.shot == IntroScript.Shot.RISE }] - 1.6f
        return lerp(-15f, -1.5f, smooth((t - riseAt) / 4.5f)) + 0.3f * sin(t * 0.8f)
    }
    private val entityZ = -17f
    private val entityScale = 5f
    private fun shotStart(s: IntroScript.Shot): Float = IntroScript.STARTS[IntroScript.BEATS.indexOfFirst { it.shot == s }] - 0.6f

    /** Swish: where she is (x, height, z) and which way she faces, at [t]. */
    private val sw = FloatArray(4)
    private fun swishAt(t: Float) {
        val land = shotStart(IntroScript.Shot.LAND)
        val pull = shotStart(IntroScript.Shot.PULL)
        val kneel = shotStart(IntroScript.Shot.KNEEL)
        val flares = shotStart(IntroScript.Shot.FLARES)
        val rise = shotStart(IntroScript.Shot.RISE)
        when {
            t < land -> {
                // Flying in low over the water, from far out, slowing as she comes in to land.
                val u = smooth(t / land)
                sw[0] = lerp(-34f, -1.4f, 1f - (1f - u) * (1f - u)); sw[2] = lerp(9f, 1.4f, u)
                sw[1] = lerp(3.2f, 0f, smooth((t - land * 0.55f) / (land * 0.45f))) + 0.15f * sin(t * 3f) * (1f - u)
                sw[3] = atan2(1.4f - 9f, -1.4f + 34f)
            }
            t < rise -> {
                // Landed: looking about her, toward the camera then away.
                sw[0] = -1.4f; sw[1] = 0f; sw[2] = 1.4f
                sw[3] = 1.5708f + 0.7f * sin((t - land) * 0.9f)
            }
            t < flares -> {
                // Turning round to it as it rises.
                sw[0] = -1.4f; sw[1] = 0f; sw[2] = 1.4f
                val want = atan2(entityZ - 1.4f, 1.4f)
                sw[3] = lerp(1.5708f, want, smooth((t - rise) / 1.2f))
            }
            t < pull -> {
                // Its flares lift her off the ground; she fights them.
                sw[0] = -1.4f; sw[2] = 1.4f
                sw[1] = 0.55f * smooth((t - flares) / 2f) + 0.08f * sin(t * 7f)
                sw[3] = atan2(entityZ - 1.4f, 1.4f) + 0.25f * sin(t * 5.3f)
            }
            t < kneel -> {
                // Pulled across the island toward it, still struggling, then still.
                val u = smooth((t - pull) / (kneel - pull))
                sw[0] = lerp(-1.4f, 0f, u); sw[2] = lerp(1.4f, -2.6f, u)
                sw[1] = lerp(0.55f, 0.15f, u)
                sw[3] = atan2(entityZ - sw[2], -sw[0]) + 0.2f * sin(t * 5f) * (1f - u)
            }
            else -> {
                // Set down in the ring of stones, kneeling to it.
                sw[0] = 0f; sw[2] = -2.6f
                sw[1] = lerp(0.15f, 0f, smooth((t - kneel) / 0.8f))
                sw[3] = atan2(entityZ + 2.6f, 0f)
            }
        }
    }

    fun render(width: Int, height: Int, p: IntroParams, dt: Float) {
        val t = p.time
        val (shot, from, to) = IntroScript.shotAt(t)
        val sp = ((t - from) / (to - from).coerceAtLeast(0.1f)).coerceIn(0f, 1f)
        val beat = IntroScript.beatAt(t)
        val swishTalking = beat >= 0 && IntroScript.BEATS[beat].who == IntroScript.Who.SWISH
        val entityTalking = beat >= 0 && IntroScript.BEATS[beat].who == IntroScript.Who.ENTITY && t >= shotStart(IntroScript.Shot.RISE)
        swishAt(t)
        val ey = entityY(t)
        val head = ey + entityScale * 1.4f
        val dark = smooth((t - shotStart(IntroScript.Shot.RISE) + 1.5f) / 4f)
        val flareOn = t >= shotStart(IntroScript.Shot.FLARES) + 0.4f && t < shotStart(IntroScript.Shot.KNEEL) + 0.8f
        val flareK = if (!flareOn) 0f else minOf(1f, (t - shotStart(IntroScript.Shot.FLARES) - 0.4f) / 1.2f, (shotStart(IntroScript.Shot.KNEEL) + 0.8f - t) / 1.2f).coerceIn(0f, 1f)

        // ---- the camera, shot by shot
        val sx = sw[0]; val sy = sw[1]; val sz = sw[2]
        var shake = 0f
        when (shot) {
            IntroScript.Shot.FLY -> { setEye(sx - 3.4f + 1.6f * sp, sy + 1.7f, sz + 7.5f); setAt(sx + 2.5f, sy + 0.6f, sz - 1f) }
            IntroScript.Shot.LAND -> { setEye(sx + 0.35f, 1.15f, sz + lerp(3.6f, 2.6f, sp)); setAt(sx, 0.95f, sz) }
            IntroScript.Shot.RISE -> { setEye(sx + 1.3f, 0.55f, sz + 4.6f); setAt(lerp(sx, 0f, 0.7f), lerp(2f, head - 1f, smooth(sp * 1.4f)), lerp(sz, entityZ, 0.8f)) }
            IntroScript.Shot.FLARES -> { setEye(sx + 6.2f, 2.3f, sz + 3.4f); setAt(sx - 1.8f, 2.6f, sz - 4.5f); shake = 0.05f * flareK }
            IntroScript.Shot.PULL -> {
                // In front of her face as it takes her: between her and the Entity, looking back at her.
                val a = sw[3]
                val d = lerp(2.6f, 1.9f, sp)
                setEye(sx + cos(a) * d + 0.5f, sy + 1.1f, sz + sin(a) * d); setAt(sx, sy + 0.95f, sz)
                shake = 0.025f * flareK
            }
            IntroScript.Shot.KNEEL -> { setEye(0.9f, lerp(1.7f, 1.4f, sp), lerp(4.6f, 3.6f, sp)); setAt(0f, lerp(2.4f, 3.4f, sp), -8f) }
            IntroScript.Shot.ADDRESS, IntroScript.Shot.END -> {
                // Its face, filling the screen, coming closer: it is talking to whoever is holding the screen.
                val d = lerp(14f, 8.5f, smooth(if (shot == IntroScript.Shot.END) 1f else sp))
                setEye(0f, head - 0.6f, entityZ + d); setAt(0f, head - 0.4f, entityZ)
            }
        }
        if (shake > 0f) { eye[0] += (rng.nextFloat() - 0.5f) * shake; eye[1] += (rng.nextFloat() - 0.5f) * shake }
        val aspect = width.toFloat() / height
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.3f, 400f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], at[0], at[1], at[2], 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // A burst of its blue where the flares first strike, and sparks while they pour in.
        if (shot != lastShot) {
            if (shot == IntroScript.Shot.FLARES) repeat(30) { spark(sx, sy + 1f, sz, 4f) }
            lastShot = shot
        }
        if (flareK > 0.2f && rng.nextFloat() < 0.6f) spark(sx, sy + 1f, sz, 2f)

        // ---- the sky: dusk going over to the Entity's dark as it rises
        val r0 = lerp(0.42f, 0.05f, dark); val g0 = lerp(0.22f, 0.02f, dark); val b0 = lerp(0.42f, 0.13f, dark)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(r0, g0, b0, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)

        Toon.setup(lit, viewProj, lightVP, eye, t, null)
        val tr = lerp(1f, 0.55f, dark); val tg = lerp(0.92f, 0.45f, dark); val tb = lerp(0.95f, 0.85f, dark)
        lit.f("uRim", 0.1f)
        lit.v4("uTint", tr, tg, tb, 1f)
        lit.mat4("uModel", Toon.IDENTITY)
        sea.draw()
        island.draw()
        lit.f("uRim", 0.25f)
        solids.draw()
        lit.v4("uTint", 1f, 1f, 1f, 1f)

        // ---- Swish, and the Entity
        // (While the flares pour in she glitches; once they have hold of her, only a flicker, so her face is seen.)
        val glitchK = if (shot == IntroScript.Shot.FLARES) flareK else if (shot == IntroScript.Shot.PULL) flareK * 0.35f * (1f - sp) else 0f
        val glitch = glitchK > 0.02f && ((t * 9f).toInt() % 3 != 0)
        swishAnim.time = t; swishAnim.walk = 0f; swishAnim.moving = 0f; swishAnim.scale = 1f; swishAnim.spin = 0f
        swishAnim.jump = sy
        swishAnim.recoil = if (flareK > 0f) 0.4f + 0.4f * sin(t * 13f) else 0f
        swishAnim.flash = 0.25f * glitchK * (if (glitch) 1f else 0f)
        swishAnim.talk = if (swishTalking) ((0.5f + 0.5f * sin(t * 17f)) * (0.55f + 0.45f * sin(t * 5.3f))).coerceIn(0f, 1f) else 0f
        swishAnim.bow = if (shot == IntroScript.Shot.KNEEL || shot == IntroScript.Shot.ADDRESS || shot == IntroScript.Shot.END) 45f * smooth((t - shotStart(IntroScript.Shot.KNEEL)) / 1.1f) else 0f
        entityAnim.time = t; entityAnim.walk = 0f; entityAnim.moving = 0f; entityAnim.recoil = 0f; entityAnim.spin = 0f; entityAnim.bow = 0f
        entityAnim.scale = entityScale; entityAnim.jump = ey
        entityAnim.talk = if (entityTalking) 0.5f + 0.5f * sin(t * 9f) else 0f
        entityAnim.flash = 0f
        val showEntity = ey > -14f

        lit.f("uRim", 0.5f)
        models.draw(lit, swish, 0, sx, sz, sw[3], swishAnim, Pass.COLOR)
        if (showEntity) {
            lit.f("uRim", 0.9f); lit.f("uEmissive", 0.12f * dark)
            models.drawSoul(lit, null, 0f, entityZ, 1.5708f, entityAnim, Pass.COLOR)
            lit.f("uEmissive", 0f)
        }
        lit.f("uFlash", 0f); lit.f("uEmissive", 0f); lit.v4("uTint", 1f, 1f, 1f, 1f); lit.mat4("uModel", Toon.IDENTITY)

        // Outlines
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f)
        lit.f("uOutline", 0.03f); lit.mat4("uModel", Toon.IDENTITY)
        solids.draw()
        lit.f("uOutline", FighterModels.OUTLINE)
        models.draw(lit, swish, 0, sx, sz, sw[3], swishAnim, Pass.OUTLINE)
        if (showEntity) models.drawSoul(lit, null, 0f, entityZ, 1.5708f, entityAnim, Pass.OUTLINE)
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // While it takes her, she glitches: cyan and magenta copies of her torn apart.
        if (glitch) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            lit.i("uMode", 2)
            val j = 0.08f + 0.18f * glitchK * rng.nextFloat()
            lit.v4("uTint", 0.1f, 0.9f, 1f, 0.5f * glitchK)
            models.draw(lit, swish, 0, sx + j, sz, sw[3], swishAnim, Pass.SILHOUETTE)
            lit.v4("uTint", 1f, 0.15f, 0.8f, 0.45f * glitchK)
            models.draw(lit, swish, 0, sx - j, sz, sw[3], swishAnim, Pass.SILHOUETTE)
            lit.i("uMode", 0)
            lit.v4("uTint", 1f, 1f, 1f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // ---- light: the low sun, stars as it darkens, glints on the water, the Entity's glow and its flares
        particles.update(dt)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        sprites.begin()
        // The sun going down on the horizon, behind and to the side; gone as the Entity comes.
        val sun = 1f - dark
        if (sun > 0.02f) {
            sprites.add(-60f, 6f, -150f, 70f, 1f, 0.55f, 0.3f, 0.35f * sun)
            sprites.add(-60f, 6f, -150f, 22f, 1f, 0.85f, 0.55f, 0.8f * sun)
        }
        for (i in 0 until 120) {
            val x = (hash(i, 1) - 0.5f) * 400f; val z = -60f - hash(i, 2) * 160f; val y = 18f + hash(i, 3) * 90f
            val tw = 0.6f + 0.4f * sin(t * (1.3f + hash(i, 4) * 2f) + i)
            sprites.add(x, y, z, 0.8f + hash(i, 5) * 1.2f, 0.8f, 0.85f, 1f, (0.2f + 0.6f * dark) * tw)
        }
        for (i in 0 until 90) {
            val a = hash(i, 6) * 6.2832f; val d = 7f + hash(i, 7) * 50f
            val x = cos(a) * d; val z = sin(a) * d
            val tw = (sin(t * (2f + hash(i, 8) * 3f) + i * 1.7f)).coerceAtLeast(0f)
            sprites.add(x, -0.2f, z, 0.25f + hash(i, 9) * 0.3f, lerp(1f, 0.4f, dark), lerp(0.8f, 0.8f, dark), 1f, 0.45f * tw)
        }
        if (showEntity) {
            sprites.add(0f, ey + entityScale * 1.1f, entityZ - 1f, entityScale * 3.4f, 0.35f, 0.2f, 0.9f, 0.25f * dark)
            sprites.add(0f, head, entityZ + 1f, entityScale * 0.9f, 0.6f, 0.85f, 1f, 0.22f * dark)
        }
        // Its flares: blinding blue streams from its chest, arcing down into hers, and swirling round her.
        if (flareK > 0f) {
            val cx = 0f; val cy = ey + entityScale * 0.95f; val cz = entityZ + 1.5f
            val tx = sx; val ty = sy + 1f; val tz = sz
            for (s in 0 until 5) {
                val off = s * 0.21f
                for (k in 0 until 26) {
                    val u = ((t * 0.9f + off + k * 0.018f) % 1f)
                    val bend = sin(u * 3.1416f) * (2.5f + s * 0.6f)
                    val side = (s - 2) * 0.9f * sin(u * 3.1416f)
                    val x = lerp(cx, tx, u) + side; val y = lerp(cy, ty, u) + bend; val z = lerp(cz, tz, u)
                    val fade = flareK * (0.3f + 0.7f * (1f - k / 26f))
                    sprites.add(x, y, z, 0.32f + 0.25f * (1f - k / 26f), 0.45f, 0.82f, 1f, 0.75f * fade)
                }
            }
            for (k in 0 until 40) {
                val a = t * 5f + k * 0.55f
                val h = (k / 40f) * 1.6f
                val r = 0.45f + 0.15f * sin(t * 3f + k)
                sprites.add(tx + cos(a) * r, sy + 0.2f + h, tz + sin(a) * r, 0.16f, 0.5f, 0.85f, 1f, 0.8f * flareK)
            }
            sprites.add(tx, ty, tz, 1.6f, 0.5f, 0.85f, 1f, 0.45f * flareK)
        }
        // Afterwards, the Entity's four streaks settle into her shoulders: hers now, as every fighter's are.
        if (t >= shotStart(IntroScript.Shot.KNEEL)) {
            val k0 = smooth((t - shotStart(IntroScript.Shot.KNEEL)) / 1.5f)
            for (line in 0 until 4) for (k in 0 until 10) {
                val u = ((t * 0.6f + k * 0.1f + line * 0.25f) % 1f)
                val side = if (line < 2) 1f else -1f
                val ang = sw[3] + side * 1.5708f
                val x = sx + cos(ang) * 0.3f + cos(ang + u * 6f) * 0.15f * u
                val z = sz + sin(ang) * 0.3f + sin(ang + u * 6f) * 0.15f * u
                sprites.add(x, sy + 0.95f + (1f - u) * 1.3f, z, 0.07f, 0.5f, 0.85f, 1f, 0.8f * k0)
            }
        }
        // Flying in: a trail of wind and storm behind her.
        if (shot == IntroScript.Shot.FLY && sy > 0.2f) {
            val back = sw[3] + 3.1416f
            for (k in 0 until 26) {
                val d = 0.3f + k * 0.28f
                val wob = sin(t * 6f + k * 0.7f) * 0.12f * (k / 26f)
                sprites.add(sx + cos(back) * d, sy + 0.8f + wob, sz + sin(back) * d, 0.3f + k * 0.03f, 0.8f, 0.92f, 1f, 0.5f * (1f - k / 26f))
            }
        }
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        lastT = t
    }

    private fun spark(x: Float, y: Float, z: Float, speed: Float) {
        val a = rng.nextFloat() * 6.28f
        val sp = speed * (0.4f + rng.nextFloat())
        particles.spawn(x, y + (rng.nextFloat() - 0.5f) * 0.6f, z, cos(a) * sp, rng.nextFloat() * 2f, sin(a) * sp, 0.45f, 0.08f,
            if (rng.nextFloat() < 0.3f) 0xFFFFFFFF.toInt() else 0xFF5CC8FF.toInt(), 1f)
    }

    private fun setEye(x: Float, y: Float, z: Float) { eye[0] = x; eye[1] = y; eye[2] = z }
    private fun setAt(x: Float, y: Float, z: Float) { at[0] = x; at[1] = y; at[2] = z }

    private fun hash(i: Int, k: Int): Float {
        var h = i * 73856093 xor (k * 83492791)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }
}

/** Hosts [IntroScene] (opaque, full screen). The screen around it keeps the clock in [params]. */
@SuppressLint("ViewConstructor")
class IntroView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    val params = IntroParams()
    private var thread: GlThread? = null

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val renderer = object : GlRenderer {
            private var scene: IntroScene? = null
            private var w = width
            private var h = height
            override fun onCreated() { scene = IntroScene() }
            override fun onSize(width: Int, height: Int) { w = width; h = height }
            override fun onFrame(dt: Float) { scene?.render(w, h, params, dt) }
        }
        val gfx = io.github.projectwip.gl.GraphicsProfile.current
        thread = GlThread(st, renderer, "intro-gl", transparent = false, msaa = gfx.msaa).also { it.resize(width, height); it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread?.shutdown(); thread = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
