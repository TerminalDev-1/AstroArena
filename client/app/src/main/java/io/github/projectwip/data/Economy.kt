package io.github.projectwip.data

import kotlin.math.floor
import kotlin.random.Random

/** A request the rules don't allow. [status] is only there to tell the reasons apart in tests. */
class Refused(val status: Int, message: String) : Exception(message)

/** A save changed by something the player did, with what that gave back. */
data class Done<T>(val save: SaveData, val value: T)

/**
 * The rules for everything that is earned, spent, upgraded, claimed or opened: pure functions from one [SaveData]
 * to the next, so they run in JVM tests. (They used to be the game server's `economy.py` and `rules.py`.)
 *
 * Rewards are [Reward]s. Anything the player already owns is paid out instead ([compensation]), and Credits earned
 * once every fighter is unlocked become Glory.
 */
object Economy {
    // ---------------------------------------------------------------------------- upgrades

    /** Bolts to go from [level] to the next, times the debug menu's cost [factor] (never more than [Progression.MAX_COST_FACTOR]). */
    fun upgradeCost(level: Int, factor: Float = 1f): Int =
        roundHalfUp(Balance.upgradeCostFrom(level) * factor.coerceIn(0f, Progression.MAX_COST_FACTOR).toDouble())

    /** Rounds halves up, as `Math.round` does. */
    fun roundHalfUp(x: Double): Int = floor(x + 0.5).toInt()

    fun upgrade(save: SaveData, fighter: FighterId, factor: Float = 1f, noCap: Boolean = false): Done<Int> {
        val p = save.progress(fighter)
        if (!p.unlocked) throw Refused(409, "that fighter isn't unlocked")
        if (p.level >= if (noCap) Balance.LEVEL_LIMIT else Balance.MAX_LEVEL) throw Refused(409, "that fighter is at the top level")
        val cost = upgradeCost(p.level, factor)
        if (save.bolts < cost) throw Refused(402, "not enough Upgrade Credits")
        return Done(save.copy(bolts = save.bolts - cost, fighters = save.fighters + (fighter to p.copy(level = p.level + 1))), cost)
    }

    // ---------------------------------------------------------------------------- rewards

    /** What is paid out instead of something the player already owns. */
    fun compensation(reward: Reward): Reward = when (reward) {
        is Reward.UnlockFighter -> Reward.Bolts(300)
        is Reward.SkinReward -> Reward.Prisms(30)
        is Reward.Bundle -> Reward.Bundle(reward.items.map { compensation(it) })
        else -> reward
    }

    /** Adds a reward to the save and returns what was actually given. */
    fun grant(save: SaveData, reward: Reward): Done<Reward> {
        if (reward is Reward.Bundle) {
            var s = save
            // One flat list: Credits that unlocked a fighter come back as a bundle of their own, which is unpacked here.
            val given = reward.items.flatMap { item -> grant(s, item).also { s = it.save }.value.let { if (it is Reward.Bundle) it.items else listOf(it) } }
            return Done(s, Reward.Bundle(given))
        }
        if (reward is Reward.Credits) return fillRoad(save, reward.amount)
        val actual = if (Progression.owns(save, reward)) compensation(reward) else reward
        return Done(apply(save, actual), actual)
    }

    /**
     * Credits go straight onto the Spark Road. The moment they cover the next fighter along it that fighter is
     * unlocked and what is left carries on toward the one after. Once every fighter is unlocked Credits have
     * nowhere to go, so they are paid as Upgrade Credits instead, one for one.
     */
    private fun fillRoad(save: SaveData, amount: Int): Done<Reward> {
        if (amount <= 0) return Done(save, Reward.Credits(0))
        if (SparkRoad.next(save) == null) return Done(save.copy(bolts = save.bolts + amount), Reward.Bolts(amount))
        var s = save.copy(credits = save.credits + amount)
        val unlocked = ArrayList<Reward>()
        while (true) {
            val step = SparkRoad.next(s) ?: break
            if (s.credits < step.cost) break
            s = apply(s.copy(credits = s.credits - step.cost), Reward.UnlockFighter(step.fighter))
            unlocked += Reward.UnlockFighter(step.fighter)
        }
        if (SparkRoad.next(s) == null && s.credits > 0) s = s.copy(bolts = s.bolts + s.credits, credits = 0)
        return Done(s, if (unlocked.isEmpty()) Reward.Credits(amount) else Reward.Bundle(listOf<Reward>(Reward.Credits(amount)) + unlocked))
    }

