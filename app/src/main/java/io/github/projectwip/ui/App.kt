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
    data object Shop : Screen { override val depth = 1 }
    data object Settings : Screen { override val depth = 1 }
    data class Match(val config: MatchConfig) : Screen { override val depth = 2 }
    data class Result(val summary: MatchSummary, val rewards: MatchRewards) : Screen { override val depth = 3 }
}

/** A modal reward reveal: "You got +400 Bolts". */
data class RewardReveal(val title: String, val reward: Reward)

@Composable
fun App(repo: GameRepository, sfx: Sfx, music: io.github.projectwip.audio.Music, startScreen: String? = null) {
    val save by repo.save.collectAsState()
    var screen by remember {
        mutableStateOf(
            when (startScreen) {
                "match" -> Screen.Match(startMatchConfig(repo.save.value))
                "boss" -> Screen.Match(startMatchConfig(repo.save.value).copy(mode = io.github.projectwip.data.GameMode.BOSS))
                "fighters" -> Screen.Fighters()
                "kito" -> Screen.Fighters(FighterId.KITO)
                "shop" -> Screen.Shop
                "track" -> Screen.CupTrack
                "settings" -> Screen.Settings
                "leaders" -> Screen.Leaderboard
                "result" -> previewResult(repo.save.value)
                else -> Screen.Home
            }
        )
    }
    var reveal by remember { mutableStateOf<RewardReveal?>(null) }
    /** The Spark Capsule being opened, if any. Its reward is already saved by the time this is set. */
    var capsule by remember {
        // Debug: `--es screen capsule3` previews opening a capsule of tier 3 (`capsule3s`: one that splits into eight) without touching the save.
        mutableStateOf(startScreen?.takeIf { it.startsWith("capsule") }?.let {
            val tier = CapsuleTier.entries[(it.removePrefix("capsule").trimEnd('s', 'f', 'b').toIntOrNull() ?: 0).coerceIn(0, CapsuleTier.entries.lastIndex)]
            // Suffixes: s = splits into eight, f = a fighter comes out, b = a bundle comes out.
            val reward = when {
                it.endsWith("f") -> Reward.UnlockFighter(FighterId.MIRA)
                it.endsWith("b") -> Reward.Bundle(listOf(Reward.SkinReward(FighterId.BRAKK, 1), Reward.Prisms(150), Reward.Bolts(800)))
                else -> Reward.Bolts(100 * (tier.ordinal + 1))
            }
            CapsuleResult(tier, reward, pieces = if (it.endsWith("s")) 8 else 1)
        })
    }

    // ---- start-up: load sounds and music, and ask GitHub whether a newer release exists
    var booting by remember { mutableStateOf(true) }
    var bootProgress by remember { mutableStateOf(0f) }
    var bootStatus by remember { mutableStateOf("Checking for updates…") }
    var update by remember {
        // Debug: `--es screen update` shows the update screen with made-up details.
        mutableStateOf(if (startScreen == "update") io.github.projectwip.net.UpdateInfo("9.9.9-preview", "## New\n- Example note one\n- Example note two", REPO_RELEASES, REPO_RELEASES) else null)
    }
    LaunchedEffect(Unit) {
        val checked = java.util.concurrent.atomic.AtomicBoolean(false)
        // Debug: `--es screen updatecheck` runs the real check pretending to be a very old version.
        val running = if (startScreen == "updatecheck") "0.0.1" else io.github.projectwip.BuildConfig.VERSION_NAME
        launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = io.github.projectwip.net.Updater.check(running)
            if (found != null) update = found
            checked.set(true)
        }
        val started = System.currentTimeMillis()
        while (true) {
            val waited = System.currentTimeMillis() - started
            val target = 0.2f * (if (checked.get()) 1f else (waited / 4000f).coerceAtMost(0.9f)) + 0.6f * sfx.progress + 0.2f * (if (music.ready) 1f else 0f)
            // The bar only ever moves forward, and eases toward the real figure so it doesn't jump.
            bootProgress = maxOf(bootProgress, bootProgress + (target - bootProgress) * 0.2f)
            bootStatus = when {
                !checked.get() -> "Checking for updates…"
                sfx.progress < 1f -> "Building sound effects…"
                !music.ready -> "Composing the lobby music…"
                else -> "Ready!"
            }
            val done = checked.get() && sfx.progress >= 1f && music.ready
            // Stay up long enough to be read; never hang forever if something fails to load.
            if ((done && waited > 1200 && bootProgress > 0.985f) || waited > 15000) break
            delay(40)
        }
        bootProgress = 1f
        booting = false
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
    LaunchedEffect(wantedTrack, booting, update) { music.play(if (booting || update != null) null else wantedTrack) }

    val go: (Screen) -> Unit = { if (it !is Screen.Match) sfx.play(Sound.WHOOSH, 0.7f); screen = it }
    val showReward: (RewardReveal) -> Unit = { reveal = it }

    val openCapsule: () -> Unit = { repo.openCapsule()?.let { capsule = it } }
    var debugMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Match) {
        screen = Screen.Home
    }
    BackHandler(enabled = capsule != null) { capsule = null }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.BgBottom)) {
        // Uniform UI scale so tablets get bigger, more legible UI — layouts then use the extra room
        // (see UiMetrics.roomy) rather than just stretching.
        val base = LocalDensity.current
        val scale = (maxHeight.value / 480f).coerceIn(0.92f, 1.4f)
        val density = Density(base.density * scale, 1f)
        val metrics = UiMetrics(maxWidth.value / scale, maxHeight.value / scale, scale)

        val lobby = remember { io.github.projectwip.render3d.LobbyParams() }
        CompositionLocalProvider(LocalDensity provides density, LocalUi provides metrics, LocalSfx provides sfx, LocalLobby provides lobby) {
            if (screen !is Screen.Match) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> io.github.projectwip.render3d.LobbyView(ctx, lobby) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The menu steps aside while a capsule is being opened, so nothing sits on top of the 3D capsule.
            val menuAlpha by androidx.compose.animation.core.animateFloatAsState(if (capsule != null) 0f else 1f, tween(160), label = "menu")
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
                    is Screen.Fighters -> FightersScreen(save, repo, s.focus ?: save.selectedFighter, go)
                    Screen.CupTrack -> CupTrackScreen(save, repo, go, showReward)
                    Screen.Leaderboard -> io.github.projectwip.ui.screens.LeaderboardScreen(save, repo.today, go)
                    Screen.Shop -> ShopScreen(save, repo, go, showReward)
                    Screen.Settings -> SettingsScreen(save, repo, go)
                    is Screen.Match -> MatchScreen(s.config, save.settings, sfx, save.matchesPlayed,
                        onFinish = { summary ->
                            val rewards = repo.applyMatch(summary.report)
                            screen = Screen.Result(summary, rewards)
                        })
                    is Screen.Result -> ResultScreen(s.summary, s.rewards, save, go)
                }
            }

            AnimatedVisibility(reveal != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                reveal?.let { RewardRevealOverlay(it, save.bolts, save.prisms) { reveal = null } }
            }
            // The debug menu hides behind a small "D" in the corner of every menu screen.
            if (screen !is Screen.Match && capsule == null && reveal == null) {
                io.github.projectwip.ui.screens.DebugButton(Modifier.align(Alignment.BottomStart)) { debugMenu = true }
            }
            if (debugMenu) io.github.projectwip.ui.screens.DebugMenu(save, repo) { debugMenu = false }
            // On top of everything: the loading screen, then (if a newer release exists) the update screen.
            if (!booting) update?.let { io.github.projectwip.ui.screens.UpdateScreen(it) { update = null } }
            AnimatedVisibility(booting, enter = fadeIn(tween(0)), exit = fadeOut(tween(250))) {
                io.github.projectwip.ui.screens.LoadingScreen(bootProgress, bootStatus)
            }
            AnimatedVisibility(capsule != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                capsule?.let { CapsuleOpenOverlay(it, if (save.settings.debugInfiniteCapsules) Int.MAX_VALUE else save.capsules, save.bolts, save.prisms, onNext = openCapsule, onDone = { capsule = null }) }
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

fun startMatchConfig(save: io.github.projectwip.data.SaveData): MatchConfig {
    val p = save.progress(save.selectedFighter)
    return MatchConfig(save.selectedFighter, p.level, p.skin, save.settings.playerName, save.settings.botDifficulty, mode = save.selectedMode)
}

fun rewardLabel(r: Reward): String = when (r) {
    is Reward.Bolts -> "+${r.amount} Bolts"
    is Reward.Prisms -> "+${r.amount} Prisms"
    is Reward.UnlockFighter -> "${Balance.fighter(r.fighter).name} unlocked!"
    is Reward.SkinReward -> "${Balance.fighter(r.fighter).skins[r.skinIndex].name} colorway"
    is Reward.Bundle -> r.items.joinToString(", ") { rewardLabel(it) }
}

@Composable
fun RewardVisual(r: Reward, modifier: Modifier = Modifier) {
    when (r) {
        is Reward.Bolts -> GameIcon(IconKind.BOLT, modifier)
        is Reward.Prisms -> GameIcon(IconKind.PRISM, modifier)
        is Reward.UnlockFighter -> FighterView(Balance.fighter(r.fighter), 0, modifier, pedestal = false)
        is Reward.SkinReward -> FighterView(Balance.fighter(r.fighter), r.skinIndex, modifier, pedestal = false)
        is Reward.Bundle -> GameIcon(IconKind.GIFT, modifier)
    }
}

@Composable
private fun RewardRevealOverlay(r: RewardReveal, boltsNow: Int, prismsNow: Int, onDismiss: () -> Unit) {
    Box(
        // Swallows taps so nothing underneath is pressed while the reward plays out.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        RewardShowcase(r.title, Palette.Gold, r.reward, boltsNow, prismsNow) {
            ChunkyButton(onDismiss, Modifier.size(200.dp, 60.dp), ButtonStyle.GREEN) { GameText("AWESOME", Type.Heading) }
        }
    }
}

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
