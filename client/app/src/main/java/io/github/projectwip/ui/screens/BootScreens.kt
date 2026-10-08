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
import androidx.compose.runtime.setValue
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
 * Shown while the game gets ready: it connects to the game server, checks GitHub for a newer release and builds
 * its sounds and music. [progress] is real (0..1 of that work), and [status] says which part is happening.
 * [onSkip], when given, offers a way to stop waiting for the server.
 */
@Composable
fun LoadingScreen(progress: Float, status: String, onSkip: (() -> Unit)? = null) {
    val time by rememberAnimTime()
    Box(
        // Swallows touches so nothing underneath can be pressed while loading.
        Modifier.fillMaxSize().background(Color(0xF00B0620)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The roster, bobbing out of step with each other.
            Row(horizontalArrangement = Arrangement.spacedBy((-70).dp), verticalAlignment = Alignment.Bottom) {
                io.github.projectwip.data.Balance.fighters.forEachIndexed { i, def ->
                    io.github.projectwip.ui.FighterView(def, 0, Modifier.size(if (i == 1 || i == 2) 230.dp else 200.dp)
                        .graphicsLayer { translationY = 7.dp.toPx() * sin(time * 2.6f + i * 1.3f) }, pedestal = false)
                }
            }
            GameText("ASTROARENA", Type.Display.copy(fontSize = Type.Display.fontSize * 1.5f), color = Palette.Gold, outline = 5.dp)
            Spacer(Modifier.height(18.dp))
            // One, two, three dots, repeating.
            // While the server is being reached that is what it says; after that it is plain loading.
            val connecting = status.startsWith("Connecting")
            GameText((if (connecting) "CONNECTING TO SERVER" else "LOADING") + ".".repeat(1 + (time * 2.5f).toInt() % 3), Type.Title, outline = 3.5.dp,
                modifier = Modifier.width(if (connecting) 430.dp else 190.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(progress, Modifier.width(360.dp).height(22.dp), animate = false)
                Spacer(Modifier.width(12.dp))
                GameText("${(progress * 100).toInt()}%", Type.Title, color = Palette.Gold, outline = 3.5.dp, modifier = Modifier.width(80.dp))
            }
            Spacer(Modifier.height(8.dp))
            PlainText(status, Type.Label, color = Palette.TextDim)
        }
        if (onSkip != null) ChunkyButton(onSkip, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp).size(240.dp, 50.dp), ButtonStyle.GREY, lip = 4.dp) {
            GameText("PLAY OFFLINE (DEV)", Type.Label, outline = 2.dp)
        }
        PlainText(TIPS[(time / 4f).toInt() % TIPS.size], Type.Body, Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), color = Color.White, align = TextAlign.Center)
        PlainText(BuildConfig.VERSION_NAME, Type.Small, Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

/** Shown one at a time on the loading and matchmaking screens. */
val TIPS = listOf(
    "Tip: tap the attack stick to fire at the nearest enemy.",
    "Tip: tall grass hides you until an enemy gets close.",
    "Tip: your super charges as you land hits.",
    "Tip: stay out of the fight for a few seconds and you start to heal.",
    "Tip: in Last Spark, break crates for Power Cells before the storm closes in.",
    "Tip: a top-four finish or a win earns an Arena Box, up to three a day.",
    "Tip: upgrades raise a fighter's health and damage. Upgrade Credits pay for them.",
    "Tip: you can move every control in Settings > Controls.",
)

/**
 * A new player's first screen: choose the name other players will see. Their account is made on the server
 * under this name as soon as they confirm (or later, if the server can't be reached yet).
 */
@Composable
fun NameScreen(onDone: (String) -> Unit) {
    var name by remember { androidx.compose.runtime.mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0620)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Near the top, so the keyboard doesn't cover it.
        Panel(Modifier.widthIn(max = 640.dp).padding(18.dp), cut = 20.dp) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GameText("WHAT'S YOUR NAME?", Type.Display, color = Palette.Gold, outline = 4.dp)
                PlainText("This is the name other players see. Up to 16 letters and numbers; you can change it later in Settings.",
                    Type.Body, color = Color.White, align = TextAlign.Center, maxLines = 3)
                NameField("") { name = it }
                ChunkyButton({ onDone(name.trim()) }, Modifier.size(260.dp, 64.dp), ButtonStyle.GREEN, enabled = name.isNotBlank()) { GameText("LET'S GO", Type.Heading) }
            }
        }
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
                    "A newer ${BuildConfig.VERSION_NAME} build is out, and this one is no longer supported, " +
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

/**
 * The game server has turned this version away (it is listed in the server's versions_not_supported.cfg).
 * Shows the server's own message. Dev builds can carry on regardless.
 */
@Composable
fun UnsupportedScreen(message: String, releasesUrl: String, onSkip: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(Color(0xF00B0620)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 680.dp).padding(18.dp), cut = 20.dp) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GameText("VERSION NOT SUPPORTED", Type.Display, color = Palette.Gold, outline = 4.dp)
                PlainText(message.ifBlank { "This version of the game is no longer supported. Please update to keep playing." },
                    Type.Body, color = Color.White, align = TextAlign.Center, maxLines = 5)
                PlainText("Your progress is kept.", Type.Small, align = TextAlign.Center)
                ChunkyButton({
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))) } catch (_: Exception) { }
                }, Modifier.size(320.dp, 64.dp), ButtonStyle.GREEN) { GameText("GET THE LATEST VERSION", Type.Heading) }
                if (BuildConfig.DEBUG) ChunkyButton(onSkip, Modifier.size(210.dp, 50.dp), ButtonStyle.GREY, lip = 4.dp) { GameText("PLAY ANYWAY (DEV)", Type.Label, outline = 2.dp) }
            }
        }
    }
}

