package io.github.projectwip.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.projectwip.BuildConfig
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.MoveStickMode
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.Settings
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.ConfirmDialog
import io.github.projectwip.ui.GameBackground
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.plateShape
import io.github.projectwip.audio.Sound

private enum class Tab(val label: String) { GAMEPLAY("Gameplay"), CONTROLS("Controls"), AUDIO("Audio & Feel"), DISPLAY("Display"), DATA("Data") }

@Composable
fun SettingsScreen(save: SaveData, repo: GameRepository, go: (Screen) -> Unit) {
    var tab by remember { mutableStateOf(Tab.GAMEPLAY) }
    val s = save.settings
    val set: ((Settings) -> Settings) -> Unit = { repo.updateSettings(it) }
    val ui = LocalUi.current

    Box(Modifier.fillMaxSize()) {
        GameBackground()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SETTINGS", { go(Screen.Home) }, null, null)
            Row(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                Column(Modifier.width(if (ui.roomy) 200.dp else 170.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (t in Tab.entries) {
                        ChunkyButton({ tab = t }, Modifier.fillMaxWidth().height(if (ui.roomy) 58.dp else 48.dp),
                            if (t == tab) ButtonStyle.ORANGE else ButtonStyle.PURPLE, lip = 4.dp) {
                            GameText(t.label.uppercase(), Type.Label, outline = 2.dp)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Panel(Modifier.weight(1f).fillMaxHeight(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (tab) {
                            Tab.GAMEPLAY -> GameplayTab(s, set)
                            Tab.CONTROLS -> ControlsTab(s, set)
                            Tab.AUDIO -> AudioTab(s, set)
                            Tab.DISPLAY -> DisplayTab(s, set)
                            Tab.DATA -> DataTab(repo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameplayTab(s: Settings, set: ((Settings) -> Settings) -> Unit) {
    SectionTitle("BOT DIFFICULTY", "Changes how bots think — reaction time, aim, dodging, positioning, target choice and super timing. Never their health or damage.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (d in BotDifficulty.entries) {
            val selected = d == s.botDifficulty
            Box(Modifier.weight(1f)) {
                ChunkyButton({ set { it.copy(botDifficulty = d) } }, Modifier.fillMaxWidth().height(150.dp),
                    if (selected) ButtonStyle.ORANGE else ButtonStyle.PURPLE, cut = 14.dp) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GameText(d.label.uppercase(), Type.Heading, color = if (selected) Color.White else difficultyColor(d), outline = 2.5.dp)
                        Spacer(Modifier.height(4.dp))
                        PlainText(d.blurb, Type.Small, color = Color.White.copy(alpha = 0.9f), maxLines = 5, align = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        PlainText("Win +${d.cupBonus} Cups · Bolts ×${d.boltMultiplier}", Type.Small, color = Palette.Gold, align = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
    SectionTitle("PLAYER NAME", "Shown above your fighter in matches.")
    NameField(s.playerName) { n -> set { it.copy(playerName = n) } }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Box(
        Modifier.width(320.dp).height(52.dp).drawBehind {
            val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
            val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
            drawPath(p, Palette.PanelInset)
            drawPath(p, Palette.Ink, style = Stroke(2.5.dp.toPx()))
        }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            text,
            onValueChange = { v ->
                val clean = v.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }.take(16)
                text = clean
                if (clean.isNotBlank()) onChange(clean.trim())
            },
            singleLine = true,
            textStyle = Type.Heading,
            cursorBrush = SolidColor(Palette.Gold),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ControlsTab(s: Settings, set: ((Settings) -> Settings) -> Unit) {
    SectionTitle("MOVE STICK", "Floating appears wherever your left thumb lands. Fixed always sits in the corner.")
    Segmented(listOf("FLOATING", "FIXED"), if (s.moveStickMode == MoveStickMode.FLOATING) 0 else 1) { i ->
        set { it.copy(moveStickMode = if (i == 0) MoveStickMode.FLOATING else MoveStickMode.FIXED) }
    }
    SliderRow("CONTROL SIZE", "${(s.controlScale * 100).toInt()}%", s.controlScale, 0.7f, 1.4f) { v -> set { it.copy(controlScale = v) } }
    SliderRow("CONTROL OPACITY", "${(s.controlOpacity * 100).toInt()}%", s.controlOpacity, 0.3f, 1f) { v -> set { it.copy(controlOpacity = v) } }
    ToggleRow("AIM ASSIST", "When you drag to aim, shots within ${io.github.projectwip.match.MatchRunner.ASSIST_DEGREES.toInt()}° of a visible enemy snap onto them.", s.aimAssist) { v -> set { it.copy(aimAssist = v) } }
    ToggleRow("TAP TO AUTO-AIM", "A quick tap on the attack stick fires at the nearest visible enemy (marked with a gold ring and arrow). Drag to aim manually; drag back to the centre to cancel.", s.tapToAutoAim) { v -> set { it.copy(tapToAutoAim = v) } }
    PlainText("Coming later: drag-to-reposition layout editor.", Type.Small, color = Palette.TextDim.copy(alpha = 0.7f))
}

@Composable
private fun AudioTab(s: Settings, set: ((Settings) -> Settings) -> Unit) {
    val sfx = LocalSfx.current
    ToggleRow("MUTE", "Silence all sound effects.", s.muted) { v -> set { it.copy(muted = v) } }
    SliderRow("EFFECTS VOLUME", "${(s.sfxVolume * 100).toInt()}%", s.sfxVolume, 0f, 1f, onRelease = { sfx?.play(Sound.HIT) }) { v -> set { it.copy(sfxVolume = v) } }
    ToggleRow("HAPTICS", "Vibrate on shots, hits and knockouts.", s.haptics) { v ->
        set { it.copy(haptics = v) }
        if (v) { sfx?.hapticsEnabled = true; sfx?.buzz(40, 180) }
    }
    PlainText("All sound effects are generated by the game at startup. Music is not in this preview yet.", Type.Small, color = Palette.TextDim.copy(alpha = 0.7f))
}

@Composable
private fun DisplayTab(s: Settings, set: ((Settings) -> Settings) -> Unit) {
    ToggleRow("HIGH FRAME RATE", "Render matches at up to 120 Hz on supported screens. Turn off to save battery. Applies next match.", s.highFrameRate) { v -> set { it.copy(highFrameRate = v) } }
    ToggleRow("DAMAGE NUMBERS", "Show damage you deal and take.", s.showDamageNumbers) { v -> set { it.copy(showDamageNumbers = v) } }
    ToggleRow("SHOW FPS", "Frame counter in matches.", s.showFps) { v -> set { it.copy(showFps = v) } }
}

@Composable
private fun DataTab(repo: GameRepository) {
    var confirm by remember { mutableStateOf(false) }
    SectionTitle("RESET PROGRESS", "Erase Cups, levels, currencies and claimed rewards. Settings are kept. This cannot be undone.")
    ChunkyButton({ confirm = true }, Modifier.width(260.dp).height(56.dp), ButtonStyle.RED) { GameText("RESET PROGRESS", Type.Heading) }
    Spacer(Modifier.height(10.dp))
    SectionTitle("ABOUT", "Project WIP-Preview ${BuildConfig.VERSION_NAME}. Preview software: everything may change without notice. All characters, art, sounds and rules are original.")
    if (confirm) {
        ConfirmDialog("RESET EVERYTHING?", "All progress will be lost.", "RESET", { repo.resetProgress(); confirm = false }, { confirm = false }, ButtonStyle.RED)
    }
}

// ---------------------------------------------------------------------------------------------- controls

@Composable
private fun SectionTitle(title: String, body: String) {
    Column {
        GameText(title, Type.Heading, color = Palette.Gold, outline = 2.5.dp)
        PlainText(body, Type.Body)
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val sfx = LocalSfx.current
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { sfx?.play(Sound.TAP); onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GameText(title, Type.Heading, outline = 2.5.dp)
            PlainText(body, Type.Body)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(checked)
    }
}

@Composable
private fun Toggle(checked: Boolean) {
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "toggle")
    Canvas(Modifier.size(78.dp, 40.dp)) {
        val r = size.height / 2
        drawRoundRect(Palette.Ink, cornerRadius = CornerRadius(r))
        val inset = 3.dp.toPx()
        drawRoundRect(
            Brush.verticalGradient(listOf(lerp(Palette.GreyDeep, Palette.GreenDeep, t), lerp(Palette.GreyLip, Palette.GreenLip, t))),
            Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(r),
        )
        val kx = inset + r - inset + (size.width - 2 * r) * t
        drawCircle(Palette.Ink, r - inset + 1, Offset(kx, r))
        drawCircle(Color.White, r - inset * 2, Offset(kx, r))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            ChunkyButton({ onSelect(i) }, Modifier.width(150.dp).height(50.dp), if (i == selected) ButtonStyle.CYAN else ButtonStyle.PURPLE, lip = 4.dp) {
                GameText(o, Type.Label, outline = 2.dp)
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, valueLabel: String, value: Float, min: Float, max: Float, onRelease: () -> Unit = {}, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameText(title, Type.Heading, outline = 2.5.dp)
            Spacer(Modifier.weight(1f))
            GameText(valueLabel, Type.Heading, color = Palette.Cyan, outline = 2.5.dp)
        }
        Spacer(Modifier.height(6.dp))
        ChunkySlider(value, min, max, onRelease, onChange)
    }
}

/** Big-thumb slider. Commits on release so we don't write the save file on every frame. */
@Composable
private fun ChunkySlider(value: Float, min: Float, max: Float, onRelease: () -> Unit, onChange: (Float) -> Unit) {
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    fun toValue(x: Float, w: Float, pad: Float) = (min + ((x - pad) / (w - pad * 2)).coerceIn(0f, 1f) * (max - min))
    Canvas(
        Modifier.fillMaxWidth().height(44.dp)
            .pointerInput(min, max) {
                val pad = size.height / 2f
                detectTapGestures { o -> local = toValue(o.x, size.width.toFloat(), pad); change(local); release() }
            }
            .pointerInput(min, max) {
                val pad = size.height / 2f
                detectDragGestures(
                    onDragStart = { o -> dragging = true; local = toValue(o.x, size.width.toFloat(), pad) },
                    onDragEnd = { dragging = false; change(local); release() },
                    onDragCancel = { dragging = false; change(local) },
                ) { ch, _ -> local = toValue(ch.position.x, size.width.toFloat(), pad) }
            },
    ) {
        val pad = size.height / 2
        val trackH = 16.dp.toPx()
        val y = size.height / 2
        val f = ((local - min) / (max - min)).coerceIn(0f, 1f)
        drawRoundRect(Palette.Ink, Offset(pad - 3, y - trackH / 2 - 3), Size(size.width - pad * 2 + 6, trackH + 6), CornerRadius(trackH))
        drawRoundRect(Palette.PanelInset, Offset(pad, y - trackH / 2), Size(size.width - pad * 2, trackH), CornerRadius(trackH))
        drawRoundRect(Brush.verticalGradient(listOf(Palette.Cyan, Palette.CyanDeep), y - trackH / 2, y + trackH / 2),
            Offset(pad, y - trackH / 2), Size((size.width - pad * 2) * f, trackH), CornerRadius(trackH))
        val kx = pad + (size.width - pad * 2) * f
        drawCircle(Palette.Ink, pad - 1, Offset(kx, y))
        drawCircle(Brush.verticalGradient(listOf(Color.White, Color(0xFFCFC8EA)), y - pad, y + pad), pad - 4.dp.toPx(), Offset(kx, y))
    }
}
