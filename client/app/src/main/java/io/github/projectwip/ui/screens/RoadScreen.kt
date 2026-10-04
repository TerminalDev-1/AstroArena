package io.github.projectwip.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.PassState
import io.github.projectwip.data.Reward
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.SparkRoad
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalServer
import io.github.projectwip.ui.LocalServerCall
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardReveal
import io.github.projectwip.ui.RewardVisual
import io.github.projectwip.ui.SCRIM
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rewardLabel

private val CREDIT = Color(0xFF3FE08A)
private val CREDIT_DEEP = Color(0xFF159A5E)

/** A bar that is [fraction] full. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = CREDIT, height: Dp = 16.dp) {
    Canvas(modifier.height(height)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(Palette.Ink, cornerRadius = r)
        val inset = 2.5.dp.toPx()
        drawRoundRect(Palette.PanelInset, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2), r)
        val w = (size.width - inset * 2) * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, Offset(inset, inset), androidx.compose.ui.geometry.Size(w.coerceAtLeast(size.height - inset * 2), size.height - inset * 2), r)
    }
}

/**
 * The Spark Road: the fighters in the order they are unlocked, strung along a road. Credits fill the next one's
 * bar; when it is full the player unlocks it (the server spends the Credits), and the one after becomes next.
 */
@Composable
fun RoadScreen(save: SaveData, go: (Screen) -> Unit, showReward: (RewardReveal) -> Unit) {
    val ask = LocalServerCall.current
    val ui = LocalUi.current
    val next = SparkRoad.next(save)
    val cardW = if (ui.roomy) 150.dp else 128.dp
    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SPARK ROAD", { go(Screen.Fighters()) }, null, save.prisms, credits = save.credits)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                // The road itself: a wide band that winds from one stop to the next, with a dashed line down the middle.
                Canvas(Modifier.fillMaxSize()) {
                    val road = Path().apply {
                        val mid = size.height * 0.5f
                        val swing = 26.dp.toPx()
                        moveTo(0f, mid + swing)
                        val stops = SparkRoad.steps.size + 1
                        for (i in 0 until stops) {
                            val x0 = size.width * i / stops; val x1 = size.width * (i + 1) / stops
                            val y0 = mid + if (i % 2 == 0) swing else -swing; val y1 = mid + if (i % 2 == 0) -swing else swing
                            cubicTo(x0 + (x1 - x0) * 0.5f, y0, x0 + (x1 - x0) * 0.5f, y1, x1, y1)
                        }
                    }
                    drawPath(road, Palette.Ink, style = Stroke(58.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(road, Color(0xFF5B45C8), style = Stroke(48.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(road, CREDIT.copy(alpha = 0.8f), style = Stroke(4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 14.dp.toPx()))))
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoadStop(FighterId.JUNO, cardW, Modifier.offset(y = 22.dp), unlocked = true) { Badge("START", color = Palette.CyanDeep) }
                    SparkRoad.steps.forEachIndexed { i, step ->
                        val unlocked = save.progress(step.fighter).unlocked
                        val isNext = step == next
                        RoadStop(step.fighter, cardW, Modifier.offset(y = if (i % 2 == 0) (-22).dp else 22.dp), unlocked, highlight = isNext) {
                            when {
                                unlocked -> Badge("UNLOCKED", color = Palette.GreenDeep)
                                isNext -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ProgressBar(save.credits.toFloat() / step.cost, Modifier.fillMaxWidth())
                                    PlainText("${"%,d".format(minOf(save.credits, step.cost))} / ${step.cost}", Type.Small, color = Color.White)
                                    Spacer(Modifier.height(4.dp))
                                    ChunkyButton(
                                        { ask({ roadUnlock() }) { showReward(RewardReveal("Spark Road", it)) } },
                                        Modifier.fillMaxWidth().height(46.dp), ButtonStyle.GREEN, enabled = save.credits >= step.cost, lip = 4.dp,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            GameText("UNLOCK ", Type.Label, outline = 2.dp)
                                            GameIcon(IconKind.CREDIT, Modifier.size(20.dp))
                                            GameText(" ${step.cost}", Type.Label, outline = 2.dp)
                                        }
                                    }
                                }
                                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    GameIcon(IconKind.LOCK, Modifier.size(20.dp))
                                    Spacer(Modifier.width(4.dp))
                                    GameIcon(IconKind.CREDIT, Modifier.size(20.dp))
                                    GameText(" ${step.cost}", Type.Label, outline = 2.dp)
                                }
                            }
                        }
                    }
                }
            }
            PlainText(
                if (next == null) "Every fighter is unlocked. Credits you earn from here on are paid as Power Ups, ten for each."
                else "Credits come from matches, Spark Drops, the Cup Track, the Spark Pass and the shop. They unlock the fighters in this order.",
                Type.Body, Modifier.fillMaxWidth().padding(start = 130.dp, end = 130.dp, bottom = 14.dp), align = TextAlign.Center, maxLines = 2,
            )
        }
    }
}

