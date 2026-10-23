package io.github.projectwip.ui.screens

import io.github.projectwip.ui.bleed
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import io.github.projectwip.ui.enterFrom
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.GameMode
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.Progression
import io.github.projectwip.data.SaveData
import io.github.projectwip.render3d.LobbyShot
import io.github.projectwip.sim.Arena
import io.github.projectwip.sim.Arenas
import io.github.projectwip.sim.Tile
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.CurrencyPill
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LobbyShotEffect
import io.github.projectwip.ui.LobbyVignette
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.ProgressBar
import io.github.projectwip.ui.RewardReveal
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.lobbyAnchor
import io.github.projectwip.ui.startMatchConfig

@Composable
fun HomeScreen(
    save: SaveData, repo: GameRepository, go: (Screen) -> Unit,
    @Suppress("UNUSED_PARAMETER") showReward: (RewardReveal) -> Unit, openCapsule: () -> Unit,
) {
    val ui = LocalUi.current
    val serverStatus = io.github.projectwip.ui.LocalServer.current?.status?.collectAsState()?.value
    val online = serverStatus?.online == true
    val prog = save.progress(save.selectedFighter)
    val canUpgradeAny = Balance.fighters.any { Progression.canUpgrade(save, it.id) }
    val claimable = Progression.claimable(save).size
    val giftReady = Progression.dailyGiftAvailable(save, repo.today)
    // The player's place among the real accounts on the server; unknown while offline.
    val rank = serverStatus?.account?.takeIf { serverStatus.online }?.rank
    var picking by remember { mutableStateOf(false) }

    LobbyShotEffect(LobbyShot.HOME, save.selectedFighter, prog.skin)

    Box(Modifier.fillMaxSize()) {
        LobbyVignette()
        Row(Modifier.fillMaxSize()) {
            // ---------------- the rail: every place to go, down the left edge
            NavRail {
                Spacer(Modifier.weight(0.4f))
                RailItem("SHOP", IconKind.SHOP, if (giftReady) "FREE" else null, Palette.GreenDeep, 0) { go(Screen.Shop) }
                RailItem("FIGHTERS", IconKind.FIGHTERS, if (canUpgradeAny) "UP" else null, Palette.GreenDeep, 1) { go(Screen.Fighters()) }
                RailItem("RANKS", IconKind.RANK, rank?.let { "#$it" }, Palette.CyanDeep, 2) { go(Screen.Leaderboard) }
                RailItem("NEWS", IconKind.NEWS, null, Palette.Red, 3) { go(Screen.News) }
                Spacer(Modifier.weight(1f))
                RailItem("SETTINGS", IconKind.GEAR, null, Palette.Red, 4) { go(Screen.Settings) }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                // ---------------- top bar: Cups and the Cup Track on the left, the wallet on the right
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp, top = 10.dp).enterFrom(60, dy = (-12).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The Cups panel takes whatever room the wallet leaves, so nothing is ever squeezed.
                    Box(Modifier.weight(1f)) { ProfileAndCups(save, claimable) { go(Screen.CupTrack) } }
                    Spacer(Modifier.width(12.dp))
                    CurrencyPill(IconKind.BOLT, save.bolts)
                    Spacer(Modifier.width(10.dp))
                    CurrencyPill(IconKind.PRISM, save.prisms, onClick = { go(Screen.Shop) })
                }

                Row(Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 18.dp, bottom = 14.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        // ---------------- hero (the 3D fighter stands here; this space is see-through)
                        Box(Modifier.weight(1f).fillMaxWidth().lobbyAnchor(), contentAlignment = Alignment.BottomCenter) {
                            NamePlate(save, Modifier.enterFrom(200)) { go(Screen.Fighters(save.selectedFighter)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        // ---------------- bottom left: the Spark Road
                        RoadButton(save, Modifier.width(if (ui.wide) 240.dp else 200.dp).enterFrom(260, dx = (-16).dp, dy = 0.dp)) { go(Screen.Road) }
                    }
                    Spacer(Modifier.width(12.dp))

                    // ---------------- mode + play
                    Column(
                        Modifier.width(if (ui.wide) 330.dp else 280.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.End,
                    ) {
                        val dropsOnly = save.settings.glitchDropsOnly
                        // Boss Mode or 3v3 with one or two real players, by team code.
                        if (!dropsOnly && !io.github.projectwip.ui.LocalOfflineMode.current) {
                            ChunkyButton({ go(Screen.Team) }, Modifier.fillMaxWidth().height(48.dp).enterFrom(120, dx = 24.dp, dy = 0.dp), ButtonStyle.GLASS, lip = 4.dp, cut = 10.dp) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    GameIcon(IconKind.FIGHTERS, Modifier.size(26.dp))
                                    Spacer(Modifier.width(10.dp))
                                    GameText("TEAM UP", Type.Heading, color = Palette.Positive, outline = 2.dp)
                                    Spacer(Modifier.weight(1f))
                                    PlainText("PLAY WITH FRIENDS", Type.Eyebrow, color = Palette.TextDim, maxLines = 1)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        CapsuleButton(
                            if (save.settings.debugInfiniteCapsules) Int.MAX_VALUE else save.capsules, Progression.capsulesLeftToday(save, repo.today),
                            online || io.github.projectwip.ui.LocalOfflineMode.current, dropsOnly, Modifier.enterFrom(170, dx = 24.dp, dy = 0.dp), openCapsule,
                        )
                        // Arena Boxes only: no fights, just drops.
                        if (!dropsOnly) {
                            Spacer(Modifier.height(10.dp))
                            ModeChip(save.selectedMode, save.settings.botDifficulty, Modifier.enterFrom(220, dx = 24.dp, dy = 0.dp)) { picking = true }
                            Spacer(Modifier.height(12.dp))
                            PlayButton(Modifier.enterFrom(280, dx = 24.dp, dy = 0.dp)) { go(Screen.Match(startMatchConfig(save))) }
                        }
                    }
                }
            }
        }

        // Online, or the offline profile: under the Cups panel. It steps aside for the mode picker.
        if (!picking) {
            val offline = !online || io.github.projectwip.ui.LocalOfflineMode.current
            // Offline, say why: the player's own choice, or what went wrong reaching the server.
            val why = when {
                !offline -> ""
                save.settings.forceOffline -> " · you turned on Offline mode in Settings"
                else -> io.github.projectwip.ui.LocalServer.current?.unreachable?.takeIf { it.isNotBlank() }?.let { " · can't reach $it" }.orEmpty()
            }
            PlainText(
                if (!offline) "● ONLINE" else "● OFFLINE · CHAOS MODE$why", Type.Small,
                Modifier.align(Alignment.TopStart).padding(start = if (ui.roomy) 108.dp else 92.dp, top = 70.dp),
                color = if (!offline) Palette.Positive else Palette.TextDim, maxLines = 1,
            )
        }
        // The server's notice, across the top. It steps aside for the mode picker.
        val notice = serverStatus?.notice.orEmpty()
        if (online && notice.isNotBlank() && !picking) {
            Badge(notice.take(90), Modifier.align(Alignment.TopCenter).padding(top = 74.dp), color = Palette.CyanDeep)
        }

        androidx.activity.compose.BackHandler(enabled = picking) { picking = false }
        AnimatedVisibility(picking, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            ModePicker(save, repo) { picking = false }
        }
    }
}

// ---------------------------------------------------------------------------------------------- rail

/** A strip of dark glass down the left edge holding the [RailItem]s, with a hairline of rim light along its edge. */
@Composable
private fun NavRail(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    Column(
        Modifier.fillMaxHeight().width(if (ui.roomy) 92.dp else 78.dp)
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(Color(0xFA050E18), Color(0xF00B1C2E))))
                drawRect(Brush.verticalGradient(listOf(Palette.Rim.copy(alpha = 0.15f), Color.Transparent), 0f, size.height * 0.35f))
                drawLine(Brush.verticalGradient(listOf(Color.Transparent, Palette.Rim.copy(alpha = 0.6f), Color.Transparent)), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1.5.dp.toPx())
            }
            .padding(vertical = if (ui.roomy) 14.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (ui.roomy) 6.dp else 2.dp),
        content = content,
    )
}

