package io.github.projectwip.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    /** Set when this line is a Boss Mode boss rather than a fighter. */
    val boss: io.github.projectwip.data.BossKind? = null,
)

data class MatchSummary(
    val report: MatchReport, val players: List<PlayerLine>, val playerTeam: Int, val serverMatchId: Long = 0,
    /** What the player did, tick by tick: the server replays the match from this. */
    val inputs: ByteArray? = null,
) {
    /** This summary with the server's findings in place of the device's own. */
    fun judged(j: io.github.projectwip.data.JudgedResult): MatchSummary = copy(
        report = j.over(report),
        players = players.map { if (it.isPlayer) it.copy(kos = j.kos, deaths = j.deaths, damage = j.damage, placement = j.placement) else it },
    )
}

fun summarize(match: Match, report: MatchReport): MatchSummary {
    // Free-for-all: the star goes to the last fighter standing; team modes use the contribution score.
    val mvp = if (match.freeForAll) match.world.fighters.firstOrNull { it.placement == 1 } else match.world.mvp()
    return MatchSummary(
        report,
        match.world.fighters.map {
            PlayerLine(it.name, it.def.id, it.skin, it.team, it.kos, it.deaths, it.damageDealt, it === match.player, it === mvp, it.isBot,
                placement = if (it === match.player) report.placement else it.placement, boss = it.def.boss)
        },
        match.player.team,
        match.config.serverMatchId,
        match.inputs.toBytes(),
    )
}

/**
 * Starts a match. The game server is asked to set it up first (its seed, which fixes the bots, their names
 * and how tough they are); if there is no server, or it doesn't answer quickly, the match is set up on the
 * device instead and counts as an offline match. While that happens, and for a few seconds after, the
 * matchmaking screen shows the line-up filling in; [onCancel] backs out of it.
 */
@Composable
fun MatchScreen(
    config: MatchConfig, settings: Settings, sfx: Sfx, matchesPlayed: Int,
    server: io.github.projectwip.net.GameServer?, onCancel: () -> Unit, onFinish: (MatchSummary) -> Unit,
) {
    // Decided once, as the match is asked for: online it is against a real player, offline against a bot for practice.
    // (If the connection is still coming up, it gets a few seconds to, so a 1v1 asked for at start-up isn't offline.)
    var duelOnline by remember {
        mutableStateOf(if (config.mode != io.github.projectwip.data.GameMode.DUEL || server == null) false else if (server.status.value.online) true else null)
    }
    LaunchedEffect(Unit) {
        if (duelOnline != null) return@LaunchedEffect
        repeat(60) {
            if (server?.status?.value?.online == true) { duelOnline = true; return@LaunchedEffect }
            kotlinx.coroutines.delay(100)
        }
        duelOnline = false
    }
    if (duelOnline == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1C143A)), contentAlignment = Alignment.Center) { GameText("CONNECTING…", Type.Title, outline = 3.5.dp) }
        return
    }
    if (duelOnline == true && server != null) {
        DuelMatch(config, settings, sfx, matchesPlayed, server, onCancel, onFinish)
        return
    }
    var match by remember { mutableStateOf<Match?>(null) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            server?.planMatch(config.mode, config.playerFighter, config.playerLevel, config.difficulty, config.boss)
        }
        val planned = if (plan == null) config
            else config.copy(
                seed = plan.seed, botNames = plan.botNames, serverMatchId = plan.matchId,
                difficulty = plan.difficulty ?: config.difficulty, playerLevel = plan.level ?: config.playerLevel,
            )
        match = Match(planned)
    }
    val ready = match
    // Boss Mode and the Training Area have nobody to find: they go straight in.
    val matchmade = config.mode == io.github.projectwip.data.GameMode.LAST_SPARK || config.mode == io.github.projectwip.data.GameMode.KNOCKOUT_RUSH
    if (ready != null && (started || !matchmade)) MatchBody(ready, settings, sfx, matchesPlayed, onFinish)
    else if (matchmade) Matchmaking(config, ready, onCancel) { started = true }
    else Box(Modifier.fillMaxSize().background(Color(0xFF1C143A)), contentAlignment = Alignment.Center) {
        GameText("LOADING THE ARENA…", Type.Title, outline = 3.5.dp)
    }
}

/**
 * A 1v1 against a real player: joins the server's lobby, waits there for someone else to join, then plays the
 * match in step with their device. Leaving (or cancelling the wait) hangs up, which hands the other player the win.
 */
