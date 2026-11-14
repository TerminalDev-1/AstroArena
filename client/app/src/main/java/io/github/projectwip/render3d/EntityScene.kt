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
import io.github.projectwip.gl.Program
import io.github.projectwip.gl.Shaders
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Entity's cutscenes in its fight (the user's ask: "actual cutscenes", not just a screen saying so), staged on its
 * own platform in its void, with the player's own fighter:
 *  - [VICTORY]: beaten, it convulses, glitches through the souls it carries, cracks with light and implodes into
 *    nothing; the fighter turns to the camera and celebrates.
 *  - [DEFEAT]: it looms over the fighter on their knees, its streaks wrap them, lift them and pull them into its chest,
 *    where they are gone; then it speaks to the player.
 *  - [CORRUPT]: one of its flares lands on the player mid-fight and pulls them into it (since build 111, the user's
 *    design: no longer corrupted, trapped among the souls it carries until their team beats it): its streaks pour
 *    down on them, they fight it, they are lifted and pulled into its chest, and the souls inside it flicker across
 *    it. (The match waits while it plays.)
 */
enum class EntityCut { VICTORY, DEFEAT, CORRUPT }

object EntityScript {
    class Beat(val at: Float, val text: String)

    fun length(cut: EntityCut) = when (cut) { EntityCut.VICTORY -> 13f; EntityCut.DEFEAT -> 12.8f; EntityCut.CORRUPT -> 7.6f }

    private val CORRUPT_LINES = listOf(
        "Come. Join the others.", "In you go. They're waiting for you.", "Another voice for me.",
        "Hush. You're mine now.", "So many souls. Room for one more.",
    )

    fun beats(cut: EntityCut, line: Int): List<Beat> = when (cut) {
        EntityCut.VICTORY -> listOf(
            Beat(0.6f, "No. No, no, no."),
            Beat(3.5f, "You think this is over? I live in every one of you."),
            Beat(10.4f, "...For now."),
        )
        EntityCut.DEFEAT -> listOf(
            Beat(0.8f, "You fought well. It changes nothing."),
            Beat(4.0f, "Come here."),
            Beat(7.3f, "Mine. Like all the others."),
            Beat(9.8f, "Come back when you're ready to lose again."),
        )
        EntityCut.CORRUPT -> listOf(
            Beat(0.9f, CORRUPT_LINES[Math.floorMod(line, CORRUPT_LINES.size)]),
            Beat(5.6f, "Your team can still free you. If they beat me."),
        )
    }

    /** The line being shown at [t], or -1. */
    fun beatAt(cut: EntityCut, line: Int, t: Float): Int {
        val b = beats(cut, line)
        var k = -1
        for (i in b.indices) if (t >= b[i].at) k = i
        if (k < 0) return -1
        val end = if (k + 1 < b.size) b[k + 1].at - 0.2f else length(cut) - 0.4f
        return if (t < end) k else -1
    }
}

class EntitySceneParams {
    @Volatile var time = 0f
    @Volatile var cut = EntityCut.VICTORY
    @Volatile var fighter = FighterId.BYTE
    @Volatile var skin = 0
}

