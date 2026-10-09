package com.charlie.weather.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一個縣市鄉鎮預報檔（F-D0047 系列，數 MB）的索引：每個鄉鎮的座標與各自的 JSON。
 * 解析一次後給路線上的各點共用，每點只需解析最近那個鄉鎮的小段 JSON。
 */
class TownshipIndex(val county: String, val towns: List<Town>) {
    class Town(val name: String, val latitude: Double?, val longitude: Double?, val json: String)
}

/** 中央氣象署測站觀測（O-A0003-001 / O-A0001-001）。數值缺測時為 null。 */
data class CwaStation(
    val id: String,
    val name: String,
    val county: String,
    val town: String,
    val latitude: Double,
    val longitude: Double,
    val time: LocalDateTime?,
    val weather: String?,
    val temperature: Double?,
    val humidity: Int?,
    val windSpeedMs: Double?,
    val windDirection: Int?,
    val gustMs: Double?,
    val pressure: Double?,
    val precipitation: Double?,
    val uvIndex: Double?,
    val dailyHigh: Double?,
    val dailyLow: Double?,
)

data class CwaObservation(val station: CwaStation, val distanceKm: Double)

/** 鄉鎮 3 天預報中每 3 小時的區段。 */
data class CwaBlock(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val precipitationProbability: Int?,
    val weatherCode: Int?,
    val weather: String?,
)

/** 鄉鎮一週預報整理成的每日資料。 */
data class CwaDaily(
    val date: LocalDate,
    val max: Double?,
    val min: Double?,
    val hasDaytime: Boolean,
    val precipitationProbability: Int?,
    val weatherCode: Int?,
    val weather: String?,
)

data class CwaForecast(
    val county: String,
    val township: String,
    val hourlyTemperature: Map<LocalDateTime, Double>,
    val blocks: List<CwaBlock>,
    val daily: List<CwaDaily>,
) {
    fun blockAt(time: LocalDateTime): CwaBlock? = blocks.firstOrNull { !time.isBefore(it.start) && time.isBefore(it.end) }
}

data class CwaAlert(
    val phenomena: String,
    val significance: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
) {
    val title: String get() = phenomena + significance
}

/** 自動雨量站（O-A0002-001）的一筆觀測，單位毫米；缺測為 null。 */
data class RainGauge(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val time: LocalDateTime?,
    val past10Min: Double?,
    val past1Hour: Double?,
    /** 本日累積雨量 */
    val today: Double?,
)

/** 附近雨量站綜合判斷的「現在」降雨狀況。 */
data class RainNow(
    val stationName: String,
    val distanceKm: Double,
    val time: LocalDateTime?,
    /** 附近任一站過去 10 分鐘有雨 */
    val raining: Boolean,
    /** 以過去 10 分鐘雨量推估的時雨量（mm/h），取附近各站最大值 */
    val ratePerHour: Double,
    /** 附近各站過去 1 小時雨量的最大值 */
    val pastHour: Double,
    /** 最近一站的本日累積雨量 */
    val today: Double?,
)

data class CwaData(
    val observation: CwaObservation?,
    val forecast: CwaForecast?,
    val alerts: List<CwaAlert>,
    val rain: RainNow? = null,
)

object CwaParser {

    fun parseStations(json: String): List<CwaStation> {
        val arr = JSONObject(json).optJSONObject("cwaopendata")?.optJSONObject("dataset")?.optJSONArray("Station")
            ?: return emptyList()
        return arr.objects().mapNotNull { s ->
            val geo = s.optJSONObject("GeoInfo") ?: return@mapNotNull null
            val coords = geo.optJSONArray("Coordinates")?.objects().orEmpty()
            val coord = coords.firstOrNull { it.optString("CoordinateName") == "WGS84" } ?: coords.firstOrNull()
                ?: return@mapNotNull null
            val lat = coord.opt("StationLatitude").asDouble() ?: return@mapNotNull null
            val lon = coord.opt("StationLongitude").asDouble() ?: return@mapNotNull null
            val we = s.optJSONObject("WeatherElement") ?: JSONObject()
            CwaStation(
                id = s.optString("StationId"),
                name = s.optString("StationName"),
                county = geo.optString("CountyName"),
                town = geo.optString("TownName"),
                latitude = lat,
                longitude = lon,
                time = parseTime(s.path("ObsTime", "DateTime")),
                weather = (we.opt("Weather") as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("-9") },
                temperature = we.opt("AirTemperature").measured(),
                humidity = we.opt("RelativeHumidity").measured()?.takeIf { it in 0.0..100.0 }?.toInt(),
                windSpeedMs = we.opt("WindSpeed").measured()?.takeIf { it >= 0 },
                windDirection = we.opt("WindDirection").measured()?.takeIf { it in 0.0..360.0 }?.toInt(),
                gustMs = we.path("GustInfo", "PeakGustSpeed").measured()?.takeIf { it >= 0 },
                pressure = we.opt("AirPressure").measured()?.takeIf { it > 500 },
                precipitation = we.path("Now", "Precipitation").measured()?.takeIf { it >= 0 },
                uvIndex = we.opt("UVIndex").measured()?.takeIf { it >= 0 },
                dailyHigh = we.path("DailyExtreme", "DailyHigh", "TemperatureInfo", "AirTemperature").measured(),
                dailyLow = we.path("DailyExtreme", "DailyLow", "TemperatureInfo", "AirTemperature").measured(),
            )
        }
    }

