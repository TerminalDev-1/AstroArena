package io.github.projectwip.gl

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interleaved vertex layout shared by every lit mesh:
 *   location 0: position (3)   location 1: normal (3)   location 2: colour RGBA (4)   location 3: extra (1)
 * `extra` is a free per-vertex scalar — grass uses it as "sway weight".
 */
const val STRIDE_FLOATS = 11

class Mesh(private val vao: Int, private val vbo: Int, private val ibo: Int, val indexCount: Int) {
    fun draw(mode: Int = GLES30.GL_TRIANGLES) {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(mode, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
    }
}

/**
 * Builds meshes from primitives on the CPU. Primitives are placed with an affine transform stack;
 * normals are transformed by the inverse-transpose so non-uniform scales (ellipsoids, squashed boxes) light correctly.
 * Must call [build] on a thread with a current GL context.
 */
class MeshBuilder {
    private var verts = FloatArray(4096)
    private var vCount = 0
    private var idx = IntArray(8192)
    private var iCount = 0

    private val stack = ArrayList<FloatArray>()
    private var m = identity()
    private var nm = identity()
    private val tmp4 = FloatArray(4)
    private val out4 = FloatArray(4)

    var r = 1f; var g = 1f; var b = 1f; var a = 1f
    var extra = 0f

    fun color(c: Long, alpha: Float = 1f): MeshBuilder {
        val v = c.toInt()
        r = ((v shr 16) and 0xFF) / 255f; g = ((v shr 8) and 0xFF) / 255f; b = (v and 0xFF) / 255f; a = alpha
        return this
    }

    fun color(rr: Float, gg: Float, bb: Float, aa: Float = 1f): MeshBuilder { r = rr; g = gg; b = bb; a = aa; return this }

    // ------------------------------------------------------------------ transform stack

    inline fun with(block: MeshBuilder.() -> Unit) { push(); block(); pop() }
    fun push() { stack += m.copyOf() }
    fun pop() { m = stack.removeAt(stack.lastIndex); updateNormalMatrix() }
    fun translate(x: Float, y: Float, z: Float) { Matrix.translateM(m, 0, x, y, z); updateNormalMatrix() }
    fun rotate(deg: Float, x: Float, y: Float, z: Float) { Matrix.rotateM(m, 0, deg, x, y, z); updateNormalMatrix() }
    fun scale(x: Float, y: Float, z: Float) { Matrix.scaleM(m, 0, x, y, z); updateNormalMatrix() }

    private fun updateNormalMatrix() {
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, m, 0)
        Matrix.transposeM(nm, 0, inv, 0)
    }

