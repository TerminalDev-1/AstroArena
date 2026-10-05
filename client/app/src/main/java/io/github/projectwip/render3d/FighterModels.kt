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
enum class Slot { PRIMARY, SECONDARY, ACCENT, SKIN, DARK, METAL, WHITE, INK }

/** Simple rig. Every part is attached to exactly one bone. */
enum class Bone { BODY, HEAD, WEAPON, ARM, LEG_L, LEG_R, FLOAT }

class Part(val mesh: Mesh, val slot: Slot, val bone: Bone, val outline: Boolean, val emissive: Boolean)

/** Rig layout (bone pivots in model space; model faces +X, up +Y). */
class Rig(
    val headY: Float,
    val hipY: Float,
    val hipZ: Float,
    val shoulder: FloatArray,   // weapon shoulder (x,y,z), relative to body
    val shoulderL: FloatArray,  // free arm shoulder
    val floatY: Float = 0f,
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
}

enum class Pass { SHADOW, COLOR, OUTLINE, SILHOUETTE }

/**
 * Builds and draws the original 3D fighters. One instance per GL context.
 */
class FighterModels {
    private val models = HashMap<FighterId, FighterModel>()
    private val bossModels = HashMap<io.github.projectwip.data.BossKind, FighterModel>()

    init {
        models[FighterId.JUNO] = buildJuno()
        models[FighterId.BRAKK] = buildBrakk()
        models[FighterId.MIRA] = buildMira()
        models[FighterId.KITO] = buildKito()
        models[FighterId.VARUN] = buildVarun()
        bossModels[io.github.projectwip.data.BossKind.BARRAGE] = buildHailstorm()
        bossModels[io.github.projectwip.data.BossKind.SWEEPER] = buildLighthouse()
        bossModels[io.github.projectwip.data.BossKind.STAMPEDE] = buildRamrod()
    }

    fun model(id: FighterId) = models.getValue(id)
    /** A fighter's model, or a boss's own. */
    fun model(def: FighterDef) = def.boss?.let { bossModels.getValue(it) } ?: models.getValue(def.id)

    // ------------------------------------------------------------------ construction helpers

    private class Assembler {
        private val builders = LinkedHashMap<Triple<Bone, Slot, Int>, MeshBuilder>()
        fun add(bone: Bone, slot: Slot, outline: Boolean = true, emissive: Boolean = false, block: MeshBuilder.() -> Unit) {
            val key = Triple(bone, slot, (if (outline) 1 else 0) or (if (emissive) 2 else 0))
            builders.getOrPut(key) { MeshBuilder() }.apply { with { block() } }
        }
        fun build(rig: Rig) = FighterModel(
            builders.filterValues { !it.isEmpty }.map { (k, b) -> Part(b.build(), k.second, k.first, k.third and 1 != 0, k.third and 2 != 0) },
            rig,
        )
    }

    private fun MeshBuilder.at(x: Float, y: Float, z: Float, block: MeshBuilder.() -> Unit) = with { translate(x, y, z); block() }
    /** Rotate so a Y-axis primitive points along +X. */
    private fun MeshBuilder.alongX(block: MeshBuilder.() -> Unit) = with { rotate(-90f, 0f, 0f, 1f); block() }
    private fun MeshBuilder.alongZ(block: MeshBuilder.() -> Unit) = with { rotate(90f, 1f, 0f, 0f); block() }
    private fun MeshBuilder.octa(rx: Float, ry: Float, rz: Float) = ellipsoid(rx, ry, rz, 2, 4)

    // ------------------------------------------------------------------ Juno — courier with coil blaster

