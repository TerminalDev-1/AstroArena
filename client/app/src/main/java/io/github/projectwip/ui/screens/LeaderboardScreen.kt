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

/** The Cup ladder: the real players on the game server, ranked by Cups. Offline there is nothing to rank against. */
@Composable
fun LeaderboardScreen(save: SaveData, go: (Screen) -> Unit) {
    val server = io.github.projectwip.ui.LocalServer.current
    /** Null while loading; empty when the server couldn't be asked. */
    var standings by remember { androidx.compose.runtime.mutableStateOf<List<LeaderboardEntry>?>(null) }
    LaunchedEffect(save.cups, save.settings.playerName) {
        val players = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server?.leaderboard() }
        // The server sends them best first.
        standings = players.orEmpty().mapIndexed { i, p -> LeaderboardEntry(i + 1, p.name, p.cups, p.fighter, p.id == server?.playerId, p.glory) }
    }
    val rows = standings
    val me = rows?.firstOrNull { it.isPlayer }
    val list = rememberLazyListState()
    // Open with the player's own row in view, a few places of context above it.
    LaunchedEffect(me?.rank) { me?.let { list.scrollToItem((it.rank - 4).coerceAtLeast(0)) } }

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("LEADERBOARD", { go(Screen.Home) }, null, null) {
                if (me != null && rows != null) Badge("YOU ARE #${me.rank} OF ${rows.size}", color = Palette.OrangeDeep)
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.Center) {
                Panel(Modifier.widthIn(max = 860.dp).fillMaxHeight(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        when {
                            rows == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                GameText("LOADING…", Type.Title, outline = 3.5.dp)
                            }
                            rows.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                GameText("YOU'RE OFFLINE", Type.Title, color = Palette.Gold, outline = 3.5.dp)
                                Spacer(Modifier.height(6.dp))
                                PlainText("The leaderboard is kept by the server. It will be here when you're back online.", Type.Body, color = Color.White)
                            }
                            else -> LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(rows, key = { it.rank }) { LeaderRow(it) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        PlainText("Every player on the server, ranked by Cups.", Type.Small, Modifier.padding(start = 30.dp, bottom = 16.dp), maxLines = 2)
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
        // Glory: the rank a player climbs once every fighter is theirs.
        if (e.glory > 0) {
            Spacer(Modifier.width(10.dp))
            GameIcon(IconKind.GLORY, Modifier.size(26.dp))
            Spacer(Modifier.width(4.dp))
            PlainText(io.github.projectwip.data.Glory.rank(e.glory).title, Type.Label, color = Palette.Gold, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        GameIcon(IconKind.CUP, Modifier.size(32.dp))
        Spacer(Modifier.width(6.dp))
        GameText("%,d".format(e.cups), Type.Heading, color = Palette.Gold, outline = 2.5.dp)
    }
}
