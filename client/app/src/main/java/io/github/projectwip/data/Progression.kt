package io.github.projectwip.data

/** What the player brought out of a match. Built by the match, applied by [Progression.applyMatch]. */
data class MatchReport(
    val outcome: MatchOutcome,
    val mode: GameMode = GameMode.KNOCKOUT_RUSH,
    /** 1-based finishing place in free-for-all modes; 0 for team modes. */
    val placement: Int = 0,
    val players: Int = 6,
    val fighter: FighterId,
    val kos: Int,
    val deaths: Int,
    val damageDealt: Int,
    val mvp: Boolean,
    val difficulty: BotDifficulty,
    val blueScore: Int,
    val redScore: Int,
)

/** What the result screen shows. Before/after values let it animate the change. */
data class MatchRewards(
    val cupsBefore: Int,
    val cupDelta: Int,
    val bolts: Int,
    val firstWinPrisms: Int,
    val newlyReachedMilestones: List<Milestone>,
    val capsuleEarned: Boolean = false,
    /** Spark Capsules that can still be earned today, after this match. */
    val capsulesLeftToday: Int = 0,
)

/** One stat row on the upgrade screen. */
data class StatPreview(val label: String, val current: Int, val next: Int?, val suffix: String = "") {
    val delta: Int? get() = next?.let { it - current }
}

sealed interface PurchaseResult {
    data object Ok : PurchaseResult
    data object NotEnough : PurchaseResult
    data object AlreadyOwned : PurchaseResult
}

/** Pure progression rules: every function takes a save and returns a new one. */
object Progression {

    fun applyMatch(save: SaveData, report: MatchReport, today: Long): Pair<SaveData, MatchRewards> {
        val ffa = report.mode == GameMode.LAST_SPARK
        // Boss Mode pays Bolts only: its boss has fixed stats, so Cups and the daily Prisms stay out of it.
        val boss = report.mode == GameMode.BOSS
        val cupDelta = if (boss) 0 else if (ffa) Balance.cupsForPlacement(report.placement, save.cups, report.difficulty)
            else Balance.cupsFor(report.outcome, save.cups, report.difficulty, report.mvp)
        val bolts = if (ffa) Balance.boltsForPlacement(report.placement, report.kos, report.difficulty)
            else Balance.boltsFor(report.outcome, report.kos, report.difficulty)
        val firstWin = !boss && report.outcome == MatchOutcome.VICTORY && save.lastFirstWinDay != today
        val prisms = if (firstWin) Balance.FIRST_WIN_PRISMS else 0
        val newCups = (save.cups + cupDelta).coerceAtLeast(0)
        val reached = CupTrack.milestones.filter { it.cups in (save.bestCups + 1)..newCups }
        val earnedBefore = if (save.capsuleDay == today) save.capsulesEarnedToday else 0
        val capsule = SparkCapsules.earns(report) && earnedBefore < SparkCapsules.PER_DAY
        val earnedNow = earnedBefore + if (capsule) 1 else 0
        val next = save.copy(
            capsules = save.capsules + if (capsule) 1 else 0,
            capsuleDay = today,
            capsulesEarnedToday = earnedNow,
            cups = newCups,
            bestCups = maxOf(save.bestCups, newCups),
            bolts = save.bolts + bolts,
            prisms = save.prisms + prisms,
            lastFirstWinDay = if (firstWin) today else save.lastFirstWinDay,
            matchesPlayed = save.matchesPlayed + 1,
            victories = save.victories + if (report.outcome == MatchOutcome.VICTORY) 1 else 0,
            totalKos = save.totalKos + report.kos,
        )
        return next to MatchRewards(save.cups, newCups - save.cups, bolts, prisms, reached, capsule, SparkCapsules.PER_DAY - earnedNow)
    }

    // ---------------- Spark Capsules ----------------

    fun capsulesLeftToday(save: SaveData, today: Long): Int =
        SparkCapsules.PER_DAY - if (save.capsuleDay == today) save.capsulesEarnedToday else 0

