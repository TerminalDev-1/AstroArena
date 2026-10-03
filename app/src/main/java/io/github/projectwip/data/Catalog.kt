package io.github.projectwip.data

/** Anything the game can hand to the player. */
sealed interface Reward {
    data class Bolts(val amount: Int) : Reward
    data class Prisms(val amount: Int) : Reward
    data class UnlockFighter(val fighter: FighterId) : Reward
    data class SkinReward(val fighter: FighterId, val skinIndex: Int) : Reward
    /** Several rewards at once (custom shop offers). */
    data class Bundle(val items: List<Reward>) : Reward
}

enum class Currency { FREE, BOLTS, PRISMS }

/**
 * A shop offer created in-game with the Offer Creator. Everything about it is chosen by the player:
 * contents, price, an optional "was" price shown as a discount, expiry, purchase limit and colour theme.
 */
data class CustomOffer(
    val id: Long,
    val title: String,
    val bolts: Int = 0,
    val prisms: Int = 0,
    val fighter: FighterId? = null,
    val skinFighter: FighterId? = null,
    val skinIndex: Int = 0,
    val currency: Currency = Currency.PRISMS,
    val price: Int = 0,
    /** If greater than [price], shown struck-through with a discount badge. 0 = off. */
    val wasPrice: Int = 0,
    /** Epoch millis after which the offer disappears. 0 = never. */
    val expiresAt: Long = 0,
    /** How many times it can be bought. 0 = unlimited. */
    val limit: Int = 1,
    val purchased: Int = 0,
    /** Index into the creator's colour themes. */
    val theme: Int = 0,
) {
    val contents: List<Reward> get() = buildList {
        if (bolts > 0) add(Reward.Bolts(bolts))
        if (prisms > 0) add(Reward.Prisms(prisms))
        fighter?.let { add(Reward.UnlockFighter(it)) }
        skinFighter?.let { add(Reward.SkinReward(it, skinIndex)) }
    }
    val reward: Reward get() = contents.singleOrNull() ?: Reward.Bundle(contents)
    fun expired(now: Long) = expiresAt in 1..now
    val soldOut get() = limit in 1..purchased
    val discountPercent get() = if (wasPrice > price && wasPrice > 0) ((wasPrice - price) * 100 / wasPrice) else 0
}

/** How good a Spark Capsule turned out. Each tier up is rarer and pays better. */
enum class CapsuleTier(val label: String, val color: Long, val weight: Int) {
    SCRAP("Scrap", 0xFF9AA6C0, 40),
    TUNED("Tuned", 0xFF4ED36A, 28),
    CHARGED("Charged", 0xFF2EC4F1, 18),
    OVERCLOCKED("Overclocked", 0xFFFF8A1F, 8),
    PRISMATIC("Prismatic", 0xFFFF6BFF, 4),
    ULTRA("Ultra", 0xFFFFE14D, 2),
}

/** What came out of an opened capsule. */
data class CapsuleResult(
    val tier: CapsuleTier,
    val reward: Reward,
    /** How many capsules this one became while it was being opened (1, 2, 4 or 8); the player keeps the extras. */
    val pieces: Int = 1,
) {
    val split get() = pieces > 1
}

/**
 * Spark Capsules: earned from your first few good finishes each day, opened from the home screen.
 * The tier is rolled when the capsule is opened; the reward never duplicates something already owned.
 */
object SparkCapsules {
    /** Capsules that can be earned per calendar day. */
    const val PER_DAY = 3
    /** Every save starts with one, so the first open doesn't have to be earned. */
    const val STARTING = 1

    /** A win in team modes, or a top-4 finish in free-for-all, earns a capsule. */
    fun earns(report: MatchReport): Boolean =
        if (report.mode == GameMode.LAST_SPARK) report.placement in 1..4 else report.outcome == MatchOutcome.VICTORY

    /** Highest luck the debug menu offers. */
    const val MAX_LUCK = 4f

    /** Chance of each tier (summing to 1). [luck] multiplies a tier's weight by (1 + luck) for every tier it is above Scrap. */
    fun odds(luck: Float = 0f): List<Float> {
        val w = CapsuleTier.entries.map { it.weight * Math.pow(1.0 + luck, it.ordinal.toDouble()).toFloat() }
        val sum = w.sum()
        return w.map { it / sum }
    }

    /** The most capsules one can turn into. */
    const val MAX_PIECES = 8

    /** Chance that a capsule splits in two as it is opened, leaving a second one to open. Luck helps. */
    fun splitChance(luck: Float = 0f) = 0.25f + 0.05f * luck

    /** Once it has split, the chance that every piece splits again (2 -> 4 -> 8). */
    fun resplitChance(luck: Float = 0f) = 0.5f + 0.08f * luck

    /** The pieces a drop splits into are better than a plain one: they roll with this much extra luck and are never Scrap. */
    const val SPLIT_LUCK = 0.6f

    fun rollPieces(rng: kotlin.random.Random, luck: Float = 0f): Int {
        if (rng.nextFloat() >= splitChance(luck)) return 1
        var pieces = 2
        while (pieces < MAX_PIECES && rng.nextFloat() < resplitChance(luck)) pieces *= 2
        return pieces
    }

    fun rollTier(rng: kotlin.random.Random, luck: Float = 0f): CapsuleTier {
        var roll = rng.nextFloat()
        for ((i, chance) in odds(luck).withIndex()) { roll -= chance; if (roll < 0f) return CapsuleTier.entries[i] }
        return CapsuleTier.SCRAP
    }

