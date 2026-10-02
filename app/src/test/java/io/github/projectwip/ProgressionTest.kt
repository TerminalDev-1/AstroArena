package io.github.projectwip

import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
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
        assertEquals(s.at(Balance.MAX_LEVEL), s.at(99))
    }

    @Test fun upgradePreviewShowsExactDelta() {
        val juno = Balance.fighter(FighterId.JUNO)
        val rows = Progression.statPreview(juno, 4)
        val dmg = rows[1]
        assertEquals(juno.attackDamage.at(4), dmg.current)
        assertEquals(juno.attackDamage.perLevel, dmg.delta)
        assertNull(Progression.statPreview(juno, Balance.MAX_LEVEL)[0].next)
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
}
