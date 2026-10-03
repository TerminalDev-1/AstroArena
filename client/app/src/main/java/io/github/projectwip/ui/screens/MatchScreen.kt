package io.github.projectwip.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.projectwip.MainActivity
import io.github.projectwip.audio.Sfx
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.MatchOutcome
import io.github.projectwip.data.MatchReport
import io.github.projectwip.data.Settings
import io.github.projectwip.match.MatchView
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.MatchConfig
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type

data class PlayerLine(
    val name: String, val fighter: FighterId, val skin: Int, val team: Int,
    val kos: Int, val deaths: Int, val damage: Int, val isPlayer: Boolean, val isMvp: Boolean, val isBot: Boolean,
    /** Free-for-all finishing place; 0 = still fighting when your match ended. */
    val placement: Int = 0,
)

data class MatchSummary(val report: MatchReport, val players: List<PlayerLine>, val playerTeam: Int, val serverMatchId: Long = 0)

fun summarize(match: Match, report: MatchReport): MatchSummary {
    // Free-for-all: the star goes to the last fighter standing; team modes use the contribution score.
    val mvp = if (match.freeForAll) match.world.fighters.firstOrNull { it.placement == 1 } else match.world.mvp()
    return MatchSummary(
        report,
        match.world.fighters.map {
            PlayerLine(it.name, it.def.id, it.skin, it.team, it.kos, it.deaths, it.damageDealt, it === match.player, it === mvp, it.isBot,
                placement = if (it === match.player) match.placement else it.placement)
        },
        match.player.team,
        match.config.serverMatchId,
    )
}

/**
 * Starts a match. The game server is asked to set it up first (its seed, which fixes the bots, their names
 * and how tough they are); if there is no server, or it doesn't answer quickly, the match is set up on the
 * device instead and counts as an offline match.
 */
@Composable
fun MatchScreen(
    config: MatchConfig, settings: Settings, sfx: Sfx, matchesPlayed: Int,
    server: io.github.projectwip.net.GameServer?, onFinish: (MatchSummary) -> Unit,
) {
    var planned by remember { mutableStateOf<MatchConfig?>(null) }
    LaunchedEffect(Unit) {
        val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            server?.planMatch(config.mode, config.playerFighter, config.playerLevel, config.difficulty)
        }
        planned = if (plan == null) config else config.copy(seed = plan.seed, botNames = plan.botNames, serverMatchId = plan.matchId, difficulty = plan.difficulty ?: config.difficulty)
    }
    val ready = planned
    if (ready == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1C143A)), contentAlignment = Alignment.Center) {
            GameText("FINDING A MATCH…", Type.Title, outline = 3.5.dp)
        }
    } else MatchBody(ready, settings, sfx, matchesPlayed, onFinish)
}

@Composable
private fun MatchBody(config: MatchConfig, settings: Settings, sfx: Sfx, matchesPlayed: Int, onFinish: (MatchSummary) -> Unit) {
    val match = remember { Match(config) }
    var paused by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<MatchView?>(null) }
    var done by remember { mutableStateOf(false) }
    /** The line-up is showing; the match waits behind it. */
    var intro by remember { mutableStateOf(!match.practice) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { (context as? MainActivity)?.applyRefreshRate(settings.highFrameRate) }

    fun finish(report: MatchReport) {
        if (done) return
        done = true
        view?.paused = true
        onFinish(summarize(match, report))
    }

    BackHandler(enabled = !done) {
        if (intro) return@BackHandler
        paused = !paused
        if (paused) view?.paused = true else view?.resumeGame()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1C143A))) {
        AndroidView(
            factory = { ctx ->
                MatchView(ctx, match, settings, sfx, matchesPlayed,
                    onPauseRequested = { if (!done && !intro) { paused = true; view?.paused = true } },
                    onFinished = { report -> finish(report) },
                ).also { view = it; it.paused = intro }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (intro) MatchIntro(match) { if (intro) { intro = false; view?.resumeGame() } }
        if (paused && !done) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Panel(cut = 20.dp) {
                    Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GameText("PAUSED", Type.Display, outline = 4.dp)
                        PlainText(if (match.practice) "Nothing is at stake in the Training Area. Leave whenever you like." else "Bots wait for you. Leaving now counts as a defeat.", Type.Body, align = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        ChunkyButton({ paused = false; view?.resumeGame() }, Modifier.size(260.dp, 64.dp), ButtonStyle.GREEN) { GameText("RESUME", Type.Title) }
                        ChunkyButton({ finish(match.report().copy(outcome = MatchOutcome.DEFEAT)) }, Modifier.size(260.dp, 54.dp), ButtonStyle.RED) {
                            GameText("LEAVE MATCH", Type.Heading)
                        }
                    }
                }
            }
        }
    }
}
