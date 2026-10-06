package com.charlie.weather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.CommuteTrip
import com.charlie.weather.data.HourlyForecast
import java.time.LocalDate
import java.time.LocalDateTime

private val CommuteSecondary = Color.White.copy(alpha = 0.6f)
private val CommuteRain = Color(0xFF64D2FF)

/** 通勤時段預報：出發地（出發時）與目的地（約 1 小時後抵達時）的天氣。 */
@Composable
fun CommuteCard(trip: CommuteTrip, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    GlassCard("${dayWord(trip.departure, today)} ${timeLabel(trip.departure)} ${trip.leg.label}通勤", modifier) {
        CommuteRow("出發", trip.from, trip.fromHour)
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color.White.copy(alpha = 0.18f))
        CommuteRow("抵達", trip.to, trip.toHour)
        Spacer(Modifier.height(8.dp))
        Text(trip.advice, fontSize = 14.sp, color = Color.White, lineHeight = 19.sp)
    }
}

private fun dayWord(time: LocalDateTime, today: LocalDate) = when (time.toLocalDate()) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> weekdayLabel(time.dayOfWeek)
}

@Composable
private fun CommuteRow(caption: String, place: City, hour: HourlyForecast?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(place.displayName, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$caption · ${place.name}",
                fontSize = 12.sp,
                color = CommuteSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hour == null) {
            Text("暫無資料", fontSize = 14.sp, color = CommuteSecondary)
            return@Row
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            val pop = hour.precipitationProbability ?: 0
            if (pop >= 20) {
                Text("$pop%", fontSize = 13.sp, color = CommuteRain, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
            }
            Text(WeatherCodes.emoji(hour.weatherCode, hour.isDay), fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Text(hour.temperature.deg(), fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}
