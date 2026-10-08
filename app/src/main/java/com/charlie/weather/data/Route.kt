package com.charlie.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 交通方式：決定用哪種 OSM 路網規劃路線。[fallbackKmh] 是市區含紅綠燈與車流的平均時速，
 * 用在直線估計，也當作 OSM 行車時間的下限（OSM 依道路速限計算，沒有路況，市區常低估一半）。
 */
enum class TravelMode(val label: String, val profile: String, val fallbackKmh: Double, val rainGear: String?) {
    SCOOTER("機車", "routed-car", 25.0, "雨衣"),
    CAR("汽車", "routed-car", 22.0, null),
    BIKE("單車", "routed-bike", 13.0, "雨衣"),
    WALK("步行", "routed-foot", 4.5, "雨傘"),
}

data class LatLon(val latitude: Double, val longitude: Double)

/** 路線與行車時間的來源 */
enum class RouteSource {
    /** Google 地圖，含路況 */
    GOOGLE,

    /** OpenStreetMap 路網，不含路況 */
    OSM,

    /** 路線服務無法使用，以直線距離估計 */
    ESTIMATE,
}

/** 規劃好的路線。 */
data class RoutePath(
    val points: List<LatLon>,
    val distanceKm: Double,
    val durationMinutes: Double,
    val source: RouteSource = RouteSource.OSM,
) {
    val approximate: Boolean get() = source == RouteSource.ESTIMATE
}

/** 路線上取樣的一個點；[fraction] 是從起點算起佔全程的比例（0–1）。 */
data class RoutePoint(val position: LatLon, val fraction: Double, val distanceKm: Double)

/** 查詢一次路線的結果：取樣點與各點的天氣（某點查詢失敗時為 null）。改出發時間只需重新計算，不必重抓。 */
data class RouteData(
    val from: City,
    val to: City,
    val mode: TravelMode,
    val path: RoutePath,
    val points: List<RoutePoint>,
    val weathers: List<Weather?>,
    /** 途經點（從 Google 地圖分享的路線才有） */
    val via: List<City> = emptyList(),
)

/** 路線上一點在「經過時間」的天氣。 */
data class RouteStop(
    val point: RoutePoint,
    val eta: LocalDateTime,
    /** 氣象署鄉鎮名稱（例如「內湖區」）；台灣以外為 null */
    val place: String?,
    val hour: HourlyForecast?,
    /** 附近雨量站顯示現在正在下雨（只在即將經過的點才採用） */
    val rainingNow: Boolean,
) {
    val wet: Boolean get() = rainingNow || hour?.let(Commute::isWet) == true
    val probability: Int get() = if (rainingNow) 100 else hour?.precipitationProbability ?: 0
}

/** 沿途清單的一行：連續經過同一個鄉鎮的點。 */
data class StopGroup(val stops: List<RouteStop>) {
    val first: RouteStop get() = stops.first()
    val last: RouteStop get() = stops.last()

    /** 降雨機率最高（同機率取較早）的點，代表這一段的天氣 */
    val worst: RouteStop get() = stops.maxWith(compareBy<RouteStop> { it.probability }.thenByDescending { it.eta })
}

data class RouteForecast(
    val data: RouteData,
    val departure: LocalDateTime,
    val stops: List<RouteStop>,
    /** 比目前出發時間更不容易淋到雨的出發時間；沒有更好的選擇時為 null */
    val betterDeparture: Pair<LocalDateTime, Int>? = null,
) {
    val arrival: LocalDateTime get() = departure.plusSeconds((data.path.durationMinutes * 60).roundToLong())
    val maxProbability: Int get() = stops.maxOfOrNull { it.probability } ?: 0
    val summary: String get() = RoutePlanner.summary(this)
}

/**
 * 路線規劃：有 Google 金鑰時用 Google 地圖（含路況）；否則或失敗時用 OpenStreetMap 路網
 * （FOSSGIS 的 OSRM 服務，免金鑰）；都失敗時以直線估計。
 */
object RouteApi {
    private const val BASE_URL = "https://routing.openstreetmap.de"

