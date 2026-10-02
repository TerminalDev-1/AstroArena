package io.github.projectwip.render3d

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import io.github.projectwip.gl.Program

/** Directional-light shadow map (hardware PCF via a depth-compare texture). */
class ShadowMap(val size: Int) {
    val texture: Int
    val fbo: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.e("ShadowMap", "incomplete: $status")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun begin() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 4f)
    }

    /** Ends the shadow pass and binds [target] (0 = the window) for the main pass. */
    fun end(target: Int = 0) {
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
    }
}

/** Shared look: light direction and the toon lighting uniforms. */
object Toon {
    /** Direction light travels: from upper-left-front (camera side) toward the back, so faces are lit. */
    val LIGHT = floatArrayOf(0.45f, -1f, -0.5f).let { v ->
        val l = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
    val INK = floatArrayOf(0.106f, 0.063f, 0.208f)
    val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Binds [p] and sets every uniform of the lit shader to the house style. */
    fun setup(p: Program, viewProj: FloatArray, lightVP: FloatArray, eye: FloatArray, time: Float, shadow: ShadowMap?) {
        p.use()
        p.mat4("uViewProj", viewProj)
        p.mat4("uLightVP", lightVP)
        p.v3("uLightDir", LIGHT[0], LIGHT[1], LIGHT[2])
        p.v3("uCamPos", eye[0], eye[1], eye[2])
        p.v3("uSky", 0.66f, 0.66f, 0.82f)
        p.v3("uGround", 0.36f, 0.3f, 0.42f)
        p.v3("uSun", 0.62f, 0.58f, 0.5f)
        p.f("uTime", time)
        p.f("uSway", 0f)
        p.f("uOutline", 0f)
        p.f("uFlash", 0f)
        p.f("uEmissive", 0f)
        p.f("uRim", 0.35f)
        p.f("uShadowOn", if (shadow != null) 1f else 0f)
        p.f("uShadowTexel", 1f / (shadow?.size ?: 1024))
        p.f("uRevealOn", 0f)
        p.i("uMode", 0)
        p.i("uShadow", 0)
        p.v4("uTint", 1f, 1f, 1f, 1f)
        p.mat4("uModel", IDENTITY)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadow?.texture ?: 0)
    }
}
