package io.github.projectwip.render3d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.gl.Egl
import io.github.projectwip.gl.GlRenderer
import io.github.projectwip.gl.GlThread
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import io.github.projectwip.gl.Shaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What the stage should show. Written from the UI thread, read by the GL thread. */
class StageParams {
    @Volatile var fighter = FighterId.BYTE
    /** Set to show a Boss Mode boss instead of [fighter]. */
    @Volatile var boss: io.github.projectwip.data.BossKind? = null
    @Volatile var skin = 0
    @Volatile var locked = false
    @Volatile var pedestal = true
    /** Extra yaw from the user dragging the fighter, degrees. */
    @Volatile var dragYaw = 0f
    @Volatile var celebrateAt = 0L
    @Volatile var cheerAt = 0L
    /** Beaten: the fighter hangs their head and shakes it (the result screen, after a loss). */
    @Volatile var sulkAt = 0L
    /** Dimension: show one of the forgotten fighters (a [io.github.projectwip.data.Lore.Soul] ordinal) instead, or -1. */
    @Volatile var soul = -1
    /**
     * Dimension: show the Void, glitching between itself and the forgotten fighters whose souls it carries (the
     * user's lore). [voidSoul], when set, is the one whose voice it is speaking with: that one shows through.
     */
    @Volatile var voidCycle = false
    @Volatile var voidSoul = -1
    /** Talking (cutscenes): the mouth moves and they gesture with the words. */
    @Volatile var talking = false
    /** Dimension: chained to a pillar behind them with this many chains (the captive in its talk), rattling. */
    @Volatile var chains = 0
}

/**
 * A fighter on a pedestal, lit and shadowed like the arena. Used live (menus) and offscreen (portraits).
 */
