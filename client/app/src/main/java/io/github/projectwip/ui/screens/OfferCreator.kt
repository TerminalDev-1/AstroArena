package io.github.projectwip.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.Balance
import io.github.projectwip.data.Currency
import io.github.projectwip.data.CustomOffer
import io.github.projectwip.data.FighterId
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardVisual
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.plateShape
import io.github.projectwip.ui.rewardLabel
import kotlinx.coroutines.delay

/** Colour themes an offer can use (top, bottom). */
val OFFER_THEMES = listOf(
    "Violet" to (Color(0xFF7A2BB8) to Color(0xFF3A1470)),
    "Ember" to (Color(0xFFE0582A) to Color(0xFF7A1E14)),
    "Ocean" to (Color(0xFF1E8FD8) to Color(0xFF0E3A7A)),
    "Mint" to (Color(0xFF26B884) to Color(0xFF0E5A44)),
    "Gold" to (Color(0xFFE6A41E) to Color(0xFF8A4A0A)),
)

private fun currencyIcon(c: Currency) = when (c) { Currency.BOLTS -> IconKind.BOLT; Currency.PRISMS -> IconKind.PRISM; Currency.FREE -> IconKind.GIFT }

/** The "+ CREATE OFFER" tile at the start of the custom offers section. */
@Composable
fun CreateOfferCard(width: Dp, onClick: () -> Unit) {
    ChunkyButton(onClick, Modifier.width(width).fillMaxHeight().padding(top = 8.dp), ButtonStyle.GLASS, cut = 16.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GameIcon(IconKind.PLUS, Modifier.size(64.dp))
            Spacer(Modifier.height(10.dp))
            GameText("CREATE OFFER", Type.Heading, outline = 2.5.dp)
            PlainText("Put a deal in every player's shop", Type.Small, align = TextAlign.Center)
        }
    }
}

/** A player-made offer in the Shop. */
@Composable
fun CustomOfferCard(o: CustomOffer, width: Dp, canDelete: Boolean, onBuy: () -> Unit, onDelete: () -> Unit) {
    val (top, bottom) = OFFER_THEMES[o.theme.coerceIn(0, OFFER_THEMES.lastIndex)].second
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    Box(Modifier.width(width).fillMaxHeight()) {
        Panel(Modifier.fillMaxSize().padding(top = 8.dp), color = top, colorBottom = bottom, cut = 16.dp) {
            Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GameText(o.title.uppercase(), Type.Heading, outline = 2.5.dp, align = TextAlign.Center)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RewardVisual(o.reward, Modifier.fillMaxSize(0.8f))
                }
                PlainText(o.contents.joinToString(", ") { rewardLabel(it) }, Type.Label, color = Color.White, align = TextAlign.Center, maxLines = 3)
                if (o.expiresAt > 0) {
                    val left = ((o.expiresAt - now) / 1000).coerceAtLeast(0)
                    PlainText("Ends in %d:%02d:%02d".format(left / 3600, (left / 60) % 60, left % 60), Type.Small, color = Palette.Gold)
                }
                if (o.limit > 0) PlainText("${(o.limit - o.purchased).coerceAtLeast(0)} of ${o.limit} left", Type.Small, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(6.dp))
                val available = !o.soldOut && !o.expired(now)
                ChunkyButton(onBuy, Modifier.fillMaxWidth().height(54.dp), ButtonStyle.GREEN, enabled = available) {
                    if (!available) GameText(if (o.soldOut) "SOLD OUT" else "EXPIRED", Type.Heading)
                    else if (o.currency == Currency.FREE || o.price == 0) GameText("FREE!", Type.Heading)
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        if (o.wasPrice > o.price) PlainText("${o.wasPrice} ", Type.Label.copy(textDecoration = TextDecoration.LineThrough), color = Color.White.copy(alpha = 0.75f))
                        GameIcon(currencyIcon(o.currency), Modifier.size(24.dp))
                        GameText(" ${o.price}", Type.Heading)
                    }
                }
            }
        }
        if (o.discountPercent > 0) Badge("-${o.discountPercent}%", Modifier.align(Alignment.TopCenter), color = Palette.RedDeep)
        if (canDelete) {
            ChunkyButton(onDelete, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = 0.dp).size(36.dp, 36.dp), ButtonStyle.RED, lip = 3.dp, cut = 8.dp) {
                GameText("✕", Type.Label, outline = 2.dp)
            }
        }
    }
}

/**
 * The Offer Creator: pick contents, price, discount, duration, limit and theme, with a live preview.
 */