    suspend fun route(
        from: LatLon,
        to: LatLon,
        mode: TravelMode,
        departure: LocalDateTime? = null,
        google: GoogleRoutes? = null,
        via: List<LatLon> = emptyList(),
    ): RoutePath {
        google?.route(from, to, mode, departure, via)?.let { return it }
        return try {
            parse(get(url(from, to, mode, via)))?.let { withCityPace(it, mode) } ?: straightLine(from, to, mode, via)
        } catch (e: IOException) {
            straightLine(from, to, mode, via)
        } catch (e: org.json.JSONException) {
            straightLine(from, to, mode, via)
        }
    }

    fun url(from: LatLon, to: LatLon, mode: TravelMode, via: List<LatLon> = emptyList()): String {
        fun p(l: LatLon) = String.format(Locale.US, "%.5f,%.5f", l.longitude, l.latitude)
        val coords = (listOf(from) + via + to).joinToString(";", transform = ::p)
        return "$BASE_URL/${mode.profile}/route/v1/driving/$coords?overview=full&geometries=geojson"
    }

    /** 解析 OSRM 回應（座標為 [經度, 緯度]）。 */
    fun parse(json: String): RoutePath? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val route = root.optJSONArray("routes")?.optJSONObject(0) ?: return null
        val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val points = (0 until coords.length()).mapNotNull { i ->
            coords.optJSONArray(i)?.let { LatLon(it.getDouble(1), it.getDouble(0)) }
        }
        if (points.size < 2) return null
        return RoutePath(points, route.optDouble("distance", 0.0) / 1000, route.optDouble("duration", 0.0) / 60)
    }

    /** OSM 的行車時間不得少於以市區平均時速走完全程的時間。 */
    fun withCityPace(path: RoutePath, mode: TravelMode): RoutePath =
        path.copy(durationMinutes = maxOf(path.durationMinutes, path.distanceKm / mode.fallbackKmh * 60))

    /** 直線距離 × 1.3 估計實際道路距離。 */
    fun straightLine(from: LatLon, to: LatLon, mode: TravelMode, via: List<LatLon> = emptyList()): RoutePath {
        val points = listOf(from) + via + to
        val km = points.zipWithNext { a, b -> CwaParser.distanceKm(a.latitude, a.longitude, b.latitude, b.longitude) }.sum() * 1.3
        return RoutePath(points, km, km / mode.fallbackKmh * 60, RouteSource.ESTIMATE)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // 服務的使用規範要求可辨識的 User-Agent
            conn.setRequestProperty("User-Agent", "MyWeatherAndroid/1.0 (github.com/charlie5041/weather_android)")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

object RoutePlanner {
    /** 預設取樣間距：氣象署鄉鎮預報與 Open-Meteo 網格約數公里，再密也沒有更多資訊 */
    const val STEP_KM = 3.0

    /** 設定裡可選的取樣間距（公里） */
    val STEP_OPTIONS = listOf(1, 2, 3, 5, 10)

    /** 一次最多查幾個點（一次 Open-Meteo 請求）；路線很長時間距會自動拉大 */
    const val MAX_POINTS = 50

    /** 沿路線約每 [stepKm] 公里取一點（含起點與終點），最多 [maxPoints] 點。 */
    fun sample(path: RoutePath, stepKm: Double = STEP_KM, maxPoints: Int = MAX_POINTS): List<RoutePoint> {
        val pts = path.points
        val cumulative = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cumulative[i] = cumulative[i - 1] +
                CwaParser.distanceKm(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude)
        }
        val total = cumulative.last()
        if (total <= 0.0) return listOf(RoutePoint(pts.first(), 0.0, 0.0))
        val segments = ceil(path.distanceKm.coerceAtLeast(total) / stepKm).toInt().coerceIn(1, maxPoints - 1)
        var seg = 0
        return (0..segments).map { k ->
            val fraction = k.toDouble() / segments
            val target = total * fraction
            while (seg < pts.size - 2 && cumulative[seg + 1] < target) seg++
            val a = pts[seg]
            val b = pts[seg + 1]
            val len = cumulative[seg + 1] - cumulative[seg]
            val t = if (len <= 0.0) 0.0 else ((target - cumulative[seg]) / len).coerceIn(0.0, 1.0)
            RoutePoint(
                LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t),
                fraction,
                path.distanceKm * fraction,
            )
        }
    }

    /** 依出發時間算出各點的經過時間與當時天氣，並找出較不會淋雨的出發時間。 */
    fun evaluate(data: RouteData, departure: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): RouteForecast {
        val stops = stopsAt(data, departure, now)
        val risk = risk(stops)
        // 往後 2 小時內每 30 分鐘試一次，降雨機率明顯較低才建議
        val better = if (risk < 40) null else (1..4)
            .map { departure.plusMinutes(30L * it) }
            .map { it to risk(stopsAt(data, it, now)) }
            .filter { (_, r) -> r <= risk - 20 }
            .minByOrNull { (_, r) -> r }
        return RouteForecast(data, departure, stops, better)
    }

    private fun risk(stops: List<RouteStop>) = stops.maxOfOrNull { it.probability } ?: 0

    private fun stopsAt(data: RouteData, departure: LocalDateTime, now: LocalDateTime): List<RouteStop> =
        data.points.mapIndexed { i, point ->
            val eta = departure.plusSeconds((data.path.durationMinutes * 60 * point.fraction).roundToLong())
            val weather = data.weathers.getOrNull(i)
            val rain = weather?.cwa?.rain
            // 雨量站是「現在」的實測，只用在 30 分鐘內會經過的點；資料需在 40 分鐘內
            val rainingNow = rain != null && rain.raining &&
                Duration.between(now, eta).toMinutes() in -10..30 &&
                rain.time?.let { Duration.between(it, now).abs().toMinutes() <= 40 } == true
            RouteStop(
                point = point,
                eta = eta,
                place = weather?.cwa?.township,
                hour = weather?.let { nearestHour(it, eta) },
                rainingNow = rainingNow,
            )
        }

    /**
     * 把中途連續在同一個鄉鎮的點合併成一組，讓沿途清單短一點；起點與終點各自一組。
     * 每組以降雨機率最高的點代表。
     */
    fun group(stops: List<RouteStop>): List<StopGroup> {
        val groups = mutableListOf<MutableList<RouteStop>>()
        stops.forEachIndexed { i, stop ->
            // 中途點、前一點也是中途點，且同一個鄉鎮
            val sameAsPrev = i >= 2 && i < stops.lastIndex && stop.place != null && stop.place == stops[i - 1].place
            if (sameAsPrev) groups.last() += stop else groups += mutableListOf(stop)
        }
        return groups.map { StopGroup(it) }
    }

    /** 取最接近經過時間的整點預報（1 小時內）；路上的時間不一定是整點。 */
    fun nearestHour(weather: Weather, time: LocalDateTime): HourlyForecast? =
        weather.hourly
            .filter { abs(Duration.between(it.time, time).toMinutes()) <= 60 }
            .minByOrNull { abs(Duration.between(it.time, time).toMinutes()) }

    fun summary(forecast: RouteForecast): String {
        val stops = forecast.stops
        if (stops.all { it.hour == null && !it.rainingNow }) return "暫無這段時間的預報資料"
        val gear = forecast.data.mode.rainGear?.let { "，記得帶$it" } ?: "，注意路面濕滑"
        fun where(s: RouteStop) = s.place ?: "距起點 ${"%.1f".format(Locale.US, s.point.distanceKm)} 公里處"
        val wet = stops.filter { it.wet }
        val tip = when {
            stops.any { it.rainingNow } -> "${where(stops.first { it.rainingNow })}附近現在正在下雨$gear"
            wet.size == stops.size -> "沿途都可能下雨$gear"
            wet.isNotEmpty() -> {
                val first = wet.first()
                val heavy = wet.any { (it.hour?.precipitation ?: 0.0) >= 10 || (it.hour?.weatherCode ?: 0) in 95..99 }
                "約 ${"%02d:%02d".format(first.eta.hour, first.eta.minute)} 經過${where(first)}時可能${if (heavy) "有大雨" else "下雨"}" +
                    "（降雨機率 ${first.probability}%）$gear"
            }
            forecast.maxProbability >= 30 -> "沿途降雨機率最高 ${forecast.maxProbability}%，可以備著${forecast.data.mode.rainGear ?: "雨具"}"
            else -> "沿途降雨機率低，適合出發"
        }
        val better = forecast.betterDeparture?.let { (time, p) ->
            "。若改在 ${"%02d:%02d".format(time.hour, time.minute)} 出發，降雨機率約 $p%"
        }.orEmpty()
        return tip + better
    }
}
