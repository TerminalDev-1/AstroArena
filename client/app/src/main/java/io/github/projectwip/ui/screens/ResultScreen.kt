package io.github.projectwip.ui.screens

import io.github.projectwip.ui.bleed
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.FighterRanks
import androidx.compose.runtime.mutableStateOf
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

/** How long the Cups a match paid take to fly to the counter, and the share of that one Cup is in the air for. */
private const val FLIGHT_MS = 1500
private const val FLIGHT_SPAN = 0.55f

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
    val me = summary.players.firstOrNull { it.isPlayer }
    val fighterCupsAfter = rewards.fighterCupsBefore + rewards.fighterCupDelta
    val fighterCupsShown = remember { Animatable(rewards.fighterCupsBefore.toFloat()) }
    val rankedUp = FighterRanks.rank(fighterCupsAfter) > FighterRanks.rank(rewards.fighterCupsBefore)
    /** Springs up when the fighter's rank changes. */
    val rankPop = remember { Animatable(1f) }
    /** The medallion's turn and the burst of sparks round it when the rank changes, 0..1. */
    val rankSpin = remember { Animatable(0f) }
    val rankBurst = remember { Animatable(0f) }
    var celebrate by remember { mutableStateOf(false) }
    /** How many reward rows have popped in so far. */
    var rowsShown by remember { mutableIntStateOf(0) }
    val won = r.outcome == MatchOutcome.VICTORY
    // Cups that were won fly from the banner to the counter, which counts up as they land. Each is thrown out to
    // a spot of its own first (these, as a share of the throw's reach).
    val flyingCups = remember {
        val rng = kotlin.random.Random(rewards.cupsBefore * 31 + rewards.cupDelta)
        List(if (rewards.cupDelta > 0) rewards.cupDelta.coerceIn(6, 14) else 0) { Offset(rng.nextFloat() * 2f - 1f, rng.nextFloat() * 2f - 1f) }
    }
    val flight = remember { Animatable(0f) }
    var flyFrom by remember { mutableStateOf(Offset.Unspecified) }
    var flyTo by remember { mutableStateOf(Offset.Unspecified) }
    var flyOrigin by remember { mutableStateOf(Offset.Zero) }
    /** The reveal ([ResultReveal]) plays first; the stats and their own animations follow it. */
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (!revealed) return@LaunchedEffect
        // The banner lands (lower and duller for a loss)...
        sfx?.play(Sound.WHOOSH, 0.7f)
        sfx?.play(Sound.BANNER, pitch = if (won) 1f else 0.72f)
        sfx?.buzz(50, 200)
        bannerPop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(revealed) {
        if (!revealed) return@LaunchedEffect
        // ...then the rewards pop in one by one, each a little higher...
        delay(550)
        repeat(8) { i ->
            rowsShown = i + 1
            if (i < rewardRowCount(rewards, r.mvp && r.mode == io.github.projectwip.data.GameMode.KNOCKOUT_RUSH)) {
                // Every reward lands with a pop and a cha-ching, each one a little higher than the last.
                sfx?.play(Sound.POP, 0.6f, 0.9f + i * 0.1f); sfx?.play(Sound.CHING, 0.8f, 0.9f + i * 0.07f); sfx?.buzz(14, 110)
                delay(260)
            }
        }
        // ...and the Cups tick up to their new total.
        if (rewards.cupDelta != 0) {
            val flying = flyingCups.isNotEmpty()
            if (flying) {
                sfx?.play(Sound.POP, 0.8f, 0.7f); sfx?.buzz(30, 160)
                launch { flight.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing)) }
                // The count starts as the first of them lands.
                delay((FLIGHT_MS * FLIGHT_SPAN).toLong())
            }
            val counting = tween<Float>(if (flying) (FLIGHT_MS * (1f - FLIGHT_SPAN)).toInt() else 1000, easing = if (flying) LinearEasing else FastOutSlowInEasing)
            val ticking = launch {
                var n = 0
                while (true) { sfx?.play(Sound.COUNT, 0.6f, 0.85f + minOf(n, 12) * 0.05f); n++; delay(75) }
            }
            launch { fighterCupsShown.animateTo(fighterCupsAfter.toFloat(), counting) }
            cupsShown.animateTo((rewards.cupsBefore + rewards.cupDelta).toFloat(), counting)
            ticking.cancel()
            if (rewards.cupDelta > 0) { sfx?.play(Sound.REWARD, 0.8f); sfx?.play(Sound.CHING, 0.9f, 1.15f) } else sfx?.play(Sound.DENIED, 0.8f)
        }
        // A new rank: the badge leaps, the rays come out, and it gets a fanfare of its own.
        if (rankedUp) {
            delay(150)
            celebrate = true
            sfx?.play(Sound.UPGRADE, 1f); sfx?.play(Sound.CHING, 0.9f, 1.3f); sfx?.buzz(90, 230)
            launch { rankSpin.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
            launch { rankBurst.animateTo(1f, tween(1100, easing = LinearEasing)) }
            rankPop.snapTo(1.9f)
            rankPop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { flyOrigin = it.positionInRoot() }) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        androidx.compose.foundation.layout.Box(Modifier.bleed().fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        // Light and motion behind everything: rays turning round the title, and confetti for a win.
        ResultBackdrop(color, won || (ffa && r.placement == 1), if (flyFrom.isSpecified) flyFrom - flyOrigin else Offset.Unspecified)
        Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---------------- left: banner + scoreboard
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(if (ui.roomy) 136.dp else 100.dp).fillMaxWidth().onGloballyPositioned { flyFrom = it.boundsInRoot().center }) {
                    OutcomeRibbon(color, Modifier.fillMaxWidth(0.94f).height(if (ui.roomy) 92.dp else 66.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { scaleX = bannerPop.value; scaleY = bannerPop.value }) {
                        GameText(label, Type.Display.copy(fontSize = Type.Display.fontSize * if (ui.roomy) 1.6f else 1.3f), color = color, outline = 5.dp)
                        PlainText(
                            when (r.mode) {
                                io.github.projectwip.data.GameMode.LAST_SPARK -> "Last Spark · Static Canyon · ${r.difficulty.label} bots"
                                io.github.projectwip.data.GameMode.KNOCKOUT_RUSH -> "Knockout Rush · Foundry Yard · ${r.difficulty.label} bots"
                                io.github.projectwip.data.GameMode.BOSS -> "Boss Mode · Proving Ground · ${r.difficulty.label} boss"
                                io.github.projectwip.data.GameMode.TRAINING -> "Training Area"
                                io.github.projectwip.data.GameMode.DUEL -> "1v1 · Proving Ground"
                            }, Type.Label, color = Palette.TextDim)
                    }
                }
                if (!ffa) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.arrive(0, 0f, -30f)) {
                    GameText(r.blueScore.let { if (summary.playerTeam == 0) it else r.redScore }.toString(), Type.Display, color = Palette.Ally, outline = 4.dp)
                    GameText("  –  ", Type.Title, outline = 3.dp)
                    GameText(r.redScore.let { if (summary.playerTeam == 0) it else r.blueScore }.toString(), Type.Display, color = Palette.Enemy, outline = 4.dp)
                }
                Spacer(Modifier.height(10.dp))
                if (ffa) {
                    // Standings: still-standing fighters first, then by finishing place.
                    val order = summary.players.sortedBy { if (it.placement == 0) 0 else it.placement }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TeamPanel("STANDINGS", order.take(5), Palette.Gold, Modifier.weight(1f), ranked = true)
                        TeamPanel(" ", order.drop(5), Palette.Gold, Modifier.weight(1f), ranked = true, first = 5)
                    }
                } else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bossMode = r.mode == io.github.projectwip.data.GameMode.BOSS
                    TeamPanel(if (bossMode) "YOU" else "YOUR TEAM", summary.players.filter { it.team == summary.playerTeam }, Palette.Ally, Modifier.weight(1f))
                    TeamPanel(if (bossMode) "THE BOSS" else "OPPONENTS", summary.players.filter { it.team != summary.playerTeam }, Palette.Enemy, Modifier.weight(1f), first = 3)
                }
            }

            // ---------------- right: the fighter's rank, the Cups, and what the match paid
            Column(Modifier.width(if (ui.wide) 360.dp else 300.dp).fillMaxHeight().arrive(0, 60f, 0f)) {
                Panel(Modifier.weight(1f).fillMaxWidth(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val shownCups = fighterCupsShown.value.toInt()
                        HeaderChip(if (celebrate) "RANK UP!" else "FIGHTER RANK", if (celebrate) Palette.Positive else Palette.Gold,
                            Modifier.graphicsLayer { scaleX = rankPop.value.coerceAtMost(1.3f); scaleY = rankPop.value.coerceAtMost(1.3f) })
                        // The fighter, on a lit pedestal, with its rank medallion at its shoulder.
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (celebrate || won) FighterRays(Modifier.size(300.dp), if (celebrate) Palette.Positive else Palette.Gold)
                            Pedestal(if (celebrate) Palette.Positive else Palette.Cyan, Modifier.fillMaxSize())
                            if (me != null) FighterView(me.boss?.let { Balance.boss(it) } ?: Balance.fighter(me.fighter), me.skin, Modifier.fillMaxSize(), pedestal = false)
                            RankMedallion(FighterRanks.label(shownCups), FighterRanks.progress(shownCups), rankSpin.value, rankBurst.value,
                                Modifier.align(Alignment.BottomEnd).size(if (ui.roomy) 84.dp else 66.dp).graphicsLayer { scaleX = rankPop.value; scaleY = rankPop.value })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            GameText(me?.let { Balance.fighter(it.fighter).name.uppercase() } ?: "", Type.Heading, outline = 2.5.dp, modifier = Modifier.weight(1f))
                            GameIcon(IconKind.CUP, Modifier.size(22.dp))
                            Spacer(Modifier.width(4.dp))
                            GameText("%,d".format(shownCups), Type.Heading, outline = 2.5.dp)
                            if (rewards.fighterCupDelta != 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge((if (rewards.fighterCupDelta > 0) "+" else "") + rewards.fighterCupDelta, color = if (rewards.fighterCupDelta > 0) Palette.GreenDeep else Palette.RedDeep)
                            }
                        }
                        PlainText(FighterRanks.nextAt(shownCups).let { "Rank ${FighterRanks.label(it)} at ${"%,d".format(it)} Cups" }, Type.Small, color = Palette.TextDim)

                        // The tray: the player's own Cups, the Cup Track they climb, and what the match paid.
                        Column(Modifier.fillMaxWidth().tray().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            var row = 0
                            val cupIcon = with(LocalDensity.current) { 27.dp.toPx() }
                            if (row++ < rowsShown) Box(Modifier.fillMaxWidth().onGloballyPositioned { val b = it.boundsInRoot(); flyTo = Offset(b.left + cupIcon, b.center.y) }.graphicsLayer {
                                // The counter jumps a little as each Cup lands in it.
                                val landing = (flight.value - FLIGHT_SPAN) / (1f - FLIGHT_SPAN)
                                val bump = if (landing > 0f && landing < 1f) 0.05f * kotlin.math.abs(kotlin.math.sin(landing * flyingCups.size * Math.PI.toFloat())) else 0f
                                scaleX = 1f + bump; scaleY = 1f + bump
                            }) {
                                RewardRow(IconKind.CUP, "%,d".format(cupsShown.value.toInt()),
                                    (if (rewards.cupDelta >= 0) "+" else "") + rewards.cupDelta,
                                    if (rewards.cupDelta >= 0) Palette.GreenDeep else Palette.RedDeep)
                            }
                            val best = save.bestCups
                            val next = CupTrack.nextMilestone(best)
                            val prev = CupTrack.previousMilestoneCups(best)
                            if (rewards.newlyReachedMilestones.isNotEmpty()) {
                                Badge("NEW CUP TRACK REWARD!", color = Palette.GreenDeep)
                                PlainText(rewards.newlyReachedMilestones.joinToString { rewardLabel(it.reward) }, Type.Small, color = Palette.Positive)
                            }
                            if (next != null) {
                                ProgressBar((best - prev).toFloat() / (next.cups - prev), Modifier.fillMaxWidth().height(10.dp))
                                PlainText("Cup Track: ${rewardLabel(next.reward)} at ${next.cups} Cups", Type.Small, color = Palette.Text)
                            }
                            // What the match paid: a tile each, popping in one by one.
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                if (row++ < rowsShown) RewardTile(IconKind.BOLT, "+${rewards.bolts}", Palette.Orange)
                                if (rewards.credits > 0 && row++ < rowsShown) RewardTile(IconKind.CREDIT, "+${rewards.credits}", Palette.Gold)
                                if (rewards.firstWinPrisms > 0 && row++ < rowsShown) RewardTile(IconKind.PRISM, "+${rewards.firstWinPrisms}", Palette.Prism)
                                if (rewards.capsuleEarned && row++ < rowsShown) RewardTile(IconKind.CAPSULE, "+1", Palette.Cyan)
                            }
                            if (rewards.unlocked.isNotEmpty() && row++ < rowsShown) for (f in rewards.unlocked) {
                                Badge("${Balance.fighter(f).name.substringBefore(' ').uppercase()} UNLOCKED ON THE SPARK ROAD!", Modifier.arrive(0, 0f, 20f), color = Palette.GoldDeep)
                            }
                            if (rewards.mvpCups > 0 && row++ < rowsShown) Badge("MVP · +${rewards.mvpCups} CUPS", Modifier.arrive(0, 0f, 20f), color = Palette.GoldDeep)
                        }
                        val dropsHere = r.mode != io.github.projectwip.data.GameMode.BOSS && r.mode != io.github.projectwip.data.GameMode.DUEL && rewards.online
                        // Everything a match is worth is awarded by the server; without it a match is practice.
                        if (r.mode == io.github.projectwip.data.GameMode.DUEL && !rewards.online) PlainText("This 1v1 wasn't settled by the server · nothing is earned", Type.Small, color = Palette.Gold)
                        else if (!rewards.online) PlainText("The server couldn't be reached · this match wasn't counted", Type.Small, color = Palette.Gold)
                        if (dropsHere && !rewards.capsuleEarned && rewards.capsulesLeftToday <= 0) {
                            PlainText("All of today's Arena Boxes are earned · more tomorrow", Type.Small, color = Palette.Text)
                        }
                        if (dropsHere && !rewards.capsuleEarned && rewards.capsulesLeftToday > 0) {
                            PlainText("${if (ffa) "Finish top 4" else "Win"} to earn an Arena Box · ${rewards.capsulesLeftToday} left today", Type.Small, color = Palette.Text)
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
        // The Cups the match paid, on their way from the banner to the counter.
        val f = flight.value
        if (f > 0f && f < 1f && flyFrom.isSpecified && flyTo.isSpecified) {
            for ((i, thrown) in flyingCups.withIndex()) {
                val u = (f - i.toFloat() / (flyingCups.size - 1) * (1f - FLIGHT_SPAN)) / FLIGHT_SPAN
                if (u <= 0f || u >= 1f) continue
                GameIcon(IconKind.CUP, Modifier.size(36.dp).graphicsLayer {
                    // Thrown out from the banner, slowing; then off to the counter along an arc, gathering speed.
                    val reach = 110.dp.toPx()
                    val out = Offset(flyFrom.x + thrown.x * reach, flyFrom.y + thrown.y * reach * 0.5f)
                    val at = if (u < 0.3f) {
                        val k = 1f - (1f - u / 0.3f) * (1f - u / 0.3f)
                        Offset(flyFrom.x + (out.x - flyFrom.x) * k, flyFrom.y + (out.y - flyFrom.y) * k)
                    } else {
                        val k = (u - 0.3f) / 0.7f * ((u - 0.3f) / 0.7f)
                        val top = Offset((out.x + flyTo.x) / 2f, minOf(out.y, flyTo.y) - 70.dp.toPx())
                        Offset(
                            (1f - k) * (1f - k) * out.x + 2f * (1f - k) * k * top.x + k * k * flyTo.x,
                            (1f - k) * (1f - k) * out.y + 2f * (1f - k) * k * top.y + k * k * flyTo.y,
                        )
                    }
                    translationX = at.x - flyOrigin.x - size.width / 2f
                    translationY = at.y - flyOrigin.y - size.height / 2f
                    val grow = if (u < 0.12f) u / 0.12f * 1.3f else 1.3f - 0.5f * (u - 0.12f) / 0.88f
                    scaleX = grow; scaleY = grow
                    rotationZ = thrown.x * 50f * (1f - u)
                })
            }
        }
        // The reveal plays over it all first, then sweeps away to show it.
        if (!revealed) ResultReveal(summary) { revealed = true }
    }
}