class StageScene(private val withPedestal: Boolean) {
    private val lit = Program(Shaders.LIT_VS, Shaders.LIT_FS, "stage-lit")
    private val depth = Program(Shaders.LIT_VS, Shaders.DEPTH_FS, "stage-depth")
    private val sprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "stage-sprite")
    private val models = FighterModels()
    private val shadow = ShadowMap(1024)
    private val sprites = SpriteBatch(640)
    private val particles = Particles3D(256)
    private val rng = Random(5)
    private val base: Mesh
    private val top: Mesh
    private val rim: Mesh

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val eye = floatArrayOf(0f, 2.1f, 6.3f)
    private val anim = FighterAnim()
    private val link: Mesh = MeshBuilder().apply { color(1f, 1f, 1f); torus(0.075f, 0.024f, 12, 6) }.build()
    private val pillar: Mesh = MeshBuilder().apply { color(1f, 1f, 1f); box(1f, 1f, 1f) }.build()
    private var talkOpen = 0f
    private var shownYaw = 0f
    private var lastCelebrate = 0L

    init {
        base = MeshBuilder().apply { color(0.3f, 0.22f, 0.66f); with { translate(0f, -0.2f, 0f); cylinder(1.25f, 0.4f, 40) } }.build()
        top = MeshBuilder().apply {
            color(0.5f, 0.42f, 0.95f); with { translate(0f, 0.005f, 0f); cylinder(1.08f, 0.02f, 40) }
            color(0.62f, 0.55f, 1f); with { translate(0f, 0.02f, 0f); ring(0.55f, 0.62f, 40) }
        }.build()
        rim = MeshBuilder().apply { with { translate(0f, 0.0f, 0f); torus(1.17f, 0.06f, 48, 8) } }.build()
        Matrix.setLookAtM(lightView, 0, -Toon.LIGHT[0] * 10f, -Toon.LIGHT[1] * 10f, -Toon.LIGHT[2] * 10f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(lightProj, 0, -2.5f, 2.5f, -2.5f, 2.5f, 1f, 25f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
    }

    private val cp = FloatArray(3)

    /** A point on one of the four streak lines round the fighter on the stage (as `MatchRenderer.streakPoint`). */
    private fun streakAt(facing: Float, line: Int, u: Float, time: Float, spread: Float = 0f) {
        val sc = 1f
        val side = if (line < 2) 1f else -1f
        val sub = if (line % 2 == 0) 1f else -1f
        val f = facing
        val sideAng = f + side * 1.5708f
        val sx = kotlin.math.cos(sideAng) * 0.3f * sc; val sz = kotlin.math.sin(sideAng) * 0.3f * sc; val sy = 0.95f * sc
        val converge = 0.3f; val around = 0.45f
        when {
            u <= converge -> {
                val v = u / converge
                val back = f + 3.1416f
                val tx = sx + kotlin.math.cos(sideAng) * 0.12f + kotlin.math.cos(back) * (0.18f + 0.12f * sub)
                val tz = sz + kotlin.math.sin(sideAng) * 0.12f + kotlin.math.sin(back) * (0.18f + 0.12f * sub)
                val w = 1f - (1f - v) * (1f - v)
                val drift = (1f - v) * spread * 0.18f
                cp[0] = tx + (sx - tx) * w + kotlin.math.cos(f) * drift
                cp[1] = sy + 1.25f + (0.12f - 1.25f) * v
                cp[2] = tz + (sz - tz) * w + kotlin.math.sin(f) * drift
            }
            u <= around -> {
                val v = (u - converge) / (around - converge)
                val a = v * 6.2832f * sub + 1.5708f
                cp[0] = sx + kotlin.math.cos(f) * kotlin.math.cos(a) * 0.16f
                cp[1] = sy + kotlin.math.sin(a) * 0.16f
                cp[2] = sz + kotlin.math.sin(f) * kotlin.math.cos(a) * 0.16f
            }
            else -> {
                val v = (u - around) / (1f - around)
                val ang = sideAng + sub * v * (7.8f + 0.4f * sin(time * 0.7f + line)) + sub * v * time * 0.35f
                val r = 0.38f + 0.16f * v
                cp[0] = kotlin.math.cos(ang) * r
                cp[1] = sy - v * 0.5f
                cp[2] = kotlin.math.sin(ang) * r
            }
        }
    }

    /** The streaks and their rising comets round the fighter on the stage: glow, a little brighter while they talk. */
    private fun addComets(facing: Float, time: Float, talking: Boolean) {
        val boost = if (talking) 0.25f else 0f
        for (line in 0..3) {
            for (k in 0..11) {
                streakAt(facing, line, 0.1f + 0.9f * k / 11f, time)
                sprites.add(cp[0], cp[1], cp[2], 0.15f, 0.25f, 0.55f, 1f, 0.08f + 0.12f * boost)
            }
            for (fd in floatArrayOf(-1f, 1f)) for (k in 0..2) {
                streakAt(facing, line, 0.3f * k / 3f, time, fd)
                sprites.add(cp[0], cp[1], cp[2], 0.09f, 0.3f, 0.6f, 1f, 0.06f)
            }
            val head = 1f - ((time * 0.28f + line * 0.25f) % 1f)
            for (k in 0..7) {
                streakAt(facing, line, (head + k * 0.03f).coerceAtMost(1f), time)
                val fade = 1f - k / 8f
                sprites.add(cp[0], cp[1], cp[2], 0.1f + 0.13f * fade, 0.35f + 0.4f * fade, 0.7f + 0.25f * fade, 1f, 0.07f + 0.2f * fade)
            }
            streakAt(facing, line, head, time)
            sprites.add(cp[0], cp[1], cp[2], 0.32f, 0.3f, 0.6f, 1f, 0.13f + 0.1f * boost)
            sprites.add(cp[0], cp[1], cp[2], 0.12f, 0.75f, 0.92f, 1f, 0.4f)
        }
    }

    private val chainM = FloatArray(16)

    /**
     * Dimension's captive, in 3D: a dark stone pillar behind them, and [count] chains of real links wrapped round
     * them from it, at the waist, the chest and the shoulders, rattling, jerking taut when they yank.
     */
    private fun drawChains(count: Int, yank: Float, time: Float) {
        lit.f("uRim", 0.3f)
        lit.v4("uTint", 0.2f, 0.15f, 0.27f, 1f)
        Matrix.setIdentityM(chainM, 0); Matrix.translateM(chainM, 0, 0f, 1.25f, -0.62f); Matrix.scaleM(chainM, 0, 0.42f, 2.5f, 0.42f)
        lit.mat4("uModel", chainM); pillar.draw()
        lit.f("uRim", 0.5f); lit.f("uGloss", 0.6f)
        lit.v4("uTint", 0.66f, 0.64f, 0.72f, 1f)
        for (c in 0 until count) {
            val h = 0.55f + c * 0.3f
            val r = 0.4f - c * 0.04f
            val links = 16
            for (k in 0 until links) {
                // Round the front of them from one side of the pillar to the other.
                val u = k / (links - 1f)
                val ang = (-200f + 220f * u) * (PI.toFloat() / 180f)
                val rr = r + 0.015f * sin(time * 20f + k + c) * (1f + 4f * yank)
                val x = kotlin.math.cos(ang) * rr
                val z = -kotlin.math.sin(ang) * rr * 1.1f - 0.12f
                val y = h + 0.02f * sin(time * 13f + k * 0.7f) - 0.05f * kotlin.math.sin(u * PI.toFloat())
                Matrix.setIdentityM(chainM, 0)
                Matrix.translateM(chainM, 0, x, y, z)
                Matrix.rotateM(chainM, 0, -ang * 180f / PI.toFloat() + 90f, 0f, 1f, 0f)
                Matrix.rotateM(chainM, 0, if (k % 2 == 0) 90f else 0f, 1f, 0f, 0f)
                lit.mat4("uModel", chainM); link.draw()
            }
        }
        lit.f("uGloss", 0f)
        lit.v4("uTint", 1f, 1f, 1f, 1f)
        lit.mat4("uModel", Toon.IDENTITY)
    }

    fun render(width: Int, height: Int, p: StageParams, time: Float, dt: Float, framing: Float = 1f, targetFbo: Int = 0) {
        val def = p.boss?.let { Balance.boss(it) } ?: Balance.fighter(p.fighter)
        val skin = def.skins[p.skin.coerceIn(0, def.skins.lastIndex)]
        val aspect = width.toFloat() / height
        Matrix.perspectiveM(proj, 0, 30f / framing.coerceAtLeast(0.3f), aspect, 0.5f, 40f)
        val lookY = if (withPedestal) 0.95f else 0.9f
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, lookY, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        // Animation: idle sway toward the camera, user drag, celebrations.
        val now = System.currentTimeMillis()
        val ct = (now - p.celebrateAt) / 1000f
        val cheer = (now - p.cheerAt) / 1000f
        val sulk = (now - p.sulkAt) / 1000f
        if (p.celebrateAt != lastCelebrate && ct < 0.05f) {
            lastCelebrate = p.celebrateAt
            val c = skin.accent.toInt()
            repeat(40) {
                val a = rng.nextFloat() * 6.28f
                val sp = 1.5f + rng.nextFloat() * 2.5f
                particles.spawn(cos(a) * 0.4f, 0.6f + rng.nextFloat(), sin(a) * 0.4f, cos(a) * sp, 2f + rng.nextFloat() * 3f, sin(a) * sp,
                    1.1f, 0.12f, if (it % 2 == 0) c else 0xFFFFD640.toInt(), 1f, grav = 6f)
            }
        }
        val targetYaw = p.dragYaw
        shownYaw += (targetYaw - shownYaw) * (1f - kotlin.math.exp(-dt * 10f))
        anim.time = time
        anim.walk = 0f
        anim.moving = 0f
        anim.recoil = 0f
        anim.flash = 0f
        anim.scale = 1f
        anim.jump = 0f
        anim.spin = 0f
        if (ct in 0f..0.9f) {
            anim.jump = sin(ct / 0.9f * PI.toFloat()) * 0.7f
            anim.spin = 360f * smooth(ct / 0.9f)
        } else if (cheer in 0f..0.5f) {
            anim.jump = sin(cheer / 0.5f * PI.toFloat()) * 0.35f
            anim.recoil = sin(cheer / 0.5f * PI.toFloat())
        } else if (sulk in 0f..1.6f) {
            // A slow shake of the head, side to side, dying away, sinking a little.
            val k = 1f - sulk / 1.6f
            anim.spin = sin(sulk * 11f) * 22f * k
            anim.jump = -0.05f * sin(minOf(sulk, 0.8f) / 0.8f * PI.toFloat() * 0.5f)
        }
        // Talking: the mouth opening and closing with the words, a bob and a turn now and then as they say it.
        val wantOpen = if (p.talking) ((0.5f + 0.5f * sin(time * 17f)) * (0.55f + 0.45f * sin(time * 5.3f))).coerceIn(0f, 1f) else 0f
        talkOpen += (wantOpen - talkOpen) * (1f - kotlin.math.exp(-dt * 25f))
        anim.talk = talkOpen
        if (p.talking) {
            anim.jump += 0.025f * kotlin.math.abs(sin(time * 9f))
            anim.spin += 5f * sin(time * 2.3f)
            anim.recoil = maxOf(anim.recoil, 0.25f * (sin(time * 3.1f) - 0.6f).coerceAtLeast(0f) * 2.5f)
        }
        // Chained: pulling against it, a hard yank now and then.
        val yank = if (p.chains > 0 && (time % 2.4f) < 0.3f) sin((time % 2.4f) / 0.3f * PI.toFloat()) else 0f
        if (p.chains > 0) { anim.spin += 9f * sin(time * 6f) * (0.4f + yank); anim.moving = 0.15f + 0.4f * yank; anim.walk = time * 7f }
        // Face mostly toward the camera, 3/4 view, with a gentle idle sway.
        val facing = (PI / 2 - 0.62).toFloat() + sin(time * 0.6f) * 0.08f - Math.toRadians(shownYaw.toDouble()).toFloat()

        // Dimension: who is drawn. The Void spends most of its time as itself, then glitches (flickering between
        // the two, jolting, flashing) into one of the forgotten fighters for a moment, and glitches back.
        val souls = io.github.projectwip.data.Lore.Soul.entries
        var who: io.github.projectwip.data.Lore.Soul? = if (p.soul >= 0) souls[p.soul] else null
        var asVoid = p.voidCycle
        var flash = 0f
        if (p.voidCycle) {
            val period = 3.6f
            val ph = time % period
            val next = if (p.voidSoul >= 0) souls[p.voidSoul] else souls[(time / period).toInt() % souls.size]
            val glitching = ph in 2.2f..2.45f || ph > 3.35f
            val showingSoul = ph in 2.45f..3.35f
            asVoid = !showingSoul
            if (glitching) {
                asVoid = (time * 26f).toInt() % 2 == 0
                anim.spin += (rng.nextFloat() - 0.5f) * 40f
                anim.jump += (rng.nextFloat() - 0.5f) * 0.08f
                flash = 0.35f + 0.3f * rng.nextFloat()
                if (rng.nextFloat() < 0.5f) {
                    val c = next.color.toInt()
                    val a = rng.nextFloat() * 6.28f
                    particles.spawn(cos(a) * 0.5f, 0.4f + rng.nextFloat() * 1.4f, sin(a) * 0.5f, cos(a) * 2f, 0.5f, sin(a) * 2f, 0.5f, 0.1f, c, 1f)
                }
            }
            who = if (asVoid) null else next
        }
        val special = p.voidCycle || p.soul >= 0
        // The Void is a head taller than anyone, so it is drawn smaller to fit the same stage.
        if (special && asVoid) anim.scale = 0.8f
        fun drawSubject(prog: io.github.projectwip.gl.Program, pass: Pass) {
            if (special) models.drawSoul(prog, if (asVoid) null else who, 0f, 0f, facing, anim, pass)
            else models.draw(prog, def, p.skin, 0f, 0f, facing, anim, pass)
        }

        // Shadow
        shadow.begin()
        depth.use()
        depth.mat4("uViewProj", lightVP); depth.mat4("uLightVP", lightVP); depth.f("uOutline", 0f); depth.f("uSway", 0f); depth.f("uTime", time)
        drawSubject(depth, Pass.SHADOW)
        shadow.end(targetFbo)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)

        Toon.setup(lit, viewProj, lightVP, eye, time, shadow)
        if (withPedestal && p.pedestal) {
            lit.f("uRim", 0.25f)
            base.draw(); top.draw()
            val a = skin.secondary.toInt()
            lit.v4("uTint", r(a), g(a), b(a), 1f)
            lit.f("uEmissive", 0.55f + 0.2f * sin(time * 2.4f))
            rim.draw()
            lit.f("uEmissive", 0f)
            lit.v4("uTint", 1f, 1f, 1f, 1f)
        }
        lit.f("uRim", 0.5f)
        if (p.locked) {
            lit.i("uMode", 2)
            lit.v4("uTint", 0.13f, 0.09f, 0.28f, 1f)
            drawSubject(lit, Pass.SILHOUETTE)
            lit.i("uMode", 0)
        } else {
            anim.flash = flash
            drawSubject(lit, Pass.COLOR)
            anim.flash = 0f
        }
        lit.f("uFlash", 0f); lit.f("uEmissive", 0f)
        if (p.chains > 0) drawChains(p.chains, yank, time)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        lit.i("uMode", 1)
        lit.v4("uTint", Toon.INK[0], Toon.INK[1], Toon.INK[2], 1f)
        lit.f("uOutline", FighterModels.OUTLINE)
        drawSubject(lit, Pass.OUTLINE)
        if (withPedestal && p.pedestal) { lit.f("uOutline", 0.03f); lit.mat4("uModel", Toon.IDENTITY); base.draw() }
        lit.f("uOutline", 0f)
        lit.i("uMode", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Celebration sparkles + a few ambient motes
        particles.update(dt)
        if (withPedestal && rng.nextFloat() < 0.12f) {
            val a = rng.nextFloat() * 6.28f
            val c = skin.secondary.toInt()
            particles.spawn(cos(a) * 1.1f, 0.05f, sin(a) * 1.1f, 0f, 0.6f + rng.nextFloat() * 0.5f, 0f, 1.6f, 0.06f, c, 0.9f)
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        sprite.use()
        sprite.mat4("uViewProj", viewProj)
        sprite.v3("uRight", view[0], view[4], view[8])
        sprite.v3("uUp", view[1], view[5], view[9])
        sprites.begin()
        particles.emit(sprites, true)
        // The Entity's energy in them here too (the user's lore): comets on their streaks, as in a match.
        if (withPedestal && !special && !p.locked && def.boss == null) addComets(facing, time, p.talking)
        sprites.flush()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
    private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f
}

/**
 * Live 3D fighter for menus. Transparent, so Compose backgrounds show through.
 * Drag to spin the fighter; tap to make them cheer.
 */
@SuppressLint("ViewConstructor")
class FighterStageView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    val params = StageParams()
    private var thread: GlThread? = null
    private var downX = 0f
    private var startYaw = 0f
    private var moved = false

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val renderer = object : GlRenderer {
            private var scene: StageScene? = null
            private var w = width
            private var h = height
            private var time = 0f
            override fun onCreated() { scene = StageScene(withPedestal = true) }
            override fun onSize(width: Int, height: Int) { w = width; h = height }
            override fun onFrame(dt: Float) { time += dt; scene?.render(w, h, params, time, dt) }
        }
        val gfx = io.github.projectwip.gl.GraphicsProfile.current
        thread = GlThread(st, renderer, "stage-gl", transparent = true, msaa = gfx.msaa).also { it.maxFps = gfx.menuFrameRate; it.resize(width, height); it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.shutdown()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; startYaw = params.dragYaw; moved = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (abs(dx) > 12f) moved = true
                params.dragYaw = startYaw + dx / width * 360f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved) { params.cheerAt = System.currentTimeMillis(); performClick() }
                params.dragYaw = 0f // spring back to the hero pose
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Renders every fighter/skin once, offscreen, into bitmaps for cards and lists (one GL context, ~1s total).
 */
object Portraits {
    private val _images = MutableStateFlow<Map<Pair<FighterId, Int>, Bitmap>>(emptyMap())
    val images: StateFlow<Map<Pair<FighterId, Int>, Bitmap>> = _images.asStateFlow()
    private val _bosses = MutableStateFlow<Map<io.github.projectwip.data.BossKind, Bitmap>>(emptyMap())
    /** The bosses of Boss Mode, for the line-up before a fight and the result after it. */
    val bosses: StateFlow<Map<io.github.projectwip.data.BossKind, Bitmap>> = _bosses.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            try { renderAll() } catch (e: Exception) { Log.e("Portraits", "portrait render failed", e) }
        }, "portraits").start()
    }

    private fun renderAll() {
        val size = 384
        val egl = Egl(transparent = true)
        val pb = egl.pbuffer(1, 1)
        egl.makeCurrent(pb)
        // Multisampled FBO, resolved into a plain one for read-back.
        val ids = IntArray(4)
        GLES30.glGenFramebuffers(2, ids, 0)
        GLES30.glGenRenderbuffers(2, ids, 2)
        val msFbo = ids[0]; val resolveFbo = ids[1]; val msColor = ids[2]; val msDepth = ids[3]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msColor)
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, 4, GLES30.GL_RGBA8, size, size)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msDepth)
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, 4, GLES30.GL_DEPTH_COMPONENT24, size, size)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msColor)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, msDepth)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)

        val scene = StageScene(withPedestal = false)
        val params = StageParams().apply { pedestal = false }
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        val out = HashMap<Pair<FighterId, Int>, Bitmap>()
        val bossOut = HashMap<io.github.projectwip.data.BossKind, Bitmap>()
        // Every fighter in every colourway, then the bosses.
        val subjects = Balance.fighters.flatMap { def -> def.skins.indices.map { def to it } } + Balance.bosses.map { it to 0 }
        for ((def, skin) in subjects) {
            params.fighter = def.id
            params.boss = def.boss
            params.skin = skin
            scene.render(size, size, params, time = 0.6f, dt = 0f, framing = 1.05f, targetFbo = msFbo)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
            GLES30.glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
            buf.position(0)
            GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.position(0)
            val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val picture = Bitmap.createBitmap(raw, 0, 0, size, size, flip, false)
            raw.recycle()
            val kind = def.boss
            if (kind != null) { bossOut[kind] = picture; _bosses.value = HashMap(bossOut) }
            else { out[def.id to skin] = picture; _images.value = HashMap(out) }
        }
        egl.destroySurface(pb)
        egl.release()
        Log.i("Portraits", "rendered ${out.size} portraits")
    }
}
