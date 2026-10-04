package io.github.projectwip.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Single source of truth for the player's progress. The UI observes [save];
 * every mutation goes through [Progression] and is persisted immediately on a background thread.
 */
class GameRepository(private val store: SaveStore) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "save-io").apply { isDaemon = true } }
    private val _save = MutableStateFlow(store.load())
    val save: StateFlow<SaveData> = _save.asStateFlow()

    /** The day the server says it is, once it has said so. Days (the daily gift, the day's drops) are the server's. */
    @Volatile var serverDay: Long? = null

    /** Today's number: the server's when known, this device's otherwise. */
    val today: Long get() = serverDay ?: LocalDate.now().toEpochDay()

    /** Called with every new save (the server connection uses it to upload a copy). */
    var onCommit: ((SaveData) -> Unit)? = null

    private fun commit(next: SaveData) {
        _save.value = next
        io.execute { store.write(next) }
        onCommit?.invoke(next)
    }

    /** Replaces the whole save with one restored from the server, keeping this device's own settings. */
    fun restore(fromServer: SaveData) = commit(fromServer.copy(settings = _save.value.settings))

    /** [verdict] is what the server awarded; null for an offline match. */
    fun applyMatch(report: MatchReport, verdict: ServerVerdict?): MatchRewards {
        val (next, rewards) = Progression.applyMatch(_save.value, report, today, verdict)
        commit(next)
        return rewards
    }

    /** Counts a Spark Drop the server opened. */
    fun dropOpened() = commit(Progression.dropOpened(_save.value))

    /** Takes on what the server holds for this player (Cups, drops, and when given the profile and shop deals). */
    fun syncAccount(
        cups: Int, drops: Int, dropsLeftToday: Int, profile: ServerProfile? = null, deals: List<CustomOffer>? = null,
        day: Long? = null,
    ) {
        if (day != null) serverDay = day
        val next = Progression.syncAccount(_save.value, cups, drops, dropsLeftToday, today, profile, deals)
        if (next != _save.value) commit(next)
    }

    /** Switches the debug menu's cheats off, if any are on. */
    fun clearCheats() {
        val clean = Progression.withoutCheats(_save.value.settings)
        if (clean != _save.value.settings) commit(_save.value.copy(settings = clean))
    }

    val capsulesLeftToday: Int get() = Progression.capsulesLeftToday(_save.value, today)

    fun selectFighter(id: FighterId) = commit(Progression.selectFighter(_save.value, id))

    fun selectMode(mode: GameMode) = commit(_save.value.copy(selectedMode = mode))

    fun selectSkin(id: FighterId, skin: Int) = commit(Progression.selectSkin(_save.value, id, skin))

    fun updateSettings(transform: (Settings) -> Settings) = commit(_save.value.copy(settings = transform(_save.value.settings)))

    /** Wipes what is kept on this device (after the server has started the account over), keeping settings. */
    fun resetProgress() = commit(SaveData(settings = _save.value.settings, capsuleSeed = System.nanoTime()))
}
