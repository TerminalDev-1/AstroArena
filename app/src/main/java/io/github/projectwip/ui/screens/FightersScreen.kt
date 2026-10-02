package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.CupTrack
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.Progression
import io.github.projectwip.data.Reward
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.StatPreview
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameBackground
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.ProgressBar
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.plateShape
import io.github.projectwip.ui.popOnChange
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun FightersScreen(save: SaveData, repo: GameRepository, initial: FighterId, go: (Screen) -> Unit) {
    var focus by remember { mutableStateOf(initial) }
    var upgradeCount by remember { mutableIntStateOf(0) }
    val ui = LocalUi.current
    val sfx = LocalSfx.current
    val def = Balance.fighter(focus)
    val prog = save.progress(focus)

    Box(Modifier.fillMaxSize()) {
        GameBackground()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("FIGHTERS", { go(Screen.Home) }, save.bolts, save.prisms)
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                // ---------------- roster
                Column(
                    Modifier.width(if (ui.roomy) 168.dp else 140.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (f in Balance.fighters) RosterCard(save, f.id, f.id == focus) { focus = f.id }
                }

                // ---------------- hero
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            FighterView(def, prog.skin, Modifier.fillMaxSize(), rays = true, locked = !prog.unlocked, celebrateKey = upgradeCount)
                            UpgradeBurst(upgradeCount, Color(def.skins[prog.skin].accent))
                            if (!prog.unlocked) GameIcon(IconKind.LOCK, Modifier.size(80.dp))
                        }
                        GameText(def.name.uppercase(), Type.Display, outline = 4.dp)
                        PlainText("${def.title} · ${def.role}", Type.Label, color = Palette.Cyan)
                        if (ui.roomy) {
                            Spacer(Modifier.height(4.dp))
                            PlainText(def.lore, Type.Body, modifier = Modifier.width(380.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Spacer(Modifier.height(8.dp))
                        SkinRow(save, focus, repo, go)
                    }
                }

                // ---------------- stats + upgrade
                Panel(Modifier.width(if (ui.wide) 380.dp else 330.dp).fillMaxHeight(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        LevelHeader(prog.level, prog.unlocked, upgradeCount)
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val rows = Progression.statPreview(def, prog.level)
                            androidx.compose.runtime.key(focus) {
                                rows.forEachIndexed { i, r -> StatRow(r, i, prog.unlocked) }
                            }
                            Spacer(Modifier.height(4.dp))
                            FixedStats(def)
                        }
                        Spacer(Modifier.height(10.dp))
                        ActionButtons(save, focus, repo, go) {
                            upgradeCount++
                            sfx?.buzz(70, 200)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterCard(save: SaveData, id: FighterId, focused: Boolean, onClick: () -> Unit) {
    val def = Balance.fighter(id)
    val p = save.progress(id)
    val ui = LocalUi.current
    Box {
        ChunkyButton(onClick, Modifier.fillMaxWidth().height(if (ui.roomy) 118.dp else 92.dp),
            if (focused) ButtonStyle.GOLD else if (p.unlocked) ButtonStyle.PURPLE else ButtonStyle.GREY, cut = 14.dp, lip = 4.dp) {
            Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().aspectRatio(0.8f)) {
                    FighterView(def, p.skin, Modifier.fillMaxSize(), pedestal = false)
                }
                Column(Modifier.weight(1f)) {
                    GameText(def.name.substringBefore(' ').uppercase(), Type.Label, outline = 2.dp)
                    if (p.unlocked) GameText("LV ${p.level}", Type.Heading, color = if (focused) Color.White else Palette.Gold, outline = 2.dp)
                    else GameIcon(IconKind.LOCK, Modifier.size(24.dp))
                }
            }
        }
        if (save.selectedFighter == id) Badge("IN USE", Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp), color = Palette.CyanDeep)
        else if (Progression.canUpgrade(save, id)) Badge("UP", Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp), color = Palette.GreenDeep)
    }
}

@Composable
private fun LevelHeader(level: Int, unlocked: Boolean, upgradeCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).popOnChange(upgradeCount), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Palette.Ink)
                drawCircle(Palette.OrangeDeep, size.minDimension / 2 - 3.dp.toPx())
                drawCircle(Palette.Orange, size.minDimension / 2 - 7.dp.toPx())
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlainText("LV", Type.Small, color = Color.White)
                GameText(level.toString(), Type.Title, outline = 2.5.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (level < Balance.MAX_LEVEL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GameText("LEVEL $level", Type.Heading, outline = 2.5.dp)
                    if (unlocked) {
                        GameText("  →  ${level + 1}", Type.Heading, color = Palette.Positive, outline = 2.5.dp)
                    }
                }
            } else {
                GameText("MAX LEVEL", Type.Heading, color = Palette.Gold, outline = 2.5.dp)
            }
            Spacer(Modifier.height(6.dp))
            ProgressBar(level.toFloat() / Balance.MAX_LEVEL, Modifier.fillMaxWidth().height(14.dp), Palette.Orange, Palette.OrangeDeep)
            PlainText("Level $level of ${Balance.MAX_LEVEL}", Type.Small)
        }
    }
}