    /** Opens one capsule: rolls its tier and reward from the save's seed and grants it. Null if there is none to open. */
    fun openCapsule(save: SaveData): Pair<SaveData, CapsuleResult>? {
        val infinite = save.settings.debugInfiniteCapsules
        if (save.capsules <= 0 && !infinite) return null
        val rng = kotlin.random.Random(save.capsuleSeed)
        // Pieces from an earlier split are opened first, and roll better than a plain one.
        val boosted = save.boostedCapsules > 0
        val luck = save.settings.debugLuck + if (boosted) SparkCapsules.SPLIT_LUCK else 0f
        val rolled = SparkCapsules.rollTier(rng, luck)
        val tier = if (boosted && rolled == CapsuleTier.SCRAP) CapsuleTier.TUNED else rolled
        val reward = SparkCapsules.rollReward(tier, save, rng)
        val pieces = SparkCapsules.rollPieces(rng, save.settings.debugLuck)
        val left = (if (infinite) save.capsules else save.capsules - 1) + pieces - 1
        val next = grant(save, reward).copy(
            capsules = left, capsulesOpened = save.capsulesOpened + 1, capsuleSeed = rng.nextLong(),
            boostedCapsules = (save.boostedCapsules - if (boosted) 1 else 0) + pieces - 1,
        )
        return next to CapsuleResult(tier, reward, pieces)
    }

    // ---------------- Upgrades ----------------

    /** At [Balance.MAX_LEVEL] (or beyond) with the cap in force. */
    fun levelCapped(save: SaveData, id: FighterId): Boolean =
        save.progress(id).level >= Balance.MAX_LEVEL && !save.settings.debugNoLevelCap

    /** The debug menu's upgrade-cost slider goes from free up to this many times the normal price. */
    const val MAX_COST_FACTOR = 3f

    /** What the next upgrade of [id] costs right now (the balance table, times the debug cost factor). */
    fun upgradeCost(save: SaveData, id: FighterId): Int =
        Math.round(Balance.upgradeCostFrom(save.progress(id).level) * save.settings.debugUpgradeCost)

    fun canUpgrade(save: SaveData, id: FighterId): Boolean {
        val p = save.progress(id)
        val cost = upgradeCost(save, id)
        return p.unlocked && !levelCapped(save, id) && save.bolts >= cost
    }

    fun upgrade(save: SaveData, id: FighterId): SaveData? {
        if (!canUpgrade(save, id)) return null
        val p = save.progress(id)
        val cost = upgradeCost(save, id)
        return save.copy(
            bolts = save.bolts - cost,
            fighters = save.fighters + (id to p.copy(level = p.level + 1)),
        )
    }

    fun statPreview(def: FighterDef, level: Int, capped: Boolean = false): List<StatPreview> {
        val next: Int? = if (capped) null else level + 1
        fun line(label: String, s: StatLine, suffix: String = "") = StatPreview(label, s.at(level), next?.let { s.at(it) }, suffix)
        val shots = def.attack.projectiles
        return listOf(
            line("Health", def.health),
            line(if (shots > 1) "${def.attackName} (×$shots)" else def.attackName, def.attackDamage),
            line(def.superSpec.name, def.superDamage, if (def.superSpec.projectiles > 1) " ×${def.superSpec.projectiles}" else ""),
        )
    }

    // ---------------- Cup Track ----------------

    fun claimable(save: SaveData): List<Milestone> =
        CupTrack.milestones.filter { it.cups <= save.bestCups && it.cups !in save.claimedMilestones }

    /** Claims a milestone. Returns the new save and the reward actually granted (after duplicate compensation). */
    fun claimMilestone(save: SaveData, milestone: Milestone): Pair<SaveData, Reward>? {
        if (milestone.cups > save.bestCups || milestone.cups in save.claimedMilestones) return null
        val actual = if (owns(save, milestone.reward)) CupTrack.duplicateCompensation(milestone.reward) else milestone.reward
        val granted = grant(save, actual).copy(claimedMilestones = save.claimedMilestones + milestone.cups)
        return granted to actual
    }

