package io.github.projectwip.net

import android.content.Context
import android.util.Log
import io.github.projectwip.ai.BotProfile
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.CapsuleResult
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.data.Currency
import io.github.projectwip.data.CustomOffer
import io.github.projectwip.data.FighterProgress
import io.github.projectwip.data.ServerProfile
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.JudgedResult
import io.github.projectwip.data.MatchOutcome
import io.github.projectwip.data.MatchReport
import io.github.projectwip.data.Reward
import io.github.projectwip.data.ServerVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** This player as the server sees them. The server owns these numbers; the game only shows them. */
data class Account(
    val id: String,
    /** The server lists this player as a developer: the debug menu and the difficulty choice are theirs. */
    val developer: Boolean,
    val cups: Int,
    /** Place on the server's leaderboard (1 = top), out of [players] accounts. */
    val rank: Int,
    val players: Int,
    /** Unopened Glitch Drops. */
    val drops: Int,
    val dropsLeftToday: Int,
    /** The bot difficulty the server gives ordinary players. */
    val difficulty: BotDifficulty,
    /** Currencies, fighters and claimed rewards. */
    val profile: ServerProfile? = null,
    /** The shop deals running right now, with how many times this player has bought each. */
    val deals: List<CustomOffer> = emptyList(),
    /** The difficulties the server lets this player pick. */
    val difficulties: List<BotDifficulty> = BotDifficulty.entries,
    /** Today's offers, picked by the server; their [CustomOffer.id] is their place in the list. */
    val dailyOffers: List<CustomOffer> = emptyList(),
    /** The server's day number (days since 1970 on the server's clock). */
    val day: Long = -1,
    /** When the server's day ends and the shop changes, on this device's clock (ms). */
    val dayEndsAt: Long = 0,
    val giftAvailable: Boolean = true,
)

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
    /** Null until the server has said who this player is. */
    val account: Account? = null,
    /** The server's owner has disabled this account: the game is offline (practice) until they let it back in. */
    val disabled: Boolean = false,
    /** Why, in the owner's words ("" if they gave none). */
    val disabledReason: String = "",
    /** When it ends by itself, on this device's clock (ms since 1970); 0 = when the owner says. */
    val disabledUntil: Long = 0,
)

/** A match as the server set it up. */
data class MatchPlan(
    val matchId: Long, val seed: Long, val botNames: List<String>, val difficulty: BotDifficulty?,
    /** The level the server holds for the fighter; the match is played (and replayed) at this level. */
    val level: Int?,
)

/** One item on the News tab, as the server wrote it. */
data class NewsItem(val title: String, val date: String, val tag: String, val text: String)

/** A real player on the server's leaderboard. */
data class RemotePlayer(val id: String, val name: String, val cups: Int, val fighter: FighterId)

