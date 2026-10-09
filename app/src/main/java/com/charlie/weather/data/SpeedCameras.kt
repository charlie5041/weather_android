package com.charlie.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** 固定式測速照相（警政署「測速執法設置點」）。 */
data class SpeedCamera(
    val position: LatLon,
    /** 設置地點（例如「臺北市內湖區成功路四段」） */
    val address: String,
    /** 速限（km/h）；資料沒有時為 null */
    val limit: Int?,
    /** 拍攝方向的原文（例如「南向北」「雙向」） */
    val direction: String?,
)

/** 路線上會經過的測速照相；[fraction] 是在路線上的位置（0 是起點、1 是終點）。 */
data class RouteCamera(val camera: SpeedCamera, val fraction: Double, val distanceKm: Double)

/**
 * 警政署開放資料「測速執法設置點」（政府資料開放平臺 7320），全國約 2,500 筆。
 * 下載後存在 [dir]，7 天更新一次；下載失敗時沿用舊檔。資料不定期更新，與現場可能不同。
 */
class SpeedCameraRepository(private val dir: File) {
    private val lock = Mutex()
    private var memory: List<SpeedCamera>? = null

    suspend fun cameras(): List<SpeedCamera> = lock.withLock {
        val file = File(dir, FILE_NAME)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS
        memory?.takeIf { fresh }?.let { return@withLock it }
        if (!fresh) {
            try {
                val cameras = download()
                if (cameras.isNotEmpty()) {
                    withContext(Dispatchers.IO) { file.writeText(toJson(cameras)) }
                    memory = cameras
                    return@withLock cameras
                }
            } catch (e: IOException) {
                // 沿用舊檔
            } catch (e: org.json.JSONException) {
                // 沿用舊檔
            }
        }
        memory ?: withContext(Dispatchers.IO) {
            runCatching { fromJson(file.readText()) }.getOrDefault(emptyList())
        }.also { if (it.isNotEmpty()) memory = it }
    }

