package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.CapsuleResult
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.Reward
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.CurrencyPill
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalLobby
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardVisual
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CYAN = Color(0xFF29F0FF)
private val MAGENTA = Color(0xFFFF2BD6)

/** A fixed scramble of [n] into 0..1, so a glitch frame looks the same however often it is redrawn. */
private fun scramble(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

/**
 * Game text that glitches: a cyan and a magenta copy sit just either side of it, and every so often all three
 * are thrown apart for a few frames. [strength] 0..1 is how often and how far.
 */
@Composable
fun GlitchText(text: String, style: TextStyle, color: Color, outline: Dp, time: Float, strength: Float, modifier: Modifier = Modifier) {
    val frame = (time * 22f).toInt()
    val torn = scramble(frame / 4 * 11 + 3) < 0.12f + 0.3f * strength
    val dx = if (torn) (scramble(frame) - 0.5f) * 26f * strength else 1.5f
    val dy = if (torn) (scramble(frame + 9) - 0.5f) * 6f * strength else 0f
    Box(modifier) {
        GameText(text, style, Modifier.graphicsLayer { translationX = -dx.dp.toPx(); translationY = dy.dp.toPx(); alpha = 0.75f }, color = CYAN, outline = outline)
        GameText(text, style, Modifier.graphicsLayer { translationX = dx.dp.toPx(); translationY = -dy.dp.toPx(); alpha = 0.75f }, color = MAGENTA, outline = outline)
        GameText(text, style, Modifier.graphicsLayer { translationX = if (torn) dx.dp.toPx() * 0.3f else 0f }, color = color, outline = outline)
    }
}

/** Strips of light knocked across the whole screen for a few frames at a time, as if the picture were tearing. */
@Composable
fun GlitchBars(time: Float, color: Color, strength: Float) {
    val frame = (time * 22f).toInt()
    if (scramble(frame / 3 * 7 + 5) >= 0.1f + 0.3f * strength) return
    Canvas(Modifier.fillMaxSize()) {
        for (i in 0 until 5) {
            val n = frame * 17 + i * 53
            val w = size.width * (0.15f + 0.5f * scramble(n))
            val h = (2f + 12f * scramble(n + 1)).dp.toPx()
            val tint = when (i % 3) { 0 -> CYAN; 1 -> MAGENTA; else -> color }
            drawRect(tint.copy(alpha = (0.1f + 0.25f * scramble(n + 2)) * strength.coerceAtMost(1f)), Offset(scramble(n + 3) * (size.width - w), scramble(n + 4) * size.height), Size(w, h))
        }
    }
}

private fun boltsIn(r: Reward): Int = when (r) { is Reward.Bolts -> r.amount; is Reward.Bundle -> r.items.sumOf { boltsIn(it) }; else -> 0 }
private fun prismsIn(r: Reward): Int = when (r) { is Reward.Prisms -> r.amount; is Reward.Bundle -> r.items.sumOf { prismsIn(it) }; else -> 0 }
private fun unlocksIn(r: Reward): Int = when (r) { is Reward.UnlockFighter, is Reward.SkinReward -> 1; is Reward.Bundle -> r.items.sumOf { unlocksIn(it) }; else -> 0 }

/** What a card says under its picture: the amount, or the name of what was unlocked. */
private fun shortLabel(r: Reward): String = when (r) {
    is Reward.Bolts -> "+%,d".format(r.amount)
    is Reward.Prisms -> "+%,d".format(r.amount)
    is Reward.UnlockFighter -> Balance.fighter(r.fighter).name.substringBefore(' ').uppercase()
    is Reward.SkinReward -> Balance.fighter(r.fighter).skins[r.skinIndex].name.uppercase()
    is Reward.Bundle -> "JACKPOT"
}

/**
 * "Open all": every drop the player had, opened at once. A ring of drops charges up through every tier and
 * overloads; then everything that came out lands on the screen one card after another, the best last, while
 * the wallet counts up; and the totals slam in underneath. It is all saved already: this only shows it.
 */
@Composable
fun DropHaulOverlay(results: List<CapsuleResult>, boltsNow: Int, prismsNow: Int, onDone: () -> Unit) = key(results) {
    val sfx = LocalSfx.current
    val lobby = LocalLobby.current
    val time by rememberAnimTime()
    // The best come last.
    val cards = remember { results.sortedBy { it.tier.ordinal } }
    val bolts = remember { cards.sumOf { boltsIn(it.reward) } }
    val prisms = remember { cards.sumOf { prismsIn(it.reward) } }
    val unlocks = remember { cards.sumOf { unlocksIn(it.reward) } }
    /** 0 = the drops overload, 1 = the cards land, 2 = the totals. */
    var stage by remember { mutableIntStateOf(0) }
    var landed by remember { mutableIntStateOf(0) }
    var charge by remember { mutableIntStateOf(0) }
    val flash = remember { Animatable(0f) }
    val slam = remember { Animatable(2.6f) }
    val grid = rememberLazyGridState()

    DisposableEffect(Unit) {
        lobby.capsuleOpenAt = 0L
        lobby.capsulePieces = 8
        lobby.capsuleSplitAt = System.currentTimeMillis()
        lobby.capsuleColor = CapsuleTier.SCRAP.color.toInt()
        lobby.capsuleGlitch = 1f
        lobby.capsuleShown = true
        onDispose { lobby.capsuleShown = false; lobby.capsuleGlitch = 0f }
    }
    LaunchedEffect(Unit) {
        sfx?.play(Sound.GLITCH)
        delay(350)
        // The ring of drops charges straight up through every tier...
        for (t in CapsuleTier.entries.drop(1)) {
            charge = t.ordinal
            lobby.capsuleColor = t.color.toInt()
            lobby.capsuleChargeAt = System.currentTimeMillis()
            lobby.capsuleKnockAt = System.currentTimeMillis()
            sfx?.play(Sound.DROP_UPGRADE, 0.9f, 0.85f + 0.12f * t.ordinal)
            sfx?.buzz(40, 200)
            delay(240)
        }
        // ...and overloads.
        delay(200)
        lobby.capsuleOpenAt = System.currentTimeMillis()
        sfx?.play(Sound.GLITCH, 0.9f, 0.8f)
        delay((io.github.projectwip.render3d.Capsule3D.WIND * 1000).toLong())
        sfx?.play(Sound.DROP_OPEN)
        sfx?.buzz(120, 255)
        delay(((io.github.projectwip.render3d.Capsule3D.GONE - io.github.projectwip.render3d.Capsule3D.WIND) * 1000).toLong() - 120)
        lobby.capsuleShown = false
        stage = 1
        launch { flash.snapTo(1f); flash.animateTo(0f, tween(650)) }
        // Everything lands in about four seconds however much there is; the good ones get a beat to themselves.
        val gap = (3600L / cards.size).coerceIn(30L, 170L)
        for ((i, card) in cards.withIndex()) {
            landed = i + 1
            val t = card.tier.ordinal
            sfx?.play(Sound.POP, 0.5f, 0.8f + 0.12f * t)
            if (t >= CapsuleTier.OVERCLOCKED.ordinal) { sfx?.play(Sound.CHING, 0.8f, 0.9f + 0.06f * t); sfx?.buzz(18, 160) }
            delay(if (t >= CapsuleTier.OVERCLOCKED.ordinal) gap + 160 else gap)
        }
        delay(300)
        stage = 2
        sfx?.play(Sound.BANNER)
        sfx?.play(Sound.REWARD, 0.9f)
        sfx?.buzz(80, 240)
        launch { flash.snapTo(0.6f); flash.animateTo(0f, tween(500)) }
        slam.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(landed) { if (landed > 0) grid.scrollToItem(landed - 1) }

    val shownBolts = remember(landed) { cards.take(landed).sumOf { boltsIn(it.reward) } }
    val shownPrisms = remember(landed) { cards.take(landed).sumOf { prismsIn(it.reward) } }
    val tint = Color(CapsuleTier.entries[charge].color)

    Box(
        // While the drops are still closed the lobby renderer does the dimming, so the 3D drops stay bright.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (stage > 0) 0.84f else 0f)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        if (stage == 0) {
            Column(Modifier.fillMaxSize().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GameText("OPENING ${cards.size} SPARK DROPS", Type.Heading, color = Palette.TextDim, outline = 2.5.dp)
                GlitchText(CapsuleTier.entries[charge].label.uppercase(), Type.Display.copy(fontSize = Type.Display.fontSize * 1.25f), tint, 5.dp, time, 1f)
            }
            GlitchBars(time, tint, 1f)
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlitchText("OPENED ${cards.size} DROPS", Type.Title, Palette.Gold, 3.5.dp, time, 0.5f, Modifier.weight(1f))
                    CurrencyPill(IconKind.BOLT, boltsNow - bolts + shownBolts)
                    Spacer(Modifier.width(10.dp))
                    CurrencyPill(IconKind.PRISM, prismsNow - prisms + shownPrisms)
                }
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f).fillMaxWidth(), grid, horizontalArrangement = Arrangement.Center) {
                    items(landed) { i -> HaulCard(cards[i]) }
                }
                // The totals keep their room from the start, so the cards above don't jump when they arrive.
                Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
                    if (stage == 2) Row(
                        Modifier.graphicsLayer { scaleX = slam.value; scaleY = slam.value },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        if (bolts > 0) Total(IconKind.BOLT, "+%,d".format(bolts))
                        if (prisms > 0) Total(IconKind.PRISM, "+%,d".format(prisms))
                        if (unlocks > 0) Total(IconKind.FIGHTERS, "$unlocks NEW")
                        ChunkyButton(onDone, Modifier.size(190.dp, 60.dp), ButtonStyle.GREEN) { GameText("AWESOME", Type.Heading) }
                    }
                }
            }
            if (stage == 1) GlitchBars(time, Palette.Gold, 0.5f)
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.85f)))
    }
}

