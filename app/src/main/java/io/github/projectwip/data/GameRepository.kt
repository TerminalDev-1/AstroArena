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

    val today: Long get() = LocalDate.now().toEpochDay()

    private fun commit(next: SaveData) {
        _save.value = next
        io.execute { store.write(next) }
    }

    fun applyMatch(report: MatchReport): MatchRewards {
        val (next, rewards) = Progression.applyMatch(_save.value, report, today)
        commit(next)
        return rewards
    }

    fun upgrade(id: FighterId): Boolean = Progression.upgrade(_save.value, id)?.also(::commit) != null

    fun claimMilestone(m: Milestone): Reward? = Progression.claimMilestone(_save.value, m)?.let { (s, r) -> commit(s); r }

    fun buy(item: ShopItem): PurchaseResult {
        val (s, r) = Progression.buy(_save.value, item)
        if (r == PurchaseResult.Ok) commit(s)
        return r
    }

    fun addOffer(offer: CustomOffer) = commit(Progression.addOffer(_save.value, offer))

    fun removeOffer(id: Long) = commit(Progression.removeOffer(_save.value, id))

    fun buyOffer(id: Long): Progression.OfferResult {
        val (s, r) = Progression.buyOffer(_save.value, id, System.currentTimeMillis())
        if (r is Progression.OfferResult.Ok) commit(s)
        return r
    }

    fun openCapsule(): CapsuleResult? = Progression.openCapsule(_save.value)?.let { (s, r) -> commit(s); r }

    /** Debug menu hand-outs. */
    fun debugGrant(bolts: Int = 0, prisms: Int = 0, capsules: Int = 0) =
        commit(_save.value.let { it.copy(bolts = it.bolts + bolts, prisms = it.prisms + prisms, capsules = it.capsules + capsules) })

    val capsulesLeftToday: Int get() = Progression.capsulesLeftToday(_save.value, today)

    fun claimDailyGift(): Reward? = Progression.claimDailyGift(_save.value, today)?.let { (s, r) -> commit(s); r }

    fun selectFighter(id: FighterId) = commit(Progression.selectFighter(_save.value, id))

    fun selectMode(mode: GameMode) = commit(_save.value.copy(selectedMode = mode))

    fun selectSkin(id: FighterId, skin: Int) = commit(Progression.selectSkin(_save.value, id, skin))

    fun updateSettings(transform: (Settings) -> Settings) = commit(_save.value.copy(settings = transform(_save.value.settings)))

    /** Wipes progress but keeps settings. */
    fun resetProgress() = commit(SaveData(settings = _save.value.settings, capsuleSeed = System.nanoTime()))
}
