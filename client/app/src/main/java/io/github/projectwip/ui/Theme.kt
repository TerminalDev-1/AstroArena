package io.github.projectwip.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.projectwip.audio.Sfx

/**
 * Visual identity: "arena console". Dark steel-blue glass slabs (each with a visible side) with notched corners,
 * hairline rims of cool light and a bright accent edge, condensed upright type with wide tracking, hot orange for the
 * main action, electric cyan for secondary, gold for Cups.
 */
object Palette {
    val Ink = Color(0xFF07111C)
    val BgTop = Color(0xFF1D4A78)
    val BgBottom = Color(0xFF0A1726)
    val Panel = Color(0xF0193A5A)
    val PanelLight = Color(0xFF356591)
    val PanelDark = Color(0xF20D2136)
    val PanelInset = Color(0xFF0A1A2B)
    /** The cool light that catches the edges of plates and panels. */
    val Rim = Color(0xFF8FD6FF)
    /** A hairline edge on glass. */
    val Hairline = Color(0x668FD6FF)

    val Orange = Color(0xFFFFA62B)
    val OrangeDeep = Color(0xFFFF6A00)
    val OrangeLip = Color(0xFF9A3500)
    val Cyan = Color(0xFF34D0F5)
    val CyanDeep = Color(0xFF1479D9)
    val CyanLip = Color(0xFF0A4384)
    val Green = Color(0xFF62E887)
    val GreenDeep = Color(0xFF1FA34E)
    val GreenLip = Color(0xFF0F5E2D)
    val Grey = Color(0xFF7C8C9C)
    val GreyDeep = Color(0xFF4F6072)
    val GreyLip = Color(0xFF2C3946)
    val Red = Color(0xFFFF4D5E)
    val RedDeep = Color(0xFFD62842)
    val RedLip = Color(0xFF6E1022)

    val Gold = Color(0xFFFFC93C)
    val GoldDeep = Color(0xFFFF8A1F)
    val Bolt = Color(0xFF9BE7FF)
    val BoltDeep = Color(0xFF4AA3D8)
    val Prism = Color(0xFFE85CFF)
    val PrismDeep = Color(0xFF8E2BE0)

    val Text = Color.White
    val TextDim = Color(0xFFA9C6E2)
    val Positive = Color(0xFF7CFF9B)
    val Ally = Color(0xFF3FB6FF)
    val Enemy = Color(0xFFFF4D5E)
}

/**
 * Condensed and upright for display, tracked wide for labels; plain sans for body text. The display faces are the
 * device's own sans drawn narrower, so they look the same on every device whatever fonts it has.
 */
object Type {
    private val narrow = TextGeometricTransform(scaleX = 0.86f)
    private val condensed = FontFamily.SansSerif
    private val family = FontFamily.SansSerif
    val Display = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Black, fontSize = 39.sp, letterSpacing = 1.sp, textGeometricTransform = narrow, color = Palette.Text)
    val Title = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Black, fontSize = 25.sp, letterSpacing = 0.6.sp, textGeometricTransform = narrow, color = Palette.Text)
    val Heading = TextStyle(fontFamily = condensed, fontWeight = FontWeight.ExtraBold, fontSize = 19.5.sp, letterSpacing = 0.8.sp, textGeometricTransform = narrow, color = Palette.Text)
    val Label = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.5.sp, textGeometricTransform = narrow, color = Palette.Text)
    val Body = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Palette.TextDim)
    val Small = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.2.sp, color = Palette.TextDim)
    /** Tiny tracked capitals over a section or a figure ("NEXT REWARD", "CPU CHIPS"). */
    val Eyebrow = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 2.sp, textGeometricTransform = narrow, color = Palette.Rim)
}

/** Layout info after UI scaling. `roomy` = tablet-class height; layouts add content rather than just growing. */
data class UiMetrics(val widthDp: Float, val heightDp: Float, val scale: Float) {
    val roomy get() = heightDp >= 470f
    val wide get() = widthDp >= 900f
}

/** How far a camera cut-out reaches in from the left and the right edge, in pixels. The menus keep out of it. */
val LocalCutout = compositionLocalOf { 0 to 0 }
val LocalUi = compositionLocalOf { UiMetrics(800f, 400f, 1f) }
val LocalSfx = staticCompositionLocalOf<Sfx?> { null }
val LocalServer = staticCompositionLocalOf<io.github.projectwip.net.GameServer?> { null }

/** True for developers (dev builds, and players the game server lists): they get the debug menu and the difficulty choice. */
val LocalDev = staticCompositionLocalOf { false }
/** The persistent 3D lobby behind the menus; screens tell it what to show. */
val LocalLobby = staticCompositionLocalOf { io.github.projectwip.render3d.LobbyParams() }
/** The screen being drawn, and the way to another: the sub-screens' section switcher uses them. */
val LocalScreen = compositionLocalOf<Screen?> { null }
val LocalNav = staticCompositionLocalOf<((Screen) -> Unit)?> { null }
