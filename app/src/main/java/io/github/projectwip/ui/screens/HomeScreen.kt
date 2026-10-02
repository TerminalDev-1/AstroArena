package io.github.projectwip.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.Progression
import io.github.projectwip.data.SaveData
import io.github.projectwip.sim.Arenas
import io.github.projectwip.sim.Tile
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.CurrencyPill
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameBackground
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.ProgressBar
import io.github.projectwip.ui.RewardReveal
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.startMatchConfig

@Composable
fun HomeScreen(save: SaveData, repo: GameRepository, go: (Screen) -> Unit, showReward: (RewardReveal) -> Unit) {
    val ui = LocalUi.current
    val def = Balance.fighter(save.selectedFighter)
    val prog = save.progress(save.selectedFighter)
    val canUpgradeAny = Balance.fighters.any { Progression.canUpgrade(save, it.id) }
    val claimable = Progression.claimable(save).size
    val giftReady = Progression.dailyGiftAvailable(save, repo.today)

    Box(Modifier.fillMaxSize()) {
        GameBackground()
        Column(Modifier.fillMaxSize()) {
            // ---------------- top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileChip(save)
                Spacer(Modifier.width(14.dp))
                CupButton(save, claimable) { go(Screen.CupTrack) }
                Spacer(Modifier.weight(1f))
                CurrencyPill(IconKind.BOLT, save.bolts)
                Spacer(Modifier.width(10.dp))
                CurrencyPill(IconKind.PRISM, save.prisms, onClick = { go(Screen.Shop) })
                Spacer(Modifier.width(12.dp))
                ChunkyButton({ go(Screen.Settings) }, Modifier.size(52.dp, 50.dp), ButtonStyle.PURPLE) {
                    GameIcon(IconKind.GEAR, Modifier.size(28.dp))
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                // ---------------- left navigation
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    NavTile("SHOP", IconKind.SHOP, if (giftReady) "FREE" else null, Palette.Green) { go(Screen.Shop) }
                    NavTile("FIGHTERS", IconKind.FIGHTERS, if (canUpgradeAny) "UP" else null, Palette.Green) { go(Screen.Fighters()) }
                    NavTile("CUP TRACK", IconKind.TRACK, if (claimable > 0) claimable.toString() else null, Palette.Red) { go(Screen.CupTrack) }
                }

                // ---------------- hero
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FighterView(
                            def, prog.skin,
                            Modifier.weight(1f, fill = false).aspectRatio(1f).fillMaxHeight()
                                .clickable(remember { MutableInteractionSource() }, null) { go(Screen.Fighters(save.selectedFighter)) },
                            rays = true,
                        )
                        NamePlate(save) { go(Screen.Fighters(save.selectedFighter)) }
                    }
                }

                // ---------------- play column
                Column(
                    Modifier.width(if (ui.wide) 320.dp else 270.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.End,
                ) {
                    ModeCard(save, repo, showMap = ui.roomy)
                    Spacer(Modifier.height(14.dp))
                    PlayButton { go(Screen.Match(startMatchConfig(save))) }
                }
            }
        }
    }
}

@Composable
private fun ProfileChip(save: SaveData) {
    Panel(Modifier.height(52.dp), cut = 12.dp) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { GameIcon(IconKind.STAR, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(8.dp))
            Column {
                GameText(save.settings.playerName, Type.Label, outline = 2.dp)
                PlainText("${save.victories} wins · ${save.matchesPlayed} played", Type.Small)
            }
        }
    }
}

