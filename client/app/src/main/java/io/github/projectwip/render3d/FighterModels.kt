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
/** [VOID] is pure black: Ninja's face, which isn't there. */
/** [GEM], [WING], [CREATURE]: the Entity's jewel; [EYE]: its glowing blue eyes. Fixed colours. */
enum class Slot { PRIMARY, SECONDARY, ACCENT, SKIN, SKIN_BROWN, DARK, METAL, WHITE, INK, VOID, GEM, WING, EYE, CREATURE }

/** Simple rig. Every part is attached to exactly one bone. */
/** [FORE_L] and [FORE_R] are the front legs of a fighter that walks on four. */
/** [MOUTH] rides the head at the model's mouth spot and opens as the fighter talks ([FighterAnim.talk]). */
/** [JEWEL] moves with the body, and is left out when [FighterAnim.jewelGone] (the Entity's jewel, broken). */
/** [WING_L] and [WING_R] beat about [Rig.wing] (the Dragon's wings: [FighterAnim.wingBeat]). */
enum class Bone { BODY, HEAD, WEAPON, ARM, LEG_L, LEG_R, FORE_L, FORE_R, FLOAT, MOUTH, JEWEL, WING_L, WING_R }

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
    /** Where the wings hinge (x, y, z; z for the right wing, mirrored for the left). */
    val wing: FloatArray = floatArrayOf(0f, 1f, 0.3f),
)

/** [mouth]: where the mouth sits on the head (head-local x, y, z), for a model that has one. */
class FighterModel(val parts: List<Part>, val rig: Rig, val mouth: FloatArray? = null)

/** Per-draw animation inputs. */
/** How high the hips are in a model, where a bow bends it ([FighterAnim.bow]). */
const val BOW_PIVOT = 0.55f

class FighterAnim {
    var walk = 0f        // walk cycle phase (radians)
    var moving = 0f      // 0..1 how much the fighter is moving
    var recoil = 0f      // 0..1 weapon kick
    var flash = 0f       // 0..1 hit flash
    var time = 0f
    var jump = 0f        // extra height (menu celebrations)
    var spin = 0f        // extra yaw degrees (menu celebrations)
    var bow = 0f         // degrees bent forward from the hips (bowing to the Entity in its uprising)
    var scale = 1f
    /** 0..1: a front leg flung forward (a fighter on four legs throwing something). */
    var throwL = 0f
    var throwR = 0f
    /** 0..1: the weapon arm swung up and over, for an overhead throw. */
    var swing = 0f
    /** 0..1 through a swing of a blade: the weapon sweeps flat across the front, from one side to the other. Below 0: none. */
    var slash = -1f
    /** 0..1: how far open the mouth is, while talking (cutscenes); the head nods with it. */
    var talk = 0f
    /** 0..1: the eyes lit lightning blue (the Entity has them: in its cutscenes and its ritual). */
    var eyeGlow = 0f
    /** 0..1: how hard the wings beat: a slow idle stir at 0, full flight at 1. */
    var wingBeat = 0f
    /** The Entity's jewel is broken: its parts ([Bone.JEWEL]) are not drawn. */
    var jewelGone = false
}

enum class Pass { SHADOW, COLOR, OUTLINE, SILHOUETTE }

/**
 * Builds and draws the original 3D fighters. One instance per GL context.
 */
class FighterModels {
    private val models = HashMap<FighterId, FighterModel>()
    private val soulModels = HashMap<io.github.projectwip.data.Lore.Soul, FighterModel>()
    private val voidModel by lazy { buildDragon() }
    private val jewelModel by lazy { buildLooseJewel() }

    init {
        // Ninja, the only fighter since build 114 (the owner's decision), on the starter's id. Swish's model is kept with
        // the other removed fighters' in render3d/legacy, as Rocket's and Spark's were before.
        models[FighterId.BYTE] = buildNinja()
        // The Dragon's minions (build 116). (The Juggernaut is the Crusher, drawn far bigger: `MatchRenderer`.)
        models[FighterId.MINION_BOT] = buildCrusher()
        models[FighterId.MINION_BRUTE] = models.getValue(FighterId.MINION_BOT)
        models[FighterId.MINION_LASER] = buildZapper()
        models[FighterId.MINION_HOUND] = buildLurcher()
        models[FighterId.DRAGONLING] = buildDragonling()
        // (Spark was retired in build 102: [buildSpark] is kept, unregistered, like Rocket's model.)
        // The removed fighters' models (render3d/legacy), still under their ids: nobody plays them.
        with(io.github.projectwip.render3d.legacy.LegacyModels) {
            models[FighterId.BRAKK] = buildBrakk()
            models[FighterId.KIRA] = buildKira()
            models[FighterId.KITO] = buildKito()
            models[FighterId.BUDDY] = buildBuddy()
        }
        // Dimension: the forgotten fighters whose souls the Void carries (the user's lore), and the Void itself.
        soulModels[io.github.projectwip.data.Lore.Soul.BYTE] = buildByte()
        soulModels[io.github.projectwip.data.Lore.Soul.ROCKET] = buildRocket()
        soulModels[io.github.projectwip.data.Lore.Soul.SPARK] = buildSpark()
        soulModels[io.github.projectwip.data.Lore.Soul.MIRA] = buildMira()
        soulModels[io.github.projectwip.data.Lore.Soul.VARUN] = buildVarun()
    }

    fun model(id: FighterId) = models.getValue(id)
    fun model(def: FighterDef) = models.getValue(def.id)

    // ------------------------------------------------------------------ construction helpers

    internal class Assembler {
        private val builders = LinkedHashMap<Triple<Bone, Slot, Int>, MeshBuilder>()
        fun add(bone: Bone, slot: Slot, outline: Boolean = true, emissive: Boolean = false, gloss: Boolean = false, block: MeshBuilder.() -> Unit) {
            val key = Triple(bone, slot, (if (outline) 1 else 0) or (if (emissive) 2 else 0) or (if (gloss) 4 else 0))
            builders.getOrPut(key) { MeshBuilder() }.apply { with { block() } }
        }
        private var mouthAt: FloatArray? = null
        /** A mouth at head-local ([x], [y], [z]): a dark slit that opens into an oval as the fighter talks. */
        fun mouth(x: Float, y: Float, z: Float, width: Float = 0.06f) {
            mouthAt = floatArrayOf(x, y, z)
            add(Bone.MOUTH, Slot.INK, outline = false) { ellipsoid(0.022f, 0.032f, width, 8, 12) }
        }
        fun build(rig: Rig) = FighterModel(
            builders.filterValues { !it.isEmpty }.map { (k, b) -> Part(b.build(), k.second, k.first, k.third and 1 != 0, k.third and 2 != 0, k.third and 4 != 0) },
            rig, mouthAt,
        )
    }

    internal fun MeshBuilder.at(x: Float, y: Float, z: Float, block: MeshBuilder.() -> Unit) = with { translate(x, y, z); block() }
    /** Rotate so a Y-axis primitive points along +X. */
    internal fun MeshBuilder.alongX(block: MeshBuilder.() -> Unit) = with { rotate(-90f, 0f, 0f, 1f); block() }
    internal fun MeshBuilder.alongZ(block: MeshBuilder.() -> Unit) = with { rotate(90f, 1f, 0f, 0f); block() }
    internal fun MeshBuilder.octa(rx: Float, ry: Float, rz: Float) = ellipsoid(rx, ry, rz, 2, 4)

    // ------------------------------------------------------------------ Swish — a storm goddess with a giant bow


    // ------------------------------------------------------------------ Spark — the Spark Drop that woke up

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
        a.mouth(0.285f, 0.07f, 0f)
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