    private fun buildJuno(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.02f, hipY = 0.42f, hipZ = 0.15f, shoulder = floatArrayOf(0.08f, 0.74f, 0.3f), shoulderL = floatArrayOf(0.02f, 0.82f, -0.3f))
        for ((bone, z) in listOf(Bone.LEG_L to 0f, Bone.LEG_R to 0f)) {
            a.add(bone, Slot.DARK) { at(0f, -0.16f, z) { capsule(0.11f, 0.16f) } }
            a.add(bone, Slot.SECONDARY) { at(0.05f, -0.37f, z) { roundedBox(0.3f, 0.13f, 0.19f, 0.06f) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.68f, 0f) { roundedBox(0.5f, 0.52f, 0.58f, 0.21f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0f, 0.6f, 0f) { roundedBox(0.53f, 0.1f, 0.61f, 0.05f) } }
        a.add(Bone.BODY, Slot.METAL) { at(-0.3f, 0.74f, 0f) { roundedBox(0.22f, 0.36f, 0.42f, 0.09f) } }
        a.add(Bone.BODY, Slot.ACCENT) { at(-0.42f, 0.86f, 0.12f) { cylinder(0.035f, 0.12f) } }
        a.add(Bone.BODY, Slot.ACCENT) { at(0f, 0.93f, 0f) { torus(0.19f, 0.06f) } }
        // Head
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.2f, 0f) { sphere(0.33f) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.04f, 0.25f, 0f) { ellipsoid(0.36f, 0.34f, 0.36f, 8, 16, 0f, 0.4f) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(0.24f, 0.2f, 0f) { roundedBox(0.16f, 0.15f, 0.5f, 0.06f) } }
        a.add(Bone.HEAD, Slot.WHITE, outline = false) {
            at(0.325f, 0.22f, 0.1f) { sphere(0.045f, 6, 8) }
            at(0.325f, 0.22f, -0.1f) { sphere(0.035f, 6, 8) }
        }
        a.add(Bone.HEAD, Slot.INK, outline = false) { at(-0.12f, 0.62f, 0.12f) { rotate(20f, 1f, 0f, 0f); cylinder(0.022f, 0.34f, 6) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(-0.12f, 0.8f, 0.18f) { sphere(0.08f, 8, 10) } }
        // Coil blaster (weapon bone sits at the shoulder)
        a.add(Bone.WEAPON, Slot.METAL) { at(0.32f, 0f, 0f) { alongX { cylinder(0.085f, 0.56f, 12) } } }
        a.add(Bone.WEAPON, Slot.ACCENT, emissive = true) {
            for (x in listOf(0.2f, 0.32f, 0.44f)) at(x, 0f, 0f) { alongX { torus(0.1f, 0.035f, 14, 6) } }
        }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.62f, 0f, 0f) { alongX { cylinder(0.11f, 0.09f, 12) } } }
        a.add(Bone.WEAPON, Slot.SKIN) { at(0.04f, 0f, 0f) { sphere(0.1f, 8, 10) } }
        // Free arm
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.12f, 0f) { capsule(0.09f, 0.14f) } }
        a.add(Bone.ARM, Slot.SKIN) { at(0f, -0.3f, 0f) { sphere(0.095f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Brakk — scrapyard bruiser

    private fun buildBrakk(): FighterModel {
        val a = Assembler()
        val rig = Rig(headY = 1.12f, hipY = 0.38f, hipZ = 0.24f, shoulder = floatArrayOf(0.02f, 0.86f, 0.5f), shoulderL = floatArrayOf(0f, 0.92f, -0.52f))
        for (bone in listOf(Bone.LEG_L, Bone.LEG_R)) {
            a.add(bone, Slot.DARK) { at(0f, -0.15f, 0f) { roundedBox(0.3f, 0.32f, 0.27f, 0.09f) } }
            a.add(bone, Slot.METAL) { at(0.06f, -0.32f, 0f) { roundedBox(0.4f, 0.14f, 0.32f, 0.06f) } }
        }
        a.add(Bone.BODY, Slot.PRIMARY) { at(0f, 0.8f, 0f) { roundedBox(0.82f, 0.66f, 0.88f, 0.24f) } }
        a.add(Bone.BODY, Slot.SECONDARY) { at(0.38f, 0.8f, 0f) { roundedBox(0.14f, 0.42f, 0.58f, 0.06f) } }
        a.add(Bone.BODY, Slot.DARK) { at(0f, 0.52f, 0f) { roundedBox(0.84f, 0.11f, 0.9f, 0.05f) } }
        a.add(Bone.BODY, Slot.ACCENT, outline = false) {
            for (y in listOf(0.98f, 0.62f)) for (z in listOf(0.31f, -0.31f)) at(0.43f, y, z) { sphere(0.045f, 6, 8) }
        }
        for (z in listOf(0.2f, -0.2f)) {
            a.add(Bone.BODY, Slot.METAL) { at(-0.36f, 1.22f, z) { cylinder(0.08f, 0.5f, 10) } }
            a.add(Bone.BODY, Slot.DARK) { at(-0.36f, 1.47f, z) { torus(0.08f, 0.03f, 10, 5) } }
        }
        // Head with single glowing eye
        a.add(Bone.HEAD, Slot.DARK) { at(0.02f, 0.1f, 0f) { roundedBox(0.44f, 0.34f, 0.46f, 0.15f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) { at(0.21f, 0.12f, 0f) { roundedBox(0.08f, 0.12f, 0.34f, 0.04f) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.25f, 0.12f, 0.02f) { sphere(0.075f, 8, 10) } }
        // Scrap cannon
        a.add(Bone.WEAPON, Slot.METAL) { at(0.3f, 0f, 0f) { alongX { cylinder(0.17f, 0.72f, 14) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.66f, 0f, 0f) { alongX { torus(0.17f, 0.065f, 16, 6) } } }
        a.add(Bone.WEAPON, Slot.DARK) { at(-0.06f, 0f, 0f) { sphere(0.17f, 8, 12) } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.18f, 0.18f, 0f) { alongZ { cylinder(0.12f, 0.22f, 12) } } }
        // Big fist arm
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.12f, 0f) { capsule(0.13f, 0.14f) } }
        a.add(Bone.ARM, Slot.METAL) { at(0.02f, -0.34f, 0f) { roundedBox(0.26f, 0.24f, 0.24f, 0.09f) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Mira — prism sniper

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
        a.add(Bone.HEAD, Slot.DARK) { at(-0.05f, 0.19f, 0f) { ellipsoid(0.32f, 0.33f, 0.32f, 8, 16, 0f, 0.55f) } }
        a.add(Bone.HEAD, Slot.INK, outline = false) {
            at(0.255f, 0.15f, 0.09f) { sphere(0.05f, 6, 8) }
            at(0.255f, 0.15f, -0.09f) { sphere(0.05f, 6, 8) }
        }
        // Floating prism
        a.add(Bone.FLOAT, Slot.SECONDARY, emissive = true) { octa(0.13f, 0.21f, 0.13f) }
        // Crystal rifle
        a.add(Bone.WEAPON, Slot.DARK) { at(0.42f, 0f, 0f) { alongX { cylinder(0.05f, 1.0f, 10) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(-0.04f, -0.02f, 0f) { roundedBox(0.26f, 0.13f, 0.09f, 0.04f) } }
        a.add(Bone.WEAPON, Slot.METAL) { at(0.3f, 0.09f, 0f) { alongX { cylinder(0.045f, 0.22f, 8) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY, emissive = true) { at(0.98f, 0f, 0f) { octa(0.18f, 0.09f, 0.09f) } }
        a.add(Bone.WEAPON, Slot.SKIN) { at(0.06f, 0f, 0f) { sphere(0.08f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.1f, 0f) { capsule(0.075f, 0.14f) } }
        a.add(Bone.ARM, Slot.SKIN) { at(0f, -0.26f, 0f) { sphere(0.08f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Kito — arc-blade assassin

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
        // Arc blade: hilt, guard and a long glowing edge.
        a.add(Bone.WEAPON, Slot.METAL) { at(0.06f, 0f, 0f) { alongX { cylinder(0.05f, 0.2f, 8) } } }
        a.add(Bone.WEAPON, Slot.SECONDARY) { at(0.18f, 0f, 0f) { roundedBox(0.06f, 0.2f, 0.12f, 0.02f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, emissive = true) { at(0.62f, 0f, 0f) { roundedBox(0.82f, 0.11f, 0.035f, 0.015f) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.02f, 0f, 0f) { sphere(0.09f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.11f, 0f) { capsule(0.08f, 0.14f) } }
        a.add(Bone.ARM, Slot.DARK) { at(0f, -0.28f, 0f) { sphere(0.085f, 8, 10) } }
        return a.build(rig)
    }

    // ------------------------------------------------------------------ Varun — firefighter with a rocket rack

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
        a.add(Bone.HEAD, Slot.SKIN) { at(0f, 0.2f, 0f) { sphere(0.32f) } }
        a.add(Bone.HEAD, Slot.DARK, outline = false) { at(0.29f, 0.1f, 0f) { roundedBox(0.08f, 0.06f, 0.3f, 0.025f) } }
        a.add(Bone.HEAD, Slot.WHITE, outline = false) { for (z in listOf(0.11f, -0.11f)) at(0.31f, 0.23f, z) { sphere(0.04f, 6, 8) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.02f, 0.3f, 0f) { ellipsoid(0.37f, 0.33f, 0.37f, 8, 16, 0f, 0.5f) } }
        a.add(Bone.HEAD, Slot.PRIMARY) { at(-0.06f, 0.3f, 0f) { rotate(-10f, 0f, 0f, 1f); cylinder(0.5f, 0.04f, 18) } }
        a.add(Bone.HEAD, Slot.SECONDARY) { at(-0.04f, 0.58f, 0f) { roundedBox(0.56f, 0.12f, 0.08f, 0.03f) } }
        a.add(Bone.HEAD, Slot.ACCENT, outline = false, emissive = true) { at(0.33f, 0.42f, 0f) { roundedBox(0.05f, 0.14f, 0.14f, 0.03f) } }
        // The rocket rack on his shoulder: three tubes side by side, a warhead showing in each.
        a.add(Bone.WEAPON, Slot.METAL) { for (z in listOf(-0.14f, 0f, 0.14f)) at(0.3f, 0.04f, z) { alongX { cylinder(0.075f, 0.62f, 10) } } }
        a.add(Bone.WEAPON, Slot.DARK) { for (x in listOf(0.12f, 0.46f)) at(x, 0.04f, 0f) { roundedBox(0.08f, 0.2f, 0.48f, 0.03f) } }
        a.add(Bone.WEAPON, Slot.ACCENT, outline = false, emissive = true) { for (z in listOf(-0.14f, 0f, 0.14f)) at(0.63f, 0.04f, z) { sphere(0.065f, 6, 8) } }
        a.add(Bone.WEAPON, Slot.DARK) { at(0.04f, -0.08f, 0f) { sphere(0.1f, 8, 10) } }
        a.add(Bone.ARM, Slot.PRIMARY) { at(0f, -0.12f, 0f) { capsule(0.1f, 0.14f) } }
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
    fun draw(prog: Program, def: FighterDef, skinIndex: Int, x: Float, z: Float, facing: Float, anim: FighterAnim, pass: Pass, ink: FloatArray = INK) {
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
        Matrix.rotateM(body, 0, -8f * mv, 0f, 0f, 1f) // lean forward when running
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
                    Matrix.rotateM(bone, 0, anim.recoil * 14f, 0f, 0f, 1f)
                }
                Bone.ARM -> {
                    System.arraycopy(body, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, rig.shoulderL[0], rig.shoulderL[1], rig.shoulderL[2])
                    Matrix.rotateM(bone, 0, sin(anim.walk) * 35f * mv + sin(t * 2.4f) * 3f, 0f, 0f, 1f)
                }
                Bone.LEG_L, Bone.LEG_R -> {
                    val side = if (p.bone == Bone.LEG_L) -1f else 1f
                    System.arraycopy(root, 0, bone, 0, 16)
                    Matrix.translateM(bone, 0, 0f, rig.hipY, rig.hipZ * side)
                    Matrix.rotateM(bone, 0, sin(anim.walk) * 32f * mv * side, 0f, 0f, 1f)
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
                    val c = slotColor(p.slot, skin.primary, skin.secondary, skin.accent)
                    prog.v4("uTint", c[0], c[1], c[2], 1f)
                    prog.f("uFlash", anim.flash)
                    prog.f("uEmissive", if (p.emissive) 0.75f else 0f)
                }
                Pass.OUTLINE -> prog.v4("uTint", ink[0], ink[1], ink[2], ink[3])
                Pass.SILHOUETTE, Pass.SHADOW -> Unit
            }
            p.mesh.draw()
        }
    }

    private val col = FloatArray(3)
    private fun slotColor(s: Slot, primary: Long, secondary: Long, accent: Long): FloatArray {
        val c: Long = when (s) {
            Slot.PRIMARY -> primary
            Slot.SECONDARY -> secondary
            Slot.ACCENT -> accent
            Slot.SKIN -> 0xFFFFD3B0
            Slot.DARK -> darken(primary, 0.5f)
            Slot.METAL -> 0xFF6A7390
            Slot.WHITE -> 0xFFFFFFFF
            Slot.INK -> 0xFF1B1035
        }
        val v = c.toInt()
        col[0] = ((v shr 16) and 0xFF) / 255f; col[1] = ((v shr 8) and 0xFF) / 255f; col[2] = (v and 0xFF) / 255f
        return col
    }

    companion object {
        val INK = floatArrayOf(0.106f, 0.063f, 0.208f, 1f)
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
