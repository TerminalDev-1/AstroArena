package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import io.github.projectwip.ui.lobbyAnchor
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What an upgrade changed, for the level-up moment: the level reached, and each stat before ([StatPreview.current]) and after ([StatPreview.next]). */
private class UpgradeMoment(val level: Int, val rows: List<StatPreview>)

private val STAT_ICONS = listOf(IconKind.HEART, IconKind.SWORDS, IconKind.STAR)

/**
 * The fighters. It opens on a grid of every fighter ([initial] null); tapping one opens that fighter's own page,
 * where it is upgraded. Opened straight onto a fighter (from the home screen), back goes home rather than to the grid.
 */
@Composable
fun FightersScreen(save: SaveData, repo: GameRepository, initial: FighterId?, go: (Screen) -> Unit) {
    var focus by remember { mutableStateOf(initial) }
    val id = focus
    if (id == null) FighterGrid(save, go) { focus = it }
    else {
        androidx.activity.compose.BackHandler(enabled = initial == null) { focus = null }
        FighterPage(save, repo, id, go) { if (initial == null) focus = null else go(Screen.Home) }
    }
}

@Composable
private fun FighterGrid(save: SaveData, go: (Screen) -> Unit, open: (FighterId) -> Unit) {
    val ui = LocalUi.current
    val next = io.github.projectwip.data.SparkRoad.next(save)
    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("FIGHTERS", { go(Screen.Home) }, save.bolts, save.prisms, credits = save.credits) {
                // The Spark Road is where fighters are unlocked.
                ChunkyButton({ go(Screen.Road) }, Modifier.size(170.dp, 46.dp), ButtonStyle.GREEN, lip = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GameIcon(IconKind.CREDIT, Modifier.size(22.dp))
                        GameText(" SPARK ROAD", Type.Label, outline = 2.dp)
                    }
                }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (ui.roomy) 170.dp else 138.dp),
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(Balance.fighters.size) { i ->
                    val id = Balance.fighters[i].id
                    FighterCard(save, id, next?.takeIf { it.fighter == id }?.cost ?: io.github.projectwip.data.SparkRoad.steps.firstOrNull { it.fighter == id }?.cost) { open(id) }
                }
            }
        }
    }
}

/** One fighter in the grid: portrait, name, role, and either its level or (locked) what the Spark Road asks for it. */
@Composable
private fun FighterCard(save: SaveData, id: FighterId, roadCost: Int?, onClick: () -> Unit) {
    val def = Balance.fighter(id)
    val p = save.progress(id)
    val inUse = save.selectedFighter == id
    Box {
        ChunkyButton(onClick, Modifier.fillMaxWidth().aspectRatio(0.72f).padding(top = 6.dp),
            if (inUse) ButtonStyle.GOLD else if (p.unlocked) ButtonStyle.PURPLE else ButtonStyle.GREY, cut = 16.dp, lip = 5.dp) {
            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FighterView(def, p.skin, Modifier.weight(1f).fillMaxWidth(), pedestal = false, locked = !p.unlocked)
                GameText(def.name.substringBefore(' ').uppercase(), Type.Heading, outline = 2.5.dp)
                PlainText(def.role, Type.Small, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                Spacer(Modifier.height(4.dp))
                if (p.unlocked) {
                    GameText(if (Progression.levelCapped(save, id)) "MAX · LV ${p.level}" else "LEVEL ${p.level}", Type.Label, color = if (inUse) Color.White else Palette.Gold, outline = 2.dp)
                    LevelPips(p.level, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp))
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    GameIcon(IconKind.LOCK, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    GameIcon(IconKind.CREDIT, Modifier.size(20.dp))
                    GameText(" ${roadCost ?: "-"}", Type.Label, outline = 2.dp)
                }
            }
        }
        when {
            inUse -> Badge("IN USE", Modifier.align(Alignment.TopEnd).offset(x = 4.dp), color = Palette.CyanDeep)
            Progression.canUpgrade(save, id) -> Badge("UPGRADE", Modifier.align(Alignment.TopEnd).offset(x = 4.dp), color = Palette.GreenDeep)
        }
    }
}

/** The level as a bar of segments, one for each level up to the top one: filled for the levels reached. */
@Composable
private fun LevelPips(level: Int, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(modifier.height(height)) {
        val n = Balance.MAX_LEVEL
        val gap = 2.5.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val x = i * (w + gap)
            val r = androidx.compose.ui.geometry.CornerRadius(size.height * 0.3f)
            drawRoundRect(Palette.Ink, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), r)
            val inset = 1.5.dp.toPx()
            drawRoundRect(if (i < level) Palette.Orange else Palette.PanelInset, Offset(x + inset, inset),
                androidx.compose.ui.geometry.Size(w - inset * 2, size.height - inset * 2), r)
        }
    }
}