    // ------------------------------------------------------------------ raw

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        ensureV()
        tmp4[0] = x; tmp4[1] = y; tmp4[2] = z; tmp4[3] = 1f
        Matrix.multiplyMV(out4, 0, m, 0, tmp4, 0)
        val o = vCount * STRIDE_FLOATS
        verts[o] = out4[0]; verts[o + 1] = out4[1]; verts[o + 2] = out4[2]
        tmp4[0] = nx; tmp4[1] = ny; tmp4[2] = nz; tmp4[3] = 0f
        Matrix.multiplyMV(out4, 0, nm, 0, tmp4, 0)
        val l = sqrt(out4[0] * out4[0] + out4[1] * out4[1] + out4[2] * out4[2]).coerceAtLeast(1e-6f)
        verts[o + 3] = out4[0] / l; verts[o + 4] = out4[1] / l; verts[o + 5] = out4[2] / l
        verts[o + 6] = r; verts[o + 7] = g; verts[o + 8] = b; verts[o + 9] = a
        verts[o + 10] = extra
        return vCount++
    }

    fun tri(i0: Int, i1: Int, i2: Int) {
        if (iCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[iCount++] = i0; idx[iCount++] = i1; idx[iCount++] = i2
    }

    fun quad(i0: Int, i1: Int, i2: Int, i3: Int) { tri(i0, i1, i2); tri(i0, i2, i3) }

    private fun ensureV() {
        if ((vCount + 1) * STRIDE_FLOATS > verts.size) verts = verts.copyOf(verts.size * 2)
    }

    // ------------------------------------------------------------------ primitives (all centred on the origin)

    /** Flat-shaded box. */
    fun box(sx: Float, sy: Float, sz: Float) {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        face(-hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz, 0f, 0f, 1f)
        face(hx, -hy, -hz, -hx, -hy, -hz, -hx, hy, -hz, hx, hy, -hz, 0f, 0f, -1f)
        face(hx, -hy, hz, hx, -hy, -hz, hx, hy, -hz, hx, hy, hz, 1f, 0f, 0f)
        face(-hx, -hy, -hz, -hx, -hy, hz, -hx, hy, hz, -hx, hy, -hz, -1f, 0f, 0f)
        face(-hx, hy, hz, hx, hy, hz, hx, hy, -hz, -hx, hy, -hz, 0f, 1f, 0f)
        face(-hx, -hy, -hz, hx, -hy, -hz, hx, -hy, hz, -hx, -hy, hz, 0f, -1f, 0f)
    }

    private fun face(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float, nx: Float, ny: Float, nz: Float,
    ) {
        val a0 = vertex(x0, y0, z0, nx, ny, nz)
        val a1 = vertex(x1, y1, z1, nx, ny, nz)
        val a2 = vertex(x2, y2, z2, nx, ny, nz)
        val a3 = vertex(x3, y3, z3, nx, ny, nz)
        quad(a0, a1, a2, a3)
    }

    /**
     * Smooth rounded box ("spherified cube"): each face is a grid whose points are pulled onto a rounded
     * shell. Smooth normals make it ideal for toon shading and inverted-hull outlines.
     */
    fun roundedBox(sx: Float, sy: Float, sz: Float, radius: Float, seg: Int = 5) {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        val rr = minOf(radius, hx, hy, hz)
        val ix = hx - rr; val iy = hy - rr; val iz = hz - rr
        // For each of the 6 faces, generate a (seg+1)^2 grid.
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        )
        val n = seg * 2 + 1 // more rows near edges by doubling resolution
        for (f in faces) {
            val base = vCount
            for (j in 0..n) for (i in 0..n) {
                val u = -1f + 2f * i / n
                val v = -1f + 2f * j / n
                // point on cube surface
                val px = (f[0] + f[3] * u + f[6] * v) * hx
                val py = (f[1] + f[4] * u + f[7] * v) * hy
                val pz = (f[2] + f[5] * u + f[8] * v) * hz
                val cx = px.coerceIn(-ix, ix); val cy = py.coerceIn(-iy, iy); val cz = pz.coerceIn(-iz, iz)
                var nx = px - cx; var ny = py - cy; var nz = pz - cz
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-6f) { nx = f[0]; ny = f[1]; nz = f[2] } else { nx /= l; ny /= l; nz /= l }
                vertex(cx + nx * rr, cy + ny * rr, cz + nz * rr, nx, ny, nz)
            }
            for (j in 0 until n) for (i in 0 until n) {
                val i0 = base + j * (n + 1) + i
                quad(i0, i0 + 1, i0 + n + 2, i0 + n + 1)
            }
        }
    }

    fun sphere(radius: Float, lat: Int = 12, lon: Int = 16) = ellipsoid(radius, radius, radius, lat, lon)

    fun ellipsoid(rx: Float, ry: Float, rz: Float, lat: Int = 12, lon: Int = 16, latFrom: Float = 0f, latTo: Float = 1f) {
        val base = vCount
        for (j in 0..lat) {
            val t = latFrom + (latTo - latFrom) * j / lat
            val phi = PI * t
            val y = cos(phi).toFloat()
            val s = sin(phi).toFloat()
            for (i in 0..lon) {
                val th = 2 * PI * i / lon
                val x = (cos(th) * s).toFloat()
                val z = (sin(th) * s).toFloat()
                vertex(x * rx, y * ry, z * rz, x / rx, y / ry, z / rz)
            }
        }
        for (j in 0 until lat) for (i in 0 until lon) {
            val i0 = base + j * (lon + 1) + i
            quad(i0, i0 + 1, i0 + lon + 2, i0 + lon + 1)
        }
    }

    /** Cylinder along Y, centred. Optionally different top radius (cone / frustum). */
    fun cylinder(radius: Float, height: Float, seg: Int = 16, topRadius: Float = radius, caps: Boolean = true) {
        val hy = height / 2
        val base = vCount
        val slope = (radius - topRadius) / height
        for (i in 0..seg) {
            val th = 2 * PI * i / seg
            val c = cos(th).toFloat(); val s = sin(th).toFloat()
            val l = sqrt(1 + slope * slope)
            vertex(c * radius, -hy, s * radius, c / l, slope / l, s / l)
            vertex(c * topRadius, hy, s * topRadius, c / l, slope / l, s / l)
        }
        for (i in 0 until seg) {
            val i0 = base + i * 2
            quad(i0, i0 + 1, i0 + 3, i0 + 2)
        }
        if (caps) {
            if (topRadius > 0f) disc(topRadius, hy, seg, up = true)
            if (radius > 0f) disc(radius, -hy, seg, up = false)
        }
    }

    private fun disc(radius: Float, y: Float, seg: Int, up: Boolean) {
        val ny = if (up) 1f else -1f
        val c0 = vertex(0f, y, 0f, 0f, ny, 0f)
        val base = vCount
        for (i in 0..seg) {
            val th = 2 * PI * i / seg
            vertex(cos(th).toFloat() * radius, y, sin(th).toFloat() * radius, 0f, ny, 0f)
        }
        for (i in 0 until seg) if (up) tri(c0, base + i + 1, base + i) else tri(c0, base + i, base + i + 1)
    }

    fun capsule(radius: Float, length: Float, seg: Int = 12) {
        val hl = length / 2
        with { translate(0f, hl, 0f); ellipsoid(radius, radius, radius, 6, seg, 0f, 0.5f) }
        cylinder(radius, length, seg, caps = false)
        with { translate(0f, -hl, 0f); ellipsoid(radius, radius, radius, 6, seg, 0.5f, 1f) }
    }

    /** Torus in the XZ plane. */
    fun torus(major: Float, minor: Float, segMajor: Int = 20, segMinor: Int = 8) {
        val base = vCount
        for (i in 0..segMajor) {
            val u = 2 * PI * i / segMajor
            val cu = cos(u).toFloat(); val su = sin(u).toFloat()
            for (j in 0..segMinor) {
                val v = 2 * PI * j / segMinor
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                vertex((major + minor * cv) * cu, minor * sv, (major + minor * cv) * su, cv * cu, sv, cv * su)
            }
        }
        for (i in 0 until segMajor) for (j in 0 until segMinor) {
            val i0 = base + i * (segMinor + 1) + j
            quad(i0, i0 + 1, i0 + segMinor + 2, i0 + segMinor + 1)
        }
    }

    /** Flat ring (annulus) on the XZ plane facing up. */
    fun ring(inner: Float, outer: Float, seg: Int = 40, fromDeg: Float = 0f, toDeg: Float = 360f) {
        val base = vCount
        for (i in 0..seg) {
            val th = Math.toRadians((fromDeg + (toDeg - fromDeg) * i / seg).toDouble())
            val c = cos(th).toFloat(); val s = sin(th).toFloat()
            vertex(c * inner, 0f, s * inner, 0f, 1f, 0f)
            vertex(c * outer, 0f, s * outer, 0f, 1f, 0f)
        }
        for (i in 0 until seg) {
            val i0 = base + i * 2
            quad(i0, i0 + 2, i0 + 3, i0 + 1)
        }
    }

    /** Pie slice on XZ (for cone aim indicators): centre at origin, pointing +X. */
    fun sector(radius: Float, spreadDeg: Float, seg: Int = 24) {
        val c0 = vertex(0f, 0f, 0f, 0f, 1f, 0f)
        val base = vCount
        for (i in 0..seg) {
            val th = Math.toRadians((-spreadDeg / 2 + spreadDeg * i / seg).toDouble())
            vertex(cos(th).toFloat() * radius, 0f, sin(th).toFloat() * radius, 0f, 1f, 0f)
        }
        for (i in 0 until seg) tri(c0, base + i + 1, base + i)
    }

    /** Horizontal quad on XZ, from (x0,z0) to (x1,z1). */
    fun groundQuad(x0: Float, z0: Float, x1: Float, z1: Float, y: Float = 0f) {
        val a0 = vertex(x0, y, z0, 0f, 1f, 0f)
        val a1 = vertex(x1, y, z0, 0f, 1f, 0f)
        val a2 = vertex(x1, y, z1, 0f, 1f, 0f)
        val a3 = vertex(x0, y, z1, 0f, 1f, 0f)
        quad(a0, a3, a2, a1)
    }

    val isEmpty get() = iCount == 0

    fun build(): Mesh {
        val vb = ByteBuffer.allocateDirect(vCount * STRIDE_FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(verts, 0, vCount * STRIDE_FLOATS).position(0)
        val ib = ByteBuffer.allocateDirect(iCount * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(idx, 0, iCount).position(0)
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(2, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vCount * STRIDE_FLOATS * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iCount * 4, ib, GLES30.GL_STATIC_DRAW)
        val stride = STRIDE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 40)
        GLES30.glBindVertexArray(0)
        return Mesh(ids[0], ids[1], ids[2], iCount)
    }

    companion object {
        fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }
}
