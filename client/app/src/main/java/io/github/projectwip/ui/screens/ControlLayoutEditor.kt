package io.github.projectwip.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.projectwip.data.ControlLayout
import io.github.projectwip.data.Settings
import io.github.projectwip.match.TouchControls
import io.github.projectwip.ui.ButtonStyle
import io.github.projectwip.ui.ChunkyButton
import io.github.projectwip.ui.GameText
import io.github.projectwip.ui.Palette
import io.github.projectwip.ui.PlainText
import io.github.projectwip.ui.Type
import kotlin.math.hypot

/**
 * Full-screen editor for where the match controls sit: drag the move stick, attack button and super button
 * anywhere (for example all on one side, to play one-handed). Shown at the exact size they have in a match.
 */
@Composable
fun ControlLayoutEditor(settings: Settings, onChange: (ControlLayout) -> Unit, onClose: () -> Unit) {
    val density = LocalContext.current.resources.displayMetrics.density
    var layout by remember { mutableStateOf(settings.controlLayout) }
    var held by remember { mutableIntStateOf(-1) }
    BackHandler(onBack = onClose)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xF2091520))) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // The real control code decides sizes and default spots, so the editor can't drift from the match.
        val controls = remember(layout, w, h, settings) { TouchControls(density).apply { configure(settings.copy(controlLayout = layout), w, h, 0, 0) } }
        val current by rememberUpdatedState(controls)
        val commit by rememberUpdatedState(onChange)
        val label = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); textAlign = Paint.Align.CENTER } }

        fun centre(c: TouchControls, i: Int) = when (i) { 0 -> Offset(c.moveCx, c.moveCy); 1 -> Offset(c.attackCx, c.attackCy); else -> Offset(c.superCx, c.superCy) }
        fun radius(c: TouchControls, i: Int) = when (i) { 0 -> c.moveRadius; 1 -> c.attackRadius; else -> c.superRadius }

        Canvas(
            Modifier.fillMaxSize().pointerInput(w, h) {
                detectDragGestures(
                    onDragStart = { o ->
                        // Smallest control first, so the super button can be picked off the attack stick's rim.
                        held = listOf(2, 1, 0).firstOrNull { i -> (o - centre(current, i)).getDistance() < radius(current, i) * 1.5f } ?: -1
                    },
                    onDragEnd = { held = -1; commit(layout) },
                    onDragCancel = { held = -1 },
                ) { change, _ ->
                    if (held < 0) return@detectDragGestures
                    val r = radius(current, held) * 1.15f
                    val fx = change.position.x.coerceIn(r, w - r) / w
                    val fy = change.position.y.coerceIn(r, h - r) / h
                    layout = when (held) {
                        0 -> layout.copy(moveX = fx, moveY = fy)
                        1 -> layout.copy(attackX = fx, attackY = fy)
                        else -> layout.copy(superX = fx, superY = fy)
                    }
                }
            },
        ) {
            val names = listOf("MOVE", "ATTACK", "SUPER")
            val colors = listOf(Palette.Cyan, Palette.Orange, Palette.Gold)
            for (i in 0..2) {
                val c = centre(controls, i)
                val r = radius(controls, i)
                val on = held == i
                drawCircle(colors[i].copy(alpha = if (on) 0.5f else 0.28f), r * 1.15f, c)
                drawCircle(if (on) Color.White else colors[i], r * 1.15f, c, style = Stroke(3.dp.toPx()))
                drawCircle(colors[i], r * 0.45f, c)
                drawCircle(Palette.Ink, r * 0.45f, c, style = Stroke(3.dp.toPx()))
                drawIntoCanvas {
                    label.textSize = 14.dp.toPx()
                    label.color = Color.White.toArgb()
                    it.nativeCanvas.drawText(names[i], c.x, c.y - r * 1.15f - 8.dp.toPx(), label)
                }
            }
        }

        Column(Modifier.align(Alignment.TopCenter).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GameText("EDIT CONTROL LAYOUT", Type.Title, color = Palette.Gold, outline = 3.5.dp)
            PlainText("Drag each control where your thumb wants it. Put them all on one side to play one-handed.", Type.Body, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChunkyButton({ layout = ControlLayout(); onChange(ControlLayout()) }, Modifier.size(170.dp, 52.dp), ButtonStyle.PURPLE, lip = 4.dp) { GameText("RESET", Type.Heading) }
                ChunkyButton(onClose, Modifier.size(170.dp, 52.dp), ButtonStyle.GREEN, lip = 4.dp) { GameText("DONE", Type.Heading) }
            }
        }
    }
}
