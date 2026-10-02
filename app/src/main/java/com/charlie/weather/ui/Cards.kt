package com.charlie.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.AirQuality
import com.charlie.weather.data.CwaAlert
import com.charlie.weather.data.Weather
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

private val Secondary = Color.White.copy(alpha = 0.6f)
private val DividerColor = Color.White.copy(alpha = 0.18f)

@Composable
fun GlassCard(
    title: String,
    icon: String,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.sp, color = Secondary, fontWeight = FontWeight.Medium)
        }
        if (divider) {
            HorizontalDivider(Modifier.padding(top = 10.dp), color = DividerColor)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** 小方塊資訊卡（UV、濕度、能見度等）。 */
@Composable
fun InfoCard(
    title: String,
    icon: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    footer: String? = null,
    visual: (@Composable () -> Unit)? = null,
) {
    GlassCard(title, icon, modifier.heightIn(min = 160.dp)) {
        Text(value, fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Medium)
        subtitle?.let { Text(it, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium) }
        if (visual != null) {
            Spacer(Modifier.height(8.dp))
            visual()
        }
        Spacer(Modifier.weight(1f))
        footer?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f), lineHeight = 17.sp)
        }
    }
}

// ---------------- 每小時預報 ----------------

private data class HourEntry(val time: LocalDateTime, val label: String, val icon: String, val pop: Int?, val value: String)

private fun buildHourEntries(w: Weather): List<HourEntry> {
    val start = w.current.time.truncatedTo(ChronoUnit.HOURS)
    val hours = w.hourly.filter { !it.time.isBefore(start) }.take(25)
    if (hours.isEmpty()) return emptyList()
    val end = hours.last().time
    val entries = hours.mapIndexed { i, h ->
        if (i == 0) {
            HourEntry(h.time, "現在", WeatherCodes.emoji(w.current.weatherCode, w.current.isDay), h.precipitationProbability, w.current.temperature.deg())
        } else {
            HourEntry(h.time, hourLabel(h.time), WeatherCodes.emoji(h.weatherCode, h.isDay), h.precipitationProbability, h.temperature.deg())
        }
    }.toMutableList()
    w.daily.forEach { d ->
        d.sunrise?.takeIf { it.isAfter(w.current.time) && it.isBefore(end) }?.let {
            entries += HourEntry(it, timeLabel(it), "🌅", null, "日出")
        }
        d.sunset?.takeIf { it.isAfter(w.current.time) && it.isBefore(end) }?.let {
            entries += HourEntry(it, timeLabel(it), "🌇", null, "日落")
        }
    }
    return entries.sortedBy { it.time }
}

private fun hourlySummary(w: Weather): String {
    val today = w.today ?: return WeatherCodes.description(w.current.weatherCode)
    val upcomingRain = w.hourly
        .filter { it.time.isAfter(w.current.time) }
        .take(12)
        .firstOrNull { (it.precipitationProbability ?: 0) >= 50 && WeatherCodes.isRain(it.weatherCode) }
    val rainText = when {
        WeatherCodes.isRain(w.current.weatherCode) -> "目前${w.current.conditionText()}。"
        upcomingRain != null -> "預計約${hourLabel(upcomingRain.time)}起可能下雨。"
        else -> ""
    }
    return "今日${today.description ?: WeatherCodes.description(today.weatherCode)}，最高溫 ${today.temperatureMax.deg()}。$rainText" +
        "風速最高 ${w.current.windGusts.roundOr()} km/h。"
}

