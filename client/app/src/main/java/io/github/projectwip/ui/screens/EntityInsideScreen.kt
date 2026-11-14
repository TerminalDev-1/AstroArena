package io.github.projectwip.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.projectwip.match.MatchView
import io.github.projectwip.render3d.EntityInsideView
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Panel
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import io.github.projectwip.ui.rememberAnimTime
import kotlin.math.sin

/**
 * Trapped inside the Entity (the user's design, build 111): the ruin it keeps its prisoners in, full screen, while the
 * match goes on underneath; over it, its health (what the team has to take down to free them), what is happening to
 * them (it feeds on them), and how the teammates still fighting are doing.
 */
@Composable
fun EntityInsideScreen(read: () -> MatchView.InsideInfo?) {
    var info by remember { mutableStateOf(read()) }
    var scene by remember { mutableStateOf<EntityInsideView?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val i = read()
            info = i
            scene?.params?.held = i?.held ?: emptyList()
        }
    }
    val time by rememberAnimTime()
    val violet = Color(0xFFB46CFF)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> EntityInsideView(ctx).also { scene = it } }, modifier = Modifier.fillMaxSize())
        val i = info
        // Its health, across the top.
        Column(Modifier.align(Alignment.TopCenter).padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Panel(cut = 12.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlainText("THE ENTITY · %,d".format(i?.hp ?: 0), Type.Label, maxLines = 1)
                    Canvas(Modifier.width(420.dp).height(10.dp)) {
                        val r = CornerRadius(size.height / 2, size.height / 2)
                        drawRoundRect(Color(0xCC0C061E), cornerRadius = r)
                        val frac = if (i != null && i.maxHp > 0) i.hp.toFloat() / i.maxHp else 0f
                        drawRoundRect(violet, size = Size(size.width * frac, size.height), cornerRadius = r)
                    }
                }
            }
            GameText("TRAPPED INSIDE THE ENTITY", Type.Title, color = Color(0xFFE9D4FF), outline = 3.dp, modifier = Modifier.padding(top = 14.dp))
            PlainText("YOUR TEAM MUST BEAT IT TO FREE YOU", Type.Label, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
        }
        // What it is doing to them, pulsing.
        val held = i?.held?.size ?: 0
        GameText(when (held) { 0, 1 -> "IT FEEDS ON YOU"; 2 -> "IT FEEDS ON BOTH OF YOU"; else -> "IT FEEDS ON ALL $held OF YOU" }, Type.Heading, color = Color(0xFFFF7A9C), outline = 2.5.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp).graphicsLayer { alpha = 0.6f + 0.4f * sin(time * 3f) })
        PlainText("Every fighter it holds heals it and makes it hit harder", Type.Small, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            color = Color.White.copy(alpha = 0.6f), maxLines = 1)
        // The team still fighting out there.
        if (i != null && i.team.isNotEmpty()) Panel(Modifier.align(Alignment.BottomStart).padding(16.dp), cut = 12.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlainText("YOUR TEAM", Type.Eyebrow, maxLines = 1)
                for (m in i.team) Row(verticalAlignment = Alignment.CenterVertically) {
                    PlainText(m.name, Type.Label, Modifier.width(96.dp), maxLines = 1)
                    if (m.trapped) PlainText("TRAPPED", Type.Label, color = Color(0xFFFF7A9C), maxLines = 1)
                    else Canvas(Modifier.width(120.dp).height(8.dp)) {
                        val r = CornerRadius(size.height / 2, size.height / 2)
                        drawRoundRect(Color(0xCC0C061E), cornerRadius = r)
                        drawRoundRect(if (m.alive) Color(0xFF4FE07A) else Color(0xFF777777),
                            size = Size(size.width * (m.hp.toFloat() / m.maxHp.coerceAtLeast(1)).coerceIn(0f, 1f), size.height), cornerRadius = r)
                    }
                }
            }
        }
    }
}
