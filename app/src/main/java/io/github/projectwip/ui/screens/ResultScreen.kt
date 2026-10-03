package io.github.projectwip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.MatchOutcome
import io.github.projectwip.data.MatchRewards
import io.github.projectwip.data.SaveData
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.FighterRays
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameBackground
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.ProgressBar
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.plateShape
import io.github.projectwip.ui.rewardLabel
import io.github.projectwip.ui.startMatchConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ResultScreen(summary: MatchSummary, rewards: MatchRewards, save: SaveData, go: (Screen) -> Unit) {
    val r = summary.report
    val ui = LocalUi.current
    val sfx = LocalSfx.current
    val ffa = r.mode == io.github.projectwip.data.GameMode.LAST_SPARK
    val (label, color) = when {
        ffa && r.placement == 1 -> Pair("VICTORY! #1", Palette.Gold)
        ffa -> Pair("#${r.placement} PLACE", if (r.placement <= 4) Palette.Cyan else Palette.Red)
        r.outcome == MatchOutcome.VICTORY -> Pair("VICTORY!", Palette.Gold)
        r.outcome == MatchOutcome.DRAW -> Pair("DRAW", Color.White)
        else -> Pair("DEFEAT", Palette.Red)
    }
    val bannerPop = remember { Animatable(0.3f) }
    val cupsShown = remember { Animatable(rewards.cupsBefore.toFloat()) }
    /** How many reward rows have popped in so far. */
    var rowsShown by remember { mutableIntStateOf(0) }
    val won = r.outcome == MatchOutcome.VICTORY
    LaunchedEffect(Unit) {
        // The banner lands (lower and duller for a loss)...
        sfx?.play(Sound.BANNER, pitch = if (won) 1f else 0.72f)
        sfx?.buzz(50, 200)
        bannerPop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) {
        // ...then the rewards pop in one by one, each a little higher...
        delay(550)
        repeat(6) { i ->
            rowsShown = i + 1
            if (i < rewardRowCount(rewards, r.mvp && !ffa)) {
                // Every reward lands with a pop and a cha-ching, each one a little higher than the last.
                sfx?.play(Sound.POP, 0.6f, 0.9f + i * 0.1f); sfx?.play(Sound.CHING, 0.8f, 0.9f + i * 0.07f); sfx?.buzz(14, 110)
                delay(260)
            }
        }
        // ...and the Cups tick up to their new total.
        if (rewards.cupDelta != 0) {
            val ticking = launch {
                var n = 0
                while (true) { sfx?.play(Sound.COUNT, 0.6f, 0.85f + minOf(n, 12) * 0.05f); n++; delay(75) }
            }
            cupsShown.animateTo((rewards.cupsBefore + rewards.cupDelta).toFloat(), tween(1000, easing = FastOutSlowInEasing))
            ticking.cancel()
            if (rewards.cupDelta > 0) { sfx?.play(Sound.REWARD, 0.8f); sfx?.play(Sound.CHING, 0.9f, 1.15f) } else sfx?.play(Sound.DENIED, 0.8f)
        }
    }

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---------------- left: banner + scoreboard
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(if (ui.roomy) 130.dp else 96.dp).fillMaxWidth()) {
                    if (r.outcome == MatchOutcome.VICTORY) FighterRays(Modifier.size(360.dp), Palette.Gold)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { scaleX = bannerPop.value; scaleY = bannerPop.value }) {
                        GameText(label, Type.Display.copy(fontSize = Type.Display.fontSize * if (ui.roomy) 1.7f else 1.35f), color = color, outline = 5.dp)
                        PlainText(if (ffa) "Last Spark · Static Canyon · ${r.difficulty.label} bots" else "Knockout Rush · Foundry Yard · ${r.difficulty.label} bots", Type.Label, color = Palette.TextDim)
                    }
                }
                if (!ffa) Row(verticalAlignment = Alignment.CenterVertically) {
                    GameText(r.blueScore.let { if (summary.playerTeam == 0) it else r.redScore }.toString(), Type.Display, color = Palette.Ally, outline = 4.dp)
                    GameText("  –  ", Type.Title, outline = 3.dp)
                    GameText(r.redScore.let { if (summary.playerTeam == 0) it else r.blueScore }.toString(), Type.Display, color = Palette.Enemy, outline = 4.dp)
                }
                Spacer(Modifier.height(8.dp))
                if (ffa) {
                    // Standings: still-standing fighters first, then by finishing place.
                    val order = summary.players.sortedBy { if (it.placement == 0) 0 else it.placement }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TeamPanel("STANDINGS", order.take(5), Palette.Gold, Modifier.weight(1f), ranked = true)
                        TeamPanel(" ", order.drop(5), Palette.Gold, Modifier.weight(1f), ranked = true)
                    }
                } else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TeamPanel("YOUR TEAM", summary.players.filter { it.team == summary.playerTeam }, Palette.Ally, Modifier.weight(1f))
                    TeamPanel("OPPONENTS", summary.players.filter { it.team != summary.playerTeam }, Palette.Enemy, Modifier.weight(1f))
                }
            }

            // ---------------- right: rewards
            Column(Modifier.width(if (ui.wide) 360.dp else 300.dp).fillMaxHeight()) {
                Panel(Modifier.weight(1f).fillMaxWidth(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GameText("REWARDS", Type.Title, color = Palette.Gold, outline = 3.dp)
                        var row = 0
                        if (row++ < rowsShown) RewardRow(IconKind.CUP, "%,d".format(cupsShown.value.toInt()),
                            (if (rewards.cupDelta >= 0) "+" else "") + rewards.cupDelta,
                            if (rewards.cupDelta >= 0) Palette.GreenDeep else Palette.RedDeep)
                        if (row++ < rowsShown) RewardRow(IconKind.BOLT, "Bolts", "+${rewards.bolts}", Palette.CyanDeep)
                        if (rewards.firstWinPrisms > 0 && row++ < rowsShown) RewardRow(IconKind.PRISM, "First win of the day", "+${rewards.firstWinPrisms}", Palette.PrismDeep)
                        if (rewards.capsuleEarned && row++ < rowsShown) RewardRow(IconKind.CAPSULE, "Spark Drop", "+1", Palette.CyanDeep)
                        if (r.mvp && !ffa && row++ < rowsShown) RewardRow(IconKind.STAR, "MVP bonus", "+2 Cups", Palette.OrangeDeep)

                        val after = rewards.cupsBefore + rewards.cupDelta
                        val best = save.bestCups
                        val next = CupTrack.nextMilestone(best)
                        val prev = CupTrack.previousMilestoneCups(best)
                        Spacer(Modifier.weight(1f))
                        if (rewards.newlyReachedMilestones.isNotEmpty()) {
                            Badge("NEW CUP TRACK REWARD!", color = Palette.GreenDeep)
                            PlainText(rewards.newlyReachedMilestones.joinToString { rewardLabel(it.reward) }, Type.Small, color = Palette.Positive)
                        }
                        if (next != null) {
                            PlainText("Next reward at ${next.cups} Cups: ${rewardLabel(next.reward)}", Type.Small)
                            ProgressBar((best - prev).toFloat() / (next.cups - prev), Modifier.fillMaxWidth().height(16.dp))
                        }
                        PlainText("Cups now: $after", Type.Small)
                        if (!rewards.capsuleEarned && rewards.capsulesLeftToday <= 0) {
                            PlainText("All of today's Spark Drops are earned · more tomorrow", Type.Small)
                        }
                        if (!rewards.capsuleEarned && rewards.capsulesLeftToday > 0) {
                            PlainText("${if (ffa) "Finish top 4" else "Win"} to earn a Spark Drop · ${rewards.capsulesLeftToday} left today", Type.Small)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChunkyButton({ go(Screen.Home) }, Modifier.weight(1f).height(66.dp), ButtonStyle.PURPLE) { GameText("HOME", Type.Title) }
                    ChunkyButton({ go(Screen.Match(startMatchConfig(save))) }, Modifier.weight(1.3f).height(66.dp), ButtonStyle.ORANGE) {
                        GameText("PLAY AGAIN", Type.Heading)
                    }
                }
            }
        }
    }
}