    /** The Credits a reward put on the road (looking inside bundles). */
    fun creditsIn(reward: Reward): Int = when (reward) {
        is Reward.Credits -> reward.amount
        is Reward.Bundle -> reward.items.sumOf { creditsIn(it) }
        else -> 0
    }

    /** The fighters a reward unlocked (looking inside bundles). */
    fun fightersIn(reward: Reward): List<FighterId> = when (reward) {
        is Reward.UnlockFighter -> listOf(reward.fighter)
        is Reward.Bundle -> reward.items.flatMap { fightersIn(it) }
        else -> emptyList()
    }

    private fun apply(save: SaveData, reward: Reward): SaveData = when (reward) {
        is Reward.Bolts -> save.copy(bolts = save.bolts + reward.amount)
        is Reward.Prisms -> save.copy(prisms = save.prisms + reward.amount)
        is Reward.Credits -> save.copy(credits = save.credits + reward.amount)   // (see fillRoad)
        is Reward.UnlockFighter -> save.copy(fighters = save.fighters + (reward.fighter to save.progress(reward.fighter).copy(unlocked = true)))
        is Reward.SkinReward -> save.progress(reward.fighter).let { p ->
            save.copy(fighters = save.fighters + (reward.fighter to p.copy(ownedSkins = p.ownedSkins + 0 + reward.skinIndex)))
        }
        is Reward.Bundle -> reward.items.fold(save) { s, item -> apply(s, item) }
    }

    // ---------------------------------------------------------------------------- the shop

    /** The reward and CPU Chip price of a standing shop item, or null if there is no such item. */
    fun shopItem(key: String): Pair<Reward, Int>? {
        Shop.creditPacks.firstOrNull { it.key == key }?.let { return Reward.Credits(it.credits) to it.pricePrisms }
        Shop.boltCrates.firstOrNull { it.key == key }?.let { return Reward.Bolts(it.bolts) to it.pricePrisms }
        Shop.fighterOffers.firstOrNull { it.key == key }?.let { return Reward.UnlockFighter(it.fighter) to it.pricePrisms }
        Shop.skinOffers.firstOrNull { it.key == key }?.let { return Reward.SkinReward(it.fighter, it.skinIndex) to it.pricePrisms }
        return null
    }

    /** Buys a standing shop item with CPU Chips. */
    fun buy(save: SaveData, key: String): Done<Reward> {
        val (reward, price) = shopItem(key) ?: throw Refused(404, "no such shop item")
        if (Progression.owns(save, reward)) throw Refused(409, "already owned")
        if (reward is Reward.SkinReward && !save.progress(reward.fighter).unlocked) throw Refused(409, "unlock the fighter first")
        if (save.prisms < price) throw Refused(402, "not enough CPU Chips")
        return grant(save.copy(prisms = save.prisms - price), reward)
    }

    fun claimGift(save: SaveData, day: Long): Done<Reward> {
        if (save.lastDailyGiftDay == day) throw Refused(409, "today's gift is already claimed")
        return grant(save.copy(lastDailyGiftDay = day), Shop.dailyGift(day))
    }

    fun claimMilestone(save: SaveData, cups: Int): Done<Reward> {
        val milestone = CupTrack.milestones.firstOrNull { it.cups == cups } ?: throw Refused(404, "no such Cup Track reward")
        if (cups > save.bestCups) throw Refused(409, "not reached yet")
        if (cups in save.claimedMilestones) throw Refused(409, "already claimed")
        return grant(save.copy(claimedMilestones = save.claimedMilestones + cups), milestone.reward)
    }

    // ---------------------------------------------------------------------------- deals

