package io.github.projectwip.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.Balance
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.RoadStep
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.SparkRoad
import io.github.projectwip.render3d.LobbyShot
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LobbyShotEffect
import io.github.projectwip.ui.LocalLobby
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardReveal
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import kotlin.math.roundToInt

private val CREDIT = Color(0xFF3FE08A)

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
 * The Spark Road, in 3D: a real road in the lobby (see `LobbyShot.ROAD`) with every fighter standing along it in the
 * order they are unlocked. The stretch already travelled is lit, the stretch to the next fighter fills as the
 * Credits come in, and the moment it is full that fighter is unlocked. Drag sideways (or use the arrows) to travel
 * along it; the card at the bottom is about whoever the camera is in front of.
 */
@Composable
fun RoadScreen(save: SaveData, go: (Screen) -> Unit, @Suppress("UNUSED_PARAMETER") showReward: (RewardReveal) -> Unit) {
    val lobby = LocalLobby.current
    val next = SparkRoad.next(save)
    val stops = remember { listOf<RoadStep?>(null) + SparkRoad.steps } // null = the starting fighter
    val nextIndex = if (next == null) -1 else stops.indexOf(next)
    var scroll by remember { mutableFloatStateOf((if (nextIndex >= 0) nextIndex else stops.lastIndex).toFloat()) }
    val unlockedMask = stops.foldIndexed(0) { i, mask, step -> if (step == null || save.progress(step.fighter).unlocked) mask or (1 shl i) else mask }

    LobbyShotEffect(LobbyShot.ROAD)
    SideEffect {
        lobby.roadUnlocked = unlockedMask
        lobby.roadNext = nextIndex
        lobby.roadFill = if (next == null) 1f else save.credits.toFloat() / next.cost
        lobby.roadScroll = scroll
    }

    val focus = scroll.roundToInt().coerceIn(0, stops.lastIndex)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(onDragEnd = { scroll = scroll.roundToInt().toFloat() }) { _, dx ->
                // A third of the screen is one stop.
                scroll = (scroll - dx / (size.width / 3f)).coerceIn(0f, stops.lastIndex.toFloat())
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SPARK ROAD", { go(Screen.Home) }, null, null)
            RoadSummary(save, next, Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                ChunkyButton({ scroll = (focus - 1).coerceAtLeast(0).toFloat() }, Modifier.size(64.dp, 64.dp), ButtonStyle.PURPLE, enabled = focus > 0, lip = 4.dp) { GameText("‹", Type.Display, outline = 3.dp) }
                StopCard(save, stops[focus], isNext = focus == nextIndex, Modifier.widthIn(max = 560.dp).weight(1f, fill = false)) {
                    go(Screen.Fighters(stops[focus]?.fighter ?: FighterId.BYTE))
                }
                ChunkyButton({ scroll = (focus + 1).coerceAtMost(stops.lastIndex).toFloat() }, Modifier.size(64.dp, 64.dp), ButtonStyle.PURPLE, enabled = focus < stops.lastIndex, lip = 4.dp) { GameText("›", Type.Display, outline = 3.dp) }
            }
        }
    }
}

/** Above the road: which fighter the Credits are filling and how far along it is. */
@Composable
private fun RoadSummary(save: SaveData, next: RoadStep?, modifier: Modifier) {
    Panel(modifier, cut = 14.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) {
                GameIcon(IconKind.CREDIT, Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GameText("NEXT: ${Balance.fighter(next.fighter).name.substringBefore(' ').uppercase()}", Type.Label, color = Palette.Gold, outline = 2.dp)
                    ProgressBar(save.credits.toFloat() / next.cost, Modifier.fillMaxWidth(), height = 18.dp)
                    PlainText("Every Credit you earn goes straight onto the road. Glitch Drops give the most. A full bar unlocks the fighter.", Type.Small, color = Color.White, maxLines = 1)
                }
                Spacer(Modifier.width(14.dp))
                GameText("${"%,d".format(minOf(save.credits, next.cost))} / ${"%,d".format(next.cost)}", Type.Title, outline = 3.dp)
            } else {
                GameIcon(IconKind.CHECK, Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GameText("THE ROAD IS FINISHED", Type.Label, color = Palette.Gold, outline = 2.dp)
                    ProgressBar(1f, Modifier.fillMaxWidth(), Palette.Gold, 18.dp)
                    PlainText("Every fighter is unlocked. Credits are paid out as Upgrade Credits now.", Type.Small, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

/** Who the camera is in front of: their name and rarity, and where they stand on the road. */
@Composable
private fun StopCard(save: SaveData, step: RoadStep?, isNext: Boolean, modifier: Modifier, onView: () -> Unit) {
    val def = Balance.fighter(step?.fighter ?: FighterId.BYTE)
    val rarity = Color(def.rarity.color)
    val unlocked = step == null || save.progress(step.fighter).unlocked
    Panel(modifier, cut = 16.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GameText(def.name.substringBefore(' ').uppercase(), Type.Title, outline = 3.dp)
                    Spacer(Modifier.width(10.dp))
                    Badge(def.rarity.label.uppercase(), color = lerp(rarity, Color.Black, 0.35f))
                }
                PlainText(def.title + " · " + def.role, Type.Small, color = Color.White, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                when {
                    step == null -> PlainText("Where everyone starts.", Type.Body, color = Palette.Text, maxLines = 1)
                    unlocked -> PlainText("Unlocked.", Type.Body, color = Palette.Positive, maxLines = 1)
                    isNext -> {
                        ProgressBar(save.credits.toFloat() / step.cost, Modifier.fillMaxWidth(), height = 16.dp)
                        PlainText("${"%,d".format(minOf(save.credits, step.cost))} / ${"%,d".format(step.cost)} Credits · unlocks the moment it is full", Type.Small, color = Color.White, maxLines = 1)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(IconKind.LOCK, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        GameIcon(IconKind.CREDIT, Modifier.size(22.dp))
                        GameText(" ${"%,d".format(step.cost)}", Type.Label, outline = 2.dp)
                        PlainText("  ·  further along the road", Type.Small, color = Palette.Text, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ChunkyButton(onView, Modifier.size(120.dp, 54.dp), if (isNext) ButtonStyle.GREEN else ButtonStyle.CYAN, lip = 4.dp) { GameText("VIEW", Type.Heading) }
        }
    }
}
