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
import io.github.projectwip.data.VoiceStyle
import io.github.projectwip.render3d.IntroScript
import io.github.projectwip.render3d.IntroView
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Type

/**
 * The opening a new player sees the first time they open the game (the user's idea): how Swish came to the Entity.
 * The scene is [io.github.projectwip.render3d.IntroScene]; this keeps its clock, says each line in its speaker's voice,
 * shows the words, and lets the player skip it.
 */
@Composable
fun IntroCutscene(sfx: Sfx?, onDone: () -> Unit) {
    var time by remember { mutableStateOf(0f) }
    var said by remember { mutableIntStateOf(-1) }
    var leaving by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<IntroView?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(600))
        val start = withFrameNanos { it }
        while (!leaving) {
            withFrameNanos { now ->
                time = (now - start) / 1e9f
                view?.params?.time = time
            }
            if (time >= IntroScript.LENGTH) leaving = true
        }
    }
    LaunchedEffect(leaving) { if (leaving) { fade.animateTo(0f, tween(500)); onDone() } }

    // Each line in its speaker's voice as it comes; the scene's big moments get a sound.
    val beat = IntroScript.beatAt(time)
    LaunchedEffect(beat) {
        if (beat < 0 || beat == said) return@LaunchedEffect
        said = beat
        val b = IntroScript.BEATS[beat]
        if (b.who == IntroScript.Who.ENTITY) sfx?.say(b.text, VoiceStyle.VOID, force = true, queue = true)
        else sfx?.say(b.text, VoiceStyle.STORM, force = true, queue = true)
        val first = IntroScript.BEATS.indexOfFirst { it.shot == b.shot } == beat
        if (first) when (b.shot) {
            IntroScript.Shot.RISE -> { sfx?.play(Sound.THUNDER, 0.9f, 0.6f); sfx?.play(Sound.VOID, 1f) }
            IntroScript.Shot.FLARES -> sfx?.play(Sound.PORTAL_OPEN, 0.9f)
            IntroScript.Shot.PULL -> sfx?.play(Sound.WHOOSH, 0.9f, 0.6f)
            IntroScript.Shot.KNEEL -> sfx?.play(Sound.VOID, 0.8f, 0.8f)
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = fade.value }) {
        AndroidView({ ctx -> IntroView(ctx).also { view = it } }, Modifier.fillMaxSize())
        // Letterbox bars: it is a film.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.08f).background(Color.Black))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.08f).background(Color.Black))
        }
        // The words, on the game's plates: the Entity's purple, Swish's blue.
        if (beat >= 0) {
            val b = IntroScript.BEATS[beat]
            val entity = b.who == IntroScript.Who.ENTITY
            Panel(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp).widthIn(max = 640.dp),
                color = if (entity) Color(0xFF3A2390) else Color(0xFF1E4F9A),
                colorBottom = if (entity) Color(0xFF1E1150) else Color(0xFF0E2A5C),
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GameText(if (entity) "THE ENTITY" else "SWISH", Type.Eyebrow, color = if (entity) Color(0xFFC9A6FF) else Color(0xFF9FDBFF))
                    GameText(b.text, Type.Heading.copy(fontSize = 19.sp), color = Palette.Text, align = TextAlign.Center, maxLines = 2)
                }
            }
        }
        // The end: the name of the game, out of the dark.
        if (time >= IntroScript.END_AT) {
            val k = ((time - IntroScript.END_AT) / 1.2f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = k)), contentAlignment = Alignment.Center) {
                GameText("ASTROARENA", Type.Display.copy(fontSize = 54.sp), modifier = Modifier.graphicsLayer { alpha = k; scaleX = 0.9f + 0.1f * k; scaleY = 0.9f + 0.1f * k })
            }
        }
        ChunkyButton({ leaving = true }, Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp), style = ButtonStyle.GREY) {
            GameText("SKIP", Type.Label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