@Composable
private fun DuelMatch(
    config: MatchConfig, settings: Settings, sfx: Sfx, matchesPlayed: Int,
    server: io.github.projectwip.net.GameServer, onCancel: () -> Unit, onFinish: (MatchSummary) -> Unit,
) {
    val link = remember { server.duelLink() }
    var match by remember { mutableStateOf<Match?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val time by io.github.projectwip.ui.rememberAnimTime()
    LaunchedEffect(Unit) {
        if (link == null) { problem = "The 1v1 lobby is on the game server, and it can't be reached right now."; return@LaunchedEffect }
        val start = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { link.find(server.duelHello(config.playerFighter, config.playerSkin)) }
        if (start == null) problem = link.error ?: "Couldn't join the 1v1 lobby. Is the server's 1v1 port (its port + 1) open on the network?"
        else match = Match(config.copy(seed = start.seed, playerLevel = start.level, duel = start.setup, humanPlayer = true))
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { link?.close() } }
    val ready = match
    if (ready != null) { MatchBody(ready, settings, sfx, matchesPlayed, onFinish, link); return }
    BackHandler(onBack = onCancel)
    val me = io.github.projectwip.data.Balance.fighter(config.playerFighter)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        io.github.projectwip.ui.GameBackground(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlainText("1V1 · " + config.mode.tagline, Type.Label, color = io.github.projectwip.ui.Palette.TextDim)
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                io.github.projectwip.ui.FighterRays(Modifier.fillMaxSize(), Color(me.skins[config.playerSkin.coerceIn(0, me.skins.lastIndex)].secondary))
                io.github.projectwip.ui.FighterView(me, config.playerSkin, Modifier.fillMaxSize(), pedestal = false)
            }
            val p = problem
            if (p == null) {
                GameText("WAITING FOR AN OPPONENT" + ".".repeat(1 + (time * 2.5f).toInt() % 3), Type.Title, outline = 3.5.dp, modifier = Modifier.width(520.dp))
                PlainText("The match starts as soon as another player on this server picks 1v1. It waits for a real player, however long that takes.", Type.Body, color = Color.White, align = TextAlign.Center, modifier = Modifier.width(560.dp))
                // (Read every frame, as the dots animate: the lobby may say why nobody is being found.)
                link?.note?.let { PlainText(it, Type.Body, color = io.github.projectwip.ui.Palette.Red, align = TextAlign.Center, modifier = Modifier.width(560.dp)) }
                PlainText("This is a test mode: nothing is earned or lost in it yet.", Type.Small, color = io.github.projectwip.ui.Palette.Gold)
            } else {
                GameText("NO 1V1 RIGHT NOW", Type.Title, color = io.github.projectwip.ui.Palette.Gold, outline = 3.5.dp)
                PlainText(p, Type.Body, color = Color.White, align = TextAlign.Center, modifier = Modifier.width(520.dp))
            }
            ChunkyButton(onCancel, Modifier.size(200.dp, 52.dp), ButtonStyle.RED, lip = 4.dp, sound = io.github.projectwip.audio.Sound.UI_BACK) { GameText(if (p == null) "CANCEL" else "BACK", Type.Heading) }
        }
    }
}

/**
 * The wait before a match: your fighter, the mode, and the line-up filling in one opponent at a time until the
 * match is found. The opponents are bots, and the screen says so. [match] is null until the match is set up.
 */
