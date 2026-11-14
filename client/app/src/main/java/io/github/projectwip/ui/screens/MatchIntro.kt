package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.sim.Fighter
import io.github.projectwip.sim.Match
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The line-up shown before a match starts: your team sliding in from the left, the opponents from the right
 * and a "VS" slamming down between them (or all ten fighters for a free-for-all). Tap to skip.
 */
@Composable
fun MatchIntro(match: Match, onDone: () -> Unit) {
    val sfx = LocalSfx.current
    val ui = LocalUi.current
    val slide = remember { Animatable(0f) }
    val vs = remember { Animatable(3f) }
    val vsAlpha = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        sfx?.play(Sound.WHOOSH)
        launch { slide.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        delay(380)
        sfx?.play(Sound.VERSUS)
        sfx?.buzz(60, 220)
        launch { vsAlpha.animateTo(1f, tween(90)) }
        vs.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        delay(1900)
        fade.animateTo(0f, tween(260))
        onDone()
    }

    val me = match.player
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color(0xE6140B38))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDone),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GameText(match.config.mode.title.uppercase(), Type.Display, color = Palette.Gold, outline = 4.dp)
            PlainText("${match.world.arena.name} · ${match.config.mode.tagline}", Type.Label, color = Palette.TextDim)
            Spacer(Modifier.height(10.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TeamColumn("YOUR TEAM", match.world.fighters.filter { it.team == me.team }, me, Palette.CyanDeep, Palette.Ally,
                        Modifier.weight(1f).graphicsLayer { translationX = -(1f - slide.value) * size.width * 1.2f })
                    Box(Modifier.width(if (ui.wide) 190.dp else 130.dp), contentAlignment = Alignment.Center) {
                        GameText("VS", Type.Display.copy(fontSize = Type.Display.fontSize * if (ui.roomy) 3f else 2.2f), color = Palette.Gold, outline = 7.dp,
                            modifier = Modifier.graphicsLayer { scaleX = vs.value; scaleY = vs.value; alpha = vsAlpha.value; rotationZ = -8f })
                    }
                    if (match.bossMode) EntityColumn(Modifier.weight(1f).graphicsLayer { translationX = (1f - slide.value) * size.width * 1.2f })
                    else TeamColumn("OPPONENTS", match.world.fighters.filter { it.team != me.team }, me, Palette.RedDeep, Palette.Enemy,
                        Modifier.weight(1f).graphicsLayer { translationX = (1f - slide.value) * size.width * 1.2f })
                }
            Spacer(Modifier.height(8.dp))
            PlainText(
                if (match.bossMode) "Hit the shadow under it. Dodge its marks and its flares. Beat it before time runs out"
                else "First team to ${match.world.rules.koTarget} knockouts wins",
                Type.Label, color = Color.White,
            )
        }
    }
}

/** The other side in the Entity's fight: the Entity itself, glitching between the souls it carries. */
@Composable
private fun EntityColumn(modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GameText("THE ENTITY", Type.Title, color = Color(0xFFC9A6FF), outline = 3.dp)
        Panel(Modifier.weight(1f).fillMaxWidth(), color = Color(0xFF3A2390), colorBottom = Palette.PanelDark, cut = 14.dp) {
            io.github.projectwip.ui.SoulView(Modifier.fillMaxSize(), voidCycle = true)
        }
    }
}

@Composable
private fun TeamColumn(title: String, fighters: List<Fighter>, me: Fighter, plate: Color, accent: Color, modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GameText(title, Type.Title, color = accent, outline = 3.dp)
        // One fighter on a side (Boss Mode) gets the whole card: a big portrait with the name underneath.
        for (f in fighters) FighterCard(f, f === me, plate, Modifier.weight(1f).fillMaxWidth(), tall = fighters.size == 1)
    }
}

/** One fighter in the line-up: portrait, who is playing it, and what it brings. */
@Composable
private fun FighterCard(f: Fighter, isMe: Boolean, plate: Color, modifier: Modifier, tall: Boolean = false) {
    Panel(modifier.fillMaxHeight(), color = if (isMe) Palette.GreenDeep else plate, colorBottom = Palette.PanelDark, cut = 14.dp) {
        if (tall) {
            // Narrow card (ten to a screen): portrait on top, name underneath.
            Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FighterView(f.def, f.skin, Modifier.weight(1f).fillMaxWidth(), pedestal = false)
                GameText(f.name, Type.Label, color = if (isMe) Palette.Gold else Color.White, outline = 2.dp)
                PlainText("${f.def.name} · LV ${f.level}", Type.Small, color = Color.White, maxLines = 1)
            }
            return@Panel
        }
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FighterView(f.def, f.skin, Modifier.fillMaxHeight().width(74.dp), pedestal = false)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                GameText(f.name, Type.Heading, color = if (isMe) Palette.Gold else Color.White, outline = 2.5.dp)
                PlainText("${f.def.name} · ${f.def.role}", Type.Small, color = Color.White, maxLines = 1)
                PlainText("${"%,d".format(f.maxHp)} HP · ${f.attackDamage} dmg", Type.Small, color = Palette.TextDim, maxLines = 1)
            }
            Badge("LV ${f.level}", color = Palette.OrangeDeep)
        }
    }
}
