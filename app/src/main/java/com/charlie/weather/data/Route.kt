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
    val county: String? = null,
    /** 這個縣市發布中的天氣特報 */
    val alerts: List<CwaAlert> = emptyList(),
) {
    val wet: Boolean get() = rainingNow || hour?.let(Commute::isWet) == true
    val probability: Int get() = if (rainingNow) 100 else hour?.precipitationProbability ?: 0

    val rainLevel: RainLevel get() = when {
        rainingNow || (hour?.precipitation ?: 0.0) >= 10 || (hour?.weatherCode ?: 0) in 95..99 -> RainLevel.HEAVY
        wet -> RainLevel.WET
        hour == null -> RainLevel.UNKNOWN
        probability >= 30 -> RainLevel.MAYBE
        else -> RainLevel.DRY
    }
}

/** 一個點的雨況分級；地圖、沿途清單與雨況時間軸共用。 */
enum class RainLevel { UNKNOWN, DRY, MAYBE, WET, HEAVY }

/**
 * 雨況時間軸的一段：行程時間的 [start, end)（0 是出發、1 是抵達）都是同一個雨況。
 * 經過時間與距離成正比，所以比例也就是路線上的位置。
 */
data class RainSpan(val start: Double, val end: Double, val level: RainLevel)

/** 結果最上方的大字結論，與一行補充說明。 */
data class RouteVerdict(val title: String, val detail: String)

/** 沿途除了雨以外要注意的事；文字在畫面上依使用者的單位組成。 */
sealed interface RouteHazard {
    /** 經過時仍生效的天氣特報，與會經過的發布縣市 */
    data class Alert(val title: String, val counties: List<String>) : RouteHazard

    /** 沿途最大的陣風（km/h） */
    data class Wind(val gustKmh: Double, val stop: RouteStop) : RouteHazard

    /** 路上會日落；stop 是日落後經過的第一個點 */
    data class Sunset(val time: LocalDateTime, val stop: RouteStop) : RouteHazard

    /** 沿途最低的體感溫度；騎車時含車速造成的風寒 */
    data class Cold(val feelsLike: Double, val temperature: Double, val riding: Boolean, val stop: RouteStop) : RouteHazard

    /** 沿途最高的體感溫度 */
    data class Heat(val feelsLike: Double, val stop: RouteStop) : RouteHazard

    /** 沿途最高的紫外線指數 */
    data class Uv(val index: Double, val stop: RouteStop) : RouteHazard
}

