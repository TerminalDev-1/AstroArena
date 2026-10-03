package io.github.projectwip.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.BuildConfig
import io.github.projectwip.net.UpdateInfo
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.ProgressBar
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.sin

/**
 * Shown while the game gets ready: it checks GitHub for a newer release and builds its sounds and music.
 * [progress] is real (0..1 of that work), and [status] says which part is happening.
 */
@Composable
fun LoadingScreen(progress: Float, status: String) {
    val time by rememberAnimTime()
    Box(
        // Swallows touches so nothing underneath can be pressed while loading.
        Modifier.fillMaxSize().background(Color(0xF00B0620)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GameIcon(IconKind.CUP, Modifier.size(96.dp).graphicsLayer { val s = 1f + 0.05f * sin(time * 3f); scaleX = s; scaleY = s })
            Spacer(Modifier.height(8.dp))
            GameText("ASTROARENA", Type.Display.copy(fontSize = Type.Display.fontSize * 1.5f), color = Palette.Gold, outline = 5.dp)
            Spacer(Modifier.height(18.dp))
            // One, two, three dots, repeating.
            GameText("LOADING" + ".".repeat(1 + (time * 2.5f).toInt() % 3), Type.Title, outline = 3.5.dp, modifier = Modifier.width(190.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(progress, Modifier.width(360.dp).height(22.dp), animate = false)
                Spacer(Modifier.width(12.dp))
                GameText("${(progress * 100).toInt()}%", Type.Title, color = Palette.Gold, outline = 3.5.dp, modifier = Modifier.width(80.dp))
            }
            Spacer(Modifier.height(8.dp))
            PlainText(status, Type.Label, color = Palette.TextDim)
        }
        PlainText("v${BuildConfig.VERSION_NAME}", Type.Small, Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

/**
 * A newer release exists: the game stops here until it is installed, and says why. Dev builds can carry on
 * regardless, so testing an old build is still possible.
 */
@Composable
fun UpdateScreen(update: UpdateInfo, onSkip: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    Box(
        Modifier.fillMaxSize().background(Color(0xF00B0620)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 720.dp).padding(18.dp), cut = 20.dp) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GameText("UPDATE REQUIRED", Type.Display, color = Palette.Gold, outline = 4.dp)
                PlainText(
                    "You have version ${BuildConfig.VERSION_NAME}. Version ${update.version} is out, and this version is no longer supported, " +
                        "so the game won't start until you update. Your progress is kept.",
                    Type.Body, color = Color.White, align = TextAlign.Center, maxLines = 4,
                )
                val notes = remember(update) { update.notes.lines().map { it.trim().trimStart('#', '-', ' ').replace("**", "") }.filter { it.isNotBlank() }.take(5) }
                if (notes.isNotEmpty()) Column(Modifier.widthIn(max = 620.dp)) {
                    GameText("WHAT'S NEW", Type.Label, color = Palette.Gold, outline = 2.dp)
                    for (n in notes) PlainText("• $n", Type.Small, color = Color.White, maxLines = 2)
                }
                Spacer(Modifier.height(4.dp))
                ChunkyButton({ open(update.apkUrl) }, Modifier.size(300.dp, 64.dp), ButtonStyle.GREEN) { GameText("DOWNLOAD UPDATE", Type.Heading) }
                PlainText("This downloads the new APK in your browser. Open it when it finishes and choose Install.", Type.Small, align = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChunkyButton({ open(update.pageUrl) }, Modifier.size(210.dp, 50.dp), ButtonStyle.PURPLE, lip = 4.dp) { GameText("RELEASE PAGE", Type.Label, outline = 2.dp) }
                    if (BuildConfig.DEBUG) ChunkyButton(onSkip, Modifier.size(210.dp, 50.dp), ButtonStyle.GREY, lip = 4.dp) { GameText("PLAY ANYWAY (DEV)", Type.Label, outline = 2.dp) }
                }
            }
        }
    }
}
