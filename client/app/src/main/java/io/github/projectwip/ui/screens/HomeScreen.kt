package io.github.projectwip.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
    var picking by remember { mutableStateOf(false) }

    LobbyShotEffect(LobbyShot.HOME, save.selectedFighter, prog.skin)

    Box(Modifier.fillMaxSize()) {
        LobbyVignette()
        Column(Modifier.fillMaxSize()) {
            // ---------------- top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileAndCups(save, claimable) { go(Screen.CupTrack) }
                Spacer(Modifier.width(10.dp))
                // The player's place among the real accounts on the server; unknown while offline.
                val status = io.github.projectwip.ui.LocalServer.current?.status?.collectAsState()?.value
                val rank = status?.account?.takeIf { status.online }?.rank?.toString() ?: "?"
                ChunkyButton({ go(Screen.Leaderboard) }, Modifier.size(104.dp, 58.dp), ButtonStyle.GLASS, lip = 4.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GameText("#$rank", Type.Heading, color = Palette.Gold, outline = 2.5.dp)
                        PlainText("LEADERBOARD", Type.Small, color = Color.White, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                CurrencyPill(IconKind.BOLT, save.bolts)
                Spacer(Modifier.width(10.dp))
                CurrencyPill(IconKind.PRISM, save.prisms, onClick = { go(Screen.Shop) })
                Spacer(Modifier.width(12.dp))
                ChunkyButton({ go(Screen.Settings) }, Modifier.size(52.dp, 50.dp), ButtonStyle.GLASS) {
                    GameIcon(IconKind.GEAR, Modifier.size(28.dp))
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                // ---------------- left rail
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    NavTile("SHOP", IconKind.SHOP, if (giftReady) "FREE" else null, Palette.Green) { go(Screen.Shop) }
                    NavTile("FIGHTERS", IconKind.FIGHTERS, if (canUpgradeAny) "UP" else null, Palette.Green) { go(Screen.Fighters()) }
                    NavTile("CUP TRACK", IconKind.TRACK, if (claimable > 0) claimable.toString() else null, Palette.Red) { go(Screen.CupTrack) }
                }

                // ---------------- hero (the 3D fighter stands here; this column is see-through)
                Box(Modifier.weight(1f).fillMaxHeight().lobbyAnchor(), contentAlignment = Alignment.BottomCenter) {
                    NamePlate(save) { go(Screen.Fighters(save.selectedFighter)) }
                }

                // ---------------- mode + play
                Column(
                    Modifier.width(if (ui.wide) 330.dp else 280.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.End,
                ) {
                    CapsuleButton(if (save.settings.debugInfiniteCapsules) Int.MAX_VALUE else save.capsules, Progression.capsulesLeftToday(save, repo.today), online, openCapsule)
                    Spacer(Modifier.height(10.dp))
                    ModeChip(save.selectedMode, save.settings.botDifficulty) { picking = true }
                    Spacer(Modifier.height(12.dp))
                    PlayButton { go(Screen.Match(startMatchConfig(save))) }
                }
            }
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

// ---------------------------------------------------------------------------------------------- top bar

@Composable
private fun ProfileAndCups(save: SaveData, claimable: Int, onCups: () -> Unit) {
    val next = CupTrack.nextMilestone(save.bestCups)
    val prev = CupTrack.previousMilestoneCups(save.bestCups)
    val frac = if (next == null) 1f else (save.bestCups - prev).toFloat() / (next.cups - prev)
    Box {
        ChunkyButton(onCups, Modifier.height(58.dp).widthIn(min = 300.dp), ButtonStyle.GLASS, lip = 4.dp) {
            Row(Modifier.padding(start = 66.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(110.dp)) {
                    GameText(save.settings.playerName, Type.Label, outline = 2.dp)
                    PlainText("${save.victories} wins", Type.Small)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.width(130.dp)) {
                    GameText("%,d".format(save.cups), Type.Heading, color = Palette.Gold, outline = 2.5.dp)
                    ProgressBar(frac, Modifier.fillMaxWidth().height(9.dp))
                    PlainText(next?.let { "Next reward: ${it.cups}" } ?: "Track complete!", Type.Small)
                }
            }
        }
        GameIcon(IconKind.CUP, Modifier.size(66.dp).offset(x = (-6).dp, y = (-4).dp))
        if (claimable > 0) Badge(claimable.toString(), Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
    }
}

@Composable
private fun NavTile(label: String, icon: IconKind, badge: String?, badgeColor: Color, onClick: () -> Unit) {
    val ui = LocalUi.current
    val w = if (ui.roomy) 118.dp else 100.dp
    val h = if (ui.roomy) 100.dp else 80.dp
    Box {
        ChunkyButton(onClick, Modifier.size(w, h), ButtonStyle.GLASS) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GameIcon(icon, Modifier.size(if (ui.roomy) 46.dp else 36.dp))
                Spacer(Modifier.height(4.dp))
                GameText(label, Type.Label, outline = 2.5.dp)
            }
        }
        if (badge != null) {
            val pulse by rememberInfiniteTransition(label = "badge").animateFloat(1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
            Badge(badge, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, color = badgeColor)
        }
    }
}

@Composable
private fun NamePlate(save: SaveData, onClick: () -> Unit) {
    val def = Balance.fighter(save.selectedFighter)
    val p = save.progress(save.selectedFighter)
    val canUp = Progression.canUpgrade(save, def.id)
    Box(Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
        Panel(color = Color(0xD8392A8C), colorBottom = Color(0xE01A1150), cut = 14.dp) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Palette.Ink)
                        drawCircle(Palette.Orange, size.minDimension / 2 - 3.dp.toPx())
                    }
                    GameText(p.level.toString(), Type.Title, outline = 2.5.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    GameText(def.name.uppercase(), Type.Title, outline = 3.dp)
                    PlainText("${def.title} · ${def.role}", Type.Small)
                }
                if (canUp) {
                    Spacer(Modifier.width(12.dp))
                    Badge("UPGRADE!", color = Palette.GreenDeep)
                }
            }
        }
    }
}

