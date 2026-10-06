package com.charlie.weather.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.BuildConfig
import com.charlie.weather.data.AddressResult
import com.charlie.weather.data.City
import com.charlie.weather.data.CwaParser
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.MapTile
import com.charlie.weather.data.MapTileCache
import com.charlie.weather.data.MapViewport
import com.charlie.weather.data.WebMercator
import com.charlie.weather.data.RouteData
import com.charlie.weather.data.RouteForecast
import com.charlie.weather.data.RoutePlanner
import com.charlie.weather.data.RouteSource
import com.charlie.weather.data.RouteStop
import com.charlie.weather.data.TravelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val RouteDry = Color(0xFF30D158)
internal val RouteMaybe = Color(0xFFFFD60A)
internal val RouteWet = Color(0xFF0A84FF)
internal val RouteHeavy = Color(0xFFBF5AF2)
private val MapPlaceholder = Color(0xFFE8E6E1)

private enum class Endpoint { FROM, TO }

/** 起點、終點與出發時間；從通勤卡片開啟時會帶入住家、公司與通勤時間。 */
data class RouteRequest(val from: City?, val to: City?, val departure: LocalDateTime? = null, val mode: TravelMode? = null)

/**
 * 路線降雨：像地圖 App 一樣輸入起點與終點，沿路線每幾公里取一點，
 * 依騎到該點的時間查逐時預報，看路上會不會遇到雨。
 */
@Composable
fun RouteScreen(
    request: RouteRequest,
    places: List<City>,
    onSearch: suspend (String) -> List<AddressResult>,
    onUseCurrentLocation: suspend () -> AddressResult?,
    onLoad: suspend (City, City, TravelMode, LocalDateTime) -> RouteData,
    /** 行車時間含路況（Google）：改出發時間要重新查詢 */
    trafficAware: Boolean = false,
    onClose: () -> Unit,
) {
    val openedAt = remember { LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) }
    var from by remember { mutableStateOf(request.from) }
    var to by remember { mutableStateOf(request.to) }
    var mode by remember { mutableStateOf(request.mode ?: TravelMode.SCOOTER) }
    var departure by remember { mutableStateOf(request.departure ?: openedAt) }
    var editing by remember { mutableStateOf<Endpoint?>(if (request.from != null && request.to == null) Endpoint.TO else null) }
    var data by remember { mutableStateOf<RouteData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickTime by remember { mutableStateOf(false) }

    LaunchedEffect(from, to, mode, if (trafficAware) departure else null) {
        val a = from
        val b = to
        data = null
        error = null
        if (a == null || b == null) return@LaunchedEffect
        loading = true
        try {
            data = onLoad(a, b, mode, departure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "無法取得沿途天氣，請檢查網路連線"
        }
        loading = false
    }
    val forecast = remember(data, departure) { data?.let { RoutePlanner.evaluate(it, departure) } }

    val endpoint = editing
    if (endpoint != null) {
        EndpointSearch(
            title = if (endpoint == Endpoint.FROM) "選擇起點" else "選擇終點",
            places = places,
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
                TextButton(onClick = onClose) { Text("完成", color = Accent, fontSize = 17.sp) }
                Text(
                    "路線降雨",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(72.dp))
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
                                }
                                .padding(10.dp),
                        )
                    }
                }

                ChipRow(TravelMode.entries.map { modeEmoji(it) + " " + it.label }, TravelMode.entries.indexOf(mode)) {
                    mode = TravelMode.entries[it]
                }

                val presets = buildList {
                    add("現在出發" to openedAt)
                    request.departure?.takeIf { it.isAfter(openedAt) }?.let { add("${routeDay(it)} ${clock(it)}" to it) }
                    listOf(30L, 60L, 120L).forEach { add((if (it < 60) "$it 分鐘後" else "${it / 60} 小時後") to openedAt.plusMinutes(it)) }
                }
                val presetIndex = presets.indexOfFirst { it.second == departure }
                val labels = presets.map { it.first } + if (presetIndex < 0) "${routeDay(departure)} ${clock(departure)}" else "自訂時間"
                ChipRow(labels, if (presetIndex >= 0) presetIndex else presets.size) { i ->
                    if (i < presets.size) departure = presets[i].second else pickTime = true
                }

                when {
                    from == null || to == null -> Hint("輸入起點與終點，查看路上每一段經過時的降雨機率。")
                    loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    error != null -> Hint(error!!)
                    forecast != null -> RouteResult(forecast, onDeparture = { departure = it })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pickTime) {
        DepartureTimeDialog(departure, onDismiss = { pickTime = false }) { hour, minute ->
            pickTime = false
            val today = openedAt.toLocalDate().atTime(hour, minute)
            // 選的時間已經過了就當作明天
            departure = if (today.isBefore(openedAt.minusMinutes(5))) today.plusDays(1) else today
        }
    }
}