/** How many rows the rewards panel will show, so each one gets its own pop. */
private fun rewardRowCount(rewards: MatchRewards, mvpBonus: Boolean) =
    2 + (if (rewards.credits > 0) 1 else 0) + (if (rewards.unlocked.isNotEmpty()) 1 else 0) + (if (rewards.firstWinPrisms > 0) 1 else 0) + (if (rewards.capsuleEarned) 1 else 0) + (if (mvpBonus) 1 else 0)

/** A fighter's rank: a gold medallion with the rank on it. */
@Composable
fun RankBadge(label: String, modifier: Modifier = Modifier) {
    Box(modifier.drawBehind {
        val r = size.minDimension / 2
        drawCircle(Palette.Ink, r)
        drawCircle(Palette.GoldDeep, r * 0.9f)
        drawCircle(Palette.Gold, r * 0.74f)
        drawCircle(Color.White.copy(alpha = 0.35f), r * 0.5f, center.copy(y = center.y - r * 0.22f))
    }, contentAlignment = Alignment.Center) {
        GameText(label, if (label.length > 3) Type.Small else if (label.length > 2) Type.Label else Type.Title, outline = 2.5.dp)
    }
}

/**
 * Slides and fades something in [index] steps after the screen opens, from ([dx], [dy]) dp away. Used to bring the
 * standings in row by row and the panels in from the sides.
 */
