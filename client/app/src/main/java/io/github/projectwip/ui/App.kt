package io.github.projectwip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.projectwip.data.CapsuleResult
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.MatchRewards
import io.github.projectwip.data.Reward
import io.github.projectwip.sim.MatchConfig
import io.github.projectwip.ui.screens.CapsuleOpenOverlay
import io.github.projectwip.ui.screens.CupTrackScreen
import io.github.projectwip.ui.screens.FightersScreen
import io.github.projectwip.ui.screens.HomeScreen
import io.github.projectwip.ui.screens.MatchScreen
import io.github.projectwip.ui.screens.MatchSummary
import io.github.projectwip.ui.screens.ResultScreen
import io.github.projectwip.ui.screens.SettingsScreen
import io.github.projectwip.ui.screens.ShopScreen

sealed interface Screen {
    val depth: Int
    data object Home : Screen { override val depth = 0 }
    data class Fighters(val focus: FighterId? = null) : Screen { override val depth = 1 }
    data object CupTrack : Screen { override val depth = 1 }
    data object Leaderboard : Screen { override val depth = 1 }
    data object News : Screen { override val depth = 1 }
    data object Shop : Screen { override val depth = 1 }
    data object Road : Screen { override val depth = 2 }
    data object Settings : Screen { override val depth = 1 }
    data object Team : Screen { override val depth = 1 }
    data class Match(val config: MatchConfig) : Screen { override val depth = 2 }
    data class Result(val summary: MatchSummary, val rewards: MatchRewards) : Screen { override val depth = 3 }
}

/** A modal reward reveal: "You got +400 Bolts". */
data class RewardReveal(val title: String, val reward: Reward)