@Composable
private fun Total(icon: IconKind, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GameIcon(icon, Modifier.size(44.dp))
        Spacer(Modifier.width(6.dp))
        GameText(text, Type.Display, outline = 4.dp)
    }
}

/** One opened drop: what came out, on a plate in its tier's colour. It springs into place when it first appears. */
@Composable
private fun HaulCard(result: CapsuleResult) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)) }
    val c = Color(result.tier.color)
    Panel(
        Modifier.padding(4.dp).aspectRatio(0.8f).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -25f },
        color = lerp(c, Color.Black, 0.35f), colorBottom = lerp(c, Color.Black, 0.72f), cut = 10.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                RewardVisual(result.reward, Modifier.fillMaxHeight().aspectRatio(1f))
            }
            GameText(shortLabel(result.reward), Type.Label, outline = 2.dp, align = TextAlign.Center)
            PlainText(result.tier.label.uppercase(), Type.Small, color = lerp(c, Color.White, 0.5f), align = TextAlign.Center, maxLines = 1)
        }
    }
}

/** Made-up drops for `--es screen haul`: plenty of each tier, a fighter, a colourway and a jackpot. */
fun previewHaul(): List<CapsuleResult> = List(46) { i ->
    val tier = CapsuleTier.entries[when { i % 23 == 22 -> 5; i % 15 == 14 -> 4; i % 8 == 7 -> 3; i % 4 == 3 -> 2; i % 2 == 1 -> 1; else -> 0 }]
    val reward = when (tier) {
        CapsuleTier.ULTRA -> Reward.Bundle(listOf(Reward.SkinReward(FighterId.BRAKK, 1), Reward.Prisms(1350), Reward.Bolts(6600)))
        CapsuleTier.PRISMATIC -> if (i < 20) Reward.UnlockFighter(FighterId.MIRA) else Reward.SkinReward(FighterId.JUNO, 2)
        CapsuleTier.OVERCLOCKED -> Reward.Prisms(270 + i)
        else -> if (i % 3 == 0) Reward.Prisms(45 * (tier.ordinal + 1)) else Reward.Bolts(270 * (tier.ordinal + 1) + i * 15)
    }
    CapsuleResult(tier, reward)
}
