package io.github.projectwip.data

/** Anything the game can hand to the player. */
sealed interface Reward {
    data class Bolts(val amount: Int) : Reward
    data class Prisms(val amount: Int) : Reward
    /** Credits: progress along the Spark Road, toward the fighter the player picked. Not something held in a wallet. */
    data class Credits(val amount: Int) : Reward
    data class UnlockFighter(val fighter: FighterId) : Reward
    data class SkinReward(val fighter: FighterId, val skinIndex: Int) : Reward
    /** Several rewards at once (custom shop offers). */
    data class Bundle(val items: List<Reward>) : Reward
}

enum class Currency { FREE, BOLTS, PRISMS }

/**
 * A shop deal. Developers make them in-game with the Offer Creator (contents, price, an optional "was" price
 * shown as a discount, expiry, purchase limit and colour theme); the server keeps them and shows them to every
 * player, each with their own [purchased] count.
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

/** How good an item in an Arena Box turned out. Each tier up is rarer and pays better. */
enum class CapsuleTier(val label: String, val color: Long, val weight: Int) {
    SCRAP("Scrap", 0xFF4F86FF, 40),
    TUNED("Tuned", 0xFF4ED36A, 28),
    CHARGED("Charged", 0xFFA66BFF, 18),
    OVERCLOCKED("Overclocked", 0xFFFF8A1F, 8),
    PRISMATIC("Prismatic", 0xFFFF4FB8, 4),
    ULTRA("Ultra", 0xFFFFE14D, 2),
}

/** One thing that came out of an Arena Box, and how rare it turned out to be. */
data class BoxItem(val tier: CapsuleTier, val reward: Reward)

/** What came out of an opened Arena Box: every item in it, in the order they are shown. */
data class CapsuleResult(val items: List<BoxItem>) {
    /** The rarest item's tier: the box takes its colour as it opens. */
    val best: CapsuleTier get() = items.maxOfOrNull { it.tier } ?: CapsuleTier.SCRAP
}

/**
 * Spark Capsules ("Arena Boxes" to players; they were Spark Drops, then Arena Boxes): earned from your first few good finishes each day, opened from the
 * home screen. The game server decides all of it: whether a match earned one, and what comes out when one is
 * opened (`server/astro/rules.py`). What is left here is only what the game needs to show them.
 */
object SparkCapsules {
    /** Capsules that can be earned per calendar day. */
    const val PER_DAY = 0  // Arena Boxes were removed in 1.3
    /** Every save starts with one, so the first open doesn't have to be earned. */
    const val STARTING = 0

    /** Highest luck the debug menu offers (shown as x15). */
    const val MAX_LUCK = 14f

    /** Chance of each tier (summing to 1). [luck] multiplies a tier's weight by (1 + luck) for every tier it is above Scrap. */
    fun odds(luck: Float = 0f): List<Float> {
        val w = CapsuleTier.entries.map { it.weight * Math.pow(1.0 + luck, it.ordinal.toDouble()).toFloat() }
        val sum = w.sum()
        return w.map { it / sum }
    }

    /** A box holds this many items, and now and then more, up to [MAX_ITEMS]. */
    const val BOX_ITEMS = 3
    const val MAX_ITEMS = 8

    /** After the first [BOX_ITEMS], the chance of each further item. Luck helps. */
    fun moreItemsChance(luck: Float = 0f) = (0.4f + 0.05f * luck).coerceAtMost(1f)
}

/** One stop on the Spark Road: a fighter, and the Credits it takes to unlock. */
data class RoadStep(val fighter: FighterId, val cost: Int)

/**
 * The Spark Road. Credits are not kept in a wallet: whatever is earned goes straight into the road, toward the
 * next fighter along it. The order is fixed, the cheapest rarity first. When that fighter's cost is covered it
 * is theirs to claim. The server does all of it (`server/astro/economy.py`); this copy is for showing the road.
 */
object SparkRoad {
    /** Every fighter on the road, in the order they are unlocked. */
    val steps: List<RoadStep> = Balance.fighters.filter { it.rarity != Rarity.STARTER && it.id != FighterId.BYTE }.map { RoadStep(it.id, it.rarity.roadCost) }.sortedBy { it.cost }

    /** The fighter the Credits are filling: the first one along the road that is still locked. Null when the road is finished. */
    fun next(save: SaveData): RoadStep? = steps.firstOrNull { !save.progress(it.fighter).unlocked }
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

    data class CreditPack(override val key: String, override val title: String, val credits: Int, override val pricePrisms: Int) : ShopItem

    data class SkinOffer(val fighter: FighterId, val skinIndex: Int) : ShopItem {
        override val key = "skin_${fighter.name}_$skinIndex"
        private val skin get() = Balance.fighter(fighter).skins[skinIndex]
        override val title get() = skin.name
        override val pricePrisms get() = skin.pricePrisms
    }
}

object Shop {
    val boltCrates = listOf(
        ShopItem.BoltCrate("crate_s", "Coin Pouch", bolts = 400, pricePrisms = 10),
        ShopItem.BoltCrate("crate_m", "Coin Crate", bolts = 1200, pricePrisms = 25),
        ShopItem.BoltCrate("crate_l", "Coin Vault", bolts = 3000, pricePrisms = 50),
    )

    val creditPacks = listOf(
        ShopItem.CreditPack("credits_s", "Credit Chip", credits = 60, pricePrisms = 15),
        ShopItem.CreditPack("credits_m", "Credit Stack", credits = 200, pricePrisms = 45),
        ShopItem.CreditPack("credits_l", "Credit Case", credits = 500, pricePrisms = 100),
    )

    val fighterOffers: List<ShopItem.FighterOffer> =
        FighterId.entries.mapNotNull { id -> Balance.unlockPrismPrice(id)?.let { ShopItem.FighterOffer(id, it) } }

    val skinOffers: List<ShopItem.SkinOffer> = Balance.fighters.flatMap { f ->
        f.skins.indices.drop(1).map { ShopItem.SkinOffer(f.id, it) }
    }

    /** The free daily gift alternates by calendar day so it's predictable. */
    fun dailyGift(epochDay: Long): Reward = if (epochDay % 2L == 0L) Reward.Bolts(40) else Reward.Prisms(8)
}
