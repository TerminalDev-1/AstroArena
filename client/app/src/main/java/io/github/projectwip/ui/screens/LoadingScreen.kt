package io.github.projectwip.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.projectwip.BuildConfig
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.sin

/**
 * Shown while the game gets ready: it connects to the game server, checks GitHub for a newer release and builds
 * its sounds and music. [progress] is real (0..1 of that work), and [status] says which part is happening.
 *
 * Plain, by the user's decision (build 111): black, and the white ASTROARENA title from the end of the opening,
 * fading and easing in the same way, then breathing gently. The loading is a thin dim line and a few small words at
 * the bottom. [onSkip] (dev builds) stops waiting for the server; [onIntro] plays the opening.
 */
@Composable
fun LoadingScreen(progress: Float, status: String, onSkip: (() -> Unit)? = null, onIntro: (() -> Unit)? = null) {
    val time by rememberAnimTime()
    val roomy = LocalUi.current.roomy
    Box(
        // Swallows touches so nothing underneath can be pressed while loading.
        Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) { },
    ) {
        // The title, one letter at a time (the user's ask): each fades up and drops into place from a little larger,
        // then the whole word breathes. (No `continue` in this loop: Compose can't skip a group mid-loop.)
        val all = ((time - LETTERS.length * LETTER_GAP) / 0.5f).coerceIn(0f, 1f)
        val breathe = 1f + 0.012f * sin(time * 1.5f) * all
        val style = Type.Display.copy(fontSize = if (roomy) 76.sp else 54.sp)
        Row(Modifier.align(Alignment.Center).graphicsLayer { scaleX = breathe; scaleY = breathe }) {
            for ((i, c) in LETTERS.withIndex()) {
                val k = ((time - 0.25f - i * LETTER_GAP) / 0.35f).coerceIn(0f, 1f)
                val eased = 1f - (1f - k) * (1f - k)
                GameText(c.toString(), style, modifier = Modifier.graphicsLayer {
                    alpha = eased
                    val s = 1.35f - 0.35f * eased
                    scaleX = s; scaleY = s
                    translationY = -18f * (1f - eased)
                })
            }
        }

        // The loading: a thin dim line, and what is happening, small, under it.
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (roomy) 38.dp else 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.width(if (roomy) 360.dp else 260.dp).height(3.dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.6f), size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = r)
            }
            Spacer(Modifier.height(8.dp))
            PlainText(status.trimEnd('…', '.', '!').uppercase() + if (status == "Ready!") "" else ".".repeat(1 + (time * 2.5f).toInt() % 3),
                Type.Small.copy(letterSpacing = Type.Small.fontSize * 0.25f), color = Color.White.copy(alpha = 0.45f), maxLines = 1)
        }

        if (onSkip != null) ChunkyButton(onSkip, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 18.dp).size(240.dp, 50.dp), ButtonStyle.GREY, lip = 4.dp) {
            GameText("PLAY OFFLINE (DEV)", Type.Label, outline = 2.dp)
        }
        // The opening, any time (the user's ask): in every build, release too.
        if (onIntro != null) ChunkyButton(onIntro, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 18.dp).size(190.dp, 50.dp), ButtonStyle.GREY, lip = 4.dp) {
            GameText("PLAY INTRO", Type.Label, outline = 2.dp)
        }
        PlainText(BuildConfig.VERSION_NAME + " · build " + BuildConfig.BUILD, Type.Small, Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Color.White.copy(alpha = 0.3f))
    }
}

private const val LETTERS = "ASTROARENA"
private const val LETTER_GAP = 0.12f
