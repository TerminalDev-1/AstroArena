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

    @Test fun theServerDecidesWhatAMatchIsWorth() {
        val verdict = ServerVerdict(8, 8, false, 1, 3, bolts = 28, firstWinPrisms = 10)
        val (a, r1) = Progression.applyMatch(SaveData(), report(MatchOutcome.VICTORY), today = 100, verdict = verdict)
        assertEquals(8, a.cups)
        assertEquals(8, a.bestCups)
        assertEquals(0, r1.cupsBefore)
        assertEquals(8, r1.cupDelta)
        assertTrue(r1.online)
        assertEquals(28, r1.bolts)
        assertEquals(10, r1.firstWinPrisms)
        assertEquals("Bolts arrive with the server's profile, not by adding them up here", SaveData().bolts, a.bolts)
        assertEquals(1, a.victories)
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

    @Test fun offlineMatchesEarnNothing() {
        val save = SaveData(cups = 120, bestCups = 120, capsules = 2)
        val (after, rewards) = Progression.applyMatch(save, report(MatchOutcome.VICTORY), today = 3, verdict = null)
        assertFalse(rewards.online)
        assertEquals(0, rewards.cupDelta)
        assertEquals(120, after.cups)
        assertFalse(rewards.capsuleEarned)
        assertEquals(2, after.capsules)
        assertEquals(0, rewards.bolts)
        assertEquals(0, rewards.firstWinPrisms)
        assertEquals(save.bolts, after.bolts)
        assertEquals(1, after.matchesPlayed)
    }

    @Test fun cupTrackRewardsStayClaimableAfterLosingCups() {
        val s = SaveData(cups = 30, bestCups = 30)
        assertEquals(listOf(10, 25), Progression.claimable(s).map { it.cups })
        // The server records a claim; losing Cups doesn't revoke what was reached.
        assertEquals(listOf(25), Progression.claimable(s.copy(cups = 5, claimedMilestones = setOf(10))).map { it.cups })
    }

    @Test fun dailyGiftOncePerDay() {
        assertTrue(Progression.dailyGiftAvailable(SaveData(), 10))
        assertFalse(Progression.dailyGiftAvailable(SaveData(lastDailyGiftDay = 10), 10))
        assertTrue(Progression.dailyGiftAvailable(SaveData(lastDailyGiftDay = 10), 11))
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

    @Test fun theServersProfileIsShownAsItIs() {
        val local = SaveData(
            bolts = 5, prisms = 5, selectedFighter = FighterId.MIRA,
            fighters = SaveData.defaultFighters() + (FighterId.MIRA to io.github.projectwip.data.FighterProgress(true, 3, skin = 2, ownedSkins = setOf(0, 2))) +
                (FighterId.JUNO to io.github.projectwip.data.FighterProgress(true, 9, skin = 1, ownedSkins = setOf(0, 1))),
        )
        val profile = io.github.projectwip.data.ServerProfile(
            bolts = 900, prisms = 40, bestCups = 250,
            fighters = mapOf(
                FighterId.JUNO to io.github.projectwip.data.FighterProgress(true, 4, ownedSkins = setOf(0, 1)),
                FighterId.BRAKK to io.github.projectwip.data.FighterProgress(true, 2, ownedSkins = setOf(0)),
            ),
            claimedMilestones = setOf(10, 25), lastDailyGiftDay = 7, lastFirstWinDay = 6,
        )
        val deal = io.github.projectwip.data.CustomOffer(id = 3, title = "Deal", bolts = 100, price = 5, purchased = 1)
        val synced = Progression.syncAccount(local, cups = 200, drops = 2, dropsLeftToday = 3, today = 7, profile = profile, deals = listOf(deal))
        assertEquals(900, synced.bolts)
        assertEquals(40, synced.prisms)
        assertEquals(250, synced.bestCups)
        assertEquals("the server's level wins", 4, synced.progress(FighterId.JUNO).level)
        assertEquals("the colourway being worn is kept", 1, synced.progress(FighterId.JUNO).skin)
        assertTrue(synced.progress(FighterId.BRAKK).unlocked)
        assertFalse("a fighter the server doesn't list as unlocked is locked", synced.progress(FighterId.MIRA).unlocked)
        assertEquals("and can't stay selected", FighterId.JUNO, synced.selectedFighter)
        assertEquals(setOf(10, 25), synced.claimedMilestones)
        assertFalse(Progression.dailyGiftAvailable(synced, 7))
        assertEquals(listOf(deal), synced.customOffers)
        assertEquals("applying the same account twice changes nothing", synced, Progression.syncAccount(synced, 200, 2, 3, 7, profile, listOf(deal)))
        assertEquals(1, Progression.dropOpened(synced).capsulesOpened)
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

    @Test fun levelsNeverRunOut() {
        // The price follows the table, then keeps climbing by a fixed step, and never drops.
        assertEquals(Balance.upgradeCost[0], Balance.upgradeCostFrom(1))
        assertEquals(Balance.upgradeCost.last(), Balance.upgradeCostFrom(Balance.upgradeCost.size))
        assertEquals(Balance.upgradeCost.last() + Balance.UPGRADE_COST_STEP, Balance.upgradeCostFrom(Balance.upgradeCost.size + 1))
        assertTrue((1..300).zipWithNext().all { (a, b) -> Balance.upgradeCostFrom(b) >= Balance.upgradeCostFrom(a) })
        // Normally the cap holds at MAX_LEVEL, and the dev toggle lifts it.
        val top = SaveData.defaultFighters() + (FighterId.JUNO to io.github.projectwip.data.FighterProgress(true, Balance.MAX_LEVEL))
        val capped = SaveData(bolts = 10_000_000, fighters = top)
        assertTrue(Progression.levelCapped(capped, FighterId.JUNO))
        assertFalse(Progression.canUpgrade(capped, FighterId.JUNO))
        assertTrue(Progression.canUpgrade(capped.copy(settings = io.github.projectwip.data.Settings(debugNoLevelCap = true)), FighterId.JUNO))
        val juno = Balance.fighter(FighterId.JUNO)
        assertEquals(juno.health.base + juno.health.perLevel * 60, juno.health.at(61))
    }

    @Test fun debugUpgradeCostScalesThePriceShown() {
        val normal = SaveData(bolts = 1000)
        assertEquals(Balance.upgradeCost[0], Progression.upgradeCost(normal, FighterId.JUNO))
        val free = normal.copy(bolts = 0, settings = io.github.projectwip.data.Settings(debugUpgradeCost = 0f))
        assertEquals(0, Progression.upgradeCost(free, FighterId.JUNO))
        assertTrue(Progression.canUpgrade(free, FighterId.JUNO))
        assertEquals(Balance.upgradeCost[0] * 3, Progression.upgradeCost(normal.copy(settings = io.github.projectwip.data.Settings(debugUpgradeCost = 3f)), FighterId.JUNO))
    }

    @Test fun walkingOutOfAFreeForAllIsLastPlaceNotFirst() {
        val config = io.github.projectwip.sim.MatchConfig(FighterId.JUNO, 1, 0, "Me", BotDifficulty.EASY, mode = GameMode.LAST_SPARK)
        val match = io.github.projectwip.sim.Match(config)
        assertEquals("still standing reads as first...", 1, match.report().placement)
        val left = match.forfeit()
        assertEquals("...but leaving puts you behind everyone still in", GameMode.LAST_SPARK.players, left.placement)
        assertEquals(MatchOutcome.DEFEAT, left.outcome)
        val team = io.github.projectwip.sim.Match(config.copy(mode = GameMode.KNOCKOUT_RUSH)).forfeit()
        assertEquals(MatchOutcome.DEFEAT, team.outcome)
        assertEquals(0, team.placement)
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
    }
}
