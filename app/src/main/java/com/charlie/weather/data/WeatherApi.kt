package com.charlie.weather.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Open-Meteo：免費、免金鑰，預設 best_match 會依地區自動挑選解析度最高的數值模式
 * （例如台灣附近會混用 JMA、ECMWF、GFS 等高解析模式），精準度通常比一般內建天氣好。
 */
object WeatherApi {
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val AIR_QUALITY_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    /** 沿途預報每個請求的地點數 */
    private const val CHUNK = 40
    private const val RETRY_MS = 800L

    private const val CURRENT_VARS = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
        "precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m," +
        "wind_gusts_10m,visibility,dew_point_2m,uv_index"
    private const val HOURLY_VARS = "temperature_2m,weather_code,precipitation_probability,precipitation,is_day," +
        "relative_humidity_2m,apparent_temperature,wind_speed_10m,wind_gusts_10m,uv_index"
    private const val DAILY_VARS = "weather_code,temperature_2m_max,temperature_2m_min," +
        "precipitation_probability_max,precipitation_sum,sunrise,sunset,uv_index_max"

    private fun coord(value: Double) = String.format(Locale.US, "%.4f", value)

    suspend fun fetchForecastJson(latitude: Double, longitude: Double): String = get(
        "$FORECAST_URL?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&current=$CURRENT_VARS&hourly=$HOURLY_VARS&daily=$DAILY_VARS" +
            "&timezone=auto&forecast_days=10&wind_speed_unit=kmh"
    )

