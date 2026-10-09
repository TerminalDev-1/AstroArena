package io.github.projectwip.render3d

import io.github.projectwip.gl.Mesh
import io.github.projectwip.gl.MeshBuilder
import io.github.projectwip.sim.Arena
import io.github.projectwip.sim.Tile
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Static 3D scene for an [Arena]. Sim (x, y) maps to world (x, 0, y). Built once per match on the GL thread.
 */
class ArenaModel(val arena: Arena) {
    /** Floor, pools, rim, surroundings — opaque, no outline. */
    val ground: Mesh
    /** Crate walls and props — opaque, outlined. */
    val solids: Mesh
    /** Bushes — swaying, fade near friendly fighters. */
    val grass: Mesh
    val water: Mesh
    /** World positions of lamp glows (x, y, z) for the sprite pass. */
    val lamps = ArrayList<FloatArray>()

    private val rnd = Random(42)

    init {
        val g = MeshBuilder()
        val s = MeshBuilder()
        val gr = MeshBuilder()
        val w = MeshBuilder()
        buildFloor(g)
        buildPools(g, w)
        buildWalls(s)
        buildRim(g, s)
        buildSurroundings(g, s)
        buildSpawnPads(g)
        buildGrass(gr)
        ground = g.build(); solids = s.build(); grass = gr.build(); water = w.build()
    }