@Composable
private fun StatRow(r: StatPreview, index: Int, unlocked: Boolean) {
    val shown by animateIntAsState(r.current, tween(700, delayMillis = index * 90), label = "stat")
    // Highlight flash + floating "+N" after an upgrade.
    val flash = remember { Animatable(0f) }
    val float = remember { Animatable(1f) }
    var lastDelta by remember { mutableIntStateOf(0) }
    var prevCurrent by remember { mutableIntStateOf(r.current) }
    // Rows are keyed per fighter, so an increase here can only come from an upgrade.
    LaunchedEffect(r.current) {
        val delta = r.current - prevCurrent
        prevCurrent = r.current
        if (delta <= 0) return@LaunchedEffect
        lastDelta = delta
        kotlinx.coroutines.delay(index * 90L)
        flash.snapTo(1f)
        float.snapTo(0f)
        kotlinx.coroutines.coroutineScope {
            launch { flash.animateTo(0f, tween(900)) }
            launch { float.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        }
    }

    Box(
        Modifier.fillMaxWidth().drawBehind {
            val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
            val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
            drawPath(p, Palette.PanelInset)
            if (flash.value > 0f) drawPath(p, Palette.Positive.copy(alpha = 0.35f * flash.value))
            drawPath(p, Palette.Ink, style = Stroke(2.dp.toPx()))
        }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            PlainText(r.label.uppercase(), Type.Small, color = Palette.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameText("%,d".format(shown) + r.suffix, Type.Title, outline = 2.5.dp)
                if (r.next != null && unlocked) {
                    Spacer(Modifier.width(10.dp))
                    GameText("→ %,d".format(r.next), Type.Heading, color = Palette.Positive, outline = 2.dp)
                    Spacer(Modifier.weight(1f))
                    Badge("+${r.delta}", color = Palette.GreenDeep)
                }
            }
        }
        if (float.value < 1f && lastDelta > 0) {
            GameText("+$lastDelta", Type.Title, color = Palette.Positive, outline = 3.dp,
                modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-40 * float.value).dp)
                    .graphicsLayer { alpha = 1f - float.value; scaleX = 1.3f - 0.3f * float.value; scaleY = scaleX })
        }
    }
}

@Composable
private fun FixedStats(def: io.github.projectwip.data.FighterDef) {
    val items = listOf(
        "Speed" to "%.1f".format(def.moveSpeed),
        "Range" to "%.1f".format(def.attack.range),
        "Reload" to "%.2fs".format(def.reloadSeconds),
        "Ammo" to def.ammoMax.toString(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for ((k, v) in items) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlainText(k.uppercase(), Type.Small)
            GameText(v, Type.Label, outline = 2.dp)
        }
    }
    Spacer(Modifier.height(6.dp))
    PlainText("${def.superSpec.name}: ${def.superSpec.description}", Type.Small)
    PlainText("Upgrades raise Health, ${def.attackName} and ${def.superSpec.name} by the same amount every level.", Type.Small, color = Palette.TextDim.copy(alpha = 0.7f))
}

