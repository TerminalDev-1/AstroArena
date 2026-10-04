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
    /** Bolts this match paid. */
    val bolts: Int = 0,
    /** Prisms for the first win of the day (0 if this wasn't it). */
    val firstWinPrisms: Int = 0,
    /** How the match went according to the server's own replay of it. Null if the server has no referee running. */
    val judged: JudgedResult? = null,
)

/** A match's result as the server's referee found it by replaying the match from the player's inputs. */
data class JudgedResult(val outcome: MatchOutcome, val placement: Int, val kos: Int, val deaths: Int, val damage: Int, val mvp: Boolean) {
    /** [report] with the referee's findings in place of the device's. */
    fun over(report: MatchReport): MatchReport = report.copy(outcome = outcome, placement = placement, kos = kos, deaths = deaths, damageDealt = damage, mvp = mvp)
}

/**
 * The part of a player's progress the game server keeps: currencies, fighters and claimed rewards. The game
 * shows a copy of it and never changes it by itself; everything bought, upgraded or claimed goes through the server.
 */
data class ServerProfile(
    val bolts: Int,
    val prisms: Int,
    val bestCups: Int,
    /** [FighterProgress.skin] is not the server's business: which colourway is worn is chosen on the device. */
    val fighters: Map<FighterId, FighterProgress>,
    val claimedMilestones: Set<Int>,
    val lastDailyGiftDay: Long,
    val lastFirstWinDay: Long,
)

/** One stat row on the upgrade screen. */
data class StatPreview(val label: String, val current: Int, val next: Int?, val suffix: String = "") {
    val delta: Int? get() = next?.let { it - current }
}

/**
 * Progression rules the game needs for showing things and for what stays on the device. Everything that earns,
 * spends or grants is the server's (`server/astro/economy.py`); this only mirrors and reads.
 */
object Progression {

    /**
     * Records a finished match. Everything it was worth comes from the server's [verdict]: Cups and Spark
     * Drops here, Bolts and Prisms with the profile the server sends alongside ([syncAccount]). With no verdict
     * (an offline match) nothing is earned.
     */
    fun applyMatch(save: SaveData, report: MatchReport, today: Long, verdict: ServerVerdict?): Pair<SaveData, MatchRewards> {
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
            matchesPlayed = save.matchesPlayed + 1,
            victories = save.victories + if (report.outcome == MatchOutcome.VICTORY) 1 else 0,
            totalKos = save.totalKos + report.kos,
        )
        val rewards = MatchRewards(newCups - cupDelta, cupDelta, verdict?.bolts ?: 0, verdict?.firstWinPrisms ?: 0, reached, verdict?.drop == true, leftToday, online = verdict != null)
        return next to rewards
    }

    /**
     * Takes on what the server holds for this player: Cups and Spark Drops, and (when given) the [profile] and
     * the shop [deals]. These are totals to show, so applying the same ones twice changes nothing.
     */
    fun syncAccount(
        save: SaveData, cups: Int, drops: Int, dropsLeftToday: Int, today: Long,
        profile: ServerProfile? = null, deals: List<CustomOffer>? = null,
    ): SaveData {
        val base = save.copy(
            cups = cups.coerceAtLeast(0), bestCups = maxOf(save.bestCups, cups),
            capsules = drops.coerceAtLeast(0), capsuleDay = today, capsulesEarnedToday = SparkCapsules.PER_DAY - dropsLeftToday,
            customOffers = deals ?: save.customOffers,
        )
        if (profile == null) return base
        val fighters = FighterId.entries.associateWith { id ->
            val theirs = profile.fighters[id] ?: FighterProgress(unlocked = id == FighterId.JUNO)
            val owned = theirs.ownedSkins + 0
            // The colourway being worn is kept, as long as it is still owned.
            theirs.copy(ownedSkins = owned, skin = save.progress(id).skin.takeIf { it in owned } ?: 0)
        }
        return base.copy(
            bolts = profile.bolts.coerceAtLeast(0), prisms = profile.prisms.coerceAtLeast(0),
            bestCups = maxOf(profile.bestCups, cups),
            fighters = fighters,
            selectedFighter = if (fighters[save.selectedFighter]?.unlocked == true) save.selectedFighter else FighterId.JUNO,
            claimedMilestones = profile.claimedMilestones,
            lastDailyGiftDay = profile.lastDailyGiftDay, lastFirstWinDay = profile.lastFirstWinDay,
        )
    }

    /** A save nobody has played on yet: the only kind that is replaced by the copy the server holds. */
    fun isFresh(save: SaveData): Boolean =
        save.matchesPlayed == 0 && save.capsulesOpened == 0 && save.cups == 0 && save.bestCups == 0 &&
            save.fighters.values.all { it.level == 1 } && save.fighters.values.count { it.unlocked } == 1

    // ---------------- Spark Capsules ----------------

    fun capsulesLeftToday(save: SaveData, today: Long): Int =
        SparkCapsules.PER_DAY - if (save.capsuleDay == today) save.capsulesEarnedToday else 0

    /** Counts a Spark Drop as opened. (Its reward arrives with the profile the server sends.) */
    fun dropOpened(save: SaveData): SaveData = save.copy(capsulesOpened = save.capsulesOpened + 1)

    /** Puts the debug menu's cheats back to normal (for players the server doesn't list as developers). */
    fun withoutCheats(settings: Settings): Settings =
        settings.copy(debugLuck = 0f, debugInfiniteCapsules = false, debugNoLevelCap = false, debugUpgradeCost = 1f, devMenu = false)

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

    fun owns(save: SaveData, reward: Reward): Boolean = when (reward) {
        is Reward.Bundle -> false
        is Reward.UnlockFighter -> save.progress(reward.fighter).unlocked
        is Reward.SkinReward -> reward.skinIndex in save.progress(reward.fighter).ownedSkins
        else -> false
    }

    fun dailyGiftAvailable(save: SaveData, today: Long) = save.lastDailyGiftDay != today

    fun selectFighter(save: SaveData, id: FighterId): SaveData =
        if (save.progress(id).unlocked) save.copy(selectedFighter = id) else save

    fun selectSkin(save: SaveData, id: FighterId, skin: Int): SaveData {
        val p = save.progress(id)
        return if (skin in p.ownedSkins) save.copy(fighters = save.fighters + (id to p.copy(skin = skin))) else save
    }
}
