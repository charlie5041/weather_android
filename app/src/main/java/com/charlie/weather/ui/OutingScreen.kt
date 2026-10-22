package com.charlie.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.Commute
import com.charlie.weather.data.FavoriteRoute
import com.charlie.weather.data.RouteData
import com.charlie.weather.data.RouteHazard
import com.charlie.weather.data.RouteReminder
import com.charlie.weather.data.TravelMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

private val OutingSecondary = Color(0xFFA0A0A8)
private val OutingDivider = Color(0xFF2C2C2E)
private val OutingCamera = Color(0xFFFF9F0A)
private val OutingAccent = Color(0xFF64D2FF)

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
 * 「出門」分頁：看路線上的天氣。查新路線、12 小時內下一趟常用路線的沿路結論、其他常用路線。
 * 通勤就是「上班」「下班」兩條常用路線；有住家與公司但還沒建立時，提供一鍵建立。
 * 搜尋框下面是「直接出發」（不設目的地的行車提醒）；進行中的狀態在分頁列上方的進行中橫條。
 */
@Composable
fun OutingScreen(
    favorites: List<FavoriteRoute>,
    upcoming: UpcomingTrip?,
    /** 自訂地點中的住家與公司（或學校） */
    commutePlaces: Pair<City, City>?,
    /** 騎乘中或行車提醒進行中（狀態在分頁列上方的進行中橫條） */
    rideActive: Boolean,
    onNewRoute: () -> Unit,
    onOpen: (FavoriteRoute, LocalDateTime?) -> Unit,
    onCreateCommute: (Pair<City, City>) -> Unit,
    onStartRide: (RouteData) -> Unit,
    onOpenSettings: () -> Unit,
    /** 直接出發：不設目的地的行車提醒 */
    onStartDrive: () -> Unit = {},
    recordJourneys: Boolean = true,
    speedCameras: Boolean = true,
    today: LocalDate = LocalDate.now(),
) {
    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("出門", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "設定", tint = Color.White)
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelColor)
                    .clickable(role = Role.Button, onClick = onNewRoute)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = OutingSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("要去哪裡？查沿路天氣", color = OutingSecondary, fontSize = 16.sp)
            }
        }
        // 要出門時先決定要不要設目的地：查路線（上面）或直接出發
        if (!rideActive) {
            item { DriveCard(onStartDrive, recordJourneys, speedCameras) }
        }
        upcoming?.let { trip ->
            item { NextTripCard(trip, today, rideActive, onOpen = { onOpen(trip.route, trip.departure) }, onStartRide = onStartRide) }
        }
        val newCommute = commutePlaces?.takeUnless { (home, work) -> Commute.hasRoutes(favorites, home, work) }
        item {
            FavoritesCard(
                favorites.filter { it.id != upcoming?.route?.id },
                upcoming != null,
                onOpen,
                newCommute = newCommute,
                onCreateCommute = onCreateCommute,
            )
        }
    }
}