@Composable
fun App(repo: GameRepository, sfx: Sfx, music: io.github.projectwip.audio.Music, server: io.github.projectwip.net.GameServer, startScreen: String? = null) {
    val save by repo.save.collectAsState()
    var screen by remember {
        mutableStateOf(
            when (startScreen) {
                "match" -> Screen.Match(startMatchConfig(repo.save.value))
                "boss" -> Screen.Match(startMatchConfig(repo.save.value).copy(mode = io.github.projectwip.data.GameMode.BOSS))
                "duel" -> Screen.Match(startMatchConfig(repo.save.value).copy(mode = io.github.projectwip.data.GameMode.DUEL, boss = null))
                "train" -> Screen.Match(startMatchConfig(repo.save.value).copy(mode = io.github.projectwip.data.GameMode.TRAINING))
                "fighters" -> Screen.Fighters()
                "roster" -> { io.github.projectwip.ui.screens.rosterPreview = true; Screen.Fighters() }
                "kito" -> Screen.Fighters(FighterId.KITO)
                "buddy" -> Screen.Fighters(FighterId.BUDDY)
                // The Training Area as any fighter, unlocked or not, for looking at them in play.
                "trybuddy", "trykito", "trybrakk", "trybyte" -> Screen.Match(startMatchConfig(repo.save.value).copy(
                    playerFighter = FighterId.valueOf(startScreen.removePrefix("try").uppercase()), playerSkin = 0, mode = io.github.projectwip.data.GameMode.TRAINING, boss = null))
                "shop" -> Screen.Shop
                "road" -> Screen.Road
                "track" -> Screen.CupTrack
                "settings" -> Screen.Settings
                "leaders" -> Screen.Leaderboard
                "news" -> Screen.News
                "result" -> previewResult(repo.save.value)
                else -> Screen.Home
            }
        )
    }
    var reveal by remember { mutableStateOf<RewardReveal?>(null) }
    /** The Spark Capsule being opened, if any. Its reward is already saved by the time this is set. */
    var capsule by remember {
        // Debug: `--es screen capsule3` previews opening a box whose best item is tier 3 (`capsule3s`: a full box of eight) without touching the save.
        mutableStateOf(startScreen?.takeIf { it.startsWith("capsule") }?.let {
            val tier = CapsuleTier.entries[(it.removePrefix("capsule").trimEnd('s', 'f', 'b').toIntOrNull() ?: 0).coerceIn(0, CapsuleTier.entries.lastIndex)]
            // Suffixes: s = a full box, f = a fighter comes out, b = a bundle comes out.
            val reward = when {
                it.endsWith("f") -> Reward.UnlockFighter(FighterId.KITO)
                it.endsWith("b") -> Reward.Bundle(listOf(Reward.SkinReward(FighterId.BRAKK, 1), Reward.Prisms(150), Reward.Bolts(800)))
                else -> Reward.Bolts(100 * (tier.ordinal + 1))
            }
            val count = if (it.endsWith("s")) io.github.projectwip.data.SparkCapsules.MAX_ITEMS else io.github.projectwip.data.SparkCapsules.BOX_ITEMS
            CapsuleResult(List(count - 1) { i -> io.github.projectwip.data.BoxItem(CapsuleTier.entries[i % (tier.ordinal + 1)], if (i % 2 == 0) Reward.Bolts(180 + 60 * i) else Reward.Credits(120 + 40 * i)) } + io.github.projectwip.data.BoxItem(tier, reward))
        })
    }

    // ---- start-up: connect to the game server, ask GitHub whether a newer release exists, load sounds and music
    val serverStatus by server.status.collectAsState()
    val account = serverStatus.account
    // While the server can't be reached the game is on its offline profile, answered on the device.
    val local = remember { io.github.projectwip.net.LocalGame(repo) }
    val offline by repo.offline.collectAsState()
    // Online, the tweaks (the Chaos Command Center) belong to developers, and the server says who those are: a dev
    // build is not enough. Offline is Chaos Mode: the offline profile is the player's own, so everyone has them.
    val developer = account?.developer == true && serverStatus.online
    val dev = offline || developer
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unsupportedSkipped by remember { mutableStateOf(false) }
    // A disabled account gets nothing: no menus, no offline play. The notice stays up until the server lets it back in.
    // Debug: `--es screen disabled` shows the notice with made-up details.
    val disabledShown = serverStatus.disabled || startScreen == "disabled"
    var booting by remember { mutableStateOf(true) }
    var bootProgress by remember { mutableStateOf(0f) }
    var bootStatus by remember { mutableStateOf("Connecting to server…") }
    var connection by remember { mutableStateOf(Connection.CONNECTING) }
    // A new player picks their name first; only then is an account made for them on the server.
    var needsName by remember {
        val settings = repo.save.value.settings
        mutableStateOf(!settings.nameChosen && !server.hasAccount(settings.serverUrl.ifBlank { io.github.projectwip.BuildConfig.SERVER_URL }))
    }
    var update by remember {
        // Debug: `--es screen update` shows the update screen with made-up details.
        mutableStateOf(if (startScreen == "update") io.github.projectwip.net.UpdateInfo("9.9.9-preview", "## New\n- Example note one\n- Example note two", REPO_RELEASES, REPO_RELEASES) else null)
    }
    // The server: is this version welcome, sign in, fetch live settings, and sync the save. The game keeps trying
    // for a minute; after that the player chooses between trying again and offline mode.
    LaunchedEffect(needsName) {
        if (needsName) return@LaunchedEffect
        val started = System.currentTimeMillis()
        connection = Connection.CONNECTING
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { connectToServer(server, repo) }
            if (connection != Connection.CONNECTING) break // the player stopped waiting
            val now = server.status.value
            if (now.online || !now.supported || now.disabled || now.url.isEmpty()) { connection = Connection.SETTLED; break }
            // No answer: the game goes on, offline, and keeps trying in the background.
            if (System.currentTimeMillis() - started >= CONNECT_PATIENCE_MS) { connection = Connection.SETTLED; break }
            delay(1500)
        }
    }
    LaunchedEffect(Unit) {
        val checked = java.util.concurrent.atomic.AtomicBoolean(false)
        // Debug: `--es screen updatecheck` runs the real check pretending to be a very old version.
        val running = if (startScreen == "updatecheck") "0.0.1" else io.github.projectwip.BuildConfig.VERSION_CODE.toString()
        launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = io.github.projectwip.net.Updater.check(running)
            if (found != null) update = found
            checked.set(true)
        }
        val started = System.currentTimeMillis()
        while (true) {
            val waited = System.currentTimeMillis() - started
            val settled = connection == Connection.SETTLED
            val target = 0.15f * (if (settled) 1f else (waited / CONNECT_PATIENCE_MS.toFloat()).coerceAtMost(0.9f)) +
                0.1f * (if (checked.get()) 1f else (waited / 4000f).coerceAtMost(0.9f)) + 0.55f * sfx.progress + 0.2f * (if (music.ready) 1f else 0f)
            // The bar only ever moves forward, and eases toward the real figure so it doesn't jump.
            bootProgress = maxOf(bootProgress, bootProgress + (target - bootProgress) * 0.2f)
            bootStatus = when {
                !settled -> "Connecting to server…"
                !checked.get() -> "Checking for updates…"
                sfx.progress < 1f -> "Building sound effects…"
                !music.ready -> "Composing the lobby music…"
                else -> "Ready!"
            }
            val done = settled && checked.get() && sfx.progress >= 1f && music.ready
            // Stay up long enough to be read; once the server question is settled, never hang on anything else.
            if ((done && waited > 1200 && bootProgress > 0.985f) || (settled && waited > 15000)) break
            delay(40)
        }
        bootProgress = 1f
        booting = false
    }

    // Cups, Arena Boxes, currencies, fighters and shop deals are the server's: whatever it says this player has is what the game shows.
    LaunchedEffect(account) { account?.let { repo.sync(it) } }
    // Anyone who isn't a developer plays without the debug menu's cheats, even if their save has some switched on.
    // Back online, anyone who isn't a developer loses the tweaks they had in Chaos Mode.
    LaunchedEffect(developer, offline, booting, serverStatus.online, account) { if (!booting && !offline && serverStatus.online && account != null && !developer) repo.clearCheats() }
    val inMatch = screen is Screen.Match
    // Which profile is being played on: the server's account while it answers, the offline profile kept on this
    // device while it doesn't. It is never swapped in the middle of a match (the match belongs to the profile it
    // started on), and a disabled account or an unsupported version gets no offline play. The player can also ask
    // for it, in Settings > Modes, while the server is there.
    val forceOffline = save.settings.forceOffline
    LaunchedEffect(serverStatus.online, serverStatus.disabled, serverStatus.supported, booting, inMatch, forceOffline) {
        if (!booting && !inMatch) repo.setOffline((forceOffline || !serverStatus.online) && !serverStatus.disabled && serverStatus.supported)
    }
    // Offline in the menus: quietly keep trying to get back online.
    LaunchedEffect(serverStatus.online, serverStatus.supported, booting, inMatch) {
        if (booting || inMatch || serverStatus.online || !serverStatus.supported) return@LaunchedEffect
        while (true) {
            delay(20_000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { connectToServer(server, repo) }
        }
    }

    LaunchedEffect(save.settings) {
        sfx.volume = if (save.settings.muted) 0f else save.settings.sfxVolume
        sfx.hapticsEnabled = save.settings.haptics
        music.volume = if (save.settings.muted) 0f else save.settings.musicVolume
    }

    // Lobby music plays in the menus and makes way for the match.
    // Music: silence while loading and in matches, its own theme on the result screen, the lobby loop elsewhere.
    val wantedTrack = when (val s = screen) {
        is Screen.Match -> null
        is Screen.Result -> if (s.summary.report.outcome == io.github.projectwip.data.MatchOutcome.VICTORY) io.github.projectwip.audio.Track.VICTORY else io.github.projectwip.audio.Track.DEFEAT
        else -> io.github.projectwip.audio.Track.LOBBY
    }
    val blocked = update != null || (!serverStatus.supported && !unsupportedSkipped) || (disabledShown && !inMatch)
    LaunchedEffect(wantedTrack, booting, blocked) { music.play(if (booting || blocked) null else wantedTrack) }

    /** The team of real players this player is in, if any. It outlasts the screens and the matches it plays. */
    var team by remember { mutableStateOf<io.github.projectwip.net.TeamLink?>(null) }
    val teamUp = team?.takeIf { !it.ended && it.error == null }
    // In a team, matches are started by its leader, from the team screen: Play goes there.
    val go: (Screen) -> Unit = {
        // (Offline there is nobody to team up with, and a match is the offline profile's alone.)
        val to = if (repo.offlineMode) (if (it is Screen.Team) Screen.Home else it) else if (it is Screen.Match && it.config.team == null && teamUp != null) Screen.Team else it
        if (to !is Screen.Match) sfx.play(Sound.WHOOSH, 0.7f)
        screen = to
    }
    // The leader has pressed Play: everyone in the team goes into the match, from whatever screen they are on.
    val teamStart = teamUp?.start
    LaunchedEffect(teamStart, screen is Screen.Match) {
        if (teamStart == null || screen is Screen.Match) return@LaunchedEffect
        teamStart.bots?.let { server.useBots(teamStart.difficulty, it) }
        val me = teamStart.setup.players[teamStart.setup.slot]
        screen = Screen.Match(MatchConfig(me.fighter, me.level, me.skin, me.name, teamStart.difficulty, mode = teamStart.mode,
            seed = teamStart.seed, boss = teamStart.boss, botNames = teamStart.botNames, team = teamStart.setup))
    }
    val showReward: (RewardReveal) -> Unit = { reveal = it }

    /** A short message across the top of the screen (why a drop didn't open, say). */
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(3200); toast = null } }
    var opening by remember { mutableStateOf(false) }
    /** A match has just ended and the server is replaying it to decide the result. */
    var judging by remember { mutableStateOf(false) }
    val ask = remember { ServerCall(scope, server, local, repo, sfx) { toast = it } }
    // Arena Boxes are opened by the server: it rolls the drop, the game shows what came out. (Offline they are the
    // offline profile's, and are rolled on the device.)
    val openCapsule: () -> Unit = {
        if (!opening) {
            if (repo.offlineMode) repo.save.value.settings.let { local.openDrop(it.debugLuck, it.debugInfiniteCapsules) }.let { if (it != null) capsule = it else toast = "No Arena Boxes to open." }
            else if (!serverStatus.online) toast = "Couldn't reach the server. Try again in a moment."
            else {
                opening = true
                scope.launch {
                    val cheats = repo.save.value.settings
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        server.openDrop(if (dev) cheats.debugLuck else 0f, dev && cheats.debugInfiniteCapsules)
                    }
                    opening = false
                    server.status.value.account?.let { repo.sync(it) }
                    if (result != null) { repo.dropOpened(); capsule = result }
                    else toast = if (server.status.value.online) "No Arena Boxes to open." else "Couldn't reach the server. Try again in a moment."
                }
            }
        }
    }
    /** Everything that came out of an "open all", while it is being shown. Like [capsule], it is already saved. */
    var haul by remember {
        // Debug: `--es screen haul` previews it with made-up drops, without touching the save.
        mutableStateOf(if (startScreen == "haul") io.github.projectwip.ui.screens.previewHaul() else null)
    }
    // Opens every drop that is left. [first] is the one on screen, when its own reveal is being skipped.
    val openAll: (CapsuleResult?) -> Unit = { first ->
        if (!opening) {
            if (repo.offlineMode) repo.save.value.settings.let { local.openAllDrops(it.debugLuck, it.debugInfiniteCapsules) }.let { if (it != null) { capsule = null; haul = (listOfNotNull(first) + it).flatMap { box -> box.items } } else toast = "No Arena Boxes to open." }
            else if (!serverStatus.online) toast = "Couldn't reach the server. Try again in a moment."
            else {
                opening = true
                scope.launch {
                    val luck = if (dev) repo.save.value.settings.debugLuck else 0f
                    val rest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.openAllDrops(luck) }
                    opening = false
                    server.status.value.account?.let { repo.sync(it) }
                    if (rest != null) { repo.dropOpened(rest.size); capsule = null; haul = (listOfNotNull(first) + rest).flatMap { box -> box.items } }
                    else toast = if (server.status.value.online) "No Arena Boxes to open." else "Couldn't reach the server. Try again in a moment."
                }
            }
        }
    }

    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Match) {
        screen = Screen.Home
    }
    BackHandler(enabled = capsule != null) { capsule = null }
    BackHandler(enabled = haul != null) { haul = null }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.BgBottom)) {
        // Uniform UI scale so tablets get bigger, more legible UI — layouts then use the extra room
        // (see UiMetrics.roomy) rather than just stretching.
        val base = LocalDensity.current
        val scale = (maxHeight.value / 480f).coerceIn(0.92f, 1.4f)
        val density = Density(base.density * scale, 1f)
        val metrics = UiMetrics(maxWidth.value / scale, maxHeight.value / scale, scale)

        val lobby = remember { io.github.projectwip.render3d.LobbyParams() }
        CompositionLocalProvider(LocalDensity provides density, LocalUi provides metrics, LocalSfx provides sfx, LocalLobby provides lobby, LocalServer provides server, LocalOfflineGame provides local, LocalOfflineMode provides offline, LocalDev provides dev, LocalServerCall provides ask) {
            if (screen !is Screen.Match) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> io.github.projectwip.render3d.LobbyView(ctx, lobby) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The menu steps aside while a capsule is being opened, so nothing sits on top of the 3D capsule.
            val menuAlpha by androidx.compose.animation.core.animateFloatAsState(if (capsule != null || haul != null) 0f else 1f, tween(160), label = "menu")
            AnimatedContent(
                modifier = Modifier.graphicsLayer { alpha = menuAlpha },
                targetState = screen,
                contentKey = { it::class },
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    if (targetState is Screen.Match || initialState is Screen.Match) {
                        fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                    } else {
                        (slideInHorizontally(tween(260)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220)) { w -> if (forward) -w / 6 else w / 6 } + fadeOut(tween(180)))
                    }
                },
                label = "screens",
            ) { s ->
                when (s) {
                    Screen.Home -> HomeScreen(save, repo, go, showReward, openCapsule)
                    is Screen.Fighters -> FightersScreen(save, repo, s.focus, go)
                    Screen.CupTrack -> CupTrackScreen(save, repo, go, showReward)
                    Screen.Leaderboard -> io.github.projectwip.ui.screens.LeaderboardScreen(save, go)
                    Screen.News -> io.github.projectwip.ui.screens.NewsScreen(go)
                    Screen.Shop -> ShopScreen(save, repo, go, showReward)
                    Screen.Road -> io.github.projectwip.ui.screens.RoadScreen(save, go, showReward)
                    Screen.Settings -> SettingsScreen(save, repo, go)
                    Screen.Team -> io.github.projectwip.ui.screens.TeamScreen(save, team, { team = it }, go)
                    is Screen.Match -> MatchScreen(
                        // The difficulty is the one the server last approved (it is kept in the settings), and the
                        // server's match plan has the final word.
                        s.config,
                        // (Offline the server isn't asked, even if it is there: the match is the offline profile's.)
                        save.settings, sfx, save.matchesPlayed, server.takeIf { !offline },
                        onCancel = { screen = Screen.Home },
                        team = team.takeIf { !offline },
                        onFinish = { summary ->
                            scope.launch {
                                // The server replays the match from the player's inputs: the result and what it is worth are
                                // its own. No answer means an offline match: the device's result is shown and nothing is earned.
                                // (A 1v1 is settled by the lobby, from both players' inputs, and the answer comes down its line.)
                                val duel = summary.duel
                                val squad = summary.team
                                judging = (summary.serverMatchId > 0 || duel != null || squad != null) && serverStatus.online && !repo.offlineMode
                                val verdict = if (repo.offlineMode) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (duel != null) duel.result()?.let { server.duelVerdict(it) }
                                    else if (squad != null) squad.result()?.let { server.duelVerdict(it) }
                                    else server.reportMatch(summary.serverMatchId, summary.report, summary.inputs)
                                }
                                judging = false
                                server.status.value.account?.let { repo.sync(it) }
                                val shown = verdict?.judged?.let { summary.judged(it) } ?: summary
                                // The Training Area is practice: nothing to record, straight back to the lobby.
                                if (shown.report.mode == io.github.projectwip.data.GameMode.TRAINING) screen = Screen.Home
                                else screen = Screen.Result(shown, repo.applyMatch(shown.report, verdict))
                            }
                        })
                    is Screen.Result -> ResultScreen(s.summary, s.rewards, save, go)
                }
            }

            AnimatedVisibility(reveal != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                reveal?.let { RewardRevealOverlay(it, save.bolts, save.prisms, roadNow(save), roadGoal(save)) { reveal = null } }
            }
            // On top of everything: the loading screen, then (if a newer release exists) the update screen.
            // Server status in the corner. (Its notice is part of the home screen.)
            // (Not over Settings: its tabs run down that corner, and Settings > Modes says the same.)
            if (screen !is Screen.Match && screen !is Screen.Settings && capsule == null && haul == null && reveal == null) {
                PlainText(
                    if (serverStatus.online && !offline) "● ONLINE" else "● OFFLINE · CHAOS MODE · your offline profile, kept on this device", Type.Small,
                    if (screen is Screen.Home) Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 68.dp)
                    else Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp),
                    color = if (serverStatus.online) Palette.Positive else Palette.TextDim,
                )
            }
            if (!booting) update?.let { io.github.projectwip.ui.screens.UpdateScreen(it) { update = null } }
            if (!booting && update == null && !serverStatus.supported && !unsupportedSkipped) {
                io.github.projectwip.ui.screens.UnsupportedScreen(serverStatus.message, REPO_RELEASES) { unsupportedSkipped = true }
            }
            if (!booting && update == null && disabledShown && !inMatch) {
                if (serverStatus.disabled) io.github.projectwip.ui.screens.DisabledScreen(serverStatus.disabledReason, serverStatus.disabledUntil)
                else io.github.projectwip.ui.screens.DisabledScreen("An example reason, as the server's owner wrote it.", System.currentTimeMillis() + 54 * 3600_000L)
            }
            if (judging) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                GameText("THE SERVER IS CHECKING THE MATCH…", Type.Title, outline = 3.5.dp)
            }
            toast?.let { Badge(it, Modifier.align(Alignment.TopCenter).padding(top = 120.dp), color = Palette.RedDeep) }
            AnimatedVisibility(booting, enter = fadeIn(tween(0)), exit = fadeOut(tween(250))) {
                // Dev builds don't have to sit through the minute of trying.
                io.github.projectwip.ui.screens.LoadingScreen(bootProgress, bootStatus,
                    onSkip = if (io.github.projectwip.BuildConfig.DEBUG && connection == Connection.CONNECTING) ({ connection = Connection.SETTLED }) else null)
            }
            if (needsName) io.github.projectwip.ui.screens.NameScreen { name ->
                repo.updateSettings { it.copy(playerName = name, nameChosen = true) }
                needsName = false
            }
            AnimatedVisibility(capsule != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                capsule?.let { CapsuleOpenOverlay(it, if (save.settings.debugInfiniteCapsules) Int.MAX_VALUE else save.capsules, save.bolts, save.prisms, roadNow(save), roadGoal(save), onNext = openCapsule, onOpenAll = openAll, onDone = { capsule = null }) }
            }
            AnimatedVisibility(haul != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                haul?.let { io.github.projectwip.ui.screens.DropHaulOverlay(it, save.bolts, save.prisms, roadNow(save), roadGoal(save)) { haul = null } }
            }
        }
    }
}