    fun rollReward(tier: CapsuleTier, save: SaveData, rng: kotlin.random.Random): Reward {
        fun bolts(lo: Int, hi: Int) = Reward.Bolts((lo + rng.nextInt(hi - lo + 1)) / 5 * 5)
        fun prisms(lo: Int, hi: Int) = Reward.Prisms(lo + rng.nextInt(hi - lo + 1))
        fun newSkin(): Reward? = Balance.fighters
            .filter { save.progress(it.id).unlocked }
            .flatMap { f -> f.skins.indices.filter { it !in save.progress(f.id).ownedSkins }.map { Reward.SkinReward(f.id, it) } }
            .randomOrNull(rng)
        fun newFighter(): Reward? = FighterId.entries.filter { !save.progress(it).unlocked }.randomOrNull(rng)?.let { Reward.UnlockFighter(it) }
        return when (tier) {
            CapsuleTier.SCRAP -> bolts(60, 120)
            CapsuleTier.TUNED -> if (rng.nextInt(3) == 0) prisms(15, 25) else bolts(180, 300)
            CapsuleTier.CHARGED -> if (rng.nextInt(2) == 0) prisms(35, 50) else bolts(400, 600)
            CapsuleTier.OVERCLOCKED -> (if (rng.nextInt(2) == 0) newSkin() else null) ?: if (rng.nextBoolean()) prisms(80, 110) else bolts(1000, 1300)
            CapsuleTier.PRISMATIC -> newFighter() ?: newSkin() ?: prisms(250, 300)
            // The jackpot: something new to play with (while there is anything left) plus a pile of both currencies.
            CapsuleTier.ULTRA -> Reward.Bundle(listOfNotNull(newFighter() ?: newSkin(), prisms(400, 500), bolts(2000, 2500)))
        }
    }
}

data class Milestone(val cups: Int, val reward: Reward)

/** The Cup Track. Milestones must be sorted by [Milestone.cups] and unique. */
object CupTrack {
    val milestones: List<Milestone> = listOf(
        Milestone(10, Reward.Bolts(40)),
        Milestone(25, Reward.Prisms(10)),
        Milestone(40, Reward.Bolts(75)),
        Milestone(60, Reward.SkinReward(FighterId.JUNO, 1)),
        Milestone(80, Reward.Prisms(20)),
        Milestone(100, Reward.UnlockFighter(FighterId.BRAKK)),
        Milestone(130, Reward.Bolts(150)),
        Milestone(160, Reward.Prisms(25)),
        Milestone(200, Reward.SkinReward(FighterId.BRAKK, 1)),
        Milestone(250, Reward.Bolts(250)),
        Milestone(300, Reward.Prisms(40)),
        Milestone(350, Reward.UnlockFighter(FighterId.MIRA)),
        Milestone(420, Reward.Bolts(400)),
        Milestone(500, Reward.SkinReward(FighterId.MIRA, 1)),
        Milestone(600, Reward.Prisms(60)),
        Milestone(700, Reward.Bolts(600)),
        Milestone(850, Reward.SkinReward(FighterId.JUNO, 2)),
        Milestone(1000, Reward.Prisms(100)),
    )

    fun nextMilestone(cups: Int): Milestone? = milestones.firstOrNull { it.cups > cups }
    fun previousMilestoneCups(cups: Int): Int = milestones.lastOrNull { it.cups <= cups }?.cups ?: 0

    /** If a track reward is already owned (e.g. fighter bought in the shop), it pays out this instead. */
    fun duplicateCompensation(reward: Reward): Reward = when (reward) {
        is Reward.UnlockFighter -> Reward.Bolts(300)
        is Reward.SkinReward -> Reward.Prisms(30)
        is Reward.Bundle -> Reward.Bundle(reward.items.map { duplicateCompensation(it) })
        else -> reward
    }
}

sealed interface ShopItem {
    val key: String
    val title: String
    val pricePrisms: Int

    data class FighterOffer(val fighter: FighterId, override val pricePrisms: Int) : ShopItem {
        override val key = "fighter_${fighter.name}"
        override val title = Balance.fighter(fighter).name
    }

    data class BoltCrate(override val key: String, override val title: String, val bolts: Int, override val pricePrisms: Int) : ShopItem

    data class SkinOffer(val fighter: FighterId, val skinIndex: Int) : ShopItem {
        override val key = "skin_${fighter.name}_$skinIndex"
        private val skin get() = Balance.fighter(fighter).skins[skinIndex]
        override val title get() = skin.name
        override val pricePrisms get() = skin.pricePrisms
    }
}

object Shop {
    val boltCrates = listOf(
        ShopItem.BoltCrate("crate_s", "Bolt Pouch", bolts = 120, pricePrisms = 15),
        ShopItem.BoltCrate("crate_m", "Bolt Crate", bolts = 360, pricePrisms = 40),
        ShopItem.BoltCrate("crate_l", "Bolt Vault", bolts = 800, pricePrisms = 80),
    )

    val fighterOffers: List<ShopItem.FighterOffer> =
        FighterId.entries.mapNotNull { id -> Balance.unlockPrismPrice(id)?.let { ShopItem.FighterOffer(id, it) } }

    val skinOffers: List<ShopItem.SkinOffer> = Balance.fighters.flatMap { f ->
        f.skins.indices.drop(1).map { ShopItem.SkinOffer(f.id, it) }
    }

    /** The free daily gift alternates by calendar day so it's predictable. */
    fun dailyGift(epochDay: Long): Reward = if (epochDay % 2L == 0L) Reward.Bolts(40) else Reward.Prisms(8)
}
