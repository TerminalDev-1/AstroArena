package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import io.github.projectwip.data.Economy
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalLobby
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Opening an Arena Box. What is inside is already decided (and saved) before this is shown; this only reveals it.
 * One tap and the box takes the colour of the rarest thing in it, winds up and blows open. Then its items come
 * out one at a time, each with its own rarity, while a counter in the corner says how many are still to come.
 * The box is unstable, and the screen glitches along with it.
 * [onOpenAll] opens everything that is left in one go; it is handed this box's result when this one has not
 * been shown yet, so that it can be shown with the rest.
 */
@Composable
fun CapsuleOpenOverlay(
    result: CapsuleResult, remaining: Int, boltsNow: Int, prismsNow: Int, roadNow: Int, roadGoal: Int,
    onNext: () -> Unit, onOpenAll: (CapsuleResult?) -> Unit, onDone: () -> Unit,
) = key(result) {
    val sfx = LocalSfx.current
    val scope = rememberCoroutineScope()
    val time by rememberAnimTime()
    var opening by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    /** Which of the box's items is on show. */
    var index by remember { mutableIntStateOf(0) }
    val pop = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    val countPop = remember { Animatable(1f) }
    val lobby = LocalLobby.current
    // The box itself is 3D, drawn by the lobby renderer; this overlay only tells it what is happening.
    DisposableEffect(Unit) {
        lobby.capsuleOpenAt = 0L
        lobby.capsuleSplitAt = 0L
        lobby.capsulePieces = 1
        lobby.capsuleColor = BOX_COLOR.toInt()
        lobby.capsuleGlitch = 0.2f
        lobby.capsuleShown = true
        onDispose { lobby.capsuleShown = false }
    }
    // The box never sits quietly: it stutters for as long as it is closed.
    LaunchedEffect(opened) {
        val pace = Random(result.hashCode() + 3)
        while (!opened) {
            sfx?.play(Sound.GLITCH, 0.3f, 0.7f + pace.nextFloat() * 0.8f)
            delay(360L + pace.nextInt(380))
        }
    }
    // "Open all" makes sense when there is a known number of others waiting (the debug menu's endless boxes are not).
    val others = remaining in 1..1_000_000
    // A box is a box: it has no rarity of its own, and doesn't give away what is inside.
    val color = Color(BOX_COLOR)
    val bonus = result.items.size - io.github.projectwip.data.SparkCapsules.BOX_ITEMS

    fun open() {
        if (opening) return
        opening = true
        lobby.capsuleKnockAt = System.currentTimeMillis()
        lobby.capsuleChargeAt = System.currentTimeMillis()
        lobby.capsuleGlitch = 0.4f
        sfx?.play(Sound.GLITCH, 0.5f, 0.95f)
        sfx?.play(Sound.DROP_UPGRADE, pitch = 0.95f)
        sfx?.say(OPEN_LINES[(result.hashCode() and 0x7fffffff) % OPEN_LINES.size], io.github.projectwip.data.VoiceStyle.ANNOUNCER)
        sfx?.buzz(45, 210)
        scope.launch { pop.snapTo(1.4f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)) }
        scope.launch { flash.snapTo(0.7f); flash.animateTo(0f, tween(380)) }
        scope.launch {
            delay(750)
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
            scope.launch { flash.snapTo(1f); flash.animateTo(0f, tween(650)) }
            // The announcer has a word for what came out: a fighter, more items than usual, or an ordinary box.
            delay(1500)
            sfx?.say(when {
                result.items.any { hasFighter(it.reward) } -> FIGHTER_LINES
                bonus > 0 -> BONUS_LINES
                else -> PLAIN_LINES
            }.let { it[(result.hashCode() and 0x7fffffff) % it.size] }, io.github.projectwip.data.VoiceStyle.ANNOUNCER)
        }
    }

    fun nextItem() {
        if (index >= result.items.lastIndex) return
        index++
        sfx?.play(Sound.POP, pitch = 0.9f + 0.06f * index)
        sfx?.buzz(16, 120)
        scope.launch { countPop.snapTo(1.5f); countPop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)) }
        scope.launch { flash.snapTo(0.35f); flash.animateTo(0f, tween(300)) }
    }

    BoxWithConstraints(
        // While the box is closed the lobby renderer does the dimming, so the 3D box stays bright.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (opened) 0.8f else 0f))
            .clickable(remember { MutableInteractionSource() }, null) { open() },
        contentAlignment = Alignment.Center,
    ) {
        val boxRoom = maxHeight * 0.5f
        if (!opened) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlitchText("ARENA BOX", Type.Display.copy(fontSize = Type.Display.fontSize * 1.25f), color, 5.dp, time, if (opening) 0.9f else 0.5f,
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                // Room for the 3D box, which sits in the middle of the screen.
                Spacer(Modifier.height(boxRoom))
                val pulse = 1f + 0.08f * sin(time * 7f)
                GameText(if (opening) "HERE IT COMES…" else "TAP TO OPEN", Type.Title, color = Palette.Gold, outline = 3.5.dp,
                    modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
            }
        } else {
            val item = result.items[index]
            val last = index == result.items.lastIndex
            // The counters run up to what the wallet held once this item was in it: everything, less what is still to come.
            val later = result.items.drop(index + 1).map { it.reward }
            key(index) {
                io.github.projectwip.ui.RewardShowcase(
                    // Anything past the usual three is a bonus: that is the luck of the box.
                    if (index >= io.github.projectwip.data.SparkCapsules.BOX_ITEMS) "Bonus item!" else "Item ${index + 1}", if (index >= io.github.projectwip.data.SparkCapsules.BOX_ITEMS) Palette.Gold else Palette.Cyan, item.reward,
                    boltsNow - later.sumOf { boltsIn(it) }, prismsNow - later.sumOf { prismsIn(it) },
                    roadNow = (roadNow - later.sumOf { Economy.creditsIn(it) }).coerceAtLeast(Economy.creditsIn(item.reward)), roadGoal = roadGoal,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!last) ChunkyButton({ nextItem() }, Modifier.size(220.dp, 60.dp), ButtonStyle.GREEN) { GameText("NEXT ITEM", Type.Heading) }
                        else {
                            ChunkyButton(onDone, Modifier.size(180.dp, 60.dp), if (remaining > 0) ButtonStyle.PURPLE else ButtonStyle.GREEN) { GameText("AWESOME", Type.Heading) }
                            if (remaining > 0) ChunkyButton(onNext, Modifier.size(220.dp, 60.dp), ButtonStyle.GREEN) { GameText(if (remaining > 999) "OPEN NEXT" else "OPEN NEXT ($remaining)", Type.Heading) }
                            if (others && remaining > 1) ChunkyButton({ onOpenAll(null) }, Modifier.size(220.dp, 60.dp), ButtonStyle.GOLD) { GameText("OPEN ALL (${"%,d".format(remaining)})", Type.Heading) }
                        }
                    }
                }
            }
            // How many items are still in the box.
            Panel(Modifier.align(Alignment.BottomEnd).padding(18.dp).graphicsLayer { scaleX = countPop.value; scaleY = countPop.value }, cut = 10.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GameText("${result.items.lastIndex - index}", Type.Title, color = if (last) Palette.TextDim else Palette.Gold, outline = 3.dp)
                    GameText(if (result.items.lastIndex - index == 1) "ITEM REMAINING" else "ITEMS REMAINING", Type.Label, color = Palette.TextDim, outline = 2.dp)
                }
            }
        }
        if (!opened) GlitchBars(time, color, if (opening) 0.7f else 0.4f)
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.85f)))
        // Skips the reveal: this box and every other one are opened and shown together.
        if (!opened && others && !opening) ChunkyButton({ onOpenAll(result) }, Modifier.align(Alignment.TopEnd).padding(18.dp).size(230.dp, 58.dp), ButtonStyle.GOLD) {
            GameText("OPEN ALL (${"%,d".format(remaining + 1)})", Type.Heading)
        }
        if (!opened) PlainText("An Arena Box holds three items, and with luck a few more. What is inside is locked in when you open it.", Type.Small,
            Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = -14.dp.toPx() }, align = TextAlign.Center)
    }
}

/** Every Arena Box is the same plain crate. */
private const val BOX_COLOR = 0xFFFFB03A

private val OPEN_LINES = listOf("Let's see what's inside!", "Here we go!", "Open it up!", "Fingers crossed!")
private val PLAIN_LINES = listOf("Three items. Not bad!", "Every little helps!", "A tidy box!", "Straight into the bank!")
private val BONUS_LINES = listOf("Extra items! Lucky you!", "Ooh, there's more in here!", "A bonus! Nice box!", "That one was stuffed!")
private val FIGHTER_LINES = listOf("A new fighter! What a box!", "Somebody new joins the team!")

private fun hasFighter(r: io.github.projectwip.data.Reward): Boolean = when (r) {
    is io.github.projectwip.data.Reward.UnlockFighter -> true
    is io.github.projectwip.data.Reward.Bundle -> r.items.any { hasFighter(it) }
    else -> false
}