    /** 分頁下載；伺服器不理會分頁參數時，重複的資料會被排除，沒有新資料就停止 */
    private suspend fun download(): List<SpeedCamera> {
        val all = LinkedHashMap<Triple<Double, Double, String?>, SpeedCamera>()
        for (page in 0 until MAX_PAGES) {
            val (cameras, rows) = parse(get("$URL?limit=$PAGE_SIZE&offset=${page * PAGE_SIZE}"))
            val before = all.size
            cameras.forEach { all.putIfAbsent(Triple(it.position.latitude, it.position.longitude, it.direction), it) }
            if (rows < PAGE_SIZE || all.size == before) break
        }
        return all.values.toList()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val URL = "https://od.moi.gov.tw/api/v1/rest/datastore/A01010000C-000674-011"
        private const val FILE_NAME = "speed_cameras.json"
        private const val REFRESH_MS = 7 * 24 * 60 * 60_000L
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 10

        /** 測速照相離路線多近才算在路上（公里）；資料座標有誤差，但太寬會算到旁邊的平行道路 */
        const val NEAR_KM = 0.06

        /** 路線方向與拍攝方向相差超過這個角度就不是這個方向的照相 */
        private const val MAX_ANGLE = 75.0

        /**
         * 解析警政署的資料：記錄在 result.records（也接受 records 或直接是陣列），
         * 欄位 Latitude、Longitude、Address、limit、direct 等多為字串。
         * 第一筆常是欄位說明，經緯度不是數字或不在台灣附近的會略過。
         * 回傳測速照相與原始筆數（判斷是否還有下一頁）。
         */
        fun parse(json: String): Pair<List<SpeedCamera>, Int> {
            val trimmed = json.trimStart()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONObject("result")?.optJSONArray("records") ?: root.optJSONArray("records") ?: return emptyList<SpeedCamera>() to 0
            }
            val cameras = (0 until array.length()).mapNotNull { i ->
                val r = array.optJSONObject(i) ?: return@mapNotNull null
                fun field(name: String): String? {
                    val key = r.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
                    return r.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
                }
                val lat = field("Latitude")?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = field("Longitude")?.toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in 21.5..26.6 || lon !in 118.0..122.6) return@mapNotNull null
                val address = field("Address")
                    ?: listOfNotNull(field("CityName"), field("RegionName")).joinToString("").ifEmpty { "測速照相" }
                SpeedCamera(
                    position = LatLon(lat, lon),
                    address = address,
                    limit = field("limit")?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }?.takeIf { it in 5..130 },
                    direction = field("direct"),
                )
            }
            return cameras to array.length()
        }

        fun toJson(cameras: List<SpeedCamera>): String = JSONArray().apply {
            cameras.forEach { c ->
                put(
                    JSONObject()
                        .put("Latitude", c.position.latitude.toString())
                        .put("Longitude", c.position.longitude.toString())
                        .put("Address", c.address)
                        .put("limit", c.limit?.toString() ?: "")
                        .put("direct", c.direction ?: ""),
                )
            }
        }.toString()

        fun fromJson(json: String): List<SpeedCamera> = parse(json).first

        /**
         * 拍攝方向換成行進方位角（0 北、90 東）：「南向北」「往北」「北上」「北向」都是往北；
         * 「雙向」或看不懂的為 null（任何方向都算）。
         */
        fun heading(direction: String?): Double? {
            val text = direction?.replace(" ", "") ?: return null
            if (text.contains("雙向") || Regex("(東西|南北)向").containsMatchIn(text)) return null
            val compass = "東北|東南|西北|西南|北|南|東|西"
            val word = Regex("""(?:$compass)向($compass)""").find(text)?.groupValues?.get(1)
                ?: Regex("""往($compass)""").find(text)?.groupValues?.get(1)
                ?: Regex("""($compass)(?:向|上|下|行)""").find(text)?.groupValues?.get(1)
                ?: return null
            return when (word) {
                "北" -> 0.0
                "東北" -> 45.0
                "東" -> 90.0
                "東南" -> 135.0
                "南" -> 180.0
                "西南" -> 225.0
                "西" -> 270.0
                "西北" -> 315.0
                else -> null
            }
        }

        /** 從 a 到 b 的方位角（0 北、90 東） */
        fun bearing(a: LatLon, b: LatLon): Double {
            val dx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
            val dy = b.latitude - a.latitude
            return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
        }

        fun angleBetween(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        /**
         * 路線會經過的測速照相，依經過順序排列：離路線 [NEAR_KM] 內、拍攝方向與路線方向相符。
         * 同一個地方同方向的重複資料只留一筆。
         */
        fun along(cameras: List<SpeedCamera>, path: RoutePath, km: Double = NEAR_KM): List<RouteCamera> {
            val pts = path.points
            if (pts.size < 2 || cameras.isEmpty()) return emptyList()
            val pad = km / 100.0
            val minLat = pts.minOf { it.latitude } - pad
            val maxLat = pts.maxOf { it.latitude } + pad
            val minLon = pts.minOf { it.longitude } - pad
            val maxLon = pts.maxOf { it.longitude } + pad
            // 各頂點從起點算起的距離，與 RideTracker.progress 用同樣的近似
            val cumulative = DoubleArray(pts.size)
            for (i in 1 until pts.size) cumulative[i] = cumulative[i - 1] + segmentKm(pts[i - 1], pts[i])
            val total = cumulative.last().takeIf { it > 0 } ?: return emptyList()
            val found = cameras.mapNotNull { camera ->
                val p = camera.position
                if (p.latitude !in minLat..maxLat || p.longitude !in minLon..maxLon) return@mapNotNull null
                val heading = heading(camera.direction)
                var best = Double.MAX_VALUE
                var bestAt = 0.0
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val d = TdxRoadEvents.distanceToSegmentKm(p, a, b)
                    if (d > km || d >= best) continue
                    if (heading != null && a != b && angleBetween(heading, bearing(a, b)) > MAX_ANGLE) continue
                    best = d
                    bestAt = cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * projection(p, a, b)
                }
                if (best == Double.MAX_VALUE) return@mapNotNull null
                val fraction = (bestAt / total).coerceIn(0.0, 1.0)
                RouteCamera(camera, fraction, path.distanceKm * fraction)
            }.sortedBy { it.fraction }
            // 同一處（路線上 30 公尺內）的重複資料只留一筆
            val result = mutableListOf<RouteCamera>()
            found.forEach { c ->
                val last = result.lastOrNull()
                if (last == null || (c.fraction - last.fraction) * total > 0.03) result += c
            }
            return result
        }

        /** p 投影在線段 a–b 上的位置（0 是 a、1 是 b） */
        private fun projection(p: LatLon, a: LatLon, b: LatLon): Double {
            val kx = 111.32 * cos(Math.toRadians(p.latitude))
            val ky = 110.57
            val ax = (a.longitude - p.longitude) * kx
            val ay = (a.latitude - p.latitude) * ky
            val dx = (b.longitude - a.longitude) * kx
            val dy = (b.latitude - a.latitude) * ky
            val len2 = dx * dx + dy * dy
            return if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        }

        private fun segmentKm(a: LatLon, b: LatLon): Double {
            val kx = 111.32 * cos(Math.toRadians(a.latitude))
            val dx = (b.longitude - a.longitude) * kx
            val dy = (b.latitude - a.latitude) * 110.57
            return sqrt(dx * dx + dy * dy)
        }
    }
}