/** One fighter on the road, on a plate: its picture (a silhouette until it is unlocked), its name, and [footer]. */
@Composable
private fun RoadStop(id: FighterId, width: Dp, modifier: Modifier, unlocked: Boolean, highlight: Boolean = false, footer: @Composable () -> Unit) {
    val def = Balance.fighter(id)
    val top = if (highlight) CREDIT_DEEP else if (unlocked) Palette.PanelLight else Palette.Panel
    Panel(modifier.width(width).fillMaxHeight(0.78f), color = top, colorBottom = lerp(top, Color.Black, 0.55f), cut = 14.dp) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FighterView(def, 0, Modifier.weight(1f).fillMaxWidth(), pedestal = false, rays = highlight, locked = !unlocked)
            GameText(def.name.substringBefore(' ').uppercase(), Type.Heading, outline = 2.5.dp)
            PlainText(def.role, Type.Small, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            footer()
        }
    }
}

/**
 * The Spark Pass: a season of tiers. Playing matches earns pass points, each tier reached has a reward to claim
 * (most of them Credits), and a new season starts everyone again. The server keeps all of it; this shows what
 * the account says.
 */
@Composable
fun PassScreen(save: SaveData, go: (Screen) -> Unit, showReward: (RewardReveal) -> Unit) {
    val ask = LocalServerCall.current
    val ui = LocalUi.current
    val status = LocalServer.current?.status?.collectAsState()?.value
    val pass = status?.account?.takeIf { status.online }?.pass
    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SPARK PASS", { go(Screen.Home) }, save.bolts, save.prisms, credits = save.credits)
            if (pass == null || pass.tiers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlainText("The Spark Pass is kept by the server. It will be here when you're back online.", Type.Body, align = TextAlign.Center)
                }
                return@Column
            }
            PassProgress(pass, Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            val list = rememberLazyListState()
            LaunchedEffect(Unit) {
                val firstClaim = (1..pass.reached).firstOrNull { it !in pass.claimed }
                list.scrollToItem(((firstClaim ?: (pass.reached + 1)) - 2).coerceIn(0, pass.tiers.lastIndex))
            }
            LazyRow(
                Modifier.weight(1f).fillMaxWidth(), list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pass.tiers.size) { i ->
                    val tier = i + 1
                    PassTier(tier, pass.tiers[i], reached = tier <= pass.reached, claimed = tier in pass.claimed, width = if (ui.roomy) 150.dp else 124.dp) {
                        ask({ claimPass(tier) }) { showReward(RewardReveal("Spark Pass · Tier $tier", it)) }
                    }
                }
            }
        }
    }
}

/** Where the player is in the season: the tier they are on, the bar toward the next, and how long the season has left. */
@Composable
private fun PassProgress(pass: PassState, modifier: Modifier) {
    val left = ((pass.endsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    val full = pass.reached >= pass.tiers.size
    Panel(modifier, cut = 14.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlainText("TIER", Type.Small, color = Color.White)
                GameText(pass.reached.toString(), Type.Display, color = Palette.Gold, outline = 4.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                ProgressBar(if (full) 1f else (pass.points % pass.tierPoints).toFloat() / pass.tierPoints, Modifier.fillMaxWidth(), Palette.Gold, 20.dp)
                Spacer(Modifier.height(4.dp))
                PlainText(
                    if (full) "Every tier reached this season." else "${pass.points % pass.tierPoints} / ${pass.tierPoints} to tier ${pass.reached + 1} · a win is 40 points, any match 15",
                    Type.Small, color = Color.White, maxLines = 1,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                PlainText("SEASON ENDS IN", Type.Small)
                GameText(if (left >= 86400) "${left / 86400}d ${(left % 86400) / 3600}h" else "%02d:%02d".format(left / 3600, (left / 60) % 60), Type.Heading, outline = 2.5.dp)
            }
        }
    }
}

@Composable
private fun PassTier(tier: Int, reward: Reward, reached: Boolean, claimed: Boolean, width: Dp, onClaim: () -> Unit) {
    val ready = reached && !claimed
    val top = when { ready -> Palette.Gold; reached -> Palette.PanelLight; else -> Palette.Panel }
    Box(Modifier.width(width).fillMaxHeight()) {
        Panel(Modifier.fillMaxSize().padding(top = 8.dp), color = lerp(top, Color.Black, if (ready) 0.25f else 0f), colorBottom = lerp(top, Color.Black, 0.6f), cut = 14.dp) {
            Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RewardVisual(reward, Modifier.size(if (width > 130.dp) 84.dp else 66.dp))
                }
                GameText(rewardLabel(reward), Type.Label, outline = 2.dp, align = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                when {
                    claimed -> ChunkyButton({}, Modifier.fillMaxWidth().height(46.dp), ButtonStyle.GREY, enabled = false, lip = 4.dp) { GameText("CLAIMED", Type.Label, outline = 2.dp) }
                    ready -> ChunkyButton(onClaim, Modifier.fillMaxWidth().height(46.dp), ButtonStyle.GREEN, lip = 4.dp) { GameText("CLAIM", Type.Heading) }
                    else -> ChunkyButton({}, Modifier.fillMaxWidth().height(46.dp), ButtonStyle.GREY, enabled = false, lip = 4.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) { GameIcon(IconKind.LOCK, Modifier.size(18.dp)); GameText(" LOCKED", Type.Label, outline = 2.dp) }
                    }
                }
            }
        }
        Badge("TIER $tier", Modifier.align(Alignment.TopCenter), color = if (reached) Palette.OrangeDeep else Palette.PanelDark)
    }
}