    // ------------------------------------------------------------------ Dimension's forgotten fighters and the Void
    // (The souls the Void carries are drawn with their own original models: Spark's and Rocket's were still here, and
    // Mira's and Varun's are restored exactly from the game's history; Byte's is new, by the user's decision.)

    /**
     * Byte, the very first fighter, remade for Dimension (the user found her old model ugly; the other forgotten
     * fighters keep theirs). Still who she was: an engineer in a lab coat with a replacement metal arm and leg and a
     * glowing visor, now a big-eyed girl with a pink bob, goggles pushed up on her head, a sleek blaster, and a little
     * holo cube floating at her shoulder.
     */
    private fun buildByte(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.02f, hipY = 0.36f, hipZ = 0.11f, shoulder = floatArrayOf(0.08f, 0.76f, 0.28f), shoulderL = floatArrayOf(0.02f, 0.8f, -0.28f), floatY = 1.25f)
        // One leg her own, in dark leggings and a chunky sneaker; the other the replacement, plated, with a lit seam.
        a.add(Bone.LEG_L, Slot.DARK) { at(0f, -0.12f, 0f) { capsule(0.068f, 0.16f) } }
        a.add(Bone.LEG_R, Slot.METAL) { at(0f, -0.12f, 0f) { capsule(0.075f, 0.16f) } }
        a.add(Bone.LEG_R, Slot.ACCENT, outline = false, emissive = true) { at(0.065f, -0.12f, 0f) { roundedBox(0.02f, 0.16f, 0.04f, 0.008f) } }
        a.add(Bone.LEG_R, Slot.ACCENT, outline = false, emissive = true) { at(0f, -0.02f, 0f) { torus(0.078f, 0.018f, 12, 5) } }
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.WHITE) { at(0.045f, -0.3f, 0f) { roundedBox(0.26f, 0.11f, 0.15f, 0.05f) } }
            a.add(bone, Slot.SECONDARY) { at(0.045f, -0.345f, 0f) { roundedBox(0.27f, 0.03f, 0.16f, 0.012f) } }
        }
        // A fitted top under an open lab coat that flares to the knee, a belt with a lit buckle, a badge on the coat.
        a.add(Bone.BODY, Slot.WHITE) { at(-0.02f, 0.46f, 0f) { cylinder(0.33f, 0.42f, 18, topRadius = 0.22f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0.03f, 0.72f, 0f) { roundedBox(0.3f, 0.34f, 0.34f, 0.13f) } }
        a.add(Bone.BODY, Slot.WHITE) { at(-0.04f, 0.74f, 0f) { roundedBox(0.3f, 0.32f, 0.4f, 0.12f) } }
        for (z in listOf(0.13f, -0.13f)) a.add(Bone.BODY, Slot.WHITE) { at(0.13f, 0.74f, z) { rotate(if (z > 0f) -12f else 12f, 1f, 0f, 0f); roundedBox(0.06f, 0.3f, 0.1f, 0.03f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0.02f, 0.58f, 0f) { torus(0.19f, 0.035f, 16, 6) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.2f, 0.58f, 0f) { roundedBox(0.03f, 0.06f, 0.09f, 0.012f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.16f, 0.8f, -0.13f) { roundedBox(0.03f, 0.07f, 0.06f, 0.012f) } }
        a.add(Bone.BODY, Slot.WHITE) { at(-0.03f, 0.88f, 0f) { torus(0.15f, 0.045f, 16, 6) } }
        a.add(Bone.BODY, Slot.SKIN) { at(0f, 0.93f, 0f) { capsule(0.06f, 0.06f) } }
        // Head: a round face with big eyes, a pink bob with a fringe, and goggles pushed up, their lenses lit.
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.17f, 0f) { sphere(0.27f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.05f, 0.19f, 0f) { ellipsoid(0.31f, 0.32f, 0.31f, 8, 16, 0f, 0.56f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(0.17f, 0.32f, 0f) { ellipsoid(0.11f, 0.08f, 0.26f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.12f, 0.06f, 0f) { roundedBox(0.28f, 0.3f, 0.6f, 0.13f) } }
        for (z in listOf(0.25f, -0.25f)) a.add(Bone.HEAD, Slot.SECONDARY) { at(0.05f, 0.05f, z) { ellipsoid(0.1f, 0.17f, 0.07f, 8, 10) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            for (z in listOf(0.095f, -0.095f)) at(0.245f, 0.15f, z) { ellipsoid(0.035f, 0.062f, 0.047f, 6, 8) }
        }
        a.mouth(0.255f, 0.045f, 0f, 0.05f)
        a.add(Bone.HEAD, Slot.WHITE, outline = false) { for (z in listOf(0.08f, -0.11f)) at(0.275f, 0.175f, z) { sphere(0.014f, 4, 6) } }
        a.add(Bone.HEAD, Slot.DARK) { at(0.02f, 0.35f, 0f) { rotate(-14f, 0f, 0f, 1f); torus(0.27f, 0.025f, 22, 5) } }
        for (z in listOf(0.11f, -0.11f)) {
            a.add(Bone.HEAD, Slot.METAL) { at(0.21f, 0.43f, z) { rotate(-35f, 0f, 0f, 1f); alongX { cylinder(0.075f, 0.08f, 12) } } }
            a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.25f, 0.46f, z) { rotate(-35f, 0f, 0f, 1f); alongX { cylinder(0.057f, 0.02f, 12) } } }
        }
        // Her blaster: a rounded body in her colour, a metal barrel with a lit ring at the muzzle, and a lit stripe.
        a.add(Bone.WEAPON, Slot.PRIMARY) { at(0.24f, 0.02f, 0f) { roundedBox(0.36f, 0.16f, 0.15f, 0.06f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.5f, 0.03f, 0f) { alongX { cylinder(0.05f, 0.2f, 10) } } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.61f, 0.03f, 0f) { alongX { torus(0.055f, 0.015f, 12, 5) } } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.24f, 0.105f, 0f) { roundedBox(0.24f, 0.015f, 0.05f, 0.006f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.16f, -0.1f, 0f) { rotate(-15f, 0f, 0f, 1f); roundedBox(0.07f, 0.13f, 0.07f, 0.025f) } }
        a.add(Bone.WEAPON, Slot.SKIN) { at(0.04f, 0f, 0f) { sphere(0.065f, 8, 10) } }
        // The free arm is the mechanical one: plated, a lit elbow, metal fingers.
        a.add(Bone.ARM, Slot.WHITE) { at(0f, -0.06f, 0f) { capsule(0.075f, 0.06f) } }
        a.add(Bone.ARM, Slot.METAL) { at(0f, -0.17f, 0f) { capsule(0.06f, 0.1f) } }
        a.add(Bone.ARM, Slot.ACCENT, outline = false, emissive = true) { at(0f, -0.13f, 0f) { sphere(0.045f, 6, 8) } }
        a.add(Bone.ARM, Slot.METAL) { at(0f, -0.28f, 0f) { roundedBox(0.1f, 0.08f, 0.1f, 0.03f) } }
        // A little holo cube floating at her shoulder, turning, lit, in a frame.
        a.add(Bone.FLOAT, Slot.ACCENT, outline = false, emissive = true) { at(-0.4f, 0f, 0.38f) { rotate(45f, 1f, 0f, 1f); box(0.12f, 0.12f, 0.12f) } }
        a.add(Bone.FLOAT, Slot.WHITE, outline = false) { at(-0.4f, 0f, 0.38f) { rotate(45f, 1f, 0f, 1f); box(0.15f, 0.025f, 0.025f); box(0.025f, 0.15f, 0.025f); box(0.025f, 0.025f, 0.15f) } }
        return a.build(rig)
    }

    /** Mira, as she was (restored from the game's history, build 58). */
    private fun buildMira(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.0f, hipY = 0.3f, hipZ = 0.12f, shoulder = floatArrayOf(0.06f, 0.82f, 0.28f), shoulderL = floatArrayOf(0f, 0.86f, -0.28f), floatY = 1.66f)
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.12f, 0f) { capsule(0.075f, 0.14f) } }
            a.add(bone, Slot.SECONDARY) { at(0.04f, -0.28f, 0f) { roundedBox(0.22f, 0.1f, 0.15f, 0.04f) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.52f, 0f) { cylinder(0.44f, 0.76f, 18, topRadius = 0.2f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.16f, 0f) { torus(0.43f, 0.05f, 22, 6) } }
        a.add(Bone.BODY, Slot.ACCENT) { at(0f, 0.6f, 0f) { torus(0.32f, 0.04f, 18, 6) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.88f, 0f) { ellipsoid(0.26f, 0.16f, 0.3f) } }
        // Head + hood
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.16f, 0f) { sphere(0.28f) } }
        a.mouth(0.265f, 0.03f, 0f, 0.05f)
        a.add(Bone.HEAD, Slot.DARK) { at(-0.05f, 0.19f, 0f) { ellipsoid(0.32f, 0.33f, 0.32f, 8, 16, 0f, 0.55f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            at(0.255f, 0.15f, 0.09f) { sphere(0.05f, 6, 8) }
            at(0.255f, 0.15f, -0.09f) { sphere(0.05f, 6, 8) }
        }
        // Floating prism
        a.add(Bone.FLOAT, Slot.SECONDARY, emissive = true) { octa(0.13f, 0.21f, 0.13f) }
        // Prism rifle
        a.add(Bone.WEAPON, Slot.DARK) { at(0.42f, 0f, 0f) { alongX { cylinder(0.05f, 1.0f, 10) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(-0.04f, -0.02f, 0f) { roundedBox(0.26f, 0.13f, 0.09f, 0.04f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.3f, 0.09f, 0f) { alongX { cylinder(0.045f, 0.22f, 8) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY, emissive = true) { at(0.98f, 0f, 0f) { octa(0.18f, 0.09f, 0.09f) } }
        a.add(Bone.WEAPON, Slot.SKIN) { at(0.06f, 0f, 0f) { sphere(0.08f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.1f, 0f) { capsule(0.075f, 0.14f) } }
        a.add(Bone.ARM, Slot.SKIN) { at(0f, -0.26f, 0f) { sphere(0.08f, 8, 10) } }
        return a.build(rig)
    }

    /** Varun, as he was (restored from the game's history, build 65). */
    private fun buildVarun(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.06f, hipY = 0.42f, hipZ = 0.17f, shoulder = floatArrayOf(0.06f, 0.84f, 0.36f), shoulderL = floatArrayOf(0.02f, 0.86f, -0.34f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.PRIMARY) { at(0f, -0.15f, 0f) { capsule(0.12f, 0.16f) } }
            a.add(bone, Slot.SECONDARY) { at(0f, -0.24f, 0f) { cylinder(0.13f, 0.05f, 10) } }
            a.add(bone, Slot.DARK) { at(0.05f, -0.37f, 0f) { roundedBox(0.34f, 0.14f, 0.22f, 0.06f) } }
        }
        // A long turnout coat with two bright bands, and a collar turned up.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.68f, 0f) { roundedBox(0.54f, 0.6f, 0.64f, 0.2f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { for (y in listOf(0.5f, 0.76f)) at(0f, y, 0f) { roundedBox(0.57f, 0.07f, 0.67f, 0.03f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.96f, 0f) { torus(0.2f, 0.07f) } }
        // The air tank on his back, with its valve.
        a.add(Bone.BODY, Slot.METAL) { at(-0.36f, 0.72f, 0f) { capsule(0.13f, 0.26f) } }
        a.add(Bone.BODY, Slot.ACCENT, emissive = true) { at(-0.36f, 1.0f, 0f) { sphere(0.06f, 6, 8) } }
        // Head: a helmet with a wide brim swept down at the back, a crest along the top and a badge on the front.
        a.add(Bone.HEAD, Slot.SKIN_BROWN) { at(0f, 0.2f, 0f) { sphere(0.32f) } }
        a.mouth(0.3f, 0.1f, 0f, 0.1f)
        a.add(Bone.HEAD, Slot.WHITE, outline = false) { for (z in listOf(0.11f, -0.11f)) at(0.31f, 0.23f, z) { sphere(0.04f, 6, 8) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.02f, 0.3f, 0f) { ellipsoid(0.37f, 0.33f, 0.37f, 8, 16, 0f, 0.5f) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.06f, 0.3f, 0f) { rotate(-10f, 0f, 0f, 1f); cylinder(0.5f, 0.04f, 18) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.04f, 0.58f, 0f) { roundedBox(0.56f, 0.12f, 0.08f, 0.03f) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.33f, 0.42f, 0f) { roundedBox(0.05f, 0.14f, 0.14f, 0.03f) } }
        // What is left of the rocket rack on his shoulder: one tube, with a warhead showing in it.
        a.add(Bone.WEAPON, Slot.METAL) { for (z in listOf(0f)) at(0.3f, 0.04f, z) { alongX { cylinder(0.075f, 0.62f, 10) } } }
        a.add(Bone.WEAPON, Slot.DARK) { for (x in listOf(0.12f, 0.46f)) at(x, 0.04f, 0f) { roundedBox(0.08f, 0.2f, 0.22f, 0.03f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.63f, 0.04f, 0f) { sphere(0.065f, 6, 8) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.04f, -0.08f, 0f) { sphere(0.1f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.12f, 0f) { capsule(0.1f, 0.14f) } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.31f, 0f) { sphere(0.1f, 8, 10) } }
        return a.build(rig)
    }

    /**
     * The Void as a figure: a tall robe flaring to the ground with no feet, a deep hood with a dark hollow where a
     * face should be and two pale lit eyes in it, long empty sleeves, and a broken halo turning over its head.
     */
    /**
     * The Entity as a dragon (the owner's design, build 115): rearing up on its hind legs, a deep chest with pale belly
     * plates, a long neck curving up to a horned head with glowing blue eyes and teeth, clawed arms, great membrane
     * wings and a spined tail ending in a spade. Chained at the base of its neck is its jewel ([addJewel]).
     */
    private fun buildDragon(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.72f, hipY = 0.5f, hipZ = 0.3f, shoulder = floatArrayOf(0.3f, 1.12f, 0.42f), shoulderL = floatArrayOf(0.3f, 1.12f, -0.42f), floatY = 1.3f,
            wing = floatArrayOf(-0.12f, 1.3f, 0.32f))
        // The body, rearing: chest, haunches, belly plates.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0.02f, 0.88f, 0f) { rotate(-18f, 0f, 0f, 1f); ellipsoid(0.5f, 0.66f, 0.5f, 12, 18) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(-0.12f, 0.46f, 0f) { ellipsoid(0.5f, 0.4f, 0.48f, 10, 16) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.3f, 0.86f, 0f) { rotate(-18f, 0f, 0f, 1f); ellipsoid(0.24f, 0.55f, 0.34f, 10, 14) } }
        for (k in 0 until 5) {
            a.add(Bone.BODY, Slot.SECONDARY) { at(0.5f + k * 0.035f, 0.56f + k * 0.15f, 0f) { rotate(-18f, 0f, 0f, 1f); roundedBox(0.03f, 0.022f, 0.4f - k * 0.04f, 0.01f) } }
        }
        // Cracks of its light in its chest.
        for ((k, z) in listOf(-0.16f, 0.02f, 0.18f).withIndex()) {
            a.add(Bone.BODY, Slot.EYE, outline = false, emissive = true) { at(0.38f, 0.74f + k * 0.05f, z) { rotate(-18f + (k - 1) * 12f, 0f, 0f, 1f); roundedBox(0.02f, 0.32f, 0.025f, 0.008f) } }
        }
        // The neck, curving up and forward to the head.
        val neck = listOf(floatArrayOf(0.16f, 1.22f, 0.28f), floatArrayOf(0.26f, 1.38f, 0.25f), floatArrayOf(0.34f, 1.53f, 0.22f), floatArrayOf(0.4f, 1.67f, 0.2f))
        for (n in neck) a.add(Bone.BODY, Slot.PRIMARY) { at(n[0], n[1], 0f) { sphere(n[2], 10, 14) } }
        // Spines down the neck and the back.
        val spines = listOf(floatArrayOf(0.22f, 1.78f, 0.16f), floatArrayOf(0.12f, 1.62f, 0.18f), floatArrayOf(0.0f, 1.45f, 0.2f), floatArrayOf(-0.16f, 1.28f, 0.22f),
            floatArrayOf(-0.3f, 1.08f, 0.22f), floatArrayOf(-0.4f, 0.86f, 0.2f), floatArrayOf(-0.5f, 0.64f, 0.17f))
        for (sp in spines) a.add(Bone.BODY, Slot.DARK) { at(sp[0], sp[1], 0f) { rotate(55f, 0f, 0f, 1f); cylinder(0.07f, sp[2], 6, topRadius = 0f) } }
        // The wings: from the shoulders, swept up, out and back; a spar along the leading edge and three membranes.
        for (side in listOf(1f, -1f)) {
            val wb = if (side > 0f) Bone.WING_R else Bone.WING_L
            a.add(wb, Slot.DARK) { at(-0.12f, 1.3f, 0.32f * side) { rotate(22f, 0f, 0f, 1f); rotate(side * 58f, 1f, 0f, 0f); at(0f, 0.7f, 0f) { cylinder(0.055f, 1.4f, 8, topRadius = 0.02f) } } }
            for ((k, len) in listOf(1.1f, 0.85f, 0.6f).withIndex()) {
                a.add(wb, Slot.SECONDARY) {
                    at(-0.12f, 1.3f, 0.32f * side) { rotate(22f, 0f, 0f, 1f); rotate(side * 58f, 1f, 0f, 0f); rotate(18f + k * 22f, 0f, 0f, 1f); at(-0.14f, len * 0.55f, 0f) { ellipsoid(0.4f, len * 0.62f, 0.025f, 8, 14) } }
                }
            }
            // A claw at the wing's bend.
            a.add(wb, Slot.WHITE) { at(-0.12f, 1.3f, 0.32f * side) { rotate(22f, 0f, 0f, 1f); rotate(side * 58f, 1f, 0f, 0f); at(0.02f, 1.42f, 0f) { cylinder(0.035f, 0.14f, 6, topRadius = 0f) } } }
        }
        // The tail: thick at the hips, sweeping back and round on the ground, ending in a spade.
        val tail = listOf(floatArrayOf(-0.5f, 0.42f, 0.05f, 0.26f), floatArrayOf(-0.78f, 0.3f, 0.12f, 0.22f), floatArrayOf(-1.04f, 0.22f, 0.24f, 0.18f),
            floatArrayOf(-1.26f, 0.16f, 0.4f, 0.15f), floatArrayOf(-1.42f, 0.13f, 0.6f, 0.12f), floatArrayOf(-1.52f, 0.11f, 0.8f, 0.09f))
        for (tl in tail) a.add(Bone.BODY, Slot.PRIMARY) { at(tl[0], tl[1], tl[2]) { sphere(tl[3], 8, 12) } }
        a.add(Bone.BODY, Slot.DARK) { at(-1.56f, 0.11f, 1.0f) { rotate(90f, 1f, 0f, 0f); octa(0.14f, 0.2f, 0.03f) } }
        // The head: skull, long snout, heavy brow, lower jaw, teeth, horns swept back, and its blue eyes.
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0.46f, 0.06f, 0f) { ellipsoid(0.27f, 0.22f, 0.24f, 10, 16) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0.84f, -0.01f, 0f) { ellipsoid(0.34f, 0.115f, 0.14f, 10, 14) } }
        // A crest of spikes down the back of its head.
        for (k in 0 until 3) a.add(Bone.HEAD, Slot.DARK) { at(0.3f - k * 0.1f, 0.24f - k * 0.06f, 0f) { rotate(50f, 0f, 0f, 1f); cylinder(0.05f, 0.24f - k * 0.04f, 6, topRadius = 0f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(0.72f, -0.12f, 0f) { ellipsoid(0.24f, 0.06f, 0.14f, 8, 12) } }
        for (z in listOf(0.12f, -0.12f)) {
            a.add(Bone.HEAD, Slot.DARK) { at(0.6f, 0.15f, z) { rotate(-12f, 0f, 0f, 1f); roundedBox(0.2f, 0.055f, 0.09f, 0.02f) } }
            a.add(Bone.HEAD, Slot.EYE, outline = false, emissive = true) { at(0.66f, 0.13f, z * 1.62f) { rotate(if (z > 0f) -25f else 25f, 0f, 1f, 0f); ellipsoid(0.065f, 0.042f, 0.035f, 6, 10) } }
            a.add(Bone.HEAD, Slot.VOID, outline = false) { at(1.15f, 0.04f, z * 0.4f) { sphere(0.02f, 6, 8) } }
            for (k in 0 until 4) a.add(Bone.HEAD, Slot.WHITE) { at(0.62f + k * 0.09f, -0.08f, z * (1f - k * 0.12f)) { rotate(180f, 1f, 0f, 0f); cylinder(0.02f, 0.07f, 6, topRadius = 0f) } }
            // Horns: a long pair swept back, and a short pair under them.
            a.add(Bone.HEAD, Slot.DARK) { at(0.34f, 0.24f, z * 1.2f) { rotate(42f, 0f, 0f, 1f); rotate(if (z > 0f) 26f else -26f, 1f, 0f, 0f); at(0f, 0.38f, 0f) { cylinder(0.085f, 0.76f, 8, topRadius = 0f) } } }
            a.add(Bone.HEAD, Slot.DARK) { at(0.34f, 0.05f, z * 1.6f) { rotate(75f, 0f, 0f, 1f); rotate(if (z > 0f) 30f else -30f, 1f, 0f, 0f); at(0f, 0.12f, 0f) { cylinder(0.04f, 0.24f, 6, topRadius = 0f) } } }
        }
        a.mouth(0.8f, -0.08f, 0f, 0.13f)
        // Its arms: clawed, held up in front of it.
        for (bone in listOf(Bone.WEAPON, Bone.ARM)) {
            a.add(bone, Slot.PRIMARY) { at(0f, -0.16f, 0f) { capsule(0.1f, 0.2f) } }
            a.add(bone, Slot.PRIMARY) { at(0.08f, -0.4f, 0f) { rotate(-25f, 0f, 0f, 1f); capsule(0.085f, 0.18f) } }
            a.add(bone, Slot.DARK) { at(0.16f, -0.56f, 0f) { sphere(0.1f, 8, 10) } }
            for (k in -1..1) a.add(bone, Slot.WHITE) { at(0.24f, -0.64f, k * 0.05f) { rotate(140f, 0f, 0f, 1f); cylinder(0.025f, 0.12f, 6, topRadius = 0f) } }
        }
        // Its legs: heavy thighs, scaled shins, wide clawed feet.
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.PRIMARY) { at(0f, -0.04f, 0f) { ellipsoid(0.24f, 0.3f, 0.21f, 8, 12) } }
            a.add(bone, Slot.DARK) { at(0.06f, -0.3f, 0f) { capsule(0.1f, 0.12f) } }
            a.add(bone, Slot.DARK) { at(0.14f, -0.44f, 0f) { roundedBox(0.32f, 0.1f, 0.22f, 0.04f) } }
            for (k in -1..1) a.add(bone, Slot.WHITE) { at(0.32f, -0.46f, k * 0.07f) { rotate(-90f, 0f, 0f, 1f); cylinder(0.03f, 0.1f, 6, topRadius = 0f) } }
        }
        addJewel(a, 0.6f, 1.02f, 0f, chained = true)
        return a.build(rig)
    }

    /**
     * Its jewel (the owner's design), centred at ([x], [y], [z]) and facing +X: a purple butterfly brooch, a little
     * glowing creature in its gem body powering it. [chained]: hung on a chain from a collar round the dragon's neck.
     */
    private fun addJewel(a: Assembler, x: Float, y: Float, z: Float, chained: Boolean) {
        if (chained) {
            a.add(Bone.JEWEL, Slot.METAL) { at(0.24f, 1.27f, 0f) { rotate(-38f, 0f, 0f, 1f); torus(0.29f, 0.035f, 24, 8) } }
            for (k in 0 until 4) {
                val f = (k + 0.5f) / 4f
                a.add(Bone.JEWEL, Slot.METAL) { at(0.48f + (x - 0.48f) * f, 1.2f + (y + 0.13f - 1.2f) * f, z) { rotate(if (k % 2 == 0) 0f else 90f, 0f, 1f, 0f); rotate(90f, 1f, 0f, 0f); torus(0.035f, 0.012f, 10, 6) } }
            }
        }
        // A butterfly brooch (the owner wanted it in the spirit of a butterfly brooch from a show: this one is ours):
        // a slim gem for its body, with the creature that powers it glowing in the middle, looking out...
        a.add(Bone.JEWEL, Slot.GEM, emissive = true) { at(x - 0.01f, y, z) { octa(0.045f, 0.15f, 0.055f) } }
        a.add(Bone.JEWEL, Slot.CREATURE, outline = false, emissive = true) { at(x + 0.03f, y + 0.01f, z) { sphere(0.042f, 8, 10) } }
        for (dz in listOf(0.016f, -0.016f)) a.add(Bone.JEWEL, Slot.VOID, outline = false) { at(x + 0.07f, y + 0.02f, z + dz) { sphere(0.009f, 5, 6) } }
        for (s in listOf(1f, -1f)) {
            // ...two big upper wings and two smaller lower ones, each with a lighter panel inside it...
            a.add(Bone.JEWEL, Slot.GEM, emissive = true) { at(x, y + 0.07f, z + 0.12f * s) { rotate(-s * 32f, 1f, 0f, 0f); ellipsoid(0.02f, 0.105f, 0.125f, 8, 12) } }
            a.add(Bone.JEWEL, Slot.WING, outline = false) { at(x + 0.012f, y + 0.08f, z + 0.13f * s) { rotate(-s * 32f, 1f, 0f, 0f); ellipsoid(0.02f, 0.062f, 0.075f, 8, 10) } }
            a.add(Bone.JEWEL, Slot.GEM, emissive = true) { at(x, y - 0.085f, z + 0.085f * s) { rotate(s * 36f, 1f, 0f, 0f); ellipsoid(0.02f, 0.075f, 0.085f, 8, 12) } }
            a.add(Bone.JEWEL, Slot.WING, outline = false) { at(x + 0.012f, y - 0.09f, z + 0.09f * s) { rotate(s * 36f, 1f, 0f, 0f); ellipsoid(0.02f, 0.042f, 0.048f, 8, 10) } }
            // ...and two antennae curling up from its head.
            a.add(Bone.JEWEL, Slot.DARK, outline = false) { at(x, y + 0.2f, z + 0.03f * s) { rotate(-s * 22f, 1f, 0f, 0f); cylinder(0.007f, 0.1f, 5) } }
            a.add(Bone.JEWEL, Slot.WING, outline = false) { at(x, y + 0.25f, z + 0.052f * s) { sphere(0.014f, 5, 6) } }
        }
    }

    /** The jewel on its own, broken off and lying on the platform: the dragon rushes to get it back. */
    private fun buildLooseJewel(): FighterModel {
        val a = Assembler()
        addJewel(a, 0f, 0.3f, 0f, chained = false)
        return a.build(Rig(headY = 1f, hipY = 0.4f, hipZ = 0.1f, shoulder = floatArrayOf(0f, 0.7f, 0.2f), shoulderL = floatArrayOf(0f, 0.7f, -0.2f)))
    }

    /** Draws the loose jewel lying at ([x], [z]). */
    fun drawJewel(prog: Program, x: Float, z: Float, facing: Float, anim: FighterAnim, pass: Pass, ink: FloatArray = INK) =
        drawModel(prog, jewelModel, VOID_SKIN, x, z, facing, anim, pass, ink, 0L)

    /** The Entity as it was until build 115: a robed figure (kept, unused). */
    @Suppress("unused")
    private fun buildVoidRobed(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.5f, hipY = 0.4f, hipZ = 0.15f, shoulder = floatArrayOf(0.05f, 1.22f, 0.5f), shoulderL = floatArrayOf(0.05f, 1.22f, -0.5f), floatY = 1.2f)
        // The robe, flaring to a ragged hem of points that trail on the ground.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.75f, 0f) { cylinder(0.74f, 1.5f, 24, topRadius = 0.3f) } }
        for (k in 0 until 14) {
            val t = k * (2 * Math.PI / 14).toFloat()
            a.add(Bone.BODY, Slot.PRIMARY) { at(kotlin.math.cos(t) * 0.7f, 0.08f, sin(t) * 0.7f) { rotate(-t * 57.3f, 0f, 1f, 0f); rotate(-120f, 0f, 0f, 1f); cylinder(0.12f, 0.34f, 6, topRadius = 0f) } }
        }
        // A heavy mantle over the shoulders.
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 1.3f, 0f) { ellipsoid(0.5f, 0.24f, 0.62f, 10, 18) } }
        // Seams of pale light running down the robe, as if it were cracked over something bright.
        for ((k, z) in listOf(-0.42f, -0.15f, 0.15f, 0.42f).withIndex()) {
            a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) {
                at(0.48f - kotlin.math.abs(z) * 0.35f, 0.55f + (k % 2) * 0.12f, z) { rotate(-24f, 0f, 0f, 1f); rotate(z * 25f, 1f, 0f, 0f); roundedBox(0.02f, 0.6f, 0.03f, 0.01f) }
            }
        }
        // The hood, its peak drooping back, and the dark hollow under it with two lit eyes.
        a.add(Bone.HEAD, Slot.SECONDARY) { at(0f, 0.15f, 0f) { ellipsoid(0.46f, 0.52f, 0.46f, 10, 18) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.22f, 0.52f, 0f) { rotate(55f, 0f, 0f, 1f); cylinder(0.2f, 0.5f, 10, topRadius = 0.01f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) { at(0.2f, 0.1f, 0f) { ellipsoid(0.3f, 0.36f, 0.32f, 10, 16) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) {
            for (z in listOf(0.12f, -0.12f)) at(0.47f, 0.14f, z) { rotate(if (z > 0f) -12f else 12f, 1f, 0f, 0f); ellipsoid(0.03f, 0.055f, 0.1f, 6, 10) }
        }
        // A broken halo standing behind the head: three arcs with gaps between them.
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) {
            at(-0.42f, 0.2f, 0f) { rotate(90f, 0f, 0f, 1f); for (k in 0 until 3) ring(0.56f, 0.62f, 16, k * 120f + 10f, k * 120f + 95f) }
        }
        // Long empty sleeves hanging from the mantle.
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.05f, -0.3f, 0f) { rotate(-15f, 0f, 0f, 1f); cylinder(0.12f, 0.7f, 10, topRadius = 0.2f) } }
        a.add(Bone.WEAPON, Slot.INK, outline = false) { at(0.14f, -0.66f, 0f) { sphere(0.13f, 6, 8) } }
        a.add(Bone.ARM, Slot.SECONDARY) { at(0f, -0.35f, 0f) { cylinder(0.12f, 0.7f, 10, topRadius = 0.2f) } }
        a.add(Bone.ARM, Slot.INK, outline = false) { at(0f, -0.72f, 0f) { sphere(0.13f, 6, 8) } }
        // Shards orbiting it at its waist.
        a.add(Bone.FLOAT, Slot.ACCENT, outline = false, emissive = true) {
            for (k in 0 until 4) { val t = k * 1.5708f; at(kotlin.math.cos(t) * 0.95f, (k % 2) * 0.25f, sin(t) * 0.95f) { octa(0.06f, 0.16f, 0.06f) } }
        }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Brakk — scrapyard hound


    // ------------------------------------------------------------------ Kira — hedge witch


    // ------------------------------------------------------------------ Kito — a ninja with a sword, and a hammer for his super

    // ------------------------------------------------------------------ the Dragon's minions (build 116)

    /** A melee robot: a heavy box of a body, one red visor eye, cracks of glitching light, and a piston fist. */
    private fun buildCrusher(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.02f, hipY = 0.4f, hipZ = 0.17f, shoulder = floatArrayOf(0.06f, 0.78f, 0.34f), shoulderL = floatArrayOf(0.06f, 0.78f, -0.34f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.14f, 0f) { roundedBox(0.16f, 0.3f, 0.16f, 0.05f) } }
            a.add(bone, Slot.PRIMARY) { at(0.05f, -0.36f, 0f) { roundedBox(0.3f, 0.1f, 0.22f, 0.04f) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.68f, 0f) { roundedBox(0.52f, 0.5f, 0.6f, 0.1f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0.27f, 0.66f, 0f) { roundedBox(0.04f, 0.3f, 0.4f, 0.02f) } }
        a.add(Bone.BODY, Slot.METAL) { at(0f, 0.95f, 0f) { roundedBox(0.4f, 0.08f, 0.5f, 0.03f) } }
        for ((k, z) in listOf(-0.16f, 0.12f).withIndex()) {
            a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.29f, 0.62f + k * 0.08f, z) { rotate(20f - k * 40f, 1f, 0f, 0f); roundedBox(0.012f, 0.22f, 0.025f, 0.006f) } }
        }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0.02f, 0.14f, 0f) { roundedBox(0.36f, 0.28f, 0.36f, 0.08f) } }
        a.add(Bone.HEAD, Slot.DARK) { at(0.2f, 0.16f, 0f) { roundedBox(0.04f, 0.1f, 0.28f, 0.02f) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.22f, 0.16f, 0f) { roundedBox(0.03f, 0.05f, 0.2f, 0.015f) } }
        a.add(Bone.HEAD, Slot.METAL) { at(-0.06f, 0.36f, 0.1f) { cylinder(0.015f, 0.16f, 6) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(-0.06f, 0.45f, 0.1f) { sphere(0.03f, 6, 8) } }
        // The fist: a heavy piston on the striking arm; a plain claw on the other.
        a.add(Bone.WEAPON, Slot.DARK) { at(0f, -0.14f, 0f) { capsule(0.08f, 0.16f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.06f, -0.36f, 0f) { roundedBox(0.26f, 0.22f, 0.24f, 0.06f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.19f, -0.36f, 0f) { roundedBox(0.02f, 0.14f, 0.16f, 0.01f) } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.14f, 0f) { capsule(0.07f, 0.16f) } }
        a.add(Bone.ARM, Slot.METAL) { at(0.04f, -0.33f, 0f) { roundedBox(0.16f, 0.14f, 0.16f, 0.04f) } }
        return a.build(rig)
    }

    /** A laser bot: slim, floating on a hover pad, one great lens for an eye, and a laser cannon for an arm. */
    private fun buildZapper(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.06f, hipY = 0.38f, hipZ = 0.12f, shoulder = floatArrayOf(0.05f, 0.8f, 0.3f), shoulderL = floatArrayOf(0.05f, 0.8f, -0.3f))
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.16f, 0f) { cylinder(0.3f, 0.1f, 16, topRadius = 0.22f) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0f, 0.1f, 0f) { torus(0.24f, 0.025f, 18, 6) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.56f, 0f) { cylinder(0.2f, 0.62f, 14, topRadius = 0.26f) } }
        a.add(Bone.BODY, Slot.METAL) { at(0f, 0.88f, 0f) { torus(0.24f, 0.04f, 16, 6) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0f, 0.14f, 0f) { sphere(0.24f, 12, 16) } }
        a.add(Bone.HEAD, Slot.DARK) { at(0.18f, 0.14f, 0f) { rotate(-90f, 0f, 0f, 1f); cylinder(0.13f, 0.08f, 16) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.23f, 0.14f, 0f) { sphere(0.08f, 8, 10) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0f, -0.12f, 0f) { capsule(0.06f, 0.14f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.2f, -0.28f, 0f) { alongX { cylinder(0.07f, 0.42f, 10) } } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.42f, -0.28f, 0f) { alongX { cylinder(0.045f, 0.05f, 10) } } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.14f, 0f) { capsule(0.06f, 0.16f) } }
        return a.build(rig)
    }

    /** A hound: a robot dog on four legs, a long jaw, two glowing eyes, spikes down its back. */
    private fun buildLurcher(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 0.9f, hipY = 0.4f, hipZ = 0.17f, shoulder = floatArrayOf(0.42f, 0.66f, 0f), shoulderL = floatArrayOf(-0.46f, 0.62f, 0f),
            foreX = 0.28f, hindX = -0.3f)
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R, Bone.FORE_L, Bone.FORE_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.14f, 0f) { capsule(0.06f, 0.18f) } }
            a.add(bone, Slot.METAL) { at(0.04f, -0.34f, 0f) { roundedBox(0.16f, 0.07f, 0.13f, 0.03f) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(-0.02f, 0.56f, 0f) { roundedBox(0.8f, 0.3f, 0.34f, 0.13f) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.02f, 0.5f, 0.175f) { roundedBox(0.5f, 0.02f, 0.01f, 0.005f) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.02f, 0.5f, -0.175f) { roundedBox(0.5f, 0.02f, 0.01f, 0.005f) } }
        for (k in 0 until 4) a.add(Bone.BODY, Slot.DARK) { at(-0.3f + k * 0.18f, 0.74f, 0f) { rotate(30f, 0f, 0f, 1f); cylinder(0.04f, 0.14f, 6, topRadius = 0f) } }
        // Its head on the weapon bone (it snaps forward as it bites), and its tail on the arm bone.
        a.add(Bone.WEAPON, Slot.PRIMARY) { at(0.1f, 0.06f, 0f) { roundedBox(0.28f, 0.24f, 0.26f, 0.09f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.32f, 0f, 0f) { roundedBox(0.26f, 0.12f, 0.18f, 0.04f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.3f, -0.1f, 0f) { roundedBox(0.22f, 0.05f, 0.16f, 0.02f) } }
        for (z in listOf(0.08f, -0.08f)) {
            a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.22f, 0.12f, z) { sphere(0.035f, 6, 8) } }
            a.add(Bone.WEAPON, Slot.DARK) { at(0.02f, 0.26f, z) { cylinder(0.05f, 0.16f, 6, topRadius = 0f) } }
        }
        a.add(Bone.ARM, Slot.DARK) { at(-0.12f, 0.02f, 0f) { rotate(55f, 0f, 0f, 1f); cylinder(0.035f, 0.32f, 6, topRadius = 0.01f) } }
        return a.build(rig)
    }

    /** A Dragonling: a small copy of the Dragon, upright, with its blue eyes, horns, little wings and a tail. */
    private fun buildDragonling(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 0.86f, hipY = 0.32f, hipZ = 0.15f, shoulder = floatArrayOf(0.14f, 0.6f, 0.2f), shoulderL = floatArrayOf(0.14f, 0.6f, -0.2f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.PRIMARY) { at(0f, -0.04f, 0f) { ellipsoid(0.13f, 0.16f, 0.12f, 8, 10) } }
            a.add(bone, Slot.DARK) { at(0.08f, -0.26f, 0f) { roundedBox(0.18f, 0.07f, 0.12f, 0.03f) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.5f, 0f) { rotate(-15f, 0f, 0f, 1f); ellipsoid(0.25f, 0.32f, 0.24f, 10, 14) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.14f, 0.5f, 0f) { rotate(-15f, 0f, 0f, 1f); ellipsoid(0.12f, 0.26f, 0.16f, 8, 12) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false, emissive = true) { at(0.24f, 0.5f, 0f) { roundedBox(0.015f, 0.18f, 0.02f, 0.006f) } }
        for (s in listOf(1f, -1f)) {
            a.add(Bone.BODY, Slot.DARK) { at(-0.08f, 0.72f, 0.14f * s) { rotate(20f, 0f, 0f, 1f); rotate(s * 55f, 1f, 0f, 0f); at(0f, 0.25f, 0f) { cylinder(0.025f, 0.5f, 6, topRadius = 0.01f) } } }
            a.add(Bone.BODY, Slot.SECONDARY) { at(-0.08f, 0.72f, 0.14f * s) { rotate(20f, 0f, 0f, 1f); rotate(s * 55f, 1f, 0f, 0f); rotate(30f, 0f, 0f, 1f); at(-0.06f, 0.22f, 0f) { ellipsoid(0.16f, 0.24f, 0.015f, 6, 10) } } }
        }
        for ((k, r) in listOf(0.12f, 0.1f, 0.08f, 0.06f).withIndex()) a.add(Bone.BODY, Slot.PRIMARY) { at(-0.26f - k * 0.13f, 0.24f - k * 0.04f, k * 0.05f) { sphere(r, 6, 8) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0.16f, 0.02f, 0f) { ellipsoid(0.15f, 0.12f, 0.13f, 8, 12) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(0.32f, -0.01f, 0f) { ellipsoid(0.14f, 0.07f, 0.08f, 8, 10) } }
        for (z in listOf(0.07f, -0.07f)) {
            a.add(Bone.HEAD, Slot.EYE, outline = false, emissive = true) { at(0.25f, 0.06f, z * 1.4f) { ellipsoid(0.035f, 0.025f, 0.02f, 6, 8) } }
            a.add(Bone.HEAD, Slot.DARK) { at(0.08f, 0.13f, z * 1.2f) { rotate(45f, 0f, 0f, 1f); at(0f, 0.11f, 0f) { cylinder(0.035f, 0.22f, 6, topRadius = 0f) } } }
        }
        for (bone in listOf(Bone.WEAPON, Bone.ARM)) {
            a.add(bone, Slot.PRIMARY) { at(0.02f, -0.1f, 0f) { capsule(0.05f, 0.12f) } }
            a.add(bone, Slot.WHITE) { at(0.08f, -0.24f, 0f) { rotate(140f, 0f, 0f, 1f); cylinder(0.02f, 0.08f, 6, topRadius = 0f) } }
        }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Ninja — hooded, faceless, with a gun

    /**
     * Ninja (the owner's design, build 114): a hooded ninja with no face, only a black void inside the hood, and a
     * pistol. Two eyes of ink sit far back in the void, all but invisible: they only show when the Entity holds him
     * (lit lightning blue, like everyone's).
     */
    private fun buildNinja(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.0f, hipY = 0.4f, hipZ = 0.13f, shoulder = floatArrayOf(0.1f, 0.72f, 0.27f), shoulderL = floatArrayOf(0.02f, 0.8f, -0.27f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.15f, 0f) { capsule(0.085f, 0.18f) } }
            a.add(bone, Slot.DARK) { at(0.04f, -0.36f, 0f) { roundedBox(0.26f, 0.1f, 0.16f, 0.05f) } }
            // Wrapped shins.
            a.add(bone, Slot.SECONDARY) { at(0f, -0.25f, 0f) { roundedBox(0.19f, 0.07f, 0.19f, 0.03f) } }
        }
        // A long cloak over a slim body: the coat, its tails falling behind, a sash, and a strap across the chest.
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.66f, 0f) { roundedBox(0.42f, 0.52f, 0.48f, 0.18f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(-0.12f, 0.36f, 0f) { rotate(-10f, 0f, 0f, 1f); roundedBox(0.3f, 0.3f, 0.44f, 0.08f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.5f, 0f) { roundedBox(0.45f, 0.08f, 0.51f, 0.04f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.2f, 0.72f, 0f) { rotate(35f, 1f, 0f, 0f); roundedBox(0.06f, 0.5f, 0.07f, 0.02f) } }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.92f, 0f) { torus(0.17f, 0.075f) } }
        // The hood: a deep cowl over the head, peaked at the back, its opening ringed.
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.03f, 0.21f, 0f) { ellipsoid(0.36f, 0.36f, 0.35f, 10, 18) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.26f, 0.42f, 0f) { rotate(55f, 0f, 0f, 1f); cylinder(0.12f, 0.26f, 10, topRadius = 0f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(0.25f, 0.19f, 0f) { rotate(90f, 0f, 0f, 1f); torus(0.205f, 0.04f) } }
        // No face: a black void filling the hood's opening.
        a.add(Bone.HEAD, Slot.VOID, outline = false) { at(0.24f, 0.19f, 0f) { ellipsoid(0.12f, 0.2f, 0.2f, 10, 16) } }
        for (z in listOf(0.075f, -0.075f)) {
            a.add(Bone.HEAD, Slot.INK, outline = false) { at(0.33f, 0.21f, z) { ellipsoid(0.02f, 0.03f, 0.035f, 6, 8) } }
        }
        // His gun: a grip, a long slide with a line of light along it, the barrel and its dark mouth.
        a.add(Bone.WEAPON, Slot.DARK) { at(0.02f, -0.08f, 0f) { rotate(-14f, 0f, 0f, 1f); roundedBox(0.08f, 0.17f, 0.075f, 0.02f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.16f, 0.03f, 0f) { roundedBox(0.34f, 0.095f, 0.085f, 0.02f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { at(0.17f, 0.082f, 0f) { roundedBox(0.26f, 0.014f, 0.05f, 0.005f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.34f, 0.025f, 0f) { alongX { cylinder(0.032f, 0.06f, 10) } } }
        a.add(Bone.WEAPON, Slot.VOID, outline = false) { at(0.372f, 0.025f, 0f) { alongX { cylinder(0.018f, 0.01f, 8) } } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.06f, -0.03f, 0f) { torus(0.045f, 0.012f) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.11f, 0f) { capsule(0.085f, 0.14f) } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.28f, 0f) { sphere(0.08f, 8, 10) } }
        return a.build(rig)
    }


    // ------------------------------------------------------------------ Buddy — a rogue AI with a hologram for a face


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
    fun draw(prog: Program, def: FighterDef, skinIndex: Int, x: Float, z: Float, facing: Float, anim: FighterAnim, pass: Pass, ink: FloatArray = INK, shade: Long = 0L) =
        drawModel(prog, model(def), def.skins[skinIndex.coerceIn(0, def.skins.lastIndex)], x, z, facing, anim, pass, ink, shade)

    /** Dimension: one of the forgotten fighters the Void carries, or (with null) the Void itself. */
    fun drawSoul(prog: Program, soul: io.github.projectwip.data.Lore.Soul?, x: Float, z: Float, facing: Float, anim: FighterAnim, pass: Pass, ink: FloatArray = INK) {
        if (soul == null) drawModel(prog, voidModel, VOID_SKIN, x, z, facing, anim, pass, ink, 0L)
        else drawModel(prog, soulModels.getValue(soul), io.github.projectwip.data.Skin(soul.title, soul.primary, soul.secondary, soul.accent, 0), x, z, facing, anim, pass, ink, 0L)
    }

    private fun drawModel(prog: Program, model: FighterModel, skin: io.github.projectwip.data.Skin, x: Float, z: Float, facing: Float, anim: FighterAnim, pass: Pass, ink: FloatArray, shade: Long) {
        val rig = model.rig
        val t = anim.time
        val mv = anim.moving

        Matrix.setIdentityM(root, 0)
        Matrix.translateM(root, 0, x, anim.jump, z)
        Matrix.rotateM(root, 0, -Math.toDegrees(facing.toDouble()).toFloat() + anim.spin, 0f, 1f, 0f)
        Matrix.scaleM(root, 0, anim.scale, anim.scale, anim.scale)
        // A bow: bent forward from the hips.
        if (anim.bow != 0f) { Matrix.translateM(root, 0, 0f, BOW_PIVOT, 0f); Matrix.rotateM(root, 0, -anim.bow, 0f, 0f, 1f); Matrix.translateM(root, 0, 0f, -BOW_PIVOT, 0f) }

        val bob = abs(sin(anim.walk)) * 0.07f * mv + sin(t * 2.4f) * 0.012f * (1 - mv)
        val breathe = 1f + sin(t * 2.4f) * 0.018f * (1 - mv)
        System.arraycopy(root, 0, body, 0, 16)
        Matrix.translateM(body, 0, 0f, bob, 0f)
        Matrix.rotateM(body, 0, -8f * mv + maxOf(anim.throwL, anim.throwR) * 9f, 0f, 0f, 1f) // lean forward when running; rear up to throw
        Matrix.scaleM(body, 0, 1f, breathe, 1f)

        for (p in model.parts) {
            if (p.bone == Bone.JEWEL && anim.jewelGone) continue
            if (pass == Pass.OUTLINE && !p.outline) continue
            if (pass == Pass.SILHOUETTE && p.bone == Bone.FLOAT) continue
            when (p.bone) {
                Bone.BODY, Bone.JEWEL -> System.arraycopy(body, 0, bone, 0, 16)
                // The Dragon's wings beat about their hinges: a slow stir standing, full strokes in flight.
                Bone.WING_L, Bone.WING_R -> {
                    val side = if (p.bone == Bone.WING_R) 1f else -1f
                    val w = rig.wing
                    val beat = sin(t * (1.8f + 3.7f * anim.wingBeat)) * (8f + 34f * anim.wingBeat)
                    System.arraycopy(body, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, w[0], w[1], w[2] * side)
                    Matrix.rotateM(bone, 0, beat * side, 1f, 0f, 0f)
                    Matrix.translateM(bone, 0, -w[0], -w[1], -w[2] * side)
                }
                Bone.HEAD, Bone.MOUTH -> {
                    System.arraycopy(body, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, 0f, rig.headY, 0f)
                    Matrix.rotateM(bone, 0, sin(t * 1.3f) * 3f * (1 - mv), 0f, 1f, 0f)
                    Matrix.rotateM(bone, 0, sin(anim.walk * 2) * 2f * mv, 1f, 0f, 0f)
                    // (Talking: a little nod and tilt with the words.)
                    if (anim.talk > 0f) Matrix.rotateM(bone, 0, sin(t * 9f) * 4f * anim.talk, 0f, 0f, 1f)
                    if (p.bone == Bone.MOUTH) {
                        val m = model.mouth ?: continue
                        Matrix.translateM(bone, 0, m[0], m[1], m[2])
                        Matrix.scaleM(bone, 0, 1f, 0.22f + 1.5f * anim.talk, 1f + 0.2f * anim.talk)
                    }
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
                    // Its hold on them shows in their eyes: lit lightning blue, flickering (the eyes are the face's ink).
                    // The eyes: the face's ink, and the little white highlights sitting on it (white, no outline, on the
                    // head). Both are lit, so the whole eye is one solid electric blue: lit ink alone left white dots
                    // ringed in blue, which the user saw as neon round white eyes.
                    val highlight = p.slot == Slot.WHITE && !p.outline && p.bone == Bone.HEAD
                    val eyes = anim.eyeGlow > 0f && ((p.slot == Slot.INK && p.bone != Bone.MOUTH) || highlight)
                    if (eyes) {
                        // Electric blue, deep and saturated, with a slow steady pulse (a fast crackle read as glitching).
                        val g = anim.eyeGlow
                        val pulse = 0.5f + 0.5f * sin(t * 3.2f)
                        val er = if (highlight) 0.45f else 0.06f + 0.06f * pulse
                        val eg = if (highlight) 0.8f else 0.42f + 0.1f * pulse
                        prog.v4("uTint", c[0] + (er - c[0]) * g, c[1] + (eg - c[1]) * g, c[2] + (1f - c[2]) * g, 1f)
                    } else prog.v4("uTint", c[0], c[1], c[2], 1f)
                    prog.f("uFlash", anim.flash)
                    prog.f("uEmissive", if (eyes) anim.eyeGlow * 0.8f else if (p.emissive) (if (shade != 0L) 1f else 0.75f) else 0f)
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
            Slot.VOID -> 0xFF000000
            Slot.GEM -> 0xFF6A12D8
            Slot.WING -> 0xFFC85CFF
            Slot.EYE -> 0xFF3FB4FF
            Slot.CREATURE -> 0xFFCFF3FF
        }
        val v = c.toInt()
        col[0] = ((v shr 16) and 0xFF) / 255f; col[1] = ((v shr 8) and 0xFF) / 255f; col[2] = (v and 0xFF) / 255f
        return col
    }

    companion object {
        val INK = floatArrayOf(0.078f, 0.141f, 0.208f, 1f)
        /** The Void's own colours: a robe of near-black violet, its eyes and halo pale lilac. */
        val VOID_SKIN = io.github.projectwip.data.Skin("Void", 0xFF1E0F3A, 0xFF2C1656, 0xFFC9B0FF, 0)
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
