package io.github.projectwip.data

/** What the player brought out of a match. Built by the match, applied by [Progression.applyMatch]. */
data class MatchReport(
    val outcome: MatchOutcome,
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
        val cupDelta = Balance.cupsFor(report.outcome, save.cups, report.difficulty, report.mvp)
        val bolts = Balance.boltsFor(report.outcome, report.kos, report.difficulty)
        val firstWin = report.outcome == MatchOutcome.VICTORY && save.lastFirstWinDay != today
        val prisms = if (firstWin) Balance.FIRST_WIN_PRISMS else 0
        val newCups = (save.cups + cupDelta).coerceAtLeast(0)
        val reached = CupTrack.milestones.filter { it.cups in (save.bestCups + 1)..newCups }
        val next = save.copy(
            cups = newCups,
            bestCups = maxOf(save.bestCups, newCups),
            bolts = save.bolts + bolts,
            prisms = save.prisms + prisms,
            lastFirstWinDay = if (firstWin) today else save.lastFirstWinDay,
            matchesPlayed = save.matchesPlayed + 1,
            victories = save.victories + if (report.outcome == MatchOutcome.VICTORY) 1 else 0,
            totalKos = save.totalKos + report.kos,
        )
        return next to MatchRewards(save.cups, newCups - save.cups, bolts, prisms, reached)
    }

    // ---------------- Upgrades ----------------

    fun canUpgrade(save: SaveData, id: FighterId): Boolean {
        val p = save.progress(id)
        val cost = Balance.upgradeCostFrom(p.level) ?: return false
        return p.unlocked && save.bolts >= cost
    }

    fun upgrade(save: SaveData, id: FighterId): SaveData? {
        if (!canUpgrade(save, id)) return null
        val p = save.progress(id)
        val cost = Balance.upgradeCostFrom(p.level)!!
        return save.copy(
            bolts = save.bolts - cost,
            fighters = save.fighters + (id to p.copy(level = p.level + 1)),
        )
    }

    fun statPreview(def: FighterDef, level: Int): List<StatPreview> {
        val next = if (level < Balance.MAX_LEVEL) level + 1 else null
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
        is Reward.UnlockFighter -> save.progress(reward.fighter).unlocked
        is Reward.SkinReward -> reward.skinIndex in save.progress(reward.fighter).ownedSkins
        else -> false
    }

    fun grant(save: SaveData, reward: Reward): SaveData = when (reward) {
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
