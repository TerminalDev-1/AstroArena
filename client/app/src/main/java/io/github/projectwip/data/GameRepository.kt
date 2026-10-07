package io.github.projectwip.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Single source of truth for the player's progress. The UI observes [save];
 * every mutation goes through [Progression] (or, offline, [Economy]) and is persisted immediately on a background thread.
 *
 * There are two profiles, each in a file of its own. The *online* one ([store]) is a copy of what the game server
 * holds for this player, and only the server changes what is in it. The *offline* one ([offlineStore]) is played on
 * while the server can't be reached ([offlineMode]): everything in it is earned and spent on this device, by the
 * rules in [Economy], and none of it is ever sent to the server. [save] is whichever is being played on right now.
 * Settings belong to the device, not to a profile: they are carried across when the profile changes.
 *
 * Offline is also *Chaos Mode*: everyone has every tweak there (the Chaos Command Center), since the offline profile
 * is nobody's business but the player's. Back online the tweaks are a developer's again.
 */
class GameRepository(private val store: SaveStore, private val offlineStore: SaveStore? = null) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "save-io").apply { isDaemon = true } }
    private var onlineSave = store.load()
    /** Read from its file the first time the game goes offline. */
    private var offlineSave: SaveData? = null
    private val _save = MutableStateFlow(onlineSave)
    val save: StateFlow<SaveData> = _save.asStateFlow()

    private val _offline = MutableStateFlow(false)
    /** True while the offline profile is the one being played on. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()
    val offlineMode: Boolean get() = _offline.value

    /** The copy of the server's account, whichever profile is showing: the one that is uploaded and synced. */
    val online: SaveData @Synchronized get() = onlineSave

    /** The day the server says it is, once it has said so. Days (the daily gift, the day's drops) are the server's. */
    @Volatile var serverDay: Long? = null

    /** Today's number: the server's when known and online, this device's otherwise. */
    val today: Long get() = (if (offlineMode) null else serverDay) ?: LocalDate.now().toEpochDay()

    /** Called with every new online save (the server connection uses it to upload a copy). Never the offline profile. */
    var onCommit: ((SaveData) -> Unit)? = null

    @Synchronized
    private fun commit(next: SaveData) {
        _save.value = next
        if (offlineMode) {
            offlineSave = next
            io.execute { offlineStore?.write(next) }
        } else {
            onlineSave = next
            io.execute { store.write(next) }
            onCommit?.invoke(next)
        }
    }

    private fun keepOnline(next: SaveData) {
        if (offlineMode) {
            onlineSave = next
            io.execute { store.write(next) }
        } else commit(next)
    }

    /**
     * Changes which profile is being played on: the offline one when the server can't be reached, the server's when
     * it can. Settings come along, tweaks included: offline they are everyone's (Chaos Mode), and back online the
     * game switches them off for anyone the server doesn't list as a developer.
     */
    @Synchronized
    fun setOffline(on: Boolean) {
        if (on == offlineMode) return
        val settings = _save.value.settings
        _offline.value = on
        if (on) {
            val profile = offlineSave ?: offlineStore?.load() ?: SaveData(capsuleSeed = System.nanoTime())
            commit(profile.copy(settings = settings))
        } else commit(onlineSave.copy(settings = settings))
    }

    /** Replaces the whole online save with one restored from the server, keeping this device's own settings. */
    @Synchronized
    fun restore(fromServer: SaveData) = keepOnline(fromServer.copy(settings = _save.value.settings))

    /**
     * Records a finished match. Online, [verdict] is what the server awarded (null: it couldn't be asked, and nothing
     * is earned). Offline the match is settled here, by [Economy.settleMatch], into the offline profile.
     */
    @Synchronized
    fun applyMatch(report: MatchReport, verdict: ServerVerdict?): MatchRewards {
        val (next, rewards) = if (offlineMode) {
            val settled = Economy.settleMatch(_save.value, report, today)
            Progression.applyMatch(settled.save, report, today, settled.value)
        } else Progression.applyMatch(_save.value, report, today, verdict)
        commit(next)
        return rewards
    }

    /** Offline: runs [change] on the offline profile and keeps the result. A [Refused] change leaves it as it was. */
    @Synchronized
    fun <T> transact(change: (SaveData) -> Done<T>): T {
        check(offlineMode) { "the online save is the server's to change" }
        val done = change(_save.value)
        if (done.save != _save.value) commit(done.save)
        return done.value
    }

    /** Counts a Glitch Drop the server opened. */
    fun dropOpened(count: Int = 1) = commit(Progression.dropOpened(_save.value, count))

    /** Takes on what the server holds for this player (Cups, drops, and when given the profile and shop deals). It goes into the online save, whichever profile is showing. */
    @Synchronized
    fun syncAccount(
        cups: Int, drops: Int, dropsLeftToday: Int, profile: ServerProfile? = null, deals: List<CustomOffer>? = null,
        difficulty: BotDifficulty? = null, day: Long? = null,
    ) {
        if (day != null) serverDay = day
        val serverToday = serverDay ?: LocalDate.now().toEpochDay()
        val next = Progression.syncAccount(onlineSave, cups, drops, dropsLeftToday, serverToday, profile, deals, difficulty)
        if (next != onlineSave) keepOnline(next)
    }

    /** Switches the debug menu's cheats off, if any are on. */
    fun clearCheats() {
        val clean = Progression.withoutCheats(_save.value.settings)
        if (clean != _save.value.settings) commit(_save.value.copy(settings = clean))
    }

    val capsulesLeftToday: Int get() = Progression.capsulesLeftToday(_save.value, today)

    fun selectFighter(id: FighterId) = commit(Progression.selectFighter(_save.value, id))

    fun selectMode(mode: GameMode) = commit(_save.value.copy(selectedMode = mode))

    /** Boss Mode: which boss to fight (null = a random one each time). */
    fun selectBoss(boss: BossKind?) = commit(_save.value.copy(selectedBoss = boss))

    fun selectSkin(id: FighterId, skin: Int) = commit(Progression.selectSkin(_save.value, id, skin))

    fun updateSettings(transform: (Settings) -> Settings) = commit(_save.value.copy(settings = transform(_save.value.settings)))

    /** Wipes what is kept on this device (after the server has started the account over), keeping settings. */
    fun resetProgress() = commit(SaveData(settings = _save.value.settings, capsuleSeed = System.nanoTime()))
}
