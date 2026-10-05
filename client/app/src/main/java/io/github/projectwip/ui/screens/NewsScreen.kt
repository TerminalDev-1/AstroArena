package io.github.projectwip.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.projectwip.net.NewsItem
import io.github.projectwip.ui.Badge
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Screen
import io.github.projectwip.ui.ScreenHeader
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.plateShape

/** What's new: the items the game server is carrying (its `news.cfg`), newest first. Offline there is none to show. */
@Composable
fun NewsScreen(go: (Screen) -> Unit) {
    val server = io.github.projectwip.ui.LocalServer.current
    /** Null while loading; empty when the server couldn't be asked or has nothing to say. */
    var news by remember { androidx.compose.runtime.mutableStateOf<List<NewsItem>?>(null) }
    // Asked again when the connection comes up, so opening the tab a moment too early still fills it.
    val online = server?.status?.collectAsState()?.value?.online == true
    LaunchedEffect(online) {
        news = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server?.news() }.orEmpty()
    }
    val items = news

    Box(Modifier.fillMaxSize()) {
        io.github.projectwip.ui.LobbyShotEffect(io.github.projectwip.render3d.LobbyShot.BACKDROP)
        Box(Modifier.fillMaxSize().background(io.github.projectwip.ui.SCRIM))
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("NEWS", { go(Screen.Home) }, null, null) {}
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.Center) {
                Panel(Modifier.widthIn(max = 860.dp).fillMaxHeight(), cut = 18.dp) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        when {
                            items == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                GameText("LOADING…", Type.Title, outline = 3.5.dp)
                            }
                            items.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                GameText("NO NEWS RIGHT NOW", Type.Title, color = Palette.Gold, outline = 3.5.dp)
                                Spacer(Modifier.height(6.dp))
                                PlainText("News comes from the server. It will be here when you're online and there is some.", Type.Body, color = Color.White)
                            }
                            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(items) { NewsCard(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem) {
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val o = plateShape(10.dp, 4.dp).createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(o) }
            drawPath(path, Palette.PanelInset)
            drawPath(path, Palette.Ink, style = Stroke(2.dp.toPx()))
        }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(item.tag, color = when (item.tag) {
                "NEW" -> Palette.GreenDeep
                "BALANCE" -> Palette.OrangeDeep
                "EVENT" -> Palette.PrismDeep
                "FIX" -> Palette.CyanDeep
                else -> Palette.RedDeep
            })
            Spacer(Modifier.width(8.dp))
            GameText(item.title, Type.Heading, color = Palette.Gold, outline = 2.5.dp, modifier = Modifier.weight(1f))
            PlainText(item.date, Type.Small, color = Palette.Text)
        }
        PlainText(item.text, Type.Body, color = Palette.Text, maxLines = 12)
    }
}
