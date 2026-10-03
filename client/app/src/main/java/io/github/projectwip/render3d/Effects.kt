package io.github.projectwip.render3d

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Batches camera-facing glow sprites into one dynamic buffer and one draw call. */
class SpriteBatch(private val max: Int = 2048) {
    private val floatsPerVertex = 10
    private val data = FloatArray(max * 4 * floatsPerVertex)
    private val buffer: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val vao: Int
    private val vbo: Int
    private val ibo: Int
    private var count = 0

    init {
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(2, ids, 1)
        vao = ids[0]; vbo = ids[1]; ibo = ids[2]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val idx = IntArray(max * 6)
        for (i in 0 until max) {
            val v = i * 4
            idx[i * 6] = v; idx[i * 6 + 1] = v + 1; idx[i * 6 + 2] = v + 2
            idx[i * 6 + 3] = v; idx[i * 6 + 4] = v + 2; idx[i * 6 + 5] = v + 3
        }
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(idx)
        ib.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GLES30.GL_STATIC_DRAW)
        val stride = floatsPerVertex * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 20)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 36)
        GLES30.glBindVertexArray(0)
    }

    fun begin() { count = 0 }

    fun add(x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float, a: Float) {
        if (count >= max) return
        var o = count * 4 * floatsPerVertex
        for (k in 0 until 4) {
            val cx = if (k == 0 || k == 3) -1f else 1f
            val cy = if (k < 2) -1f else 1f
            data[o] = x; data[o + 1] = y; data[o + 2] = z
            data[o + 3] = cx; data[o + 4] = cy
            data[o + 5] = r; data[o + 6] = g; data[o + 7] = b; data[o + 8] = a
            data[o + 9] = size
            o += floatsPerVertex
        }
        count++
    }

    fun flush() {
        if (count == 0) return
        val n = count * 4 * floatsPerVertex
        buffer.position(0)
        buffer.put(data, 0, n).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 4, buffer)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count * 6, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        count = 0
    }
}

/** CPU particles in world space (sparks with gravity, rising smoke, rings of glow). */
class Particles3D(private val max: Int = 1200) {
    private val x = FloatArray(max); private val y = FloatArray(max); private val z = FloatArray(max)
    private val vx = FloatArray(max); private val vy = FloatArray(max); private val vz = FloatArray(max)
    private val life = FloatArray(max); private val maxLife = FloatArray(max)
    private val size = FloatArray(max); private val grow = FloatArray(max); private val gravity = FloatArray(max)
    private val r = FloatArray(max); private val g = FloatArray(max); private val b = FloatArray(max); private val a = FloatArray(max)
    private val additive = BooleanArray(max)
    private var n = 0

    fun spawn(
        px: Float, py: Float, pz: Float, pvx: Float, pvy: Float, pvz: Float, l: Float, s: Float,
        color: Int, alpha: Float = 1f, grav: Float = 0f, growth: Float = 0f, add: Boolean = true,
    ) {
        if (n >= max) return
        x[n] = px; y[n] = py; z[n] = pz; vx[n] = pvx; vy[n] = pvy; vz[n] = pvz
        life[n] = l; maxLife[n] = l; size[n] = s; grow[n] = growth; gravity[n] = grav
        r[n] = ((color shr 16) and 0xFF) / 255f; g[n] = ((color shr 8) and 0xFF) / 255f; b[n] = (color and 0xFF) / 255f; a[n] = alpha
        additive[n] = add
        n++
    }

    fun update(dt: Float) {
        var i = 0
        while (i < n) {
            life[i] -= dt
            if (life[i] <= 0f) { swapRemove(i); continue }
            vy[i] -= gravity[i] * dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
            if (y[i] < 0.03f && gravity[i] > 0f) { y[i] = 0.03f; vy[i] = -vy[i] * 0.35f; vx[i] *= 0.6f; vz[i] *= 0.6f }
            val drag = 1f - 2.2f * dt
            vx[i] *= drag; vz[i] *= drag
            size[i] += grow[i] * dt
            i++
        }
    }

    private fun swapRemove(i: Int) {
        n--
        x[i] = x[n]; y[i] = y[n]; z[i] = z[n]; vx[i] = vx[n]; vy[i] = vy[n]; vz[i] = vz[n]
        life[i] = life[n]; maxLife[i] = maxLife[n]; size[i] = size[n]; grow[i] = grow[n]; gravity[i] = gravity[n]
        r[i] = r[n]; g[i] = g[n]; b[i] = b[n]; a[i] = a[n]; additive[i] = additive[n]
    }

    fun emit(batch: SpriteBatch, wantAdditive: Boolean) {
        for (i in 0 until n) {
            if (additive[i] != wantAdditive) continue
            val f = life[i] / maxLife[i]
            batch.add(x[i], y[i], z[i], size[i] * (0.35f + 0.65f * f), r[i], g[i], b[i], a[i] * f)
        }
    }
}