@Composable
fun HourlyCard(w: Weather, modifier: Modifier = Modifier) {
    val entries = buildHourEntries(w)
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .padding(vertical = 12.dp),
    ) {
        Text(
            hourlySummary(w),
            fontSize = 14.sp,
            color = Color.White,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = DividerColor)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(entries) { e ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(e.label, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(e.icon, fontSize = 22.sp)
                            if ((e.pop ?: 0) >= 20) {
                                Text("${e.pop}%", fontSize = 11.sp, color = PrecipBlue, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text(e.value, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ---------------- 10 日預報 ----------------

@Composable
fun DailyCard(w: Weather, modifier: Modifier = Modifier) {
    val days = w.daily
    if (days.isEmpty()) return
    val minAll = days.minOf { it.temperatureMin }
    val maxAll = days.maxOf { it.temperatureMax }
    GlassCard("${days.size} 日天氣預報", "📅", modifier, divider = false) {
        days.forEachIndexed { i, d ->
            HorizontalDivider(color = DividerColor)
            Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i == 0) "今天" else weekdayLabel(d.date.dayOfWeek),
                    modifier = Modifier.width(48.dp),
                    fontSize = 17.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
                Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(WeatherCodes.emoji(d.weatherCode, true), fontSize = 20.sp)
                    if ((d.precipitationProbability ?: 0) >= 20) {
                        Text("${d.precipitationProbability}%", fontSize = 11.sp, color = PrecipBlue, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    d.temperatureMin.deg(),
                    modifier = Modifier.width(42.dp),
                    textAlign = TextAlign.End,
                    fontSize = 17.sp,
                    color = Secondary,
                    fontWeight = FontWeight.Medium,
                )
                TemperatureRangeBar(
                    rangeMin = minAll,
                    rangeMax = maxAll,
                    low = d.temperatureMin,
                    high = d.temperatureMax,
                    current = if (i == 0) w.current.temperature else null,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(5.dp),
                )
                Text(
                    d.temperatureMax.deg(),
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End,
                    fontSize = 17.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun TemperatureRangeBar(
    rangeMin: Double,
    rangeMax: Double,
    low: Double,
    high: Double,
    current: Double?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val span = (rangeMax - rangeMin).takeIf { it > 0 } ?: 1.0
        fun x(t: Double) = (((t - rangeMin) / span).toFloat().coerceIn(0f, 1f)) * size.width
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Color.Black.copy(alpha = 0.25f), cornerRadius = radius)
        val colors = List(8) { i -> temperatureColor(rangeMin + span * i / 7) }
        val brush = Brush.horizontalGradient(colors, startX = 0f, endX = size.width)
        val start = x(low)
        val end = maxOf(x(high), start + size.height)
        drawRoundRect(brush, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = radius)
        if (current != null && !current.isNaN()) {
            val center = Offset(x(current), size.height / 2)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.height * 0.9f + 1.5.dp.toPx(), center = center)
            drawCircle(Color.White, radius = size.height * 0.9f, center = center)
        }
    }
}

/** 漸層橫條加上目前位置的小圓點（UV、空氣品質用）。 */
@Composable
fun GradientIndicatorBar(colors: List<Color>, fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(5.dp)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Brush.horizontalGradient(colors), cornerRadius = radius)
        val center = Offset(fraction.coerceIn(0f, 1f) * size.width, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.height + 1.5.dp.toPx(), center = center)
        drawCircle(Color.White, radius = size.height, center = center)
    }
}

// ---------------- 天氣特報 ----------------

@Composable
fun AlertsCard(alerts: List<CwaAlert>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFB3261E).copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text("中央氣象署天氣特報", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        alerts.forEach { alert ->
            Text(alert.title, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            val range = listOfNotNull(
                alert.start?.let { "${it.monthValue}/${it.dayOfMonth} ${timeLabel(it)}" },
                alert.end?.let { "${it.monthValue}/${it.dayOfMonth} ${timeLabel(it)}" },
            ).joinToString(" 至 ")
            if (range.isNotEmpty()) {
                Text(range, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---------------- 空氣品質 ----------------

private fun aqiCategory(aqi: Int) = when {
    aqi <= 50 -> "良好"
    aqi <= 100 -> "普通"
    aqi <= 150 -> "對敏感族群不健康"
    aqi <= 200 -> "對所有族群不健康"
    aqi <= 300 -> "非常不健康"
    else -> "危害"
}

private fun aqiAdvice(aqi: Int) = when {
    aqi <= 50 -> "空氣品質令人滿意，適合戶外活動。"
    aqi <= 100 -> "空氣品質可接受，極少數敏感者應注意。"
    aqi <= 150 -> "敏感族群應減少長時間戶外劇烈活動。"
    else -> "建議減少外出，外出時請配戴口罩。"
}

@Composable
fun AirQualityCard(aq: AirQuality, modifier: Modifier = Modifier) {
    GlassCard("空氣品質", "🍃", modifier) {
        Text("${aq.usAqi}", fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Medium)
        Text(aqiCategory(aq.usAqi), fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        GradientIndicatorBar(
            colors = listOf(Color(0xFF34C759), Color(0xFFFFCC00), Color(0xFFFF9500), Color(0xFFFF3B30), Color(0xFFAF52DE), Color(0xFF8E3A59)),
            fraction = aq.usAqi / 300f,
        )
        Spacer(Modifier.height(10.dp))
        val pm = listOfNotNull(
            aq.pm25?.let { "PM2.5 ${it.roundToInt()} μg/m³" },
            aq.pm10?.let { "PM10 ${it.roundToInt()} μg/m³" },
        ).joinToString("・")
        Text(aqiAdvice(aq.usAqi) + if (pm.isNotEmpty()) "\n$pm" else "", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f), lineHeight = 17.sp)
    }
}

// ---------------- UV ----------------

private fun uvLevel(uv: Double) = when {
    uv < 3 -> "低"
    uv < 6 -> "中"
    uv < 8 -> "高"
    uv < 11 -> "過量"
    else -> "危險"
}

@Composable
fun UvCard(w: Weather, modifier: Modifier = Modifier) {
    val uv = w.current.uvIndex.takeUnless { it.isNaN() } ?: 0.0
    val max = w.today?.uvIndexMax?.takeUnless { it.isNaN() }
    InfoCard(
        title = "紫外線指數",
        icon = "☀️",
        value = uv.roundToInt().toString(),
        subtitle = uvLevel(uv),
        modifier = modifier,
        footer = when {
            max == null -> null
            max >= 6 && w.current.isDay -> "今日最高 ${max.roundToInt()}（${uvLevel(max)}），外出請做好防曬。"
            else -> "今日最高 ${max.roundToInt()}（${uvLevel(max)}）。"
        },
        visual = {
            GradientIndicatorBar(
                colors = listOf(Color(0xFF34C759), Color(0xFFFFCC00), Color(0xFFFF9500), Color(0xFFFF3B30), Color(0xFFAF52DE)),
                fraction = (uv / 11.0).toFloat(),
            )
        },
    )
}

// ---------------- 日出日落 ----------------

@Composable
fun SunCard(w: Weather, modifier: Modifier = Modifier) {
    val now = w.current.time
    val today = w.today
    val tomorrow = w.daily.getOrNull(1)
    val sunrise = today?.sunrise
    val sunset = today?.sunset
    val (title, icon, time, footer) = when {
        sunrise != null && now.isBefore(sunrise) -> Quad("日出", "🌅", sunrise, sunset?.let { "日落：${timeLabel(it)}" })
        sunset != null && now.isBefore(sunset) -> Quad("日落", "🌇", sunset, sunrise?.let { "日出：${timeLabel(it)}" })
        else -> Quad("日出", "🌅", tomorrow?.sunrise, tomorrow?.sunset?.let { "明日日落：${timeLabel(it)}" })
    }
    InfoCard(
        title = title,
        icon = icon,
        value = time?.let { timeLabel(it) } ?: "--",
        modifier = modifier,
        footer = footer,
        visual = {
            if (sunrise != null && sunset != null) {
                SunPathGraph(
                    sunriseFraction = sunrise.toLocalTime().toSecondOfDay() / 86400f,
                    sunsetFraction = sunset.toLocalTime().toSecondOfDay() / 86400f,
                    nowFraction = now.toLocalTime().toSecondOfDay() / 86400f,
                )
            }
        },
    )
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun SunPathGraph(sunriseFraction: Float, sunsetFraction: Float, nowFraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val noon = (sunriseFraction + sunsetFraction) / 2
        val horizonValue = cos(PI * (sunsetFraction - sunriseFraction)).toFloat()
        val amp = size.height * 0.45f
        val mid = size.height * 0.62f
        fun y(f: Float) = mid - (cos(2 * PI * (f - noon)).toFloat() - horizonValue) * amp / (1f - horizonValue).coerceAtLeast(0.3f) * 0.75f
        val path = Path().apply {
            moveTo(0f, y(0f))
            for (i in 1..60) {
                val f = i / 60f
                lineTo(f * size.width, y(f))
            }
        }
        drawPath(path, Color.White.copy(alpha = 0.3f), style = Stroke(width = 2.dp.toPx()))
        clipRect(bottom = mid) {
            drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx()))
        }
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val sun = Offset(nowFraction * size.width, y(nowFraction))
        drawCircle(Color.White.copy(alpha = 0.35f), radius = 7.dp.toPx(), center = sun)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = sun)
    }
}

// ---------------- 風 ----------------

@Composable
fun WindCard(w: Weather, modifier: Modifier = Modifier) {
    val c = w.current
    GlassCard("風", "💨", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                WindRow("風", "${c.windSpeed.roundOr()} km/h")
                HorizontalDivider(color = DividerColor)
                WindRow("陣風", "${c.windGusts.roundOr()} km/h")
                HorizontalDivider(color = DividerColor)
                WindRow("風向", "${c.windDirection}° ${compassLabel(c.windDirection)}")
            }
            Spacer(Modifier.width(16.dp))
            Compass(c.windDirection, c.windSpeed, Modifier.size(116.dp))
        }
    }
}

@Composable
private fun WindRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(label, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, fontSize = 16.sp, color = Secondary)
    }
}

@Composable
private fun Compass(direction: Int, speed: Double, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val r = size.minDimension / 2
            val center = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 72) {
                val major = i % 18 == 0
                rotate(i * 5f, center) {
                    drawLine(
                        Color.White.copy(alpha = if (major) 0.9f else 0.35f),
                        Offset(center.x, center.y - r),
                        Offset(center.x, center.y - r + (if (major) 8.dp else 5.dp).toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            listOf("北" to 0.0, "東" to 90.0, "南" to 180.0, "西" to 270.0).forEach { (label, angle) ->
                val layout = measurer.measure(label, labelStyle)
                val rad = Math.toRadians(angle)
                val lr = r - 18.dp.toPx()
                val pos = Offset(
                    center.x + (lr * kotlin.math.sin(rad)).toFloat() - layout.size.width / 2,
                    center.y - (lr * kotlin.math.cos(rad)).toFloat() - layout.size.height / 2,
                )
                drawText(layout, topLeft = pos)
            }
            // 箭頭指向風吹去的方向
            rotate(direction + 180f, center) {
                val tail = Offset(center.x, center.y + r * 0.78f)
                val head = Offset(center.x, center.y - r * 0.78f)
                drawLine(Color.White, tail, head, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(head.x, head.y - 4.dp.toPx())
                    lineTo(head.x - 6.dp.toPx(), head.y + 8.dp.toPx())
                    lineTo(head.x + 6.dp.toPx(), head.y + 8.dp.toPx())
                    close()
                }
                drawPath(arrow, Color.White)
                drawCircle(Color.White, radius = 4.dp.toPx(), center = tail)
            }
            drawCircle(Color(0xFF2B3A4E).copy(alpha = 0.92f), radius = r * 0.36f, center = center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speed.roundOr(), fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
            Text("km/h", fontSize = 9.sp, color = Color.White, lineHeight = 10.sp)
        }
    }
}

// ---------------- 其他小卡 ----------------

@Composable
fun PrecipitationCard(w: Weather, modifier: Modifier = Modifier) {
    val today = w.today?.precipitationSum ?: 0.0
    val next24 = w.hourly.filter { it.time.isAfter(w.current.time) }.take(24).sumOf { it.precipitation }
    InfoCard(
        title = "降雨量",
        icon = "💧",
        value = "${formatMm(today)} 毫米",
        subtitle = "今日",
        modifier = modifier,
        footer = if (next24 < 0.1) "未來 24 小時預計不會下雨。" else "預計未來 24 小時降雨 ${formatMm(next24)} 毫米。",
    )
}

private fun formatMm(v: Double) = if (v < 10) "%.1f".format(v) else v.roundToInt().toString()

@Composable
fun FeelsLikeCard(w: Weather, modifier: Modifier = Modifier) {
    val c = w.current
    val diff = c.apparentTemperature - c.temperature
    InfoCard(
        title = "體感溫度",
        icon = "🌡️",
        value = c.apparentTemperature.deg(),
        modifier = modifier,
        footer = when {
            diff.isNaN() -> null
            diff <= -2 && c.windSpeed > 15 -> "風使體感溫度較低。"
            diff <= -2 -> "體感比實際溫度涼。"
            diff >= 2 && c.humidity >= 60 -> "濕度使體感溫度較高。"
            diff >= 2 -> "體感比實際溫度熱。"
            else -> "與實際溫度相近。"
        },
    )
}

@Composable
fun HumidityCard(w: Weather, modifier: Modifier = Modifier) {
    InfoCard(
        title = "濕度",
        icon = "💦",
        value = "${w.current.humidity}%",
        modifier = modifier,
        footer = "目前露點為 ${w.current.dewPoint.deg()}。",
    )
}

@Composable
fun VisibilityCard(w: Weather, modifier: Modifier = Modifier) {
    val km = w.current.visibility / 1000.0
    InfoCard(
        title = "能見度",
        icon = "👁️",
        value = when {
            km.isNaN() -> "--"
            km >= 10 -> "${km.roundToInt()} 公里"
            else -> "${"%.1f".format(km)} 公里"
        },
        modifier = modifier,
        footer = when {
            km.isNaN() -> null
            km >= 10 -> "目前視野非常清晰。"
            km >= 5 -> "視野良好。"
            km >= 1 -> "輕度霧氣或霾影響視野。"
            else -> "濃霧，視野不佳。"
        },
    )
}

@Composable
fun PressureCard(w: Weather, modifier: Modifier = Modifier) {
    val p = w.current.pressure
    InfoCard(
        title = "氣壓",
        icon = "🧭",
        value = p.roundOr(),
        subtitle = "hPa",
        modifier = modifier,
        visual = { PressureGauge(p) },
    )
}

@Composable
private fun PressureGauge(pressure: Double) {
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val fraction = (((pressure - 970) / 70).toFloat()).coerceIn(0f, 1f).takeUnless { pressure.isNaN() } ?: 0.5f
        val stroke = 5.dp.toPx()
        val diameter = minOf(size.width, size.height * 2) - stroke
        val topLeft = Offset((size.width - diameter) / 2, stroke / 2)
        drawArc(
            Color.White.copy(alpha = 0.25f), 180f, 180f, false,
            topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            Color.White, 180f, 180f * fraction, false,
            topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun CloudCoverCard(w: Weather, modifier: Modifier = Modifier) {
    val cc = w.current.cloudCover
    InfoCard(
        title = "雲量",
        icon = "☁️",
        value = "$cc%",
        modifier = modifier,
        footer = when {
            cc < 20 -> "天空晴朗少雲。"
            cc < 50 -> "部分天空有雲。"
            cc < 85 -> "雲量偏多。"
            else -> "天空幾乎被雲覆蓋。"
        },
    )
}