    fun parseRainGauges(json: String): List<RainGauge> {
        val arr = JSONObject(json).optJSONObject("cwaopendata")?.optJSONObject("dataset")?.optJSONArray("Station")
            ?: return emptyList()
        return arr.objects().mapNotNull { s ->
            val geo = s.optJSONObject("GeoInfo") ?: return@mapNotNull null
            val coords = geo.optJSONArray("Coordinates")?.objects().orEmpty()
            val coord = coords.firstOrNull { it.optString("CoordinateName") == "WGS84" } ?: coords.firstOrNull()
                ?: return@mapNotNull null
            val rain = s.optJSONObject("RainfallElement") ?: return@mapNotNull null
            fun mm(key: String) = rain.path(key, "Precipitation").asDouble()?.takeIf { it >= 0 }
            RainGauge(
                id = s.optString("StationId"),
                name = s.optString("StationName"),
                latitude = coord.opt("StationLatitude").asDouble() ?: return@mapNotNull null,
                longitude = coord.opt("StationLongitude").asDouble() ?: return@mapNotNull null,
                time = parseTime(s.path("ObsTime", "DateTime")),
                past10Min = mm("Past10Min"),
                past1Hour = mm("Past1hr"),
                today = mm("Now"),
            )
        }
    }

    /**
     * 綜合 [maxKm] 公里內最近 3 個雨量站判斷現在是否下雨。
     * 用多站是為了避免單一站故障或雨帶邊緣造成誤判。
     */
    fun rainNow(gauges: List<RainGauge>, latitude: Double, longitude: Double, maxKm: Double = 5.0): RainNow? {
        val near = gauges.asSequence()
            .filter { it.past10Min != null }
            .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= maxKm }
            .sortedBy { it.second }
            .take(3)
            .toList()
        val (closest, distance) = near.firstOrNull() ?: return null
        return RainNow(
            stationName = closest.name,
            distanceKm = distance,
            time = closest.time,
            raining = near.any { (it.first.past10Min ?: 0.0) > 0.0 },
            ratePerHour = near.maxOf { (it.first.past10Min ?: 0.0) * 6 },
            pastHour = near.maxOf { it.first.past1Hour ?: 0.0 },
            today = closest.today,
        )
    }

    /** 找出距離最近、且有溫度資料的測站。 */
    fun nearest(stations: List<CwaStation>, latitude: Double, longitude: Double, maxKm: Double): CwaObservation? =
        stations.asSequence()
            .filter { it.temperature != null }
            .map { CwaObservation(it, distanceKm(latitude, longitude, it.latitude, it.longitude)) }
            .filter { it.distanceKm <= maxKm }
            .minByOrNull { it.distanceKm }

    /**
     * 解析鄉鎮預報（F-D0047 系列）。
     * @param threeDayJson 未來 3 天逐 3 小時（含逐時溫度）
     * @param weeklyJson 未來 1 週逐 12 小時
     */
    fun parseTownshipForecast(threeDayJson: String?, weeklyJson: String?, latitude: Double, longitude: Double): CwaForecast? =
        parseTownshipForecast(threeDayJson?.let(::indexTownships), weeklyJson?.let(::indexTownships), latitude, longitude)

    /** 同上，使用已建立的索引（路線上多點共用，不必每點重新解析整個檔案） */
    fun parseTownshipForecast(threeDayIndex: TownshipIndex?, weeklyIndex: TownshipIndex?, latitude: Double, longitude: Double): CwaForecast? {
        val threeDay = threeDayIndex?.let { nearestTownship(it, latitude, longitude, preferredName = null) }
        val weekly = weeklyIndex?.let { nearestTownship(it, latitude, longitude, preferredName = threeDay?.second?.optString("LocationName")) }
        if (threeDay == null && weekly == null) return null
        val (county, location) = threeDay ?: weekly!!

        val hourly = mutableMapOf<LocalDateTime, Double>()
        val blocks = mutableListOf<CwaBlock>()
        threeDay?.second?.let { loc ->
            val elements = loc.elements()
            elements["溫度"]?.objects()?.forEach { t ->
                val time = parseTime(t.opt("DataTime")) ?: return@forEach
                val temp = t.value("Temperature").measured() ?: return@forEach
                hourly[time] = temp
            }
            val pops = elements["3小時降雨機率"]?.objects().orEmpty()
                .mapNotNull { t -> parseTime(t.opt("StartTime"))?.let { it to t.value("ProbabilityOfPrecipitation").asInt() } }
                .toMap()
            elements["天氣現象"]?.objects()?.forEach { t ->
                val start = parseTime(t.opt("StartTime")) ?: return@forEach
                val end = parseTime(t.opt("EndTime")) ?: start.plusHours(3)
                blocks += CwaBlock(
                    start = start,
                    end = end,
                    precipitationProbability = pops[start],
                    weatherCode = t.value("WeatherCode").asInt(),
                    weather = (t.value("Weather") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }

        val daily = weekly?.second?.let { parseWeekly(it) }.orEmpty()
        return CwaForecast(
            county = county,
            township = location.optString("LocationName"),
            hourlyTemperature = hourly,
            blocks = blocks.sortedBy { it.start },
            daily = daily,
        )
    }

    private fun parseWeekly(location: JSONObject): List<CwaDaily> {
        data class Half(
            val start: LocalDateTime,
            var max: Double? = null,
            var min: Double? = null,
            var pop: Int? = null,
            var code: Int? = null,
            var text: String? = null,
        )
        val halves = sortedMapOf<LocalDateTime, Half>()
        fun half(t: JSONObject): Half? = parseTime(t.opt("StartTime"))?.let { start -> halves.getOrPut(start) { Half(start) } }

        val elements = location.elements()
        elements["最高溫度"]?.objects()?.forEach { t -> half(t)?.max = t.value("MaxTemperature").measured() }
        elements["最低溫度"]?.objects()?.forEach { t -> half(t)?.min = t.value("MinTemperature").measured() }
        elements["12小時降雨機率"]?.objects()?.forEach { t -> half(t)?.pop = t.value("ProbabilityOfPrecipitation").asInt() }
        elements["天氣現象"]?.objects()?.forEach { t ->
            half(t)?.apply {
                code = t.value("WeatherCode").asInt()
                text = (t.value("Weather") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        return halves.values.groupBy { it.start.toLocalDate() }.map { (date, parts) ->
            val day = parts.firstOrNull { it.start.hour in 5..11 }
            val main = day ?: parts.first()
            CwaDaily(
                date = date,
                max = parts.mapNotNull { it.max }.maxOrNull(),
                min = parts.mapNotNull { it.min }.minOrNull(),
                hasDaytime = day != null,
                precipitationProbability = parts.mapNotNull { it.pop }.maxOrNull(),
                weatherCode = main.code,
                weather = main.text,
            )
        }
    }

    /** 解析天氣警特報（W-C0033-001），只回傳指定縣市、尚未結束的特報。 */
    fun parseAlerts(json: String, county: String, now: LocalDateTime): List<CwaAlert> {
        val locations = JSONObject(json).optJSONObject("cwaopendata")?.optJSONObject("dataset")?.opt("location")
            .asObjects()
        val target = normalizeCounty(county)
        val location = locations.firstOrNull { normalizeCounty(it.optString("locationName")) == target } ?: return emptyList()
        val hazards = location.optJSONObject("hazardConditions")?.opt("hazards").asObjects()
        return hazards.mapNotNull { h ->
            val info = h.optJSONObject("info") ?: return@mapNotNull null
            val phenomena = info.optString("phenomena").trim()
            if (phenomena.isEmpty()) return@mapNotNull null
            CwaAlert(
                phenomena = phenomena,
                significance = info.optString("significance").trim(),
                start = parseTime(h.path("validTime", "startTime")),
                end = parseTime(h.path("validTime", "endTime")),
            )
        }.filter { it.end == null || it.end.isAfter(now) }
    }

    fun normalizeCounty(name: String) = name.trim().replace('台', '臺')

    /** 各縣市鄉鎮 3 天預報的資料集編號；一週預報為編號 + 2。 */
    private val countyForecastNumbers = mapOf(
        "宜蘭縣" to 1, "桃園市" to 5, "新竹縣" to 9, "苗栗縣" to 13, "彰化縣" to 17, "南投縣" to 21,
        "雲林縣" to 25, "嘉義縣" to 29, "屏東縣" to 33, "臺東縣" to 37, "花蓮縣" to 41, "澎湖縣" to 45,
        "基隆市" to 49, "新竹市" to 53, "嘉義市" to 57, "臺北市" to 61, "高雄市" to 65, "新北市" to 69,
        "臺中市" to 73, "臺南市" to 77, "連江縣" to 81, "金門縣" to 85,
    )

    fun threeDayForecastId(county: String): String? =
        countyForecastNumbers[normalizeCounty(county)]?.let { "F-D0047-%03d".format(it) }

    fun weeklyForecastId(county: String): String? =
        countyForecastNumbers[normalizeCounty(county)]?.let { "F-D0047-%03d".format(it + 2) }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(a))
    }

    // ---------- JSON helpers ----------

    /** 建立鄉鎮預報檔的索引；格式不對時為 null */
    fun indexTownships(json: String): TownshipIndex? {
        val dataset = JSONObject(json).optJSONObject("cwaopendata")?.let { it.optJSONObject("Dataset") ?: it.optJSONObject("dataset") }
            ?: return null
        val group = dataset.opt("Locations").asObjects().firstOrNull() ?: return null
        val towns = group.opt("Location").asObjects().map { loc ->
            TownshipIndex.Town(
                name = loc.optString("LocationName"),
                latitude = loc.opt("Latitude").asDouble(),
                longitude = loc.opt("Longitude").asDouble(),
                json = loc.toString(),
            )
        }
        return TownshipIndex(group.optString("LocationsName"), towns)
    }

    private fun nearestTownship(index: TownshipIndex, latitude: Double, longitude: Double, preferredName: String?): Pair<String, JSONObject>? {
        val towns = index.towns
        val chosen = preferredName?.let { name -> towns.firstOrNull { it.name == name } }
            ?: towns.minByOrNull { town ->
                val lat = town.latitude ?: return@minByOrNull Double.MAX_VALUE
                val lon = town.longitude ?: return@minByOrNull Double.MAX_VALUE
                distanceKm(latitude, longitude, lat, lon)
            }
            ?: return null
        return index.county to JSONObject(chosen.json)
    }

    private fun JSONObject.elements(): Map<String, JSONArray> =
        opt("WeatherElement").asObjects().associate { it.optString("ElementName") to (it.optJSONArray("Time") ?: JSONArray()) }

    private fun JSONObject.value(key: String): Any? {
        val v = opt("ElementValue")
        val obj = when (v) {
            is JSONObject -> v
            is JSONArray -> v.optJSONObject(0)
            else -> null
        }
        return obj?.opt(key)
    }

    private fun JSONObject.path(vararg keys: String): Any? {
        var cur: Any? = this
        for (k in keys) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun Any?.asObjects(): List<JSONObject> = when (this) {
        is JSONArray -> objects()
        is JSONObject -> listOf(this)
        else -> emptyList()
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }

    private fun Any?.asInt(): Int? = asDouble()?.toInt()

    /** 氣象署以 -99、-999 等負值表示缺測。 */
    private fun Any?.measured(): Double? = asDouble()?.takeIf { it > -90 }

    private fun parseTime(value: Any?): LocalDateTime? {
        val s = (value as? String)?.trim()?.takeIf { it.length >= 16 } ?: return null
        return runCatching { OffsetDateTime.parse(s).toLocalDateTime() }
            .recoverCatching { LocalDateTime.parse(s) }
            .getOrNull()
    }
}

/** 氣象署天氣現象代碼／文字轉換為 WMO 代碼（App 內用來決定圖示與背景）。 */
object CwaCodes {
    fun toWmo(code: Int): Int = when (code) {
        1 -> 0
        2 -> 1
        3, 4 -> 2
        5, 6, 7 -> 3
        8, 9, 10, 19, 20, 29, 30, 31, 32, 38, 39 -> 80
        11, 12, 13 -> 61
        14 -> 63
        15, 16, 17, 18, 21, 22, 33, 34, 35, 36, 41 -> 95
        23, 37 -> 71
        24, 25, 26, 27, 28 -> 45
        42 -> 73
        else -> 3
    }

    fun fromText(text: String): Int? = when {
        "雷" in text -> 95
        "雪" in text -> 71
        "陣雨" in text -> 80
        "雨" in text -> 61
        "霧" in text || "霾" in text || "靄" in text -> 45
        "陰" in text -> 3
        "多雲" in text -> 2
        "晴" in text -> 0
        else -> null
    }
}