/** 出發時間比較條的一欄：在這個時間出發，沿途最高的降雨機率與最嚴重的雨況。 */
data class DepartureOption(val departure: LocalDateTime, val risk: Int, val level: RainLevel)

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
    val verdict: RouteVerdict get() = RoutePlanner.verdict(this)
    val hazards: List<RouteHazard> get() = RoutePlanner.hazards(this)
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

    /**
     * 雨況時間軸：每個取樣點代表它到前後兩點中間的那一段，相鄰同一雨況的段合併。
     */
    fun timeline(stops: List<RouteStop>): List<RainSpan> {
        val spans = mutableListOf<RainSpan>()
        stops.forEachIndexed { i, stop ->
            val start = if (i == 0) 0.0 else (stops[i - 1].point.fraction + stop.point.fraction) / 2
            val end = if (i == stops.lastIndex) 1.0 else (stop.point.fraction + stops[i + 1].point.fraction) / 2
            val level = stop.rainLevel
            val prev = spans.lastOrNull()
            if (prev != null && prev.level == level) spans[spans.lastIndex] = prev.copy(end = end)
            else spans += RainSpan(start, end, level)
        }
        return spans
    }

    /** 依各個出發時間重新計算沿途雨況（路線與行車時間不變）。 */
    fun compare(data: RouteData, departures: List<LocalDateTime>, now: LocalDateTime = LocalDateTime.now()): List<DepartureOption> =
        departures.map { departure ->
            val stops = stopsAt(data, departure, now)
            DepartureOption(departure, risk(stops), stops.maxOfOrNull { it.rainLevel } ?: RainLevel.UNKNOWN)
        }

    /**
     * 比較條上建議的出發時間：降雨機率比目前選的低 20% 以上的最低者（同機率取較早）；
     * 沒有的話為 null。
     */
    fun recommended(options: List<DepartureOption>, selected: LocalDateTime): DepartureOption? {
        val current = options.firstOrNull { it.departure == selected } ?: return null
        if (current.level == RainLevel.UNKNOWN) return null
        return options
            .filter { it.level != RainLevel.UNKNOWN && it.risk <= current.risk - 20 }
            .minWithOrNull(compareBy<DepartureOption> { it.risk }.thenBy { it.departure })
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
                county = weather?.cwa?.county,
                alerts = weather?.cwa?.alerts.orEmpty(),
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

    /** 機車、單車騎乘時的平均車速（km/h），用來算風寒；步行與汽車為 0 */
    private fun ridingKmh(mode: TravelMode) = when (mode) {
        TravelMode.SCOOTER -> 40.0
        TravelMode.BIKE -> 18.0
        else -> 0.0
    }

    /**
     * 風寒體感（加拿大／美國氣象局公式），v 為氣溫 15°C 以下時的相對風速 km/h；
     * 公式原本適用 10°C 以下，台灣冬天騎車 15°C 也會覺得冷，延伸使用並不高於氣溫。
     */
    fun windChill(temperature: Double, windKmh: Double): Double {
        if (temperature > 15 || windKmh < 4.8) return temperature
        val v = Math.pow(windKmh, 0.16)
        return minOf(temperature, 13.12 + 0.6215 * temperature - 11.37 * v + 0.3965 * temperature * v)
    }

    /**
     * 沿途要注意的事：特報、強陣風、日落、冷、熱、紫外線。
     * 汽車只看特報、陣風與日落（車內不受冷熱與日曬影響）。
     */
    fun hazards(forecast: RouteForecast): List<RouteHazard> {
        val stops = forecast.stops
        val mode = forecast.data.mode
        val exposed = mode != TravelMode.CAR
        val result = mutableListOf<RouteHazard>()

        // 經過該縣市時仍生效的特報，同一種特報合併縣市
        val alerts = linkedMapOf<String, LinkedHashSet<String>>()
        stops.forEach { stop ->
            stop.alerts
                .filter { (it.start == null || !it.start.isAfter(stop.eta)) && (it.end == null || it.end.isAfter(stop.eta)) }
                .forEach { alert -> alerts.getOrPut(alert.title) { linkedSetOf() }.apply { stop.county?.let(::add) } }
        }
        alerts.forEach { (title, counties) -> result += RouteHazard.Alert(title, counties.toList()) }

        // 機車與單車 40 km/h（約 6 級）就容易被側風吹偏
        val gustLimit = if (mode == TravelMode.SCOOTER || mode == TravelMode.BIKE) 40.0 else 55.0
        stops.filter { it.hour?.windGusts?.isNaN() == false }
            .maxByOrNull { it.hour!!.windGusts }
            ?.takeIf { it.hour!!.windGusts >= gustLimit }
            ?.let { result += RouteHazard.Wind(it.hour!!.windGusts, it) }

        sunset(forecast)?.let { result += it }

        if (exposed) {
            val ride = ridingKmh(mode)
            stops.mapNotNull { stop ->
                val h = stop.hour ?: return@mapNotNull null
                val feels = if (ride > 0 && h.temperature <= 15) {
                    windChill(h.temperature, ride + (h.windSpeed.takeUnless { it.isNaN() } ?: 0.0))
                } else {
                    h.apparentTemperature.takeUnless { it.isNaN() } ?: h.temperature
                }
                Triple(stop, feels, h.temperature)
            }.minByOrNull { it.second }
                ?.takeIf { it.second <= if (ride > 0) 12.0 else 10.0 }
                ?.let { (stop, feels, temperature) -> result += RouteHazard.Cold(feels, temperature, ride > 0, stop) }

            stops.filter { it.hour?.apparentTemperature?.isNaN() == false }
                .maxByOrNull { it.hour!!.apparentTemperature }
                ?.takeIf { it.hour!!.apparentTemperature >= 36 }
                ?.let { result += RouteHazard.Heat(it.hour!!.apparentTemperature, it) }

            stops.filter { stop -> stop.hour?.let { it.isDay && !it.uvIndex.isNaN() } == true }
                .maxByOrNull { it.hour!!.uvIndex }
                ?.takeIf { it.hour!!.uvIndex >= 8 }
                ?.let { result += RouteHazard.Uv(it.hour!!.uvIndex, it) }
        }
        return result
    }

    /** 經過兩個相鄰點之間時太陽下山 */
    private fun sunset(forecast: RouteForecast): RouteHazard.Sunset? {
        val stops = forecast.stops
        for (i in 1 until stops.size) {
            val stop = stops[i]
            val sunset = forecast.data.weathers.getOrNull(i)?.daily
                ?.firstOrNull { it.date == stop.eta.toLocalDate() }?.sunset ?: continue
            if (stops[i - 1].eta.isBefore(sunset) && !stop.eta.isBefore(sunset)) return RouteHazard.Sunset(sunset, stop)
        }
        return null
    }

    fun verdict(forecast: RouteForecast): RouteVerdict {
        val stops = forecast.stops
        if (stops.all { it.hour == null && !it.rainingNow }) return RouteVerdict("暫無預報", "這段時間還沒有逐時預報資料")
        val gear = forecast.data.mode.rainGear?.let { "記得帶$it" } ?: "注意路面濕滑"
        fun where(s: RouteStop) = s.place ?: "距起點 ${"%.1f".format(Locale.US, s.point.distanceKm)} 公里處"
        val wet = stops.filter { it.wet }
        val heavy = wet.any { it.rainLevel == RainLevel.HEAVY }
        val raining = stops.firstOrNull { it.rainingNow }
        return when {
            raining != null -> RouteVerdict("${where(raining)}正在下雨", gear)
            wet.size == stops.size -> RouteVerdict(
                if (heavy) "沿途都有雨，部分大雨" else "沿途都會下雨",
                "降雨機率最高 ${forecast.maxProbability}% · $gear",
            )
            wet.isNotEmpty() -> {
                val first = wet.first()
                val rain = if (heavy) "有大雨" else "可能下雨"
                RouteVerdict(
                    if (first == stops.first()) "一出發就$rain" else "${"%02d:%02d".format(first.eta.hour, first.eta.minute)} 起$rain",
                    "${where(first)}一帶 · 降雨機率 ${first.probability}% · $gear",
                )
            }
            forecast.maxProbability >= 30 -> RouteVerdict(
                "可能會下雨",
                "降雨機率最高 ${forecast.maxProbability}% · 可以備著${forecast.data.mode.rainGear ?: "雨具"}",
            )
            else -> RouteVerdict("沿途不太會下雨", "降雨機率最高 ${forecast.maxProbability}%")
        }
    }

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
