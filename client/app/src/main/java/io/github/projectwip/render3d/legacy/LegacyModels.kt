package io.github.projectwip.render3d.legacy

import io.github.projectwip.render3d.Bone
import io.github.projectwip.render3d.FighterModel
import io.github.projectwip.render3d.FighterModels
import io.github.projectwip.render3d.Rig
import io.github.projectwip.render3d.Slot
import io.github.projectwip.gl.MeshBuilder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The models of the fighters removed in build 114 (Swish, Bark, Kira, Kito, Buddy), kept as they were by the owner's
 * decision: Ninja is the only fighter now. Nothing plays them; the removed fighters' ids still draw with them.
 */
object LegacyModels {

    fun FighterModels.buildSwish(): FighterModel {
        val a = FighterModels.Assembler()
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
        }
        a.mouth(0.255f, 0.05f, 0f, 0.055f)
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

    fun FighterModels.buildBrakk(): FighterModel {
        val a = FighterModels.Assembler()
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

    fun FighterModels.buildKira(): FighterModel {
        val a = FighterModels.Assembler()
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
        a.mouth(0.265f, 0.02f, 0f, 0.05f)
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

    fun FighterModels.buildKito(): FighterModel {
        val a = FighterModels.Assembler()
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

    fun FighterModels.buildBuddy(): FighterModel {
        val a = FighterModels.Assembler()
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
}