    fun owns(save: SaveData, reward: Reward): Boolean = when (reward) {
        is Reward.Bundle -> false
        is Reward.UnlockFighter -> save.progress(reward.fighter).unlocked
        is Reward.SkinReward -> reward.skinIndex in save.progress(reward.fighter).ownedSkins
        else -> false
    }

    fun grant(save: SaveData, reward: Reward): SaveData = when (reward) {
        // Items already owned inside a bundle are compensated instead of wasted.
        is Reward.Bundle -> reward.items.fold(save) { s, r -> grant(s, if (owns(s, r)) CupTrack.duplicateCompensation(r) else r) }
        is Reward.Bolts -> save.copy(bolts = save.bolts + reward.amount)
        is Reward.Prisms -> save.copy(prisms = save.prisms + reward.amount)
        is Reward.UnlockFighter -> {
            val p = save.progress(reward.fighter)
            save.copy(fighters = save.fighters + (reward.fighter to p.copy(unlocked = true)))
        }
        is Reward.SkinReward -> {
            val p = save.progress(reward.fighter)
            save.copy(fighters = save.fighters + (reward.fighter to p.copy(ownedSkins = p.ownedSkins + reward.skinIndex)))
        }
    }

    // ---------------- Shop ----------------

    fun buy(save: SaveData, item: ShopItem): Pair<SaveData, PurchaseResult> {
        val reward = when (item) {
            is ShopItem.FighterOffer -> Reward.UnlockFighter(item.fighter)
            is ShopItem.SkinOffer -> Reward.SkinReward(item.fighter, item.skinIndex)
            is ShopItem.BoltCrate -> Reward.Bolts(item.bolts)
        }
        if (owns(save, reward)) return save to PurchaseResult.AlreadyOwned
        if (save.prisms < item.pricePrisms) return save to PurchaseResult.NotEnough
        return grant(save.copy(prisms = save.prisms - item.pricePrisms), reward) to PurchaseResult.Ok
    }

    // ---------------- Custom offers

    fun addOffer(save: SaveData, offer: CustomOffer): SaveData =
        save.copy(customOffers = save.customOffers + offer)

    fun removeOffer(save: SaveData, id: Long): SaveData =
        save.copy(customOffers = save.customOffers.filterNot { it.id == id })

    sealed interface OfferResult {
        data class Ok(val reward: Reward) : OfferResult
        data object NotEnough : OfferResult
        data object Unavailable : OfferResult
    }

    fun buyOffer(save: SaveData, id: Long, now: Long): Pair<SaveData, OfferResult> {
        val o = save.customOffers.firstOrNull { it.id == id } ?: return save to OfferResult.Unavailable
        if (o.expired(now) || o.soldOut || o.contents.isEmpty()) return save to OfferResult.Unavailable
        val paid = when (o.currency) {
            Currency.FREE -> save
            Currency.BOLTS -> if (save.bolts < o.price) return save to OfferResult.NotEnough else save.copy(bolts = save.bolts - o.price)
            Currency.PRISMS -> if (save.prisms < o.price) return save to OfferResult.NotEnough else save.copy(prisms = save.prisms - o.price)
        }
        val granted = grant(paid, o.reward)
        val updated = granted.copy(customOffers = granted.customOffers.map { if (it.id == id) it.copy(purchased = it.purchased + 1) else it })
        return updated to OfferResult.Ok(o.reward)
    }

    fun dailyGiftAvailable(save: SaveData, today: Long) = save.lastDailyGiftDay != today

    fun claimDailyGift(save: SaveData, today: Long): Pair<SaveData, Reward>? {
        if (!dailyGiftAvailable(save, today)) return null
        val reward = Shop.dailyGift(today)
        return grant(save, reward).copy(lastDailyGiftDay = today) to reward
    }

    fun selectFighter(save: SaveData, id: FighterId): SaveData =
        if (save.progress(id).unlocked) save.copy(selectedFighter = id) else save

    fun selectSkin(save: SaveData, id: FighterId, skin: Int): SaveData {
        val p = save.progress(id)
        return if (skin in p.ownedSkins) save.copy(fighters = save.fighters + (id to p.copy(skin = skin))) else save
    }
}