/** Spark Capsules waiting to be opened, or how to earn the next one. The server earns and opens them, so offline they wait. */
@Composable
private fun CapsuleButton(count: Int, leftToday: Int, online: Boolean, onOpen: () -> Unit) {
    Box {
        ChunkyButton(onOpen, Modifier.fillMaxWidth().height(62.dp), ButtonStyle.CYAN, enabled = count > 0, cut = 14.dp, lip = 4.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GameIcon(IconKind.CAPSULE, Modifier.size(44.dp), tint = if (count > 0) Palette.Gold else Palette.Grey)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    GameText(if (count > 0) "OPEN DROP" else "SPARK DROPS", Type.Heading, outline = 2.5.dp)
                    PlainText(
                        when {
                            !online -> if (count > 0) "Opens when you're back online" else "Earned and opened online"
                            count > 0 -> "Tap it to charge it up"
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

fun modeIcon(m: GameMode) = when (m) { GameMode.LAST_SPARK -> IconKind.SPARK; GameMode.KNOCKOUT_RUSH -> IconKind.SWORDS; GameMode.BOSS -> IconKind.SKULL; GameMode.TRAINING -> IconKind.FIGHTERS }

fun arenaFor(m: GameMode): Arena = when (m) { GameMode.LAST_SPARK -> Arenas.staticCanyon(); GameMode.KNOCKOUT_RUSH -> Arenas.foundryYard(); GameMode.BOSS -> Arenas.provingGround(); GameMode.TRAINING -> Arenas.trainingArea() }

@Composable
private fun ModeChip(mode: GameMode, d: BotDifficulty?, onClick: () -> Unit) {
    ChunkyButton(onClick, Modifier.fillMaxWidth().height(84.dp), ButtonStyle.GLASS, cut = 16.dp, sound = Sound.UI_OPEN) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GameIcon(modeIcon(mode), Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                GameText(mode.title.uppercase(), Type.Heading, color = Palette.Gold, outline = 2.5.dp)
                PlainText(mode.tagline, Type.Small, maxLines = 1)
                if (d != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    PlainText("Bots: ", Type.Small)
                    PlainText(d.label, Type.Label, color = difficultyColor(d))
                }
            }
            GameText("›", Type.Display, outline = 3.dp)
        }
    }
}

@Composable
private fun ModePicker(save: SaveData, repo: GameRepository, onClose: () -> Unit) {
    val ui = LocalUi.current
    val ask = io.github.projectwip.ui.LocalServerCall.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 1180.dp).padding(18.dp).clickable(remember { MutableInteractionSource() }, null) { }, cut = 22.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GameText("CHOOSE A MODE", Type.Title, outline = 3.5.dp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (m in GameMode.entries) {
                        ModeCard(m, m == save.selectedMode, Modifier.weight(1f), showMap = ui.roomy) { repo.selectMode(m) }
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

@Composable
private fun ModeCard(m: GameMode, selected: Boolean, modifier: Modifier, showMap: Boolean, onClick: () -> Unit) {
    val arena = remember(m) { arenaFor(m) }
    ChunkyButton(onClick, modifier.height(if (showMap) 330.dp else 190.dp), if (selected) ButtonStyle.GOLD else ButtonStyle.PURPLE, cut = 18.dp, sound = Sound.UI_SELECT) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(modeIcon(m), Modifier.size(26.dp))
                Spacer(Modifier.width(6.dp))
                GameText(m.title.uppercase(), Type.Label, outline = 2.5.dp)
            }
            PlainText(m.tagline, Type.Label, color = if (selected) Color.White else Palette.TextDim, align = TextAlign.Center)
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
                    GameMode.BOSS -> "A giant version of a random fighter. Knock it out to win; you have unlimited lives. Its strength never changes. Pays Bolts only."
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
private fun PlayButton(onClick: () -> Unit) {
    val glow by rememberInfiniteTransition(label = "play").animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "g")
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(300.dp, 110.dp)) {
            drawOval(Palette.Orange.copy(alpha = 0.18f + glow * 0.2f), Offset(0f, 0f), Size(size.width, size.height))
        }
        ChunkyButton(onClick, Modifier.fillMaxWidth().height(90.dp), ButtonStyle.ORANGE, cut = 20.dp, lip = 7.dp, sheen = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(IconKind.PLAY, Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                GameText("PLAY", Type.Display.copy(fontSize = Type.Display.fontSize * 1.2f), outline = 4.dp)
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
                Tile.WALL -> Color(0xFF7870C4)
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