@Composable
private fun Matchmaking(config: MatchConfig, match: Match?, onCancel: () -> Unit, onFound: () -> Unit) {
    val sfx = io.github.projectwip.ui.LocalSfx.current
    val time by io.github.projectwip.ui.rememberAnimTime()
    val total = config.mode.players
    /** How many of the line-up are in, counting you. */
    var found by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var done by remember { mutableStateOf(false) }
    val tip = remember { TIPS.random() }
    BackHandler(enabled = !done, onBack = onCancel)
    LaunchedEffect(match) {
        if (match == null) return@LaunchedEffect
        val pace = kotlin.random.Random(match.config.seed)
        kotlinx.coroutines.delay(500)
        while (found < total) {
            kotlinx.coroutines.delay(180L + pace.nextInt(320))
            found++
            sfx?.play(io.github.projectwip.audio.Sound.POP, 0.6f, 0.8f + 0.5f * found / total)
        }
        kotlinx.coroutines.delay(250)
        done = true
        sfx?.play(io.github.projectwip.audio.Sound.UI_OPEN)
        sfx?.buzz(40, 200)
        kotlinx.coroutines.delay(750)
        onFound()
    }
    val me = io.github.projectwip.data.Balance.fighter(config.playerFighter)
    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.GameBackground(Modifier.fillMaxSize())
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            // ---- you
            Column(Modifier.weight(0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                    io.github.projectwip.ui.FighterRays(Modifier.fillMaxSize(), Color(me.skins[config.playerSkin.coerceIn(0, me.skins.lastIndex)].secondary))
                    io.github.projectwip.ui.FighterView(me, config.playerSkin, Modifier.fillMaxSize(), pedestal = false)
                }
                GameText(config.playerName, Type.Title, color = io.github.projectwip.ui.Palette.Gold, outline = 3.5.dp)
                PlainText("${me.name} · level ${config.playerLevel}", Type.Body, color = Color.White)
            }
            // ---- the search
            Column(Modifier.weight(1.6f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlainText(config.mode.title.uppercase() + " · " + config.mode.tagline, Type.Label, color = io.github.projectwip.ui.Palette.TextDim)
                GameText(
                    if (done) "MATCH FOUND!" else "FINDING A MATCH" + ".".repeat(1 + (time * 2.5f).toInt() % 3),
                    Type.Display, color = if (done) io.github.projectwip.ui.Palette.Green else Color.White, outline = 4.dp,
                    modifier = Modifier.width(440.dp),
                )
                // The line-up: your slot is first, the rest fill in as opponents are found.
                val others = match?.world?.fighters?.filter { it !== match.player }.orEmpty()
                val perRow = if (total > 6) 5 else 3
                for (row in (0 until total).chunked(perRow)) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        for (i in row) {
                            val fighter = if (i == 0) match?.player else others.getOrNull(i - 1)
                            Slot(if (i < found) fighter else null, i == 0, time + i, Modifier.weight(1f, fill = false).width(112.dp))
                        }
                    }
                }
                GameText("$found / $total FIGHTERS", Type.Heading, color = io.github.projectwip.ui.Palette.Gold, outline = 2.5.dp)
                PlainText(if (match != null && match.config.serverMatchId <= 0L) "Offline match against bots · practice, nothing is earned" else "Your opponents are bots, picked by the server", Type.Small)
                Spacer(Modifier.height(2.dp))
                PlainText(tip, Type.Body, color = Color.White, align = TextAlign.Center)
                if (!done) ChunkyButton(onCancel, Modifier.size(200.dp, 52.dp), ButtonStyle.RED, lip = 4.dp, sound = io.github.projectwip.audio.Sound.UI_BACK) { GameText("CANCEL", Type.Heading) }
                else Spacer(Modifier.height(52.dp))
            }
        }
    }
}

/** One place in the matchmaking line-up: empty and pulsing until its fighter is found. */
@Composable
private fun Slot(fighter: io.github.projectwip.sim.Fighter?, you: Boolean, phase: Float, modifier: Modifier = Modifier) {
    Panel(modifier.height(112.dp), color = if (you) Color(0xFF3E6A2E) else io.github.projectwip.ui.Palette.PanelInset, cut = 10.dp) {
        Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (fighter == null) {
                val pulse = 0.35f + 0.25f * kotlin.math.sin(phase * 4f)
                GameText("?", Type.Display, color = Color.White.copy(alpha = pulse), outline = 3.dp)
            } else {
                io.github.projectwip.ui.FighterView(fighter.def, fighter.skin, Modifier.size(70.dp), pedestal = false)
                PlainText(if (you) "YOU" else fighter.name, Type.Small, color = if (you) io.github.projectwip.ui.Palette.Gold else Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MatchBody(match: Match, settings: Settings, sfx: Sfx, matchesPlayed: Int, onFinish: (MatchSummary) -> Unit, duel: io.github.projectwip.net.DuelLink? = null) {
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
        // A 1v1 can't be stopped: the other player is still playing. The menu opens over the running match.
        if (duel != null) return@BackHandler
        if (paused) view?.paused = true else view?.resumeGame()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1C143A))) {
        AndroidView(
            factory = { ctx ->
                MatchView(ctx, match, settings, sfx, matchesPlayed,
                    onPauseRequested = { if (!done && !intro) { paused = true; if (duel == null) view?.paused = true } },
                    onFinished = { report -> finish(report) },
                    duel = duel,
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
                        PlainText(if (match.practice) "Nothing is at stake in the Training Area. Leave whenever you like." else if (duel != null) "The match is still going: your opponent can't be paused. Leaving hands them the win." else "Bots wait for you. Leaving now counts as a defeat.", Type.Body, align = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        ChunkyButton({ paused = false; if (duel == null) view?.resumeGame() }, Modifier.size(260.dp, 64.dp), ButtonStyle.GREEN) { GameText("RESUME", Type.Title) }
                        ChunkyButton({ finish(match.forfeit()) }, Modifier.size(260.dp, 54.dp), ButtonStyle.RED) {
                            GameText("LEAVE MATCH", Type.Heading)
                        }
                    }
                }
            }
        }
    }
}