    /**
     * 一次查詢多個地點（路線沿途），回傳與 [points] 順序相同、可直接給 [parse] 的 JSON。
     * 只取 [days] 天，減少下載量。
     */
    suspend fun fetchForecastJsons(points: List<LatLon>, days: Int = 3): List<String> {
        if (points.isEmpty()) return emptyList()
        val text = get(
            "$FORECAST_URL?latitude=${points.joinToString(",") { coord(it.latitude) }}" +
                "&longitude=${points.joinToString(",") { coord(it.longitude) }}" +
                "&current=$CURRENT_VARS&hourly=$HOURLY_VARS&daily=$DAILY_VARS" +
                "&timezone=auto&forecast_days=$days&wind_speed_unit=kmh"
        )
        // 單一地點時回傳物件，多個地點時回傳陣列
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("[")) return listOf(text)
        val arr = JSONArray(trimmed)
        return (0 until arr.length()).map { arr.getJSONObject(it).toString() }
    }

    /**
     * 沿途多點的預報：每 [CHUNK] 個地點一個請求並行查詢，失敗的批次等一下再試一次；
     * 仍然失敗的批次對應的位置為 null，只讓那幾個點「暫無預報」，不讓整條路線失敗。
     * 全部失敗時才拋出 [IOException]。
     */
    suspend fun fetchForecastJsonsChunked(points: List<LatLon>, days: Int = 3): List<String?> = coroutineScope {
        if (points.isEmpty()) return@coroutineScope emptyList()
        val chunks = points.chunked(CHUNK)
        val results = chunks.map { chunk -> async { fetchWithRetry(chunk, days) } }.map { it.await() }
        if (results.all { it == null }) throw IOException("無法取得沿途預報")
        results.flatMapIndexed { i, jsons -> jsons ?: List(chunks[i].size) { null } }
    }

    private suspend fun fetchWithRetry(chunk: List<LatLon>, days: Int): List<String>? {
        repeat(2) { attempt ->
            try {
                return fetchForecastJsons(chunk, days).takeIf { it.size == chunk.size }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt == 0) delay(RETRY_MS)
            } catch (e: org.json.JSONException) {
                if (attempt == 0) delay(RETRY_MS)
            }
        }
        return null
    }

    suspend fun fetchAirQualityJson(latitude: Double, longitude: Double): String? = try {
        get("$AIR_QUALITY_URL?latitude=${coord(latitude)}&longitude=${coord(longitude)}&current=us_aqi,pm2_5,pm10&timezone=auto")
    } catch (e: IOException) {
        null
    }

    suspend fun searchCities(query: String): List<City> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(get("$GEOCODING_URL?name=$q&count=15&language=zh&format=json"))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            City(
                id = "geo_${r.optLong("id")}",
                name = r.optString("name"),
                subtitle = listOf(r.optString("admin1"), r.optString("country"))
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("，"),
                latitude = r.getDouble("latitude"),
                longitude = r.getDouble("longitude"),
            )
        }
    }

    fun parse(forecastJson: String, airQualityJson: String?, fetchedAtMillis: Long): Weather {
        val root = JSONObject(forecastJson)
        val c = root.getJSONObject("current")
        val current = CurrentConditions(
            time = LocalDateTime.parse(c.getString("time")),
            temperature = c.dbl("temperature_2m"),
            apparentTemperature = c.dbl("apparent_temperature"),
            humidity = c.optInt("relative_humidity_2m"),
            dewPoint = c.dbl("dew_point_2m"),
            isDay = c.optInt("is_day", 1) == 1,
            precipitation = c.dbl("precipitation"),
            weatherCode = c.optInt("weather_code"),
            cloudCover = c.optInt("cloud_cover"),
            pressure = c.dbl("pressure_msl"),
            windSpeed = c.dbl("wind_speed_10m"),
            windDirection = c.optInt("wind_direction_10m"),
            windGusts = c.dbl("wind_gusts_10m"),
            visibility = c.dbl("visibility"),
            uvIndex = c.dbl("uv_index"),
        )

        val h = root.getJSONObject("hourly")
        val hTime = h.getJSONArray("time")
        val hTemp = h.getJSONArray("temperature_2m")
        val hCode = h.getJSONArray("weather_code")
        val hPop = h.getJSONArray("precipitation_probability")
        val hPrecip = h.getJSONArray("precipitation")
        val hDay = h.getJSONArray("is_day")
        val hHumidity = h.optJSONArray("relative_humidity_2m") ?: JSONArray()
        val hApparent = h.optJSONArray("apparent_temperature") ?: JSONArray()
        val hWind = h.optJSONArray("wind_speed_10m") ?: JSONArray()
        val hGusts = h.optJSONArray("wind_gusts_10m") ?: JSONArray()
        val hUv = h.optJSONArray("uv_index") ?: JSONArray()
        val hourly = (0 until hTime.length()).mapNotNull { i ->
            if (hTemp.isNull(i)) return@mapNotNull null
            HourlyForecast(
                time = LocalDateTime.parse(hTime.getString(i)),
                temperature = hTemp.getDouble(i),
                weatherCode = hCode.intOr(i, 0),
                precipitationProbability = hPop.intOrNull(i),
                precipitation = hPrecip.dbl(i).takeUnless { it.isNaN() } ?: 0.0,
                isDay = hDay.intOr(i, 1) == 1,
                humidity = hHumidity.intOrNull(i),
                apparentTemperature = hApparent.dbl(i),
                windSpeed = hWind.dbl(i),
                windGusts = hGusts.dbl(i),
                uvIndex = hUv.dbl(i),
            )
        }

        val d = root.getJSONObject("daily")
        val dTime = d.getJSONArray("time")
        val dCode = d.getJSONArray("weather_code")
        val dMax = d.getJSONArray("temperature_2m_max")
        val dMin = d.getJSONArray("temperature_2m_min")
        val dPop = d.getJSONArray("precipitation_probability_max")
        val dSum = d.getJSONArray("precipitation_sum")
        val dRise = d.getJSONArray("sunrise")
        val dSet = d.getJSONArray("sunset")
        val dUv = d.getJSONArray("uv_index_max")
        val daily = (0 until dTime.length()).mapNotNull { i ->
            if (dMax.isNull(i) || dMin.isNull(i)) return@mapNotNull null
            DailyForecast(
                date = LocalDate.parse(dTime.getString(i)),
                weatherCode = dCode.intOr(i, 0),
                temperatureMax = dMax.getDouble(i),
                temperatureMin = dMin.getDouble(i),
                precipitationProbability = dPop.intOrNull(i),
                precipitationSum = dSum.dbl(i).takeUnless { it.isNaN() } ?: 0.0,
                sunrise = dRise.timeOrNull(i),
                sunset = dSet.timeOrNull(i),
                uvIndexMax = dUv.dbl(i),
            )
        }

        val airQuality = airQualityJson?.let { json ->
            runCatching {
                val aq = JSONObject(json).getJSONObject("current")
                if (aq.isNull("us_aqi")) null else AirQuality(
                    usAqi = aq.getInt("us_aqi"),
                    pm25 = aq.dbl("pm2_5").takeUnless { it.isNaN() },
                    pm10 = aq.dbl("pm10").takeUnless { it.isNaN() },
                )
            }.getOrNull()
        }

        return Weather(
            current = current,
            hourly = hourly,
            daily = daily,
            utcOffsetSeconds = root.optInt("utc_offset_seconds"),
            airQuality = airQuality,
            fetchedAtMillis = fetchedAtMillis,
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.dbl(key: String): Double = if (isNull(key)) Double.NaN else optDouble(key)
    private fun JSONArray.dbl(i: Int): Double = if (isNull(i)) Double.NaN else optDouble(i)
    private fun JSONArray.intOrNull(i: Int): Int? = if (isNull(i)) null else optInt(i)
    private fun JSONArray.intOr(i: Int, fallback: Int): Int = if (isNull(i)) fallback else optInt(i)
    private fun JSONArray.timeOrNull(i: Int): LocalDateTime? =
        if (isNull(i)) null else runCatching { LocalDateTime.parse(getString(i)) }.getOrNull()
}
