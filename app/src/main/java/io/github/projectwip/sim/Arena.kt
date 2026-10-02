package io.github.projectwip.sim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

enum class Tile(val blocksMove: Boolean, val blocksShots: Boolean) {
    FLOOR(false, false),
    WALL(true, true),
    /** Tall grass: fighters inside are hidden from enemies. */
    THICKET(false, false),
    /** Coolant pool: blocks movement, shots fly over it. */
    WATER(true, false),
}

data class Spawn(val x: Float, val y: Float)

/**
 * A tile map. One unit = one tile. (0,0) is the top-left corner of the map.
 * Everything outside the map counts as wall.
 */
class Arena(
    val name: String,
    val width: Int,
    val height: Int,
    private val tiles: Array<Tile>,
    val spawns: List<List<Spawn>>,
) {
    operator fun get(tx: Int, ty: Int): Tile =
        if (tx < 0 || ty < 0 || tx >= width || ty >= height) Tile.WALL else tiles[ty * width + tx]

    fun tileAt(x: Float, y: Float): Tile = get(floor(x).toInt(), floor(y).toInt())

    fun inThicket(x: Float, y: Float) = tileAt(x, y) == Tile.THICKET

    /**
     * Moves a circle by (dx, dy), sliding along blocking tiles. Returns the new position in [out].
     * Returns true if the move was obstructed.
     */
    fun moveCircle(x: Float, y: Float, r: Float, dx: Float, dy: Float, out: FloatArray): Boolean {
        val len = sqrt(dx * dx + dy * dy)
        val steps = maxOf(1, ceil(len / 0.2f).toInt())
        val sx = dx / steps
        val sy = dy / steps
        var px = x
        var py = y
        var blocked = false
        repeat(steps) {
            px += sx
            py += sy
            // Two passes resolve corners cleanly.
            repeat(2) {
                val minTx = floor(px - r).toInt()
                val maxTx = floor(px + r).toInt()
                val minTy = floor(py - r).toInt()
                val maxTy = floor(py + r).toInt()
                for (ty in minTy..maxTy) for (tx in minTx..maxTx) {
                    if (!get(tx, ty).blocksMove) continue
                    val cx = px.coerceIn(tx.toFloat(), tx + 1f)
                    val cy = py.coerceIn(ty.toFloat(), ty + 1f)
                    val ox = px - cx
                    val oy = py - cy
                    val d2 = ox * ox + oy * oy
                    if (d2 >= r * r) continue
                    blocked = true
                    if (d2 > 1e-8f) {
                        val d = sqrt(d2)
                        px += ox / d * (r - d)
                        py += oy / d * (r - d)
                    } else {
                        // Centre inside the tile: push out along the shallowest axis.
                        val left = px - tx
                        val right = tx + 1 - px
                        val top = py - ty
                        val bottom = ty + 1 - py
                        when (minOf(minOf(left, right), minOf(top, bottom))) {
                            left -> px = tx - r
                            right -> px = tx + 1 + r
                            top -> py = ty - r
                            else -> py = ty + 1 + r
                        }
                    }
                }
            }
        }
        out[0] = px
        out[1] = py
        return blocked
    }

    /** True if a circle of radius r overlaps any movement-blocking tile. */
    fun circleBlocked(x: Float, y: Float, r: Float): Boolean {
        for (ty in floor(y - r).toInt()..floor(y + r).toInt()) for (tx in floor(x - r).toInt()..floor(x + r).toInt()) {
            if (!get(tx, ty).blocksMove) continue
            val cx = x.coerceIn(tx.toFloat(), tx + 1f)
            val cy = y.coerceIn(ty.toFloat(), ty + 1f)
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r) return true
        }
        return false
    }

    /** True if a circle can travel in a straight line from a to b without touching movement blockers. */
    fun walkClear(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val steps = maxOf(1, ceil(len / 0.2f).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            if (circleBlocked(x0 + dx * t, y0 + dy * t, r)) return false
        }
        return true
    }

    /**
     * Grid traversal (Amanatides & Woo). Returns the distance along the segment at which it first enters a
     * tile that blocks shots, or -1 if the whole segment is clear.
     */
    fun shotBlockedAt(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return if (tileAt(x0, y0).blocksShots) 0f else -1f
        var tx = floor(x0).toInt()
        var ty = floor(y0).toInt()
        val endX = floor(x1).toInt()
        val endY = floor(y1).toInt()
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val tDeltaX = if (dx != 0f) abs(1f / dx) else Float.MAX_VALUE
        val tDeltaY = if (dy != 0f) abs(1f / dy) else Float.MAX_VALUE
        var tMaxX = if (dx > 0) (tx + 1 - x0) * tDeltaX else if (dx < 0) (x0 - tx) * tDeltaX else Float.MAX_VALUE
        var tMaxY = if (dy > 0) (ty + 1 - y0) * tDeltaY else if (dy < 0) (y0 - ty) * tDeltaY else Float.MAX_VALUE
        var t = 0f
        var guard = 0
        while (guard++ < 512) {
            if (get(tx, ty).blocksShots) return t * len
            if (tx == endX && ty == endY) return -1f
            if (tMaxX < tMaxY) {
                t = tMaxX; tMaxX += tDeltaX; tx += stepX
            } else {
                t = tMaxY; tMaxY += tDeltaY; ty += stepY
            }
            if (t > 1f) return -1f
        }
        return -1f
    }

    fun shotClear(x0: Float, y0: Float, x1: Float, y1: Float) = shotBlockedAt(x0, y0, x1, y1) < 0f

    companion object {
        /**
         * Builds a symmetric arena from its top-left quadrant. The quadrant is mirrored left↔right
         * and top↔bottom, so both teams get exactly the same terrain.
         *
         * Legend: `.` floor, `#` wall, `g` thicket (tall grass), `~` coolant pool.
         */
        fun fromQuadrant(name: String, quadrant: List<String>, spawnYs: List<Float>, spawnInset: Float): Arena {
            val qw = quadrant.first().length
            val qh = quadrant.size
            require(quadrant.all { it.length == qw }) { "Quadrant rows must have equal length" }
            val w = qw * 2
            val h = qh * 2
            val tiles = Array(w * h) { Tile.FLOOR }
            for (y in 0 until h) for (x in 0 until w) {
                val qx = if (x < qw) x else w - 1 - x
                val qy = if (y < qh) y else h - 1 - y
                tiles[y * w + x] = when (quadrant[qy][qx]) {
                    '#' -> Tile.WALL
                    'g' -> Tile.THICKET
                    '~' -> Tile.WATER
                    else -> Tile.FLOOR
                }
            }
            val blue = spawnYs.map { Spawn(spawnInset, it) }
            val red = spawnYs.map { Spawn(w - spawnInset, it) }
            return Arena(name, w, h, tiles, listOf(blue, red))
        }
    }
}

object Arenas {
    /** "Foundry Yard" — the first arena. Edit the quadrant to reshape the whole map. */
    fun foundryYard(): Arena = Arena.fromQuadrant(
        name = "Foundry Yard",
        quadrant = listOf(
            //0123456789ABCDEFG
            "ggg.......g......",
            "ggg..##...g...##.",
            "g....##.......##.",
            ".........ggg.....",
            "..##.....ggg.....",
            "..##..........~~~",
            "......##......~~~",
            "gg....##...#.....",
            "gg.........#.....",
            ".......ggg.......",
        ),
        spawnYs = listOf(6.5f, 10f, 13.5f),
        spawnInset = 1.5f,
    )
}