/** Debug (`--es screen result`): the result screen with made-up numbers, without playing or touching the save. */
private fun previewResult(save: io.github.projectwip.data.SaveData): Screen {
    val report = io.github.projectwip.data.MatchReport(
        outcome = io.github.projectwip.data.MatchOutcome.VICTORY, mode = io.github.projectwip.data.GameMode.LAST_SPARK, placement = 1, players = 10,
        fighter = save.selectedFighter, kos = 4, deaths = 0, damageDealt = 5200, mvp = true, difficulty = save.settings.botDifficulty, blueScore = 0, redScore = 0,
    )
    val players = (1..10).map { i ->
        io.github.projectwip.ui.screens.PlayerLine(if (i == 1) save.settings.playerName else "Bot $i", FighterId.entries[i % 3], 0, i, 10 - i, 1, 6000 - i * 500, i == 1, i == 1, i != 1, placement = i)
    }
    return Screen.Result(MatchSummary(report, players, 1), MatchRewards(save.cups, 8, 32, 10, emptyList(), capsuleEarned = true, capsulesLeftToday = 2))
}

private const val REPO_RELEASES = "https://github.com/TerminalDev-1/AstroArena/releases"

/** How long the loading screen keeps trying to reach the server before the game goes on in offline mode. */
private const val CONNECT_PATIENCE_MS = 8_000L

