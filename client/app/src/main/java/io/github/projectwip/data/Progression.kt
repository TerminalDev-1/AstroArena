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
    /** False for an offline match: the server wasn't there to award Cups or a Spark Drop. */
    val online: Boolean = true,
)

/**
 * What the game server decided a match was worth. Cups and Spark Drops are the server's to give, so these are
 * totals to adopt, not amounts to add up on the device.
 */
data class ServerVerdict(
    val cupDelta: Int,
    /** The player's Cups after this match. */
    val cups: Int,
    /** This match earned a Spark Drop. */
    val drop: Boolean,
    /** Unopened Spark Drops after this match. */
    val drops: Int,
    val dropsLeftToday: Int,
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

    /**
     * Records a finished match. Bolts and the first-win Prisms are worked out here; Cups and Spark Drops come
     * from the server's [verdict]. With no verdict (an offline match) neither changes.
     */
    fun applyMatch(save: SaveData, report: MatchReport, today: Long, verdict: ServerVerdict?): Pair<SaveData, MatchRewards> {
        val ffa = report.mode == GameMode.LAST_SPARK
        // Boss Mode pays Bolts only: its boss has fixed stats, so the daily Prisms stay out of it.
        val boss = report.mode == GameMode.BOSS
        val bolts = if (ffa) Balance.boltsForPlacement(report.placement, report.kos, report.difficulty)
            else Balance.boltsFor(report.outcome, report.kos, report.difficulty)
        val firstWin = !boss && report.outcome == MatchOutcome.VICTORY && save.lastFirstWinDay != today
        val prisms = if (firstWin) Balance.FIRST_WIN_PRISMS else 0
        val newCups = (verdict?.cups ?: save.cups).coerceAtLeast(0)
        val cupDelta = verdict?.cupDelta ?: 0
        val reached = CupTrack.milestones.filter { it.cups in (save.bestCups + 1)..newCups }
        val leftToday = verdict?.dropsLeftToday ?: capsulesLeftToday(save, today)
        val next = save.copy(
            capsules = verdict?.drops ?: save.capsules,
            capsuleDay = today,
            capsulesEarnedToday = SparkCapsules.PER_DAY - leftToday,
            cups = newCups,
            bestCups = maxOf(save.bestCups, newCups),
            bolts = save.bolts + bolts,
            prisms = save.prisms + prisms,
            lastFirstWinDay = if (firstWin) today else save.lastFirstWinDay,
            matchesPlayed = save.matchesPlayed + 1,
            victories = save.victories + if (report.outcome == MatchOutcome.VICTORY) 1 else 0,
            totalKos = save.totalKos + report.kos,
        )
        return next to MatchRewards(newCups - cupDelta, cupDelta, bolts, prisms, reached, verdict?.drop == true, leftToday, online = verdict != null)
    }

    /** Takes on the Cups and Spark Drops the server holds for this player. */
    fun syncAccount(save: SaveData, cups: Int, drops: Int, dropsLeftToday: Int, today: Long): SaveData = save.copy(
        cups = cups.coerceAtLeast(0), bestCups = maxOf(save.bestCups, cups),
        capsules = drops.coerceAtLeast(0), capsuleDay = today, capsulesEarnedToday = SparkCapsules.PER_DAY - dropsLeftToday,
    )

    /** A save nobody has played on yet: the only kind that is replaced by the copy the server holds. */
    fun isFresh(save: SaveData): Boolean =
        save.matchesPlayed == 0 && save.capsulesOpened == 0 && save.cups == 0 && save.bestCups == 0 &&
            save.fighters.values.all { it.level == 1 } && save.fighters.values.count { it.unlocked } == 1

    // ---------------- Spark Capsules ----------------

    fun capsulesLeftToday(save: SaveData, today: Long): Int =
        SparkCapsules.PER_DAY - if (save.capsuleDay == today) save.capsulesEarnedToday else 0

    /**
     * Adds what came out of a Spark Drop the server opened. The server knows what the player owns, so a
     * duplicate should never arrive; if one does it is compensated rather than wasted.
     */
    fun grantDrop(save: SaveData, result: CapsuleResult): SaveData {
        val reward = if (owns(save, result.reward)) CupTrack.duplicateCompensation(result.reward) else result.reward
        return grant(save, reward).copy(capsulesOpened = save.capsulesOpened + 1)
    }

    /** Puts the debug menu's cheats back to normal (for players the server doesn't list as developers). */
    fun withoutCheats(settings: Settings): Settings =
        settings.copy(debugLuck = 0f, debugInfiniteCapsules = false, debugNoLevelCap = false, debugUpgradeCost = 1f)

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