@Composable
internal fun OutingPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PanelColor).padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
internal fun PillButton(text: String, background: Color, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** 12 小時內要出發的常用路線：沿路結論、雨況時間軸、最多兩則提醒，可直接開始騎乘。 */
@Composable
private fun NextTripCard(
    trip: UpcomingTrip,
    today: LocalDate,
    rideActive: Boolean,
    onOpen: () -> Unit,
    onStartRide: (RouteData) -> Unit,
) {
    OutingPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下一趟", fontSize = 13.sp, color = OutingSecondary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${dayWord(trip.departure, today)} ${clock(trip.departure)} 出發・${trip.route.mode.label}", fontSize = 13.sp, color = OutingSecondary)
        }
        Text(
            trip.route.name,
            fontSize = 15.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val forecast = trip.forecast
        when {
            forecast != null -> {
                val verdict = forecast.verdict
                Text(verdict.title, fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold, lineHeight = 30.sp, modifier = Modifier.padding(top = 4.dp))
                Text(verdict.detail, fontSize = 14.sp, color = OutingSecondary, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                RainTimeline(forecast, labelColor = OutingSecondary)
                // 最多兩則其他提醒，特報優先（hazards 已依重要性排序）
                RouteHazards(forecast.hazards.filterNot { it == RouteHazard.NoCameras }.take(2), Modifier.padding(top = 2.dp))
            }
            trip.loading -> Text("正在查沿路天氣…", fontSize = 14.sp, color = OutingSecondary, modifier = Modifier.padding(top = 6.dp))
            else -> Text("無法取得沿路天氣，點「看路線」重新查詢", fontSize = 14.sp, color = OutingSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("看路線", OutingDivider, Color.White, Modifier.weight(1f), onOpen)
            if (forecast != null && !rideActive) {
                val riding = trip.route.mode == TravelMode.SCOOTER || trip.route.mode == TravelMode.BIKE
                PillButton(if (riding) "開始騎乘" else "開始出發", Color.White, Color.Black, Modifier.weight(1f)) { onStartRide(forecast.data) }
            }
        }
    }
}

/**
 * 常用路線一行一條，點了開啟沿路天氣；還沒有常用路線時說明怎麼加。
 * [newCommute] 不為 null 時（有住家與公司、還沒有上班路線）最上面提供一鍵建立上下班路線。
 */
@Composable
private fun FavoritesCard(
    routes: List<FavoriteRoute>,
    hasUpcoming: Boolean,
    onOpen: (FavoriteRoute, LocalDateTime?) -> Unit,
    newCommute: Pair<City, City>? = null,
    onCreateCommute: (Pair<City, City>) -> Unit = {},
) {
    OutingPanel {
        Text("常用路線", fontSize = 13.sp, color = OutingSecondary, fontWeight = FontWeight.Medium)
        newCommute?.let { places ->
            val (home, work) = places
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { onCreateCommute(places) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("＋ 建立上下班路線", fontSize = 16.sp, color = OutingAccent)
                    Text(
                        "${home.displayName} ⇄ ${work.displayName}，平日 ${Commute.MORNING_HOUR}:00 上班、${Commute.EVENING_HOUR}:00 下班出發",
                        fontSize = 13.sp,
                        color = OutingSecondary,
                    )
                }
            }
            if (routes.isNotEmpty()) HorizontalDivider(color = OutingDivider)
        }
        if (routes.isEmpty() && newCommute == null) {
            Text(
                if (hasUpcoming) {
                    "查詢路線後按 ☆ 可以再存其他常用路線。"
                } else {
                    "查詢路線後按 ☆ 存成常用路線並設定出發時間，這裡就會顯示下一趟路上的天氣，出發前也會通知。"
                },
                fontSize = 14.sp,
                color = Color.White,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        routes.forEachIndexed { i, route ->
            if (i > 0) HorizontalDivider(color = OutingDivider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { onOpen(route, null) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(route.name, fontSize = 16.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(route.reminder?.let { reminderLabel(it) }, route.mode.label).joinToString("・"),
                        fontSize = 13.sp,
                        color = OutingSecondary,
                    )
                }
                Text("›", fontSize = 20.sp, color = OutingSecondary)
            }
        }
    }
}

/** 直接出發（不設目的地的行車提醒）：依行進方向提醒前方的測速照相並記錄足跡。 */
@Composable
internal fun DriveCard(onStart: () -> Unit, recordJourneys: Boolean, speedCameras: Boolean = true) {
    OutingPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("直接出發", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        !speedCameras && recordJourneys -> "測速照相提醒已關閉（設定 → 騎乘時），只記錄足跡"
                        !speedCameras -> "測速照相提醒已關閉，可在設定 → 騎乘時開啟"
                        recordJourneys -> "不設目的地，提醒前方的測速照相並記錄足跡"
                        else -> "不設目的地，提醒前方的測速照相"
                    },
                    fontSize = 13.sp,
                    color = OutingSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "開始",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(OutingCamera)
                    .clickable(role = Role.Button, onClick = onStart)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Text(
            "停留超過 20 分鐘自動結束；通知欄的快速設定也可以加入「測速提醒」方塊。",
            fontSize = 12.sp,
            color = OutingSecondary,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
