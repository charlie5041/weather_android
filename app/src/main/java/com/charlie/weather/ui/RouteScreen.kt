package com.charlie.weather.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.BuildConfig
import com.charlie.weather.data.AddressResult
import com.charlie.weather.data.City
import com.charlie.weather.data.CwaParser
import com.charlie.weather.data.FavoriteRoute
import com.charlie.weather.data.DepartureOption
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.MapTile
import com.charlie.weather.data.MapTileCache
import com.charlie.weather.data.MapViewport
import com.charlie.weather.data.RainLevel
import com.charlie.weather.data.RainNowcast
import com.charlie.weather.data.RouteReminder
import com.charlie.weather.data.RouteHazard
import com.charlie.weather.data.WebMercator
import com.charlie.weather.data.RouteData
import com.charlie.weather.data.RouteForecast
import com.charlie.weather.data.RoutePlanner
import com.charlie.weather.data.RouteSource
import com.charlie.weather.data.RouteStop
import com.charlie.weather.data.StopGroup
import com.charlie.weather.data.TravelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal val RouteDry = Color(0xFF30D158)
internal val RouteMaybe = Color(0xFFFFD60A)
internal val RouteWet = Color(0xFF0A84FF)
internal val RouteHeavy = Color(0xFFBF5AF2)
private val MapPlaceholder = Color(0xFFE8E6E1)

private enum class Endpoint { FROM, TO }

/**
 * 起點、終點與出發時間；從通勤卡片開啟時會帶入住家、公司與通勤時間，
 * 從 Google 地圖分享路線時會帶入途經點與交通方式。
 */
data class RouteRequest(
    val from: City?,
    val to: City?,
    val departure: LocalDateTime? = null,
    val mode: TravelMode? = null,
    val via: List<City> = emptyList(),
)

/**
 * 沿路天氣：像地圖 App 一樣輸入起點與終點，沿路線每幾公里取一點，
 * 依騎到該點的時間查逐時預報，看路上會不會遇到雨。
 */
