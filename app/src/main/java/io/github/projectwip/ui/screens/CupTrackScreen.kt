package io.github.projectwip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.Milestone
import io.github.projectwip.data.Progression
import io.github.projectwip.data.SaveData
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameBackground
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardReveal
import io.github.projectwip.ui.RewardVisual
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rewardLabel

@Composable
fun CupTrackScreen(save: SaveData, repo: GameRepository, go: (Screen) -> Unit, showReward: (RewardReveal) -> Unit) {
    val ui = LocalUi.current
    val nodes = listOf<Milestone?>(null) + CupTrack.milestones // null = START
    val claimable = Progression.claimable(save)
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val firstClaim = nodes.indexOfFirst { it != null && it in claimable }
        val reached = nodes.indexOfLast { (it?.cups ?: 0) <= save.bestCups }
        listState.scrollToItem(((if (firstClaim >= 0) firstClaim else reached) - 1).coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("CUP TRACK", { go(Screen.Home) }, save.bolts, save.prisms) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GameIcon(IconKind.CUP, Modifier.size(46.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        GameText("%,d".format(save.cups), Type.Title, color = Palette.Gold, outline = 3.dp)
                        PlainText("Best: %,d".format(save.bestCups), Type.Small)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                PlainText("Win matches to earn Cups. Rewards unlock at your best Cup count and stay unlocked — claim them any time.", Type.Body, modifier = Modifier.weight(1f))
                if (claimable.size > 1) {
                    ChunkyButton({
                        claimable.forEach { m -> repo.claimMilestone(m) }
                        showReward(RewardReveal("Claimed ${claimable.size} rewards", claimable.last().reward))
                    }, Modifier.size(160.dp, 52.dp), ButtonStyle.GREEN) { GameText("CLAIM ALL", Type.Heading) }
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
            ) {
                itemsIndexed(nodes) { i, m ->
                    val cups = m?.cups ?: 0
                    val prevCups = if (i > 0) nodes[i - 1]?.cups ?: 0 else cups
                    val nextCups = nodes.getOrNull(i + 1)?.cups
                    TrackNode(
                        m, cups, prevCups, nextCups, save, isFirst = i == 0,
                        width = if (ui.roomy) 190.dp else 160.dp,
                    ) {
                        repo.claimMilestone(m!!)?.let { r -> showReward(RewardReveal("Cup Track · $cups", r)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackNode(
    m: Milestone?, cups: Int, prevCups: Int, nextCups: Int?, save: SaveData, isFirst: Boolean,
    width: androidx.compose.ui.unit.Dp, onClaim: () -> Unit,
) {
    val best = save.bestCups
    val reached = best >= cups
    val claimed = m != null && cups in save.claimedMilestones
    val canClaim = m != null && reached && !claimed
    val pulse by rememberInfiniteTransition(label = "claim").animateFloat(1f, 1.07f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")

    Box(Modifier.width(width).fillMaxHeight()) {
        // ---- the road
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.56f
            val h = 22.dp.toPx()
            val cx = size.width / 2
            val leftCups = (prevCups + cups) / 2f
            val rightCups = if (nextCups != null) (cups + nextCups) / 2f else cups.toFloat()
            fun drawHalf(x0: Float, x1: Float, c0: Float, c1: Float) {
                drawRect(Palette.Ink, Offset(x0, y - h / 2 - 3.dp.toPx()), Size(x1 - x0, h + 6.dp.toPx()))
                drawRect(Palette.PanelInset, Offset(x0, y - h / 2), Size(x1 - x0, h))
                val f = if (c1 <= c0) (if (best >= c1) 1f else 0f) else ((best - c0) / (c1 - c0)).coerceIn(0f, 1f)
                if (f > 0f) {
                    drawRect(Brush.verticalGradient(listOf(Palette.Gold, Palette.GoldDeep), y - h / 2, y + h / 2), Offset(x0, y - h / 2), Size((x1 - x0) * f, h))
                    drawRect(Color.White.copy(alpha = 0.3f), Offset(x0, y - h / 2 + 3), Size((x1 - x0) * f, h * 0.25f))
                }
            }
            if (!isFirst) drawHalf(0f, cx, leftCups, cups.toFloat())
            if (nextCups != null) drawHalf(cx, size.width, cups.toFloat(), rightCups)
            // node
            val r = 26.dp.toPx()
            drawCircle(Palette.Ink, r + 3.dp.toPx(), Offset(cx, y))
            drawCircle(if (reached) Palette.Gold else Palette.GreyDeep, r, Offset(cx, y))
            drawCircle(if (reached) Palette.GoldDeep else Palette.GreyLip, r * 0.75f, Offset(cx, y))
            // "you are here" marker inside this node's span
            val inLeft = !isFirst && best >= leftCups && best < cups
            val inRight = nextCups != null && best >= cups && best < rightCups
            if (inLeft || inRight) {
                val x = if (inLeft) (best - leftCups) / (cups - leftCups) * cx else cx + (best - cups) / (rightCups - cups) * cx
                drawRoundRect(Palette.Ink, Offset(x - 3.dp.toPx(), y - h), Size(6.dp.toPx(), h * 2), CornerRadius(3.dp.toPx()))
                drawRoundRect(Color.White, Offset(x - 1.5f.dp.toPx(), y - h + 2), Size(3.dp.toPx(), h * 2 - 4), CornerRadius(2.dp.toPx()))
            }
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // ---- reward card above the road
            Box(Modifier.weight(0.56f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.BottomCenter) {
                if (m == null) {
                    GameText("START", Type.Title, color = Palette.Gold, outline = 3.dp, modifier = Modifier.padding(bottom = 36.dp))
                } else {
                    Panel(
                        Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(bottom = 30.dp)
                            .graphicsLayer { if (canClaim) { scaleX = pulse; scaleY = pulse } },
                        color = if (canClaim) Color(0xFF3E8F5A) else if (claimed) Palette.PanelDark else Palette.Panel,
                        colorBottom = if (canClaim) Color(0xFF1E5A35) else Palette.PanelDark,
                        cut = 14.dp,
                    ) {
                        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                RewardVisual(m.reward, Modifier.fillMaxHeight().fillMaxWidth(0.8f).graphicsLayer { alpha = if (claimed) 0.45f else 1f })
                                if (claimed) GameIcon(IconKind.CHECK, Modifier.size(34.dp).align(Alignment.TopEnd))
                            }
                            GameText(rewardLabel(m.reward), Type.Label, outline = 2.dp, align = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
            // ---- below the road (the road itself is drawn at 56% height)
            Box(Modifier.weight(0.44f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(32.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(IconKind.CUP, Modifier.size(20.dp))
                        GameText(" $cups", Type.Heading, color = if (reached) Palette.Gold else Palette.TextDim, outline = 2.5.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    when {
                        canClaim -> ChunkyButton(onClaim, Modifier.size(130.dp, 50.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, ButtonStyle.GREEN) {
                            GameText("CLAIM", Type.Heading)
                        }
                        claimed -> PlainText("CLAIMED", Type.Label, color = Palette.Positive)
                        m != null -> PlainText("${cups - best} to go", Type.Small)
                    }
                }
            }
        }
    }
}