@Composable
private fun ActionButtons(save: SaveData, id: FighterId, repo: GameRepository, go: (Screen) -> Unit, onUpgraded: () -> Unit) {
    val p = save.progress(id)
    if (!p.unlocked) {
        val track = CupTrack.milestones.firstOrNull { it.reward == Reward.UnlockFighter(id) }
        if (track != null) PlainText("Free on the Cup Track at ${track.cups} Cups", Type.Small, color = Palette.Gold)
        Spacer(Modifier.height(6.dp))
        ChunkyButton({ go(Screen.Shop) }, Modifier.fillMaxWidth().height(64.dp), ButtonStyle.CYAN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameText("UNLOCK  ", Type.Heading)
                GameIcon(IconKind.PRISM, Modifier.size(26.dp))
                GameText(" ${Balance.unlockPrismPrice(id) ?: "-"}", Type.Heading)
            }
        }
        return
    }
    val cost = Balance.upgradeCostFrom(p.level)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (save.selectedFighter != id) {
            ChunkyButton({ repo.selectFighter(id) }, Modifier.width(110.dp).height(68.dp), ButtonStyle.CYAN) { GameText("SELECT", Type.Heading) }
        }
        if (cost == null) {
            ChunkyButton({}, Modifier.weight(1f).height(68.dp), ButtonStyle.GOLD, enabled = true) { GameText("MAXED OUT", Type.Heading) }
        } else {
            val afford = save.bolts >= cost
            ChunkyButton(
                { if (repo.upgrade(id)) onUpgraded() },
                Modifier.weight(1f).height(68.dp), ButtonStyle.GREEN, enabled = afford, sound = Sound.UPGRADE,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GameText("UPGRADE", Type.Heading)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(IconKind.BOLT, Modifier.size(20.dp))
                        GameText(" $cost", Type.Label, color = if (afford) Color.White else Palette.Red)
                    }
                }
            }
        }
    }
    if (cost != null && save.bolts < cost) PlainText("Need ${cost - save.bolts} more Bolts — win matches or visit the Shop.", Type.Small, color = Palette.Red)
}

@Composable
private fun SkinRow(save: SaveData, id: FighterId, repo: GameRepository, go: (Screen) -> Unit) {
    val def = Balance.fighter(id)
    val p = save.progress(id)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        def.skins.forEachIndexed { i, skin ->
            val owned = i in p.ownedSkins
            val selected = p.skin == i
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, null) {
                        if (owned && p.unlocked) repo.selectSkin(id, i) else go(Screen.Shop)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(if (selected) Palette.Gold else Palette.Ink)
                        drawCircle(Color(skin.primary), size.minDimension / 2 - 4.dp.toPx())
                        drawArc(Color(skin.secondary), -90f, 180f, true, Offset(4.dp.toPx(), 4.dp.toPx()),
                            androidx.compose.ui.geometry.Size(size.width - 8.dp.toPx(), size.height - 8.dp.toPx()))
                    }
                    if (!owned) GameIcon(IconKind.LOCK, Modifier.size(22.dp))
                    if (selected) GameIcon(IconKind.CHECK, Modifier.size(18.dp).align(Alignment.BottomEnd))
                }
                PlainText(skin.name, Type.Small)
            }
        }
    }
}

/** Radial burst + confetti over the hero when an upgrade lands. */
@Composable
private fun UpgradeBurst(trigger: Int, accent: Color) {
    val t = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, tween(1300, easing = LinearEasing))
    }
    if (t.value >= 1f) return
    val pieces = remember(trigger) { val r = Random(trigger); List(36) { Triple(r.nextFloat() * 6.28f, 0.5f + r.nextFloat(), r.nextInt(4)) } }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height * 0.45f)
        val p = t.value
        val maxR = size.minDimension * 0.55f
        drawCircle(accent.copy(alpha = (1f - p) * 0.5f), maxR * p * 1.2f, c, style = Stroke(18.dp.toPx() * (1f - p)))
        drawCircle(Color.White.copy(alpha = (1f - p * 3f).coerceAtLeast(0f) * 0.7f), maxR * 0.6f, c)
        val colors = listOf(Palette.Gold, Palette.Positive, accent, Palette.Cyan)
        for ((ang, spd, ci) in pieces) {
            val d = maxR * spd * (1f - (1f - p) * (1f - p))
            val pos = Offset(c.x + cos(ang) * d, c.y + sin(ang) * d + p * p * 120.dp.toPx() * 0.5f)
            drawCircle(colors[ci].copy(alpha = 1f - p), 6.dp.toPx() * (1f - p * 0.5f), pos)
        }
    }
}