    /** Pays for a deal and returns the reward. [purchased] is how many times it has been bought. */
    private fun payForDeal(save: SaveData, deal: CustomOffer, purchased: Int, now: Long): Done<Reward> {
        if (deal.expired(now)) throw Refused(409, "this deal has ended")
        if (deal.limit in 1..purchased) throw Refused(409, "sold out")
        var s = save
        when (deal.currency) {
            Currency.BOLTS -> { if (s.bolts < deal.price) throw Refused(402, "not enough Upgrade Credits"); s = s.copy(bolts = s.bolts - deal.price) }
            Currency.PRISMS -> { if (s.prisms < deal.price) throw Refused(402, "not enough CPU Chips"); s = s.copy(prisms = s.prisms - deal.price) }
            Currency.FREE -> {}
        }
        return grant(s, deal.reward)
    }

    /** Buys a deal made with the Offer Creator. */
    fun buyDeal(save: SaveData, id: Long, now: Long): Done<Reward> {
        val deal = save.customOffers.firstOrNull { it.id == id } ?: throw Refused(404, "no such deal")
        val done = payForDeal(save, deal, deal.purchased, now)
        return Done(done.save.copy(customOffers = done.save.customOffers.map { if (it.id == id) it.copy(purchased = it.purchased + 1) else it }), done.value)
    }

    /** The deal a developer made, checked and tidied. */
    fun cleanDeal(o: CustomOffer, id: Long): CustomOffer {
        val deal = o.copy(
            id = id, title = o.title.filter { !it.isISOControl() }.take(24).trim().ifBlank { "Offer" },
            bolts = o.bolts.coerceIn(0, 1_000_000), prisms = o.prisms.coerceIn(0, 1_000_000),
            price = o.price.coerceIn(0, 1_000_000), wasPrice = o.wasPrice.coerceIn(0, 1_000_000),
            limit = o.limit.coerceIn(0, 1000), theme = o.theme.coerceIn(0, 50), purchased = 0,
        )
        if (deal.skinFighter != null && deal.skinIndex !in Balance.fighter(deal.skinFighter).skins.indices) throw Refused(400, "no such colourway")
        if (deal.contents.isEmpty()) throw Refused(400, "a deal has to contain something")
        return deal
    }

    fun createDeal(save: SaveData, offer: CustomOffer): Done<Long> {
        val id = (save.customOffers.maxOfOrNull { it.id } ?: 0L) + 1
        return Done(save.copy(customOffers = save.customOffers + cleanDeal(offer, id)), id)
    }

    fun deleteDeal(save: SaveData, id: Long): Done<Boolean> =
        Done(save.copy(customOffers = save.customOffers.filter { it.id != id }), save.customOffers.any { it.id == id })

    // ---------------------------------------------------------------------------- daily offers

    /** Every offer the shop may pick from each day: (title, offer). The day's offers are [dailyOffers]. */
    val dailyPool: List<CustomOffer> = listOf(
        CustomOffer(0, "Pocket Change", bolts = 60, currency = Currency.FREE),
        CustomOffer(0, "Lucky Find", prisms = 3, currency = Currency.FREE),
        CustomOffer(0, "Upgrade Credit Pouch", bolts = 300, currency = Currency.PRISMS, price = 6, wasPrice = 10, theme = 1),
        CustomOffer(0, "Upgrade Credit Crate", bolts = 1000, currency = Currency.PRISMS, price = 18, wasPrice = 25, theme = 1),
        CustomOffer(0, "Upgrade Credit Haul", bolts = 2500, currency = Currency.PRISMS, price = 35, wasPrice = 50, theme = 2),
        CustomOffer(0, "Chip Pinch", prisms = 12, currency = Currency.BOLTS, price = 500, theme = 3),
        CustomOffer(0, "Chip Stack", prisms = 30, currency = Currency.BOLTS, price = 1200, wasPrice = 1500, theme = 3),
        CustomOffer(0, "Double Up", bolts = 400, prisms = 10, currency = Currency.PRISMS, price = 14, wasPrice = 20, theme = 4),
    )
    const val OFFERS_PER_DAY = 3

    /** The day's offers: the same all day, different each day. [CustomOffer.id] is the place in the list. */
    fun dailyOffers(day: Long, expiresAt: Long = 0): List<CustomOffer> =
        dailyPool.shuffled(Random(day * 7919 + 17)).take(OFFERS_PER_DAY).mapIndexed { i, o -> o.copy(id = i.toLong(), limit = 1, expiresAt = expiresAt) }