@Composable
private fun Modifier.arrive(index: Int, dx: Float, dy: Float): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(180L + index * 70L); a.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        alpha = a.value
        translationX = (1f - a.value) * dx.dp.toPx()
        translationY = (1f - a.value) * dy.dp.toPx()
    }
}

/**
 * Behind the whole screen: long rays turning slowly round the title with a glow in the outcome's colour, and
 * confetti tumbling down for a win (dim motes drifting up otherwise).
 */
@Composable
private fun ResultBackdrop(color: Color, won: Boolean, focus: Offset) {
    val time by io.github.projectwip.ui.rememberAnimTime()
    val bits = remember { val rng = kotlin.random.Random(91); List(80) { FloatArray(5) { rng.nextFloat() } } }
    val confetti = remember { listOf(Palette.Gold, Palette.Cyan, Palette.Positive, Palette.Prism, Color.White, Palette.Orange) }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = if (focus.isSpecified) focus else Offset(size.width * 0.33f, size.height * 0.14f)
        val reach = size.maxDimension * 1.2f
        val rays = 18
        rotate(time * 5f, c) {
            for (i in 0 until rays) {
                val a0 = i * 2.0 * Math.PI / rays
                val a1 = a0 + Math.PI / rays * 0.55
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(c.x + (kotlin.math.cos(a0) * reach).toFloat(), c.y + (kotlin.math.sin(a0) * reach).toFloat())
                    lineTo(c.x + (kotlin.math.cos(a1) * reach).toFloat(), c.y + (kotlin.math.sin(a1) * reach).toFloat())
                    close()
                }
                drawPath(p, androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = if (won) 0.24f else 0.09f), Color.Transparent), c, reach * 0.7f))
            }
        }
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = if (won) 0.3f else 0.16f), Color.Transparent), c, size.minDimension * 0.6f), size.minDimension * 0.6f, c)
        for ((n, b) in bits.withIndex()) {
            val sway = kotlin.math.sin(time * (0.6f + b[2]) + b[3] * 6.28f) * 18.dp.toPx()
            if (won) {
                val y = ((b[1] + time * (0.04f + 0.07f * b[2])) % 1.1f - 0.05f) * size.height
                val x = b[0] * size.width + sway
                val w = (5f + 5f * b[4]).dp.toPx()
                rotate(time * (b[2] - 0.5f) * 400f + b[3] * 360f, Offset(x, y)) {
                    // A tumbling slip of paper: it narrows as it turns edge-on.
                    val turn = kotlin.math.abs(kotlin.math.cos(time * (2f + 3f * b[4]) + n))
                    drawRect(confetti[n % confetti.size], Offset(x - w / 2f, y - w * 0.3f * turn), androidx.compose.ui.geometry.Size(w, w * 0.6f * turn + 1f), alpha = 0.8f)
                }
            } else {
                val y = (1f - (b[1] + time * (0.015f + 0.02f * b[2])) % 1f) * size.height
                drawCircle(color.copy(alpha = 0.18f + 0.15f * b[4]), (2f + 3f * b[4]).dp.toPx(), Offset(b[0] * size.width + sway, y))
            }
        }
    }
}