/** One place on the rail: its icon in a pool of light over a tracked label, with a [badge] at the corner. */
@Composable
private fun RailItem(label: String, icon: IconKind, badge: String?, badgeColor: Color, order: Int, onClick: () -> Unit) {
    val ui = LocalUi.current
    val sfx = io.github.projectwip.ui.LocalSfx.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(80), label = "rail")
    Box(Modifier.enterFrom(order * 50, dx = (-18).dp, dy = 0.dp)) {
        Column(
            Modifier.width(if (ui.roomy) 80.dp else 70.dp).height(if (ui.roomy) 78.dp else 62.dp)
                .graphicsLayer { val k = 1f - 0.06f * press; scaleX = k; scaleY = k }
                .clickable(interaction, null) { sfx?.play(Sound.TAP); onClick() }
                .drawBehind {
                    if (press > 0f) drawRoundRect(Palette.Rim.copy(alpha = 0.16f * press), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(if (ui.roomy) 52.dp else 42.dp)) {
                    drawCircle(Brush.radialGradient(listOf(Palette.Rim.copy(alpha = 0.3f), Palette.Rim.copy(alpha = 0.06f), Color.Transparent)))
                }
                GameIcon(icon, Modifier.size(if (ui.roomy) 40.dp else 32.dp))
            }
            Spacer(Modifier.height(2.dp))
            PlainText(label, Type.Eyebrow.copy(letterSpacing = 1.2.sp), color = Color.White, maxLines = 1)
        }
        if (badge != null) {
            val pulse by rememberInfiniteTransition(label = "badge").animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
            Badge(badge, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = 0.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, color = badgeColor, small = true)
        }
    }
}

