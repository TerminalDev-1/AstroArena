package io.github.projectwip.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.CapsuleTier
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.SparkCapsules
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type

/**
 * The Chaos Command Center: every tweak the game has (drop luck, free drops, upgrade cost, level cap, hand-outs). It
 * is a tab in Settings. Online it is for developers only (the server honours none of it from anyone else) and changes
 * the real account. Offline it is Chaos Mode: everyone has it, and it changes the offline profile only.
 */
@Composable
fun ChaosCommandCenter(save: SaveData, repo: GameRepository) {
    val s = save.settings
    val ask = io.github.projectwip.ui.LocalServerCall.current
    // The slider's own position while it is being dragged, so the number and the odds follow the thumb;
    // the save is only written when it is let go.
    var luck by remember { mutableFloatStateOf(s.debugLuck) }
    var costFactor by remember { mutableFloatStateOf(s.debugUpgradeCost) }
    fun snap(v: Float) = (v * 10).toInt() / 10f
    val server = io.github.projectwip.ui.LocalServer.current
    val status = server?.status?.collectAsState()?.value
    val offline = io.github.projectwip.ui.LocalOfflineMode.current
    // Online, everything these tweaks touch is the server's, so they only work if it lists this player as a developer.
    // Offline is Chaos Mode: they are everyone's, and only touch the offline profile.
    val trusted = offline || (status?.online == true && status.account?.developer == true)
    if (offline) SectionTitle("CHAOS MODE", "You are offline, so every tweak in the game is yours. They only change your offline profile, and they switch off when you are back online.")
    else SectionTitle("CHAOS COMMAND CENTER", "Every tweak in the game. They change your real account, so use them however you like.")
    if (!trusted) PlainText(
        "The server doesn't list you as a developer, so it ignores everything in this menu. " +
            "Add your player ID (${server?.playerId ?: "see Settings > Data"}) to game.cfg on the server.",
        Type.Body, color = io.github.projectwip.ui.Palette.Gold,
    )
    ToggleRow("INFINITE DROPS", "The drop button always works and opening one never uses it up.", s.debugInfiniteCapsules) { v ->
        repo.updateSettings { it.copy(debugInfiniteCapsules = v) }
    }
    ToggleRow("NO LEVEL CAP", "Fighters can be upgraded past level ${io.github.projectwip.data.Balance.MAX_LEVEL}.", s.debugNoLevelCap) { v ->
        repo.updateSettings { it.copy(debugNoLevelCap = v) }
    }
    SliderRow("DROP LUCK", "×${"%.1f".format(1f + luck)}", s.debugLuck, 0f, SparkCapsules.MAX_LUCK, onDrag = { luck = snap(it) }) { v ->
        luck = snap(v)
        repo.updateSettings { it.copy(debugLuck = snap(v)) }
    }
    val odds = SparkCapsules.odds(luck)
    PlainText(CapsuleTier.entries.joinToString("  ·  ") { "${it.label} ${"%.1f".format(odds[it.ordinal] * 100)}%" }, Type.Body, color = Color.White)
    PlainText("Chance a drop splits: ${"%.0f".format(SparkCapsules.splitChance(luck) * 100)}%, then ${"%.0f".format(SparkCapsules.resplitChance(luck) * 100)}% to split again (up to ${SparkCapsules.MAX_PIECES})",
        Type.Body, color = Color.White)
    SliderRow("UPGRADE COST", if (costFactor <= 0f) "FREE" else "×${"%.1f".format(costFactor)}", s.debugUpgradeCost, 0f, io.github.projectwip.data.Progression.MAX_COST_FACTOR,
        onDrag = { costFactor = snap(it) }) { v ->
        costFactor = snap(v)
        repo.updateSettings { it.copy(debugUpgradeCost = snap(v)) }
    }
    PlainText("Multiplies the price of every fighter upgrade. A level 1 upgrade now costs ${Math.round(io.github.projectwip.data.Balance.upgradeCostFrom(1) * costFactor)} Upgrade Credits, level 9 costs ${Math.round(io.github.projectwip.data.Balance.upgradeCostFrom(9) * costFactor)}.",
        Type.Body, color = Color.White)
    SectionTitle("HAND-OUTS", "You have ${"%,d".format(save.cups)} Cups, ${"%,d".format(save.bolts)} Upgrade Credits, ${"%,d".format(save.prisms)} CPU Chips and ${save.capsules} drops.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChunkyButton({ ask({ devGrant(cups = 50) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.GOLD, lip = 4.dp) { GameText("+50 CUPS", Type.Label, outline = 2.dp) }
        ChunkyButton({ ask({ devGrant(cups = 500) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.GOLD, lip = 4.dp) { GameText("+500 CUPS", Type.Label, outline = 2.dp) }
        ChunkyButton({ ask({ devGrant(cups = -50) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.RED, lip = 4.dp) { GameText("−50 CUPS", Type.Label, outline = 2.dp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChunkyButton({ ask({ devGrant(bolts = 1000) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.CYAN, lip = 4.dp) { GameText("+1,000 UPGRADE CREDITS", Type.Label, outline = 2.dp) }
        ChunkyButton({ ask({ devGrant(prisms = 100) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.PURPLE, lip = 4.dp) { GameText("+100 CPU CHIPS", Type.Label, outline = 2.dp) }
        ChunkyButton({ ask({ devGrant(drops = 5) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.GREEN, lip = 4.dp) { GameText("+5 DROPS", Type.Label, outline = 2.dp) }
        ChunkyButton({ ask({ devGrant(credits = 100) }) }, Modifier.width(150.dp).height(52.dp), ButtonStyle.GREEN, lip = 4.dp) { GameText("+100 CREDITS", Type.Label, outline = 2.dp) }
    }
}