/** The slab the outcome stands on: a slanted dark ribbon with a visible side, edged in [color], that slides open. */
@Composable
private fun OutcomeRibbon(color: Color, modifier: Modifier) {
    val open = remember { Animatable(0f) }
    val shine = remember { Animatable(-0.3f) }
    LaunchedEffect(Unit) {
        open.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        shine.animateTo(1.3f, tween(900, easing = FastOutSlowInEasing))
    }
    androidx.compose.foundation.Canvas(modifier.graphicsLayer { scaleX = open.value; alpha = open.value.coerceIn(0f, 1f) }) {
        val skew = size.height * 0.4f
        val side = 7.dp.toPx()
        val ink = 3.dp.toPx()
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(skew, 0f); lineTo(size.width, 0f); lineTo(size.width - skew, size.height); lineTo(0f, size.height); close()
        }
        translate(top = side) {
            drawPath(p, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.6f))
            drawPath(p, Palette.Ink, style = Stroke(ink))
        }
        drawPath(p, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.PanelLight, Palette.PanelDark, Palette.Ink)))
        clipPath(p) {
            drawLine(color, Offset(0f, ink * 1.6f), Offset(size.width, ink * 1.6f), 3.dp.toPx())
            drawLine(color.copy(alpha = 0.6f), Offset(0f, size.height - ink * 1.6f), Offset(size.width, size.height - ink * 1.6f), 2.dp.toPx())
            val x = shine.value * size.width
            drawLine(Color.White.copy(alpha = 0.22f), Offset(x, size.height), Offset(x + size.height, 0f), size.height * 0.5f)
        }
        drawPath(p, Palette.Ink, style = Stroke(ink))
    }
}