@Composable
fun RouteScreen(
    request: RouteRequest,
    places: List<City>,
    /** 最近搜尋選用的地點 */
    recentPlaces: List<City> = emptyList(),
    onRememberPlace: (City) -> Unit = {},
    onClearRecentPlaces: () -> Unit = {},
    onSearch: suspend (String) -> List<AddressResult>,
    onUseCurrentLocation: suspend () -> AddressResult?,
    /** 建議路線與替代路線（第一條是建議路線） */
    onLoad: suspend (City, City, List<City>, TravelMode, LocalDateTime) -> List<RouteData>,
    /** 行車時間含路況（Google）：改出發時間要重新查詢 */
    trafficAware: Boolean = false,
    favorites: List<FavoriteRoute> = emptyList(),
    onSaveFavorite: (FavoriteRoute) -> Unit = {},
    onRemoveFavorite: (String) -> Unit = {},
    /** 騎乘中模式是否進行中 */
    rideActive: Boolean = false,
    onStartRide: ((RouteData) -> Unit)? = null,
    onStopRide: () -> Unit = {},
    onClose: () -> Unit,
) {
    val openedAt = remember { LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) }
    var from by remember { mutableStateOf(request.from) }
    var to by remember { mutableStateOf(request.to) }
    var via by remember { mutableStateOf(request.via) }
    var mode by remember { mutableStateOf(request.mode ?: TravelMode.SCOOTER) }
    var departure by remember { mutableStateOf(request.departure ?: openedAt) }
    var editing by remember { mutableStateOf<Endpoint?>(if (request.from != null && request.to == null) Endpoint.TO else null) }
    var routes by remember { mutableStateOf<List<RouteData>?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    // 「抵達時間」模式：使用者指定幾點要到，出發時間依各路線的行車時間往回推
    var arriveBy by remember { mutableStateOf<LocalDateTime?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickTime by remember { mutableStateOf(false) }
    // 比較條從這個時間起每 30 分鐘一欄（通勤時間或自訂時間）；沒有時從現在起
    var anchor by remember { mutableStateOf(request.departure?.takeIf { it != openedAt }) }
    var editFavorite by remember { mutableStateOf(false) }
    // 按「重試」時加一，重新查詢
    var reload by remember { mutableIntStateOf(0) }
    val favorite = favorites.firstOrNull { f -> from?.let { a -> to?.let { b -> f.sameRoute(a, b, via) } } == true }

    fun departureFor(route: RouteData?): LocalDateTime =
        arriveBy?.let { a -> route?.let { a.minusSeconds((it.path.durationMinutes * 60).roundToLong()) } ?: a.minusMinutes(30) } ?: departure

    LaunchedEffect(from, to, via, mode, if (trafficAware) arriveBy ?: departure else null, reload) {
        val a = from
        val b = to
        val previous = routes?.getOrNull(selected)
        routes = null
        error = null
        if (a == null || b == null) return@LaunchedEffect
        loading = true
        try {
            routes = onLoad(a, b, via, mode, departureFor(previous))
            selected = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "無法取得沿途天氣，請檢查網路連線"
        }
        loading = false
    }
    val data = routes?.getOrNull(selected)
    val effectiveDeparture = departureFor(data)
    val forecast = remember(data, effectiveDeparture) { data?.let { RoutePlanner.evaluate(it, effectiveDeparture) } }

    val endpoint = editing
    if (endpoint != null) {
        EndpointSearch(
            title = if (endpoint == Endpoint.FROM) "選擇起點" else "選擇終點",
            places = places,
            recent = recentPlaces,
            onRemember = onRememberPlace,
            onClearRecent = onClearRecentPlaces,
            onSearch = onSearch,
            onUseCurrentLocation = onUseCurrentLocation,
            onPick = { city ->
                if (endpoint == Endpoint.FROM) from = city else to = city
                editing = if (endpoint == Endpoint.FROM && to == null) Endpoint.TO else null
            },
            onClose = { editing = null },
        )
    } else {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp)) {
                    TextButton(onClick = onClose) { Text("完成", color = Accent, fontSize = 17.sp) }
                }
                Text(
                    "沿路天氣",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                    if (from != null && to != null) {
                        TextButton(onClick = { editFavorite = true }) {
                            Text(if (favorite != null) "★ 常用" else "☆ 常用", color = Accent, fontSize = 16.sp)
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            EndpointRow(RouteDry, "起點", from) { editing = Endpoint.FROM }
                            HorizontalDivider(Modifier.padding(start = 26.dp), color = Color.White.copy(alpha = 0.1f))
                            if (via.isNotEmpty()) {
                                ViaRow(via, onClear = { via = emptyList() })
                                HorizontalDivider(Modifier.padding(start = 26.dp), color = Color.White.copy(alpha = 0.1f))
                            }
                            EndpointRow(Color(0xFFFF453A), "終點", to) { editing = Endpoint.TO }
                        }
                        Text(
                            "⇅",
                            color = Accent,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    val a = from
                                    from = to
                                    to = a
                                    via = via.reversed()
                                }
                                .padding(10.dp),
                        )
                    }
                }

                ChipRow(TravelMode.entries.map { modeEmoji(it) + " " + it.label }, TravelMode.entries.indexOf(mode)) {
                    mode = TravelMode.entries[it]
                }

                ChipRow(listOf("出發時間", "抵達時間"), if (arriveBy == null) 0 else 1) { i ->
                    if (i == 1 && arriveBy == null) {
                        // 改用目前這趟的抵達時間（進位到 5 分鐘）
                        val arrival = forecast?.arrival ?: openedAt.plusHours(1)
                        arriveBy = arrival.truncatedTo(ChronoUnit.MINUTES).plusMinutes(((5 - arrival.minute % 5) % 5).toLong())
                    } else if (i == 0 && arriveBy != null) {
                        departure = effectiveDeparture.truncatedTo(ChronoUnit.MINUTES)
                        anchor = departure
                        arriveBy = null
                    }
                }

                val arrival = arriveBy
                if (arrival == null) {
                    val departures = remember(anchor) {
                        val times = anchor?.let { a -> listOf(0L, 30L, 60L, 90L, 120L).map { a.plusMinutes(it) } }
                            ?: listOf(30L, 60L, 90L, 120L, 180L).map { openedAt.plusMinutes(it) }
                        (listOf(openedAt) + times).distinct().sorted()
                    }
                    val options = remember(data, departures) { data?.let { RoutePlanner.compare(it, departures) } }
                    DepartureStrip(
                        departures,
                        options,
                        selected = departure,
                        now = openedAt,
                        onSelect = { departure = it },
                        onCustom = { pickTime = true },
                    )
                } else {
                    // 比較抵達時間：前 90 分鐘到後 30 分鐘；出發時間已經過去的不列
                    val seconds = data?.let { (it.path.durationMinutes * 60).roundToLong() } ?: 0L
                    val arrivals = listOf(-90L, -60L, -30L, 0L, 30L).map { arrival.plusMinutes(it) }
                        .filter { it == arrival || !it.minusSeconds(seconds).isBefore(openedAt.minusMinutes(5)) }
                    val options = remember(data, arrivals) { data?.let { d -> RoutePlanner.compare(d, arrivals.map { it.minusSeconds(seconds) }) } }
                    DepartureStrip(
                        arrivals,
                        options,
                        selected = arrival,
                        now = openedAt,
                        onSelect = { arriveBy = it },
                        onCustom = { pickTime = true },
                        suffix = " 到",
                    )
                }

                when {
                    from == null || to == null -> {
                        Hint("輸入起點與終點，查看路上每一段經過時的天氣。")
                        if (favorites.isNotEmpty()) {
                            FavoriteList(favorites) { f ->
                                from = f.from
                                to = f.to
                                via = f.via
                                mode = f.mode
                            }
                        }
                    }
                    loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    error != null -> Column {
                        Hint(error!!)
                        TextButton(onClick = { reload++ }) { Text("重試", color = Accent, fontSize = 16.sp) }
                    }
                    forecast != null -> {
                        val all = routes.orEmpty()
                        if (all.size > 1) RouteChoices(all, selected, ::departureFor) { selected = it }
                        RouteResult(
                            forecast,
                            ride = onStartRide?.let { start ->
                                RideControl(rideActive, onStart = { start(forecast.data) }, onStop = onStopRide)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val a = from
    val b = to
    if (editFavorite && a != null && b != null) {
        FavoriteDialog(
            existing = favorite,
            defaultName = "${a.displayName} → ${b.displayName}",
            defaultTime = departure.takeIf { it != openedAt },
            onDismiss = { editFavorite = false },
            onSave = { name, reminder ->
                editFavorite = false
                onSaveFavorite(
                    FavoriteRoute(
                        id = favorite?.id ?: UUID.randomUUID().toString(),
                        name = name,
                        from = a,
                        to = b,
                        mode = mode,
                        via = via,
                        reminder = reminder,
                    ),
                )
            },
            onRemove = favorite?.let { f ->
                {
                    editFavorite = false
                    onRemoveFavorite(f.id)
                }
            },
        )
    }

    if (pickTime) {
        val arriving = arriveBy
        DateTimeDialog(
            title = if (arriving != null) "抵達時間" else "出發時間",
            initial = arriving ?: departure,
            today = openedAt.toLocalDate(),
            onDismiss = { pickTime = false },
        ) { picked ->
            pickTime = false
            // 今天已經過了的時間就當作明天
            val time = if (picked.toLocalDate() == openedAt.toLocalDate() && picked.isBefore(openedAt.minusMinutes(5))) picked.plusDays(1) else picked
            if (arriving != null) {
                arriveBy = time
            } else {
                departure = time
                anchor = time
            }
        }
    }
}

internal fun modeEmoji(mode: TravelMode) = when (mode) {
    TravelMode.SCOOTER -> "🛵"
    TravelMode.CAR -> "🚗"
    TravelMode.BIKE -> "🚲"
    TravelMode.WALK -> "🚶"
}

internal fun clock(time: LocalDateTime) = "%02d:%02d".format(time.hour, time.minute)

internal fun rainColor(stop: RouteStop): Color = rainLevelColor(stop.rainLevel)

internal fun rainLevelColor(level: RainLevel): Color = when (level) {
    RainLevel.HEAVY -> RouteHeavy
    RainLevel.WET -> RouteWet
    RainLevel.MAYBE -> RouteMaybe
    RainLevel.DRY -> RouteDry
    RainLevel.UNKNOWN -> Color.Gray
}

@Composable
private fun EndpointRow(dot: Color, caption: String, city: City?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(14.dp))
        if (city == null) {
            Text("選擇$caption", color = Color.Gray, fontSize = 16.sp)
        } else {
            Column {
                Text(city.displayName, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = city.address ?: city.subtitle
                if (sub.isNotBlank() && sub != city.displayName) {
                    Text(sub, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 途經點（從 Google 地圖分享的路線）；可以移除，改走起點到終點的建議路線。 */
@Composable
private fun ViaRow(via: List<City>, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Gray))
        Spacer(Modifier.width(14.dp))
        Text(
            "途經 " + via.joinToString("、") { it.displayName },
            color = Color.Gray,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("移除", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 8.dp))
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, option ->
            val active = i == selected
            Text(
                option,
                color = if (active) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White else Color(0xFF2C2C2E))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
}

@Composable
fun RouteResult(
    forecast: RouteForecast,
    /** 開始／結束騎乘中模式；null 時不顯示按鈕 */
    ride: RideControl? = null,
    /** 有 Google 金鑰時用 Google 地圖；截圖測試固定用圖磚地圖 */
    googleMap: Boolean = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank(),
) {
    val context = LocalContext.current
    val data = forecast.data
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            val verdict = forecast.verdict
            Text(verdict.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
            Text(
                verdict.detail,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(14.dp))
            RainTimeline(forecast)
            RouteHazards(forecast.hazards, Modifier.padding(top = 4.dp))
            // 不是今天出發時標出日期，免得只看時間誤會
            val day = forecast.departure.toLocalDate()
            val date = if (day == LocalDate.now()) "" else "${day.monthValue}/${day.dayOfMonth}（${weekdayLabel(day.dayOfWeek).takeLast(1)}）出發 · "
            Text(
                date + "%.1f 公里 · 約 %d 分鐘%s".format(
                    Locale.US,
                    data.path.distanceKm,
                    data.path.durationMinutes.toInt().coerceAtLeast(1),
                    when (data.path.source) {
                        RouteSource.GOOGLE -> "（含路況）"
                        RouteSource.ESTIMATE -> "（直線估計）"
                        RouteSource.OSM -> "（估計，未含即時路況）"
                    },
                ),
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        ride?.let { control ->
            val riding = data.mode == TravelMode.SCOOTER || data.mode == TravelMode.BIKE
            Text(
                when {
                    control.active -> "■ 結束沿途提醒"
                    riding -> "▶ 開始騎乘：前方下雨時通知"
                    else -> "▶ 開始出發：前方下雨時通知"
                },
                color = if (control.active) Color.White else Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (control.active) Color(0xFF3A3A3C) else Color.White)
                    .clickable(role = Role.Button, onClick = if (control.active) control.onStop else control.onStart)
                    .padding(vertical = 13.dp),
            )
        }

        Panel {
            RouteMap(
                forecast,
                onOpenMaps = { openInGoogleMaps(context, data) },
                googleMap = googleMap,
                modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(RouteDry to "不太會下", RouteMaybe to "可能", RouteWet to "會下雨", RouteHeavy to "大雨／正在下").forEach { (c, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Text(" $label", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
            visibleNowcast(forecast)?.let { radar ->
                Text(
                    "藍色區塊：雷達預估未來 1 小時雨量（${clock(radar.issued)} 資料）",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Panel {
            // 預設把連續經過同一個鄉鎮的點合併成一行；可以展開看每個點
            val groups = remember(forecast) { RoutePlanner.group(forecast.stops) }
            var expanded by remember(forecast) { mutableStateOf(false) }
            val rows = if (expanded || groups.size == forecast.stops.size) forecast.stops.map { StopGroup(listOf(it)) } else groups
            rows.forEachIndexed { i, group ->
                StopRow(
                    group,
                    title = when {
                        i == 0 -> data.from.displayName
                        i == rows.lastIndex -> data.to.displayName
                        else -> group.first.place ?: "途中"
                    },
                    first = i == 0,
                    last = i == rows.lastIndex,
                )
            }
            if (groups.size < forecast.stops.size) {
                Text(
                    if (expanded) "收合 ⌃" else "顯示全部 ${forecast.stops.size} 個點 ⌄",
                    color = Accent,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(top = 8.dp, bottom = 4.dp),
                )
            }
        }

        Text(
            "在 Google 地圖開啟這條路線 ›",
            color = Accent,
            fontSize = 15.sp,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { openInGoogleMaps(context, data) },
        )
        // 只留 OpenStreetMap 授權要求的出處標示
        if (data.path.source == RouteSource.OSM || !googleMap) {
            Text(
                "© OpenStreetMap 貢獻者",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** 還沒選起終點時列出常用路線，點了直接帶入 */
@Composable
private fun FavoriteList(favorites: List<FavoriteRoute>, onPick: (FavoriteRoute) -> Unit) {
    Text("常用路線", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
    Panel {
        favorites.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(f) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${modeEmoji(f.mode)} ${f.name}", color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                f.reminder?.let { Text(reminderLabel(it), color = Color.Gray, fontSize = 13.sp) }
            }
        }
    }
}

/** 存成常用路線：名稱與出發前提醒（每週哪幾天、幾點出發）。 */
@Composable
private fun FavoriteDialog(
    existing: FavoriteRoute?,
    defaultName: String,
    defaultTime: LocalDateTime?,
    onDismiss: () -> Unit,
    onSave: (String, RouteReminder?) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: defaultName) }
    var remind by remember { mutableStateOf(existing == null || existing.reminder != null) }
    var days by remember { mutableStateOf(existing?.reminder?.days ?: RouteReminder.WEEKDAYS) }
    var hour by remember { mutableStateOf(existing?.reminder?.hour ?: defaultTime?.hour ?: 8) }
    var minute by remember { mutableStateOf(existing?.reminder?.minute ?: defaultTime?.minute ?: 0) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text(if (existing == null) "加入常用路線" else "常用路線", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DarkTextField(value = name, onValueChange = { name = it }, placeholder = defaultName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("出發前提醒", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = remind,
                        onCheckedChange = { remind = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = RouteDry, checkedThumbColor = Color.White),
                    )
                }
                if (remind) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { pickTime = true }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("出發時間", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("%02d:%02d ›".format(hour, minute), color = Accent, fontSize = 16.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.values().forEach { day ->
                            val on = day in days
                            Text(
                                weekdayLabel(day).takeLast(1),
                                color = if (on) Color.Black else Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (on) Color.White else Color(0xFF2C2C2E))
                                    .clickable(role = Role.Checkbox) { days = if (on) days - day else days + day }
                                    .padding(top = 6.dp),
                            )
                        }
                    }
                    Text("出發前 1.5 小時內會通知沿路天氣", color = Color.Gray, fontSize = 13.sp)
                }
                if (onRemove != null) {
                    Text(
                        "移除常用路線",
                        color = Color(0xFFFF453A),
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onRemove).padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !remind || days.isNotEmpty(),
                onClick = {
                    onSave(name.trim().ifEmpty { defaultName }, if (remind && days.isNotEmpty()) RouteReminder(days, hour, minute) else null)
                },
            ) { Text("儲存", color = if (!remind || days.isNotEmpty()) Accent else Color.Gray) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Accent) } },
    )
    if (pickTime) {
        DepartureTimeDialog(LocalDate.now().atTime(hour, minute), onDismiss = { pickTime = false }) { h, m ->
            hour = h
            minute = m
            pickTime = false
        }
    }
}

/**
 * 比較用的文字：會淋雨多久。沒有會下雨的路段時，說「可能有雨」或「不太會淋雨」。
 * [short] 用在出發時間比較條的窄欄。
 */
internal fun wetLabel(option: DepartureOption, short: Boolean): String = when {
    option.wetMinutes > 0 -> if (short) "雨 ${option.wetMinutes} 分" else "淋雨約 ${durationLabel(option.wetMinutes.toDouble())}"
    option.level == RainLevel.MAYBE -> if (short) "可能" else "可能有雨（${option.risk}%）"
    else -> if (short) "不會下" else "不太會淋雨"
}

internal fun wetColor(option: DepartureOption): Color = when {
    option.wetMinutes > 0 -> rainLevelColor(maxOf(option.level, RainLevel.WET))
    option.level == RainLevel.MAYBE -> RouteMaybe
    else -> Color.Gray
}

/** 柱的顏色與雨況時間軸一致（乾燥是綠色） */
internal fun wetBarColor(option: DepartureOption): Color =
    if (option.wetMinutes == 0 && option.level != RainLevel.MAYBE) RouteDry else wetColor(option)

/** 柱高：淋雨的時間佔整趟越多越高 */
internal fun wetBar(option: DepartureOption): Float = when {
    option.wetMinutes > 0 -> 0.3f + 0.7f * option.wetFraction.toFloat()
    option.level == RainLevel.MAYBE -> 0.25f
    else -> 0.15f
}

internal fun durationLabel(minutes: Double): String {
    val m = minutes.roundToInt().coerceAtLeast(1)
    return if (m < 60) "$m 分" else if (m % 60 == 0) "${m / 60} 小時" else "${m / 60} 小時 ${m % 60} 分"
}

/**
 * 建議路線與替代路線：每條的行車時間、距離與沿途最高降雨機率；
 * 標出最快的一條，以及明顯比較不會淋雨（低 20% 以上）的一條。
 */
@Composable
internal fun RouteChoices(
    routes: List<RouteData>,
    selected: Int,
    departureFor: (RouteData) -> LocalDateTime,
    onSelect: (Int) -> Unit,
) {
    val options = routes.map { RoutePlanner.compare(it, listOf(departureFor(it))).first() }
    val fastest = routes.indices.minByOrNull { routes[it].path.durationMinutes }
    // 淋雨最少、而且比其他至少一條明顯少淋雨的路線
    val driest = options.indices.filter { options[it].level != RainLevel.UNKNOWN }
        .minWithOrNull(compareBy<Int> { options[it].wetMinutes }.thenBy { options[it].risk })
        ?.takeIf { i -> options.indices.any { j -> j != i && options[j].level != RainLevel.UNKNOWN && RoutePlanner.clearlyDrier(options[i], options[j]) } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        routes.forEachIndexed { i, route ->
            val active = i == selected
            val option = options[i].takeIf { it.level != RainLevel.UNKNOWN }
            Column(
                Modifier
                    .width(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Color.White.copy(alpha = 0.18f) else PanelColor)
                    .then(if (active) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(role = Role.Button) { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    route.path.description ?: if (i == 0) "建議路線" else "替代路線 $i",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 時間與距離分兩行，長途時才不會被截掉
                Text(durationLabel(route.path.durationMinutes), color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                Text("%.1f 公里".format(Locale.US, route.path.distanceKm), color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.width(10.dp).height(14.dp), contentAlignment = Alignment.BottomCenter) {
                        if (option != null) {
                            Box(
                                Modifier.fillMaxWidth().fillMaxHeight(wetBar(option))
                                    .clip(RoundedCornerShape(2.dp)).background(wetBarColor(option)),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        option?.let { wetLabel(it, short = false) } ?: "暫無預報",
                        color = option?.let(::wetColor) ?: Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                val tags = listOfNotNull("最快".takeIf { i == fastest }, "較不會淋雨".takeIf { i == driest })
                Text(tags.joinToString(" · ").ifEmpty { " " }, color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** 選日期（今天起 7 天，逐時預報的範圍）與時間 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(
    title: String,
    initial: LocalDateTime,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val dates = (0L until 7L).map { today.plusDays(it) }
    var date by remember { mutableStateOf(initial.toLocalDate().takeIf { it in dates } ?: today) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text(title, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dates.forEachIndexed { i, d ->
                        val on = d == date
                        Text(
                            when (i) {
                                0 -> "今天"
                                1 -> "明天"
                                else -> "${weekdayLabel(d.dayOfWeek).takeLast(1)} ${d.monthValue}/${d.dayOfMonth}"
                            },
                            color = if (on) Color.Black else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Color.White else Color(0xFF2C2C2E))
                                .clickable(role = Role.Button) { date = d }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                TimePicker(state = state)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(date.atTime(state.hour, state.minute)) }) { Text("確定", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Accent) } },
    )
}

private fun stopWhere(stop: RouteStop) = stop.place ?: "%.0f 公里處".format(Locale.US, stop.point.distanceKm)

private fun uvLevel(index: Double) = when {
    index >= 11 -> "危險級"
    index >= 8 -> "過量級"
    index >= 6 -> "高量級"
    else -> "中量級"
}

/** 一則沿途提醒的圖示與文字（依使用者的溫度、風速單位） */
internal fun hazardLine(hazard: RouteHazard): Pair<String, String> = when (hazard) {
    is RouteHazard.Alert -> "⚠️" to hazard.title + hazard.counties.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "：").orEmpty()
    is RouteHazard.Road -> "🚧" to hazard.event.let { e ->
        val text = listOfNotNull(e.location, e.description.takeIf { it != e.title }).joinToString(" ").ifEmpty { e.description }
        "${e.title}：" + if (text.length > 60) text.take(59) + "…" else text
    }
    is RouteHazard.Wind -> "💨" to "${stopWhere(hazard.stop)}一帶陣風 ${windText(hazard.gustKmh)}，注意側風"
    is RouteHazard.Sunset -> "🌇" to "${clock(hazard.time)} 日落，${stopWhere(hazard.stop)}之後天黑"
    is RouteHazard.Cold -> "🥶" to if (hazard.riding) {
        "騎乘體感約 ${hazard.feelsLike.deg()}（氣溫 ${hazard.temperature.deg()}），注意保暖"
    } else {
        "體感 ${hazard.feelsLike.deg()}，注意保暖"
    }
    is RouteHazard.Heat -> "🥵" to "體感 ${hazard.feelsLike.deg()}，注意防曬與補水"
    is RouteHazard.Uv -> "☀️" to "紫外線 ${hazard.index.roundToInt()}（${uvLevel(hazard.index)}），注意防曬"
}

/** 雨以外的沿途提醒，一則一行；特報用橘色 */
@Composable
internal fun RouteHazards(hazards: List<RouteHazard>, modifier: Modifier = Modifier) {
    if (hazards.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hazards.forEach { hazard ->
            val (icon, text) = hazardLine(hazard)
            Row {
                Text(icon, fontSize = 14.sp, modifier = Modifier.width(24.dp))
                Text(
                    text,
                    color = if (hazard is RouteHazard.Alert) Color(0xFFFF9F0A) else Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (hazard is RouteHazard.Alert) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun stripLabel(time: LocalDateTime, now: LocalDateTime) = when {
    time == now -> "現在"
    time.toLocalDate() == now.toLocalDate() -> clock(time)
    time.toLocalDate() == now.toLocalDate().plusDays(1) -> "明 ${clock(time)}"
    else -> "${weekdayLabel(time.dayOfWeek).takeLast(1)} ${clock(time)}"
}

/**
 * 出發時間比較條：每一欄是一個出發時間與該時間出發時沿途最高的降雨機率，
 * 柱高與顏色同雨況時間軸；明顯比較不會淋雨的一欄標「建議」。點一欄就改用該時間出發。
 */
@Composable
internal fun DepartureStrip(
    /** 每一欄顯示的時間（出發時間，或「抵達時間」模式的抵達時間） */
    departures: List<LocalDateTime>,
    /** 與 [departures] 一一對應，在該時間出發（或抵達）時的沿途雨況 */
    options: List<DepartureOption>?,
    selected: LocalDateTime,
    now: LocalDateTime,
    onSelect: (LocalDateTime) -> Unit,
    onCustom: () -> Unit,
    /** 加在時間後面的字（抵達時間模式為「到」） */
    suffix: String = "",
) {
    val current = options?.getOrNull(departures.indexOf(selected))
    val best = options?.let { o -> current?.let { RoutePlanner.recommended(o, it.departure) } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        departures.forEachIndexed { i, time ->
            val option = options?.getOrNull(i)?.takeIf { it.level != RainLevel.UNKNOWN }
            val active = time == selected
            StripCell(
                active = active,
                onClick = { onSelect(time) },
                label = if (time == now) "現在" else stripLabel(time, now) + suffix,
                bottom = option?.let { wetLabel(it, short = true) } ?: "–",
                bottomColor = option?.let(::wetColor) ?: Color.Gray,
                recommended = best != null && best.departure == options?.getOrNull(i)?.departure,
            ) {
                if (option != null) {
                    Box(
                        Modifier.width(22.dp).fillMaxHeight(wetBar(option))
                            .clip(RoundedCornerShape(3.dp)).background(wetBarColor(option)),
                    )
                }
            }
        }
        StripCell(active = false, onClick = onCustom, label = "自訂", bottom = "時間", bottomColor = Color.Gray, recommended = false) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp).align(Alignment.Center))
        }
    }
}

@Composable
private fun StripCell(
    active: Boolean,
    onClick: () -> Unit,
    label: String,
    bottom: String,
    bottomColor: Color,
    recommended: Boolean,
    graphic: @Composable BoxScope.() -> Unit,
) {
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color.White.copy(alpha = 0.18f) else PanelColor)
            .then(if (active) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        Box(Modifier.padding(vertical = 6.dp).height(22.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter, content = graphic)
        Text(bottom, color = bottomColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("建議", color = if (recommended) Accent else Color.Transparent, fontSize = 10.sp)
    }
}

/** 路線結果上的騎乘中模式按鈕 */
class RideControl(val active: Boolean, val onStart: () -> Unit, val onStop: () -> Unit)

/** 柱高：不靠顏色也看得出雨勢 */
private fun RainLevel.barHeight() = when (this) {
    RainLevel.UNKNOWN, RainLevel.DRY -> 0.25f
    RainLevel.MAYBE -> 0.5f
    RainLevel.WET -> 0.75f
    RainLevel.HEAVY -> 1f
}

/**
 * 雨況時間軸：整條的寬度是這趟行程的時間，每段的顏色與高度表示經過時的雨勢；
 * 下方標出發、抵達，以及第一次遇到雨（沒有的話是可能下雨）的時間。
 */
@Composable
internal fun RainTimeline(forecast: RouteForecast, modifier: Modifier = Modifier, labelColor: Color = Color.Gray) {
    val spans = remember(forecast) { RoutePlanner.timeline(forecast.stops) }
    val rain = spans.firstOrNull { it.level >= RainLevel.WET } ?: spans.firstOrNull { it.level == RainLevel.MAYBE }
    val seconds = Duration.between(forecast.departure, forecast.arrival).seconds
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(54.dp).semantics { contentDescription = forecast.summary }) {
        val barHeight = 30.dp.toPx()
        val gap = 1.5.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(Color.White.copy(alpha = 0.08f), size = Size(size.width, barHeight), cornerRadius = radius)
        spans.forEach { span ->
            val x0 = (span.start * size.width).toFloat() + gap / 2
            val x1 = (span.end * size.width).toFloat() - gap / 2
            val h = barHeight * span.level.barHeight()
            drawRoundRect(
                rainLevelColor(span.level),
                topLeft = Offset(x0, barHeight - h),
                size = Size((x1 - x0).coerceAtLeast(gap), h),
                cornerRadius = radius,
            )
        }

        val style = TextStyle(color = labelColor, fontSize = 12.sp)
        val y = barHeight + 5.dp.toPx()
        val start = measurer.measure(clock(forecast.departure), style)
        val end = measurer.measure(clock(forecast.arrival), style)
        drawText(start, topLeft = Offset(0f, y))
        drawText(end, topLeft = Offset(size.width - end.size.width, y))
        rain?.let { span ->
            val color = rainLevelColor(span.level)
            val time = forecast.departure.plusSeconds((seconds * span.start).roundToLong())
            val label = measurer.measure("${clock(time)} 起", style.copy(color = color, fontWeight = FontWeight.SemiBold))
            val x = (span.start * size.width).toFloat()
            val left = (x - label.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - label.size.width))
            // 一出發就遇雨，或太靠近兩端時不標，免得和出發、抵達時間重疊
            val pad = 6.dp.toPx()
            if (left > start.size.width + pad && left + label.size.width < size.width - end.size.width - pad) {
                drawLine(Color.White.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, y), strokeWidth = 1.dp.toPx())
                drawText(label, topLeft = Offset(left, y))
            }
        }
    }
}

private fun openInGoogleMaps(context: Context, data: RouteData) {
    val travel = when (data.mode) {
        TravelMode.SCOOTER -> "two-wheeler"
        TravelMode.CAR -> "driving"
        TravelMode.BIKE -> "bicycling"
        TravelMode.WALK -> "walking"
    }
    val waypoints = data.via.takeIf { it.isNotEmpty() }
        ?.joinToString("|", prefix = "&waypoints=") { "${it.latitude},${it.longitude}" }.orEmpty()
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${data.from.latitude},${data.from.longitude}" +
            "&destination=${data.to.latitude},${data.to.longitude}$waypoints&travelmode=$travel",
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        // 沒有地圖或瀏覽器 App
    }
}

/**
 * 路線圖的縮放：OSM 圖磚是 256px，在高密度螢幕上適度放大讓地名看得清楚又不太糊，
 * 四周留邊界放起終點標記。
 */
internal fun routeViewport(points: List<LatLon>, widthPx: Float, heightPx: Float, density: Float): MapViewport =
    WebMercator.fit(points, widthPx, heightPx, tilePx = 256 * maxOf(1f, density / 1.5f), padPx = 28 * density)

/** 路線各段（相鄰頂點之間）的顏色：取沿路線位置最接近的取樣點的降雨程度。 */
internal fun segmentColors(forecast: RouteForecast): List<Color> {
    val path = forecast.data.path.points
    val cumulative = DoubleArray(path.size)
    for (i in 1 until path.size) {
        cumulative[i] = cumulative[i - 1] +
            CwaParser.distanceKm(path[i - 1].latitude, path[i - 1].longitude, path[i].latitude, path[i].longitude)
    }
    val total = cumulative.lastOrNull()?.takeIf { it > 0 } ?: 1.0
    return (1 until path.size).map { i ->
        val f = (cumulative[i - 1] + cumulative[i]) / 2 / total
        forecast.stops.minByOrNull { kotlin.math.abs(it.point.fraction - f) }?.let(::rainColor) ?: RouteDry
    }
}

/** 出發時間在雷達預報的 1 小時內時才顯示雷達圖層 */
internal fun visibleNowcast(forecast: RouteForecast, now: LocalDateTime = LocalDateTime.now()): RainNowcast? =
    forecast.data.nowcast?.takeIf { it.covers(forecast.departure, now) }

/** 雷達格點的顏色（ARGB）：1 小時雨量越多越深，太少（< 0.1 mm）不畫 */
internal fun nowcastArgb(mm: Float): Int = when {
    mm < 0.1f -> 0
    mm < 1f -> 0x663FA9F5
    mm < 5f -> 0x88208CE8.toInt()
    mm < 10f -> 0xAA1565C0.toInt()
    else -> 0xAABF5AF2.toInt()
}

/** 路線範圍內的雷達格點影像：每格一個像素，第一列在北邊；西南角與東北角是格子的外緣 */
internal class NowcastRaster(val south: Double, val west: Double, val north: Double, val east: Double, val width: Int, val height: Int, val argb: IntArray)

internal fun nowcastRaster(nowcast: RainNowcast, points: List<LatLon>): NowcastRaster? {
    if (points.isEmpty()) return null
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    // 地圖會比路線範圍大一些（四周留邊、長寬比不同），多取一點
    val pad = maxOf(maxLat - minLat, maxLon - minLon) * 0.6 + 0.05
    val i0 = nowcast.column(minLon - pad).coerceIn(0, nowcast.nx - 1)
    val i1 = nowcast.column(maxLon + pad).coerceIn(0, nowcast.nx - 1)
    val j0 = nowcast.row(minLat - pad).coerceIn(0, nowcast.ny - 1)
    val j1 = nowcast.row(maxLat + pad).coerceIn(0, nowcast.ny - 1)
    val width = i1 - i0 + 1
    val height = j1 - j0 + 1
    if (width <= 1 || height <= 1) return null
    val argb = IntArray(width * height)
    var any = false
    for (j in j0..j1) {
        val row = (j1 - j) * width
        for (i in i0..i1) {
            val c = nowcastArgb(nowcast.value(i, j))
            if (c != 0) any = true
            argb[row + i - i0] = c
        }
    }
    if (!any) return null
    val half = nowcast.step / 2
    return NowcastRaster(
        south = nowcast.latitude(j0) - half, west = nowcast.longitude(i0) - half,
        north = nowcast.latitude(j1) + half, east = nowcast.longitude(i1) + half,
        width = width, height = height, argb = argb,
    )
}

internal fun NowcastRaster.toBitmap(): android.graphics.Bitmap =
    android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)

/** 解碼後的圖磚放在記憶體，重開畫面或改出發時間時不必重新讀檔。 */
internal object RouteMapTiles {
    private val bitmaps = LruCache<MapTile, ImageBitmap>(48)

    @Volatile
    private var cache: MapTileCache? = null

    private fun cache(context: Context) =
        cache ?: synchronized(this) { cache ?: MapTileCache(context.applicationContext.cacheDir).also { cache = it } }

    fun cached(tile: MapTile): ImageBitmap? = bitmaps.get(tile)

    suspend fun load(context: Context, tile: MapTile): ImageBitmap? {
        bitmaps.get(tile)?.let { return it }
        val bytes = cache(context).load(tile) ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } ?: return null
        bitmaps.put(tile, bitmap)
        return bitmap
    }
}

/** 有 Google 金鑰時用 Google 地圖，否則用 OpenStreetMap 圖磚畫的地圖。 */
@Composable
private fun RouteMap(forecast: RouteForecast, onOpenMaps: () -> Unit, googleMap: Boolean, modifier: Modifier = Modifier) {
    if (googleMap) {
        GoogleRouteMap(forecast, onOpenMaps, modifier)
    } else {
        TileRouteMap(forecast, modifier.clickable(onClick = onOpenMaps))
    }
}

/** OpenStreetMap 圖磚底圖加上依各段降雨程度著色的路線（類似導航 App 的路況顏色）。 */
@Composable
internal fun TileRouteMap(forecast: RouteForecast, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val path = forecast.data.path.points
    val stops = forecast.stops
    val colors = remember(forecast) { segmentColors(forecast) }
    val radar = visibleNowcast(forecast)
    val radarImage = remember(radar, path) { radar?.let { nowcastRaster(it, path) }?.let { it to it.toBitmap().asImageBitmap() } }
    BoxWithConstraints(modifier.background(MapPlaceholder)) {
        val density = LocalDensity.current.density
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (path.isEmpty() || widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints
        val viewport = remember(path, widthPx, heightPx, density) { routeViewport(path, widthPx, heightPx, density) }
        val tiles = remember(viewport) { viewport.tiles() }
        val images = remember(viewport) {
            androidx.compose.runtime.mutableStateMapOf<MapTile, ImageBitmap>().apply {
                tiles.forEach { (tile, _) -> RouteMapTiles.cached(tile)?.let { put(tile, it) } }
            }
        }
        LaunchedEffect(viewport) {
            tiles.filter { (tile, _) -> tile !in images }.forEach { (tile, _) ->
                launch { RouteMapTiles.load(context, tile)?.let { images[tile] = it } }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val tileSize = ceil(viewport.tilePx).toInt() + 1
            tiles.forEach { (tile, offset) ->
                val image = images[tile] ?: return@forEach
                drawImage(
                    image,
                    dstOffset = IntOffset(offset.first.roundToInt(), offset.second.roundToInt()),
                    dstSize = IntSize(tileSize, tileSize),
                    filterQuality = FilterQuality.Medium,
                )
            }
            radarImage?.let { (raster, image) ->
                val (x0, y0) = viewport.project(LatLon(raster.north, raster.west))
                val (x1, y1) = viewport.project(LatLon(raster.south, raster.east))
                drawImage(
                    image,
                    dstOffset = IntOffset(x0.roundToInt(), y0.roundToInt()),
                    dstSize = IntSize((x1 - x0).roundToInt(), (y1 - y0).roundToInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            val pts = path.map { viewport.project(it).let { (x, y) -> Offset(x, y) } }
            // 先畫白色外框再畫彩色路線，在地圖上比較清楚
            for (i in 1 until pts.size) {
                drawLine(Color.White, pts[i - 1], pts[i], strokeWidth = 9.dp.toPx(), cap = StrokeCap.Round)
            }
            for (i in 1 until pts.size) {
                drawLine(colors[i - 1], pts[i - 1], pts[i], strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            }
            stops.drop(1).dropLast(1).forEach { stop ->
                val (x, y) = viewport.project(stop.point.position)
                drawCircle(Color.White, 4.5.dp.toPx(), Offset(x, y))
                drawCircle(rainColor(stop), 3.dp.toPx(), Offset(x, y))
            }
            drawCircle(Color.White, 9.dp.toPx(), pts.first())
            drawCircle(RouteDry, 6.5.dp.toPx(), pts.first())
            drawCircle(Color.White, 9.dp.toPx(), pts.last())
            drawCircle(Color(0xFFFF453A), 6.5.dp.toPx(), pts.last())
        }
        Text(
            "© OpenStreetMap 貢獻者",
            color = Color(0xFF555555),
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.White.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun StopRow(group: StopGroup, title: String, first: Boolean, last: Boolean) {
    val stop = group.worst
    val color = rainColor(stop)
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(group.first.eta), color = Color.White, fontSize = 15.sp, modifier = Modifier.width(50.dp))
        Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(3.dp).weight(1f).background(if (first) Color.Transparent else Color.White.copy(alpha = 0.2f)))
                Box(Modifier.width(3.dp).weight(1f).background(if (last) Color.Transparent else Color.White.copy(alpha = 0.2f)))
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when {
                stop.rainingNow -> "附近雨量站：正在下雨"
                stop.nowcastMm != null -> if (stop.nowcastMm >= 0.1) "雷達：1 小時 %.1f mm".format(Locale.US, stop.nowcastMm) else "雷達：無雨"
                stop.hour != null -> WeatherCodes.description(stop.hour.weatherCode) +
                    (if (stop.hour.precipitation >= 0.1) " · %.1f mm".format(Locale.US, stop.hour.precipitation) else "")
                else -> "暫無預報"
            }
            // 合併的一段顯示「至 hh:mm」與距離範圍
            val where = if (group.stops.size == 1) {
                "%.1f 公里".format(Locale.US, stop.point.distanceKm)
            } else {
                "至 %s · %.0f–%.0f 公里".format(Locale.US, clock(group.last.eta), group.first.point.distanceKm, group.last.point.distanceKm)
            }
            Text(
                "$where · $detail",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        stop.hour?.let { h ->
            Text("${stop.probability}%", color = if (stop.probability >= 30) color else Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(WeatherCodes.emoji(h.weatherCode, h.isDay), fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(h.temperature.deg(), color = Color.White, fontSize = 16.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
        }
    }
}

/** 搜尋起點／終點：已儲存的地點、目前位置或地址。 */
@Composable
private fun EndpointSearch(
    title: String,
    places: List<City>,
    recent: List<City>,
    onRemember: (City) -> Unit,
    onClearRecent: () -> Unit,
    onSearch: suspend (String) -> List<AddressResult>,
    onUseCurrentLocation: suspend () -> AddressResult?,
    onPick: (City) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AddressResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        scope.launch {
            busy = true
            message = null
            results = onSearch(query)
            busy = false
            if (results.isEmpty()) message = "找不到這個地點，請輸入更完整的地址或地標名稱。"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("取消", color = Accent, fontSize = 17.sp) }
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(72.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel {
                DarkTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜尋地址或地標",
                    trailing = { IconButton(onClick = ::search) { Icon(Icons.Filled.Search, contentDescription = "搜尋", tint = Color.Gray) } },
                    onSearch = ::search,
                )
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) {
                            scope.launch {
                                busy = true
                                message = null
                                val here = onUseCurrentLocation()
                                busy = false
                                if (here != null) onPick(here.toRouteCity("目前位置", id = WeatherViewModel.LOCATION_ID)) else message = "無法取得目前位置，請確認已允許定位權限。"
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    Text(" 使用目前位置", color = Accent, fontSize = 15.sp)
                }
                if (busy) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                results.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            val city = r.toRouteCity()
                            onRemember(city)
                            onPick(city)
                        }.padding(vertical = 10.dp),
                    ) {
                        Text(r.address, color = Color.White, fontSize = 15.sp)
                        Text(if (r.approximate) "${r.area}（大概位置）" else r.area, color = Color.Gray, fontSize = 13.sp)
                    }
                }
                message?.let { Text(it, color = Color(0xFFFF9F0A), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            if (recent.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel("最近搜尋") }
                    Text(
                        "清除",
                        color = Accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onClearRecent).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Panel {
                    recent.forEachIndexed { i, place ->
                        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                onRemember(place)
                                onPick(place)
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(place.displayName, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = place.address ?: place.subtitle
                            if (sub.isNotBlank() && sub != place.displayName) {
                                Text(sub, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (places.isNotEmpty()) {
                SectionLabel("我的地點")
                Panel {
                    places.forEachIndexed { i, place ->
                        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Column(Modifier.fillMaxWidth().clickable { onPick(place) }.padding(vertical = 10.dp)) {
                            Text(place.displayName, color = Color.White, fontSize = 15.sp)
                            val sub = place.address ?: place.subtitle
                            if (sub.isNotBlank()) Text(sub, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

internal fun AddressResult.toRouteCity(label: String? = null, id: String? = null) = City(
    id = id ?: "route_%.5f_%.5f".format(Locale.US, latitude, longitude),
    name = area,
    subtitle = address,
    latitude = latitude,
    longitude = longitude,
    label = label,
    address = address,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartureTimeDialog(initial: LocalDateTime, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("出發時間", color = Color.White) },
        text = {
            TimePicker(state = state)
        },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("確定", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Accent) } },
    )
}
