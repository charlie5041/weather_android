package com.charlie.weather.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 依天氣與日夜決定的背景漸層（模仿 iOS 天氣的配色）。 */
fun backgroundColors(code: Int, isDay: Boolean): List<Color> = when {
    WeatherCodes.isThunder(code) -> listOf(Color(0xFF232634), Color(0xFF474D63))
    WeatherCodes.isSnow(code) -> if (isDay) listOf(Color(0xFF7F98B3), Color(0xFFC3D2E2)) else listOf(Color(0xFF1E2838), Color(0xFF46566E))
    WeatherCodes.isRain(code) -> if (isDay) listOf(Color(0xFF3F5268), Color(0xFF71879E)) else listOf(Color(0xFF121A26), Color(0xFF2E3B4C))
    code == 45 || code == 48 -> if (isDay) listOf(Color(0xFF6F7B86), Color(0xFFA9B2BA)) else listOf(Color(0xFF1E2329), Color(0xFF444B53))
    code == 3 -> if (isDay) listOf(Color(0xFF55708D), Color(0xFF93A9C0)) else listOf(Color(0xFF1A212C), Color(0xFF3B4757))
    code == 2 -> if (isDay) listOf(Color(0xFF2F72BD), Color(0xFF7FAFD9)) else listOf(Color(0xFF0E1830), Color(0xFF34435F))
    else -> if (isDay) listOf(Color(0xFF1F6FC9), Color(0xFF6BB6F0)) else listOf(Color(0xFF070F26), Color(0xFF26355C))
}

@Composable
fun WeatherBackground(code: Int, isDay: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(backgroundColors(code, isDay)))) {
        when {
            WeatherCodes.isRain(code) -> RainEffect(heavy = code in listOf(65, 67, 82) || WeatherCodes.isThunder(code))
            WeatherCodes.isSnow(code) -> SnowEffect()
            !isDay && code <= 2 -> StarsEffect()
        }
    }
}

private class Particle(val x: Float, val phase: Float, val speed: Float, val size: Float)

private fun particles(count: Int, seed: Int) = Random(seed).let { r ->
    List(count) { Particle(r.nextFloat(), r.nextFloat(), 0.7f + r.nextFloat() * 0.6f, r.nextFloat()) }
}

@Composable
private fun RainEffect(heavy: Boolean) {
    val drops = remember(heavy) { particles(if (heavy) 120 else 70, 1) }
    val progress by rememberInfiniteTransition(label = "rain").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "rainProgress",
    )
    Canvas(Modifier.fillMaxSize()) {
        val length = size.height * 0.035f
        drops.forEach { d ->
            val t = (progress * d.speed + d.phase) % 1f
            val y = t * (size.height + length) - length
            val x = d.x * size.width - t * size.width * 0.04f
            drawLine(
                color = Color.White.copy(alpha = 0.18f + d.size * 0.17f),
                start = Offset(x, y),
                end = Offset(x - length * 0.12f, y + length * (0.6f + d.size * 0.6f)),
                strokeWidth = 1.2f + d.size * 1.2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SnowEffect() {
    val flakes = remember { particles(70, 2) }
    val progress by rememberInfiniteTransition(label = "snow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "snowProgress",
    )
    Canvas(Modifier.fillMaxSize()) {
        flakes.forEach { f ->
            val t = (progress * f.speed + f.phase) % 1f
            val y = t * (size.height + 20f) - 10f
            val x = f.x * size.width + sin((t * 4 + f.phase) * 2 * PI).toFloat() * 18f
            drawCircle(Color.White.copy(alpha = 0.55f + f.size * 0.35f), radius = 2f + f.size * 4f, center = Offset(x, y))
        }
    }
}

@Composable
private fun StarsEffect() {
    val stars = remember { particles(80, 3) }
    val twinkle by rememberInfiniteTransition(label = "stars").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle",
    )
    Canvas(Modifier.fillMaxSize()) {
        stars.forEach { s ->
            val alpha = 0.25f + 0.55f * (0.5f + 0.5f * sin((twinkle + s.phase) * 2 * PI).toFloat())
            drawCircle(
                Color.White.copy(alpha = alpha * (0.4f + s.size * 0.6f)),
                radius = 1f + s.size * 1.8f,
                center = Offset(s.x * size.width, s.phase * size.height * 0.55f),
            )
        }
    }
}