    /** Buys today's offer number [index], once. */
    fun buyDaily(save: SaveData, index: Int, day: Long): Done<Reward> {
        val offers = dailyOffers(day)
        if (index !in offers.indices) throw Refused(404, "no such offer today")
        val offer = offers[index]
        val bought = if (save.dailyDay == day) save.dailyBought else emptySet()
        if (offer.title in bought) throw Refused(409, "already bought today")
        // An offer for something already owned would just pay out; say no instead.
        if (offer.contents.any { Progression.owns(save, it) }) throw Refused(409, "you already own what this offer gives")
        val done = payForDeal(save, offer, 0, 0)
        return Done(done.save.copy(dailyDay = day, dailyBought = bought + offer.title), done.value)
    }

    fun boughtToday(save: SaveData, title: String, day: Long) = save.dailyDay == day && title in save.dailyBought

    // ---------------------------------------------------------------------------- what a match pays

    private val PLACEMENT_BOLTS = intArrayOf(30, 26, 22, 18, 15, 12, 10, 8, 6, 5)

    fun matchBolts(mode: GameMode, outcome: MatchOutcome, placement: Int, kos: Int, difficulty: BotDifficulty): Int {
        if (mode == GameMode.TRAINING) return 0
        val koBonus = 2 * kos.coerceIn(0, 6)
        val base = if (mode == GameMode.LAST_SPARK) PLACEMENT_BOLTS[(placement - 1).coerceIn(0, PLACEMENT_BOLTS.lastIndex)]
        else when (outcome) { MatchOutcome.VICTORY -> 24; MatchOutcome.DRAW -> 14; MatchOutcome.DEFEAT -> 10 }
        return roundHalfUp((base + koBonus) * difficulty.boltMultiplier.toDouble())
    }

    /** CPU Chips for the first victory of the day. Boss Mode and the Training Area don't count. */
    fun firstWinPrisms(mode: GameMode, outcome: MatchOutcome, save: SaveData, day: Long): Int =
        if (mode == GameMode.BOSS || mode == GameMode.TRAINING || outcome != MatchOutcome.VICTORY || save.lastFirstWinDay == day) 0 else Balance.FIRST_WIN_PRISMS

    private fun good(mode: GameMode, outcome: MatchOutcome, placement: Int) =
        outcome == MatchOutcome.VICTORY || (mode == GameMode.LAST_SPARK && placement in 1..4)

    /** Credits for playing a match: a few for turning up, more for a good finish. */
    fun matchCredits(mode: GameMode, outcome: MatchOutcome, placement: Int): Int = when {
        mode == GameMode.TRAINING -> 0
        mode == GameMode.BOSS -> if (good(mode, outcome, placement)) 3 else 1
        else -> if (good(mode, outcome, placement)) 6 else 2
    }

    /** A win in Knockout Rush, or a top-4 finish in Last Spark, earns an Arena Box. */
    fun earnsDrop(mode: GameMode, outcome: MatchOutcome, placement: Int): Boolean = when (mode) {
        GameMode.LAST_SPARK -> placement in 1..4
        GameMode.KNOCKOUT_RUSH -> outcome == MatchOutcome.VICTORY
        else -> false
    }