// ---------------------------------------------------------------------------------------------- top bar

@Composable
private fun ProfileAndCups(save: SaveData, claimable: Int, onCups: () -> Unit) {
    val next = CupTrack.nextMilestone(save.bestCups)
    val prev = CupTrack.previousMilestoneCups(save.bestCups)
    val frac = if (next == null) 1f else (save.bestCups - prev).toFloat() / (next.cups - prev)
    Box {
        ChunkyButton(onCups, Modifier.height(56.dp).widthIn(max = 360.dp).fillMaxWidth(), ButtonStyle.GLASS, lip = 4.dp, cut = 12.dp) {
            Row(Modifier.padding(start = 62.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(104.dp)) {
                    GameText(save.settings.playerName, Type.Label, outline = 2.dp)
                    PlainText("${save.victories} WINS", Type.Eyebrow, color = Palette.TextDim, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        GameText("%,d".format(save.cups), Type.Heading, color = Palette.Gold, outline = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        PlainText(next?.let { "NEXT ${it.cups}" } ?: "TRACK DONE", Type.Eyebrow, color = Palette.TextDim, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    ProgressBar(frac, Modifier.fillMaxWidth().height(8.dp))
                }
            }
        }
        GameIcon(IconKind.CUP, Modifier.size(60.dp).offset(x = (-4).dp, y = (-3).dp))
        if (claimable > 0) {
            val pulse by rememberInfiniteTransition(label = "cupBadge").animateFloat(1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
            Badge(claimable.toString(), Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, color = Palette.Red)
        }
    }
}

/** The fighter on show, under their feet: level, name and what they are, on a line of light. */
@Composable
private fun NamePlate(save: SaveData, modifier: Modifier, onClick: () -> Unit) {
    val def = Balance.fighter(save.selectedFighter)
    val p = save.progress(save.selectedFighter)
    val canUp = Progression.canUpgrade(save, def.id)
    Column(
        modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The level, in a hexagon.
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    fun hex(r: Float) = androidx.compose.ui.graphics.Path().apply {
                        for (i in 0 until 6) {
                            val a = Math.PI / 6 + i * Math.PI / 3
                            val x = center.x + (Math.cos(a) * r).toFloat(); val y = center.y + (Math.sin(a) * r).toFloat()
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawPath(hex(size.minDimension / 2), Palette.Ink)
                    drawPath(hex(size.minDimension / 2 - 2.dp.toPx()), Brush.verticalGradient(listOf(Palette.Orange, Palette.OrangeDeep)))
                    drawPath(hex(size.minDimension / 2 - 4.dp.toPx()), Color.White.copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                GameText(p.level.toString(), Type.Heading, outline = 2.dp)
            }
            Spacer(Modifier.width(10.dp))
            GameText(def.name.uppercase(), Type.Display.copy(letterSpacing = 3.sp), outline = 3.dp)
            if (canUp) {
                Spacer(Modifier.width(10.dp))
                Badge("UPGRADE", color = Palette.GreenDeep)
            }
        }
        Canvas(Modifier.size(260.dp, 2.dp)) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Rim, Color.Transparent)))
        }
        Spacer(Modifier.height(3.dp))
        PlainText("${def.title} · ${def.role}".uppercase(), Type.Eyebrow, color = Palette.TextDim, maxLines = 1)
    }
}

/**
 * The way onto the Spark Road: the fighter the Credits are filling (still a silhouette) and how far along it is. The
 * moment the bar is full the fighter is unlocked. With every fighter unlocked it says the road is finished.
 */
@Composable
private fun RoadButton(save: SaveData, modifier: Modifier, onClick: () -> Unit) {
    val next = io.github.projectwip.data.SparkRoad.next(save)
    Box(modifier) {
        ChunkyButton(onClick, Modifier.fillMaxWidth().height(58.dp), ButtonStyle.GLASS, cut = 12.dp, lip = 4.dp, sound = Sound.UI_OPEN) {
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (next != null) io.github.projectwip.ui.FighterView(Balance.fighter(next.fighter), 0, Modifier.size(42.dp), pedestal = false, locked = true)
                else GameIcon(IconKind.CHECK, Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    PlainText("SPARK ROAD", Type.Eyebrow, color = Palette.Green, maxLines = 1)
                    if (next != null) {
                        PlainText("${Balance.fighter(next.fighter).name} · ${"%,d".format(minOf(save.credits, next.cost))} / ${"%,d".format(next.cost)}", Type.Small, color = Color.White, maxLines = 1)
                        ProgressBar(save.credits.toFloat() / next.cost, Modifier.fillMaxWidth().height(7.dp), Palette.Green, Palette.GreenDeep)
                    } else {
                        PlainText("Every fighter unlocked", Type.Small, color = Color.White, maxLines = 1)
                        ProgressBar(1f, Modifier.fillMaxWidth().height(7.dp))
                    }
                }
            }
        }
    }
}