/** A small slab heading. */
@Composable
private fun HeaderChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.drawBehind {
        val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
        val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
        translate(top = 4.dp.toPx()) { drawPath(p, Color.Black.copy(alpha = 0.45f)) }
        drawPath(p, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.PanelDark, Palette.Ink)))
        drawLine(color, Offset(size.width * 0.12f, size.height - 4.dp.toPx()), Offset(size.width * 0.88f, size.height - 4.dp.toPx()), 2.5.dp.toPx())
        drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
    }.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)) {
        GameText(text, Type.Title, color = color, outline = 3.dp)
    }
}

/** A lit disc for the fighter to stand on, ringed in [color]. */
@Composable
private fun Pedestal(color: Color, modifier: Modifier) {
    val time by io.github.projectwip.ui.rememberAnimTime()
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.minDimension * 0.62f
        val h = w * 0.28f
        val top = Offset(center.x - w / 2f, size.height * 0.84f - h / 2f)
        drawOval(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), Offset(center.x, top.y + h / 2f), w * 0.6f),
            Offset(center.x - w * 0.6f, top.y - h * 0.1f), androidx.compose.ui.geometry.Size(w * 1.2f, h * 1.2f))
        drawOval(Palette.PanelInset.copy(alpha = 0.85f), top, androidx.compose.ui.geometry.Size(w, h))
        drawOval(color.copy(alpha = 0.55f + 0.25f * kotlin.math.sin(time * 2.5f)), top, androidx.compose.ui.geometry.Size(w, h), style = Stroke(2.5.dp.toPx()))
    }
}