/** How long is left, roughly: "2 days 6 hours", "3 hours 20 minutes", "5 minutes". */
private fun timeLeft(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) + 59_999) / 60_000
    fun some(n: Long, unit: String) = "$n $unit" + if (n == 1L) "" else "s"
    return when {
        minutes >= 2880 -> some(minutes / 1440, "day") + ((minutes % 1440) / 60).let { if (it > 0) " " + some(it, "hour") else "" }
        minutes >= 60 -> some(minutes / 60, "hour") + (minutes % 60).let { if (it > 0) " " + some(it, "minute") else "" }
        else -> some(minutes.coerceAtLeast(1), "minute")
    }
}

/**
 * The server's owner has disabled this account (the server's accounts.cfg). Says so, with the owner's reason
 * and how long is left if there is an end ([until], on this device's clock; 0 = none). There is nothing to press:
 * a disabled account can't play at all, online or off, until the server lets it back in.
 */
@Composable
fun DisabledScreen(reason: String, until: Long) {
    val left by androidx.compose.runtime.produceState(until - System.currentTimeMillis(), until) {
        while (true) { value = until - System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xF00B0620)).clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 680.dp).padding(18.dp), cut = 20.dp) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GameText("ACCOUNT DISABLED", Type.Display, color = Palette.Enemy, outline = 4.dp)
                PlainText("The owner of this server has disabled your account.", Type.Body, color = Color.White, align = TextAlign.Center, maxLines = 3)
                if (reason.isNotBlank()) PlainText("Reason: $reason", Type.Body, color = Palette.Gold, align = TextAlign.Center, maxLines = 5)
                PlainText(
                    if (until > 0) "You're back in ${timeLeft(left)}." else "It stays disabled until the owner lets you back in.",
                    Type.Body, color = Color.White, align = TextAlign.Center, maxLines = 2)
                PlainText("Your progress is kept. You can't play until then.", Type.Small, align = TextAlign.Center)
            }
        }
    }
}