/**
 * One fighter's page. The live 3D fighter stands on the left under its name; on the right are its level (as a bar
 * that fills toward the top level), its stats, and one wide button to upgrade it. An upgrade takes over the
 * whole page for a moment (see [LevelUpMoment]).
 */
@Composable
private fun FighterPage(save: SaveData, repo: GameRepository, id: FighterId, go: (Screen) -> Unit, onBack: () -> Unit) {
    var upgradeCount by remember { mutableIntStateOf(0) }
    var moment by remember { mutableStateOf<UpgradeMoment?>(null) }
    val ui = LocalUi.current
    val sfx = LocalSfx.current
    val ask = io.github.projectwip.ui.LocalServerCall.current
    val def = Balance.fighter(id)
    val prog = save.progress(id)
    val capped = Progression.levelCapped(save, def.id)
    val accent = Color(def.skins[prog.skin].accent)

    io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.FIGHTER, id, prog.skin, locked = !prog.unlocked, celebrateKey = upgradeCount)
    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyVignette(0.8f)
        val showing = moment
        if (showing != null) {
            LevelUpMoment(def, showing, accent) { moment = null }
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("FIGHTERS", onBack, save.bolts, save.prisms)
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                // ---------------- the fighter
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    GameText(def.name.uppercase(), Type.Display.copy(fontSize = Type.Display.fontSize * 1.2f), outline = 5.dp)
                    PlainText("${def.title} · ${def.role}", Type.Label, color = Palette.Cyan)
                    // The live 3D fighter from the lobby stands here.
                    Box(Modifier.weight(1f).fillMaxWidth().lobbyAnchor(), contentAlignment = Alignment.Center) {
                        UpgradeBurst(upgradeCount, accent)
                        if (!prog.unlocked) GameIcon(IconKind.LOCK, Modifier.size(80.dp))
                    }
                    if (ui.roomy) {
                        PlainText(def.lore, Type.Body, modifier = Modifier.width(400.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                    }
                    SkinRow(save, id, repo, go)
                }

                // ---------------- level, stats, upgrade
                Panel(Modifier.width(if (ui.wide) 390.dp else 330.dp).fillMaxHeight(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        LevelHeader(prog.level, prog.unlocked, upgradeCount, capped)
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val rows = Progression.statPreview(def, prog.level, capped)
                            androidx.compose.runtime.key(id) {
                                rows.forEachIndexed { i, r -> StatRow(r, i, prog.unlocked, STAT_ICONS[i % STAT_ICONS.size]) }
                            }
                            Spacer(Modifier.height(4.dp))
                            FixedStats(def)
                        }
                        Spacer(Modifier.height(10.dp))
                        ActionButtons(save, id, repo, go) {
                            // What the stats are now, and are about to become: the level-up moment counts between them.
                            val before = Progression.statPreview(def, prog.level, capped)
                            val reached = prog.level + 1
                            ask({ upgrade(id, save.settings.debugUpgradeCost, save.settings.debugNoLevelCap) }) {
                                upgradeCount++
                                sfx?.buzz(70, 200)
                                moment = UpgradeMoment(reached, before)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The level-up moment: everything else on the page steps aside. The fighter cheers in a burst of light, "LEVEL
 * UP!" slams in with the new level, and each stat counts up from what it was to what it is. A tap ends it.
 */
@Composable
private fun LevelUpMoment(def: io.github.projectwip.data.FighterDef, m: UpgradeMoment, accent: Color, onDone: () -> Unit) {
    val sfx = LocalSfx.current
    val slam = remember { Animatable(3f) }
    val count = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(m) {
        burst++
        sfx?.play(Sound.BANNER)
        slam.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
        val ticking = launch {
            var n = 0
            while (true) { sfx?.play(Sound.COUNT, 0.5f, 0.85f + minOf(n, 12) * 0.05f); n++; kotlinx.coroutines.delay(70) }
        }
        count.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        ticking.cancel()
        sfx?.play(Sound.CHING, 0.9f, 1.1f)
        sfx?.buzz(40, 200)
        kotlinx.coroutines.delay(350)
        ready = true
    }
    Row(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { if (ready) onDone() }.padding(horizontal = 28.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The fighter keeps its place on the left, cheering.
        Box(Modifier.weight(1f).fillMaxHeight().lobbyAnchor(), contentAlignment = Alignment.Center) { UpgradeBurst(burst, accent) }
        Column(Modifier.width(400.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GameText("LEVEL UP!", Type.Display.copy(fontSize = Type.Display.fontSize * 1.35f), color = Palette.Gold, outline = 5.dp,
                modifier = Modifier.graphicsLayer { scaleX = slam.value; scaleY = slam.value; alpha = (3f - slam.value).coerceIn(0f, 1f) })
            GameText(def.name.uppercase(), Type.Title, outline = 3.5.dp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Palette.Ink)
                    drawCircle(Palette.OrangeDeep, size.minDimension / 2 - 4.dp.toPx())
                    drawCircle(Palette.Orange, size.minDimension / 2 - 10.dp.toPx())
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlainText("LEVEL", Type.Small, color = Color.White)
                    GameText(m.level.toString(), Type.Display, outline = 4.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            LevelPips(m.level, Modifier.fillMaxWidth(), 14.dp)
            Spacer(Modifier.height(12.dp))
            m.rows.forEachIndexed { i, r ->
                val to = r.next ?: r.current
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    GameIcon(STAT_ICONS[i % STAT_ICONS.size], Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    PlainText(r.label.uppercase(), Type.Label, Modifier.weight(1f), color = Color.White, maxLines = 1)
                    GameText("%,d".format(r.current + ((to - r.current) * count.value).toInt()) + r.suffix, Type.Title, outline = 3.dp)
                    Spacer(Modifier.width(8.dp))
                    Badge("+${to - r.current}", color = Palette.GreenDeep)
                }
            }
            Spacer(Modifier.height(14.dp))
            GameText("TAP TO CONTINUE", Type.Heading, color = Palette.Gold, outline = 2.5.dp, modifier = Modifier.graphicsLayer { alpha = if (ready) 1f else 0f })
        }
    }
}

@Composable
private fun LevelHeader(level: Int, unlocked: Boolean, upgradeCount: Int, capped: Boolean) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameText(if (capped) "MAX LEVEL" else "LEVEL $level", Type.Heading, color = if (capped) Palette.Gold else Color.White, outline = 2.5.dp)
                if (unlocked && !capped) {
                    GameText("  →  ${level + 1}", Type.Heading, color = Palette.Positive, outline = 2.5.dp)
                }
                Spacer(Modifier.weight(1f))
                PlainText(if (capped) "fully upgraded" else "max ${maxOf(level, Balance.MAX_LEVEL)}", Type.Small)
            }
            Spacer(Modifier.height(6.dp))
            // The level as a bar: one segment for each level up to the top one.
            LevelPips(level, Modifier.fillMaxWidth(), 12.dp)
        }
    }
}

@Composable
private fun StatRow(r: StatPreview, index: Int, unlocked: Boolean, icon: IconKind) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(icon, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                PlainText(r.label.uppercase(), Type.Small, color = Palette.TextDim)
            }
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
private fun ActionButtons(save: SaveData, id: FighterId, repo: GameRepository, go: (Screen) -> Unit, onUpgrade: () -> Unit) {
    val p = save.progress(id)
    if (!p.unlocked) {
        // Fighters are unlocked on the Spark Road with Credits; the shop sells them for Crystals as a shortcut.
        val step = io.github.projectwip.data.SparkRoad.steps.firstOrNull { it.fighter == id }
        ChunkyButton({ go(Screen.Road) }, Modifier.fillMaxWidth().height(60.dp), ButtonStyle.GREEN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameText("SPARK ROAD  ", Type.Heading)
                GameIcon(IconKind.CREDIT, Modifier.size(26.dp))
                GameText(" ${step?.cost ?: "-"}", Type.Heading)
            }
        }
        Spacer(Modifier.height(8.dp))
        ChunkyButton({ go(Screen.Shop) }, Modifier.fillMaxWidth().height(50.dp), ButtonStyle.CYAN, lip = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameText("OR BUY  ", Type.Label, outline = 2.dp)
                GameIcon(IconKind.PRISM, Modifier.size(22.dp))
                GameText(" ${Balance.unlockPrismPrice(id) ?: "-"}", Type.Label, outline = 2.dp)
            }
        }
        return
    }
    val cost = Progression.upgradeCost(save, id)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (save.selectedFighter != id) {
            ChunkyButton({ repo.selectFighter(id) }, Modifier.width(110.dp).height(72.dp), ButtonStyle.CYAN) { GameText("SELECT", Type.Heading) }
        }
        if (Progression.levelCapped(save, id)) {
            ChunkyButton({}, Modifier.weight(1f).height(72.dp), ButtonStyle.GOLD, enabled = true) { GameText("MAX LEVEL", Type.Heading) }
        } else {
            val afford = save.bolts >= cost
            // One wide button: what it does on the left, what it costs on the right.
            ChunkyButton(onUpgrade, Modifier.weight(1f).height(72.dp), ButtonStyle.GREEN, enabled = afford, sound = Sound.UPGRADE) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    GameText("UPGRADE", Type.Title, outline = 3.dp)
                    Spacer(Modifier.weight(1f))
                    GameIcon(IconKind.BOLT, Modifier.size(30.dp))
                    GameText(" %,d".format(cost), Type.Title, color = if (afford) Color.White else Palette.Red, outline = 3.dp)
                }
            }
        }
    }
    if (!Progression.levelCapped(save, id) && save.bolts < cost) PlainText("Need ${cost - save.bolts} more Power Ups — win matches or visit the Shop.", Type.Small, color = Palette.Red)
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