/**
 * A fighter's rank medallion, with a ring round it that fills toward the next rank. On a rank-up it turns over
 * ([spin], 0..1) and throws a ring of sparks ([burst], 0..1).
 */
@Composable
private fun RankMedallion(label: String, progress: Float, spin: Float, burst: Float, modifier: Modifier = Modifier) {
    Box(modifier.drawBehind {
        val r = size.minDimension / 2
        if (burst > 0f && burst < 1f) {
            for (i in 0 until 18) {
                val a = i * 2.0 * Math.PI / 18
                val d = r * (1.05f + burst * 1.4f)
                drawCircle(if (i % 2 == 0) Palette.Positive else Palette.Gold, (5.dp.toPx()) * (1f - burst),
                    Offset(center.x + (kotlin.math.cos(a) * d).toFloat(), center.y + (kotlin.math.sin(a) * d).toFloat()), alpha = 1f - burst)
            }
        }
        drawCircle(Palette.Ink, r)
        val ring = r * 0.17f
        val inset = ring / 2f + r * 0.08f
        val arcSize = androidx.compose.ui.geometry.Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
        drawArc(Palette.PanelInset, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(ring))
        drawArc(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Palette.GoldDeep, Palette.Gold, Palette.Positive, Palette.GoldDeep), center), -90f, 360f * progress.coerceIn(0f, 1f),
            false, Offset(inset, inset), arcSize, style = Stroke(ring, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(0.66f).graphicsLayer { rotationY = spin * 360f; cameraDistance = 12f * density }.drawBehind {
            val r = size.minDimension / 2
            drawCircle(Palette.GoldDeep, r)
            drawCircle(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFE28A), Palette.Gold, Palette.GoldDeep)), r * 0.84f)
            drawCircle(Color.White.copy(alpha = 0.35f), r * 0.45f, center.copy(y = center.y - r * 0.3f))
        }, contentAlignment = Alignment.Center) {
            GameText(label, if (label.length > 3) Type.Small else if (label.length > 2) Type.Label else Type.Title, outline = 2.5.dp)
        }
    }
}

