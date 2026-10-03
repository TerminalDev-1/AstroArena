package io.github.projectwip.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes [SaveData] as human-readable JSON in the app's private files dir.
 * Writes go through [AtomicFile] so a crash mid-write never corrupts progress.
 * Unknown/missing fields fall back to defaults, so old saves keep loading as the game grows.
 */
class SaveStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "save.json"))

    fun load(): SaveData = try {
        if (!file.baseFile.exists()) SaveData(capsuleSeed = System.nanoTime()) else fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.e(TAG, "Save unreadable, starting fresh", e)
        SaveData(capsuleSeed = System.nanoTime())
    }

    fun write(save: SaveData) {
        val out = file.startWrite()
        try {
            out.write(toJson(save).toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Save failed", e)
        }
    }

    companion object {
        private const val TAG = "SaveStore"

        fun toJson(s: SaveData): JSONObject = JSONObject().apply {
            put("version", SaveData.VERSION)
            put("cups", s.cups)
            put("bestCups", s.bestCups)
            put("bolts", s.bolts)
            put("prisms", s.prisms)
            put("selectedFighter", s.selectedFighter.name)
            put("selectedMode", s.selectedMode.name)
            put("claimedMilestones", JSONArray(s.claimedMilestones.sorted()))
            put("lastDailyGiftDay", s.lastDailyGiftDay)
            put("lastFirstWinDay", s.lastFirstWinDay)
            put("matchesPlayed", s.matchesPlayed)
            put("victories", s.victories)
            put("totalKos", s.totalKos)
            put("capsules", s.capsules)
            put("capsuleDay", s.capsuleDay)
            put("capsulesEarnedToday", s.capsulesEarnedToday)
            put("capsulesOpened", s.capsulesOpened)
            put("boostedCapsules", s.boostedCapsules)
            put("capsuleSeed", s.capsuleSeed)
            put("fighters", JSONObject().apply {
                s.fighters.forEach { (id, p) ->
                    put(id.name, JSONObject().apply {
                        put("unlocked", p.unlocked)
                        put("level", p.level)
                        put("skin", p.skin)
                        put("ownedSkins", JSONArray(p.ownedSkins.sorted()))
                    })
                }
            })
            put("customOffers", JSONArray().apply {
                s.customOffers.forEach { o ->
                    put(JSONObject().apply {
                        put("id", o.id); put("title", o.title); put("bolts", o.bolts); put("prisms", o.prisms)
                        put("fighter", o.fighter?.name ?: ""); put("skinFighter", o.skinFighter?.name ?: ""); put("skinIndex", o.skinIndex)
                        put("currency", o.currency.name); put("price", o.price); put("wasPrice", o.wasPrice)
                        put("expiresAt", o.expiresAt); put("limit", o.limit); put("purchased", o.purchased); put("theme", o.theme)
                    })
                }
            })
            val st = s.settings
            put("settings", JSONObject().apply {
                put("botDifficulty", st.botDifficulty.name)
                put("sfxVolume", st.sfxVolume.toDouble())
                put("muted", st.muted)
                put("haptics", st.haptics)
                put("controlScale", st.controlScale.toDouble())
                put("controlOpacity", st.controlOpacity.toDouble())
                put("moveStickMode", st.moveStickMode.name)
                put("tapToAutoAim", st.tapToAutoAim)
                put("aimAssist", st.aimAssist)
                put("showDamageNumbers", st.showDamageNumbers)
                put("highFrameRate", st.highFrameRate)
                put("showFps", st.showFps)
                put("playerName", st.playerName)
                put("attackStickMode", st.attackStickMode.name)
                put("debugLuck", st.debugLuck.toDouble())
                put("debugInfiniteCapsules", st.debugInfiniteCapsules)
                val l = st.controlLayout
                put("controlLayout", JSONArray(listOf(l.moveX, l.moveY, l.attackX, l.attackY, l.superX, l.superY).map { it.toDouble() }))
            })
        }

        fun fromJson(o: JSONObject): SaveData {
            val d = SaveData()
            val fightersJson = o.optJSONObject("fighters")
            val fighters = FighterId.entries.associateWith { id ->
                val def = d.progress(id)
                val f = fightersJson?.optJSONObject(id.name) ?: return@associateWith def
                FighterProgress(
                    unlocked = f.optBoolean("unlocked", def.unlocked),
                    level = f.optInt("level", 1).coerceIn(1, Balance.MAX_LEVEL),
                    skin = f.optInt("skin", 0),
                    ownedSkins = f.optJSONArray("ownedSkins")?.ints()?.toSet()?.plus(0) ?: setOf(0),
                )
            }
            val sd = Settings()
            val so = o.optJSONObject("settings") ?: JSONObject()
            val settings = Settings(
                botDifficulty = enumOr(so.optString("botDifficulty"), sd.botDifficulty),
                sfxVolume = so.optDouble("sfxVolume", sd.sfxVolume.toDouble()).toFloat().coerceIn(0f, 1f),
                muted = so.optBoolean("muted", sd.muted),
                haptics = so.optBoolean("haptics", sd.haptics),
                controlScale = so.optDouble("controlScale", sd.controlScale.toDouble()).toFloat().coerceIn(0.7f, 1.4f),
                controlOpacity = so.optDouble("controlOpacity", sd.controlOpacity.toDouble()).toFloat().coerceIn(0.3f, 1f),
                moveStickMode = enumOr(so.optString("moveStickMode"), sd.moveStickMode),
                tapToAutoAim = so.optBoolean("tapToAutoAim", sd.tapToAutoAim),
                aimAssist = so.optBoolean("aimAssist", sd.aimAssist),
                showDamageNumbers = so.optBoolean("showDamageNumbers", sd.showDamageNumbers),
                highFrameRate = so.optBoolean("highFrameRate", sd.highFrameRate),
                showFps = so.optBoolean("showFps", sd.showFps),
                playerName = so.optString("playerName", sd.playerName).take(16).ifBlank { sd.playerName },
                attackStickMode = enumOr(so.optString("attackStickMode"), sd.attackStickMode),
                debugLuck = so.optDouble("debugLuck", 0.0).toFloat().let { if (it.isNaN()) 0f else it.coerceIn(0f, SparkCapsules.MAX_LUCK) },
                debugInfiniteCapsules = so.optBoolean("debugInfiniteCapsules", false),
                controlLayout = so.optJSONArray("controlLayout")?.takeIf { it.length() == 6 }?.let { a ->
                    fun f(i: Int) = a.optDouble(i, -1.0).toFloat().let { if (it.isNaN()) -1f else it.coerceIn(-1f, 1f) }
                    ControlLayout(f(0), f(1), f(2), f(3), f(4), f(5))
                } ?: sd.controlLayout,
            )
            val selected = enumOr(o.optString("selectedFighter"), d.selectedFighter)
            return SaveData(
                cups = o.optInt("cups", 0).coerceAtLeast(0),
                bestCups = o.optInt("bestCups", 0).coerceAtLeast(0),
                bolts = o.optInt("bolts", d.bolts).coerceAtLeast(0),
                prisms = o.optInt("prisms", d.prisms).coerceAtLeast(0),
                fighters = fighters,
                selectedFighter = if (fighters[selected]?.unlocked == true) selected else FighterId.JUNO,
                selectedMode = enumOr(o.optString("selectedMode"), d.selectedMode),
                claimedMilestones = o.optJSONArray("claimedMilestones")?.ints()?.toSet() ?: emptySet(),
                lastDailyGiftDay = o.optLong("lastDailyGiftDay", -1),
                lastFirstWinDay = o.optLong("lastFirstWinDay", -1),
                matchesPlayed = o.optInt("matchesPlayed", 0),
                victories = o.optInt("victories", 0),
                totalKos = o.optInt("totalKos", 0),
                capsules = o.optInt("capsules", d.capsules).coerceAtLeast(0),
                capsuleDay = o.optLong("capsuleDay", -1),
                capsulesEarnedToday = o.optInt("capsulesEarnedToday", 0).coerceAtLeast(0),
                capsulesOpened = o.optInt("capsulesOpened", 0).coerceAtLeast(0),
                boostedCapsules = o.optInt("boostedCapsules", 0).coerceAtLeast(0),
                capsuleSeed = if (o.has("capsuleSeed")) o.optLong("capsuleSeed") else System.nanoTime(),
                settings = settings,
                customOffers = o.optJSONArray("customOffers")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val j = arr.optJSONObject(i) ?: return@mapNotNull null
                        CustomOffer(
                            id = j.optLong("id"), title = j.optString("title", "Offer").take(24),
                            bolts = j.optInt("bolts").coerceAtLeast(0), prisms = j.optInt("prisms").coerceAtLeast(0),
                            fighter = FighterId.entries.firstOrNull { it.name == j.optString("fighter") },
                            skinFighter = FighterId.entries.firstOrNull { it.name == j.optString("skinFighter") },
                            skinIndex = j.optInt("skinIndex"),
                            currency = enumOr(j.optString("currency"), Currency.PRISMS),
                            price = j.optInt("price").coerceAtLeast(0), wasPrice = j.optInt("wasPrice").coerceAtLeast(0),
                            expiresAt = j.optLong("expiresAt"), limit = j.optInt("limit", 1).coerceAtLeast(0),
                            purchased = j.optInt("purchased"), theme = j.optInt("theme"),
                        )
                    }
                } ?: emptyList(),
            )
        }

        private fun JSONArray.ints() = (0 until length()).map { getInt(it) }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
