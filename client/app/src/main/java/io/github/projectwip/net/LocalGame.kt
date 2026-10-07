package io.github.projectwip.net

import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.CapsuleResult
import io.github.projectwip.data.CustomOffer
import io.github.projectwip.data.Done
import io.github.projectwip.data.Economy
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.Progression
import io.github.projectwip.data.Refused
import io.github.projectwip.data.Reward
import io.github.projectwip.data.SaveData
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * What the menus can ask for: buy, upgrade, claim. Online it is the game server that answers ([GameServer]); while it
 * can't be reached the same requests are answered on the device, against the offline profile ([LocalGame]). Either
 * way a refused request returns null, with the reason in [lastError].
 */
interface GameActions {
    val lastError: String
    fun setDifficulty(difficulty: BotDifficulty): Boolean?
    fun buyDaily(index: Long, day: Long): Reward?
    fun upgrade(fighter: FighterId, costFactor: Float = 1f, noCap: Boolean = false): Int?
    fun buy(itemKey: String): Reward?
    fun claimGift(): Reward?
    fun claimMilestone(cups: Int): Reward?
    fun buyDeal(id: Long): Reward?
    fun createDeal(o: CustomOffer): Long?
    fun deleteDeal(id: Long): Boolean?
    fun reset(): Boolean?
    fun devGrant(cups: Int = 0, drops: Int = 0, bolts: Int = 0, prisms: Int = 0, credits: Int = 0): Boolean?
    fun refreshAccount(): Boolean
}

/**
 * The game with no server: the rules in [Economy], applied to the offline profile kept on this device
 * ([GameRepository.offlineMode]). It only answers while the game is offline, and nothing it does is ever sent to
 * the server. Offline nobody is a developer: there are no tweaks, no hand-outs and no deals to make.
 */
class LocalGame(private val repo: GameRepository) : GameActions {
    @Volatile override var lastError = ""
        private set

    private fun <T : Any> act(change: (SaveData) -> Done<T>): T? {
        lastError = ""
        if (!repo.offlineMode) { lastError = "the game is back online"; return null }
        return try {
            repo.transact(change)
        } catch (refused: Refused) {
            lastError = refused.message.orEmpty()
            null
        }
    }

    private fun <T> developersOnly(): T? {
        lastError = "that is for developers, and offline nobody is one"
        return null
    }

    /** Offline every difficulty may be picked. */
    override fun setDifficulty(difficulty: BotDifficulty): Boolean? {
        repo.updateSettings { it.copy(botDifficulty = difficulty) }
        return true
    }

    override fun buyDaily(index: Long, day: Long): Reward? {
        if (day != repo.today) { lastError = "the shop has changed since then"; return null }
        return act { Economy.buyDaily(it, index.toInt(), repo.today) }
    }

    /** The cost factor and the level cap switch are the Chaos Command Center's, which isn't there offline. */
    override fun upgrade(fighter: FighterId, costFactor: Float, noCap: Boolean): Int? = act { Economy.upgrade(it, fighter) }
    override fun buy(itemKey: String): Reward? = act { Economy.buy(it, itemKey) }
    override fun claimGift(): Reward? = act { Economy.claimGift(it, repo.today) }
    override fun claimMilestone(cups: Int): Reward? = act { Economy.claimMilestone(it, cups) }
    override fun buyDeal(id: Long): Reward? = act { Economy.buyDeal(it, id, System.currentTimeMillis()) }
    override fun createDeal(o: CustomOffer): Long? = developersOnly()
    override fun deleteDeal(id: Long): Boolean? = developersOnly()
    override fun reset(): Boolean? = developersOnly()
    override fun devGrant(cups: Int, drops: Int, bolts: Int, prisms: Int, credits: Int): Boolean? = developersOnly()
    override fun refreshAccount(): Boolean = true

    /** Opens one Glitch Drop from the offline profile. Null if there is none to open. */
    fun openDrop(): CapsuleResult? = act { Economy.openDrops(it, 0f, false, 1, Random.Default) }?.firstOrNull()

    /** Opens every Glitch Drop the offline profile holds; pieces that split off are left to open next. Null if there were none. */
    fun openAllDrops(): List<CapsuleResult>? = act { Economy.openDrops(it, 0f, false, null, Random.Default) }?.takeIf { it.isNotEmpty() }

    /** The offline profile as the menus read an account: today's offers and the clock they run on are this device's. */
    fun account(save: SaveData): Account {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val day = today.toEpochDay()
        val dayEndsAt = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        return Account(
            id = "", developer = false, cups = save.cups, rank = 0, players = 0, drops = save.capsules,
            dropsLeftToday = Progression.capsulesLeftToday(save, day), difficulty = save.settings.botDifficulty,
            deals = save.customOffers.filter { !it.expired(now) },
            dailyOffers = Economy.dailyOffers(day, dayEndsAt).map { it.copy(purchased = if (Economy.boughtToday(save, it.title, day)) 1 else 0) },
            day = day, dayEndsAt = dayEndsAt, giftAvailable = save.lastDailyGiftDay != day,
        )
    }
}