/** CONNECTING: trying to reach the server. SETTLED: online, or it didn't answer in time and the game is offline. */
private enum class Connection { CONNECTING, SETTLED }

/**
 * Says hello to the game server and syncs the save with it. Blocking: call it off the main thread.
 * A save nobody has played on is replaced by the copy the server holds; otherwise this device's save wins
 * and is uploaded. Either way the server then says who this player is (its Cups, its Arena Boxes, developer
 * or not), which the game takes on.
 */
fun connectToServer(server: io.github.projectwip.net.GameServer, repo: GameRepository) {
    // (The online save, whichever profile is showing: the offline profile is never the server's business.)
    val save = repo.online
    val settings = repo.save.value.settings
    val url = settings.serverUrl.ifBlank { io.github.projectwip.BuildConfig.SERVER_URL }
    val stored = server.connect(url, io.github.projectwip.BuildConfig.VERSION_CODE.toString(), settings.playerName)
    val status = server.status.value
    if (!status.online || !status.supported) return
    val restored = stored?.let { runCatching { io.github.projectwip.data.SaveStore.fromJson(it) }.getOrNull() }
    if (restored != null && io.github.projectwip.data.Progression.isFresh(save) && !io.github.projectwip.data.Progression.isFresh(restored)) {
        repo.restore(restored)
        server.refreshAccount()
    } else server.syncSave(io.github.projectwip.data.SaveStore.toJson(repo.online.copy(settings = settings)))
}