private fun modeEmoji(mode: TravelMode) = when (mode) {
    TravelMode.SCOOTER -> "🛵"
    TravelMode.CAR -> "🚗"
    TravelMode.BIKE -> "🚲"
    TravelMode.WALK -> "🚶"
}

private fun clock(time: LocalDateTime) = "%02d:%02d".format(time.hour, time.minute)

private fun routeDay(time: LocalDateTime, today: LocalDate = LocalDate.now()) = when (time.toLocalDate()) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> "${time.monthValue}/${time.dayOfMonth}"
}

internal fun rainColor(stop: RouteStop): Color = when {
    stop.rainingNow || (stop.hour?.precipitation ?: 0.0) >= 10 || (stop.hour?.weatherCode ?: 0) in 95..99 -> RouteHeavy
    stop.wet -> RouteWet
    stop.probability >= 30 -> RouteMaybe
    else -> RouteDry
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
    onDeparture: (LocalDateTime) -> Unit = {},
    /** 有 Google 金鑰時用 Google 地圖；截圖測試固定用圖磚地圖 */
    googleMap: Boolean = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank(),
) {
    val context = LocalContext.current
    val data = forecast.data
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            Text(
                "${clock(forecast.departure)} 出發 → 約 ${clock(forecast.arrival)} 抵達",
                color = Color.Gray,
                fontSize = 13.sp,
            )
            Text(
                "%.1f 公里 · 約 %d 分鐘%s".format(
                    Locale.US,
                    data.path.distanceKm,
                    data.path.durationMinutes.toInt().coerceAtLeast(1),
                    when (data.path.source) {
                        RouteSource.GOOGLE -> "（含路況）"
                        RouteSource.ESTIMATE -> "（直線估計）"
                        RouteSource.OSM -> "（未含路況）"
                    },
                ),
                color = Color.Gray,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(forecast.summary, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
            forecast.betterDeparture?.let { (time, _) ->
                Text(
                    "改成 ${clock(time)} 出發 ›",
                    color = Accent,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable { onDeparture(time) },
                )
            }
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
        }

        Panel {
            forecast.stops.forEachIndexed { i, stop ->
                StopRow(
                    stop,
                    title = when (i) {
                        0 -> data.from.displayName
                        forecast.stops.lastIndex -> data.to.displayName
                        else -> stop.place ?: "途中"
                    },
                    first = i == 0,
                    last = i == forecast.stops.lastIndex,
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
        Text(
            "沿路線約每 ${RoutePlanner.STEP_KM.toInt()} 公里取一點，依預估經過時間查逐時預報；即將經過的點會參考附近雨量站是否正在下雨。" +
                when (data.path.source) {
                    RouteSource.GOOGLE -> "路線與行車時間：Google 地圖（依出發時間的路況）。"
                    else -> (if (data.mode == TravelMode.SCOOTER) "機車以汽車路線估計，可能包含機車不能行駛的道路；" else "") +
                        "行車時間未含路況。路線資料：© OpenStreetMap 貢獻者（FOSSGIS 路線服務）。"
                } + (if (googleMap) "" else "地圖：© OpenStreetMap 貢獻者。") + "點地圖可在 Google 地圖開啟導航。",
            color = Color.Gray,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun openInGoogleMaps(context: Context, data: RouteData) {
    val travel = when (data.mode) {
        TravelMode.SCOOTER -> "two-wheeler"
        TravelMode.CAR -> "driving"
        TravelMode.BIKE -> "bicycling"
        TravelMode.WALK -> "walking"
    }
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${data.from.latitude},${data.from.longitude}" +
            "&destination=${data.to.latitude},${data.to.longitude}&travelmode=$travel",
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
private fun StopRow(stop: RouteStop, title: String, first: Boolean, last: Boolean) {
    val color = rainColor(stop)
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(stop.eta), color = Color.White, fontSize = 15.sp, modifier = Modifier.width(50.dp))
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
                stop.hour != null -> WeatherCodes.description(stop.hour.weatherCode) +
                    (if (stop.hour.precipitation >= 0.1) " · %.1f mm".format(Locale.US, stop.hour.precipitation) else "")
                else -> "暫無預報"
            }
            Text(
                "%.1f 公里 · %s".format(Locale.US, stop.point.distanceKm, detail),
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
                    Column(Modifier.fillMaxWidth().clickable { onPick(r.toRouteCity()) }.padding(vertical = 10.dp)) {
                        Text(r.address, color = Color.White, fontSize = 15.sp)
                        Text(if (r.approximate) "${r.area}（大概位置）" else r.area, color = Color.Gray, fontSize = 13.sp)
                    }
                }
                message?.let { Text(it, color = Color(0xFFFF9F0A), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
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

private fun AddressResult.toRouteCity(label: String? = null, id: String? = null) = City(
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
