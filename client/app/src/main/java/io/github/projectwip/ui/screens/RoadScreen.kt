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
import androidx.compose.ui.draw.drawBehind
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
 * The Spark Road. Credits are not held anywhere: whatever is earned goes straight into the road, toward the next
 * fighter along it. The road is drawn as one: every fighter stands on it in the order they are unlocked, the
 * stretch already travelled is lit, and the stretch to the next fighter fills as the Credits come in. When it
 * is full the fighter is claimed. With every fighter unlocked the road is finished and Credits are earned as
 * Glory instead.
 */
@Composable
fun RoadScreen(save: SaveData, go: (Screen) -> Unit, showReward: (RewardReveal) -> Unit) {
    val ask = LocalServerCall.current
    val ui = LocalUi.current
    val next = SparkRoad.next(save)
    val stopW = if (ui.roomy) 190.dp else 158.dp
    val stops = listOf<io.github.projectwip.data.RoadStep?>(null) + SparkRoad.steps // null = the starting fighter
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((stops.indexOfFirst { it != null && it == next } - 1).coerceAtLeast(0)) }
    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SPARK ROAD", { go(Screen.Home) }, null, null)
            RoadSummary(save, next, Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            LazyRow(Modifier.weight(1f).fillMaxWidth(), list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                items(stops.size) { i ->
                    val step = stops[i]
                    val unlocked = step == null || save.progress(step.fighter).unlocked
                    val isNext = step != null && step == next
                    // How much of this stop's stretch of road is lit: all of it once the fighter is unlocked; for the
                    // next one, the half that leads up to it fills with the Credits; none beyond.
                    val lit = when { unlocked -> 1f; isNext -> 0.5f * (save.credits.toFloat() / step!!.cost).coerceIn(0f, 1f); else -> 0f }
                    RoadStop(step?.fighter ?: FighterId.JUNO, stopW, lit, unlocked, isNext, first = i == 0, last = i == stops.lastIndex) {
                        when {
                            step == null -> Badge("START", color = Palette.CyanDeep)
                            unlocked -> Badge("UNLOCKED", color = Palette.GreenDeep)
                            isNext -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PlainText("${"%,d".format(minOf(save.credits, step.cost))} / ${"%,d".format(step.cost)}", Type.Label, color = Color.White)
                                Spacer(Modifier.height(4.dp))
                                ChunkyButton(
                                    { ask({ roadUnlock() }) { showReward(RewardReveal("Spark Road", it)) } },
                                    Modifier.width(150.dp).height(48.dp), ButtonStyle.GREEN, enabled = save.credits >= step.cost, lip = 4.dp,
                                ) { GameText(if (save.credits >= step.cost) "CLAIM!" else "NEXT UP", Type.Label, outline = 2.dp) }
                            }
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                GameIcon(IconKind.LOCK, Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                                GameIcon(IconKind.CREDIT, Modifier.size(22.dp))
                                GameText(" ${"%,d".format(step.cost)}", Type.Label, outline = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Above the road: which fighter the Credits are filling and how far along it is; or, the road finished, the Glory rank. */
@Composable
private fun RoadSummary(save: SaveData, next: io.github.projectwip.data.RoadStep?, modifier: Modifier) {
    Panel(modifier, cut = 14.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) {
                GameIcon(IconKind.CREDIT, Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GameText("NEXT: ${Balance.fighter(next.fighter).name.substringBefore(' ').uppercase()}", Type.Label, color = Palette.Gold, outline = 2.dp)
                    ProgressBar(save.credits.toFloat() / next.cost, Modifier.fillMaxWidth(), height = 18.dp)
                    PlainText("Every Credit you earn goes straight onto the road: matches, Spark Drops, the Cup Track, the Spark Pass, the shop.", Type.Small, color = Color.White, maxLines = 1)
                }
                Spacer(Modifier.width(14.dp))
                GameText("${"%,d".format(minOf(save.credits, next.cost))} / ${"%,d".format(next.cost)}", Type.Title, outline = 3.dp)
            } else {
                val rank = io.github.projectwip.data.Glory.rank(save.glory)
                GameIcon(IconKind.GLORY, Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GameText("GLORY · ${rank.title.uppercase()}", Type.Label, color = Palette.Gold, outline = 2.dp)
                    ProgressBar(rank.into.toFloat() / rank.size, Modifier.fillMaxWidth(), Palette.Gold, 18.dp)
                    PlainText("Every fighter is unlocked. Credits are earned as Glory now: a rank for your name, which buys nothing.", Type.Small, color = Color.White, maxLines = 1)
                }
                Spacer(Modifier.width(14.dp))
                GameText("%,d".format(save.glory), Type.Title, outline = 3.dp)
            }
        }
    }
}

/**
 * One stop on the road. The fighter (a silhouette until unlocked) stands on a marker set into the road; its
 * name, rarity and [footer] are underneath. The road runs straight through behind it, lit for [lit] of the way.
 */
@Composable
private fun RoadStop(id: FighterId, width: Dp, lit: Float, unlocked: Boolean, isNext: Boolean, first: Boolean, last: Boolean, footer: @Composable () -> Unit) {
    val def = Balance.fighter(id)
    val rarity = Color(def.rarity.color)
    Column(
        Modifier.width(width).fillMaxHeight().drawBehind {
            // The road: a band through the stop at marker height, starting at the first marker and ending at the last.
            val y = size.height * 0.56f
            val from = if (first) size.width / 2 else 0f
            val to = if (last) size.width / 2 else size.width
            drawLine(Palette.Ink, Offset(from, y), Offset(to, y), 34.dp.toPx())
            drawLine(Color(0xFF4A3AA8), Offset(from, y), Offset(to, y), 25.dp.toPx())
            val litTo = (size.width * lit).coerceIn(from, to)
            if (litTo > from) drawLine(CREDIT, Offset(from, y), Offset(litTo, y), 25.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.55f), Offset(from, y), Offset(to, y), 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 12.dp.toPx())))
            // The marker the fighter stands on: in its rarity's colour once reached, dark before.
            val c = Offset(size.width / 2, y)
            drawOval(Palette.Ink, Offset(c.x - 50.dp.toPx(), c.y - 22.dp.toPx()), androidx.compose.ui.geometry.Size(100.dp.toPx(), 44.dp.toPx()))
            drawOval(if (unlocked || isNext) rarity else Palette.PanelInset, Offset(c.x - 44.dp.toPx(), c.y - 17.dp.toPx()), androidx.compose.ui.geometry.Size(88.dp.toPx(), 34.dp.toPx()))
            drawOval(Color.White.copy(alpha = if (isNext) 0.45f else 0.2f), Offset(c.x - 30.dp.toPx(), c.y - 10.dp.toPx()), androidx.compose.ui.geometry.Size(60.dp.toPx(), 16.dp.toPx()))
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The fighter fills the space above the road, its feet on the marker.
        Box(Modifier.weight(0.56f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            FighterView(def, 0, Modifier.fillMaxHeight(if (isNext) 1f else 0.82f).fillMaxWidth(), pedestal = false, rays = isNext, locked = !unlocked)
        }
        Column(Modifier.weight(0.44f).fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GameText(def.name.substringBefore(' ').uppercase(), if (isNext) Type.Title else Type.Heading, outline = 3.dp)
            Badge(def.rarity.label.uppercase(), color = lerp(rarity, Color.Black, 0.35f))
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
            ScreenHeader("SPARK PASS", { go(Screen.Home) }, save.bolts, save.prisms)
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
