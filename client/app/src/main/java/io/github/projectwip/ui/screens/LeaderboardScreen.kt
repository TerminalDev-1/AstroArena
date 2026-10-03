package io.github.projectwip.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.Balance
import io.github.projectwip.data.Leaderboard
import io.github.projectwip.data.LeaderboardEntry
import io.github.projectwip.data.SaveData
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.plateShape

/** The Cup ladder: where the player stands among the (for now simulated) field. */
@Composable
fun LeaderboardScreen(save: SaveData, today: Long, go: (Screen) -> Unit) {
    // Real players come from the game server when it is reachable; the rest of the ladder is simulated.
    val server = io.github.projectwip.ui.LocalServer.current
    var real by remember { androidx.compose.runtime.mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val players = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server?.leaderboard() } ?: return@LaunchedEffect
        real = players.filter { it.id != server?.playerId }.map { LeaderboardEntry(0, it.name, it.cups, it.fighter, false) }
    }
    val standings = remember(save.cups, save.settings.playerName, save.selectedFighter, today, real) {
        Leaderboard.standings(save.settings.playerName, save.cups, save.selectedFighter, today, real)
    }
    val me = standings.first { it.isPlayer }
    val list = rememberLazyListState()
    // Open with the player's own row in view, a few places of context above it.
    LaunchedEffect(me.rank) { list.scrollToItem((me.rank - 4).coerceAtLeast(0)) }

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("LEADERBOARD", { go(Screen.Home) }, null, null) {
                Badge("YOU ARE #${me.rank} OF ${standings.size}", color = Palette.OrangeDeep)
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.Center) {
                Panel(Modifier.widthIn(max = 860.dp).fillMaxHeight(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(standings, key = { it.rank }) { LeaderRow(it) }
                        }
                        Spacer(Modifier.height(6.dp))
                        PlainText("Ranked by Cups. Players marked ONLINE are real players on your server; the other names are simulated rivals whose Cups drift from day to day.",
                            Type.Small, Modifier.padding(start = 30.dp), maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderRow(e: LeaderboardEntry) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).drawBehind {
            val p = Path().apply { addOutline(plateShape(9.dp, 3.dp).createOutline(size, layoutDirection, this@drawBehind)) }
            drawPath(p, if (e.isPlayer) Color(0xFF3E6A2E) else Palette.PanelInset)
            drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
        }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val medal = when (e.rank) { 1 -> Palette.Gold; 2 -> Color(0xFFD8E2F0); 3 -> Color(0xFFE0935A); else -> Color.White }
        GameText("#${e.rank}", Type.Heading, color = medal, outline = 2.5.dp, modifier = Modifier.width(64.dp))
        FighterView(Balance.fighter(e.fighter), 0, Modifier.size(44.dp), pedestal = false)
        Spacer(Modifier.width(10.dp))
        GameText(e.name, Type.Heading, color = if (e.isPlayer) Palette.Gold else Color.White, outline = 2.5.dp)
        if (e.isPlayer) { Spacer(Modifier.width(8.dp)); Badge("YOU", color = Palette.GreenDeep) }
        if (e.online) { Spacer(Modifier.width(8.dp)); Badge("ONLINE", color = Palette.CyanDeep) }
        Spacer(Modifier.weight(1f))
        GameIcon(IconKind.CUP, Modifier.size(32.dp))
        Spacer(Modifier.width(6.dp))
        GameText("%,d".format(e.cups), Type.Heading, color = Palette.Gold, outline = 2.5.dp)
    }
}
