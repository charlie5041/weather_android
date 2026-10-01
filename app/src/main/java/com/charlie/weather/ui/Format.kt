package com.charlie.weather.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.roundToInt

object WeatherCodes {
    fun description(code: Int): String = when (code) {
        0 -> "晴朗"
        1 -> "大致晴朗"
        2 -> "局部多雲"
        3 -> "陰天"
        45, 48 -> "有霧"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "凍毛毛雨"
        61 -> "小雨"
        63 -> "雨"
        65 -> "大雨"
        66, 67 -> "凍雨"
        71 -> "小雪"
        73 -> "雪"
        75 -> "大雪"
        77 -> "霰"
        80 -> "短暫陣雨"
        81 -> "陣雨"
        82 -> "豪大雨"
        85, 86 -> "陣雪"
        95 -> "雷雨"
        96, 99 -> "雷雨伴隨冰雹"
        else -> "—"
    }

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun isRain(code: Int) = code in 51..67 || code in 80..82 || code in 95..99
    fun isSnow(code: Int) = code in 71..77 || code in 85..86
    fun isThunder(code: Int) = code in 95..99
    fun isCloudy(code: Int) = code == 3 || code == 45 || code == 48
}

fun Double.deg(): String = if (isNaN()) "--°" else "${roundToInt()}°"

fun Double.roundOr(fallback: String = "--"): String = if (isNaN()) fallback else roundToInt().toString()

private fun amPm(hour: Int) = if (hour < 12) "上午" else "下午"
private fun hour12(hour: Int) = (hour % 12).let { if (it == 0) 12 else it }

/** 例：下午3時 */
fun hourLabel(time: LocalDateTime): String = "${amPm(time.hour)}${hour12(time.hour)}時"

/** 例：下午5:43 */
fun timeLabel(time: LocalDateTime): String = "${amPm(time.hour)}${hour12(time.hour)}:${"%02d".format(time.minute)}"

fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "週一"
    DayOfWeek.TUESDAY -> "週二"
    DayOfWeek.WEDNESDAY -> "週三"
    DayOfWeek.THURSDAY -> "週四"
    DayOfWeek.FRIDAY -> "週五"
    DayOfWeek.SATURDAY -> "週六"
    DayOfWeek.SUNDAY -> "週日"
}

fun compassLabel(degrees: Int): String {
    val names = listOf("北", "北北東", "東北", "東北東", "東", "東南東", "東南", "南南東", "南", "南南西", "西南", "西南西", "西", "西北西", "西北", "北北西")
    val index = (((degrees % 360) + 360) % 360 / 22.5).roundToInt() % 16
    return names[index]
}

private val tempAnchors = listOf(
    -10.0 to Color(0xFF7D8CFF),
    0.0 to Color(0xFF5AC8FA),
    10.0 to Color(0xFF63D3A8),
    20.0 to Color(0xFFF7D046),
    28.0 to Color(0xFFFF9F0A),
    36.0 to Color(0xFFFF453A),
)

fun temperatureColor(t: Double): Color {
    if (t <= tempAnchors.first().first) return tempAnchors.first().second
    if (t >= tempAnchors.last().first) return tempAnchors.last().second
    val upper = tempAnchors.indexOfFirst { it.first >= t }
    val (t0, c0) = tempAnchors[upper - 1]
    val (t1, c1) = tempAnchors[upper]
    return lerp(c0, c1, ((t - t0) / (t1 - t0)).toFloat())
}

val PrecipBlue = Color(0xFF8ED1FC)
