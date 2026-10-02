package com.charlie.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.HourlyForecast
import com.charlie.weather.data.Weather
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class DetailMetric(val title: String, val icon: String) {
    TEMPERATURE("溫度", "🌡️"),
    FEELS_LIKE("體感溫度", "🤚"),
    PRECIPITATION("降雨", "💧"),
    WIND("風", "💨"),
    UV("紫外線指數", "☀️"),
    HUMIDITY("濕度", "💦"),
}

data class DetailRequest(val cityId: String, val metric: DetailMetric, val date: LocalDate?)

private enum class ChartStyle { LINE, BAR }

/** 一個項目在某一天的圖表資料與摘要。 */
private class Series(
    val points: List<Pair<LocalDateTime, Double>>,
    val style: ChartStyle,
    val yMin: Double,
    val yMax: Double,
    val color: (Double) -> Color,
    val format: (Double) -> String,
    val headline: String,
    val subline: String,
    val description: String,
)

private val WindColor = Color(0xFF64D2FF)
private val HumidityColor = Color(0xFF5AC8FA)

private fun uvColor(uv: Double) = when {
    uv < 3 -> Color(0xFF34C759)
    uv < 6 -> Color(0xFFFFCC00)
    uv < 8 -> Color(0xFFFF9500)
    uv < 11 -> Color(0xFFFF3B30)
    else -> Color(0xFFAF52DE)
}

private fun uvLevelText(uv: Double) = when {
    uv < 3 -> "低"
    uv < 6 -> "中"
    uv < 8 -> "高"
    uv < 11 -> "過量"
    else -> "危險"
}

private fun buildSeries(metric: DetailMetric, weather: Weather, date: LocalDate, hours: List<HourlyForecast>): Series {
    fun pts(f: (HourlyForecast) -> Double?) = hours.mapNotNull { h -> f(h)?.takeUnless { it.isNaN() }?.let { h.time to it } }
    val day = weather.daily.firstOrNull { it.date == date }
    return when (metric) {
        DetailMetric.TEMPERATURE -> {
            val p = pts { it.temperature }
            val lo = day?.temperatureMin ?: p.minOfOrNull { it.second } ?: 0.0
            val hi = day?.temperatureMax ?: p.maxOfOrNull { it.second } ?: 0.0
            val values = p.map { it.second } + lo + hi
            Series(
                p, ChartStyle.LINE, floor(values.min() - 2), ceil(values.max() + 2), ::temperatureColor, { it.deg() },
                headline = "${hi.deg()} / ${lo.deg()}",
                subline = day?.description ?: day?.let { WeatherCodes.description(it.weatherCode) } ?: "",
                description = "最高溫 ${hi.deg()}，最低溫 ${lo.deg()}。" +
                    (day?.precipitationProbability?.let { "降雨機率最高 $it%。" } ?: ""),
            )
        }
        DetailMetric.FEELS_LIKE -> {
            val p = pts { it.apparentTemperature }
            val values = p.map { it.second }.ifEmpty { listOf(0.0) }
            Series(
                p, ChartStyle.LINE, floor(values.min() - 2), ceil(values.max() + 2), ::temperatureColor, { it.deg() },
                headline = "${values.max().deg()} / ${values.min().deg()}",
                subline = "體感溫度範圍",
                description = "體感溫度綜合了溫度、濕度與風，代表身體實際感受到的冷熱程度。",
            )
        }
        DetailMetric.PRECIPITATION -> {
            val p = pts { it.precipitationProbability?.toDouble() }
            val total = hours.sumOf { it.precipitation }
            val maxPop = p.maxOfOrNull { it.second }?.roundToInt() ?: 0
            Series(
                p, ChartStyle.BAR, 0.0, 100.0, { PrecipBlue }, { "${it.roundToInt()}%" },
                headline = "$maxPop%",
                subline = "降雨機率最高",
                description = if (total < 0.1) "預計全天不會有明顯降雨。" else "全天預計累積雨量約 ${"%.1f".format(total)} 毫米。",
            )
        }
        DetailMetric.WIND -> {
            val p = pts { it.windSpeed }
            val gust = hours.mapNotNull { it.windGusts.takeUnless { g -> g.isNaN() } }.maxOrNull()
            val max = p.maxOfOrNull { it.second } ?: 0.0
            Series(
                p, ChartStyle.LINE, 0.0, maxOf(20.0, ceil((maxOf(max, 1.0) * 1.25) / 10) * 10), { WindColor }, { "${it.roundToInt()} km/h" },
                headline = "${max.roundToInt()} km/h",
                subline = "最大平均風速",
                description = gust?.let { "陣風最高約 ${it.roundToInt()} km/h。" } ?: "",
            )
        }
        DetailMetric.UV -> {
            val p = pts { it.uvIndex }
            val max = day?.uvIndexMax?.takeUnless { it.isNaN() } ?: p.maxOfOrNull { it.second } ?: 0.0
            Series(
                p, ChartStyle.BAR, 0.0, maxOf(11.0, ceil(max)), ::uvColor, { it.roundToInt().toString() },
                headline = "${max.roundToInt()} ${uvLevelText(max)}",
                subline = "最高紫外線指數",
                description = if (max >= 6) "紫外線偏強，10 時至 14 時外出請做好防曬。" else "紫外線不強，一般外出無需特別防護。",
            )
        }
        DetailMetric.HUMIDITY -> {
            val p = pts { it.humidity?.toDouble() }
            val avg = p.map { it.second }.average().takeUnless { it.isNaN() } ?: 0.0
            Series(
                p, ChartStyle.LINE, 0.0, 100.0, { HumidityColor }, { "${it.roundToInt()}%" },
                headline = "${avg.roundToInt()}%",
                subline = "平均相對濕度",
                description = "相對濕度越高，體感越悶熱；低於 40% 時會覺得乾燥。",
            )
        }
    }
}

