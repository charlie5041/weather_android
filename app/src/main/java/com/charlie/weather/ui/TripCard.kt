package com.charlie.weather.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.FavoriteRoute
import com.charlie.weather.data.RouteReminder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

private val TripSecondary = Color.White.copy(alpha = 0.65f)
private val TripAccent = Color(0xFF64D2FF)
private val TripDivider = Color.White.copy(alpha = 0.18f)

/** 例：平日 08:00、週末 09:30、週一、週三 07:15 */
internal fun reminderLabel(reminder: RouteReminder): String {
    val days = when (reminder.days) {
        DayOfWeek.values().toSet() -> "每天"
        RouteReminder.WEEKDAYS -> "平日"
        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "週末"
        else -> reminder.days.sorted().joinToString("、") { weekdayLabel(it) }
    }
    return "$days %02d:%02d".format(reminder.hour, reminder.minute)
}

private fun dayWord(time: LocalDateTime, today: LocalDate) = when (time.toLocalDate()) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> weekdayLabel(time.dayOfWeek)
}

/**
 * 主頁的「出門」卡片：12 小時內要出發的常用路線，直接顯示沿路結論與雨況時間軸；
 * 其他常用路線一行一條，點了開啟沿路天氣。還沒有常用路線時說明怎麼加。
 */
@Composable
fun TripCard(
    favorites: List<FavoriteRoute>,
    upcoming: UpcomingTrip?,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    onOpen: (FavoriteRoute, LocalDateTime?) -> Unit,
    onNewRoute: () -> Unit,
) {
    GlassCard("出門", modifier) {
        if (upcoming != null) {
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(upcoming.route, upcoming.departure) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${modeEmoji(upcoming.route.mode)} ${upcoming.route.name}",
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${dayWord(upcoming.departure, today)} ${clock(upcoming.departure)} 出發", fontSize = 13.sp, color = TripSecondary)
                }
                val forecast = upcoming.forecast
                when {
                    forecast != null -> {
                        val verdict = forecast.verdict
                        Text(
                            verdict.title,
                            fontSize = 22.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(verdict.detail, fontSize = 13.sp, color = TripSecondary, lineHeight = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        RainTimeline(forecast, labelColor = TripSecondary)
                        // 最多兩則其他提醒，特報優先（hazards 已依重要性排序）
                        RouteHazards(forecast.hazards.take(2), Modifier.padding(top = 2.dp))
                    }
                    upcoming.loading -> Text("正在查沿路天氣…", fontSize = 14.sp, color = TripSecondary, modifier = Modifier.padding(top = 6.dp))
                    else -> Text("無法取得沿路天氣，點這裡開啟路線", fontSize = 14.sp, color = TripSecondary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        val others = favorites.filter { it.id != upcoming?.route?.id }.take(3)
        others.forEachIndexed { i, route ->
            if (i > 0 || upcoming != null) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = TripDivider)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(route, null) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${modeEmoji(route.mode)} ${route.name}",
                    fontSize = 15.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                route.reminder?.let { Text(reminderLabel(it), fontSize = 13.sp, color = TripSecondary) }
                Spacer(Modifier.width(6.dp))
                Text("›", fontSize = 18.sp, color = TripSecondary)
            }
        }

        if (favorites.isEmpty()) {
            Text(
                "查詢路線後按 ☆ 存成常用路線並設定出發時間，這裡就會顯示下一趟路上的天氣。",
                fontSize = 14.sp,
                color = Color.White,
                lineHeight = 19.sp,
            )
        }
        if (favorites.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = TripDivider)
        Text(
            if (favorites.isEmpty()) "查沿路天氣 ›" else "查其他路線 ›",
            fontSize = 14.sp,
            color = TripAccent,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = if (favorites.isEmpty()) 8.dp else 0.dp)
                .clickable(role = Role.Button, onClick = onNewRoute),
        )
    }
}