/** Draws the Entity's cutscenes. Built on the GL thread. */
class EntityScene {
    private val lit = Program(Shaders.LIT_VS, Shaders.LIT_FS, "entity-lit")
    private val sprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "entity-sprite")
    private val models = FighterModels()
    private val sprites = SpriteBatch(3072)
    private val particles = Particles3D(512)
    private val platform = ArenaModel(io.github.projectwip.sim.Arenas.entityRealm(), realm = true)
    private val rng = Random(17)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val eye = FloatArray(3)
    private val at = FloatArray(3)
    private val fa = FighterAnim()
    private val ea = FighterAnim()
    private var lastFlashAt = -10f
    private var lastT = -1f

    // The stage: the fighter in front of the middle of the platform, the Entity hovering behind.
    private val c = platform.arena.width / 2f
    private val fx0 = c
    private val fz0 = c + 1.4f
    private val ex = c
    private val ez = c - 3.4f
    private val lift = 1.8f
    private val eScale = 2.8f

    init {
        Matrix.setLookAtM(lightView, 0, c - Toon.LIGHT[0] * 20f, -Toon.LIGHT[1] * 20f, c - Toon.LIGHT[2] * 20f, c, 0f, c, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -12f, 12f, -12f, 12f, 1f, 60f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun setEye(x: Float, y: Float, z: Float) { eye[0] = x; eye[1] = y; eye[2] = z }
    private fun setAt(x: Float, y: Float, z: Float) { at[0] = x; at[1] = y; at[2] = z }
    private fun hash(i: Int, k: Int): Float {
        var h = i * 73856093 xor (k * 83492791)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    fun render(width: Int, height: Int, p: EntitySceneParams, dt: Float) {
        val t = p.time
        if (t < lastT) particles.update(10f)
        lastT = t
        val def = Balance.fighter(p.fighter)
        val souls = io.github.projectwip.data.Lore.Soul.entries

        // ---- where everything is, and how it moves, for this cutscene at this moment
        var fx = fx0; var fz = fz0; var fy = 0f
        var facing = -1.5707964f                      // toward the Entity
        var fScale = FIGHTER_SCALE
        var bow = 0f
        var celebrate = 0f
        var struggle = 0f
        var glitch = 0f
        var ey = lift + 0.3f * sin(t * 1.1f)
        var es = eScale
        var showEntity = true
        var soul: io.github.projectwip.data.Lore.Soul? = null
        var eFlash = 0f
        var eBow = 0f
        var streaks = 0f
        var streakFromAbove = false
        var cracks = 0f
        var bright = 0f
        var shake = 0f
        when (p.cut) {
            EntityCut.VICTORY -> {
                val convulse = smooth(t / 1.2f) * (1f - smooth((t - 7f) / 0.5f))
                es = eScale * (1f + 0.035f * sin(t * 23f) * convulse)
                if (t > 1f && t < 8.6f && (t * 12f).toInt() % 3 != 0) soul = souls[(t * 7f).toInt() % souls.size]
                eFlash = 0.12f * convulse + 0.15f * smooth((t - 6f) / 1f)
                cracks = smooth((t - 3f) / 3.5f) * (1f - smooth((t - 8.6f) / 0.3f))
                // It implodes into a point of light and is gone.
                val gone = smooth((t - 7.4f) / 1.2f)
                es *= (1f - gone).coerceAtLeast(0.001f)
                ey += 2.5f * gone
                showEntity = t < 8.6f
                if (t in 8.55f..8.7f && lastFlashAt < 8f) { lastFlashAt = t; repeat(80) { spark(ex, ey + 1f, ez, 7f, 0xFFB46CFF.toInt()) } }
                bright = smooth((t - 8.6f) / 2f)
                shake = 0.06f * convulse
                // The fighter turns to us and celebrates once it is gone.
                if (t > 9.4f) {
                    facing = lerp(-1.5707964f, 1.5707964f, smooth((t - 9.4f) / 0.6f))
                    celebrate = t - 10.2f
                }
            }
            EntityCut.DEFEAT -> {
                bow = if (t < 3.2f) 45f * smooth(t / 0.8f) else 45f * (1f - smooth((t - 3.2f) / 0.6f))
                eBow = 18f * smooth((t - 3f) / 1f)
                streaks = smooth((t - 3.3f) / 0.6f) * (1f - smooth((t - 8f) / 0.4f))
                // Lifted off the platform and pulled into its chest.
                val pull = smooth((t - 4f) / 3.6f)
                fx = lerp(fx0, ex, pull); fz = lerp(fz0, ez + 0.9f, pull); fy = lerp(0f, ey + es * 0.75f, pull)
                struggle = streaks * (1f - pull * 0.6f)
                glitch = streaks
                if (t > 7.6f) fScale = FIGHTER_SCALE * (1f - smooth((t - 7.6f) / 0.6f))
                if (t in 8.15f..8.3f && lastFlashAt < 8f) { lastFlashAt = t; repeat(50) { spark(ex, ey + es * 0.8f, ez + 0.8f, 5f, 0xFF5CC8FF.toInt()) } }
                eFlash = 0.18f * smooth((t - 8.1f) / 0.2f) * (1f - smooth((t - 8.6f) / 1f))
                shake = 0.05f * streaks
            }
            EntityCut.CORRUPT -> {
                // The flare's streaks pour down on them, then its own take them: lifted and pulled into its chest.
                streaks = smooth(t / 0.4f) * (1f - smooth((t - 5f) / 0.4f))
                streakFromAbove = t < 1.6f
                val pull = smooth((t - 1.6f) / 3.4f)
                fx = lerp(fx0, ex, pull); fz = lerp(fz0, ez + 0.9f, pull); fy = lerp(0.3f * streaks, ey + es * 0.75f, pull)
                struggle = streaks * (1f - pull * 0.5f)
                glitch = streaks
                eBow = 14f * smooth((t - 1.4f) / 1f) * (1f - smooth((t - 5.4f) / 1f))
                if (t > 4.6f) fScale = FIGHTER_SCALE * (1f - smooth((t - 4.6f) / 0.6f))
                if (t in 5.1f..5.25f && lastFlashAt < 5f) { lastFlashAt = t; repeat(50) { spark(ex, ey + es * 0.8f, ez + 0.8f, 5f, 0xFF5CC8FF.toInt()) } }
                eFlash = 0.18f * smooth((t - 5.1f) / 0.2f) * (1f - smooth((t - 5.6f) / 1f))
                // Inside it now: the souls it carries flicker across it.
                if (t > 5.3f && (t * 10f).toInt() % 3 != 0) soul = souls[(t * 6f).toInt() % souls.size]
                shake = 0.04f * streaks
            }
        }

        // ---- the camera, shot by shot
        val head = ey + es * 1.35f
        when (p.cut) {
            EntityCut.VICTORY -> when {
                t < 3.4f -> { setEye(c + 5f, 1.6f, c + 9.5f); setAt(ex, lift + eScale * 1f, ez) }
                t < 7.2f -> { val k = smooth((t - 3.4f) / 3.8f); setEye(ex + 0.4f, head - 0.2f, ez + lerp(10f, 6.5f, k)); setAt(ex, head, ez) }
                t < 9.6f -> { setEye(c - 1.5f, 3.2f, c + 10f); setAt(c, 3.6f, ez) }
                else -> { val k = smooth((t - 9.6f) / 3f); setEye(fx + 0.3f, 1.5f, fz + lerp(4f, 3.1f, k)); setAt(fx, 1.35f, fz) }
            }
            EntityCut.DEFEAT -> when {
                t < 3.2f -> { setEye(fx0 + 2.6f, 1.0f, fz0 + 4.4f); setAt(ex, lift + eScale * 0.85f, ez) }
                t < 7.4f -> { setEye(c + 6.5f, 2.6f, c + 3f); setAt(fx, fy + 1f, fz) }
                t < 9.5f -> { setEye(ex + 0.3f, ey + es * 0.95f, ez + 7.5f); setAt(ex, ey + es * 0.9f, ez) }
                else -> { val k = smooth((t - 9.5f) / 3.2f); setEye(ex, head - 0.2f, ez + lerp(10f, 6f, k)); setAt(ex, head, ez) }
            }
            EntityCut.CORRUPT -> when {
                t < 1.6f -> { setEye(fx0 + 3f, 2.2f, fz0 + 4f); setAt(fx0, 1.2f, fz0) }
                t < 5.2f -> { setEye(c + 6.5f, 2.6f, c + 3f); setAt(fx, fy + 1f, fz) }
                else -> { val k = smooth((t - 5.2f) / 2.4f); setEye(ex + 0.3f, ey + es * 0.95f, ez + lerp(8.5f, 6.5f, k)); setAt(ex, ey + es * 0.9f, ez) }
            }
        }
        if (shake > 0f) { eye[0] += (rng.nextFloat() - 0.5f) * shake; eye[1] += (rng.nextFloat() - 0.5f) * shake }
        Matrix.perspectiveM(proj, 0, 42f, width.toFloat() / height, 0.3f, 300f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], at[0], at[1], at[2], 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // ---- the void, the platform
        val vr = lerp(0.05f, 0.16f, bright); val vg = lerp(0.02f, 0.1f, bright); val vb = lerp(0.12f, 0.3f, bright)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(vr, vg, vb, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)
        Toon.setup(lit, viewProj, lightVP, eye, t, null)
        val tl = lerp(0.62f, 0.95f, bright)
        lit.f("uRim", 0.1f)
        lit.v4("uTint", tl * 0.85f, tl * 0.75f, tl, 1f)
        lit.mat4("uModel", Toon.IDENTITY)
        platform.ground.draw()
        lit.f("uRim", 0.25f)
        platform.solids.draw()
        lit.v4("uTint", 1f, 1f, 1f, 1f)

        // ---- the fighter, and the Entity
        fa.time = t; fa.walk = 0f; fa.moving = 0f; fa.scale = fScale; fa.spin = 0f; fa.jump = fy; fa.bow = bow; fa.talk = 0f
        fa.recoil = if (struggle > 0f) 0.4f + 0.4f * sin(t * 13f) * struggle else 0f
        fa.flash = if (glitch > 0f && (t * 9f).toInt() % 4 == 0) 0.12f * glitch else 0f
        var spin = 0f
        if (celebrate > 0f && celebrate < 0.9f) { fa.jump = sin(celebrate / 0.9f * 3.1416f) * 0.7f; spin = 360f * smooth(celebrate / 0.9f) }
        else if (celebrate > 1.6f) fa.jump = 0.18f * kotlin.math.abs(sin((celebrate - 1.6f) * 6f))
        if (struggle > 0f) spin += 14f * sin(t * 7f) * struggle
        fa.spin = spin
        ea.time = t; ea.walk = 0f; ea.moving = 0f; ea.recoil = 0f; ea.spin = if (soul != null) (rng.nextFloat() - 0.5f) * 30f else 0f
        ea.bow = eBow; ea.scale = es; ea.jump = ey; ea.flash = eFlash
        val beat = EntityScript.beatAt(p.cut, 0, t)
        ea.talk = if (beat >= 0) 0.5f + 0.5f * sin(t * 9f) else 0f
        val drawFighter = fScale > 0.02f
        lit.f("uRim", 0.5f)
        if (drawFighter) models.draw(lit, def, p.skin, fx, fz, facing, fa, Pass.COLOR)
        if (showEntity) {
            lit.f("uRim", 0.9f); lit.f("uEmissive", 0.12f)
            models.drawSoul(lit, soul, ex, ez, 1.5707964f, ea, Pass.COLOR)
            lit.f("uEmissive", 0f)
        }
        lit.f("uFlash", 0f); lit.v4("uTint", 1f, 1f, 1f, 1f); lit.mat4("uModel", Toon.IDENTITY)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f)
        lit.f("uOutline", 0.03f); lit.mat4("uModel", Toon.IDENTITY)
        platform.solids.draw()
        lit.f("uOutline", FighterModels.OUTLINE)
        if (drawFighter) models.draw(lit, def, p.skin, fx, fz, facing, fa, Pass.OUTLINE)
        if (showEntity) models.drawSoul(lit, soul, ex, ez, 1.5707964f, ea, Pass.OUTLINE)
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // While it has them, they glitch: cyan and magenta copies of them torn apart.
        if (drawFighter && glitch > 0.05f && (t * 11f).toInt() % 3 == 0) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            lit.i("uMode", 2)
            val j = 0.05f + 0.1f * glitch * rng.nextFloat()
            lit.v4("uTint", 0.1f, 0.9f, 1f, 0.2f * glitch)
            models.draw(lit, def, p.skin, fx + j, fz, facing, fa, Pass.SILHOUETTE)
            lit.v4("uTint", 1f, 0.15f, 0.8f, 0.18f * glitch)
            models.draw(lit, def, p.skin, fx - j, fz, facing, fa, Pass.SILHOUETTE)
            lit.i("uMode", 0)
            lit.v4("uTint", 1f, 1f, 1f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // ---- light: the void's motes and stars, the Entity's glow, its cracks, its streaks
        particles.update(dt)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        sprites.begin()
        for (i in 0 until 110) {
            val a = hash(i, 1) * 6.2832f + t * 0.02f
            val d = 11f + hash(i, 2) * 40f
            val y = -8f + hash(i, 3) * 40f
            val tw = 0.6f + 0.4f * sin(t * (1.2f + hash(i, 4) * 2f) + i)
            sprites.add(c + cos(a) * d, y, c + sin(a) * d, 0.3f + 0.7f * hash(i, 5), if (i % 3 == 0) 0.45f else 0.7f, if (i % 3 == 0) 0.9f else 0.45f, 1f, 0.5f * tw)
        }
        if (showEntity) {
            sprites.add(ex, ey + es * 1.1f, ez - 0.6f, es * 3.2f, 0.35f, 0.2f, 0.9f, 0.24f)
            sprites.add(ex, ey + es * 1.35f, ez + 0.6f, es * 0.9f, 0.6f, 0.85f, 1f, 0.22f + 0.3f * eFlash)
        }
        // Cracks of light bursting out of it as it breaks.
        if (cracks > 0f && showEntity) {
            for (r in 0 until 14) {
                val a = hash(r, 7) * 6.2832f
                val tilt = (hash(r, 8) - 0.5f) * 2f
                for (k in 0 until 10) {
                    val d = (k / 10f) * es * (0.6f + 0.8f * cracks)
                    sprites.add(ex + cos(a) * d, ey + es * 0.9f + tilt * d, ez + 0.7f + sin(a) * d * 0.3f, 0.35f * (1f - k / 12f) + 0.15f,
                        0.75f, 0.85f, 1f, 0.6f * cracks * (1f - k / 10f) * (0.6f + 0.4f * sin(t * 30f + r)))
                }
            }
        }
        // Its streaks: blinding blue streams from its chest (or from far above) into the fighter, wrapping round them.
        if (streaks > 0f && drawFighter) {
            val sx = if (streakFromAbove) fx0 - 1.5f else ex
            val sy = if (streakFromAbove) 14f else ey + es * 0.95f
            val sz = if (streakFromAbove) fz0 - 4f else ez + 1.2f
            for (strand in 0 until 5) for (j in 0 until 24) {
                val v = ((t * 1.4f + j / 24f + strand * 0.2f) % 1f)
                val arc = sin(v * 3.1416f) * (1.2f + strand * 0.3f)
                val x = lerp(sx, fx, v) + (strand - 2) * 0.3f * sin(v * 3.1416f)
                val y = lerp(sy, fy + 1f, v) + arc
                val z = lerp(sz, fz, v)
                sprites.add(x, y, z, 0.2f + 0.2f * v, 0.45f, 0.82f, 1f, 0.75f * streaks * (0.4f + 0.6f * v))
            }
            for (k in 0 until 36) {
                val a = t * 5f + k * 0.55f
                val h = (k / 36f) * 1.6f
                val r = 0.45f + 0.15f * sin(t * 3f + k)
                sprites.add(fx + cos(a) * r * fScale, fy + 0.2f + h * fScale / FIGHTER_SCALE, fz + sin(a) * r * fScale, 0.14f, 0.5f, 0.85f, 1f, 0.8f * streaks)
            }
            if (rng.nextFloat() < 0.5f) spark(fx, fy + 1f, fz, 2f, 0xFF5CC8FF.toInt())
        }
        particles.emit(sprites, true)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun spark(x: Float, y: Float, z: Float, speed: Float, color: Int) {
        val a = rng.nextFloat() * 6.28f
        val sp = speed * (0.4f + rng.nextFloat())
        particles.spawn(x, y + (rng.nextFloat() - 0.5f) * 0.8f, z, cos(a) * sp, rng.nextFloat() * 3f, sin(a) * sp, 0.6f, 0.1f,
            if (rng.nextFloat() < 0.3f) 0xFFFFFFFF.toInt() else color, 1f)
    }

    companion object {
        /** A fighter's size in a match (MatchRenderer's), so they look the same here. */
        const val FIGHTER_SCALE = 1.25f
    }
}

/** Hosts [EntityScene] (opaque, full screen). The screen around it keeps the clock in [params]. */
@SuppressLint("ViewConstructor")
class EntitySceneView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    val params = EntitySceneParams()
    private var thread: GlThread? = null

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val renderer = object : GlRenderer {
            private var scene: EntityScene? = null
            private var w = width
            private var h = height
            override fun onCreated() { scene = EntityScene() }
            override fun onSize(width: Int, height: Int) { w = width; h = height }
            override fun onFrame(dt: Float) { scene?.render(w, h, params, dt) }
        }
        val gfx = io.github.projectwip.gl.GraphicsProfile.current
        thread = GlThread(st, renderer, "entity-gl", transparent = false, msaa = gfx.msaa).also { it.resize(width, height); it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread?.shutdown(); thread = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