    /**
     * Settles a finished match: what it pays in Bolts, CPU Chips, Credits and Spark Pass points goes into the save,
     * and the verdict says the rest (Cups and Arena Boxes are applied by [Progression.applyMatch] from it).
     */
    fun settleMatch(save: SaveData, report: MatchReport, day: Long): Done<ServerVerdict> {
        val mode = report.mode
        // The Training Area is practice: nothing is earned in it.
        if (mode == GameMode.TRAINING) return Done(save, ServerVerdict(0, save.cups, false, save.capsules, Progression.capsulesLeftToday(save, day)))
        val cups = (save.cups + Trophies.cupDelta(mode, report.outcome, report.placement, save.cups, report.mvp)).coerceAtLeast(0)
        val mvpCups = Trophies.cupDelta(mode, report.outcome, report.placement, 1_000_000_000, true) - Trophies.cupDelta(mode, report.outcome, report.placement, 1_000_000_000, false)
        val earnedToday = if (save.capsuleDay == day) save.capsulesEarnedToday else 0
        val drop = earnsDrop(mode, report.outcome, report.placement) && earnedToday < SparkCapsules.PER_DAY
        val bolts = matchBolts(mode, report.outcome, report.placement, report.kos, report.difficulty)
        val prisms = firstWinPrisms(mode, report.outcome, save, day)
        val fighterBefore = save.progress(report.fighter).cups
        val fighterAfter = (fighterBefore + cups - save.cups).coerceAtLeast(0)
        var s = save.copy(
            bolts = save.bolts + bolts, prisms = save.prisms + prisms,
            lastFirstWinDay = if (prisms > 0) day else save.lastFirstWinDay,
            fighters = save.fighters + (report.fighter to save.progress(report.fighter).copy(cups = fighterAfter)),
        )
        val paid = grant(s, Reward.Credits(matchCredits(mode, report.outcome, report.placement))).also { s = it.save }.value
        val drops = save.capsules + if (drop) 1 else 0
        return Done(s, ServerVerdict(
            cupDelta = cups - save.cups, cups = cups, drop = drop, drops = drops, dropsLeftToday = SparkCapsules.PER_DAY - earnedToday - if (drop) 1 else 0,
            bolts = bolts + ((paid as? Reward.Bolts)?.amount ?: 0), firstWinPrisms = prisms, credits = creditsIn(paid), unlocked = fightersIn(paid),
            fighterCupsBefore = fighterBefore, fighterCups = fighterAfter, mvpCups = mvpCups,
        ))
    }

    // ---------------------------------------------------------------------------- Arena Boxes

    private const val DROP_BUFF = 3
    /** Every Credit amount a drop gives is multiplied by this: the Spark Road asks for thousands. */
    const val CREDIT_BUFF = 12
    /** "Open all" opens the boxes held at that moment; this is the most it goes through. */
    const val MAX_OPEN_ALL = 10_000

    private fun rollTier(rng: Random, luck: Float): Int {
        var roll = rng.nextDouble()
        SparkCapsules.odds(luck).forEachIndexed { i, chance ->
            roll -= chance
            if (roll < 0) return i
        }
        return 0
    }

    /** How many items an Arena Box holds: [SparkCapsules.BOX_ITEMS], and with some luck a few more. */
    private fun rollItems(rng: Random, luck: Float): Int {
        var items = SparkCapsules.BOX_ITEMS
        while (items < SparkCapsules.MAX_ITEMS && rng.nextFloat() < SparkCapsules.moreItemsChance(luck)) items++
        return items
    }

    /** What a drop of [tier] gives this player. Never a colourway they already own. */
    private fun rollReward(tier: CapsuleTier, save: SaveData, rng: Random): Reward {
        fun between(lo: Int, hi: Int) = rng.nextInt(lo, hi + 1)
        fun bolts(lo: Int, hi: Int): Reward = Reward.Bolts(between(lo, hi) / 5 * 5 * DROP_BUFF)
        fun prisms(lo: Int, hi: Int): Reward = Reward.Prisms(between(lo, hi) * DROP_BUFF)
        // Credits unlock fighters on the Spark Road. Drops are the way to get them in any number.
        fun credits(lo: Int, hi: Int): Reward = Reward.Credits(between(lo, hi) * CREDIT_BUFF)
        fun newSkin(): Reward? {
            val choices = FighterId.entries.filter { save.progress(it).unlocked }.flatMap { f ->
                Balance.fighter(f).skins.indices.filter { it !in save.progress(f).ownedSkins }.map { Reward.SkinReward(f, it) }
            }
            return if (choices.isEmpty()) null else choices[rng.nextInt(choices.size)]
        }
        return when (tier) {
            CapsuleTier.SCRAP -> bolts(60, 120)
            CapsuleTier.TUNED -> when (rng.nextInt(4)) { 0 -> prisms(15, 25); 1 -> credits(8, 14); else -> bolts(180, 300) }
            CapsuleTier.CHARGED -> when (rng.nextInt(3)) { 0 -> prisms(35, 50); 1 -> credits(20, 30); else -> bolts(400, 600) }
            CapsuleTier.OVERCLOCKED -> {
                val skin = if (rng.nextInt(2) == 0) newSkin() else null
                val pick = rng.nextInt(3)
                skin ?: when (pick) { 0 -> prisms(80, 110); 1 -> credits(45, 60); else -> bolts(1000, 1300) }
            }
            CapsuleTier.PRISMATIC -> if (rng.nextInt(2) == 0) credits(120, 160) else (newSkin() ?: prisms(250, 300))
            // Ultra, the jackpot: a colourway (while any is left) plus a pile of every currency.
            CapsuleTier.ULTRA -> Reward.Bundle(listOfNotNull(newSkin(), credits(250, 300), prisms(400, 500), bolts(2000, 2500)))
        }
    }

