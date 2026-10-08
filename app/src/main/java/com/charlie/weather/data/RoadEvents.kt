package com.charlie.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sqrt

/** 道路事件（施工、事故、封閉、壅塞…），來自交通部 TDX。 */
data class RoadEvent(
    /** 簡短類別，例如「施工」「事故」「壅塞」 */
    val title: String,
    val description: String,
    /** 位置說明（例如「國道1號北向 25K」） */
    val location: String?,
    val positions: List<LatLon>,
    val end: LocalDateTime? = null,
)

/**
 * 交通部 TDX 運輸資料流通服務的即時道路事件（國道與省道）。需要 TDX 會員的 Client Id／Secret
 * （GitHub Secret TDX_CLIENT_ID、TDX_CLIENT_SECRET）；沒有或查詢失敗時沒有事件。
 * 結果在記憶體快取 5 分鐘。
 */
class TdxRoadEvents(private val clientId: String, private val clientSecret: String) {
    private val lock = Mutex()
    private var token: Pair<String, Long>? = null
    private var cache: Pair<Long, List<RoadEvent>>? = null

    suspend fun events(): List<RoadEvent> = lock.withLock {
        cache?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return@withLock it.second }
        val bearer = try {
            accessToken()
        } catch (e: IOException) {
            return@withLock cache?.second.orEmpty()
        } catch (e: org.json.JSONException) {
            return@withLock cache?.second.orEmpty()
        }
        val events = coroutineScope {
            EVENT_URLS.map { url ->
                async {
                    try {
                        parse(get(url, bearer))
                    } catch (e: IOException) {
                        emptyList()
                    } catch (e: org.json.JSONException) {
                        emptyList()
                    }
                }
            }.flatMap { it.await() }
        }
        cache = System.currentTimeMillis() to events
        events
    }

    private suspend fun accessToken(): String {
        token?.takeIf { System.currentTimeMillis() < it.second }?.let { return it.first }
        val body = "grant_type=client_credentials&client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
            "&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}"
        val json = JSONObject(post(TOKEN_URL, body))
        val value = json.getString("access_token")
        // 提早一分鐘換新的
        val expires = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000
        token = value to expires
        return value
    }

    private suspend fun post(url: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun get(url: String, bearer: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CACHE_MS = 5 * 60_000L
        private const val TOKEN_URL = "https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token"
        private const val BASE = "https://tdx.transportdata.tw/api/basic/v1/Traffic/RoadEvent/LiveEvent"

        /** 國道與省道的即時事件 */
        private val EVENT_URLS = listOf("$BASE/Freeway?\$format=JSON", "$BASE/Highway?\$format=JSON")

        /** 事件離路線多近才算「沿路」（公里） */
        const val NEAR_KM = 0.5

        /**
         * 解析即時事件。事件陣列在 LiveEvents 之下（也接受直接是陣列）；
         * 位置是 WKT 字串 Positions（例如 "POINT (121.5 25.0)"），或 Position 物件的經緯度。
         */
        fun parse(json: String, zone: ZoneId = ZoneId.systemDefault()): List<RoadEvent> {
            val trimmed = json.trimStart()
            val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("LiveEvents") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val e = array.optJSONObject(i) ?: return@mapNotNull null
                val positions = wkt(e.optString("Positions")).ifEmpty {
                    e.optJSONObject("Position")?.let { p ->
                        val lat = p.optDouble("PositionLat")
                        val lon = p.optDouble("PositionLon")
                        if (lat.isNaN() || lon.isNaN()) emptyList() else listOf(LatLon(lat, lon))
                    }.orEmpty()
                }
                if (positions.isEmpty()) return@mapNotNull null
                val description = e.optString("Description").trim()
                val title = e.optString("EventTitle").trim().ifEmpty { "道路事件" }
                RoadEvent(
                    title = title,
                    description = description.ifEmpty { title },
                    location = e.optJSONObject("Location")?.optString("Other")?.trim()?.takeIf { it.isNotEmpty() },
                    positions = positions,
                    end = e.optString("EndTime").takeIf { it.isNotBlank() }?.let {
                        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
                    },
                )
            }
        }

        /** WKT（POINT、LINESTRING、MULTIPOINT…）中的所有「經度 緯度」座標 */
        fun wkt(text: String): List<LatLon> =
            Regex("""(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)""").findAll(text)
                .mapNotNull { m ->
                    val lon = m.groupValues[1].toDoubleOrNull()
                    val lat = m.groupValues[2].toDoubleOrNull()
                    if (lon == null || lat == null || lat !in -90.0..90.0) null else LatLon(lat, lon)
                }.toList()

        /** 位置在路線 [NEAR_KM] 公里內的事件 */
        fun near(events: List<RoadEvent>, path: List<LatLon>, km: Double = NEAR_KM): List<RoadEvent> {
            if (path.isEmpty()) return emptyList()
            // 先用外框排除遠的事件，再逐點比距離
            val pad = km / 100.0
            val minLat = path.minOf { it.latitude } - pad
            val maxLat = path.maxOf { it.latitude } + pad
            val minLon = path.minOf { it.longitude } - pad
            val maxLon = path.maxOf { it.longitude } + pad
            val segments = if (path.size == 1) listOf(path[0] to path[0]) else path.zipWithNext()
            return events.filter { event ->
                event.positions.any { p ->
                    p.latitude in minLat..maxLat && p.longitude in minLon..maxLon &&
                        segments.any { (a, b) -> distanceToSegmentKm(p, a, b) <= km }
                }
            }
        }

        /** 點到線段的距離（公里）；短距離用等距投影近似即可 */
        fun distanceToSegmentKm(p: LatLon, a: LatLon, b: LatLon): Double {
            val kx = 111.32 * cos(Math.toRadians(p.latitude))
            val ky = 110.57
            val ax = (a.longitude - p.longitude) * kx
            val ay = (a.latitude - p.latitude) * ky
            val dx = (b.longitude - a.longitude) * kx
            val dy = (b.latitude - a.latitude) * ky
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            return sqrt(x * x + y * y)
        }
    }
}