@Composable
fun OfferCreatorDialog(onCreate: (CustomOffer) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("Mega Deal") }
    var bolts by remember { mutableIntStateOf(500) }
    var prisms by remember { mutableIntStateOf(0) }
    var fighter by remember { mutableStateOf<FighterId?>(null) }
    var skin by remember { mutableStateOf<Pair<FighterId, Int>?>(null) }
    var currency by remember { mutableStateOf(Currency.PRISMS) }
    var price by remember { mutableIntStateOf(30) }
    var wasPrice by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) } // 0 forever, 1 = 1h, 2 = 24h, 3 = 7d
    var limit by remember { mutableIntStateOf(1) }
    var theme by remember { mutableIntStateOf(0) }

    fun build(id: Long = 0) = CustomOffer(
        id = id, title = title.ifBlank { "Offer" }, bolts = bolts, prisms = prisms, fighter = fighter,
        skinFighter = skin?.first, skinIndex = skin?.second ?: 0,
        currency = currency, price = if (currency == Currency.FREE) 0 else price, wasPrice = if (currency == Currency.FREE) 0 else wasPrice,
        expiresAt = when (duration) { 1 -> System.currentTimeMillis() + 3_600_000L; 2 -> System.currentTimeMillis() + 86_400_000L; 3 -> System.currentTimeMillis() + 7 * 86_400_000L; else -> 0L },
        limit = limit, theme = theme,
    )
    val preview = build()

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.fillMaxSize().padding(20.dp).clickable(remember { MutableInteractionSource() }, null) { }, cut = 22.dp) {
            Row(Modifier.fillMaxSize().padding(18.dp)) {
                // ---------------- editor
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameText("OFFER CREATOR", Type.Title, color = Palette.Gold, outline = 3.5.dp)
                    Field("NAME") { TitleField(title) { title = it } }
                    Field("CONTENTS") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Stepper(IconKind.BOLT, "Bolts", bolts, 0, 10_000, listOf(50, 500)) { bolts = it }
                            Stepper(IconKind.PRISM, "Prisms", prisms, 0, 1_000, listOf(5, 50)) { prisms = it }
                            Choice("Fighter", listOf("None") + Balance.fighters.map { it.name.substringBefore(' ') }, fighter?.let { it.ordinal + 1 } ?: 0) { i ->
                                fighter = if (i == 0) null else FighterId.entries[i - 1]
                            }
                            val skins = Balance.fighters.flatMap { f -> f.skins.indices.drop(1).map { f.id to it } }
                            Choice("Colorway", listOf("None") + skins.map { (f, i) -> Balance.fighter(f).skins[i].name }, skin?.let { skins.indexOf(it) + 1 } ?: 0) { i ->
                                skin = if (i == 0) null else skins[i - 1]
                            }
                        }
                    }
                    Field("PRICE") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("Pay with", listOf("Free", "Bolts", "Prisms"), currency.ordinal) { currency = Currency.entries[it] }
                            if (currency != Currency.FREE) {
                                Stepper(if (currency == Currency.BOLTS) IconKind.BOLT else IconKind.PRISM, "Price", price, 0, 10_000, listOf(5, 50)) { price = it }
                                Stepper(IconKind.STAR, "Was (discount)", wasPrice, 0, 20_000, listOf(5, 50)) { wasPrice = it }
                            }
                        }
                    }
                    Field("AVAILABILITY") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("Lasts", listOf("Forever", "1 hour", "24 hours", "7 days"), duration) { duration = it }
                            Choice("Can buy", listOf("Once", "3 times", "Unlimited"), when (limit) { 1 -> 0; 3 -> 1; else -> 2 }) { limit = when (it) { 0 -> 1; 1 -> 3; else -> 0 } }
                        }
                    }
                    Field("THEME") { Choice("Colour", OFFER_THEMES.map { it.first }, theme) { theme = it } }
                }
                Spacer(Modifier.width(18.dp))
                // ---------------- live preview
                Column(Modifier.width(260.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    GameText("PREVIEW", Type.Heading, outline = 2.5.dp)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.weight(1f)) { CustomOfferCard(preview, 230.dp, canDelete = false, onBuy = {}, onDelete = {}) }
                    Spacer(Modifier.height(10.dp))
                    if (preview.contents.isEmpty()) PlainText("Add at least one item.", Type.Small, color = Palette.Red)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChunkyButton(onDismiss, Modifier.size(110.dp, 56.dp), ButtonStyle.PURPLE) { GameText("CANCEL", Type.Label) }
                        ChunkyButton({ onCreate(build(System.currentTimeMillis())) }, Modifier.size(130.dp, 56.dp), ButtonStyle.GREEN,
                            enabled = preview.contents.isNotEmpty()) { GameText("CREATE", Type.Heading) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column {
        GameText(label, Type.Label, color = Palette.Cyan, outline = 2.dp)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun TitleField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.width(320.dp).height(50.dp).drawBehind {
            val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
            val p = androidx.compose.ui.graphics.Path().apply { addOutline(o) }
            drawPath(p, Palette.PanelInset)
            drawPath(p, Palette.Ink, style = Stroke(2.5.dp.toPx()))
        }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(value, { onChange(it.take(22)) }, singleLine = true, textStyle = Type.Heading, cursorBrush = SolidColor(Palette.Gold), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Stepper(icon: IconKind, label: String, value: Int, min: Int, max: Int, steps: List<Int>, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GameIcon(icon, Modifier.size(30.dp))
        Spacer(Modifier.width(8.dp))
        PlainText(label, Type.Label, color = Color.White, modifier = Modifier.width(120.dp))
        for (st in steps.reversed()) {
            ChunkyButton({ onChange((value - st).coerceIn(min, max)) }, Modifier.size(54.dp, 42.dp), ButtonStyle.PURPLE, lip = 3.dp, cut = 8.dp) { GameText("-$st", Type.Small.copy(color = Color.White), outline = 1.5.dp) }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) { GameText("%,d".format(value), Type.Heading, outline = 2.5.dp) }
        for (st in steps) {
            Spacer(Modifier.width(4.dp))
            ChunkyButton({ onChange((value + st).coerceIn(min, max)) }, Modifier.size(54.dp, 42.dp), ButtonStyle.CYAN, lip = 3.dp, cut = 8.dp) { GameText("+$st", Type.Small.copy(color = Color.White), outline = 1.5.dp) }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlainText(label, Type.Label, color = Color.White, modifier = Modifier.width(120.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o ->
                ChunkyButton({ onSelect(i) }, Modifier.height(42.dp).width(if (options.size > 5) 92.dp else 104.dp),
                    if (i == selected) ButtonStyle.ORANGE else ButtonStyle.PURPLE, lip = 3.dp, cut = 8.dp) {
                    PlainText(o, Type.Small.copy(color = Color.White), align = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}