@Composable
fun DetailScreen(
    city: City,
    weather: Weather,
    initialMetric: DetailMetric,
    initialDate: LocalDate?,
    onClose: () -> Unit,
) {
    var metric by rememberSaveable { mutableStateOf(initialMetric) }
    val dates = weather.daily.map { it.date }
    var date by remember { mutableStateOf(initialDate?.takeIf { it in dates } ?: dates.firstOrNull() ?: weather.current.time.toLocalDate()) }
    val hours = remember(weather, date) { weather.hourly.filter { it.time.toLocalDate() == date } }
    val series = remember(metric, weather, date) { buildSeries(metric, weather, date, hours) }
    var selected by remember(metric, date) { mutableStateOf<Int?>(null) }
    val now = weather.localNow()
    val today = now.toLocalDate()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${metric.icon} ${metric.title}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(city.name, fontSize = 13.sp, color = Color.Gray)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }

        // 日期選擇
        val dateListState = rememberLazyListState()
        LaunchedEffect(Unit) { dateListState.scrollToItem(maxOf(0, dates.indexOf(date) - 2)) }
        LazyRow(
            state = dateListState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(dates) { d ->
                val active = d == date
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { date = d }.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (d == today) "今天" else weekdayLabel(d.dayOfWeek), fontSize = 12.sp, color = Color.Gray)
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(if (active) Color.White else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            d.dayOfMonth.toString(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) Color.Black else Color.White,
                        )
                    }
                }
            }
        }

        // 數值摘要（手指按住圖表時顯示該小時）
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(64.dp)) {
            val point = selected?.let { series.points.getOrNull(it) }
            if (point != null) {
                Text(series.format(point.second), fontSize = 32.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(hourLabel(point.first), fontSize = 14.sp, color = Color.Gray)
            } else {
                Text(series.headline, fontSize = 32.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(series.subline, fontSize = 14.sp, color = Color.Gray)
            }
        }

        if (metric == DetailMetric.TEMPERATURE) {
            // 每 3 小時的天氣圖示
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 52.dp)) {
                (0 until 24 step 3).forEach { hour ->
                    val h = hours.firstOrNull { it.time.hour == hour }
                    Text(
                        h?.let { WeatherCodes.emoji(it.weatherCode, it.isDay) } ?: "",
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        HourlyChart(
            series = series,
            now = now.takeIf { date == today },
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 切換項目
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DetailMetric.entries) { m ->
                val active = m == metric
                Text(
                    "${m.icon} ${m.title}",
                    fontSize = 14.sp,
                    color = if (active) Color.Black else Color.White,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Color.White else Color(0xFF2C2C2E))
                        .clickable { metric = m }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        if (series.description.isNotBlank()) {
            Text(
                series.description,
                fontSize = 15.sp,
                color = Color.White,
                lineHeight = 21.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(16.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

private fun hourOf(t: LocalDateTime) = t.hour + t.minute / 60f

@Composable
private fun HourlyChart(
    series: Series,
    now: LocalDateTime?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.Gray, fontSize = 11.sp)
    val points = series.points
    Canvas(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(series) {
                val rightPad = 36.dp.toPx()
                fun pick(x: Float): Int? {
                    if (points.isEmpty()) return null
                    val hour = (x / (size.width - rightPad)).coerceIn(0f, 1f) * 24f
                    val offset = if (series.style == ChartStyle.BAR) 0.5f else 0f
                    return points.indices.minByOrNull { abs(hourOf(points[it].first) + offset - hour) }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSelect(pick(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        onSelect(pick(change.position.x))
                        change.consume()
                    }
                    onSelect(null)
                }
            },
    ) {
        val rightPad = 36.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val w = size.width - rightPad
        val h = size.height - bottomPad
        val span = (series.yMax - series.yMin).takeIf { it > 0 } ?: 1.0
        fun x(t: LocalDateTime) = hourOf(t) / 24f * w
        fun y(v: Double) = (h - ((v - series.yMin) / span) * h).toFloat()

        // 格線與標籤
        val gridColor = Color.White.copy(alpha = 0.12f)
        for (k in 0..4) {
            val value = series.yMin + span * k / 4
            val yy = y(value)
            drawLine(gridColor, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
            val layout = measurer.measure(series.format(value), labelStyle)
            drawText(layout, topLeft = Offset(w + 6.dp.toPx(), yy - layout.size.height / 2f))
        }
        for (hour in 0..24 step 6) {
            val xx = hour / 24f * w
            drawLine(gridColor, Offset(xx, 0f), Offset(xx, h), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            if (hour < 24) {
                val layout = measurer.measure("${hour}時", labelStyle)
                drawText(layout, topLeft = Offset(xx + 2.dp.toPx(), h + 4.dp.toPx()))
            }
        }

        if (points.isEmpty()) return@Canvas

        when (series.style) {
            ChartStyle.LINE -> {
                val line = Path().apply {
                    points.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(points.last().first), h)
                    lineTo(x(points.first().first), h)
                    close()
                }
                val colors = points.map { series.color(it.second) }
                val lineBrush = if (colors.size > 1) {
                    Brush.horizontalGradient(colors, startX = x(points.first().first), endX = x(points.last().first))
                } else {
                    Brush.horizontalGradient(listOf(colors.first(), colors.first()))
                }
                drawPath(
                    fill,
                    Brush.verticalGradient(listOf(colors.first().copy(alpha = 0.35f), Color.Transparent), startY = 0f, endY = h),
                )
                drawPath(line, lineBrush, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ChartStyle.BAR -> {
                val slot = w / 24f
                points.forEach { (t, v) ->
                    val top = y(v)
                    drawRoundRect(
                        series.color(v),
                        topLeft = Offset(x(t) + slot * 0.2f, top),
                        size = Size(slot * 0.6f, (h - top).coerceAtLeast(1.5f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }

        // 已經過去的時間調暗，並標示現在
        if (now != null) {
            val nx = x(now)
            drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset.Zero, size = Size(nx, h))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(nx, 0f), Offset(nx, h), strokeWidth = 1.5f)
        }

        selected?.let { points.getOrNull(it) }?.let { (t, v) ->
            val sx = if (series.style == ChartStyle.BAR) x(t) + w / 48f else x(t)
            drawLine(Color.White, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.5f)
            drawCircle(Color.Black, radius = 7.dp.toPx(), center = Offset(sx, y(v)))
            drawCircle(series.color(v), radius = 5.dp.toPx(), center = Offset(sx, y(v)))
        }
    }
}
