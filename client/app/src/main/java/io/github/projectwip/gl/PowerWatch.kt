package io.github.projectwip.gl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Keeps phones cool when nobody is charging them. It follows whether the device is plugged in and how hot Android
 * says it is running, and the 3D views read it every frame to cap their frame rate, and when they start to pick a
 * smaller buffer. Only drawing reads it: the simulation never sees any of it.
 *
 * - On battery the 3D menus (the lobby, the fighter stage) draw at 30 frames a second: they are decoration.
 * - On battery a match draws at no more than 60, whatever the setting.
 * - Warm (Android's LIGHT) or hotter: menus 30, matches 60, and new views start with a smaller buffer.
 * - SEVERE or hotter: everything 30, until it cools down.
 *
 * Android's thermal status only moves once a phone is already hot, so it also reads Android's thermal headroom (a
 * forecast of how close the phone is to being throttled: 1 is throttling) every few seconds, and backs off before
 * that ([level]): WARM draws a running match's buffer smaller (no more than 720 lines), HOT also draws it at 30
 * frames a second. It steps back up by itself as the phone cools (with a margin, so it doesn't flip back and forth).
 * Views that are already running hear about a change through [listen].
 */
object PowerWatch {
    @Volatile var charging = true
        private set
    /** Android's thermal status (PowerManager.THERMAL_STATUS_*), 0 = none. */
    @Volatile var thermal = 0
        private set
    private var started = false
    /** Android's forecast thermal headroom (0 cool .. 1 throttling; NaN where it can't say). */
    @Volatile var headroom = Float.NaN
        private set
    /** How far it has backed off for heat: [COOL], [WARM] or [HOT]. */
    @Volatile var level = COOL
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    const val COOL = 0
    const val WARM = 1
    const val HOT = 2
    /**
     * Headroom at which it backs off to WARM and to HOT, and how far under that it has to cool to step back. Measured on
     * the user's S26: a cool phone in a match reads about 0.64 (skin 34 C) and 1.0 is its SEVERE line (45 C), so WARM
     * is about 37.5 C, just before Android's own LIGHT, and HOT about 41 C.
     */
    private const val WARM_AT = 0.75f
    private const val HOT_AT = 0.88f
    private const val COOLER = 0.06f
    private const val POLL_MS = 5_000L

    /** [block] runs on the main thread whenever [level], [charging] or [thermal] changes; it returns the way to stop. */
    fun listen(block: () -> Unit): () -> Unit { listeners += block; return { listeners -= block } }

    private fun changed() { main.post { for (l in listeners) l() } }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        try {
            val sticky = app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { i?.let { update(it) } }
            }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            sticky?.let { update(it) }
        } catch (e: Throwable) { Log.w("PowerWatch", "no battery state", e) }
        if (Build.VERSION.SDK_INT >= 29) try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermal = pm.currentThermalStatus
            pm.addThermalStatusListener(app.mainExecutor) { s ->
                if (s != thermal) { Log.i("PowerWatch", "thermal status $thermal -> $s"); thermal = s; changed() }
            }
        } catch (e: Throwable) { Log.w("PowerWatch", "no thermal status", e) }
        if (Build.VERSION.SDK_INT >= 30) try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val poll = object : Runnable {
                override fun run() {
                    try { updateHeadroom(pm.getThermalHeadroom(10)) } catch (e: Throwable) { Log.w("PowerWatch", "no headroom", e); return }
                    main.postDelayed(this, POLL_MS)
                }
            }
            main.postDelayed(poll, 1_000L)
        } catch (e: Throwable) { Log.w("PowerWatch", "no thermal headroom", e) }
    }

    private fun update(i: Intent) {
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val now = plugged > 0
        if (now != charging) { Log.i("PowerWatch", if (now) "charging" else "on battery"); charging = now; changed() }
    }

    private fun updateHeadroom(h: Float) {
        if (h.isNaN()) return // asked too soon, or the phone can't forecast: keep what we had
        headroom = h
        val next = when (level) {
            HOT -> if (h < HOT_AT - COOLER) (if (h >= WARM_AT) WARM else COOL) else HOT
            WARM -> if (h >= HOT_AT) HOT else if (h < WARM_AT - COOLER) COOL else WARM
            else -> if (h >= HOT_AT) HOT else if (h >= WARM_AT) WARM else COOL
        }
        if (Log.isLoggable("Perf", Log.DEBUG)) Log.i("PowerWatch", "headroom ${"%.2f".format(h)} level $next")
        if (next != level) {
            Log.i("PowerWatch", "heat level $level -> $next (headroom ${"%.2f".format(h)})")
            level = next
            changed()
        }
    }

    /** The frame cap for a 3D view that asked for [fps] (0 = uncapped); [menu] for the lobby and stage. */
    fun cap(fps: Int, menu: Boolean): Int {
        var c = if (fps <= 0) 1000 else fps
        val warm = thermal >= 1 || level >= WARM // LIGHT, or its headroom running out
        if (menu && (!charging || warm)) c = minOf(c, 30)
        if (!menu && (!charging || warm)) c = minOf(c, 60)
        if (thermal >= 3 || level >= HOT) c = minOf(c, 30) // SEVERE, or close to throttling
        return if (c >= 1000) fps else c
    }

    /** The tallest buffer a view starting now should draw, given its preset's [maxLines]. */
    fun maxLines(maxLines: Int): Int = when {
        thermal >= 2 || level >= WARM -> minOf(maxLines, 720) // MODERATE, or its headroom running out
        thermal >= 1 || !charging -> minOf(maxLines, 900)
        else -> maxLines
    }
}
