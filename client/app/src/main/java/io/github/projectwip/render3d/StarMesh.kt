package io.github.projectwip.render3d

import io.github.projectwip.gl.MeshBuilder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A puffy five-pointed star, the shape the Spark Drops had: points reaching [outer] from the middle (in the XY plane,
 * one point straight up) and swelling to [puff] in front and behind. Spark is one, and so is what he throws.
 */
fun MeshBuilder.puffyStar(outer: Float = 1f, puff: Float = 0.44f) {
    for (s in floatArrayOf(1f, -1f)) starHalf(s, outer, puff)
}

private fun MeshBuilder.starHalf(s: Float, outer: Float, puff: Float) {
    val pos = ArrayList<FloatArray>()
    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int { pos += floatArrayOf(x, y, z); return vertex(x, y, z, nx, ny, nz) }
    val first = v(0f, 0f, puff * s, 0f, 0f, s)
    // Every triangle is wound to face away from the middle of the star: the ink outline is an inverted hull drawn
    // with front faces culled, so a wrong winding paints the whole thing black.
    fun face(i0: Int, i1: Int, i2: Int) {
        val p0 = pos[i0 - first]; val p1 = pos[i1 - first]; val p2 = pos[i2 - first]
        val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]
        val wx = p2[0] - p0[0]; val wy = p2[1] - p0[1]; val wz = p2[2] - p0[2]
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        val cx = (p0[0] + p1[0] + p2[0]) / 3f; val cy = (p0[1] + p1[1] + p2[1]) / 3f; val cz = (p0[2] + p1[2] + p2[2]) / 3f
        if (nx * cx + ny * cy + nz * (cz + puff * 0.5f * s) >= 0f) tri(i0, i1, i2) else tri(i0, i2, i1)
    }
    val rim = IntArray(10)
    val edge = IntArray(10)
    for (i in 0 until 10) {
        val a = -Math.PI / 2 + i * Math.PI / 5
        val r = outer * (if (i % 2 == 0) 1f else 0.51f)
        val x = (cos(a) * r).toFloat(); val y = -(sin(a) * r).toFloat()
        val len = sqrt(x * x + y * y + 0.2f * outer * outer)
        rim[i] = v(x, y, puff * 0.25f * s, x / len, y / len, 0.45f * outer * s / len)
        edge[i] = v(x, y, 0f, x / r, y / r, 0f)
    }
    for (i in 0 until 10) {
        val j = (i + 1) % 10
        face(first, rim[i], rim[j])
        face(rim[i], rim[j], edge[j]); face(rim[i], edge[j], edge[i])
    }
}