fun startMatchConfig(save: io.github.projectwip.data.SaveData): MatchConfig {
    val p = save.progress(save.selectedFighter)
    return MatchConfig(save.selectedFighter, p.level, p.skin, save.settings.playerName, save.settings.botDifficulty, mode = save.selectedMode,
        boss = save.selectedBoss.takeIf { save.selectedMode == io.github.projectwip.data.GameMode.BOSS })
}

fun rewardLabel(r: Reward): String = when (r) {
    is Reward.Bolts -> "+${r.amount} Upgrade Credits"
    is Reward.Prisms -> "+${r.amount} CPU Chips"
    is Reward.Credits -> "+${r.amount} Credits"
    is Reward.UnlockFighter -> "${Balance.fighter(r.fighter).name} unlocked!"
    is Reward.SkinReward -> "${Balance.fighter(r.fighter).skins[r.skinIndex].name} colorway"
    is Reward.Bundle -> r.items.joinToString(", ") { rewardLabel(it) }
}

@Composable
fun RewardVisual(r: Reward, modifier: Modifier = Modifier) {
    when (r) {
        is Reward.Bolts -> GameIcon(IconKind.BOLT, modifier)
        is Reward.Prisms -> GameIcon(IconKind.PRISM, modifier)
        is Reward.Credits -> GameIcon(IconKind.CREDIT, modifier)
        is Reward.UnlockFighter -> FighterView(Balance.fighter(r.fighter), 0, modifier, pedestal = false)
        is Reward.SkinReward -> FighterView(Balance.fighter(r.fighter), r.skinIndex, modifier, pedestal = false)
        is Reward.Bundle -> GameIcon(IconKind.GIFT, modifier)
    }
}

