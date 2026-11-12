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
 */
object PowerWatch {
    @Volatile var charging = true
        private set
    /** Android's thermal status (PowerManager.THERMAL_STATUS_*), 0 = none. */
    @Volatile var thermal = 0
        private set
    private var started = false

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
                if (s != thermal) Log.i("PowerWatch", "thermal status $thermal -> $s")
                thermal = s
            }
        } catch (e: Throwable) { Log.w("PowerWatch", "no thermal status", e) }
    }

    private fun update(i: Intent) {
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val now = plugged > 0
        if (now != charging) Log.i("PowerWatch", if (now) "charging" else "on battery")
        charging = now
    }

    /** The frame cap for a 3D view that asked for [fps] (0 = uncapped); [menu] for the lobby and stage. */
    fun cap(fps: Int, menu: Boolean): Int {
        var c = if (fps <= 0) 1000 else fps
        val warm = thermal >= 1 // LIGHT
        if (menu && (!charging || warm)) c = minOf(c, 30)
        if (!menu && (!charging || warm)) c = minOf(c, 60)
        if (thermal >= 3) c = minOf(c, 30) // SEVERE
        return if (c >= 1000) fps else c
    }

    /** The tallest buffer a view starting now should draw, given its preset's [maxLines]. */
    fun maxLines(maxLines: Int): Int = when {
        thermal >= 2 -> minOf(maxLines, 720) // MODERATE
        thermal >= 1 || !charging -> minOf(maxLines, 900)
        else -> maxLines
    }
}
