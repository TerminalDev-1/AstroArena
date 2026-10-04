package io.github.projectwip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.Reward
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** How many little icons fly from a currency reward to the wallet. */
private const val FLYERS = 14

/**
 * A reward being handed over, played out rather than just shown. Currencies pop out, count up, and a spray of
 * icons arcs into the wallet in the corner, clanking (Bolts) or chiming (Prisms) as each handful lands. Fighters and colourways appear as a
 * silhouette that swells, flashes and is revealed under a banner. Bundles do this for each thing in turn, then
 * lay everything out together. [buttons] appear once it has all settled.
 */
@Composable
fun RewardShowcase(
    title: String, titleColor: Color, reward: Reward,
    /** Wallet totals with this reward already included, so the counters can run up to them. */
    boltsNow: Int, prismsNow: Int,
    note: String? = null,
    buttons: @Composable () -> Unit,
) {
    val sfx = LocalSfx.current
    val ui = LocalUi.current
    val items = remember(reward) { if (reward is Reward.Bundle) reward.items else listOf(reward) }
    var index by remember(reward) { mutableIntStateOf(0) }
    var settled by remember(reward) { mutableStateOf(false) }
    /** Fighter / colourway: false while it is still a silhouette. */
    var revealed by remember(reward) { mutableStateOf(false) }
    val pop = remember { Animatable(0f) }
    val count = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val slam = remember { Animatable(2.4f) }
    var boltSpot by remember { mutableStateOf(Offset.Unspecified) }
    var prismSpot by remember { mutableStateOf(Offset.Unspecified) }

    fun bolts(r: Reward) = (r as? Reward.Bolts)?.amount ?: 0
    fun prisms(r: Reward) = (r as? Reward.Prisms)?.amount ?: 0
    val item = items[index]

    LaunchedEffect(reward, index) {
        pop.snapTo(0f); count.snapTo(0f); fly.snapTo(0f); slam.snapTo(2.4f)
        revealed = false
        if (item is Reward.Bolts || item is Reward.Prisms) {
            sfx?.play(Sound.POP)
            launch { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)) }
            val ticking = launch {
                var n = 0
                while (true) { sfx?.play(Sound.COUNT, 0.5f, 0.85f + minOf(n, 12) * 0.05f); n++; delay(70) }
            }
            // The flyers reach the wallet from about here on. Each handful that lands sounds like what it is:
            // Bolts clank like steel nuts, Prisms chime like glass.
            val landing = if (item is Reward.Bolts) Sound.BOLT_LAND else Sound.PRISM_LAND
            launch { delay(620); repeat(7) { sfx?.play(landing, 0.85f, 0.92f + it * 0.04f); sfx?.buzz(12, 90); delay(110) } }
            launch { fly.animateTo(1f, tween(1500, easing = LinearEasing)) }
            count.animateTo(1f, tween(900))
            ticking.cancel()
            delay(800)
        } else {
            sfx?.play(Sound.WHOOSH)
            pop.animateTo(1f, tween(700))
            revealed = true
            sfx?.play(Sound.UPGRADE)
            sfx?.buzz(80, 240)
            launch { flash.snapTo(1f); flash.animateTo(0f, tween(550)) }
            launch { delay(120); sfx?.play(Sound.BANNER, 0.8f) }
            slam.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            delay(1100)
        }
        if (index < items.lastIndex) index++ else settled = true
    }

    // What the wallet shows: everything from earlier items, plus the share of this one that has landed.
    val landed = ((fly.value - 0.42f) / 0.5f).coerceIn(0f, 1f)
    val boltsShown = if (settled) boltsNow else boltsNow - items.sumOf { bolts(it) } + items.take(index).sumOf { bolts(it) } + (bolts(item) * landed).toInt()
    val prismsShown = if (settled) prismsNow else prismsNow - items.sumOf { prisms(it) } + items.take(index).sumOf { prisms(it) } + (prisms(item) * landed).toInt()
    val big = if (ui.roomy) 170.dp else 120.dp

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FighterRays(Modifier.size(if (ui.roomy) 640.dp else 520.dp), titleColor)
        Row(Modifier.align(Alignment.TopEnd).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CurrencyPill(IconKind.BOLT, boltsShown, Modifier.onGloballyPositioned { boltSpot = it.positionInRoot() + Offset(it.size.height / 2f, it.size.height / 2f) })
            CurrencyPill(IconKind.PRISM, prismsShown, Modifier.onGloballyPositioned { prismSpot = it.positionInRoot() + Offset(it.size.height / 2f, it.size.height / 2f) })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GameText(title.uppercase(), Type.Title, color = titleColor, outline = 3.5.dp)
            Spacer(Modifier.height(6.dp))
            if (settled && items.size > 1) {
                // Everything from the bundle, side by side.
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                    for (r in items) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RewardVisual(r, Modifier.size(if (ui.roomy) 120.dp else 86.dp))
                        GameText(rewardLabel(r), Type.Heading, outline = 3.dp)
                    }
                }
            } else when (item) {
                is Reward.Bolts, is Reward.Prisms -> {
                    val amount = bolts(item) + prisms(item)
                    CurrencyIcon(if (item is Reward.Bolts) IconKind.BOLT else IconKind.PRISM,
                        Modifier.size(big).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -50f })
                    Spacer(Modifier.height(6.dp))
                    val bump = 1f + 0.12f * sin(count.value * 40f) * (1f - count.value)
                    GameText("+${"%,d".format((amount * count.value).toInt())} ${if (item is Reward.Bolts) "Power Ups" else "Crystals"}", Type.Display, outline = 4.dp,
                        modifier = Modifier.graphicsLayer { scaleX = bump; scaleY = bump })
                }
                else -> {
                    val def = Balance.fighter(if (item is Reward.UnlockFighter) item.fighter else (item as Reward.SkinReward).fighter)
                    val skin = (item as? Reward.SkinReward)?.skinIndex ?: 0
                    // A silhouette that swells and trembles, then the reveal.
                    val tremble = if (revealed) 0f else sin(pop.value * 60f) * 5f * pop.value
                    FighterView(def, skin, Modifier.size(big * 1.2f).graphicsLayer {
                        val s = 0.55f + 0.45f * pop.value
                        scaleX = s; scaleY = s; rotationZ = tremble
                    }, pedestal = false, locked = !revealed)
                    if (revealed) {
                        GameText(if (item is Reward.UnlockFighter) "NEW FIGHTER!" else "NEW COLOURWAY!", Type.Title, color = Palette.Gold, outline = 3.5.dp,
                            modifier = Modifier.graphicsLayer { scaleX = slam.value; scaleY = slam.value })
                        GameText(if (item is Reward.UnlockFighter) def.name.uppercase() else "${def.skins[skin].name} ${def.name}".uppercase(),
                            Type.Display, outline = 4.dp, align = TextAlign.Center)
                    } else {
                        GameText("???", Type.Display, outline = 4.dp)
                    }
                }
            }
            if (note != null && settled) { Spacer(Modifier.height(6.dp)); Badge(note, color = Palette.GreenDeep) }
            Spacer(Modifier.height(16.dp))
            // Keeps its space while hidden so nothing jumps when the buttons arrive.
            Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(settled, enter = fadeIn(tween(220))) { buttons() }
            }
        }

        // The spray of icons: burst out of the reward, hang for a beat, then race to the wallet.
        if ((item is Reward.Bolts || item is Reward.Prisms) && fly.value > 0f && fly.value < 1f && !settled) {
            val kind = if (item is Reward.Bolts) IconKind.BOLT else IconKind.PRISM
            val target = if (item is Reward.Bolts) boltSpot else prismSpot
            Canvas(Modifier.fillMaxSize()) {
                if (target == Offset.Unspecified) return@Canvas
                val from = Offset(size.width / 2f, size.height / 2f - 20.dp.toPx())
                for (i in 0 until FLYERS) {
                    val a = i * 2.399f
                    val reach = (70f + (i * 37 % 90)).dp.toPx()
                    val out = (fly.value / 0.25f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
                    val home = ((fly.value - 0.36f - i * 0.012f) / 0.4f).coerceIn(0f, 1f).let { it * it }
                    if (home >= 1f) continue
                    val burst = from + Offset(cos(a) * reach * out, sin(a) * reach * out * 0.8f)
                    val p = burst + (target - burst) * home
                    val s = 30.dp.toPx() * (1f - 0.35f * home) * out.coerceAtLeast(0.2f)
                    withTransform({ translate(p.x - s / 2, p.y - s / 2); scale(s, s, Offset.Zero) }) { drawIconUnit(kind, null) }
                }
            }
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.8f)))
    }
}
