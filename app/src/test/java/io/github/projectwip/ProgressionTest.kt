package io.github.projectwip

import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.SparkCapsules
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.MatchOutcome
import io.github.projectwip.data.MatchReport
import io.github.projectwip.data.Progression
import io.github.projectwip.data.PurchaseResult
import io.github.projectwip.data.Reward
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.Shop
import io.github.projectwip.data.StatLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {

    private fun report(outcome: MatchOutcome, kos: Int = 2, d: BotDifficulty = BotDifficulty.NORMAL) =
        MatchReport(outcome = outcome, fighter = FighterId.JUNO, kos = kos, deaths = 1, damageDealt = 1000,
            mvp = false, difficulty = d, blueScore = 10, redScore = 5)

    @Test fun statLineIsLinear() {
        val s = StatLine(100, 5)
        assertEquals(100, s.at(1))
        assertEquals(120, s.at(5))
        assertEquals(125, s.at(6))
        assertEquals("no level cap", 100 + 5 * 98, s.at(99))
    }

    @Test fun upgradePreviewShowsExactDelta() {
        val juno = Balance.fighter(FighterId.JUNO)
        val rows = Progression.statPreview(juno, 4)
        val dmg = rows[1]
        assertEquals(juno.attackDamage.at(4), dmg.current)
        assertEquals(juno.attackDamage.perLevel, dmg.delta)
        assertNull("no next level once capped", Progression.statPreview(juno, Balance.MAX_LEVEL, capped = true)[0].next)
        assertNotNull("with the cap off there is always a next level", Progression.statPreview(juno, 250)[0].next)
    }

    @Test fun upgradeSpendsBoltsAndLevelsUp() {
        val s = SaveData(bolts = 100)
        val up = Progression.upgrade(s, FighterId.JUNO)!!
        assertEquals(2, up.progress(FighterId.JUNO).level)
        assertEquals(100 - Balance.upgradeCost[0], up.bolts)
        assertNull("can't upgrade a locked fighter", Progression.upgrade(s, FighterId.MIRA))
        assertNull("can't afford", Progression.upgrade(SaveData(bolts = 0), FighterId.JUNO))
    }

    @Test fun victoryGrantsCupsBoltsAndFirstWinBonusOnce() {
        val (a, r1) = Progression.applyMatch(SaveData(), report(MatchOutcome.VICTORY), today = 100)
        assertEquals(BotDifficulty.NORMAL.cupBonus, a.cups)
        assertEquals(Balance.FIRST_WIN_PRISMS, r1.firstWinPrisms)
        val (_, r2) = Progression.applyMatch(a, report(MatchOutcome.VICTORY), today = 100)
        assertEquals(0, r2.firstWinPrisms)
    }

    @Test fun defeatNeverDropsCupsBelowZero() {
        val (s, r) = Progression.applyMatch(SaveData(cups = 3), report(MatchOutcome.DEFEAT), 1)
        assertTrue(s.cups >= 0)
        assertTrue(r.cupDelta <= 0)
        val (s2, _) = Progression.applyMatch(SaveData(cups = 500, bestCups = 500), report(MatchOutcome.DEFEAT), 1)
        assertEquals(494, s2.cups)
    }

    @Test fun cupTrackClaimsOnceAndKeepsBestCups() {
        var s = SaveData(cups = 30, bestCups = 30)
        val claimable = Progression.claimable(s)
        assertEquals(listOf(10, 25), claimable.map { it.cups })
        val (s2, reward) = Progression.claimMilestone(s, claimable[0])!!
        assertEquals(Reward.Bolts(40), reward)
        assertNull(Progression.claimMilestone(s2, claimable[0]))
        // Losing cups doesn't revoke reached milestones.
        s = s2.copy(cups = 5)
        assertEquals(listOf(25), Progression.claimable(s).map { it.cups })
    }

    @Test fun duplicateFighterRewardIsCompensated() {
        val owned = Progression.grant(SaveData(cups = 100, bestCups = 100), Reward.UnlockFighter(FighterId.BRAKK))
        val m = CupTrack.milestones.first { it.reward == Reward.UnlockFighter(FighterId.BRAKK) }
        val (_, r) = Progression.claimMilestone(owned, m)!!
        assertEquals(CupTrack.duplicateCompensation(m.reward), r)
    }

    @Test fun shopPurchases() {
        val mira = Shop.fighterOffers.first { it.fighter == FighterId.MIRA }
        assertEquals(PurchaseResult.NotEnough, Progression.buy(SaveData(prisms = 0), mira).second)
        val (s, r) = Progression.buy(SaveData(prisms = 500), mira)
        assertEquals(PurchaseResult.Ok, r)
        assertTrue(s.progress(FighterId.MIRA).unlocked)
        assertEquals(PurchaseResult.AlreadyOwned, Progression.buy(s, mira).second)
    }

    @Test fun dailyGiftOncePerDay() {
        val (s, _) = Progression.claimDailyGift(SaveData(), 10)!!
        assertNull(Progression.claimDailyGift(s, 10))
        assertNotNull(Progression.claimDailyGift(s, 11))
        assertFalse(Progression.dailyGiftAvailable(s, 10))
    }

    @Test fun lastSparkRewardsFollowPlacement() {
        fun ffa(place: Int) = MatchReport(outcome = if (place == 1) MatchOutcome.VICTORY else MatchOutcome.DEFEAT,
            mode = io.github.projectwip.data.GameMode.LAST_SPARK, placement = place, players = 10, fighter = FighterId.JUNO,
            kos = 0, deaths = 1, damageDealt = 0, mvp = false, difficulty = BotDifficulty.NORMAL, blueScore = 0, redScore = 0)
        val start = SaveData(cups = 200, bestCups = 200)
        val first = Progression.applyMatch(start, ffa(1), 1).second
        val fifth = Progression.applyMatch(start, ffa(5), 1).second
        val last = Progression.applyMatch(start, ffa(10), 1).second
        assertEquals(Balance.placementCups[0], first.cupDelta)
        assertTrue(first.cupDelta > fifth.cupDelta && fifth.cupDelta > last.cupDelta)
        assertTrue(first.bolts > last.bolts)
        // Beginners never lose Cups.
        assertEquals(0, Progression.applyMatch(SaveData(cups = 10, bestCups = 10), ffa(10), 1).second.cupDelta)
    }

    @Test fun trackIsSortedAndUnique() {
        val cups = CupTrack.milestones.map { it.cups }
        assertEquals(cups.sorted().distinct(), cups)
    }

    @Test fun capsulesAreEarnedFromGoodFinishesUpToTheDailyCap() {
        var save = SaveData(capsules = 0)
        save = Progression.applyMatch(save, report(MatchOutcome.DEFEAT), today = 5).also { assertFalse(it.second.capsuleEarned) }.first
        assertEquals(0, save.capsules)
        repeat(SparkCapsules.PER_DAY) { i ->
            val (next, rewards) = Progression.applyMatch(save, report(MatchOutcome.VICTORY), today = 5)
            assertTrue(rewards.capsuleEarned)
            assertEquals(SparkCapsules.PER_DAY - i - 1, rewards.capsulesLeftToday)
            save = next
        }
        assertEquals(SparkCapsules.PER_DAY, save.capsules)
        val (capped, rewards) = Progression.applyMatch(save, report(MatchOutcome.VICTORY), today = 5)
        assertFalse("daily cap reached", rewards.capsuleEarned)
        assertEquals(SparkCapsules.PER_DAY, capped.capsules)
        assertEquals(0, Progression.capsulesLeftToday(capped, 5))
        assertEquals("a new day resets the cap", SparkCapsules.PER_DAY, Progression.capsulesLeftToday(capped, 6))
        assertTrue(Progression.applyMatch(capped, report(MatchOutcome.VICTORY), today = 6).second.capsuleEarned)

        // Free-for-all: top four counts, fifth doesn't.
        fun ffa(place: Int) = report(if (place == 1) MatchOutcome.VICTORY else MatchOutcome.DEFEAT).copy(mode = GameMode.LAST_SPARK, placement = place, players = 10)
        assertTrue(SparkCapsules.earns(ffa(1)))
        assertTrue(SparkCapsules.earns(ffa(4)))
        assertFalse(SparkCapsules.earns(ffa(5)))
    }

    @Test fun openingACapsuleIsDeterministicAndGrantsItsReward() {
        assertNull(Progression.openCapsule(SaveData(capsules = 0)))
        val save = SaveData(capsules = 2, capsuleSeed = 99)
        val (after, result) = Progression.openCapsule(save)!!
        assertEquals("same seed, same capsule (no re-rolling by reloading)", result, Progression.openCapsule(save)!!.second)
        assertEquals(1 + result.pieces - 1, after.capsules)
        assertEquals(1, after.capsulesOpened)
        assertTrue("the next capsule must use a fresh seed", after.capsuleSeed != save.capsuleSeed)
        when (val r = result.reward) {
            is Reward.Bolts -> assertEquals(save.bolts + r.amount, after.bolts)
            is Reward.Prisms -> assertEquals(save.prisms + r.amount, after.prisms)
            else -> assertTrue(Progression.owns(after, r))
        }
    }

    @Test fun capsuleRewardsNeverDuplicateAndRespectTierOdds() {
        // Open a long run of capsules: nothing already owned may ever come out, and every tier shows up.
        var save = SaveData(capsules = 4000, capsuleSeed = 1)
        val seen = HashMap<CapsuleTier, Int>()
        var splits = 0
        val pieces = java.util.TreeMap<Int, Int>()
        repeat(4000) {
            val before = save
            val (next, result) = Progression.openCapsule(save)!!
            assertFalse("duplicate ${result.reward}", Progression.owns(before, result.reward))
            seen.merge(result.tier, 1, Int::plus)
            if (result.split) splits++
            pieces.merge(result.pieces, 1, Int::plus)
            assertEquals("a split hands back the extra capsules", before.capsules - 1 + result.pieces - 1, next.capsules)
            // Keep every open a plain one here, so the tier counts reflect the base odds.
            save = next.copy(boostedCapsules = 0)
        }
        println("capsule tiers over 4000 opens: $seen, splits: $splits, pieces: $pieces")
        assertEquals("capsules only ever become 1, 2, 4 or 8", setOf(1, 2, 4, 8), pieces.keys)
        assertTrue("bigger splits are rarer", pieces.getValue(2) > pieces.getValue(8))
        assertTrue("plenty of drops should split ($splits of 4000)", splits in 900..1900)
        assertEquals(CapsuleTier.entries.toSet(), seen.keys)
        for (t in CapsuleTier.entries.zipWithNext()) assertTrue("${t.first} should be more common than ${t.second}", seen.getValue(t.first) > seen.getValue(t.second))
        assertTrue("Prismatic capsules unlock every fighter eventually", FighterId.entries.all { save.progress(it).unlocked })
    }

    @Test fun debugLuckAndInfiniteCapsules() {
        val normal = SparkCapsules.odds(0f)
        val lucky = SparkCapsules.odds(SparkCapsules.MAX_LUCK)
        assertEquals(1f, normal.sum(), 1e-4f)
        assertEquals(1f, lucky.sum(), 1e-4f)
        assertEquals("Ultra is the rarest tier", 0.02f, normal.last(), 1e-4f)
        assertEquals(normal.min(), normal.last(), 0f)
        assertTrue("max luck makes Ultra the most likely tier", lucky.last() > 0.5f && lucky.last() == lucky.max())
        assertTrue(SparkCapsules.splitChance(SparkCapsules.MAX_LUCK) > SparkCapsules.splitChance(0f))
        assertTrue("chances never exceed 100%", SparkCapsules.splitChance(SparkCapsules.MAX_LUCK) <= 1f && SparkCapsules.resplitChance(SparkCapsules.MAX_LUCK) <= 1f)
        assertEquals("the luck slider tops out at x15", 14f, SparkCapsules.MAX_LUCK, 0f)

        val settings = io.github.projectwip.data.Settings(debugLuck = SparkCapsules.MAX_LUCK, debugInfiniteCapsules = true)
        var save = SaveData(capsules = 0, capsuleSeed = 3, settings = settings)
        var prismatic = 0
        repeat(200) {
            val (next, result) = Progression.openCapsule(save)!!
            if (result.tier == CapsuleTier.ULTRA) prismatic++
            save = next
        }
        assertTrue("infinite capsules never run out", save.capsules >= 0)
        assertEquals(200, save.capsulesOpened)
        assertTrue("luck should show ($prismatic/200 Ultra)", prismatic > 70)
    }

    @Test fun splitPiecesAreBetterThanPlainDrops() {
        // Open a plain drop until one splits, then check its pieces are flagged and never come out Scrap.
        var save = SaveData(capsules = 1, capsuleSeed = 5)
        while (save.boostedCapsules == 0) save = Progression.openCapsule(save.copy(capsules = 1, boostedCapsules = 0))!!.first
        val pieces = save.boostedCapsules
        assertTrue(pieces in 1..7)
        assertEquals("the extras are the pieces", pieces, save.capsules)
        repeat(pieces) {
            val (next, result) = Progression.openCapsule(save.copy(settings = save.settings))!!
            assertTrue("a split piece is never Scrap", result.tier != CapsuleTier.SCRAP)
            save = next.copy(capsules = next.capsules.coerceAtLeast(1))
        }
    }

    @Test fun leaderboardRanksByCups() {
        val day = 20000L
        val low = io.github.projectwip.data.Leaderboard.standings("Me", 0, FighterId.JUNO, day)
        assertEquals(io.github.projectwip.data.Leaderboard.RIVALS + 1, low.size)
        assertEquals(1, low.count { it.isPlayer })
        assertEquals((1..low.size).toList(), low.map { it.rank })
        assertTrue(low.zipWithNext().all { (a, b) -> a.cups >= b.cups })
        val mid = io.github.projectwip.data.Leaderboard.rank(400, day)
        assertTrue("more Cups, better rank", mid < low.first { it.isPlayer }.rank)
        assertEquals("enough Cups tops the ladder", 1, io.github.projectwip.data.Leaderboard.rank(5000, day))
        assertEquals("same day, same ladder", low, io.github.projectwip.data.Leaderboard.standings("Me", 0, FighterId.JUNO, day))
    }

    @Test fun levelsNeverRunOut() {
        // The price follows the table, then keeps climbing by a fixed step, and never drops.
        assertEquals(Balance.upgradeCost[0], Balance.upgradeCostFrom(1))
        assertEquals(Balance.upgradeCost.last(), Balance.upgradeCostFrom(Balance.upgradeCost.size))
        assertEquals(Balance.upgradeCost.last() + Balance.UPGRADE_COST_STEP, Balance.upgradeCostFrom(Balance.upgradeCost.size + 1))
        assertTrue((1..300).zipWithNext().all { (a, b) -> Balance.upgradeCostFrom(b) >= Balance.upgradeCostFrom(a) })
        // Normally the cap holds at MAX_LEVEL...
        var capped = SaveData(bolts = 10_000_000)
        repeat(Balance.MAX_LEVEL - 1) { capped = Progression.upgrade(capped, FighterId.JUNO)!! }
        assertEquals(Balance.MAX_LEVEL, capped.progress(FighterId.JUNO).level)
        assertTrue(Progression.levelCapped(capped, FighterId.JUNO))
        assertNull("the cap stops further upgrades", Progression.upgrade(capped, FighterId.JUNO))
        // ...and the dev toggle lifts it.
        var save = SaveData(bolts = 10_000_000, settings = io.github.projectwip.data.Settings(debugNoLevelCap = true))
        repeat(60) { save = Progression.upgrade(save, FighterId.JUNO)!! }
        assertEquals(61, save.progress(FighterId.JUNO).level)
        val juno = Balance.fighter(FighterId.JUNO)
        assertEquals(juno.health.base + juno.health.perLevel * 60, juno.health.at(61))
    }
}
