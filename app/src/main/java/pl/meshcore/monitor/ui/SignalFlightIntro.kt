package pl.meshcore.monitor.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.meshcore.monitor.R
import kotlin.math.sin

/** Local, five-second welcome animation. Never waits for a network request. */
@Composable
fun SignalFlightIntro(onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val started = SystemClock.elapsedRealtime()
        while (seconds < 5f) {
            withFrameNanos {
                seconds = ((SystemClock.elapsedRealtime() - started) / 1000f).coerceAtMost(5f)
            }
        }
        finished()
    }
    val points = remember {
        List(66) { index ->
            fun fraction(value: Double) = ((value % 1 + 1) % 1).toFloat()
            Offset(40 + fraction(sin(index * 17.123) * 43758.5453) * 540,
                80 + fraction(sin(index * 31.91) * 12345.678) * 1080)
        }
    }
    val links = remember(points) {
        points.flatMapIndexed { index, point ->
            points.indices.filter { it != index }.sortedBy { (points[it] - point).getDistance() }
                .take(2).filter { it > index }.map { index to it }
        }
    }
    val route = remember { listOf(Offset(90f, 1050f), Offset(245f, 870f), Offset(390f, 700f),
        Offset(265f, 475f), Offset(470f, 290f), Offset(325f, 125f)) }
    val dark = Color(0xFF080E0B)
    val green = Color(0xFF68F69B)
    val reveal = introEase((seconds - 2.6f) / 1.1f)
    Box(Modifier.fillMaxSize().background(dark), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = maxOf(size.width / 620f, size.height / 1240f)
            val zoom = 1.75f - .75f * introEase(seconds / 3.8f)
            withTransform({
                translate(size.width / 2, size.height / 2)
                scale(scale * zoom, scale * zoom, pivot = Offset.Zero)
                translate(-310f, -620f + 100 * (1 - introEase(seconds / 3.8f)))
            }) {
                links.forEach { (a, b) -> drawLine(Color(0x55365043), points[a], points[b], 1f) }
                points.forEach { drawCircle(Color(0x66547361), 2f, it) }
                val progress = (seconds / 3.6f).coerceIn(0f, 1f) * 5
                fun glow(point: Offset, radius: Float) {
                    drawCircle(Brush.radialGradient(listOf(green.copy(alpha = .65f), Color.Transparent),
                        point, radius), radius, point)
                }
                route.zipWithNext().forEachIndexed { index, (a, b) ->
                    val amount = (progress - index).coerceIn(0f, 1f)
                    if (amount > 0) {
                        val tip = a + (b - a) * amount
                        drawLine(green.copy(alpha = .08f), a, tip, 18f)
                        drawLine(green.copy(alpha = .16f), a, tip, 9f)
                        drawLine(green, a, tip, 3f)
                        glow(a, 24f)
                        if (amount < 1f) glow(tip, 45f)
                    }
                }
                route.forEachIndexed { index, point ->
                    if (progress >= index) drawCircle(Color(0xFFA0FFBB), 4f, point)
                }
            }
            drawRect(Brush.radialGradient(listOf(Color.Transparent, dark.copy(alpha = .92f)),
                center, size.height * .65f))
            drawRect(dark.copy(alpha = reveal * .62f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = reveal
                scaleX = .88f + .12f * reveal
                scaleY = scaleX
            }) {
            Image(painterResource(R.drawable.m2_logo), "M²", Modifier.size(116.dp))
            Spacer(Modifier.height(23.dp))
            Text("MeshCore Live Monitor", color = Color(0xFFEDF6EF), fontSize = 16.sp)
            Spacer(Modifier.height(15.dp))
            Text("FOLLOW THE SIGNAL", color = Color(0xFF83BA92), fontSize = 10.sp, letterSpacing = 3.sp)
        }
    }
}

private fun introEase(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
