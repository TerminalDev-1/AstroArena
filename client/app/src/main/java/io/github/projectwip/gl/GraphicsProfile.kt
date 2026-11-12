package io.github.projectwip.gl

import android.app.ActivityManager
import android.content.Context
import io.github.projectwip.data.GraphicsQuality
import io.github.projectwip.data.Settings

/**
 * What a graphics preset means to the renderers. Only drawing reads it: the simulation (and so the referee and
 * lockstep) never sees any of it.
 */
data class GraphicsProfile(
    val quality: GraphicsQuality,
    /** The tallest buffer drawn, in pixels; a taller screen is drawn smaller and scaled up by the display hardware. */
    val maxLines: Int,
    /** Anti-aliasing samples (0 = off). */
    val msaa: Int,
    /** Shadow map size; 0 = no shadows. */
    val shadowSize: Int,
    /** Ink outlines round the arena's walls and crates (fighters always keep theirs). */
    val arenaOutlines: Boolean,
    /** Share of particles kept, 0..1. */
    val effects: Float,
    val frameRate: Int,
) {
    /** Buffer size for a view of [w] x [h]: never bigger than the view. */
    fun bufferSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val short = minOf(w, h)
        // On battery or running warm, the view is drawn smaller (PowerWatch).
        val lines = PowerWatch.maxLines(maxLines)
        if (short <= lines) return w to h
        val k = lines.toFloat() / short
        return (w * k).toInt().coerceAtLeast(1) to (h * k).toInt().coerceAtLeast(1)
    }

    /** The 3D menus behind the UI are decoration: never more than 60 frames a second. */
    val menuFrameRate get() = minOf(frameRate, 60)

    companion object {
        /** What the renderers use now (set from the settings on the main thread, read when a view starts). */
        @Volatile var current: GraphicsProfile = of(GraphicsQuality.MEDIUM, 60)

        fun of(q: GraphicsQuality, frameRate: Int): GraphicsProfile = when (q) {
            GraphicsQuality.LOW -> GraphicsProfile(q, maxLines = 720, msaa = 0, shadowSize = 0, arenaOutlines = false, effects = 0.5f, frameRate = frameRate)
            GraphicsQuality.HIGH -> GraphicsProfile(q, maxLines = 1440, msaa = 4, shadowSize = 2048, arenaOutlines = true, effects = 1f, frameRate = frameRate)
            else -> GraphicsProfile(GraphicsQuality.MEDIUM, maxLines = 1080, msaa = 2, shadowSize = 1024, arenaOutlines = true, effects = 0.8f, frameRate = frameRate)
        }

        /**
         * The preset AUTO stands for on this device. Medium for most phones (strong phones get hot at High in long
         * sessions), Low for phones short of memory.
         */
        fun autoFor(context: Context): GraphicsQuality {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return GraphicsQuality.MEDIUM
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return if (am.isLowRamDevice || info.totalMem < 4L * 1024 * 1024 * 1024) GraphicsQuality.LOW else GraphicsQuality.MEDIUM
        }

        fun resolve(context: Context, s: Settings): GraphicsProfile =
            of(if (s.graphics == GraphicsQuality.AUTO) autoFor(context) else s.graphics, s.frameRate)

        /** Frame rates this screen can show, from 30 up (90 and 120 only where the panel has them). */
        fun frameRates(context: Context): List<Int> {
            val display = try { if (android.os.Build.VERSION.SDK_INT >= 30) context.display else null } catch (_: Throwable) { null }
            val top = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
            return listOf(30, 60, 90, 120).filter { it <= 60 || top >= it - 1f }
        }
    }
}
