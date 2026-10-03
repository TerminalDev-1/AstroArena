package io.github.projectwip.net

import android.util.Log
import io.github.projectwip.data.Versions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** A release on GitHub that is newer than the running game. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)

/**
 * Asks GitHub which releases of the game exist. This is the only thing the game ever sends over the network:
 * one anonymous GET, no account, no device details.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/TerminalDev-1/AstroArena/releases?per_page=15"

    /**
     * The newest published release if it is newer than [current], or null when the game is up to date or GitHub
     * can't be reached (no connection, rate limit): being offline must never lock anyone out. Blocking; call it
     * off the main thread.
     */
    fun check(current: String, timeoutMs: Int = 4000): UpdateInfo? = try {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AstroArena/$current")
        val body = try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        body?.let { newest(JSONArray(it), current) }
    } catch (e: Exception) {
        Log.i("Updater", "update check skipped: $e")
        null
    }

    private fun newest(releases: JSONArray, current: String): UpdateInfo? {
        var best: UpdateInfo? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft")) continue
            val tag = r.optString("tag_name")
            if (!Versions.isNewer(tag, best?.version ?: current)) continue
            // A release without an APK can't be installed, so it doesn't count.
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: continue
            best = UpdateInfo(tag.removePrefix("v"), r.optString("body"), apk, r.optString("html_url"))
        }
        return best
    }
}
