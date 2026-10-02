package io.github.projectwip.ai

import io.github.projectwip.sim.Arena
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/** A* over the tile grid (8-way, no corner cutting) with line-of-sight path smoothing. */
class Pathfinder(private val arena: Arena) {
    private val w = arena.width
    private val h = arena.height
    private val gScore = FloatArray(w * h)
    private val cameFrom = IntArray(w * h)
    private val closed = BooleanArray(w * h)

    private fun walkable(x: Int, y: Int) = x in 0 until w && y in 0 until h && !arena[x, y].blocksMove

    /** Returns waypoints (tile centres, smoothed) from start to goal, excluding the start. Empty if unreachable. */
    fun find(sx: Float, sy: Float, gx: Float, gy: Float, radius: Float): List<FloatArray> {
        val start = nearestWalkable(floor(sx).toInt(), floor(sy).toInt()) ?: return emptyList()
        val goal = nearestWalkable(floor(gx).toInt(), floor(gy).toInt()) ?: return emptyList()
        if (start == goal) return listOf(floatArrayOf(gx, gy))

        gScore.fill(Float.MAX_VALUE)
        closed.fill(false)
        cameFrom.fill(-1)
        val open = PriorityQueue<LongArrayNode>(compareBy { it.f })
        gScore[start] = 0f
        open += LongArrayNode(start, heuristic(start, goal))
        var found = false
        while (open.isNotEmpty()) {
            val cur = open.poll()!!.index
            if (cur == goal) { found = true; break }
            if (closed[cur]) continue
            closed[cur] = true
            val cx = cur % w
            val cy = cur / w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (!walkable(nx, ny)) continue
                if (dx != 0 && dy != 0 && (!walkable(cx + dx, cy) || !walkable(cx, cy + dy))) continue
                val n = ny * w + nx
                if (closed[n]) continue
                val cost = gScore[cur] + if (dx != 0 && dy != 0) 1.4142f else 1f
                if (cost < gScore[n]) {
                    gScore[n] = cost
                    cameFrom[n] = cur
                    open += LongArrayNode(n, cost + heuristic(n, goal))
                }
            }
        }
        if (!found) return emptyList()

        val raw = ArrayList<FloatArray>()
        var c = goal
        while (c != -1 && c != start) {
            raw += floatArrayOf(c % w + 0.5f, c / w + 0.5f)
            c = cameFrom[c]
        }
        raw.reverse()
        if (raw.isNotEmpty()) raw[raw.lastIndex] = floatArrayOf(gx, gy).takeIf { !arena.circleBlocked(gx, gy, radius) } ?: raw.last()
        return smooth(sx, sy, raw, radius)
    }

    /** Skips waypoints that can be reached in a straight line. */
    private fun smooth(sx: Float, sy: Float, pts: List<FloatArray>, r: Float): List<FloatArray> {
        if (pts.size <= 1) return pts
        val out = ArrayList<FloatArray>()
        var fromX = sx
        var fromY = sy
        var i = 0
        while (i < pts.size) {
            var j = pts.lastIndex
            while (j > i && !arena.walkClear(fromX, fromY, pts[j][0], pts[j][1], r)) j--
            out += pts[j]
            fromX = pts[j][0]; fromY = pts[j][1]
            i = j + 1
        }
        return out
    }

    private fun heuristic(a: Int, b: Int): Float {
        val dx = abs(a % w - b % w).toFloat()
        val dy = abs(a / w - b / w).toFloat()
        return (dx + dy) + (1.4142f - 2f) * minOf(dx, dy)
    }

    private fun nearestWalkable(x: Int, y: Int): Int? {
        if (walkable(x, y)) return y * w + x
        for (r in 1..4) for (dy in -r..r) for (dx in -r..r) {
            if (walkable(x + dx, y + dy)) return (y + dy) * w + (x + dx)
        }
        return null
    }

    private class LongArrayNode(val index: Int, val f: Float)

    companion object {
        fun dist(a: FloatArray, x: Float, y: Float) = hypot(a[0] - x, a[1] - y)
    }
}