/** Arena Boxes waiting to be opened, or how to earn the next one. [canOpen]: the server is there to open them, or the game is on its offline profile, which opens its own. */
@Composable
private fun CapsuleButton(count: Int, leftToday: Int, canOpen: Boolean, big: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    Box(modifier) {
        ChunkyButton(onOpen, Modifier.fillMaxWidth().height(if (big) 150.dp else 62.dp), ButtonStyle.CYAN, enabled = count > 0, cut = 12.dp, lip = 4.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // A drop waiting to be opened glitches, like the real thing does when it is.
                if (count > 0) io.github.projectwip.ui.GlitchIcon(IconKind.CAPSULE, Modifier.size(if (big) 84.dp else 44.dp), tint = Palette.Gold)
                else GameIcon(IconKind.CAPSULE, Modifier.size(44.dp), tint = Palette.Grey)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    GameText(if (count > 0) "OPEN BOX" else "ARENA BOXES", Type.Heading, outline = 2.dp)
                    PlainText(
                        when {
                            !canOpen -> if (count > 0) "Opens when you're back online" else "Earned and opened online"
                            count > 0 -> "Tap to see what is inside"
                            leftToday > 0 -> "Win or top 4 earns one · $leftToday left today"
                            else -> "Today's are all earned · more tomorrow"
                        },
                        Type.Small, color = Color.White, maxLines = 1,
                    )
                }
            }
        }
        if (count > 0) {
            val pulse by rememberInfiniteTransition(label = "drops").animateFloat(1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
            Badge(if (count > 999) "∞" else count.toString(), Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).graphicsLayer { scaleX = pulse; scaleY = pulse })
        }
    }
}