/**
 * The game's connection to the AstroArena server (see the `server/` directory of the repository).
 *
 * The server is in charge of Cups, Glitch Drops, Bolts, Prisms, fighters, the shop and its deals, the bot
 * difficulty and who gets the debug menu. Without it the game still plays, in offline mode: matches are set up
 * on the device, but nothing is earned and nothing can be bought, upgraded, claimed or opened.
 *
 * A match is still played on the device, but its result is the server's: the device hands in what the player did
 * and the server replays the match itself ([reportMatch]). Nothing here may ever block
 * or break offline play.
 *
 * The blocking calls ([connect], [syncSave], [planMatch], [reportMatch], [openDrop], [leaderboard]) must be made
 * off the main thread.
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
    private val usable get() = _status.value.online && _status.value.supported

    /** Why the server refused the last request, in its own words ("" if it didn't). */
    @Volatile var lastError = ""
        private set

    /** Has this install already got an account on the server at [url]? */
    fun hasAccount(url: String): Boolean = prefs.contains("token@" + url.trim().trimEnd('/'))

    private class Reply(val code: Int, val body: JSONObject?) {
        /** The server's owner has disabled this account. */
        val disabled get() = code == 403 && body?.optBoolean("disabled") == true
    }

    /** [base], shut out as [body] says. The end is the server's time; it is moved onto this device's clock. */
    private fun shutOut(base: ServerStatus, body: JSONObject?): ServerStatus {
        val until = body?.optLong("until") ?: 0L
        return base.copy(
            online = false, disabled = true, disabledReason = body?.optString("reason").orEmpty(),
            disabledUntil = if (until > 0) System.currentTimeMillis() + until - body!!.optLong("now", until) else 0,
        )
    }

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
            val reply = Reply(code, text?.let { runCatching { JSONObject(it) }.getOrNull() })
            // 426: the server has stopped supporting this version since we connected.
            if (code == 426) _status.value = _status.value.copy(supported = false, message = reply.body?.optString("error").orEmpty())
            if (reply.disabled) _status.value = shutOut(_status.value, reply.body)
            reply.body?.optJSONObject("account")?.let { noteAccount(it) }
            reply
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.i(TAG, "$method $path failed: $e")
        null
    }

    private fun noteAccount(o: JSONObject) {
        // Times are the server's. Its clock and this device's differ by `ahead`; everything it sends is moved onto
        // the device's clock so countdowns can simply compare with System.currentTimeMillis().
        val time = o.optJSONObject("time")
        val ahead = if (time != null) time.optLong("now") - System.currentTimeMillis() else 0L
        fun local(serverMs: Long) = if (serverMs > 0) serverMs - ahead else serverMs
        fun offers(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { deal(it) } }.map { it.copy(expiresAt = local(it.expiresAt)) }
        } ?: emptyList()
        val account = Account(
            id = o.optString("id"), developer = o.optBoolean("developer"), cups = o.optInt("cups"), rank = o.optInt("rank"), players = o.optInt("players"), drops = o.optInt("drops"),
            dropsLeftToday = o.optInt("dropsLeftToday"),
            difficulty = BotDifficulty.entries.firstOrNull { it.name == o.optString("difficulty") } ?: BotDifficulty.EASY,
            profile = o.optJSONObject("profile")?.let { profile(it) },
            deals = offers("deals"),
            difficulties = o.optJSONArray("difficulties")?.let { a -> (0 until a.length()).mapNotNull { i -> BotDifficulty.entries.firstOrNull { it.name == a.optString(i) } } }
                ?: BotDifficulty.entries,
            dailyOffers = offers("dailyOffers"),
            day = time?.optLong("day", -1) ?: -1, dayEndsAt = local(time?.optLong("dayEndsAt") ?: 0L),
            giftAvailable = o.optBoolean("giftAvailable", true),
        )
        _status.value = _status.value.copy(account = account)
    }

    private fun lost() { _status.value = _status.value.copy(online = false) }

    private fun ints(o: JSONObject, key: String): Set<Int> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet()

    private fun fighterNamed(name: String) = FighterId.entries.firstOrNull { it.name == name }

    private fun profile(o: JSONObject): ServerProfile {
        val fighters = o.optJSONObject("fighters")
        return ServerProfile(
            bolts = o.optInt("bolts"), prisms = o.optInt("prisms"), bestCups = o.optInt("bestCups"), credits = o.optInt("credits"),
            fighters = FighterId.entries.associateWith { id ->
                val f = fighters?.optJSONObject(id.name)
                FighterProgress(
                    unlocked = f?.optBoolean("unlocked") ?: (id == FighterId.BYTE), level = (f?.optInt("level", 1) ?: 1).coerceAtLeast(1), cups = (f?.optInt("cups", 0) ?: 0).coerceAtLeast(0),
                    ownedSkins = (f?.let { ints(it, "ownedSkins") } ?: emptySet()) + 0,
                )
            },
            claimedMilestones = ints(o, "claimedMilestones"),
            lastDailyGiftDay = o.optLong("lastDailyGiftDay", -1), lastFirstWinDay = o.optLong("lastFirstWinDay", -1),
        )
    }

    private fun deal(o: JSONObject) = CustomOffer(
        id = o.optLong("id"), title = o.optString("title", "Offer").take(24), bolts = o.optInt("bolts"), prisms = o.optInt("prisms"),
        fighter = fighterNamed(o.optString("fighter")), skinFighter = fighterNamed(o.optString("skinFighter")), skinIndex = o.optInt("skinIndex"),
        currency = Currency.entries.firstOrNull { it.name == o.optString("currency") } ?: Currency.PRISMS,
        price = o.optInt("price"), wasPrice = o.optInt("wasPrice"), expiresAt = o.optLong("expiresAt"),
        limit = o.optInt("limit", 1), purchased = o.optInt("purchased"), theme = o.optInt("theme"),
    )

    /** A request that changes this player's account. The reply (which carries the new account) or null if it was refused or never arrived. */
    private fun act(path: String, body: JSONObject = JSONObject(), timeoutMs: Int = 3000): JSONObject? {
        lastError = ""
        if (!usable) return null
        val r = call("POST", path, body, auth = true, timeoutMs = timeoutMs)
        if (r == null) lost()
        if (r != null && r.code != 200) lastError = r.body?.optString("error").orEmpty()
        return if (r?.code == 200) r.body else null
    }

    /** Asks the server to let this player fight bots of [difficulty]. Null if it says no (see [lastError]). */
    fun setDifficulty(difficulty: BotDifficulty): Boolean? = act("/v1/settings/difficulty", JSONObject().put("difficulty", difficulty.name))?.let { true }

    /** Buys one of today's offers ([index] in [Account.dailyOffers]) as shown on [day]; after the server's midnight it no longer counts. */
    fun buyDaily(index: Long, day: Long): Reward? = act("/v1/shop/daily/$index/buy", JSONObject().put("day", day))?.optJSONObject("reward")?.let { reward(it) }

    /** Levels a fighter up. Returns what it cost, or null if the server said no. [costFactor] and [noCap] count for developers only. */
    fun upgrade(fighter: FighterId, costFactor: Float = 1f, noCap: Boolean = false): Int? =
        act("/v1/fighters/upgrade", JSONObject().put("fighter", fighter.name).put("costFactor", costFactor.toDouble()).put("noCap", noCap))?.optInt("cost")

    /** Buys a standing shop item (see [io.github.projectwip.data.ShopItem.key]) and returns what was received. */
    fun buy(itemKey: String): Reward? = act("/v1/shop/buy", JSONObject().put("item", itemKey))?.optJSONObject("reward")?.let { reward(it) }

    fun claimGift(): Reward? = act("/v1/shop/gift")?.optJSONObject("reward")?.let { reward(it) }

    /** Claims the Cup Track reward at [cups]. What comes back is what was actually given (owned things are paid out instead). */
    fun claimMilestone(cups: Int): Reward? = act("/v1/track/claim", JSONObject().put("cups", cups))?.optJSONObject("reward")?.let { reward(it) }

    fun buyDeal(id: Long): Reward? = act("/v1/shop/deals/$id/buy")?.optJSONObject("reward")?.let { reward(it) }

    /** Developers: puts a deal in every player's shop. */
    fun createDeal(o: CustomOffer): Long? = act("/v1/dev/deals", JSONObject()
        .put("title", o.title).put("bolts", o.bolts).put("prisms", o.prisms).put("fighter", o.fighter?.name ?: "")
        .put("skinFighter", o.skinFighter?.name ?: "").put("skinIndex", o.skinIndex).put("currency", o.currency.name)
        .put("price", o.price).put("wasPrice", o.wasPrice).put("expiresAt", o.expiresAt).put("limit", o.limit).put("theme", o.theme))?.optLong("id")

    /** Developers: takes a deal out of the shop. */
    fun deleteDeal(id: Long): Boolean? = act("/v1/dev/deals/$id/delete")?.optBoolean("deleted")

    /** Starts this account's progress over on the server. */
    fun reset(): Boolean? = act("/v1/reset")?.let { true }

    /** Developer hand-outs (the server refuses anyone else). */
    fun devGrant(cups: Int = 0, drops: Int = 0, bolts: Int = 0, prisms: Int = 0, credits: Int = 0): Boolean? =
        act("/v1/dev/grant", JSONObject().put("cups", cups).put("drops", drops).put("bolts", bolts).put("prisms", prisms).put("credits", credits))?.let { true }

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
     * Updates [status] either way. The caller then settles the save ([syncSave] or [refreshAccount]), which is
     * when the server says who this player is.
     */
    fun connect(url: String, gameVersion: String, playerName: String): JSONObject? {
        baseUrl = url.trim().trimEnd('/')
        version = gameVersion
        if (baseUrl.isEmpty()) { _status.value = ServerStatus(); return null }
        val hello = call("GET", "/v1/status?version=" + java.net.URLEncoder.encode(gameVersion, "UTF-8"))
        val info = hello?.body
        // Who this player is doesn't change because the connection dropped: keep it across a reconnect.
        val known = _status.value.account.takeIf { _status.value.url == baseUrl }
        if (hello == null || hello.code != 200 || info == null) {
            _status.value = ServerStatus(online = false, url = baseUrl, account = known)
            BotProfile.overrides = emptyMap()
            return null
        }
        val state = ServerStatus(
            online = true, supported = info.optBoolean("supported", true), message = info.optString("message"),
            notice = info.optString("notice"), url = baseUrl, account = known,
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
        if (saved == null) { _status.value = state.copy(online = false); return null }
        if (saved.disabled) { _status.value = shutOut(state, saved.body); return null }
        if (saved.code == 426) { _status.value = state.copy(supported = false, message = saved.body?.optString("error").orEmpty()); return null }
        _status.value = state
        return if (saved.code == 200) saved.body?.optJSONObject("save") else null
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

    /** Uploads the save now and learns the account from the reply. False if the server couldn't be reached. */
    fun syncSave(save: JSONObject): Boolean {
        if (!usable) return false
        val r = call("PUT", "/v1/save", JSONObject().put("save", save), auth = true)
        if (r == null) lost()
        return r?.code == 200
    }

    /** Asks the server who this player is (Cups, drops, developer or not). */
    fun refreshAccount(): Boolean {
        if (!usable) return false
        val r = call("GET", "/v1/me", auth = true)
        if (r == null) lost()
        return r?.code == 200
    }

    /** Uploads the save in the background. Only the newest one waiting is ever sent. */
    fun pushSave(save: JSONObject) {
        if (!usable) return
        if (pendingSave.getAndSet(save) != null) return // an upload is already queued; it will pick this one up
        worker.execute {
            val latest = pendingSave.getAndSet(null) ?: return@execute
            if (call("PUT", "/v1/save", JSONObject().put("save", latest), auth = true) == null) lost()
        }
    }

    /** Asks the server to set a match up. Null (quickly) when there is no server to ask. */
    fun planMatch(mode: GameMode, fighter: FighterId, level: Int, difficulty: BotDifficulty, boss: io.github.projectwip.data.BossKind? = null): MatchPlan? {
        if (!usable) return null
        val body = JSONObject().put("mode", mode.name).put("fighter", fighter.name).put("level", level).put("difficulty", difficulty.name)
        // Boss Mode: the boss the player picked, which the server keeps so that its replay fights the same one.
        if (boss != null) body.put("boss", boss.name)
        val r = call("POST", "/v1/matches", body, auth = true, timeoutMs = 1500)
        val o = r?.body
        if (r == null || r.code != 201 || o == null) {
            if (r == null) lost()
            return null
        }
        val names = o.optJSONArray("botNames")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        val planned = BotDifficulty.entries.firstOrNull { it.name == o.optString("difficulty") }
        // The bots must behave exactly as the server will have them behave when it replays this match.
        o.optJSONObject("bots")?.let { useBots(planned ?: difficulty, it) }
        return MatchPlan(o.optLong("matchId"), o.optLong("seed"), names, planned, if (o.has("level")) o.optInt("level") else null)
    }

    /**
     * Hands a finished match to the server: the player's [inputs] (see [io.github.projectwip.sim.InputLog]), which
     * the server replays to find the result for itself, and the device's own [report] for servers without a
     * referee. The answer is what the match was worth and, with a referee, how the server says it went. Null
     * when the match wasn't the server's, the server couldn't be reached, or it refused the match.
     */
    fun reportMatch(matchId: Long, report: MatchReport, inputs: ByteArray?): ServerVerdict? {
        if (matchId <= 0 || !usable) return null
        val body = JSONObject().put("outcome", report.outcome.name).put("placement", report.placement)
            .put("kos", report.kos).put("deaths", report.deaths).put("damage", report.damageDealt).put("mvp", report.mvp)
        if (inputs != null) {
            val packed = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(packed).use { it.write(inputs) }
            body.put("inputs", java.util.Base64.getEncoder().encodeToString(packed.toByteArray()))
        }
        // Replaying a match takes the server a second or two.
        val r = call("POST", "/v1/matches/$matchId/result", body, auth = true, timeoutMs = 20000)
        val o = r?.body
        if (r == null) lost()
        if (r == null || r.code != 200 || o == null) return null
        return verdict(o)
    }

    /** What a 1v1 was worth, as the lobby settled it ([DuelLink.result]), with the account brought up to date. */
    fun duelVerdict(o: JSONObject): ServerVerdict {
        refreshAccount()
        return verdict(o)
    }

    private fun verdict(o: JSONObject): ServerVerdict {
        val account = o.optJSONObject("account")
        val judged = o.optJSONObject("report")?.let { j ->
            MatchOutcome.entries.firstOrNull { it.name == j.optString("outcome") }?.let {
                JudgedResult(it, j.optInt("placement"), j.optInt("kos"), j.optInt("deaths"), j.optInt("damage"), j.optBoolean("mvp"))
            }
        }
        return ServerVerdict(
            o.optInt("cupDelta"), o.optInt("cups"), o.optBoolean("drop"), account?.optInt("drops") ?: 0, account?.optInt("dropsLeftToday") ?: 0,
            bolts = o.optInt("bolts"), firstWinPrisms = o.optInt("firstWinPrisms"), credits = o.optInt("credits"),
            unlocked = o.optJSONArray("unlocked")?.let { a -> (0 until a.length()).mapNotNull { fighterNamed(a.optString(it)) } } ?: emptyList(), judged = judged,
            fighterCupsBefore = o.optInt("fighterCupsBefore"), fighterCups = o.optInt("fighterCups"), mvpCups = o.optInt("mvpCups"),
        )
    }

    /**
     * Opens one Glitch Drop. The server rolls it; this only carries the answer back. [luck] and [free] are
     * honoured for developers only. Null if the server couldn't be reached or the player has none to open.
     */
    fun openDrop(luck: Float = 0f, free: Boolean = false): CapsuleResult? {
        if (!usable) return null
        val r = call("POST", "/v1/drops/open", JSONObject().put("luck", luck.toDouble()).put("free", free), auth = true)
        val o = r?.body
        if (r == null) lost()
        // 409: the server says there is nothing to open, so its count is the one to show.
        if (r?.code == 409) refreshAccount()
        if (r == null || r.code != 200 || o == null) return null
        return capsuleResult(o)
    }

    /**
     * Opens every Glitch Drop the player holds, in one go; pieces that split off on the way are left to open
     * next. The server rolls them all; the answer is what came out of each, in the order they were opened. Null
     * if it couldn't be reached or there were none.
     */
    fun openAllDrops(luck: Float = 0f): List<CapsuleResult>? {
        // Thousands of drops make a long answer, so this one is given more time than the rest.
        val results = act("/v1/drops/open-all", JSONObject().put("luck", luck.toDouble()), timeoutMs = 20_000)?.optJSONArray("results") ?: return null
        return (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { capsuleResult(it) } }.takeIf { it.isNotEmpty() }
    }

    private fun capsuleResult(o: JSONObject): CapsuleResult? {
        val tier = CapsuleTier.entries.firstOrNull { it.name == o.optString("tier") } ?: return null
        val reward = o.optJSONObject("reward")?.let { reward(it) } ?: return null
        return CapsuleResult(tier, reward, o.optInt("pieces", 1).coerceIn(1, 8))
    }

    private fun reward(o: JSONObject): Reward? {
        fun fighter() = FighterId.entries.firstOrNull { it.name == o.optString("fighter") }
        return when (o.optString("type")) {
            "bolts" -> Reward.Bolts(o.optInt("amount").coerceAtLeast(0))
            "prisms" -> Reward.Prisms(o.optInt("amount").coerceAtLeast(0))
            "credits" -> Reward.Credits(o.optInt("amount").coerceAtLeast(0))
            "fighter" -> fighter()?.let { Reward.UnlockFighter(it) }
            "skin" -> fighter()?.let { Reward.SkinReward(it, o.optInt("skin")) }
            "bundle" -> o.optJSONArray("items")?.let { a -> Reward.Bundle((0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { reward(it) } }) }
            else -> null
        }
    }

    /** Makes the bots of difficulty [d] behave as the server's [settings] say: the same as when it replays the match. */
    fun useBots(d: BotDifficulty, settings: JSONObject) {
        val values = HashMap<String, Any>()
        for (key in settings.keys()) values[key] = settings.get(key)
        BotProfile.overrides = BotProfile.overrides + (d to BotProfile.builtIn(d).withOverrides(values))
    }

    /** The line to the server's team lobby (the same port as the 1v1 lobby), or null when offline. */
    fun teamLink(): TeamLink? {
        if (!_status.value.online) return null
        return try {
            val url = URL(baseUrl)
            TeamLink(url.host, (if (url.port > 0) url.port else 80) + 1)
        } catch (_: Exception) {
            null
        }
    }

    /** What this player tells the team lobby: who they are, the fighter they bring, and the team to make ([mode]) or join ([code]). */
    fun teamHello(fighter: FighterId, skin: Int, action: String, mode: io.github.projectwip.data.GameMode?, boss: io.github.projectwip.data.BossKind?, code: String): JSONObject =
        duelHello(fighter, skin).put("action", action).put("mode", mode?.name.orEmpty()).put("boss", boss?.name.orEmpty()).put("code", code)

    /** The line to the server's 1v1 lobby (it listens one port above the game server), or null when offline. */
    fun duelLink(): DuelLink? {
        if (!_status.value.online) return null
        return try {
            val url = URL(baseUrl)
            DuelLink(url.host, (if (url.port > 0) url.port else 80) + 1)
        } catch (_: Exception) {
            null
        }
    }

    /** What this player tells the lobby: who they are and the fighter they bring. Its level is the server's to say. */
    fun duelHello(fighter: FighterId, skin: Int): JSONObject =
        JSONObject().put("token", token.orEmpty()).put("version", version).put("fighter", fighter.name).put("skin", skin)

    /** The News tab's items, newest first, or null when the server can't be asked. */
    fun news(): List<NewsItem>? {
        if (!_status.value.online) return null
        val items = call("GET", "/v1/news")?.body?.optJSONArray("news") ?: return null
        return (0 until items.length()).mapNotNull { i ->
            val n = items.optJSONObject(i) ?: return@mapNotNull null
            NewsItem(n.optString("title"), n.optString("date"), n.optString("tag", "NEWS"), n.optString("text"))
        }
    }

    /** Real players by Cups, or null when the server can't be asked. */
    fun leaderboard(limit: Int = 200): List<RemotePlayer>? {
        if (!_status.value.online) return null
        val players = call("GET", "/v1/leaderboard?limit=$limit")?.body?.optJSONArray("players") ?: return null
        return (0 until players.length()).mapNotNull { i ->
            val p = players.optJSONObject(i) ?: return@mapNotNull null
            RemotePlayer(
                p.optString("id"), p.optString("name", "Player"), p.optInt("cups"),
                FighterId.entries.firstOrNull { it.name == p.optString("fighter") } ?: FighterId.BYTE,
            )
        }
    }

    private companion object {
        const val TAG = "GameServer"
    }
}
