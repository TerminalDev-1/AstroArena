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
import io.github.projectwip.data.ServerVerdict
import io.github.projectwip.data.CapsuleResult
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

    @Test fun theServerAwardsCupsAndTheGameAwardsBoltsAndTheFirstWinBonusOnce() {
        val (a, r1) = Progression.applyMatch(SaveData(), report(MatchOutcome.VICTORY), today = 100, verdict = ServerVerdict(8, 8, false, 1, 3))
        assertEquals(8, a.cups)
        assertEquals(8, a.bestCups)
        assertEquals(0, r1.cupsBefore)
        assertEquals(8, r1.cupDelta)
        assertTrue(r1.online)
        assertTrue(r1.bolts > 0)
        assertEquals(Balance.FIRST_WIN_PRISMS, r1.firstWinPrisms)
        val (_, r2) = Progression.applyMatch(a, report(MatchOutcome.VICTORY), today = 100, verdict = ServerVerdict(8, 16, false, 1, 3))
        assertEquals(0, r2.firstWinPrisms)
        assertEquals(8, r2.cupsBefore)
    }

    @Test fun theServersTotalsAreAdoptedNotAddedUp() {
        // Applying the same verdict to a save that already took on the server's numbers must not count it twice.
        val verdict = ServerVerdict(cupDelta = -6, cups = 494, drop = true, drops = 4, dropsLeftToday = 1)
        val synced = Progression.syncAccount(SaveData(cups = 500, bestCups = 500, capsules = 3), 494, 4, 1, today = 7)
        val (after, rewards) = Progression.applyMatch(synced, report(MatchOutcome.DEFEAT), today = 7, verdict = verdict)
        assertEquals(494, after.cups)
        assertEquals(500, after.bestCups)
        assertEquals(4, after.capsules)
        assertEquals(500, rewards.cupsBefore)
        assertTrue(rewards.capsuleEarned)
        assertEquals(1, rewards.capsulesLeftToday)
        assertEquals(1, Progression.capsulesLeftToday(after, 7))
    }

    @Test fun offlineMatchesPayBoltsButNoCupsOrDrops() {
        val save = SaveData(cups = 120, bestCups = 120, capsules = 2)
        val (after, rewards) = Progression.applyMatch(save, report(MatchOutcome.VICTORY), today = 3, verdict = null)
        assertFalse(rewards.online)
        assertEquals(0, rewards.cupDelta)
        assertEquals(120, after.cups)
        assertFalse(rewards.capsuleEarned)
        assertEquals(2, after.capsules)
        assertTrue(rewards.bolts > 0)
        assertEquals(save.bolts + rewards.bolts, after.bolts)
        assertEquals(1, after.matchesPlayed)
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

    @Test fun lastSparkBoltsFollowPlacement() {
        fun ffa(place: Int) = MatchReport(outcome = if (place == 1) MatchOutcome.VICTORY else MatchOutcome.DEFEAT,
            mode = io.github.projectwip.data.GameMode.LAST_SPARK, placement = place, players = 10, fighter = FighterId.JUNO,
            kos = 0, deaths = 1, damageDealt = 0, mvp = false, difficulty = BotDifficulty.NORMAL, blueScore = 0, redScore = 0)
        val start = SaveData(cups = 200, bestCups = 200)
        val first = Progression.applyMatch(start, ffa(1), 1, null).second
        val fifth = Progression.applyMatch(start, ffa(5), 1, null).second
        val last = Progression.applyMatch(start, ffa(10), 1, null).second
        assertTrue(first.bolts > fifth.bolts && fifth.bolts > last.bolts)
    }

    @Test fun trackIsSortedAndUnique() {
        val cups = CupTrack.milestones.map { it.cups }
        assertEquals(cups.sorted().distinct(), cups)
    }

    @Test fun theServersDropCountIsShownAndResetsWithTheDay() {
        val save = Progression.syncAccount(SaveData(capsules = 0), cups = 30, drops = 5, dropsLeftToday = 0, today = 5)
        assertEquals(5, save.capsules)
        assertEquals(30, save.cups)
        assertEquals(0, Progression.capsulesLeftToday(save, 5))
        assertEquals("a new day resets the cap", SparkCapsules.PER_DAY, Progression.capsulesLeftToday(save, 6))
        assertEquals("best Cups never go down", 30, Progression.syncAccount(save, 10, 5, 3, 6).bestCups)
    }

    @Test fun aDropTheServerOpenedIsGranted() {
        val save = SaveData(capsules = 2)
        val bolts = Progression.grantDrop(save, CapsuleResult(CapsuleTier.SCRAP, Reward.Bolts(100)))
        assertEquals(save.bolts + 100, bolts.bolts)
        assertEquals(1, bolts.capsulesOpened)
        assertEquals("the count of unopened drops is the server's to change", 2, bolts.capsules)
        val bundle = Reward.Bundle(listOf(Reward.UnlockFighter(FighterId.MIRA), Reward.Prisms(400), Reward.Bolts(2000)))
        val ultra = Progression.grantDrop(save, CapsuleResult(CapsuleTier.ULTRA, bundle, pieces = 8))
        assertTrue(ultra.progress(FighterId.MIRA).unlocked)
        assertEquals(save.prisms + 400, ultra.prisms)
        // A fighter that is somehow already owned is paid out instead of wasted.
        val again = Progression.grantDrop(ultra, CapsuleResult(CapsuleTier.PRISMATIC, Reward.UnlockFighter(FighterId.MIRA)))
        assertEquals(ultra.bolts + 300, again.bolts)
    }

    @Test fun dropOddsShownInTheDebugMenu() {
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
    }

    @Test fun cheatsCanBeSwitchedOff() {
        val cheating = io.github.projectwip.data.Settings(debugLuck = 9f, debugInfiniteCapsules = true, debugNoLevelCap = true, debugUpgradeCost = 0f, playerName = "Ace")
        assertEquals(io.github.projectwip.data.Settings(playerName = "Ace"), Progression.withoutCheats(cheating))
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

    @Test fun debugUpgradeCostScalesThePrice() {
        val normal = SaveData(bolts = 1000)
        assertEquals(Balance.upgradeCost[0], Progression.upgradeCost(normal, FighterId.JUNO))
        val free = normal.copy(bolts = 0, settings = io.github.projectwip.data.Settings(debugUpgradeCost = 0f))
        assertEquals(0, Progression.upgradeCost(free, FighterId.JUNO))
        assertEquals("free upgrades need no Bolts", 2, Progression.upgrade(free, FighterId.JUNO)!!.progress(FighterId.JUNO).level)
        val triple = normal.copy(settings = io.github.projectwip.data.Settings(debugUpgradeCost = 3f))
        val up = Progression.upgrade(triple, FighterId.JUNO)!!
        assertEquals(1000 - Balance.upgradeCost[0] * 3, up.bolts)
    }

    @Test fun bossModePaysBoltsOnly() {
        val save = SaveData(cups = 100, bestCups = 100, capsules = 0)
        val win = report(MatchOutcome.VICTORY).copy(mode = GameMode.BOSS, players = 2)
        val (after, rewards) = Progression.applyMatch(save, win, today = 9, verdict = ServerVerdict(0, 100, false, 0, 3))
        assertEquals("no Cups from a boss that never gets tougher", 0, rewards.cupDelta)
        assertEquals(100, after.cups)
        assertTrue(rewards.bolts > 0)
        assertEquals(0, rewards.firstWinPrisms)
        assertFalse(rewards.capsuleEarned)
        assertEquals(0, after.capsules)
    }

    @Test fun versionsCompareByNumber() {
        val v = io.github.projectwip.data.Versions
        assertTrue(v.isNewer("v0.4.2-preview", "0.4.1-preview"))
        assertTrue(v.isNewer("v0.10.0-preview", "0.9.9-preview"))
        assertTrue(v.isNewer("1.0", "0.99.99-preview"))
        assertFalse("the same version is not an update", v.isNewer("v0.4.2-preview", "0.4.2-preview"))
        assertFalse("an older release is not an update", v.isNewer("v0.3.3-preview", "0.4.2-preview"))
        assertFalse("garbage is never an update", v.isNewer("latest", "0.4.2-preview"))
        assertFalse(v.isNewer("v0.4.3", "not-a-version"))
        // Whole-number versions (v6 onward), and the two-part tag older installs can read.
        assertTrue(v.isNewer("v6.0", "0.5.1-preview"))
        assertTrue(v.isNewer("v6", "0.5.1-preview"))
        assertTrue(v.isNewer("v7.0", "6"))
        assertFalse("v6.0 is the same version as 6", v.isNewer("v6.0", "6"))
        assertFalse(v.isNewer("0.5.1-preview", "6"))
    }

    @Test fun rosterHasFourFightersAtGenreScale() {
        assertEquals(FighterId.entries.size, Balance.fighters.size)
        assertEquals("the roster lists fighters in id order", FighterId.entries.toList(), Balance.fighters.map { it.id })
        val kito = Balance.fighter(FighterId.KITO)
        assertEquals("Kito", kito.name)
        assertFalse("new fighters start locked", SaveData().progress(FighterId.KITO).unlocked)
        assertNotNull(Balance.unlockPrismPrice(FighterId.KITO))
        assertTrue(CupTrack.milestones.any { it.reward == Reward.UnlockFighter(FighterId.KITO) })
        // Thousands of health, hundreds to a thousand-odd per hit.
        for (f in Balance.fighters) {
            assertTrue("${f.name} health ${f.health.base}", f.health.base in 2500..6000)
            assertTrue("${f.name} damage ${f.attackDamage.base}", f.attackDamage.base in 200..1200)
        }
    }

    @Test fun serverCanRetuneBotsAndFreshSavesAreRecognised() {
        val base = io.github.projectwip.ai.BotProfile.builtIn(BotDifficulty.EASY)
        val tuned = base.withOverrides(mapOf("reactiontime" to 0.25, "shotdiscipline" to true, "nonsense" to 9, "wander" to "lots"))
        assertEquals(0.25f, tuned.reactionTime, 1e-6f)
        assertTrue(tuned.shotDiscipline)
        assertEquals("a value of the wrong type is ignored", base.wander, tuned.wander, 0f)
        assertEquals(base.aimErrorDegrees, tuned.aimErrorDegrees, 0f)
        assertEquals("with nothing from the server, bots use their built-in behaviour", base, io.github.projectwip.ai.BotProfile.of(BotDifficulty.EASY))

        assertTrue(Progression.isFresh(SaveData()))
        assertFalse(Progression.isFresh(SaveData(cups = 5, bestCups = 5)))
        assertFalse(Progression.isFresh(Progression.applyMatch(SaveData(), report(MatchOutcome.DEFEAT), today = 1, verdict = null).first))

        // Real players from the server slot into the ladder by Cups and are marked.
        val others = listOf(io.github.projectwip.data.LeaderboardEntry(0, "Rival", 900, FighterId.MIRA, false))
        val ladder = io.github.projectwip.data.Leaderboard.standings("Me", 100, FighterId.JUNO, 20000L, others)
        assertEquals(io.github.projectwip.data.Leaderboard.RIVALS + 2, ladder.size)
        val rival = ladder.single { it.online }
        assertEquals("Rival", rival.name)
        assertTrue(rival.rank < ladder.first { it.isPlayer }.rank)
    }
}