// ---------------------------------------------------------------------------------------------- mode

fun modeIcon(m: GameMode) = when (m) { GameMode.LAST_SPARK -> IconKind.SPARK; GameMode.KNOCKOUT_RUSH -> IconKind.SWORDS; GameMode.BOSS -> IconKind.SKULL; GameMode.TRAINING -> IconKind.FIGHTERS; GameMode.DUEL -> IconKind.SWORDS }

fun arenaFor(m: GameMode): Arena = when (m) { GameMode.LAST_SPARK -> Arenas.staticCanyon(); GameMode.KNOCKOUT_RUSH -> Arenas.foundryYard(); GameMode.BOSS -> Arenas.provingGround(); GameMode.TRAINING -> Arenas.trainingArea(); GameMode.DUEL -> Arenas.provingGround() }

@Composable
private fun ModeChip(mode: GameMode, d: BotDifficulty?, modifier: Modifier, onClick: () -> Unit) {
    ChunkyButton(onClick, modifier.fillMaxWidth().height(84.dp), ButtonStyle.GLASS, cut = 14.dp, sound = Sound.UI_OPEN) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The mode's icon on a tile of its own.
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2E6A9E), Color(0xFF102A44))), cornerRadius = r)
                    drawRoundRect(Palette.Gold.copy(alpha = 0.7f), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    drawCircle(Brush.radialGradient(listOf(Palette.Gold.copy(alpha = 0.3f), Color.Transparent)), size.minDimension * 0.5f)
                }
                GameIcon(modeIcon(mode), Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                PlainText("GAME MODE", Type.Eyebrow, maxLines = 1)
                GameText(mode.title.uppercase(), Type.Heading, color = Palette.Gold, outline = 2.dp)
                if (mode == GameMode.DUEL) PlainText("Against a real player", Type.Small, color = Palette.Positive, maxLines = 1)
                else if (d != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    PlainText("${mode.tagline.substringBefore(" ·")} · Bots ", Type.Small, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    PlainText(d.label, Type.Label, color = difficultyColor(d), maxLines = 1)
                }
                else PlainText(mode.tagline, Type.Small, maxLines = 1)
            }
            GameIcon(IconKind.BACK, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f; alpha = 0.8f })
        }
    }
}

