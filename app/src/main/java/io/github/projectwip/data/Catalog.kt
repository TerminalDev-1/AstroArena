package io.github.projectwip.data

/** Anything the game can hand to the player. */
sealed interface Reward {
    data class Bolts(val amount: Int) : Reward
    data class Prisms(val amount: Int) : Reward
    data class UnlockFighter(val fighter: FighterId) : Reward
    data class SkinReward(val fighter: FighterId, val skinIndex: Int) : Reward
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
