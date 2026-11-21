package io.github.projectwip.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.VoiceStyle
import io.github.projectwip.render3d.EntityCut
import io.github.projectwip.render3d.EntityScript
import io.github.projectwip.render3d.EntitySceneView
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.Type

/**
 * One of the Entity's cutscenes ([EntityCut]): the 3D scene, its words on the game's purple plates, said in its
 * voice, the moments' sounds, the title at the end, and SKIP. [line] picks which of its lines (corruption has several).
 */
@Composable
fun EntityCutscene(cut: EntityCut, fighter: FighterId, skin: Int, line: Int, sfx: Sfx?, modifier: Modifier = Modifier, onDone: () -> Unit) {
    var time by remember { mutableStateOf(0f) }
    var said by remember { mutableIntStateOf(-1) }
    var leaving by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<EntitySceneView?>(null) }
    val fade = remember { Animatable(0f) }
    val length = EntityScript.length(cut)
    LaunchedEffect(Unit) {
        when (cut) {
            EntityCut.VICTORY -> { sfx?.play(Sound.VOID, 1f, 1.2f); sfx?.play(Sound.THUNDER, 0.8f, 0.8f) }
            EntityCut.DEFEAT -> sfx?.play(Sound.VOID, 1f, 0.8f)
            EntityCut.CORRUPT -> { sfx?.play(Sound.VOID, 1f, 1.1f); sfx?.play(Sound.WHOOSH, 0.8f, 0.6f) }
            else -> { sfx?.play(Sound.VOID, 1f, 0.75f); sfx?.play(Sound.WHOOSH, 0.7f, 0.5f) }
        }
        fade.animateTo(1f, tween(350))
        val start = withFrameNanos { it }
        var cues = 0
        var nextBeat = 0f
        while (!leaving) {
            withFrameNanos { now ->
                time = (now - start) / 1e9f
                view?.params?.time = time
            }
            // The big moments' sounds.
            if (cut == EntityCut.VICTORY && cues == 0 && time > 7.4f) { cues++; sfx?.play(Sound.WHOOSH, 1f, 0.5f) }
            if (cut == EntityCut.VICTORY && cues == 1 && time > 8.6f) { cues++; sfx?.play(Sound.THUNDER, 1f, 0.6f); sfx?.play(Sound.VICTORY, 0.9f) }
            if (cut == EntityCut.DEFEAT && cues == 0 && time > 3.3f) { cues++; sfx?.play(Sound.PORTAL_OPEN, 0.9f) }
            if (cut == EntityCut.DEFEAT && cues == 1 && time > 8.15f) { cues++; sfx?.play(Sound.WHOOSH, 1f, 0.45f); sfx?.play(Sound.DEFEAT, 0.9f) }
            // A drain: the yank back off their feet, and (the last time) the pull inside.
            if (cut.drain && cues == 0 && time > 1f) { cues++; sfx?.play(Sound.THUNDER, 0.9f, 0.7f); sfx?.play(Sound.WHOOSH, 1f, 0.42f); sfx?.buzz(260, 255) }
            if (cut.drain && cues == 1 && time > 5.6f) { cues++; sfx?.play(Sound.HIT, 1f, 0.4f); sfx?.play(Sound.TEMPEST_HIT, 0.7f, 0.5f); sfx?.buzz(160, 255) }
            // Its grip: a heartbeat thudding, faster as it drains them.
            if (cut.drain && time > nextBeat && time > 1.4f && time < 5.5f) {
                sfx?.play(Sound.HIT, 0.75f, 0.32f)
                nextBeat = time + lerpF(0.85f, 0.45f, ((time - 1.4f) / 4f).coerceIn(0f, 1f))
            }
            if (cut.last && cues == 2 && time > 7.8f) { cues++; sfx?.play(Sound.VOID, 1f, 0.6f) }
            if (time >= length) leaving = true
        }
    }
    LaunchedEffect(leaving) { if (leaving) { fade.animateTo(0f, tween(400)); onDone() } }

    val beats = remember(cut, line) { EntityScript.beats(cut, line) }
    val beat = EntityScript.beatAt(cut, line, time)
    LaunchedEffect(beat) {
        if (beat < 0 || beat == said) return@LaunchedEffect
        said = beat
        // (The fighter's own words are only shown: the Entity's are said in its voice.)
        if (!beats[beat].fighter && io.github.projectwip.data.Balance.DRAGON_SPEAKS) sfx?.say(beats[beat].text, VoiceStyle.VOID, force = true, queue = true)
    }

    Box(modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = fade.value }) {
        AndroidView({ ctx -> EntitySceneView(ctx).also { v -> v.params.cut = cut; v.params.fighter = fighter; v.params.skin = skin; view = v } }, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.07f).background(Color.Black))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.07f).background(Color.Black))
        if (beat >= 0) {
            Panel(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).widthIn(max = 640.dp), color = Color(0xFF3A2390), colorBottom = Color(0xFF1E1150)) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GameText(if (beats[beat].fighter) io.github.projectwip.data.Balance.fighter(fighter).name.uppercase() else "THE DRAGON", Type.Eyebrow,
                        color = if (beats[beat].fighter) Color(0xFF8FE6FF) else Color(0xFFC9A6FF))
                    GameText(beats[beat].text, Type.Heading.copy(fontSize = 19.sp), color = Palette.Text, align = TextAlign.Center, maxLines = 2)
                }
            }
        }
        // A drain: a red flash as it strikes and as they hit the floor, and red closing in at the edges, pulsing
        // with the heartbeat, while it chokes them.
        if (cut.drain) {
            val hold = ((time - 1f) / 0.4f).coerceIn(0f, 1f) * (1f - ((time - 5.3f) / 0.5f).coerceIn(0f, 1f))
            val beat = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(time * 4.2f))
            // (Each flash only from its own moment: the second one was lit the whole time before it.)
            val yankFlash = if (time >= 1f) 1f - ((time - 1f) / 0.3f).coerceIn(0f, 1f) else 0f
            val slamFlash = if (time >= 5.6f) 1f - ((time - 5.6f) / 0.3f).coerceIn(0f, 1f) else 0f
            val flash = maxOf(yankFlash, slamFlash)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                if (hold > 0f) drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0xFFB0001C).copy(alpha = 0.75f * hold * beat),
                    center = center, radius = size.maxDimension * 0.62f))
                if (flash > 0f && time < 6f) drawRect(Color(0xFFFF3B4E).copy(alpha = 0.55f * flash))
            }
        }
        // The title, at the end.
        val titleAt = when (cut) {
            EntityCut.VICTORY -> 9.8f; EntityCut.DEFEAT -> 11.2f; EntityCut.CORRUPT -> 5.4f
            EntityCut.DRAIN, EntityCut.DRAIN_WEAK -> 5.9f; EntityCut.DRAIN_FAINT, EntityCut.DRAIN_OBEY -> 9.2f
        }
        if (time >= titleAt) {
            val k = ((time - titleAt) / 0.35f).coerceIn(0f, 1f)
            val (word, color) = when (cut) {
                EntityCut.VICTORY -> "VICTORY" to Palette.Gold
                EntityCut.DEFEAT -> "DEFEAT" to Palette.Red
                EntityCut.CORRUPT -> "DRAINED DRY" to Color(0xFFC9A6FF)
                EntityCut.DRAIN -> "DRAINED" to Color(0xFFFF6A7A)
                EntityCut.DRAIN_WEAK -> "WEAKER" to Color(0xFFFF6A7A)
                EntityCut.DRAIN_FAINT, EntityCut.DRAIN_OBEY -> "DRAINED DRY" to Color(0xFFC9A6FF)
            }
            Box(Modifier.align(if (cut == EntityCut.VICTORY) Alignment.TopCenter else Alignment.Center).padding(top = if (cut == EntityCut.VICTORY) 44.dp else 0.dp)) {
                GameText(word, Type.Display.copy(fontSize = if (cut == EntityCut.CORRUPT || cut.last) 40.sp else 64.sp), color = color, outline = 6.dp,
                    modifier = Modifier.graphicsLayer { alpha = k; scaleX = 1.4f - 0.4f * k; scaleY = 1.4f - 0.4f * k })
            }
        }
        ChunkyButton({ leaving = true }, Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp), style = ButtonStyle.GREY) {
            GameText("SKIP", Type.Label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