    private fun hash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7fffffff
    }

    // ------------------------------------------------------------------ floor

    private fun buildFloor(b: MeshBuilder) {
        val a = arena
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val t = a[x, y]
            if (t == Tile.WATER) continue
            val checker = (x + y) % 2 == 0
            var r: Float; var gg: Float; var bb: Float
            if (t == Tile.THICKET) { r = 0.22f; gg = 0.52f; bb = 0.3f }
            else if (checker) { r = 0.9f; gg = 0.79f; bb = 0.58f } else { r = 0.86f; gg = 0.75f; bb = 0.55f }
            // Team home zones (vertical team maps: your team at the bottom).
            if (a.spawns.isNotEmpty()) {
                if (y >= a.height - 3) { r = r * 0.78f + 0.25f * 0.22f; gg = gg * 0.82f + 0.71f * 0.18f; bb = bb * 0.7f + 1f * 0.3f }
                if (y < 3) { r = r * 0.75f + 1f * 0.25f; gg = gg * 0.8f + 0.3f * 0.2f; bb = bb * 0.8f + 0.37f * 0.2f }
            }
            val v = (hash(x, y) % 7) / 100f
            b.color(r - v, gg - v, bb - v)
            tile(b, x.toFloat(), y.toFloat())
            // Decals: bolts and scuffs
            val h = hash(x, y)
            if (t != Tile.THICKET && h % 6 == 0) {
                b.color(r * 0.72f, gg * 0.7f, bb * 0.68f)
                b.with { translate(x + 0.3f + (h % 5) * 0.08f, 0.012f, y + 0.35f + (h % 3) * 0.14f); cylinder(0.06f, 0.02f, 8) }
                b.with { translate(x + 0.66f, 0.012f, y + 0.7f); cylinder(0.05f, 0.02f, 8) }
            }
        }
        // Centre markings
        b.color(1f, 1f, 1f, 1f)
        b.with { translate(a.width / 2f, 0.008f, a.height / 2f); ring(1.55f, 1.7f, 48) }
        b.with { translate(a.width / 2f, 0.008f, 0f); b.groundQuad(-0.06f, 0.2f, 0.06f, a.height / 2f - 1.7f) }
        b.with { translate(a.width / 2f, 0.008f, 0f); b.groundQuad(-0.06f, a.height / 2f + 1.7f, 0.06f, a.height - 0.2f) }
    }

    /** A floor tile with bevelled edges (top at y=0, bevel down to y=-0.06). */
    private fun tile(b: MeshBuilder, x: Float, z: Float) {
        val i = 0.045f
        val d = -0.06f
        val t0 = b.vertex(x + i, 0f, z + i, 0f, 1f, 0f)
        val t1 = b.vertex(x + 1 - i, 0f, z + i, 0f, 1f, 0f)
        val t2 = b.vertex(x + 1 - i, 0f, z + 1 - i, 0f, 1f, 0f)
        val t3 = b.vertex(x + i, 0f, z + 1 - i, 0f, 1f, 0f)
        b.quad(t0, t3, t2, t1)
        // bevels (darker)
        val rr = b.r; val gg = b.g; val bb = b.b
        b.color(rr * 0.8f, gg * 0.8f, bb * 0.8f)
        val n = 0.7f
        // north (-z)
        run {
            val a0 = b.vertex(x, d, z, 0f, n, -n); val a1 = b.vertex(x + 1, d, z, 0f, n, -n)
            val a2 = b.vertex(x + 1 - i, 0f, z + i, 0f, n, -n); val a3 = b.vertex(x + i, 0f, z + i, 0f, n, -n)
            b.quad(a0, a3, a2, a1)
        }
        // south (+z)
        run {
            val a0 = b.vertex(x + i, 0f, z + 1 - i, 0f, n, n); val a1 = b.vertex(x + 1 - i, 0f, z + 1 - i, 0f, n, n)
            val a2 = b.vertex(x + 1, d, z + 1, 0f, n, n); val a3 = b.vertex(x, d, z + 1, 0f, n, n)
            b.quad(a0, a3, a2, a1)
        }
        // west (-x)
        run {
            val a0 = b.vertex(x, d, z, -n, n, 0f); val a1 = b.vertex(x + i, 0f, z + i, -n, n, 0f)
            val a2 = b.vertex(x + i, 0f, z + 1 - i, -n, n, 0f); val a3 = b.vertex(x, d, z + 1, -n, n, 0f)
            b.quad(a0, a3, a2, a1)
        }
        // east (+x)
        run {
            val a0 = b.vertex(x + 1 - i, 0f, z + i, n, n, 0f); val a1 = b.vertex(x + 1, d, z, n, n, 0f)
            val a2 = b.vertex(x + 1, d, z + 1, n, n, 0f); val a3 = b.vertex(x + 1 - i, 0f, z + 1 - i, n, n, 0f)
            b.quad(a0, a3, a2, a1)
        }
        b.color(rr, gg, bb)
    }

    // ------------------------------------------------------------------ pools

    private fun buildPools(g: MeshBuilder, w: MeshBuilder) {
        val a = arena
        val depth = -0.45f
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y] != Tile.WATER) continue
            g.color(0.08f, 0.3f, 0.5f)
            g.groundQuad(x.toFloat(), y.toFloat(), x + 1f, y + 1f, depth)
            g.color(0.53f, 0.57f, 0.62f)
            // Pool walls where the neighbour is not water
            if (a[x, y - 1] != Tile.WATER) g.with { translate(x + 0.5f, depth / 2, y + 0.04f); box(1f, -depth, 0.08f) }
            if (a[x, y + 1] != Tile.WATER) g.with { translate(x + 0.5f, depth / 2, y + 0.96f); box(1f, -depth, 0.08f) }
            if (a[x - 1, y] != Tile.WATER) g.with { translate(x + 0.04f, depth / 2, y + 0.5f); box(0.08f, -depth, 1f) }
            if (a[x + 1, y] != Tile.WATER) g.with { translate(x + 0.96f, depth / 2, y + 0.5f); box(0.08f, -depth, 1f) }
            // Raised lip
            g.color(0.78f, 0.82f, 0.9f)
            if (a[x, y - 1] != Tile.WATER) g.with { translate(x + 0.5f, 0.04f, y + 0.03f); box(1f, 0.08f, 0.1f) }
            if (a[x, y + 1] != Tile.WATER) g.with { translate(x + 0.5f, 0.04f, y + 0.97f); box(1f, 0.08f, 0.1f) }
            if (a[x - 1, y] != Tile.WATER) g.with { translate(x + 0.03f, 0.04f, y + 0.5f); box(0.1f, 0.08f, 1f) }
            if (a[x + 1, y] != Tile.WATER) g.with { translate(x + 0.97f, 0.04f, y + 0.5f); box(0.1f, 0.08f, 1f) }
            w.color(1f, 1f, 1f)
            w.groundQuad(x.toFloat(), y.toFloat(), x + 1f, y + 1f, -0.14f)
        }
    }

    // ------------------------------------------------------------------ walls

    private fun buildWalls(s: MeshBuilder) {
        val a = arena
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y] != Tile.WALL) continue // crates are dynamic; see MatchRenderer
            val h = hash(x, y)
            val hgt = 1.05f + (h % 3) * 0.04f
            s.color(0.42f, 0.61f, 0.78f)
            s.with { translate(x + 0.5f, hgt / 2 - 0.02f, y + 0.5f); roundedBox(0.96f, hgt, 0.96f, 0.1f, 2) }
            s.color(0.61f, 0.79f, 0.95f)
            s.with { translate(x + 0.5f, hgt - 0.02f, y + 0.5f); roundedBox(1.0f, 0.16f, 1.0f, 0.07f, 2) }
            if (h % 4 == 0) {
                // Hazard band
                s.color(1f, 0.62f, 0.11f)
                s.with { translate(x + 0.5f, hgt * 0.42f, y + 0.5f); roundedBox(0.99f, 0.14f, 0.99f, 0.05f, 1) }
            } else {
                // Rivet plate on the camera-facing side
                s.color(0.31f, 0.47f, 0.62f)
                s.with { translate(x + 0.5f, hgt * 0.45f, y + 0.985f); roundedBox(0.6f, 0.4f, 0.04f, 0.02f, 1) }
            }
        }
    }

    // ------------------------------------------------------------------ rim & surroundings

    private fun buildRim(g: MeshBuilder, s: MeshBuilder) {
        val a = arena
        val wd = a.width.toFloat(); val ht = a.height.toFloat()
        // Perimeter blocks with alternating hazard colours.
        fun block(cx: Float, cz: Float, sx: Float, sz: Float, i: Int) {
            if (i % 2 == 0) s.color(1f, 0.78f, 0.2f) else s.color(0.16f, 0.24f, 0.32f)
            s.with { translate(cx, 0.28f, cz); roundedBox(sx, 0.56f, sz, 0.06f, 1) }
        }
        for (i in 0 until a.width) { block(i + 0.5f, -0.35f, 1f, 0.7f, i); block(i + 0.5f, ht + 0.35f, 1f, 0.7f, i + 1) }
        for (i in 0 until a.height) { block(-0.35f, i + 0.5f, 0.7f, 1f, i + 1); block(wd + 0.35f, i + 0.5f, 0.7f, 1f, i) }
        s.color(0.95f, 0.38f, 0.1f)
        for ((cx, cz) in listOf(-0.35f to -0.35f, wd + 0.35f to -0.35f, -0.35f to ht + 0.35f, wd + 0.35f to ht + 0.35f)) {
            s.with { translate(cx, 0.45f, cz); roundedBox(0.9f, 0.9f, 0.9f, 0.15f, 2) }
        }
        // Outer concourse
        // Ground outside the arena: frames around it, so sunken pools inside stay visible.
        fun frame(inset: Float, outer: Float, y: Float) {
            g.groundQuad(-outer, -outer, wd + outer, -inset, y)
            g.groundQuad(-outer, ht + inset, wd + outer, ht + outer, y)
            g.groundQuad(-outer, -inset, -inset, ht + inset, y)
            g.groundQuad(wd + inset, -inset, wd + outer, ht + inset, y)
        }
        g.color(0.25f, 0.34f, 0.43f)
        frame(3f, 40f, -0.07f)
        g.color(0.31f, 0.42f, 0.52f)
        frame(0f, 3f, -0.065f)
        // Walkway stripes
        g.color(0.41f, 0.53f, 0.64f)
        var x = -3f
        while (x < wd + 3f) { g.groundQuad(x, -2.9f, x + 0.6f, -2.5f, -0.06f); g.groundQuad(x, ht + 2.5f, x + 0.6f, ht + 2.9f, -0.06f); x += 1.4f }
    }

    private fun buildSurroundings(g: MeshBuilder, s: MeshBuilder) {
        val a = arena
        val wd = a.width.toFloat(); val ht = a.height.toFloat()
        // Spectator stands along the far (top) side: stepped tiers.
        for (tier in 0 until 4) {
            s.color(0.36f + tier * 0.04f, 0.3f + tier * 0.03f, 0.62f + tier * 0.03f)
            s.with { translate(wd / 2, 0.35f + tier * 0.55f, -4.2f - tier * 1.1f); roundedBox(wd + 6f, 0.7f + tier * 1.1f, 1.1f, 0.08f, 1) }
        }
        // Crowd "heads" on the stands — little coloured spheres.
        val colors = listOf(0xFFFF8A1FL, 0xFF2EC4F1L, 0xFFFFD23FL, 0xFFE85CFFL, 0xFF62E887L, 0xFFFF4D5EL)
        for (tier in 0 until 4) {
            var cx = -2f
            while (cx < wd + 2f) {
                if (rnd.nextFloat() < 0.6f) {
                    s.color(colors[rnd.nextInt(colors.size)])
                    s.with { translate(cx, 0.75f + tier * 1.1f + 0.2f, -4.2f - tier * 1.1f); sphere(0.22f, 6, 8) }
                }
                cx += 0.7f + rnd.nextFloat() * 0.4f
            }
        }
        // Barrel clusters, crate stacks and lamps around the other sides.
        val spots = listOf(
            -2.4f to 2f, -2.2f to ht - 3f, wd + 2.3f to 3f, wd + 2.5f to ht - 2.5f,
            5f to ht + 2.4f, wd - 6f to ht + 2.3f, wd / 2 - 4f to ht + 2.6f, wd / 2 + 4f to ht + 2.6f,
        )
        spots.forEachIndexed { i, (sx, sz) ->
            when (i % 3) {
                0 -> barrels(s, sx, sz)
                1 -> crates(s, sx, sz)
                else -> lamp(s, sx, sz)
            }
        }
        for ((sx, sz) in listOf(-2.6f to ht / 2, wd + 2.6f to ht / 2)) lamp(s, sx, sz)
        // Big pipes running along the bottom edge.
        s.color(0.55f, 0.6f, 0.75f)
        s.with { translate(wd / 2, 0.45f, ht + 4.4f); rotate(90f, 0f, 0f, 1f); cylinder(0.45f, wd + 12f, 18) }
        s.color(1f, 0.62f, 0.11f)
        var px = 0f
        while (px < wd) { s.with { translate(px, 0.45f, ht + 4.4f); rotate(90f, 0f, 0f, 1f); cylinder(0.5f, 0.25f, 18) }; px += 5f }
    }

    private fun barrels(s: MeshBuilder, x: Float, z: Float) {
        for (k in 0 until 3) {
            val bx = x + cos(k * 2.1f) * 0.55f
            val bz = z + sin(k * 2.1f) * 0.55f
            s.color(if (k == 1) 0xFF2EC4F1L else 0xFFFF5A3CL)
            s.with { translate(bx, 0.45f, bz); cylinder(0.32f, 0.9f, 14) }
            s.color(0.17f, 0.23f, 0.3f)
            s.with { translate(bx, 0.62f, bz); torus(0.32f, 0.04f, 14, 5) }
        }
    }

    private fun crates(s: MeshBuilder, x: Float, z: Float) {
        s.color(0.85f, 0.55f, 0.25f)
        s.with { translate(x, 0.4f, z); roundedBox(0.8f, 0.8f, 0.8f, 0.08f, 1) }
        s.with { translate(x + 0.85f, 0.35f, z + 0.1f); rotate(14f, 0f, 1f, 0f); roundedBox(0.7f, 0.7f, 0.7f, 0.08f, 1) }
        s.color(0.92f, 0.65f, 0.3f)
        s.with { translate(x + 0.35f, 1.15f, z); rotate(-10f, 0f, 1f, 0f); roundedBox(0.65f, 0.65f, 0.65f, 0.08f, 1) }
    }

    private fun lamp(s: MeshBuilder, x: Float, z: Float) {
        s.color(0.24f, 0.32f, 0.4f)
        s.with { translate(x, 1.6f, z); cylinder(0.08f, 3.2f, 8) }
        s.with { translate(x, 0.12f, z); cylinder(0.25f, 0.24f, 10) }
        s.color(1f, 0.93f, 0.6f)
        s.with { translate(x, 3.25f, z); sphere(0.22f, 8, 10) }
        lamps += floatArrayOf(x, 3.25f, z)
    }

    private fun buildSpawnPads(g: MeshBuilder) {
        for (sp in arena.ffaSpawns) {
            g.color(0.98f, 0.78f, 0.25f)
            g.with { translate(sp.x, 0.02f, sp.y); cylinder(0.5f, 0.05f, 24) }
            g.color(1f, 1f, 1f)
            g.with { translate(sp.x, 0.05f, sp.y); ring(0.34f, 0.41f, 24) }
        }
        arena.spawns.forEachIndexed { team, spawns ->
            for (sp in spawns) {
                if (team == 0) g.color(0.2f, 0.55f, 0.95f) else g.color(0.9f, 0.25f, 0.33f)
                g.with { translate(sp.x, 0.02f, sp.y); cylinder(0.55f, 0.05f, 24) }
                g.color(1f, 1f, 1f)
                g.with { translate(sp.x, 0.05f, sp.y); ring(0.38f, 0.45f, 24) }
            }
        }
    }

    // ------------------------------------------------------------------ bushes

    private fun buildGrass(b: MeshBuilder) {
        val a = arena
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y] != Tile.THICKET) continue
            // Bush body: a couple of overlapping blobs.
            for (k in 0 until 2) {
                val ox = 0.3f + rnd.nextFloat() * 0.4f
                val oz = 0.3f + rnd.nextFloat() * 0.4f
                b.extra = 0.35f
                b.color(0.25f + rnd.nextFloat() * 0.05f, 0.62f + rnd.nextFloat() * 0.08f, 0.32f)
                b.with { translate(x + ox, 0.32f, y + oz); ellipsoid(0.48f, 0.42f, 0.48f, 6, 10) }
            }
            // Blades sticking out of the top.
            repeat(7) {
                val bx = x + 0.12f + rnd.nextFloat() * 0.76f
                val bz = y + 0.12f + rnd.nextFloat() * 0.76f
                val hgt = 0.75f + rnd.nextFloat() * 0.35f
                val light = rnd.nextBoolean()
                blade(b, bx, bz, hgt, if (light) 0.42f else 0.3f, if (light) 0.8f else 0.68f, if (light) 0.4f else 0.34f)
            }
        }
        b.extra = 0f
    }

    private fun blade(b: MeshBuilder, x: Float, z: Float, h: Float, r: Float, g: Float, bl: Float) {
        val w = 0.09f
        val tilt = (rnd.nextFloat() - 0.5f) * 0.25f
        b.color(r * 0.7f, g * 0.7f, bl * 0.7f)
        b.extra = 0f
        val p0 = floatArrayOf(x - w, 0.1f, z - w * 0.6f)
        val p1 = floatArrayOf(x + w, 0.1f, z - w * 0.6f)
        val p2 = floatArrayOf(x, 0.1f, z + w)
        val tip = floatArrayOf(x + tilt, h, z + tilt * 0.5f)
        val base = listOf(p0, p1, p2)
        for (i in 0 until 3) {
            val q0 = base[i]; val q1 = base[(i + 1) % 3]
            // face normal
            val ux = q1[0] - q0[0]; val uy = q1[1] - q0[1]; val uz = q1[2] - q0[2]
            val vx = tip[0] - q0[0]; val vy = tip[1] - q0[1]; val vz = tip[2] - q0[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l; ny /= l; nz /= l
            if (nx * (q0[0] - x) + nz * (q0[2] - z) < 0) { nx = -nx; ny = -ny; nz = -nz }
            b.color(r * 0.7f, g * 0.7f, bl * 0.7f); b.extra = 0f
            val i0 = b.vertex(q0[0], q0[1], q0[2], nx, ny, nz)
            val i1 = b.vertex(q1[0], q1[1], q1[2], nx, ny, nz)
            b.color(r, g, bl); b.extra = 1f
            val i2 = b.vertex(tip[0], tip[1], tip[2], nx, ny, nz)
            b.tri(i0, i1, i2)
            b.tri(i0, i2, i1)
        }
        b.extra = 0f
    }
}