@Composable
private fun RewardRevealOverlay(r: RewardReveal, boltsNow: Int, prismsNow: Int, roadNow: Int, roadGoal: Int, onDismiss: () -> Unit) {
    Box(
        // Swallows taps so nothing underneath is pressed while the reward plays out.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        RewardShowcase(r.title, Palette.Gold, r.reward, boltsNow, prismsNow, roadNow = roadNow, roadGoal = roadGoal) {
            ChunkyButton(onDismiss, Modifier.size(200.dp, 60.dp), ButtonStyle.GREEN) { GameText("AWESOME", Type.Heading) }
        }
    }
}

/** What the Spark Road is asking for the fighter being unlocked; 0 once the road is finished. */
fun roadGoal(save: io.github.projectwip.data.SaveData): Int = io.github.projectwip.data.SparkRoad.next(save)?.cost ?: 0

/** What a [RoadMeter] shows: the Credits on the road. */
fun roadNow(save: io.github.projectwip.data.SaveData): Int = save.credits

/** Confirm dialog in game style. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: ButtonStyle = ButtonStyle.GREEN,
    content: @Composable () -> Unit = {},
) {
    val pop = remember { Animatable(0.7f) }
    val sfx = LocalSfx.current
    LaunchedEffect(Unit) { sfx?.play(Sound.UI_OPEN); pop.animateTo(1f, spring(dampingRatio = 0.55f)) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Panel(
            Modifier.widthIn(max = 460.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .clickable(remember { MutableInteractionSource() }, null) { },
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GameText(title, Type.Title, outline = 3.5.dp)
                Spacer(Modifier.height(10.dp))
                content()
                PlainText(body, Type.Body, align = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChunkyButton(onDismiss, Modifier.size(150.dp, 56.dp), ButtonStyle.PURPLE) { GameText("CANCEL", Type.Heading) }
                    ChunkyButton(onConfirm, Modifier.size(170.dp, 56.dp), confirmStyle) { GameText(confirmLabel, Type.Heading) }
                }
            }
        }
    }
}

@Composable
fun FighterRays(modifier: Modifier = Modifier, color: Color = Palette.Gold) {
    val time by rememberAnimTime()
    androidx.compose.foundation.Canvas(modifier) {
        val c = center
        rotate(time * 20f, c) {
            for (i in 0 until 14) {
                val a0 = i * (2 * Math.PI / 14)
                val a1 = a0 + 0.14
                val r = size.minDimension / 2
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(c.x + (kotlin.math.cos(a0) * r).toFloat(), c.y + (kotlin.math.sin(a0) * r).toFloat())
                    lineTo(c.x + (kotlin.math.cos(a1) * r).toFloat(), c.y + (kotlin.math.sin(a1) * r).toFloat())
                    close()
                }
                drawPath(p, color.copy(alpha = 0.16f))
            }
        }
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), c, size.minDimension / 2.5f))
    }
}
