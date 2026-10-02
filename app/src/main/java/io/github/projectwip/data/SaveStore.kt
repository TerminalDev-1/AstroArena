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
        if (!file.baseFile.exists()) SaveData() else fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.e(TAG, "Save unreadable, starting fresh", e)
        SaveData()
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
            put("claimedMilestones", JSONArray(s.claimedMilestones.sorted()))
            put("lastDailyGiftDay", s.lastDailyGiftDay)
            put("lastFirstWinDay", s.lastFirstWinDay)
            put("matchesPlayed", s.matchesPlayed)
            put("victories", s.victories)
            put("totalKos", s.totalKos)
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
                put("showDamageNumbers", st.showDamageNumbers)
                put("highFrameRate", st.highFrameRate)
                put("showFps", st.showFps)
                put("playerName", st.playerName)
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
                showDamageNumbers = so.optBoolean("showDamageNumbers", sd.showDamageNumbers),
                highFrameRate = so.optBoolean("highFrameRate", sd.highFrameRate),
                showFps = so.optBoolean("showFps", sd.showFps),
                playerName = so.optString("playerName", sd.playerName).take(16).ifBlank { sd.playerName },
            )
            val selected = enumOr(o.optString("selectedFighter"), d.selectedFighter)
            return SaveData(
                cups = o.optInt("cups", 0).coerceAtLeast(0),
                bestCups = o.optInt("bestCups", 0).coerceAtLeast(0),
                bolts = o.optInt("bolts", d.bolts).coerceAtLeast(0),
                prisms = o.optInt("prisms", d.prisms).coerceAtLeast(0),
                fighters = fighters,
                selectedFighter = if (fighters[selected]?.unlocked == true) selected else FighterId.JUNO,
                claimedMilestones = o.optJSONArray("claimedMilestones")?.ints()?.toSet() ?: emptySet(),
                lastDailyGiftDay = o.optLong("lastDailyGiftDay", -1),
                lastFirstWinDay = o.optLong("lastFirstWinDay", -1),
                matchesPlayed = o.optInt("matchesPlayed", 0),
                victories = o.optInt("victories", 0),
                totalKos = o.optInt("totalKos", 0),
                settings = settings,
            )
        }

        private fun JSONArray.ints() = (0 until length()).map { getInt(it) }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
