package io.github.projectwip.net

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.projectwip.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps a crash for the server. A crash in the game's own code is written to `files/crash.json` as it happens;
 * one inside native code (a graphics driver, say) or a kill for lack of memory can't be caught, so on the next
 * start Android's own record of why the game last ended is read too (Android 11 and later). The next time the
 * server answers, [sendPending] hands each report in (`POST /v1/crashes`) and forgets it.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val FILE = "crash.json"
    private const val PREFS = "crash"
    private const val MAX_CHARS = 30_000

    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences

    /** The GPU, as its driver names itself; noted by the GL thread so a report can say what it ran on. */
    @Volatile var gpu: String = ""
        set(value) {
            field = value
            if (::prefs.isInitialized && value.isNotBlank() && prefs.getString("gpu", "") != value) prefs.edit().putString("gpu", value).apply()
        }

    fun install(context: Context) {
        dir = context.filesDir
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (gpu.isBlank()) gpu = prefs.getString("gpu", "").orEmpty()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                write(report("Uncaught on thread ${thread.name}\n$trace", "java"))
            } catch (_: Throwable) {
                // Never let the reporter get in the way of the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
        try {
            noteLastExit(context)
        } catch (e: Throwable) {
            Log.w(TAG, "couldn't read why the game last ended", e)
        }
    }

    /** A problem that didn't crash the game but should be looked at (the graphics failing to start, say). */
    fun record(what: String, error: Throwable) {
        if (!::dir.isInitialized) return
        try {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            write(report("$what\n$trace", "problem"))
        } catch (_: Throwable) {}
    }

    /** Every saved report, as text a player can share (they are sent to the server too, once it answers). */
    fun pendingText(): String? {
        if (!::dir.isInitialized) return null
        val list = pending()
        if (list.isEmpty()) return null
        return list.joinToString("\n\n") { o ->
            "AstroArena ${o.optString("kind")} report · build ${o.optString("version")} · ${o.optString("crashed")}\n" +
                "${o.optString("device")} · Android ${o.optString("android")} · ${o.optString("gpu")}\n" + o.optString("report")
        }.take(12_000)
    }

    /** When the reports waiting now were last offered to the player to share, so each lot is offered once. */
    fun shouldOffer(): Boolean {
        if (!::dir.isInitialized) return false
        val file = File(dir, FILE)
        if (!file.exists()) return false
        val stamp = file.lastModified()
        if (prefs.getLong("offered", 0L) >= stamp) return false
        prefs.edit().putLong("offered", stamp).apply()
        return true
    }

    private fun report(text: String, kind: String) = JSONObject()
        .put("report", text.take(MAX_CHARS))
        .put("kind", kind)
        .put("version", BuildConfig.BUILD)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        .put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        .put("gpu", gpu.ifBlank { prefs.getString("gpu", "").orEmpty() })
        .put("crashed", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))

    @Synchronized private fun pending(): MutableList<JSONObject> {
        val file = File(dir, FILE)
        if (!file.exists()) return mutableListOf()
        return try {
            val a = org.json.JSONArray(file.readText())
            MutableList(a.length()) { a.getJSONObject(it) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized private fun save(list: List<JSONObject>) {
        val file = File(dir, FILE)
        if (list.isEmpty()) { file.delete(); return }
        file.writeText(org.json.JSONArray(list.takeLast(5)).toString())
    }

    @Synchronized private fun write(o: JSONObject) = save(pending() + o)

    /** Android's record of how the game last ended, when it was a crash we couldn't catch or a kill for memory. */
    private fun noteLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val seen = prefs.getLong("lastExit", 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
        if (exits.isEmpty()) return
        prefs.edit().putLong("lastExit", exits.maxOf { it.timestamp }).apply()
        if (seen == 0L) return // the first run with the reporter: older exits are from before it, and may be long gone
        for (exit in exits) {
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
                ApplicationExitInfo.REASON_ANR -> "frozen"
                else -> continue // a Java crash is already in crash.json; anything else isn't a crash
            }
            val trace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && exit.reason != ApplicationExitInfo.REASON_LOW_MEMORY) {
                try { exit.traceInputStream?.use { it.readBytes().toString(Charsets.ISO_8859_1).filter { c -> c == '\n' || c in ' '..'~' } } } catch (_: Exception) { null }
            } else null
            val text = buildString {
                append("Exit: ").append(kind).append(" — ").append(exit.description ?: "no description")
                append("\nStatus ").append(exit.status).append(", importance ").append(exit.importance)
                append(", memory ").append(exit.pss / 1024).append(" MB (pss), ").append(exit.rss / 1024).append(" MB (rss)\n")
                if (!trace.isNullOrBlank()) append(trace)
            }
            write(report(text, kind).put("crashed", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(exit.timestamp))))
        }
    }

    /**
     * Hands every saved crash to the server. [post] sends one and gives the reply's HTTP code, or null when the
     * server couldn't be reached; a report is only kept for another try in that case.
     */
    fun sendPending(post: (JSONObject) -> Int?) {
        if (!::dir.isInitialized) return
        val list = pending()
        if (list.isEmpty()) return
        val left = list.filter { post(it) == null }
        save(left)
        Log.i(TAG, "sent ${list.size - left.size} crash report(s)")
    }
}
