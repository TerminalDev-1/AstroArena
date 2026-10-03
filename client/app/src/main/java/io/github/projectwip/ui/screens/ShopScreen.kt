package io.github.projectwip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.Balance
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.Progression
import io.github.projectwip.data.Reward
import io.github.projectwip.data.SaveData
import io.github.projectwip.data.Shop
import io.github.projectwip.data.ShopItem
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.ConfirmDialog
import io.github.projectwip.ui.FighterRays
import io.github.projectwip.ui.FighterView
import io.github.projectwip.ui.GameBackground
import io.github.projectwip.ui.GameIcon
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.IconKind
import io.github.projectwip.ui.LocalSfx
import io.github.projectwip.ui.LocalUi
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.RewardReveal
import io.github.projectwip.ui.RewardVisual
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rewardLabel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun ShopScreen(save: SaveData, repo: GameRepository, go: (Screen) -> Unit, showReward: (RewardReveal) -> Unit) {
    var pending by remember { mutableStateOf<ShopItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val sfx = LocalSfx.current
    val ui = LocalUi.current
    val cardW = if (ui.roomy) 210.dp else 176.dp
    val dev = io.github.projectwip.ui.LocalDev.current
    val ask = io.github.projectwip.ui.LocalServerCall.current

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("SHOP", { go(Screen.Home) }, save.bolts, save.prisms)
            LazyRow(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 18.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Section("DAILY GIFT") { DailyGiftCard(save, repo, cardW * 1.15f, showReward) } }
                // Deals come from the server: developers make them, and every player sees them.
                val now = System.currentTimeMillis()
                val deals = save.customOffers.filter { !it.expired(now) }
                if (dev || deals.isNotEmpty()) item {
                    Section("DEALS") {
                        if (dev) CreateOfferCard(cardW * 0.8f) { creating = true }
                        deals.forEach { o ->
                            CustomOfferCard(o, cardW, canDelete = dev,
                                onBuy = { ask({ buyDeal(o.id) }) { showReward(RewardReveal(o.title, it)) } },
                                onDelete = { ask({ deleteDeal(o.id) }) })
                        }
                    }
                }
                item {
                    Section("FIGHTERS") {
                        Shop.fighterOffers.forEach { o ->
                            val owned = save.progress(o.fighter).unlocked
                            OfferCard(cardW, o.pricePrisms, owned, tag = if (!owned) Balance.fighter(o.fighter).role.uppercase() else null, onBuy = { pending = o }) {
                                FighterView(Balance.fighter(o.fighter), 0, Modifier.fillMaxSize(), pedestal = false, rays = !owned)
                                Title(o.title, Balance.fighter(o.fighter).title)
                            }
                        }
                    }
                }
                item {
                    Section("BOLT SUPPLIES") {
                        Shop.boltCrates.forEachIndexed { i, c ->
                            OfferCard(cardW, c.pricePrisms, owned = false, tag = if (i == 2) "BEST VALUE" else null, onBuy = { pending = c }) {
                                BoltPile(i + 1)
                                Title(c.title, "+${c.bolts} Bolts")
                            }
                        }
                    }
                }
                item {
                    Section("COLORWAYS") {
                        Shop.skinOffers.forEach { s ->
                            val prog = save.progress(s.fighter)
                            val owned = s.skinIndex in prog.ownedSkins
                            OfferCard(cardW, s.pricePrisms, owned, requires = if (!prog.unlocked) Balance.fighter(s.fighter).name else null, onBuy = { pending = s }) {
                                FighterView(Balance.fighter(s.fighter), s.skinIndex, Modifier.fillMaxSize(), pedestal = false)
                                Title(s.title, Balance.fighter(s.fighter).name)
                            }
                        }
                    }
                }
            }
        }

        if (creating) {
            OfferCreatorDialog(onCreate = { offer -> creating = false; ask({ createDeal(offer) }) { sfx?.play(Sound.REWARD) } }, onDismiss = { creating = false })
        }

        pending?.let { item ->
            ConfirmDialog(
                title = "BUY ${item.title.uppercase()}?",
                body = "Costs ${item.pricePrisms} Prisms. You have ${save.prisms}.",
                confirmLabel = "BUY",
                onDismiss = { pending = null },
                onConfirm = {
                    pending = null
                    ask({ buy(item.key) }) { showReward(RewardReveal("Purchased!", it)) }
                },
            ) { RewardVisual(rewardOf(item), Modifier.size(120.dp)) }
        }
    }
}

