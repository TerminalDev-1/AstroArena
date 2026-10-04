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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.CapsuleResult
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalLobby
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/** Knocks it takes to open a capsule. Some of them charge it up a tier, the rest just rattle it. */
private val TAPS = CapsuleTier.entries.size - 1

/**
 * Opening a Spark Capsule. The result is already decided (and saved) before this is shown; the taps only
 * reveal it: each knock either rattles the capsule or charges it up a tier, and the last one overloads it.
 * The capsule is unstable, more so with every tier, and the screen glitches along with it.
 * [onOpenAll] opens everything that is left in one go; it is handed this capsule's result when this one has not
 * been shown yet, so that it can be shown with the rest.
 */
@Composable
fun CapsuleOpenOverlay(
    result: CapsuleResult, remaining: Int, boltsNow: Int, prismsNow: Int,
    onNext: () -> Unit, onOpenAll: (CapsuleResult?) -> Unit, onDone: () -> Unit,
) = key(result) {
    val sfx = LocalSfx.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val time by rememberAnimTime()
    // Which knocks charge the capsule: as many as the tier is above Scrap, spread at random over the taps.
    val plan = remember { List(TAPS) { it < result.tier.ordinal }.shuffled(Random(result.hashCode())) }
    var taps by remember { mutableIntStateOf(0) }
    var tier by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    // A capsule that splits doubles (2, 4, 8) on separate knocks before the last one.
    val splitTaps = remember {
        val doublings = Integer.numberOfTrailingZeros(result.pieces.coerceAtLeast(1))
        (1 until TAPS).shuffled(Random(result.hashCode() + 7)).take(doublings).toSet()
    }
    var pieces by remember { mutableIntStateOf(1) }
    val splitPop = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    val lobby = LocalLobby.current
    // The capsule itself is 3D, drawn by the lobby renderer; this overlay only tells it what is happening.
    DisposableEffect(Unit) {
        lobby.capsuleOpenAt = 0L
        lobby.capsuleSplitAt = 0L
        lobby.capsulePieces = 1
        lobby.capsuleColor = CapsuleTier.SCRAP.color.toInt()
        lobby.capsuleGlitch = 0.2f
        lobby.capsuleShown = true
        onDispose { lobby.capsuleShown = false }
    }
    // The drop never sits quietly: it stutters for as long as it is closed.
    LaunchedEffect(opened) {
        val pace = Random(result.hashCode() + 3)
        while (!opened) {
            sfx?.play(Sound.GLITCH, 0.3f, 0.7f + pace.nextFloat() * 0.8f)
            delay(360L + pace.nextInt(380))
        }
    }
    // "Open all" makes sense when there is a known number of others waiting (the debug menu's endless drops are not).
    val others = remaining in 1..1_000_000
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
            // The higher it charges, the less stable it gets.
            lobby.capsuleGlitch = 0.2f + 0.16f * tier
            sfx?.play(Sound.GLITCH, 0.5f, 0.9f + 0.08f * tier)
            sfx?.play(Sound.DROP_UPGRADE, pitch = 0.85f + 0.12f * tier)
            sfx?.buzz(45, 210)
            scope.launch { pop.snapTo(1.4f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)) }
            scope.launch { flash.snapTo(0.7f); flash.animateTo(0f, tween(380)) }
        } else {
            sfx?.play(Sound.DROP_TAP, pitch = 0.92f + 0.06f * taps)
            sfx?.buzz(16, 120)
        }
        scope.launch { shake.snapTo(1f); shake.animateTo(0f, tween(420)) }
        if (taps in splitTaps) {
            pieces *= 2
            lobby.capsulePieces = pieces
            lobby.capsuleSplitAt = System.currentTimeMillis()
            sfx?.play(Sound.POP, pitch = 0.7f + 0.1f * pieces / 2)
            sfx?.play(Sound.DROP_UPGRADE, 0.7f, 1.3f + 0.08f * pieces / 2)
            sfx?.buzz(60, 230)
            scope.launch { splitPop.snapTo(1.6f); splitPop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)) }
        }
        if (taps == TAPS) scope.launch {
            delay(if (charged) 750 else 450)
            // It winds up and collapses, tearing all the way, then blows apart.
            lobby.capsuleOpenAt = System.currentTimeMillis()
            sfx?.play(Sound.GLITCH, 0.9f, 0.8f)
            sfx?.buzz(30, 140)
            delay((io.github.projectwip.render3d.Capsule3D.WIND * 1000).toLong())
            sfx?.play(Sound.DROP_OPEN)
            sfx?.buzz(90, 255)
            delay(((io.github.projectwip.render3d.Capsule3D.GONE - io.github.projectwip.render3d.Capsule3D.WIND) * 1000).toLong() - 120)
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
        if (!opened) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GameText("SPARK DROP", Type.Heading, color = Palette.TextDim, outline = 2.5.dp)
                GlitchText(shown.label.uppercase(), Type.Display.copy(fontSize = Type.Display.fontSize * 1.25f), color, 5.dp, time, 0.5f + 0.2f * tier,
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
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
                if (pieces > 1) GameText("SPLIT INTO $pieces!  +${pieces - 1} DROP${if (pieces > 2) "S" else ""}", Type.Heading, color = Palette.Green, outline = 3.dp,
                    modifier = Modifier.graphicsLayer { scaleX = splitPop.value; scaleY = splitPop.value })
            }
        } else {
            io.github.projectwip.ui.RewardShowcase(
                "${result.tier.label} drop", Color(result.tier.color), result.reward, boltsNow, prismsNow,
                note = if (result.split) "SPLIT INTO ${result.pieces} · +${result.pieces - 1} DROP${if (result.pieces > 2) "S" else ""}" else null,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChunkyButton(onDone, Modifier.size(180.dp, 60.dp), if (remaining > 0) ButtonStyle.PURPLE else ButtonStyle.GREEN) { GameText("AWESOME", Type.Heading) }
                    if (remaining > 0) ChunkyButton(onNext, Modifier.size(220.dp, 60.dp), ButtonStyle.GREEN) { GameText(if (remaining > 999) "OPEN NEXT" else "OPEN NEXT ($remaining)", Type.Heading) }
                    if (others && remaining > 1) ChunkyButton({ onOpenAll(null) }, Modifier.size(220.dp, 60.dp), ButtonStyle.GOLD) { GameText("OPEN ALL (${"%,d".format(remaining)})", Type.Heading) }
                }
            }
        }
        if (!opened) GlitchBars(time, color, 0.4f + 0.15f * tier)
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.85f)))
        // Skips the knocking: this drop and every other one are opened and shown together.
        if (!opened && others && taps < TAPS) ChunkyButton({ onOpenAll(result) }, Modifier.align(Alignment.TopEnd).padding(18.dp).size(230.dp, 58.dp), ButtonStyle.GOLD) {
            GameText("OPEN ALL (${"%,d".format(remaining + 1)})", Type.Heading)
        }
        if (!opened) PlainText("Drops charge up at random, and now and then one splits — into two, four or even eight — and the pieces roll better. The result is locked in when you open one.", Type.Small,
            Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = -14.dp.toPx() }, align = TextAlign.Center)
    }
}
