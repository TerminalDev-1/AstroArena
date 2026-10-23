package io.github.projectwip.render3d

import android.opengl.Matrix
import io.github.projectwip.data.FighterDef
import io.github.projectwip.data.FighterId
import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.gl.Program
import kotlin.math.abs
import kotlin.math.sin

/** Which skin colour a part takes. Meshes are white; colour is applied per draw so skins are free. */
enum class Slot { PRIMARY, SECONDARY, ACCENT, SKIN, SKIN_BROWN, DARK, METAL, WHITE, INK }

/** Simple rig. Every part is attached to exactly one bone. */
/** [FORE_L] and [FORE_R] are the front legs of a fighter that walks on four. */
enum class Bone { BODY, HEAD, WEAPON, ARM, LEG_L, LEG_R, FORE_L, FORE_R, FLOAT }

/** [gloss]: a shiny, chromatic shell (see the lit shader's `uGloss`). */
class Part(val mesh: Mesh, val slot: Slot, val bone: Bone, val outline: Boolean, val emissive: Boolean, val gloss: Boolean = false)

/** Rig layout (bone pivots in model space; model faces +X, up +Y). */
class Rig(
    val headY: Float,
    val hipY: Float,
    val hipZ: Float,
    val shoulder: FloatArray,   // weapon shoulder (x,y,z), relative to body
    val shoulderL: FloatArray,  // free arm shoulder
    val floatY: Float = 0f,
    /** On four legs: how far forward the front legs are hung, and how far back the hind ones. */
    val foreX: Float = 0f,
    val hindX: Float = 0f,
)

class FighterModel(val parts: List<Part>, val rig: Rig)

/** Per-draw animation inputs. */
class FighterAnim {
    var walk = 0f        // walk cycle phase (radians)
    var moving = 0f      // 0..1 how much the fighter is moving
    var recoil = 0f      // 0..1 weapon kick
    var flash = 0f       // 0..1 hit flash
    var time = 0f
    var jump = 0f        // extra height (menu celebrations)
    var spin = 0f        // extra yaw degrees (menu celebrations)
    var scale = 1f
    /** 0..1: a front leg flung forward (a fighter on four legs throwing something). */
    var throwL = 0f
    var throwR = 0f
    /** 0..1: the weapon arm swung up and over, for an overhead throw. */
    var swing = 0f
    /** 0..1 through a swing of a blade: the weapon sweeps flat across the front, from one side to the other. Below 0: none. */
    var slash = -1f
}

enum class Pass { SHADOW, COLOR, OUTLINE, SILHOUETTE }

/**
 * Builds and draws the original 3D fighters. One instance per GL context.
 */
class FighterModels {
    private val models = HashMap<FighterId, FighterModel>()
    private val bossModels = HashMap<io.github.projectwip.data.BossKind, FighterModel>()

    init {
        // (Swish took Spark's place, as Spark took Rocket's: both their models are kept. Spark's still stands in the
        // Training Area as its dummies, swarm and sentry: see [FighterDef.sparkLook].)
        models[FighterId.BYTE] = buildSwish()
        models[FighterId.BRAKK] = buildBrakk()
        models[FighterId.KIRA] = buildKira()
        models[FighterId.KITO] = buildKito()
        models[FighterId.BUDDY] = buildBuddy()
        bossModels[io.github.projectwip.data.BossKind.BARRAGE] = buildHailstorm()
        bossModels[io.github.projectwip.data.BossKind.SWEEPER] = buildLighthouse()
        bossModels[io.github.projectwip.data.BossKind.STAMPEDE] = buildRamrod()
    }

    fun model(id: FighterId) = models.getValue(id)
    /** A fighter's model, or a boss's own. */
    fun model(def: FighterDef) = def.boss?.let { bossModels.getValue(it) } ?: if (def.sparkLook) spark else models.getValue(def.id)
    private val spark by lazy { buildSpark() }

    // ------------------------------------------------------------------ construction helpers

    private class Assembler {
        private val builders = LinkedHashMap<Triple<Bone, Slot, Int>, MeshBuilder>()
        fun add(bone: Bone, slot: Slot, outline: Boolean = true, emissive: Boolean = false, gloss: Boolean = false, block: MeshBuilder.() -> Unit) {
            val key = Triple(bone, slot, (if (outline) 1 else 0) or (if (emissive) 2 else 0) or (if (gloss) 4 else 0))
            builders.getOrPut(key) { MeshBuilder() }.apply { with { block() } }
        }
        fun build(rig: Rig) = FighterModel(
            builders.filterValues { !it.isEmpty }.map { (k, b) -> Part(b.build(), k.second, k.first, k.third and 1 != 0, k.third and 2 != 0, k.third and 4 != 0) },
            rig,
        )
    }

    private fun MeshBuilder.at(x: Float, y: Float, z: Float, block: MeshBuilder.() -> Unit) = with { translate(x, y, z); block() }
    /** Rotate so a Y-axis primitive points along +X. */
    private fun MeshBuilder.alongX(block: MeshBuilder.() -> Unit) = with { rotate(-90f, 0f, 0f, 1f); block() }
    private fun MeshBuilder.alongZ(block: MeshBuilder.() -> Unit) = with { rotate(90f, 1f, 0f, 0f); block() }
    private fun MeshBuilder.octa(rx: Float, ry: Float, rz: Float) = ellipsoid(rx, ry, rz, 2, 4)

    // ------------------------------------------------------------------ Swish — a storm goddess with a giant bow

