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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.CapsuleResult
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.FighterRays
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalLobby
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardVisual
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import io.github.projectwip.ui.rewardLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/** Knocks it takes to open a capsule. Some of them charge it up a tier, the rest just rattle it. */
private const val TAPS = 4

/**
 * Opening a Spark Capsule. The result is already decided (and saved) before this is shown; the taps only
 * reveal it: each knock either rattles the capsule or charges it up a tier, and the last one bursts it open.
 */
@Composable
fun CapsuleOpenOverlay(result: CapsuleResult, remaining: Int, onNext: () -> Unit, onDone: () -> Unit) = key(result) {
    val sfx = LocalSfx.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val time by rememberAnimTime()
    // Which knocks charge the capsule: as many as the tier is above Scrap, spread at random over the taps.
    val plan = remember { List(TAPS) { it < result.tier.ordinal }.shuffled(Random(result.hashCode())) }
    var taps by remember { mutableIntStateOf(0) }
    var tier by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    val lobby = LocalLobby.current
    // The capsule itself is 3D, drawn by the lobby renderer; this overlay only tells it what is happening.
    DisposableEffect(Unit) {
        lobby.capsuleOpenAt = 0L
        lobby.capsuleColor = CapsuleTier.SCRAP.color.toInt()
        lobby.capsuleShown = true
        onDispose { lobby.capsuleShown = false }
    }
    val shown = CapsuleTier.entries[tier]
    val color = Color(shown.color)

    fun knock() {
        if (opened || taps >= TAPS) return
        val charged = plan[taps]
        taps++
        lobby.capsuleKnockAt = System.currentTimeMillis()
        if (charged) {
            tier++
            lobby.capsuleColor = CapsuleTier.entries[tier].color.toInt()
            lobby.capsuleChargeAt = System.currentTimeMillis()
            sfx?.play(Sound.DROP_UPGRADE, pitch = 0.85f + 0.12f * tier)
            sfx?.buzz(45, 210)
            scope.launch { pop.snapTo(1.4f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)) }
            scope.launch { flash.snapTo(0.7f); flash.animateTo(0f, tween(380)) }
        } else {
            sfx?.play(Sound.DROP_TAP, pitch = 0.92f + 0.06f * taps)
            sfx?.buzz(16, 120)
        }
        scope.launch { shake.snapTo(1f); shake.animateTo(0f, tween(420)) }
        if (taps == TAPS) scope.launch {
            delay(if (charged) 750 else 450)
            lobby.capsuleOpenAt = System.currentTimeMillis()
            sfx?.play(Sound.DROP_OPEN)
            sfx?.buzz(90, 255)
            delay(260)
            lobby.capsuleShown = false
            opened = true
            flash.snapTo(1f)
            flash.animateTo(0f, tween(650))
        }
    }

    BoxWithConstraints(
        // While the capsule is closed the lobby renderer does the dimming, so the 3D capsule stays bright.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (opened) 0.8f else 0f))
            .clickable(remember { MutableInteractionSource() }, null) { knock() },
        contentAlignment = Alignment.Center,
    ) {
        val capsuleRoom = maxHeight * 0.46f
        // Darken the menu around the capsule but leave a soft window in the middle for the 3D render underneath.
        if (!opened) Canvas(Modifier.fillMaxSize()) {
            val r = size.height * 0.52f
            drawRect(Brush.radialGradient(0.5f to Color.Transparent, 1f to Color(0xE0050212), center = center, radius = r))
        }
        if (opened) FighterRays(Modifier.size(if (ui.roomy) 640.dp else 520.dp), color)
        if (!opened) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GameText("SPARK CAPSULE", Type.Heading, color = Palette.TextDim, outline = 2.5.dp)
                GameText(shown.label.uppercase(), Type.Display.copy(fontSize = Type.Display.fontSize * 1.25f), color = color, outline = 5.dp,
                    modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                // Room for the 3D capsule, which sits in the middle of the screen.
                Spacer(Modifier.height(capsuleRoom))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (t in CapsuleTier.entries) Canvas(Modifier.size(if (t.ordinal == tier) 20.dp else 14.dp)) {
                        drawCircle(Palette.Ink)
                        drawCircle(if (t.ordinal <= tier) Color(t.color) else Palette.PanelInset, size.minDimension / 2 - 2.5.dp.toPx())
                    }
                }
                Spacer(Modifier.height(8.dp))
                val pulse = 1f + 0.08f * sin(time * 7f)
                GameText(if (taps < TAPS) "TAP TO CHARGE  ·  ${TAPS - taps}" else "HERE IT COMES…", Type.Title, color = Palette.Gold, outline = 3.5.dp,
                    modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
            }
        } else {
            val rise = remember { Animatable(0.4f) }
            androidx.compose.runtime.LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { scaleX = rise.value; scaleY = rise.value }) {
                GameText("${result.tier.label} capsule".uppercase(), Type.Title, color = Color(result.tier.color), outline = 3.5.dp)
                Spacer(Modifier.height(6.dp))
                RewardVisual(result.reward, Modifier.size(if (ui.roomy) 170.dp else 120.dp))
                Spacer(Modifier.height(6.dp))
                GameText(rewardLabel(result.reward), Type.Display, outline = 4.dp, align = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChunkyButton(onDone, Modifier.size(180.dp, 60.dp), if (remaining > 0) ButtonStyle.PURPLE else ButtonStyle.GREEN) { GameText("AWESOME", Type.Heading) }
                    if (remaining > 0) ChunkyButton(onNext, Modifier.size(220.dp, 60.dp), ButtonStyle.GREEN) { GameText("OPEN NEXT ($remaining)", Type.Heading) }
                }
            }
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.85f)))
        if (!opened) PlainText("Capsules charge up at random — the reward is locked in when you open one.", Type.Small,
            Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = -14.dp.toPx() }, align = TextAlign.Center)
    }
}
