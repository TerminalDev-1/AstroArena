package io.github.projectwip.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log

/** What a GL thread drives. All methods run on the GL thread. */
interface GlRenderer {
    fun onCreated()
    fun onSize(width: Int, height: Int)
    /** Draw one frame. [dt] is seconds since the previous frame (clamped). */
    fun onFrame(dt: Float)
    fun onDestroyed() {}
}

/** Minimal EGL setup (ES 3.0, RGBA8888, depth 24, 4x MSAA when available). */
class Egl(transparent: Boolean) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext

    init {
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        config = chooseConfig(4) ?: chooseConfig(0) ?: error("No EGL config")
        val attrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attrs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    private fun chooseConfig(samples: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
            EGL14.EGL_SAMPLES, samples,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) configs[0] else null
    }

    fun windowSurface(nativeWindow: Any): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, nativeWindow, intArrayOf(EGL14.EGL_NONE), 0)

    fun pbuffer(w: Int, h: Int): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)

    fun makeCurrent(s: EGLSurface) = check(EGL14.eglMakeCurrent(display, s, s, context)) { "eglMakeCurrent failed" }

    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)

    fun destroySurface(s: EGLSurface) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
    }
}

/**
 * A dedicated render thread bound to one window surface (a SurfaceView's Surface or a TextureView's
 * SurfaceTexture). eglSwapBuffers paces the loop to the display's vsync.
 */
class GlThread(
    private val nativeWindow: Any,
    private val renderer: GlRenderer,
    private val name: String,
    private val transparent: Boolean = false,
) : Thread(name) {
    @Volatile private var running = true
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var sizeChanged = false
    @Volatile var paused = false
    private val perfLog = Log.isLoggable("Perf", Log.DEBUG)
    private var perfFrames = 0
    private var perfTime = 0f
    private var perfWorst = 0f
    private var perfSlow = 0
    private var perfDraw = 0f
    private var perfSwap = 0f

    fun resize(w: Int, h: Int) { width = w; height = h; sizeChanged = true }

    fun shutdown() {
        running = false
        join(1500)
    }

    override fun run() {
        val egl = try { Egl(transparent) } catch (e: Exception) { Log.e(name, "EGL init failed", e); return }
        val surface = egl.windowSurface(nativeWindow)
        egl.makeCurrent(surface)
        try {
            renderer.onCreated()
            var last = System.nanoTime()
            while (running) {
                if (sizeChanged) {
                    sizeChanged = false
                    GLES30.glViewport(0, 0, width, height)
                    renderer.onSize(width, height)
                }
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                if (paused) { sleep(30); continue }
                if (perfLog) {
                    // Frame pacing every two seconds: `adb shell setprop log.tag.Perf DEBUG`, then `adb logcat -s Perf`.
                    perfFrames++; perfTime += dt; if (dt > perfWorst) perfWorst = dt
                    if (dt > 0.02f) perfSlow++
                    if (perfTime >= 2f) {
                        Log.i("Perf", "$name fps=${(perfFrames / perfTime).toInt()} worst=${(perfWorst * 1000).toInt()}ms over20ms=$perfSlow" +
                            " draw=${"%.1f".format(perfDraw / perfFrames)}ms swap=${"%.1f".format(perfSwap / perfFrames)}ms")
                        perfFrames = 0; perfTime = 0f; perfWorst = 0f; perfSlow = 0; perfDraw = 0f; perfSwap = 0f
                    }
                }
                val f0 = System.nanoTime()
                renderer.onFrame(dt)
                val f1 = System.nanoTime()
                val swapped = egl.swap(surface)
                perfDraw += (f1 - f0) / 1e6f; perfSwap += (System.nanoTime() - f1) / 1e6f
                if (!swapped) {
                    Log.w(name, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                    sleep(16)
                }
            }
        } catch (e: Exception) {
            Log.e(name, "render thread crashed", e)
        } finally {
            try { renderer.onDestroyed() } catch (_: Exception) {}
            egl.destroySurface(surface)
            egl.release()
        }
    }
}
