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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.Balance
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.SaveData
import io.github.projectwip.net.TeamLink
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many digits a team's code has. */
private const val CODE_DIGITS = 4

/**
 * Teams: two or three real players in one Boss Mode or Knockout Rush match. One player makes a team and is given
 * a code; the others type it in here. [team] is the team this player is in (kept by the app, so it outlasts this
 * screen and the matches the team plays); [setTeam] changes it.
 */
@Composable
fun TeamScreen(save: SaveData, team: TeamLink?, setTeam: (TeamLink?) -> Unit, go: (Screen) -> Unit) {
    val server = io.github.projectwip.ui.LocalServer.current
    val online = server?.status?.collectAsState()?.value?.online == true
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    val fighter = Balance.fighter(save.selectedFighter)
    val skin = save.progress(save.selectedFighter).skin

    fun open(action: String, mode: GameMode? = null) {
        if (busy || server == null) return
        busy = true
        problem = null
        scope.launch {
            val link = withContext(Dispatchers.IO) {
                server.teamLink()?.takeIf { it.open(server.teamHello(save.selectedFighter, skin, action, mode, save.selectedBoss, code)) }
            }
            busy = false
            if (link == null) problem = "The team lobby is on the game server, and it can't be reached right now."
            else setTeam(link)
        }
    }

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("TEAM", { go(Screen.Home) }, null, null) {}
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                val roster = team?.roster
                when {
                    !online && team == null -> Notice("TEAMS NEED THE SERVER", "A team is real players, each on their own device. Connect to the game server to make or join one.")
                    // Turned away by the lobby, or the line to the team has gone.
                    team != null && (team.error != null || team.ended) -> Notice(
                        if (team.error != null) "NO TEAM" else "THE TEAM IS OVER",
                        team.error ?: "The connection to your team was lost.", "OK",
                    ) { setTeam(null) }
                    team != null && roster == null -> GameText("JOINING…", Type.Title, outline = 3.5.dp)
                    team != null && roster != null -> Row(Modifier.widthIn(max = 980.dp).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        // ---- who is in it
                        Panel(Modifier.weight(1.6f).fillMaxHeight(), cut = 18.dp) {
                            Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                GameText(if (roster.mode == GameMode.BOSS) "BOSS MODE · TOGETHER" else "KNOCKOUT RUSH · 3V3", Type.Heading, color = Palette.Gold, outline = 2.5.dp)
                                PlainText(
                                    if (roster.mode == GameMode.BOSS) "Two or three of you against one boss. It is tougher for every extra player."
                                    else "Your team against three bots. With two of you, a bot makes up the third.",
                                    Type.Small, color = Palette.Text, align = TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    for (i in 0 until 3) {
                                        val member = roster.members.getOrNull(i)
                                        Panel(Modifier.weight(1f).fillMaxHeight(), color = if (i == roster.you) Color(0xFF3E6A2E) else Palette.PanelInset, cut = 12.dp) {
                                            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                                if (member == null) {
                                                    GameText("?", Type.Display, color = Color.White.copy(alpha = 0.4f), outline = 3.dp)
                                                    PlainText("Waiting for a player", Type.Small, color = Palette.TextDim)
                                                } else {
                                                    Box(Modifier.weight(1f).fillMaxWidth()) {
                                                        FighterView(Balance.fighter(member.fighter), member.skin, Modifier.fillMaxSize(), pedestal = false)
                                                    }
                                                    GameText(member.name, Type.Label, color = if (i == roster.you) Palette.Gold else Color.White, outline = 2.dp)
                                                    PlainText("${Balance.fighter(member.fighter).name} · level ${member.level}", Type.Small, color = Palette.Text)
                                                    if (i == 0) Badge("LEADER", color = Palette.GoldDeep)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // ---- the code, and getting going
                        Panel(Modifier.weight(1f).fillMaxHeight(), cut = 18.dp) {
                            Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlainText("TEAM CODE", Type.Label, color = Palette.TextDim)
                                GameText(roster.code.toList().joinToString(" "), Type.Display.copy(fontSize = Type.Display.fontSize * 1.5f), color = Palette.Gold, outline = 5.dp)
                                PlainText("Friends on this server tap TEAM, then type this code to join.", Type.Small, color = Palette.Text, align = TextAlign.Center)
                                Spacer(Modifier.weight(1f))
                                team.note?.let { PlainText(it, Type.Small, color = Palette.Red, align = TextAlign.Center) }
                                if (roster.leading) {
                                    val ready = roster.members.size >= 2
                                    if (!ready) PlainText("A team needs at least two players.", Type.Small, color = Palette.Text, align = TextAlign.Center)
                                    ChunkyButton({ if (ready) team.go() }, Modifier.fillMaxWidth().height(72.dp), if (ready) ButtonStyle.ORANGE else ButtonStyle.GLASS) {
                                        GameText("PLAY", Type.Title, outline = 3.5.dp)
                                    }
                                } else {
                                    PlainText("Waiting for ${roster.members.first().name} to start the match…", Type.Body, color = Color.White, align = TextAlign.Center)
                                }
                                ChunkyButton({ team.close(); setTeam(null) }, Modifier.fillMaxWidth().height(50.dp), ButtonStyle.RED, lip = 4.dp, sound = io.github.projectwip.audio.Sound.UI_BACK) {
                                    GameText("LEAVE TEAM", Type.Heading)
                                }
                            }
                        }
                    }
                    else -> Row(Modifier.widthIn(max = 980.dp).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        // ---- make one
                        Panel(Modifier.weight(1f).fillMaxHeight(), cut = 18.dp) {
                            Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                GameText("MAKE A TEAM", Type.Title, color = Palette.Gold, outline = 3.dp)
                                PlainText("You get a code for up to two friends to join with, and you start the matches.", Type.Small, color = Palette.Text, align = TextAlign.Center)
                                Box(Modifier.weight(1f).fillMaxWidth()) { FighterView(fighter, skin, Modifier.fillMaxSize(), pedestal = false) }
                                PlainText("You bring ${fighter.name}. Pick another fighter before you make or join a team.", Type.Small, color = Palette.Text, align = TextAlign.Center)
                                ChunkyButton({ open("create", GameMode.BOSS) }, Modifier.fillMaxWidth().height(58.dp), ButtonStyle.PURPLE, lip = 5.dp) { GameText("BOSS MODE", Type.Heading) }
                                ChunkyButton({ open("create", GameMode.KNOCKOUT_RUSH) }, Modifier.fillMaxWidth().height(58.dp), ButtonStyle.ORANGE, lip = 5.dp) { GameText("KNOCKOUT RUSH 3V3", Type.Heading) }
                            }
                        }
                        // ---- join one
                        Panel(Modifier.weight(1f).fillMaxHeight(), cut = 18.dp) {
                            Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                GameText("JOIN A TEAM", Type.Title, color = Palette.Cyan, outline = 3.dp)
                                GameText((0 until CODE_DIGITS).joinToString(" ") { code.getOrNull(it)?.toString() ?: "_" }, Type.Display, outline = 4.dp)
                                problem?.let { PlainText(it, Type.Small, color = Palette.Red, align = TextAlign.Center) }
                                // A keypad of our own: the code is four digits, and no keyboard has to cover the screen.
                                for (keys in listOf("123", "456", "789", "<0>")) {
                                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (key in keys) {
                                            val join = key == '>'
                                            ChunkyButton({
                                                when (key) {
                                                    '<' -> code = code.dropLast(1)
                                                    '>' -> if (code.length == CODE_DIGITS) open("join")
                                                    else -> if (code.length < CODE_DIGITS) code += key
                                                }
                                            }, Modifier.weight(1f).fillMaxHeight(), if (join) (if (code.length == CODE_DIGITS) ButtonStyle.GREEN else ButtonStyle.GLASS) else ButtonStyle.GLASS, lip = 4.dp) {
                                                GameText(when (key) { '<' -> "⌫"; '>' -> if (busy) "…" else "JOIN"; else -> key.toString() }, if (join) Type.Heading else Type.Title, outline = 2.5.dp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, text: String, button: String? = null, onClick: () -> Unit = {}) {
    Panel(Modifier.width(560.dp), cut = 18.dp) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GameText(title, Type.Title, color = Palette.Gold, outline = 3.5.dp)
            PlainText(text, Type.Body, color = Color.White, align = TextAlign.Center)
            if (button != null) ChunkyButton(onClick, Modifier.size(200.dp, 54.dp), ButtonStyle.PURPLE, lip = 4.dp) { GameText(button, Type.Heading) }
        }
    }
}