/** How many rows the rewards panel will show, so each one gets its own pop. */
private fun rewardRowCount(rewards: MatchRewards, mvpBonus: Boolean) =
    2 + (if (rewards.firstWinPrisms > 0) 1 else 0) + (if (rewards.capsuleEarned) 1 else 0) + (if (mvpBonus) 1 else 0)

@Composable
private fun RewardRow(icon: IconKind, label: String, value: String, chip: Color) {
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = pop.value; scaleY = pop.value }.drawBehind {
            val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
            val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
            drawPath(p, Palette.PanelInset)
            drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
        }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameIcon(icon, Modifier.size(38.dp))
        Spacer(Modifier.width(8.dp))
        GameText(label, Type.Heading, outline = 2.5.dp, modifier = Modifier.weight(1f))
        Badge(value, color = chip)
    }
}

@Composable
private fun TeamPanel(title: String, players: List<PlayerLine>, color: Color, modifier: Modifier, ranked: Boolean = false) {
    Panel(modifier.fillMaxHeight(), cut = 14.dp) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GameText(title, Type.Label, color = color, outline = 2.dp)
            for (p in if (ranked) players else players.sortedByDescending { it.kos * 1000 + it.damage / 10 }) {
                Row(
                    Modifier.fillMaxWidth().weight(1f).drawBehind {
                        val o = plateShape(8.dp, 3.dp).createOutline(size, layoutDirection, this)
                        val path = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
                        drawPath(path, if (p.isPlayer) Color(0xFF3E6A2E) else Palette.PanelInset)
                        drawPath(path, Palette.Ink, style = Stroke(2.dp.toPx()))
                    }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ranked) {
                        GameText(if (p.placement == 0) "—" else "#${p.placement}", Type.Heading,
                            color = if (p.placement == 1) Palette.Gold else Color.White, outline = 2.dp, modifier = Modifier.width(44.dp))
                    }
                    Box(Modifier.fillMaxHeight().width(46.dp)) {
                        FighterView(Balance.fighter(p.fighter), p.skin, Modifier.fillMaxSize(), pedestal = false)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GameText(p.name, Type.Label, outline = 2.dp, color = if (p.isPlayer) Palette.Gold else Color.White)
                            if (p.isBot) PlainText("  BOT", Type.Small)
                            if (p.isMvp) { Spacer(Modifier.width(4.dp)); GameIcon(IconKind.STAR, Modifier.size(18.dp)) }
                        }
                        PlainText("${p.kos} KO · ${p.deaths} down · ${"%,d".format(p.damage)} dmg", Type.Small)
                    }
                }
            }
        }
    }
}