private fun rewardOf(item: ShopItem): Reward = when (item) {
    is ShopItem.FighterOffer -> Reward.UnlockFighter(item.fighter)
    is ShopItem.BoltCrate -> Reward.Bolts(item.bolts)
    is ShopItem.SkinOffer -> Reward.SkinReward(item.fighter, item.skinIndex)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxHeight()) {
        GameText(title, Type.Heading, color = Palette.Gold, outline = 2.5.dp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameText(title.uppercase(), Type.Heading, outline = 2.5.dp, align = TextAlign.Center)
        PlainText(subtitle, Type.Small, align = TextAlign.Center)
    }
}

@Composable
private fun OfferCard(
    width: androidx.compose.ui.unit.Dp,
    price: Int,
    owned: Boolean,
    tag: String? = null,
    requires: String? = null,
    onBuy: () -> Unit,
    visual: @Composable () -> Unit,
) {
    Box(Modifier.width(width).fillMaxHeight()) {
        Panel(Modifier.fillMaxSize().padding(top = 8.dp), cut = 16.dp) {
            Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // visual + title share the space; title slot is the last child of `visual`
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    VisualSlot(visual)
                }
                Spacer(Modifier.height(8.dp))
                when {
                    owned -> ChunkyButton({}, Modifier.fillMaxWidth().height(52.dp), ButtonStyle.GREY, enabled = false) { GameText("OWNED", Type.Heading) }
                    requires != null -> ChunkyButton({}, Modifier.fillMaxWidth().height(52.dp), ButtonStyle.GREY, enabled = false) {
                        PlainText("Requires $requires", Type.Small, color = Color.White, align = TextAlign.Center)
                    }
                    else -> ChunkyButton(onBuy, Modifier.fillMaxWidth().height(52.dp), ButtonStyle.GREEN) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GameIcon(IconKind.PRISM, Modifier.size(24.dp))
                            GameText(" $price", Type.Heading)
                        }
                    }
                }
            }
        }
        if (tag != null) Badge(tag, Modifier.align(Alignment.TopCenter), color = Palette.OrangeDeep)
    }
}

/** Lays out "visual then title": the first composable fills, later ones wrap. */
@Composable
private fun VisualSlot(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val rest = measurables.drop(1).map { it.measure(loose) }
        val restH = rest.sumOf { it.height }
        val visualH = (constraints.maxHeight - restH).coerceAtLeast(0)
        val visual = measurables.first().measure(loose.copy(maxHeight = visualH, minHeight = visualH, minWidth = constraints.maxWidth))
        layout(constraints.maxWidth, constraints.maxHeight) {
            visual.place(0, 0)
            var y = visualH
            rest.forEach { p -> p.place((constraints.maxWidth - p.width) / 2, y); y += p.height }
        }
    }
}

@Composable
private fun BoltPile(size: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FighterRays(Modifier.fillMaxSize(), Palette.Bolt)
        val n = size * 2 + 1
        Box(Modifier.size(130.dp)) {
            for (i in 0 until n) {
                val x = ((i * 37) % 70) - 35
                val y = 20 - (i / 3) * 18 + (i % 2) * 6
                GameIcon(IconKind.BOLT, Modifier.size(54.dp).align(Alignment.Center).offset(x.dp, y.dp))
            }
        }
    }
}

@Composable
private fun DailyGiftCard(save: SaveData, repo: GameRepository, width: androidx.compose.ui.unit.Dp, showReward: (RewardReveal) -> Unit) {
    val available = Progression.dailyGiftAvailable(save, repo.today)
    val ask = io.github.projectwip.ui.LocalServerCall.current
    val reward = Shop.dailyGift(repo.today)
    val countdown by produceState("") {
        while (true) {
            val now = LocalDateTime.now()
            val d = Duration.between(now, LocalDate.now().plusDays(1).atStartOfDay())
            val s = d.seconds
            value = "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
            delay(1000)
        }
    }
    Box(Modifier.width(width).fillMaxHeight()) {
        Panel(Modifier.fillMaxSize().padding(top = 8.dp), color = Color(0xFF7A2BB8), colorBottom = Color(0xFF3A1470), cut = 16.dp) {
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (available) FighterRays(Modifier.fillMaxSize(), Palette.Prism)
                    GameIcon(IconKind.GIFT, Modifier.size(110.dp))
                }
                GameText(if (available) rewardLabel(reward) else "COME BACK SOON", Type.Heading, outline = 2.5.dp, align = TextAlign.Center)
                PlainText(if (available) "Free, once a day" else "Next gift in $countdown", Type.Small, align = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                ChunkyButton(
                    { ask({ claimGift() }) { showReward(RewardReveal("Daily gift", it)) } },
                    Modifier.fillMaxWidth().height(52.dp), ButtonStyle.GOLD, enabled = available,
                ) { GameText(if (available) "FREE!" else countdown, Type.Heading) }
            }
        }
        Badge("DAILY", Modifier.align(Alignment.TopCenter), color = Palette.PrismDeep)
    }
}