@Composable
private fun CupButton(save: SaveData, claimable: Int, onClick: () -> Unit) {
    val next = CupTrack.nextMilestone(save.bestCups)
    val prev = CupTrack.previousMilestoneCups(save.bestCups)
    val frac = if (next == null) 1f else (save.bestCups - prev).toFloat() / (next.cups - prev)
    Box {
        ChunkyButton(onClick, Modifier.size(210.dp, 56.dp), ButtonStyle.PURPLE, lip = 4.dp) {
            Row(Modifier.fillMaxSize().padding(start = 58.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GameText("%,d".format(save.cups), Type.Title, color = Palette.Gold, outline = 3.dp)
                    ProgressBar(frac, Modifier.fillMaxWidth().height(9.dp))
                }
            }
        }
        GameIcon(IconKind.CUP, Modifier.size(62.dp).offset(x = (-6).dp, y = (-4).dp))
        if (claimable > 0) Badge(claimable.toString(), Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
    }
}

@Composable
private fun NavTile(label: String, icon: IconKind, badge: String?, badgeColor: Color, onClick: () -> Unit) {
    val ui = LocalUi.current
    val w = if (ui.roomy) 124.dp else 104.dp
    val h = if (ui.roomy) 104.dp else 84.dp
    Box {
        ChunkyButton(onClick, Modifier.size(w, h), ButtonStyle.CYAN) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GameIcon(icon, Modifier.size(if (ui.roomy) 48.dp else 38.dp))
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
        Panel(Modifier.padding(top = 6.dp), cut = 14.dp) {
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

@Composable
private fun ModeCard(save: SaveData, repo: GameRepository, showMap: Boolean) {
    val d = save.settings.botDifficulty
    Panel(Modifier.fillMaxWidth(), cut = 18.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(IconKind.SWORDS, Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    GameText("KNOCKOUT RUSH", Type.Heading, color = Palette.Gold, outline = 2.5.dp)
                    PlainText("3v3 · first to ${Balance.KO_TARGET} KOs · Foundry Yard", Type.Small)
                }
            }
            if (showMap) {
                Spacer(Modifier.height(10.dp))
                Minimap(Modifier.fillMaxWidth().aspectRatio(34f / 20f))
            }
            Spacer(Modifier.height(10.dp))
            // Quick bot-difficulty switch — bots are a first-class way to play.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChunkyButton({ repo.updateSettings { it.copy(botDifficulty = BotDifficulty.entries[(d.ordinal + 3) % 4]) } },
                    Modifier.size(40.dp, 40.dp), ButtonStyle.PURPLE, lip = 3.dp) { GameText("‹", Type.Title) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlainText("BOTS", Type.Small)
                    GameText(d.label.uppercase(), Type.Heading, color = difficultyColor(d), outline = 2.5.dp)
                }
                ChunkyButton({ repo.updateSettings { it.copy(botDifficulty = BotDifficulty.entries[(d.ordinal + 1) % 4]) } },
                    Modifier.size(40.dp, 40.dp), ButtonStyle.PURPLE, lip = 3.dp) { GameText("›", Type.Title) }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                PlainText("Win: +${d.cupBonus}", Type.Small, color = Palette.Gold)
                GameIcon(IconKind.CUP, Modifier.size(16.dp).padding(start = 2.dp))
                PlainText("  ·  Bolts ×${d.boltMultiplier}", Type.Small, color = Palette.Bolt)
            }
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
        ChunkyButton(onClick, Modifier.fillMaxWidth().height(86.dp), ButtonStyle.ORANGE, cut = 20.dp, lip = 7.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(IconKind.PLAY, Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                GameText("PLAY", Type.Display.copy(fontSize = Type.Display.fontSize * 1.15f), outline = 4.dp)
            }
        }
    }
}

/** A live thumbnail of the actual arena layout. */
@Composable
fun Minimap(modifier: Modifier = Modifier) {
    val arena = remember { Arenas.foundryYard() }
    Canvas(modifier) {
        val t = size.width / arena.width
        drawRect(Palette.Ink)
        for (y in 0 until arena.height) for (x in 0 until arena.width) {
            val c = when (arena[x, y]) {
                Tile.WALL -> Color(0xFF7870C4)
                Tile.THICKET -> Color(0xFF3FAE5C)
                Tile.WATER -> Color(0xFF2CA0DE)
                Tile.FLOOR -> if (x < 3) Color(0xFFB9C8E0) else if (x >= arena.width - 3) Color(0xFFE6B9B0) else Color(0xFFDEC492)
            }
            drawRect(c, Offset(x * t, y * t), Size(t + 0.5f, t + 0.5f))
        }
        for ((team, spawns) in arena.spawns.withIndex()) for (s in spawns) {
            drawCircle(Palette.Ink, t * 0.75f, Offset(s.x * t, s.y * t))
            drawCircle(if (team == 0) Palette.Ally else Palette.Enemy, t * 0.55f, Offset(s.x * t, s.y * t))
        }
    }
}