/** A recessed well inside a panel, for the rewards to sit in. */
private fun Modifier.tray() = drawBehind {
    val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
    val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
    drawPath(p, Palette.PanelInset.copy(alpha = 0.85f))
    clipPath(p) {
        // Its upper lip throws a shadow into it.
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent), 0f, 12.dp.toPx()))
    }
    drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
}

/** One thing the match paid: a little slab with a glow behind its icon, springing in with a shine across it. */
@Composable
private fun RewardTile(icon: IconKind, value: String, glow: Color) {
    val pop = remember { Animatable(0f) }
    val shine = remember { Animatable(-0.6f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)) }
        delay(120)
        shine.animateTo(1.6f, tween(650))
    }
    Column(
        Modifier.graphicsLayer {
            val k = 0.4f + 0.6f * pop.value
            scaleX = k; scaleY = k
            alpha = pop.value.coerceIn(0f, 1f)
            rotationZ = (1f - pop.value) * -14f
        }.drawBehind {
            val o = plateShape(9.dp, 3.dp).createOutline(size, layoutDirection, this)
            val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
            translate(top = 4.dp.toPx()) {
                drawPath(p, androidx.compose.ui.graphics.lerp(Palette.PanelInset, Color.Black, 0.5f))
                drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
            }
            drawPath(p, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.PanelLight, Palette.PanelDark)))
            clipPath(p) {
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent), Offset(size.width / 2f, 22.dp.toPx()), 28.dp.toPx()),
                    28.dp.toPx(), Offset(size.width / 2f, 22.dp.toPx()))
                val x = shine.value * size.width
                drawLine(Color.White.copy(alpha = 0.3f), Offset(x, size.height), Offset(x + size.height * 0.6f, 0f), 10.dp.toPx())
            }
            drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
        }.padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GameIcon(icon, Modifier.size(32.dp))
        GameText(value, Type.Label, outline = 2.dp)
    }
}

