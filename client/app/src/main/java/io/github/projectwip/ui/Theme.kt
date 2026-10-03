package io.github.projectwip.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.projectwip.audio.Sfx

/**
 * Visual identity: "neon foundry". Deep indigo plates with chamfered corners, hot orange for primary
 * actions, electric cyan for secondary, gold for Cups. Everything has a thick ink outline.
 */
object Palette {
    val Ink = Color(0xFF1B1035)
    val BgTop = Color(0xFF34188A)
    val BgBottom = Color(0xFF120A2E)
    val Panel = Color(0xFF2D1D74)
    val PanelLight = Color(0xFF41309F)
    val PanelDark = Color(0xFF1D1252)
    val PanelInset = Color(0xFF170D44)

    val Orange = Color(0xFFFF9F1C)
    val OrangeDeep = Color(0xFFFF6A00)
    val OrangeLip = Color(0xFFA83A00)
    val Cyan = Color(0xFF34D0F5)
    val CyanDeep = Color(0xFF1479D9)
    val CyanLip = Color(0xFF0A4A92)
    val Green = Color(0xFF62E887)
    val GreenDeep = Color(0xFF22A852)
    val GreenLip = Color(0xFF136A33)
    val Grey = Color(0xFF8C84A8)
    val GreyDeep = Color(0xFF5E5780)
    val GreyLip = Color(0xFF3A3458)
    val Red = Color(0xFFFF4D5E)
    val RedDeep = Color(0xFFD62842)
    val RedLip = Color(0xFF7E1426)

    val Gold = Color(0xFFFFC93C)
    val GoldDeep = Color(0xFFFF8A1F)
    val Bolt = Color(0xFF9BE7FF)
    val BoltDeep = Color(0xFF4AA3D8)
    val Prism = Color(0xFFE85CFF)
    val PrismDeep = Color(0xFF8E2BE0)

    val Text = Color.White
    val TextDim = Color(0xFFB9ADEB)
    val Positive = Color(0xFF7CFF9B)
    val Ally = Color(0xFF3FB6FF)
    val Enemy = Color(0xFFFF4D5E)
}

/** One heavy family, used italic for display and upright for body. */
object Type {
    private val family = FontFamily.SansSerif
    val Display = TextStyle(fontFamily = family, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 34.sp, color = Palette.Text)
    val Title = TextStyle(fontFamily = family, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 24.sp, color = Palette.Text)
    val Heading = TextStyle(fontFamily = family, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Palette.Text)
    val Label = TextStyle(fontFamily = family, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Palette.Text)
    val Body = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.TextDim)
    val Small = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Palette.TextDim)
}

/** Layout info after UI scaling. `roomy` = tablet-class height; layouts add content rather than just growing. */
data class UiMetrics(val widthDp: Float, val heightDp: Float, val scale: Float) {
    val roomy get() = heightDp >= 470f
    val wide get() = widthDp >= 900f
}

val LocalUi = compositionLocalOf { UiMetrics(800f, 400f, 1f) }
val LocalSfx = staticCompositionLocalOf<Sfx?> { null }
val LocalServer = staticCompositionLocalOf<io.github.projectwip.net.GameServer?> { null }
/** The persistent 3D lobby behind the menus; screens tell it what to show. */
val LocalLobby = staticCompositionLocalOf { io.github.projectwip.render3d.LobbyParams() }
