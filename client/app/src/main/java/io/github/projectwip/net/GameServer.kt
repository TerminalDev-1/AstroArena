package io.github.projectwip.net

import android.content.Context
import android.util.Log
import io.github.projectwip.ai.BotProfile
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.MatchReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** What the game knows about the server right now. */
data class ServerStatus(
    /** The last attempt to reach it worked. */
    val online: Boolean = false,
    /** False when the server has turned this version of the game away. */
    val supported: Boolean = true,
    /** Why this version was turned away. */
    val message: String = "",
    /** A short message from the server for the home screen. */
    val notice: String = "",
    val url: String = "",
)

/** A match as the server set it up. */
data class MatchPlan(val matchId: Long, val seed: Long, val botNames: List<String>)

/** A real player on the server's leaderboard. */
data class RemotePlayer(val id: String, val name: String, val cups: Int, val fighter: FighterId)

/**
 * The game's connection to the AstroArena server (see the `server/` directory of the repository).
 *
 * Everything here is best effort. If the server can't be reached the game carries on exactly as it did before
 * there was one: saves stay on the device, matches are set up locally, bots use their built-in behaviour and the
 * leaderboard shows only simulated rivals. Nothing in this class may ever block or break offline play.
 *
 * The blocking calls ([connect], [planMatch], [leaderboard]) must be made off the main thread.
 */
class GameServer(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "server-io").apply { isDaemon = true } }
    private val pendingSave = AtomicReference<JSONObject?>(null)
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    @Volatile private var baseUrl = ""
    @Volatile private var version = ""

    /** This install's id on the current server, once it has registered. */
    val playerId: String? get() = prefs.getString("id@$baseUrl", null)
    private val token: String? get() = prefs.getString("token@$baseUrl", null)

    private class Reply(val code: Int, val body: JSONObject?)

    /** One request. Null means the server couldn't be reached at all. */
    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = false, timeoutMs: Int = 3000): Reply? = try {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-Client-Version", version)
        if (auth) c.setRequestProperty("Authorization", "Bearer ${token.orEmpty()}")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            Reply(code, text?.let { runCatching { JSONObject(it) }.getOrNull() })
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.i(TAG, "$method $path failed: $e")
        null
    }

    private fun register(name: String): Boolean {
        val r = call("POST", "/v1/players", JSONObject().put("name", name).put("version", version)) ?: return false
        val id = r.body?.optString("id").orEmpty()
        val tok = r.body?.optString("token").orEmpty()
        if (r.code != 201 || id.isEmpty() || tok.isEmpty()) return false
        prefs.edit().putString("id@$baseUrl", id).putString("token@$baseUrl", tok).apply()
        return true
    }

    /**
     * Says hello to the server at [url]: checks this [gameVersion] is supported, signs in (registering on first
     * contact), and fetches the live bot settings. Returns the save the server holds for this player, if any.
     * Updates [status] either way.
     */
    fun connect(url: String, gameVersion: String, playerName: String): JSONObject? {
        baseUrl = url.trim().trimEnd('/')
        version = gameVersion
        if (baseUrl.isEmpty()) { _status.value = ServerStatus(); return null }
        val hello = call("GET", "/v1/status?version=" + java.net.URLEncoder.encode(gameVersion, "UTF-8"))
        val info = hello?.body
        if (hello == null || hello.code != 200 || info == null) {
            _status.value = ServerStatus(online = false, url = baseUrl)
            BotProfile.overrides = emptyMap()
            return null
        }
        val state = ServerStatus(
            online = true, supported = info.optBoolean("supported", true), message = info.optString("message"),
            notice = info.optString("notice"), url = baseUrl,
        )
        if (!state.supported) { _status.value = state; return null }

        if (token == null && !register(playerName)) { _status.value = state.copy(online = false); return null }
        call("GET", "/v1/config")?.body?.optJSONObject("bots")?.let { applyBots(it) }

        var saved = call("GET", "/v1/save", auth = true)
        if (saved?.code == 401) {
            // The server doesn't know this token (its database was reset): sign up again.
            prefs.edit().remove("id@$baseUrl").remove("token@$baseUrl").apply()
            if (!register(playerName)) { _status.value = state.copy(online = false); return null }
            saved = call("GET", "/v1/save", auth = true)
        }
        _status.value = state
        return if (saved?.code == 200) saved.body?.optJSONObject("save") else null
    }

    private fun applyBots(bots: JSONObject) {
        val out = HashMap<BotDifficulty, BotProfile>()
        for (d in BotDifficulty.entries) {
            val o = bots.optJSONObject(d.name) ?: continue
            val values = HashMap<String, Any>()
            for (key in o.keys()) values[key] = o.get(key)
            out[d] = BotProfile.builtIn(d).withOverrides(values)
        }
        BotProfile.overrides = out
    }

    /** Uploads the save in the background. Only the newest one waiting is ever sent. */
    fun pushSave(save: JSONObject) {
        if (!_status.value.online || !_status.value.supported) return
        if (pendingSave.getAndSet(save) != null) return // an upload is already queued; it will pick this one up
        worker.execute {
            val latest = pendingSave.getAndSet(null) ?: return@execute
            val r = call("PUT", "/v1/save", JSONObject().put("save", latest), auth = true)
            if (r == null) _status.value = _status.value.copy(online = false)
        }
    }

    /** Asks the server to set a match up. Null (quickly) when there is no server to ask. */
    fun planMatch(mode: GameMode, fighter: FighterId, level: Int, difficulty: BotDifficulty): MatchPlan? {
        if (!_status.value.online || !_status.value.supported) return null
        val body = JSONObject().put("mode", mode.name).put("fighter", fighter.name).put("level", level).put("difficulty", difficulty.name)
        val r = call("POST", "/v1/matches", body, auth = true, timeoutMs = 1500)
        val o = r?.body
        if (r == null || r.code != 201 || o == null) {
            if (r == null) _status.value = _status.value.copy(online = false)
            return null
        }
        val names = o.optJSONArray("botNames")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return MatchPlan(o.optLong("matchId"), o.optLong("seed"), names)
    }

    /** Tells the server how a match it planned went. */
    fun reportMatch(matchId: Long, report: MatchReport) {
        if (matchId <= 0 || !_status.value.online) return
        val body = JSONObject().put("outcome", report.outcome.name).put("placement", report.placement)
            .put("kos", report.kos).put("deaths", report.deaths).put("damage", report.damageDealt)
        worker.execute { call("POST", "/v1/matches/$matchId/result", body, auth = true) }
    }

    /** Real players by Cups, or null when the server can't be asked. */
    fun leaderboard(limit: Int = 50): List<RemotePlayer>? {
        if (!_status.value.online) return null
        val players = call("GET", "/v1/leaderboard?limit=$limit")?.body?.optJSONArray("players") ?: return null
        return (0 until players.length()).mapNotNull { i ->
            val p = players.optJSONObject(i) ?: return@mapNotNull null
            RemotePlayer(
                p.optString("id"), p.optString("name", "Player"), p.optInt("cups"),
                FighterId.entries.firstOrNull { it.name == p.optString("fighter") } ?: FighterId.JUNO,
            )
        }
    }

    private companion object {
        const val TAG = "GameServer"
    }
}