@Composable
private fun RewardRow(icon: IconKind, label: String, value: String, chip: Color) {
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = pop.value; scaleY = pop.value }.drawBehind {
            val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
            val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
            translate(top = 4.dp.toPx()) { drawPath(p, androidx.compose.ui.graphics.lerp(Palette.PanelInset, Color.Black, 0.5f)) }
            drawPath(p, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.PanelLight, Palette.PanelDark)))
            drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
        }.padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameIcon(icon, Modifier.size(34.dp))
        Spacer(Modifier.width(8.dp))
        GameText(label, Type.Heading, outline = 2.5.dp, modifier = Modifier.weight(1f))
        Badge(value, color = chip)
    }
}

@Composable
private fun TeamPanel(title: String, players: List<PlayerLine>, color: Color, modifier: Modifier, ranked: Boolean = false, first: Int = 0) {
    Panel(modifier.fillMaxHeight(), cut = 14.dp) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GameText(title, Type.Label, color = color, outline = 2.dp)
            for ((n, p) in (if (ranked) players else players.sortedByDescending { it.kos * 1000 + it.damage / 10 }).withIndex()) {
                Row(
                    Modifier.fillMaxWidth().weight(1f).arrive(first + n, -50f, 0f).drawBehind {
                        val o = plateShape(8.dp, 3.dp).createOutline(size, layoutDirection, this)
                        val path = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
                        // Each row is a little slab, the player's lit green.
                        translate(top = 3.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.4f)) }
                        drawPath(path, if (p.isPlayer) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF4F8A3A), Color(0xFF2C5222)))
                            else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.PanelDark, Palette.PanelInset)))
                        drawPath(path, Palette.Ink, style = Stroke(2.dp.toPx()))
                    }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ranked) {
                        GameText(if (p.placement == 0) "—" else "#${p.placement}", Type.Heading,
                            color = if (p.placement == 1) Palette.Gold else Color.White, outline = 2.dp, modifier = Modifier.width(38.dp))
                    }
                    Box(Modifier.fillMaxHeight().width(if (ranked) 22.dp else 46.dp)) {
                        FighterView(p.boss?.let { Balance.boss(it) } ?: Balance.fighter(p.fighter), p.skin, Modifier.fillMaxSize(), pedestal = false)
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