@Composable
private fun ModePicker(save: SaveData, repo: GameRepository, onClose: () -> Unit) {
    val ui = LocalUi.current
    val ask = io.github.projectwip.ui.LocalServerCall.current
    Box(
        Modifier.bleed().fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 1180.dp).padding(18.dp).clickable(remember { MutableInteractionSource() }, null) { }, cut = 22.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GameText("CHOOSE A MODE", Type.Title, outline = 3.5.dp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (m in GameMode.entries) {
                        // With Boss Mode picked the cards are a little shorter, to leave room for the row of bosses underneath.
                        ModeCard(m, m == save.selectedMode, Modifier.weight(1f), showMap = ui.roomy, short = save.selectedMode == GameMode.BOSS) { repo.selectMode(m) }
                    }
                }
                if (save.selectedMode == GameMode.BOSS) {
                    // Boss Mode: which boss to fight, or leave it to chance.
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameText("BOSS", Type.Heading, outline = 2.5.dp)
                        Spacer(Modifier.width(6.dp))
                        BossChoice("RANDOM", "Any of them", null, save.selectedBoss == null, Modifier.weight(1f)) { repo.selectBoss(null) }
                        for (b in Balance.bosses) BossChoice(b.name.uppercase(), b.title, b, save.selectedBoss == b.boss, Modifier.weight(1f)) { repo.selectBoss(b.boss) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameText("BOTS", Type.Heading, outline = 2.5.dp)
                    Spacer(Modifier.width(6.dp))
                    for (d in BotDifficulty.entries) {
                        val sel = d == save.settings.botDifficulty
                        // The server has to agree; its answer (the approved difficulty) is what gets shown.
                        ChunkyButton({ ask({ setDifficulty(d) }) }, Modifier.size(118.dp, 50.dp),
                            if (sel) ButtonStyle.ORANGE else ButtonStyle.PURPLE, lip = 4.dp, sound = Sound.UI_SELECT) {
                            GameText(d.label.uppercase(), Type.Label, color = if (sel) Color.White else difficultyColor(d), outline = 2.dp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    ChunkyButton(onClose, Modifier.size(130.dp, 54.dp), ButtonStyle.GREEN) { GameText("DONE", Type.Heading) }
                }
            }
        }
    }
}

/** One boss to pick in the mode picker: its portrait (a skull for "random"), name and what it is. */
@Composable
private fun BossChoice(name: String, title: String, def: io.github.projectwip.data.FighterDef?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ChunkyButton(onClick, modifier.height(56.dp), if (selected) ButtonStyle.GOLD else ButtonStyle.PURPLE, lip = 4.dp, cut = 12.dp, sound = Sound.UI_SELECT) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                if (def != null) io.github.projectwip.ui.FighterView(def, 0, Modifier.fillMaxSize(), pedestal = false)
                else GameIcon(IconKind.SKULL, Modifier.size(32.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column {
                GameText(name, Type.Label, outline = 2.dp)
                PlainText(title, Type.Small, color = if (selected) Color.White else Palette.TextDim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ModeCard(m: GameMode, selected: Boolean, modifier: Modifier, showMap: Boolean, short: Boolean = false, onClick: () -> Unit) {
    val arena = remember(m) { arenaFor(m) }
    ChunkyButton(onClick, modifier.height(if (!showMap) 190.dp else if (short) 268.dp else 330.dp), if (selected) ButtonStyle.GOLD else ButtonStyle.PURPLE, cut = 18.dp, sound = Sound.UI_SELECT) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(modeIcon(m), Modifier.size(22.dp))
                Spacer(Modifier.width(5.dp))
                GameText(m.title.uppercase(), Type.Label.copy(letterSpacing = 0.sp), outline = 2.dp)
            }
            Spacer(Modifier.height(2.dp))
            PlainText(m.tagline, Type.Body, color = if (selected) Color.White else Palette.TextDim, align = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            if (showMap) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Minimap(arena, Modifier.fillMaxHeight().aspectRatio(arena.width.toFloat() / arena.height))
                }
                Spacer(Modifier.height(6.dp))
            }
            PlainText("${arena.name} · ${m.players} fighters", Type.Small, color = if (selected) Color.White else Palette.TextDim)
            PlainText(
                when (m) {
                    GameMode.LAST_SPARK -> "Break crates for Power Cells. Outlast the Static Storm. The higher you finish, the more Cups."
                    GameMode.KNOCKOUT_RUSH -> "Respawns on. Your team starts at the bottom. Win to earn Cups."
                    GameMode.BOSS -> "One of three bosses, each with moves of its own: rockets, sweeping beams, charges. Watch the marked ground. Knock it out to win; you have unlimited lives. Win to earn Cups."
                    GameMode.DUEL -> "Against one real player on this server, each on their own device. First to 3 knockouts, played for Cups. Leaving a match counts as a defeat."
                    GameMode.TRAINING -> "Four dummies, a swarm of minis and a boss that never move or attack, plus one sentry gun that does shoot. No timer, no rewards: leave whenever you like."
                },
                Type.Small, color = if (selected) Color.White else Palette.TextDim, align = TextAlign.Center, maxLines = 5,
            )
        }
    }
}

fun difficultyColor(d: BotDifficulty): Color = when (d) {
    BotDifficulty.EASY -> Palette.Green
    BotDifficulty.NORMAL -> Palette.Cyan
    BotDifficulty.HARD -> Palette.Orange
    BotDifficulty.ELITE -> Palette.Red
}

@Composable
private fun PlayButton(modifier: Modifier, onClick: () -> Unit) {
    val t by rememberInfiniteTransition(label = "play").animateFloat(0f, 1f, infiniteRepeatable(tween(1200)), label = "g")
    Box(modifier, contentAlignment = Alignment.Center) {
        // A soft orange light pulsing under the button.
        Canvas(Modifier.matchParentSize()) {
            val k = (kotlin.math.sin(t * 2 * Math.PI).toFloat() + 1f) / 2f
            drawRect(Brush.radialGradient(listOf(Palette.Orange.copy(alpha = 0.22f + 0.16f * k), Color.Transparent), center, size.width * 0.62f), Offset(-size.width * 0.1f, -size.height * 0.3f), Size(size.width * 1.2f, size.height * 1.6f))
        }
        ChunkyButton(onClick, Modifier.fillMaxWidth().height(88.dp), ButtonStyle.ORANGE, cut = 18.dp, lip = 7.dp, sheen = true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                GameText("PLAY", Type.Display.copy(fontSize = Type.Display.fontSize * 1.3f, letterSpacing = 6.sp), outline = 3.dp)
                Spacer(Modifier.weight(1f))
                // Three chevrons, lighting up one after another towards the arena.
                Canvas(Modifier.size(54.dp, 30.dp)) {
                    for (i in 0 until 3) {
                        val lit = ((t * 3f - i) % 3f + 3f) % 3f
                        val a = 0.35f + 0.65f * (1f - (lit.coerceIn(0f, 1f)))
                        val x = i * size.width / 3.2f
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(x, 0f); lineTo(x + size.width * 0.18f, 0f); lineTo(x + size.width * 0.38f, size.height / 2)
                            lineTo(x + size.width * 0.18f, size.height); lineTo(x, size.height); lineTo(x + size.width * 0.2f, size.height / 2); close()
                        }
                        drawPath(p, Color.White.copy(alpha = a))
                        drawPath(p, Palette.Ink.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** A live thumbnail of an arena layout. */
@Composable
fun Minimap(arena: Arena, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = minOf(size.width / arena.width, size.height / arena.height)
        drawRect(Palette.Ink)
        for (y in 0 until arena.height) for (x in 0 until arena.width) {
            val c = when (arena[x, y]) {
                Tile.WALL -> Color(0xFF78A1C4)
                Tile.THICKET -> Color(0xFF3FAE5C)
                Tile.WATER -> Color(0xFF2CA0DE)
                Tile.CRATE -> Color(0xFFE08A2E)
                Tile.FLOOR -> Color(0xFFDEC492)
            }
            drawRect(c, Offset(x * t, y * t), Size(t + 0.5f, t + 0.5f))
        }
        for ((team, spawns) in arena.spawns.withIndex()) for (s in spawns) {
            drawCircle(Palette.Ink, t * 0.8f, Offset(s.x * t, s.y * t))
            drawCircle(if (team == 0) Palette.Ally else Palette.Enemy, t * 0.6f, Offset(s.x * t, s.y * t))
        }
        for (s in arena.ffaSpawns) {
            drawCircle(Palette.Ink, t * 0.8f, Offset(s.x * t, s.y * t))
            drawCircle(Palette.Gold, t * 0.6f, Offset(s.x * t, s.y * t))
        }
    }
}