    /**
     * Opens up to [most] of the Arena Boxes the player holds (null = all of them, up to [MAX_OPEN_ALL]) and adds what
     * came out to the save. [free]: none is used up, and there are always more (the Chaos Command Center, and the
     * Arena Boxes only mode); [most] must then say how many, since there is no count to go by. Each item is added
     * before the next is rolled, so a box never holds the same colourway twice. Empty if there was nothing to open.
     */
    fun openDrops(save: SaveData, luck: Float, free: Boolean, most: Int?, rng: Random): Done<List<CapsuleResult>> {
        var s = save
        var drops = save.capsules
        val limit = most ?: minOf(drops, MAX_OPEN_ALL)
        val luck = luck.coerceIn(0f, SparkCapsules.MAX_LUCK)
        val results = ArrayList<CapsuleResult>()
        while (results.size < limit && (drops > 0 || free)) {
            results += CapsuleResult(List(rollItems(rng, luck)) {
                val tier = CapsuleTier.entries[rollTier(rng, luck)]
                BoxItem(tier, grant(s, rollReward(tier, s, rng)).also { s = it.save }.value)
            })
            if (!free) drops--
        }
        if (results.isEmpty()) return Done(save, results)
        return Done(s.copy(capsules = drops, capsulesOpened = s.capsulesOpened + results.size), results)
    }

    // ---------------------------------------------------------------------------- developers

    /** The debug menu's hand-outs. */
    fun devGrant(save: SaveData, cups: Int = 0, drops: Int = 0, bolts: Int = 0, prisms: Int = 0, credits: Int = 0): SaveData {
        val newCups = (save.cups + cups).coerceAtLeast(0)
        val base = save.copy(
            cups = newCups, bestCups = maxOf(save.bestCups, newCups), capsules = (save.capsules + drops).coerceAtLeast(0),
            bolts = (save.bolts + bolts).coerceAtLeast(0), prisms = (save.prisms + prisms).coerceAtLeast(0),
        )
        return if (credits > 0) grant(base, Reward.Credits(credits)).save else base
    }
}

/** What each mode pays in Cups. Cups don't depend on how hard the bots are, and the Training Area never pays. */
object Trophies {
    /** [places]: Cups by finishing place (a mode with them is paid by place alone). Otherwise a victory, a draw, and a defeat that costs 1 Cup per [lossStep] held, up to [maxLoss]. */
    private class Pays(val places: List<Int>? = null, val win: Int = 0, val mvpBonus: Int = 0, val draw: Int = 0, val maxLoss: Int = 0, val lossStep: Int = 0)

    private val table = mapOf(
        GameMode.LAST_SPARK to Pays(places = listOf(25, 22, 20, 17, 14, 12, 7, 3, 0, 0)),
        GameMode.KNOCKOUT_RUSH to Pays(win = 8, mvpBonus = 2, draw = 1, maxLoss = 6, lossStep = 80),
        GameMode.BOSS to Pays(win = 5),
    )

    /** How a match changes a player's Cups. Never takes them below zero. */
    fun cupDelta(mode: GameMode, outcome: MatchOutcome, placement: Int, cups: Int, mvp: Boolean): Int {
        val pays = table[mode] ?: return 0
        val delta = when {
            pays.places != null -> pays.places[(placement - 1).coerceIn(0, pays.places.lastIndex)]
            outcome == MatchOutcome.VICTORY -> pays.win + if (mvp) pays.mvpBonus else 0
            outcome == MatchOutcome.DRAW -> pays.draw
            else -> -(if (pays.lossStep > 0) minOf(pays.maxLoss, cups / pays.lossStep) else pays.maxLoss)
        }
        return maxOf(delta, -cups)
    }
}
