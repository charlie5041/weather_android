package com.charlie.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.charlie.weather.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * 向量天氣圖示（取代 emoji：emoji 依手機品牌長得不一樣、也無法配合主題色）。
 * 線條風格與分頁列圖示一致：24 格、1.8 線寬、圓頭。
 */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp, cloudColor: Color = Palette.Ink3) {
    val label = WeatherCodes.description(code)
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val sun = Palette.Sun
        val moon = Palette.Primary
        val rain = Palette.Rain
        when {
            code == 0 || (code == 1 && !isDay) -> if (isDay) sun(Offset(12f, 12f), 4.4f, u, sun, stroke, rays = true) else moon(Offset(12f, 12f), 7f, u, moon, stroke)
            code == 1 || code == 2 -> {
                if (isDay) sun(Offset(8.5f, 8.5f), 3.2f, u, sun, stroke, rays = true) else moon(Offset(9f, 8f), 4.6f, u, moon, stroke)
                cloud(u, cloudColor, stroke, dy = 3f, scale = 0.82f, dx = 3f)
            }
            code == 3 -> cloud(u, cloudColor, stroke)
            code == 45 || code == 48 -> {
                cloud(u, cloudColor, stroke, dy = -3f)
                listOf(16.5f, 20f).forEachIndexed { i, y ->
                    drawLine(cloudColor, Offset((5f + i * 2) * u, y * u), Offset((19f - i * 2) * u, y * u), 1.8f * u, StrokeCap.Round)
                }
            }
            WeatherCodes.isThunder(code) -> {
                cloud(u, cloudColor, stroke, dy = -3f)
                val bolt = Path().apply {
                    moveTo(13f * u, 14f * u); lineTo(10.5f * u, 18f * u); lineTo(13.5f * u, 18f * u); lineTo(11f * u, 22f * u)
                }
                drawPath(bolt, sun, style = stroke)
            }
            WeatherCodes.isSnow(code) -> {
                cloud(u, cloudColor, stroke, dy = -3f)
                listOf(8f, 12f, 16f).forEach { x ->
                    drawCircle(rain, radius = 1.2f * u, center = Offset(x * u, (if (x == 12f) 20.5f else 18.5f) * u))
                }
            }
            WeatherCodes.isRain(code) -> {
                cloud(u, cloudColor, stroke, dy = -3f)
                val heavy = code in listOf(65, 67, 82)
                val xs = if (code in 51..57) listOf(10f, 14f) else if (heavy) listOf(8f, 11.5f, 15f, 18.5f) else listOf(9f, 13f, 17f)
                xs.forEach { x -> drawLine(rain, Offset(x * u, 17.5f * u), Offset((x - 1f) * u, 20.5f * u), 1.8f * u, StrokeCap.Round) }
            }
            else -> cloud(u, cloudColor, stroke)
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, u: Float, color: Color, stroke: Stroke, rays: Boolean) {
    drawCircle(color, radius = r * u, center = Offset(c.x * u, c.y * u), style = stroke)
    if (!rays) return
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val inner = r + 2.2f
        val outer = r + 4f
        drawLine(
            color,
            Offset((c.x + inner * cos(a).toFloat()) * u, (c.y + inner * sin(a).toFloat()) * u),
            Offset((c.x + outer * cos(a).toFloat()) * u, (c.y + outer * sin(a).toFloat()) * u),
            1.8f * u,
            StrokeCap.Round,
        )
    }
}

/** 彎月：外圓減去偏右上的內圓。 */
private fun DrawScope.moon(c: Offset, r: Float, u: Float, color: Color, stroke: Stroke) {
    val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x * u, c.y * u), r * u)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset((c.x + r * 0.55f) * u, (c.y - r * 0.45f) * u), r * 0.8f * u)) }
    drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, outer, bite), color, style = stroke)
}

/** 雲：以 24 格座標畫，dx/dy 平移、scale 以雲的中心縮放。 */
private fun DrawScope.cloud(u: Float, color: Color, stroke: Stroke, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f) {
    fun p(x: Float, y: Float) = Offset(((x - 12f) * scale + 12f + dx) * u, ((y - 13f) * scale + 13f + dy) * u)
    val path = Path().apply {
        val a = p(7f, 18.5f); moveTo(a.x, a.y)
        val b = p(17f, 18.5f); lineTo(b.x, b.y)
        val c1 = p(21.5f, 18.5f); val c2 = p(21.5f, 10.5f); val e1 = p(17.4f, 10.5f); cubicTo(c1.x, c1.y, c2.x, c2.y, e1.x, e1.y)
        val d1 = p(16f, 5.5f); val d2 = p(8f, 5.5f); val e2 = p(6.8f, 11.9f); cubicTo(d1.x, d1.y, d2.x, d2.y, e2.x, e2.y)
        val f1 = p(2.5f, 12.5f); val f2 = p(2.8f, 18.5f); cubicTo(f1.x, f1.y, f2.x, f2.y, a.x, a.y)
        close()
    }
    drawPath(path, color, style = stroke)
}