    private fun buildSwish(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.02f, hipY = 0.36f, hipZ = 0.11f, shoulder = floatArrayOf(0.08f, 0.76f, 0.27f), shoulderL = floatArrayOf(0.02f, 0.8f, -0.27f), floatY = 1.3f)
        // Slim legs in dark leggings, and boots with a lit band round the top.
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.12f, 0f) { capsule(0.065f, 0.16f) } }
            a.add(bone, Slot.SECONDARY) { at(0.04f, -0.29f, 0f) { roundedBox(0.24f, 0.1f, 0.13f, 0.045f) } }
            a.add(bone, Slot.ACCENT, outline = false, emissive = true) { at(0f, -0.21f, 0f) { torus(0.07f, 0.018f, 12, 5) } }
        }
        // A short dress that flares out, a lit hem, a sash, and a diamond brooch.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.42f, 0f) { cylinder(0.34f, 0.36f, 18, topRadius = 0.19f) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0f, 0.25f, 0f) { torus(0.335f, 0.022f, 22, 5) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.72f, 0f) { roundedBox(0.3f, 0.32f, 0.36f, 0.13f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.6f, 0f) { torus(0.2f, 0.04f, 16, 6) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.18f, 0.62f, 0f) { octa(0.04f, 0.06f, 0.04f) } }
        // A little capelet over the shoulders, and her neck.
        a.add(Bone.BODY, Slot.SECONDARY) { at(-0.03f, 0.87f, 0f) { ellipsoid(0.23f, 0.09f, 0.3f) } }
        a.add(Bone.BODY, Slot.SKIN) { at(0f, 0.93f, 0f) { capsule(0.06f, 0.06f) } }
        // Head: a round face with big eyes, long silver hair, and a tiara set with a diamond.
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.17f, 0f) { sphere(0.27f) } }
        a.add(Bone.HEAD, Slot.WHITE) { at(-0.05f, 0.18f, 0f) { ellipsoid(0.3f, 0.32f, 0.3f, 8, 16, 0f, 0.55f) } }
        a.add(Bone.HEAD, Slot.WHITE) { at(0.17f, 0.33f, 0f) { ellipsoid(0.12f, 0.08f, 0.27f) } }
        a.add(Bone.HEAD, Slot.WHITE) { at(-0.21f, -0.14f, 0f) { roundedBox(0.16f, 0.62f, 0.44f, 0.08f) } }
        for (z in listOf(0.24f, -0.24f)) a.add(Bone.HEAD, Slot.WHITE) { at(0.06f, -0.03f, z) { capsule(0.055f, 0.24f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            for (z in listOf(0.095f, -0.095f)) {
                at(0.245f, 0.16f, z) { ellipsoid(0.035f, 0.06f, 0.045f, 6, 8) }
                // (Lashes, flicked out at the corners.)
                at(0.25f, 0.225f, z * 1.15f) { rotate(if (z > 0f) -18f else 18f, 1f, 0f, 0f); roundedBox(0.02f, 0.016f, 0.08f, 0.006f) }
            }
            at(0.262f, 0.05f, 0f) { roundedBox(0.02f, 0.014f, 0.07f, 0.006f) }
        }
        a.add(Bone.HEAD, Slot.WHITE, outline = false) { for (z in listOf(0.08f, -0.11f)) at(0.275f, 0.185f, z) { sphere(0.014f, 4, 6) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) {
            at(0f, 0.34f, 0f) { rotate(-8f, 0f, 0f, 1f); torus(0.265f, 0.02f, 22, 5) }
            at(0.27f, 0.39f, 0f) { octa(0.05f, 0.08f, 0.045f) }
        }
        // A little thundercloud keeps her company, with a spark of lightning under it.
        a.add(Bone.FLOAT, Slot.METAL) {
            at(-0.42f, 0f, 0.42f) { sphere(0.11f, 8, 10) }
            at(-0.32f, 0.02f, 0.44f) { sphere(0.085f, 8, 10) }
            at(-0.52f, -0.01f, 0.4f) { sphere(0.08f, 8, 10) }
        }
        a.add(Bone.FLOAT, Slot.ACCENT, outline = false, emissive = true) { at(-0.42f, -0.14f, 0.42f) { rotate(25f, 1f, 0f, 0f); roundedBox(0.025f, 0.12f, 0.025f, 0.008f) } }
        // Her bow: a long arc held upright, lit at both tips, with its string.
        val r = 0.62f
        val cx = -0.3f
        val steps = 9
        val span = 62f
        for (k in 0 until steps) {
            val deg = -span + (2 * span) * (k + 0.5f) / steps
            val rad = Math.toRadians(deg.toDouble())
            val seg = r * Math.toRadians(2.0 * span / steps).toFloat()
            a.add(Bone.WEAPON, Slot.PRIMARY) {
                at(cx + r * kotlin.math.cos(rad).toFloat(), r * kotlin.math.sin(rad).toFloat(), 0f) { rotate(deg, 0f, 0f, 1f); capsule(0.035f, seg) }
            }
        }
        val tipX = cx + r * kotlin.math.cos(Math.toRadians(span.toDouble())).toFloat()
        val tipY = r * kotlin.math.sin(Math.toRadians(span.toDouble())).toFloat()
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) {
            at(tipX, tipY, 0f) { octa(0.045f, 0.07f, 0.045f) }
            at(tipX, -tipY, 0f) { octa(0.045f, 0.07f, 0.045f) }
        }
        a.add(Bone.WEAPON, Slot.WHITE, outline = false) { at(tipX, 0f, 0f) { cylinder(0.008f, tipY * 2f, 5) } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(cx + r, 0f, 0f) { roundedBox(0.08f, 0.16f, 0.08f, 0.03f) } }
        a.add(Bone.WEAPON, Slot.SKIN) { at(cx + r - 0.02f, 0f, 0f) { sphere(0.065f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.1f, 0f) { capsule(0.07f, 0.12f) } }
        a.add(Bone.ARM, Slot.SKIN) { at(0f, -0.25f, 0f) { sphere(0.065f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Spark — the Spark Drop that woke up (retired: the Training Area's targets)

    private fun buildSpark(): FighterModel {
        val a = Assembler()
        val cy = 0.8f
        val r = 0.66f
        // He stands up straight, his face to the front, leaning back only a touch. (For two builds he leaned back 65
        // degrees to read from above, and stared at the sky.) What reads from above now is the star's bulk: it is a
        // fat one, front and back.
        val lean = 10f
        val puff = 0.4f
        fun MeshBuilder.onStar(block: MeshBuilder.() -> Unit) = at(0f, cy, 0f) { rotate(lean, 0f, 0f, 1f); block() }
        fun MeshBuilder.inPlane(block: MeshBuilder.() -> Unit) = onStar { rotate(90f, 0f, 1f, 0f); block() }
        val rig = Rig(headY = 1.45f, hipY = 0.46f, hipZ = 0.2f, shoulder = floatArrayOf(0f, cy + 0.18f, 0.64f), shoulderL = floatArrayOf(0f, cy + 0.18f, -0.64f), floatY = 1.55f)
        // Two short legs on round glossy feet.
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.17f, 0f) { capsule(0.09f, 0.18f) } }
            a.add(bone, Slot.SECONDARY, gloss = true) { at(0.05f, -0.42f, 0f) { ellipsoid(0.17f, 0.09f, 0.13f, 8, 12) } }
        }
        // Stubby arms out of the side points, in white mitts.
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.1f, 0f) { capsule(0.065f, 0.11f) } }
        a.add(Bone.ARM, Slot.WHITE, gloss = true) { at(0f, -0.24f, 0f) { sphere(0.1f, 8, 10) } }
        // The body is the drop itself, just as the Spark Drops looked: a puffy five-pointed star in glossy chromatic
        // blue, its face to the front, with a deeper blue star set in behind it and a lit seam where the halves meet.
        a.add(Bone.BODY, Slot.PRIMARY, gloss = true) { inPlane { puffyStar(r, puff) } }
        a.add(Bone.BODY, Slot.SECONDARY, gloss = true) { onStar { at(-0.1f, 0f, 0f) { rotate(90f, 0f, 1f, 0f); puffyStar(r * 0.88f, puff * 0.85f) } } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { inPlane { puffyStar(r * 1.03f, 0.05f) } }
        // A face with some nerve: narrow eyes set at an angle under two low brows, one cocked higher than the other,
        // a small catch of light in each, and a smirk pulled up on one side. (The surface of the star falls from
        // [puff] in the middle to a quarter of it at the rim.)
        fun surface(y: Float, z: Float) = puff - 0.75f * puff * (kotlin.math.hypot(y, z) / 0.45f).coerceAtMost(1f)
        for (side in floatArrayOf(1f, -1f)) {
            val z = 0.11f * side
            val ex = surface(0.07f, z)
            a.add(Bone.BODY, Slot.INK, outline = false) { onStar { at(ex - 0.01f, 0.07f, z) { rotate(-14f * side, 1f, 0f, 0f); ellipsoid(0.05f, 0.072f, 0.07f, 8, 12) } } }
            a.add(Bone.BODY, Slot.WHITE, outline = false) { onStar { at(ex + 0.03f, 0.095f, z + 0.02f) { sphere(0.015f, 5, 6) } } }
            val by = if (side < 0f) 0.185f else 0.16f
            a.add(Bone.BODY, Slot.INK, outline = false) { onStar { at(surface(by, z) + 0.005f, by, z * 1.05f) { rotate((if (side < 0f) -12f else -22f) * side, 1f, 0f, 0f); roundedBox(0.05f, 0.028f, 0.12f, 0.012f) } } }
        }
        a.add(Bone.BODY, Slot.INK, outline = false) {
            onStar {
                for (i in -3..3) {
                    val u = i / 3f
                    val z = u * 0.075f
                    val y = -0.1f + 0.022f * u * u - 0.028f * u
                    at(surface(y, z) + 0.008f, y, z) { sphere(0.018f, 5, 6) }
                }
            }
        }
        // A few little stars twinkling round the top point.
        a.add(Bone.FLOAT, Slot.ACCENT, outline = false, emissive = true) {
            for (n in 0 until 3) { val t = n * 2.094f; at(kotlin.math.cos(t) * 0.34f, (n - 1) * 0.06f, sin(t) * 0.34f) { rotate(t * 57.3f, 0f, 1f, 0f); puffyStar(0.07f, 0.035f) } }
        }
        // What he throws: a little star of his own, lit, held out in a white mitt.
        a.add(Bone.WEAPON, Slot.WHITE, gloss = true) { at(0.02f, 0f, 0f) { sphere(0.1f, 8, 10) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.13f, 0.06f, 0.04f) { rotate(90f, 0f, 1f, 0f); puffyStar(0.15f, 0.075f) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Rocket — ran away from a fireworks barge with the whole stock on her back

    private fun buildRocket(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.02f, hipY = 0.42f, hipZ = 0.14f, shoulder = floatArrayOf(0.08f, 0.76f, 0.3f), shoulderL = floatArrayOf(0.02f, 0.82f, -0.3f))
        // Flight-suit legs in tall boots, each with a cuff and a lit heel.
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.PRIMARY) { at(0f, -0.13f, 0f) { capsule(0.1f, 0.14f) } }
            a.add(bone, Slot.SECONDARY) { at(0f, -0.28f, 0f) { cylinder(0.115f, 0.16f, 12, topRadius = 0.13f) } }
            a.add(bone, Slot.SECONDARY) { at(0.05f, -0.38f, 0f) { roundedBox(0.3f, 0.12f, 0.2f, 0.06f) } }
            a.add(bone, Slot.METAL) { at(0f, -0.2f, 0f) { torus(0.13f, 0.025f, 14, 6) } }
            a.add(bone, Slot.ACCENT, outline = false, emissive = true) { at(-0.1f, -0.39f, 0f) { roundedBox(0.05f, 0.07f, 0.13f, 0.02f) } }
        }
        // A short flight jacket over a dark undersuit, with a stripe round the hem, a chevron on the chest and a lit pin.
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.52f, 0f) { roundedBox(0.38f, 0.24f, 0.44f, 0.1f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.74f, 0f) { roundedBox(0.46f, 0.4f, 0.54f, 0.18f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.01f, 0.6f, 0f) { roundedBox(0.47f, 0.07f, 0.55f, 0.03f) } }
        for (s in listOf(1f, -1f)) {
            a.add(Bone.BODY, Slot.SECONDARY) { at(0.235f, 0.76f, 0.075f * s) { rotate(35f * s, 1f, 0f, 0f); roundedBox(0.03f, 0.06f, 0.2f, 0.01f) } }
        }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.24f, 0.86f, 0f) { octa(0.03f, 0.06f, 0.06f) } }
        a.add(Bone.BODY, Slot.METAL) { at(0f, 0.47f, 0f) { torus(0.26f, 0.035f, 18, 6) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.27f, 0.47f, 0f) { roundedBox(0.04f, 0.08f, 0.1f, 0.015f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.95f, 0f) { torus(0.17f, 0.06f, 16, 6) } }
        // The pack on her back: two boosters with nose cones, fins and nozzles, and a flame burning under each.
        a.add(Bone.BODY, Slot.METAL) { at(-0.27f, 0.74f, 0f) { roundedBox(0.14f, 0.36f, 0.3f, 0.05f) } }
        for (z in listOf(0.2f, -0.2f)) {
            a.add(Bone.BODY, Slot.WHITE) { at(-0.37f, 0.75f, z) { cylinder(0.11f, 0.44f, 12) } }
            a.add(Bone.BODY, Slot.SECONDARY) { at(-0.37f, 1.07f, z) { cylinder(0.11f, 0.2f, 12, topRadius = 0f) } }
            a.add(Bone.BODY, Slot.SECONDARY) { at(-0.37f, 0.8f, z) { torus(0.115f, 0.025f, 14, 6) } }
            a.add(Bone.BODY, Slot.DARK) { at(-0.37f, 0.49f, z) { cylinder(0.125f, 0.1f, 12, topRadius = 0.08f) } }
            a.add(Bone.BODY, Slot.SECONDARY) { at(-0.49f, 0.62f, z) { roundedBox(0.14f, 0.2f, 0.035f, 0.012f) } }
            a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(-0.37f, 0.33f, z) { rotate(180f, 0f, 0f, 1f); cylinder(0.085f, 0.24f, 10, topRadius = 0f) } }
            a.add(Bone.BODY, Slot.WHITE, outline = false, emissive = true) { at(-0.37f, 0.41f, z) { sphere(0.05f, 6, 8) } }
        }
        // Head: hair the colour of a flame swept back into two long tails like exhaust plumes, lit at the tips;
        // flying goggles pushed up on her forehead, and a fin on top.
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.2f, 0f) { sphere(0.3f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            for (z in listOf(0.11f, -0.11f)) at(0.27f, 0.2f, z) { ellipsoid(0.04f, 0.06f, 0.04f, 6, 8) }
        }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.06f, 0.24f, 0f) { ellipsoid(0.33f, 0.32f, 0.34f, 8, 16, 0f, 0.55f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.2f, 0.1f, 0f) { roundedBox(0.24f, 0.3f, 0.56f, 0.1f) } }
        for (z in listOf(0.24f, -0.24f)) {
            a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.42f, 0.16f, z) { rotate(if (z > 0f) 14f else -14f, 0f, 1f, 0f); rotate(105f, 0f, 0f, 1f); cylinder(0.11f, 0.5f, 10, topRadius = 0.03f) } }
            a.add(Bone.HEAD, Slot.METAL) { at(-0.25f, 0.2f, z) { alongX { torus(0.105f, 0.03f, 12, 6) } } }
            a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(-0.67f, 0.09f, z * 1.27f) { sphere(0.065f, 6, 8) } }
        }
        a.add(Bone.HEAD, Slot.DARK) { at(0f, 0.38f, 0f) { torus(0.295f, 0.035f, 20, 6) } }
        for (z in listOf(0.12f, -0.12f)) {
            a.add(Bone.HEAD, Slot.METAL) { at(0.25f, 0.4f, z) { alongX { cylinder(0.095f, 0.09f, 12) } } }
            a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.3f, 0.4f, z) { alongX { cylinder(0.07f, 0.03f, 12) } } }
        }
        a.add(Bone.HEAD, Slot.WHITE) { at(-0.1f, 0.6f, 0f) { rotate(-24f, 0f, 0f, 1f); roundedBox(0.3f, 0.16f, 0.045f, 0.015f) } }
        // The launcher: a fat tube with a flared mouth, a rocket's nose showing in it, a lit sight and a grip.
        a.add(Bone.WEAPON, Slot.METAL) { at(0.3f, 0.02f, 0f) { alongX { cylinder(0.12f, 0.62f, 14) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.56f, 0.02f, 0f) { alongX { cylinder(0.15f, 0.12f, 14, topRadius = 0.17f) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.1f, 0.02f, 0f) { alongX { torus(0.125f, 0.03f, 14, 6) } } }
        a.add(Bone.WEAPON, Slot.DARK) { at(-0.04f, 0.02f, 0f) { alongX { cylinder(0.14f, 0.08f, 12, topRadius = 0.1f) } } }
        a.add(Bone.WEAPON, Slot.WHITE) { at(0.66f, 0.02f, 0f) { alongX { cylinder(0.09f, 0.12f, 12) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.8f, 0.02f, 0f) { alongX { cylinder(0.09f, 0.16f, 12, topRadius = 0f) } } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.3f, 0.17f, 0f) { roundedBox(0.16f, 0.05f, 0.05f, 0.02f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.24f, -0.14f, 0f) { roundedBox(0.07f, 0.16f, 0.07f, 0.025f) } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.04f, 0f, 0f) { sphere(0.1f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.11f, 0f) { capsule(0.085f, 0.14f) } }
        a.add(Bone.ARM, Slot.SECONDARY) { at(0f, -0.28f, 0f) { sphere(0.09f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Brakk — scrapyard hound

    private fun buildBrakk(): FighterModel {
        val a = Assembler()
        // A dog on four legs. His head hangs on the weapon bone, so it snaps back when he lets fly: a bark. His tail
        // is on the arm bone, so it wags as he runs.
        val rig = Rig(headY = 1.0f, hipY = 0.44f, hipZ = 0.2f, shoulder = floatArrayOf(0.44f, 0.84f, 0f), shoulderL = floatArrayOf(-0.5f, 0.78f, 0f),
            foreX = 0.3f, hindX = -0.32f)
        // Hind legs are his own; one front leg is scrap.
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.PRIMARY) { at(-0.02f, -0.08f, 0f) { roundedBox(0.24f, 0.26f, 0.2f, 0.09f) } }
            a.add(bone, Slot.PRIMARY) { at(0.02f, -0.24f, 0f) { capsule(0.07f, 0.12f) } }
            a.add(bone, Slot.DARK) { at(0.06f, -0.39f, 0f) { roundedBox(0.22f, 0.1f, 0.18f, 0.05f) } }
        }
        a.add(Bone.FORE_L, Slot.PRIMARY) { at(0f, -0.17f, 0f) { capsule(0.075f, 0.2f) } }
        a.add(Bone.FORE_L, Slot.DARK) { at(0.05f, -0.39f, 0f) { roundedBox(0.22f, 0.1f, 0.18f, 0.05f) } }
        a.add(Bone.FORE_R, Slot.METAL) { at(0f, -0.17f, 0f) { roundedBox(0.13f, 0.34f, 0.13f, 0.04f) } }
        a.add(Bone.FORE_R, Slot.ACCENT, outline = false, emissive = true) { at(0.07f, -0.15f, 0f) { roundedBox(0.02f, 0.18f, 0.06f, 0.01f) } }
        a.add(Bone.FORE_R, Slot.METAL) { at(0.05f, -0.39f, 0f) { roundedBox(0.24f, 0.1f, 0.2f, 0.05f) } }
        // A long barrel of a body, deep in the chest, with a scrap plate bolted over the back.
        a.add(Bone.BODY, Slot.PRIMARY) { at(-0.04f, 0.64f, 0f) { roundedBox(0.92f, 0.42f, 0.5f, 0.2f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0.26f, 0.66f, 0f) { roundedBox(0.4f, 0.5f, 0.56f, 0.2f) } }
        a.add(Bone.BODY, Slot.WHITE) { at(0.36f, 0.56f, 0f) { roundedBox(0.24f, 0.3f, 0.4f, 0.11f) } }
        a.add(Bone.BODY, Slot.METAL) { at(-0.1f, 0.86f, 0f) { roundedBox(0.5f, 0.08f, 0.42f, 0.03f) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false) {
            for (x in listOf(-0.3f, 0.1f)) for (z in listOf(0.16f, -0.16f)) at(x, 0.91f, z) { sphere(0.035f, 6, 8) }
        }
        // The collar, with a tag that glows.
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.42f, 0.8f, 0f) { rotate(-62f, 0f, 0f, 1f); torus(0.2f, 0.055f, 16, 6) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.56f, 0.64f, 0f) { sphere(0.06f, 8, 10) } }
        // Head: a square skull, a long scrap muzzle, one eye of his own and one that glows, and two pricked ears.
        a.add(Bone.WEAPON, Slot.PRIMARY) { at(0.12f, 0.16f, 0f) { roundedBox(0.38f, 0.36f, 0.42f, 0.14f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.4f, 0.08f, 0f) { roundedBox(0.3f, 0.2f, 0.26f, 0.07f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.38f, -0.04f, 0f) { roundedBox(0.26f, 0.07f, 0.22f, 0.03f) } }
        a.add(Bone.WEAPON, Slot.INK, outline = false) { at(0.56f, 0.13f, 0f) { sphere(0.065f, 8, 10) } }
        a.add(Bone.WEAPON, Slot.INK, outline = false) { at(0.3f, 0.23f, 0.15f) { sphere(0.05f, 6, 8) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.3f, 0.23f, -0.15f) { sphere(0.06f, 8, 10) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.02f, 0.46f, 0.15f) { rotate(14f, 1f, 0f, 0f); cylinder(0.09f, 0.26f, 6, topRadius = 0f) } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.02f, 0.46f, -0.15f) { rotate(-14f, 1f, 0f, 0f); cylinder(0.09f, 0.26f, 6, topRadius = 0f) } }
        // Tail: up and over, with a bright tip.
        a.add(Bone.ARM, Slot.PRIMARY) { at(-0.08f, 0.13f, 0f) { rotate(32f, 0f, 0f, 1f); capsule(0.055f, 0.24f) } }
        a.add(Bone.ARM, Slot.SECONDARY) { at(-0.19f, 0.29f, 0f) { sphere(0.075f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Kira — hedge witch

    private fun buildKira(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.0f, hipY = 0.3f, hipZ = 0.12f, shoulder = floatArrayOf(0.08f, 0.78f, 0.3f), shoulderL = floatArrayOf(0f, 0.84f, -0.3f), floatY = 1.2f)
        // Pointed shoes under a long robe that flares to the ground.
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.12f, 0f) { capsule(0.07f, 0.12f) } }
            a.add(bone, Slot.DARK) { at(0.07f, -0.27f, 0f) { roundedBox(0.3f, 0.09f, 0.14f, 0.04f) } }
            a.add(bone, Slot.ACCENT) { at(0.24f, -0.22f, 0f) { sphere(0.05f, 6, 8) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.5f, 0f) { cylinder(0.46f, 0.8f, 18, topRadius = 0.2f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.13f, 0f) { torus(0.45f, 0.045f, 22, 6) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.6f, 0f) { torus(0.31f, 0.045f, 18, 6) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false) { at(0.31f, 0.6f, 0f) { roundedBox(0.06f, 0.11f, 0.11f, 0.02f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.9f, 0f) { ellipsoid(0.25f, 0.14f, 0.29f) } }
        // Three little bottles on her belt, each with something glowing in it.
        a.add(Bone.BODY, Slot.SECONDARY, outline = false, emissive = true) {
            for (z in listOf(0.2f, 0.3f)) at(0.18f, 0.48f, z) { capsule(0.04f, 0.07f) }
            at(0.16f, 0.48f, -0.26f) { capsule(0.04f, 0.07f) }
        }
        // Head: a pale face, hair down her back, and the hat: a wide brim and a tall crooked point.
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.16f, 0f) { sphere(0.28f) } }
        a.add(Bone.HEAD, Slot.DARK) { at(-0.08f, 0.14f, 0f) { ellipsoid(0.3f, 0.34f, 0.31f, 8, 16, 0f, 0.55f) } }
        a.add(Bone.HEAD, Slot.DARK) { at(-0.22f, -0.08f, 0f) { roundedBox(0.2f, 0.36f, 0.46f, 0.08f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            at(0.255f, 0.17f, 0.1f) { sphere(0.045f, 6, 8) }
            at(0.255f, 0.17f, -0.1f) { sphere(0.045f, 6, 8) }
        }
        a.add(Bone.HEAD, Slot.SECONDARY, outline = false, emissive = true) { at(0.27f, 0.06f, 0f) { roundedBox(0.03f, 0.03f, 0.14f, 0.01f) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0f, 0.38f, 0f) { cylinder(0.52f, 0.05f, 22) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.04f, 0.68f, 0f) { rotate(12f, 0f, 0f, 1f); cylinder(0.29f, 0.62f, 14, topRadius = 0.02f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(0f, 0.44f, 0f) { torus(0.29f, 0.05f, 18, 6) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false) { at(0.3f, 0.45f, 0f) { roundedBox(0.05f, 0.12f, 0.12f, 0.02f) } }
        // A wisp of the stuff follows her about.
        a.add(Bone.FLOAT, Slot.SECONDARY, outline = false, emissive = true) { at(-0.45f, 0f, 0.4f) { sphere(0.09f, 8, 10) } }
        // Her staff: a crooked stick with a ball of poison held in a claw at the end.
        a.add(Bone.WEAPON, Slot.DARK) { at(0.38f, 0f, 0f) { alongX { cylinder(0.04f, 1.0f, 8) } } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.86f, 0f, 0f) { alongX { torus(0.1f, 0.03f, 10, 5) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY, emissive = true) { at(0.97f, 0f, 0f) { sphere(0.14f, 8, 12) } }
        a.add(Bone.WEAPON, Slot.SKIN) { at(0.06f, 0f, 0f) { sphere(0.08f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.1f, 0f) { capsule(0.085f, 0.14f) } }
        a.add(Bone.ARM, Slot.SKIN) { at(0f, -0.27f, 0f) { sphere(0.08f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Kito — a ninja with a sword, and a hammer for his super

    private fun buildKito(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.0f, hipY = 0.4f, hipZ = 0.13f, shoulder = floatArrayOf(0.1f, 0.72f, 0.27f), shoulderL = floatArrayOf(0.02f, 0.8f, -0.27f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.15f, 0f) { capsule(0.085f, 0.18f) } }
            a.add(bone, Slot.ACCENT) { at(0.04f, -0.36f, 0f) { roundedBox(0.26f, 0.1f, 0.16f, 0.05f) } }
        }
        // Slim torso with a sash and a chest plate.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.66f, 0f) { roundedBox(0.4f, 0.5f, 0.46f, 0.18f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.5f, 0f) { roundedBox(0.43f, 0.09f, 0.49f, 0.04f) } }
        a.add(Bone.BODY, Slot.METAL) { at(0.2f, 0.72f, 0f) { roundedBox(0.08f, 0.22f, 0.3f, 0.03f) } }
        // Scarf: a ring at the neck and a tail streaming out behind.
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.92f, 0f) { torus(0.17f, 0.07f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(-0.36f, 0.86f, 0.1f) { rotate(-22f, 0f, 0f, 1f); roundedBox(0.48f, 0.07f, 0.16f, 0.03f) } }
        // Hooded head with a glowing visor slit and two swept-back fins.
        a.add(Bone.HEAD, Slot.DARK) { at(0f, 0.2f, 0f) { sphere(0.31f) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.05f, 0.25f, 0f) { ellipsoid(0.34f, 0.33f, 0.34f, 8, 16, 0f, 0.5f) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.26f, 0.2f, 0f) { roundedBox(0.1f, 0.07f, 0.4f, 0.03f) } }
        for (z in listOf(0.2f, -0.2f)) {
            a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.12f, 0.5f, z) { rotate(if (z > 0f) 20f else -20f, 1f, 0f, 0f); cylinder(0.055f, 0.3f, 6, topRadius = 0f) } }
        }
        // His sword: hilt, guard and a long glowing edge.
        a.add(Bone.WEAPON, Slot.METAL) { at(0.06f, 0f, 0f) { alongX { cylinder(0.05f, 0.2f, 8) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.18f, 0f, 0f) { roundedBox(0.06f, 0.2f, 0.12f, 0.02f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, emissive = true) { at(0.68f, 0f, 0f) { roundedBox(0.94f, 0.12f, 0.035f, 0.015f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.02f, 0f, 0f) { sphere(0.09f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.11f, 0f) { capsule(0.08f, 0.14f) } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.28f, 0f) { sphere(0.085f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Buddy — a rogue AI with a hologram for a face

    private fun buildBuddy(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.1f, hipY = 0.4f, hipZ = 0.17f, shoulder = floatArrayOf(0.14f, 0.78f, 0.4f), shoulderL = floatArrayOf(0.02f, 0.84f, -0.34f), floatY = 1.5f)
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.15f, 0f) { roundedBox(0.17f, 0.32f, 0.17f, 0.05f) } }
            a.add(bone, Slot.METAL) { at(0.05f, -0.36f, 0f) { roundedBox(0.32f, 0.12f, 0.2f, 0.05f) } }
        }
        // The body is a server tower: a dark case, lit vents down the front, a row of status lights, a cable loop behind.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.68f, 0f) { roundedBox(0.46f, 0.62f, 0.56f, 0.1f) } }
        a.add(Bone.BODY, Slot.METAL) { at(0f, 0.4f, 0f) { roundedBox(0.5f, 0.08f, 0.6f, 0.03f) } }
        a.add(Bone.BODY, Slot.SECONDARY, outline = false, emissive = true) {
            for (y in listOf(0.56f, 0.66f, 0.76f)) at(0.235f, y, 0f) { roundedBox(0.02f, 0.04f, 0.36f, 0.01f) }
        }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) {
            for (z in listOf(-0.14f, 0f, 0.14f)) at(0.235f, 0.9f, z) { sphere(0.035f, 6, 8) }
        }
        a.add(Bone.BODY, Slot.DARK) { at(-0.28f, 0.66f, 0f) { alongZ { torus(0.16f, 0.04f, 14, 6) } } }
        // No head. A projector ring sits on the shoulders and throws a face into the air above it: a flat screen of
        // light with nothing behind it, two eyes and a mouth drawn on, and a bright bar along its top and bottom.
        a.add(Bone.HEAD, Slot.METAL) { at(0f, -0.06f, 0f) { cylinder(0.17f, 0.07f, 12) } }
        a.add(Bone.HEAD, Slot.SECONDARY, outline = false, emissive = true) { at(0f, -0.01f, 0f) { torus(0.13f, 0.025f, 14, 5) } }
        // (The screen leans back, so that it can be read from above as well as from in front.)
        fun MeshBuilder.screen(block: MeshBuilder.() -> Unit) = at(0f, 0.36f, 0f) { rotate(34f, 0f, 0f, 1f); block() }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { screen { roundedBox(0.012f, 0.46f, 0.66f, 0.005f) } }
        a.add(Bone.HEAD, Slot.SECONDARY, outline = false, emissive = true) {
            screen {
                for (y in listOf(-0.24f, 0.24f)) at(0f, y, 0f) { roundedBox(0.03f, 0.025f, 0.72f, 0.008f) }
                for (z in listOf(-0.36f, 0.36f)) at(0f, 0f, z) { roundedBox(0.03f, 0.5f, 0.02f, 0.008f) }
            }
        }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            screen {
                for (z in listOf(-0.16f, 0.16f)) at(0.015f, 0.07f, z) { roundedBox(0.012f, 0.15f, 0.1f, 0.004f) }
                at(0.015f, -0.12f, 0f) { roundedBox(0.012f, 0.04f, 0.3f, 0.004f) }
            }
        }
        // Loose bits of code turning round the screen.
        a.add(Bone.FLOAT, Slot.SECONDARY, outline = false, emissive = true) {
            for (k in 0 until 3) { val t = k * 2.094f; at(kotlin.math.cos(t) * 0.5f, (k - 1) * 0.1f, sin(t) * 0.5f) { roundedBox(0.07f, 0.07f, 0.07f, 0.01f) } }
        }
        // What it hits people with: a whole computer, monitor and all, held out in front.
        a.add(Bone.WEAPON, Slot.METAL) { at(0.36f, 0f, 0f) { roundedBox(0.36f, 0.36f, 0.44f, 0.05f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.545f, 0.02f, 0f) { roundedBox(0.02f, 0.26f, 0.34f, 0.01f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.36f, -0.22f, 0f) { roundedBox(0.4f, 0.06f, 0.5f, 0.02f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.06f, 0f, 0f) { sphere(0.11f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.12f, 0f) { roundedBox(0.15f, 0.3f, 0.15f, 0.05f) } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.31f, 0f) { sphere(0.1f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Hailstorm — a walking launch pad

    private fun buildHailstorm(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 0.98f, hipY = 0.36f, hipZ = 0.3f, shoulder = floatArrayOf(0.1f, 0.62f, 0f), shoulderL = floatArrayOf(0f, 0.9f, -0.56f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.14f, 0f) { roundedBox(0.34f, 0.32f, 0.3f, 0.1f) } }
            a.add(bone, Slot.METAL) { at(0.06f, -0.32f, 0f) { roundedBox(0.46f, 0.12f, 0.36f, 0.05f) } }
        }
        // A squat hull with a warning stripe and a dark waist.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.72f, 0f) { roundedBox(0.86f, 0.5f, 0.98f, 0.18f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.6f, 0f) { roundedBox(0.88f, 0.1f, 1.0f, 0.04f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.48f, 0f) { roundedBox(0.7f, 0.12f, 0.8f, 0.05f) } }
        // A rocket pod on each shoulder: a box of tubes tipped up at the sky, a warhead showing in each.
        for (z in listOf(0.5f, -0.5f)) {
            a.add(Bone.BODY, Slot.METAL) { at(-0.08f, 1.12f, z) { rotate(28f, 0f, 0f, 1f); roundedBox(0.62f, 0.4f, 0.4f, 0.07f) } }
            a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) {
                for (dy in listOf(0.09f, -0.09f)) for (dz in listOf(0.09f, -0.09f)) at(0.19f, 1.27f + dy, z + dz) { sphere(0.07f, 6, 8) }
            }
        }
        // A low head between the pods: one wide visor.
        a.add(Bone.HEAD, Slot.DARK) { at(0.04f, 0.06f, 0f) { roundedBox(0.4f, 0.26f, 0.44f, 0.11f) } }
        a.add(Bone.HEAD, Slot.SECONDARY, outline = false, emissive = true) { at(0.22f, 0.08f, 0f) { roundedBox(0.06f, 0.08f, 0.34f, 0.03f) } }
        // The flak gun in its chest: a ring of short barrels.
        a.add(Bone.WEAPON, Slot.DARK) { at(0.36f, 0f, 0f) { alongX { cylinder(0.2f, 0.24f, 14) } } }
        a.add(Bone.WEAPON, Slot.METAL) { for (k in 0 until 6) { val t = k * 1.047f; at(0.52f, sin(t) * 0.11f, kotlin.math.cos(t) * 0.11f) { alongX { cylinder(0.04f, 0.22f, 6) } } } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.1f, 0f) { capsule(0.12f, 0.12f) } }
        a.add(Bone.ARM, Slot.METAL) { at(0.02f, -0.3f, 0f) { roundedBox(0.24f, 0.22f, 0.22f, 0.08f) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Lighthouse — a tower with a lamp for a head

    private fun buildLighthouse(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.22f, hipY = 0.26f, hipZ = 0.2f, shoulder = floatArrayOf(0.12f, 1.36f, 0f), shoulderL = floatArrayOf(0f, 0.8f, -0.4f), floatY = 1.42f)
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.1f, 0f) { capsule(0.1f, 0.1f) } }
            a.add(bone, Slot.METAL) { at(0.04f, -0.24f, 0f) { roundedBox(0.3f, 0.1f, 0.24f, 0.04f) } }
        }
        // A tapering tower in bands, on a wide foot, with a gallery near the top.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.72f, 0f) { cylinder(0.42f, 1.0f, 18, topRadius = 0.26f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { for ((y, r) in listOf(0.5f to 0.4f, 0.86f to 0.33f)) at(0f, y, 0f) { cylinder(r, 0.16f, 18, topRadius = r - 0.025f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.2f, 0f) { cylinder(0.5f, 0.12f, 18) } }
        a.add(Bone.BODY, Slot.METAL) { at(0f, 1.22f, 0f) { torus(0.34f, 0.045f, 20, 6) } }
        // The lamp room: a glowing lens under a cap.
        a.add(Bone.HEAD, Slot.DARK) { at(0f, 0.02f, 0f) { cylinder(0.3f, 0.06f, 14) } }
        a.add(Bone.HEAD, Slot.ACCENT, emissive = true) { at(0f, 0.2f, 0f) { sphere(0.24f, 10, 14) } }
        a.add(Bone.HEAD, Slot.METAL) { at(0f, 0.44f, 0f) { cylinder(0.32f, 0.2f, 14, topRadius = 0.04f) } }
        // A ring of light that turns round the lamp.
        a.add(Bone.FLOAT, Slot.SECONDARY, emissive = true) {
            torus(0.44f, 0.03f, 24, 6)
            for (k in 0 until 4) { val t = k * 1.571f; at(kotlin.math.cos(t) * 0.44f, 0f, sin(t) * 0.44f) { sphere(0.07f, 6, 8) } }
        }
        // The beam's nozzle, pointing where it is about to sweep.
        a.add(Bone.WEAPON, Slot.METAL) { at(0.3f, 0f, 0f) { alongX { cylinder(0.11f, 0.3f, 12, topRadius = 0.16f) } } }
        a.add(Bone.WEAPON, Slot.ACCENT, emissive = true) { at(0.47f, 0f, 0f) { sphere(0.1f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.08f, 0f) { capsule(0.07f, 0.1f) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Ramrod — a bull made for knocking walls down

    private fun buildRamrod(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 0.74f, hipY = 0.36f, hipZ = 0.3f, shoulder = floatArrayOf(0.44f, 0.56f, 0f), shoulderL = floatArrayOf(-0.2f, 0.8f, -0.5f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.PRIMARY) { at(0f, -0.14f, 0f) { roundedBox(0.3f, 0.3f, 0.28f, 0.1f) } }
            a.add(bone, Slot.DARK) { at(0.03f, -0.32f, 0f) { roundedBox(0.36f, 0.1f, 0.32f, 0.04f) } }
        }
        // A long barrel of a body, heavy at the shoulders, with a hump and a stub of a tail.
        a.add(Bone.BODY, Slot.PRIMARY) { at(-0.05f, 0.68f, 0f) { roundedBox(1.0f, 0.56f, 0.86f, 0.24f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0.18f, 0.98f, 0f) { ellipsoid(0.32f, 0.2f, 0.36f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.12f, 0.7f, 0f) { roundedBox(0.12f, 0.6f, 0.9f, 0.05f) } }
        a.add(Bone.BODY, Slot.DARK) { at(-0.62f, 0.78f, 0f) { rotate(40f, 0f, 0f, 1f); capsule(0.05f, 0.24f) } }
        a.add(Bone.BODY, Slot.METAL, outline = false) { for (z in listOf(0.44f, -0.44f)) for (x in listOf(-0.3f, 0f, 0.3f)) at(x, 0.9f, z) { sphere(0.05f, 6, 8) } }
        // Head held low: glowing eyes, a ring through the nose, two long horns swept forward.
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0.3f, 0.06f, 0f) { roundedBox(0.44f, 0.36f, 0.46f, 0.15f) } }
        a.add(Bone.HEAD, Slot.DARK) { at(0.52f, -0.04f, 0f) { roundedBox(0.14f, 0.2f, 0.34f, 0.07f) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { for (z in listOf(0.13f, -0.13f)) at(0.5f, 0.12f, z) { sphere(0.05f, 6, 8) } }
        a.add(Bone.HEAD, Slot.ACCENT) { at(0.6f, -0.14f, 0f) { alongZ { torus(0.07f, 0.02f, 12, 5) } } }
        for (z in listOf(0.26f, -0.26f)) {
            a.add(Bone.HEAD, Slot.SECONDARY) { at(0.34f, 0.28f, z) { rotate(if (z > 0f) 60f else -60f, 1f, 0f, 0f); rotate(-35f, 0f, 0f, 1f); cylinder(0.09f, 0.5f, 8, topRadius = 0f) } }
        }
        // The ram: a steel plate carried in front of everything else.
        a.add(Bone.WEAPON, Slot.METAL) { at(0.42f, 0f, 0f) { roundedBox(0.14f, 0.5f, 0.9f, 0.05f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { for (z in listOf(0.26f, 0f, -0.26f)) at(0.5f, 0f, z) { octa(0.09f, 0.09f, 0.07f) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.1f, 0f) { capsule(0.11f, 0.12f) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ drawing

    private val root = FloatArray(16)
    private val body = FloatArray(16)
    private val bone = FloatArray(16)
    private val tmp = FloatArray(16)

    /**
     * Draws a fighter standing at (x, z) on the ground, facing [facing] radians (sim convention:
     * 0 = +X, π/2 = +Z). The caller has already bound [prog] and set camera/light uniforms.
     */
    /** [shade]: draw the fighter as a shadow of this colour, with whatever glows on it glowing red. */
    fun draw(prog: Program, def: FighterDef, skinIndex: Int, x: Float, z: Float, facing: Float, anim: FighterAnim, pass: Pass, ink: FloatArray = INK, shade: Long = 0L) {
        val model = model(def)
        val rig = model.rig
        val skin = def.skins[skinIndex.coerceIn(0, def.skins.lastIndex)]
        val t = anim.time
        val mv = anim.moving

        Matrix.setIdentityM(root, 0)
        Matrix.translateM(root, 0, x, anim.jump, z)
        Matrix.rotateM(root, 0, -Math.toDegrees(facing.toDouble()).toFloat() + anim.spin, 0f, 1f, 0f)
        Matrix.scaleM(root, 0, anim.scale, anim.scale, anim.scale)

        val bob = abs(sin(anim.walk)) * 0.07f * mv + sin(t * 2.4f) * 0.012f * (1 - mv)
        val breathe = 1f + sin(t * 2.4f) * 0.018f * (1 - mv)
        System.arraycopy(root, 0, body, 0, 16)
        Matrix.translateM(body, 0, 0f, bob, 0f)
        Matrix.rotateM(body, 0, -8f * mv + maxOf(anim.throwL, anim.throwR) * 9f, 0f, 0f, 1f) // lean forward when running; rear up to throw
        Matrix.scaleM(body, 0, 1f, breathe, 1f)

        for (p in model.parts) {
            if (pass == Pass.OUTLINE && !p.outline) continue
            if (pass == Pass.SILHOUETTE && p.bone == Bone.FLOAT) continue
            when (p.bone) {
                Bone.BODY -> System.arraycopy(body, 0, bone, 0, 16)
                Bone.HEAD -> {
                    System.arraycopy(body, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, 0f, rig.headY, 0f)
                    Matrix.rotateM(bone, 0, sin(t * 1.3f) * 3f * (1 - mv), 0f, 1f, 0f)
                    Matrix.rotateM(bone, 0, sin(anim.walk * 2) * 2f * mv, 1f, 0f, 0f)
                }
                Bone.WEAPON -> {
                    System.arraycopy(body, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, rig.shoulder[0] - anim.recoil * 0.16f, rig.shoulder[1] + sin(anim.walk) * 0.025f * mv, rig.shoulder[2])
                    // (A blade is swept across flat, a little raised, and comes to rest pointing forward again.)
                    if (anim.slash >= 0f) Matrix.rotateM(bone, 0, (0.5f - anim.slash) * 190f * (1f - anim.slash * anim.slash * anim.slash), 0f, 1f, 0f)
                    Matrix.rotateM(bone, 0, anim.recoil * 14f + sin(anim.swing * 3.1416f) * 110f + (if (anim.slash >= 0f) sin(anim.slash * 3.1416f) * 12f else 0f), 0f, 0f, 1f)
                }
                Bone.ARM -> {
                    System.arraycopy(body, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, rig.shoulderL[0], rig.shoulderL[1], rig.shoulderL[2])
                    Matrix.rotateM(bone, 0, sin(anim.walk) * 35f * mv + sin(t * 2.4f) * 3f, 0f, 0f, 1f)
                }
                Bone.LEG_L, Bone.LEG_R -> {
                    val side = if (p.bone == Bone.LEG_L) -1f else 1f
                    System.arraycopy(root, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, rig.hindX, rig.hipY, rig.hipZ * side)
                    Matrix.rotateM(bone, 0, sin(anim.walk) * 32f * mv * side, 0f, 0f, 1f)
                }
                // A trot: each front leg swings with the hind leg on the other side.
                Bone.FORE_L, Bone.FORE_R -> {
                    val side = if (p.bone == Bone.FORE_L) -1f else 1f
                    System.arraycopy(root, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, rig.foreX, rig.hipY, rig.hipZ * side)
                    Matrix.rotateM(bone, 0, -sin(anim.walk) * 32f * mv * side + (if (side < 0f) anim.throwL else anim.throwR) * 85f, 0f, 0f, 1f)
                }
                Bone.FLOAT -> {
                    System.arraycopy(root, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, -0.05f, rig.floatY + sin(t * 2.6f) * 0.08f + bob, 0f)
                    Matrix.rotateM(bone, 0, t * 90f, 0f, 1f, 0f)
                }
            }
            prog.mat4("uModel", bone)
            when (pass) {
                Pass.COLOR -> {
                    val c = if (shade != 0L) slotColor(Slot.ACCENT, shade, shade, if (p.emissive) SHADOW_GLOW else shade)
                        else slotColor(p.slot, skin.primary, skin.secondary, skin.accent)
                    prog.v4("uTint", c[0], c[1], c[2], 1f)
                    prog.f("uFlash", anim.flash)
                    prog.f("uEmissive", if (p.emissive) (if (shade != 0L) 1f else 0.75f) else 0f)
                    prog.f("uGloss", if (p.gloss && shade == 0L) 1f else 0f)
                }
                Pass.OUTLINE -> prog.v4("uTint", ink[0], ink[1], ink[2], ink[3])
                Pass.SILHOUETTE, Pass.SHADOW -> Unit
            }
            p.mesh.draw()
        }
        if (pass == Pass.COLOR) prog.f("uGloss", 0f)
    }

    private val col = FloatArray(3)
    private fun slotColor(s: Slot, primary: Long, secondary: Long, accent: Long): FloatArray {
        val c: Long = when (s) {
            Slot.PRIMARY -> primary
            Slot.SECONDARY -> secondary
            Slot.ACCENT -> accent
            Slot.SKIN -> 0xFFFFD3B0
            Slot.SKIN_BROWN -> 0xFFB5764A
            Slot.DARK -> darken(primary, 0.5f)
            Slot.METAL -> 0xFF6A7390
            Slot.WHITE -> 0xFFFFFFFF
            Slot.INK -> 0xFF142435
        }
        val v = c.toInt()
        col[0] = ((v shr 16) and 0xFF) / 255f; col[1] = ((v shr 8) and 0xFF) / 255f; col[2] = (v and 0xFF) / 255f
        return col
    }

    companion object {
        val INK = floatArrayOf(0.078f, 0.141f, 0.208f, 1f)
        /** What glows on a shadow (see [draw]). */
        const val SHADOW_GLOW = 0xFFFF2A3AL
        /** Outline thickness in model units. */
        const val OUTLINE = 0.035f

        fun darken(c: Long, f: Float): Long {
            val v = c.toInt()
            val r = (((v shr 16) and 0xFF) * f).toInt()
            val g = (((v shr 8) and 0xFF) * f).toInt()
            val b = ((v and 0xFF) * f).toInt()
            return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
        }
    }
}
