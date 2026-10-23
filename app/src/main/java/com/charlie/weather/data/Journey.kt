package com.charlie.weather.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 軌跡上的一個定位點；[time] 是 epoch 毫秒，[accuracy] 是定位精度（公尺）。 */
data class TrackPoint(val time: Long, val latitude: Double, val longitude: Double, val accuracy: Float? = null) {
    val position get() = LatLon(latitude, longitude)
}

/**
 * 足跡裡的一張照片：檔案在 journeys/photos/<足跡 id>/<id>.jpg（縮圖 <id>_t.jpg）。
 * [takenAt] 是拍攝時間（epoch 毫秒），[position] 是放在路線上的位置；都沒有時為「未定位」。
 */
data class JourneyPhoto(val id: String, val takenAt: Long?, val position: LatLon?)

/**
 * 騎乘中記下的當地天氣（沿用騎乘中模式本來就會查的預報與雷達，不另外查）。
 * [rainMm] 是雷達預估或逐時預報的 1 小時雨量，[place] 是鄉鎮名稱。
 */
data class WeatherSample(
    val time: Long,
    val position: LatLon,
    val temperature: Double?,
    val weatherCode: Int?,
    val rainMm: Double?,
    val wet: Boolean,
    val place: String? = null,
)

/** 足跡時間軸上的一件事 */
sealed class JourneyEvent(open val time: Long) {
    data class Start(override val time: Long, val weather: WeatherSample?) : JourneyEvent(time)
    data class Photo(override val time: Long, val photo: JourneyPhoto) : JourneyEvent(time)
    data class RainStarts(override val time: Long, val sample: WeatherSample) : JourneyEvent(time)
    data class RainStops(override val time: Long, val sample: WeatherSample) : JourneyEvent(time)
    data class End(override val time: Long, val weather: WeatherSample?) : JourneyEvent(time)
}

/** 一趟騎乘的足跡：[points] 是精簡後用來畫地圖的軌跡。 */
data class Journey(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Double,
    val points: List<TrackPoint>,
    /** 起點與終點的行政區（例如中和區）；查不到時為 null */
    val fromArea: String? = null,
    val toArea: String? = null,
    /** 使用者自己取的名稱 */
    val name: String? = null,
    val photos: List<JourneyPhoto> = emptyList(),
    /** 騎乘中模式才有（行車提醒沒有查天氣） */
    val weather: List<WeatherSample> = emptyList(),
    /** 中途暫停的總時間（毫秒），不算進時間與平均時速 */
    val pausedMs: Long = 0,
    /** 累計爬升（公尺）；GPS 沒有高度或程序被結束後補存的足跡為 null */
    val climbM: Double? = null,
) {
    /** 途中有遇到雨 */
    val metRain: Boolean get() = weather.any { it.wet }

    /** 淋雨的時間（分鐘）：下雨的取樣到下一個取樣之間都算 */
    val rainMinutes: Long
        get() = weather.indices.filter { weather[it].wet }.sumOf { i ->
            val next = weather.getOrNull(i + 1)?.time ?: endTime
            ((next - weather[i].time) / 60_000).coerceAtLeast(0)
        }

    /** 出發、照片、開始下雨與雨停、抵達，依時間排序；沒有拍攝時間的照片不列 */
    val timeline: List<JourneyEvent>
        get() {
            val events = mutableListOf<JourneyEvent>(JourneyEvent.Start(startTime, weather.firstOrNull()))
            photos.forEach { ph -> ph.takenAt?.let { events += JourneyEvent.Photo(it.coerceIn(startTime, endTime), ph) } }
            var wet = false
            weather.forEach { w ->
                if (w.wet != wet) events += if (w.wet) JourneyEvent.RainStarts(w.time, w) else JourneyEvent.RainStops(w.time, w)
                wet = w.wet
            }
            events += JourneyEvent.End(endTime, weather.lastOrNull())
            // 同時間時出發排最前、抵達排最後
            return events.sortedWith(compareBy<JourneyEvent>({ it.time }, { if (it is JourneyEvent.Start) 0 else if (it is JourneyEvent.End) 2 else 1 }))
        }

    /** 扣掉暫停的時間（毫秒） */
    val movingMs: Long get() = (endTime - startTime - pausedMs).coerceAtLeast(0)

    val durationMinutes: Long get() = movingMs / 60_000

    /** 平均時速（公里）：扣掉暫停，不扣掉沒暫停的停等 */
    val averageKmh: Double get() = if (movingMs > 0) distanceKm / (movingMs / 3_600_000.0) else 0.0

    /** 例：中和區 → 內湖區；有自訂名稱時用名稱 */
    val title: String
        get() = name?.takeIf { it.isNotBlank() } ?: when {
            fromArea != null && toArea != null && fromArea != toArea -> "$fromArea → $toArea"
            fromArea != null -> "${fromArea}一帶"
            toArea != null -> "${toArea}一帶"
            else -> "騎乘紀錄"
        }
}

/** 軌跡的計算：過濾不可靠的定位點、計算距離、精簡點數。 */
object Track {
    /** 精度比這個差（公尺）的點不記 */
    const val MAX_ACCURACY_M = 30f

    /** 和上一點換算的時速超過這個值視為跳點 */
    const val MAX_KMH = 200.0

    /** 少於這個距離（公里）或時間的紀錄不存，避免誤觸 */
    const val MIN_KM = 0.5
    const val MIN_MS = 3 * 60_000L

    /** 精簡軌跡時允許偏離原本路線的距離（公尺） */
    const val SIMPLIFY_M = 8.0

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distanceKm(points: List<TrackPoint>): Double =
        (1 until points.size).sumOf { distanceKm(points[it - 1].position, points[it].position) }

    /** 這個點是否該記下來：精度夠，且離上一個記下的點不是瞬移 */
    fun accept(previous: TrackPoint?, point: TrackPoint): Boolean {
        if (point.accuracy != null && point.accuracy > MAX_ACCURACY_M) return false
        previous ?: return true
        if (point.time <= previous.time) return false
        val hours = (point.time - previous.time) / 3_600_000.0
        return distanceKm(previous.position, point.position) / hours <= MAX_KMH
    }

    /** 依序過濾整串原始點（程序重啟後讀回的檔案也可能有不可靠的點） */
    fun clean(points: List<TrackPoint>): List<TrackPoint> {
        val result = ArrayList<TrackPoint>(points.size)
        points.forEach { if (accept(result.lastOrNull(), it)) result += it }
        return result
    }

    /** Douglas-Peucker：保留轉彎處，直線上的點只留頭尾 */
    fun simplify(points: List<TrackPoint>, toleranceM: Double = SIMPLIFY_M): List<TrackPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var worst = -1
            var worstM = 0.0
            for (i in start + 1 until end) {
                val d = offsetM(points[i].position, points[start].position, points[end].position)
                if (d > worstM) {
                    worstM = d
                    worst = i
                }
            }
            if (worst >= 0 && worstM > toleranceM) {
                keep[worst] = true
                stack.addLast(start to worst)
                stack.addLast(worst to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** 點 [p] 到線段 a–b 的距離（公尺），以 a 為原點的平面近似 */
    private fun offsetM(p: LatLon, a: LatLon, b: LatLon): Double {
        val kx = 111_320.0 * cos(Math.toRadians(a.latitude))
        val ky = 110_570.0
        val px = (p.longitude - a.longitude) * kx
        val py = (p.latitude - a.latitude) * ky
        val bx = (b.longitude - a.longitude) * kx
        val by = (b.latitude - a.latitude) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /** 記錄中的里程與時間（扣掉暫停）是否還太短，結束後不會存 */
    fun tooShort(km: Double, movingMs: Long): Boolean = km < MIN_KM || movingMs < MIN_MS

    /** 整理原始點成一趟足跡；太短（誤觸）時回傳 null */
    fun build(id: String, raw: List<TrackPoint>): Journey? = build(id, listOf(raw))

    /**
     * 有暫停時軌跡分成幾段：距離只算各段內，段與段之間的時間算暫停。
     * 地圖仍把各段接起來畫（暫停期間移動的那段會是直線）。
     */
    @JvmName("buildSegments")
    fun build(id: String, segments: List<List<TrackPoint>>): Journey? {
        val parts = segments.map(::clean).filter { it.isNotEmpty() }
        val points = parts.flatten()
        if (points.size < 2) return null
        val distance = parts.sumOf { distanceKm(it) }
        val paused = (1 until parts.size).sumOf { (parts[it].first().time - parts[it - 1].last().time).coerceAtLeast(0) }
        if (tooShort(distance, points.last().time - points.first().time - paused)) return null
        return Journey(id, points.first().time, points.last().time, distance, parts.flatMap { simplify(it) }, pausedMs = paused)
    }

    /** 記錄檔中暫停的標記行 */
    const val PAUSE_LINE = "pause"

    /** 讀記錄檔：依暫停標記分段 */
    fun parseSegments(lines: List<String>): List<List<TrackPoint>> {
        val segments = mutableListOf(mutableListOf<TrackPoint>())
        lines.forEach { line ->
            if (line.trim() == PAUSE_LINE) {
                if (segments.last().isNotEmpty()) segments += mutableListOf<TrackPoint>()
            } else {
                decodePoint(line)?.let { segments.last() += it }
            }
        }
        return segments.filter { it.isNotEmpty() }
    }

    /** 拍攝時間在旅程前後這麼久以內，仍依時間放到起點或終點 */
    const val PHOTO_SLACK_MS = 10 * 60_000L

    /** 照片的 GPS 離路線這麼近（公里）才採用 */
    const val PHOTO_GPS_KM = 1.0

    /** 依時間在軌跡上內插出當時的位置；時間不在旅程期間時為 null */
    fun positionAt(points: List<TrackPoint>, time: Long): LatLon? {
        if (points.isEmpty()) return null
        if (time < points.first().time - PHOTO_SLACK_MS || time > points.last().time + PHOTO_SLACK_MS) return null
        if (time <= points.first().time) return points.first().position
        if (time >= points.last().time) return points.last().position
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].time <= time) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val t = if (b.time == a.time) 0.0 else (time - a.time).toDouble() / (b.time - a.time)
        return LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    /** 照片放在路線上的位置：先看拍攝時間，再看照片 GPS（要離路線夠近）；都不行時為 null */
    fun placePhoto(points: List<TrackPoint>, takenAt: Long?, gps: LatLon?): LatLon? {
        takenAt?.let { positionAt(points, it) }?.let { return it }
        if (gps == null || points.isEmpty()) return null
        return gps.takeIf { points.minOf { p -> distanceKm(p.position, gps) } <= PHOTO_GPS_KM }
    }

    /** 沿路線走到 [fraction]（0 起點、1 終點）的位置，手動調整照片位置用 */
    fun positionAtFraction(points: List<TrackPoint>, fraction: Double): LatLon? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first().position
        val target = distanceKm(points) * fraction.coerceIn(0.0, 1.0)
        var walked = 0.0
        for (i in 1 until points.size) {
            val len = distanceKm(points[i - 1].position, points[i].position)
            if (walked + len >= target) {
                val t = if (len == 0.0) 0.0 else (target - walked) / len
                val a = points[i - 1]
                val b = points[i]
                return LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
            }
            walked += len
        }
        return points.last().position
    }

    /** 位置在路線上的進度（0–1），取最近的頂點 */
    fun fractionOf(points: List<TrackPoint>, position: LatLon): Double {
        if (points.size < 2) return 0.0
        val total = distanceKm(points).takeIf { it > 0 } ?: return 0.0
        var walked = 0.0
        var best = Double.MAX_VALUE
        var bestAt = 0.0
        for (i in points.indices) {
            if (i > 0) walked += distanceKm(points[i - 1].position, points[i].position)
            val d = distanceKm(points[i].position, position)
            if (d < best) {
                best = d
                bestAt = walked
            }
        }
        return bestAt / total
    }

    private val exifTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    /** EXIF 的拍攝時間（例：2026:10:21 08:30:05，時區 +08:00）；沒有時區時用手機的時區 */
    fun parseExifTime(dateTime: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val local = dateTime?.trim()?.let { runCatching { LocalDateTime.parse(it, exifTime) }.getOrNull() } ?: return null
        val z = offset?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() } ?: zone
        return local.atZone(z).toInstant().toEpochMilli()
    }

    fun encodePoint(p: TrackPoint): String =
        "%d,%.6f,%.6f,%s".format(Locale.US, p.time, p.latitude, p.longitude, p.accuracy?.let { "%.0f".format(Locale.US, it) }.orEmpty())

    fun decodePoint(line: String): TrackPoint? {
        val parts = line.split(',')
        if (parts.size < 3) return null
        return TrackPoint(
            time = parts[0].toLongOrNull() ?: return null,
            latitude = parts[1].toDoubleOrNull() ?: return null,
            longitude = parts[2].toDoubleOrNull() ?: return null,
            accuracy = parts.getOrNull(3)?.toFloatOrNull(),
        )
    }

    fun toJson(j: Journey): JSONObject = JSONObject()
        .put("id", j.id)
        .put("start", j.startTime)
        .put("end", j.endTime)
        .put("km", j.distanceKm)
        .put("from", j.fromArea)
        .put("to", j.toArea)
        .put("name", j.name)
        .put("paused", j.pausedMs)
        .put("climb", j.climbM)
        .put(
            "points",
            JSONArray().apply {
                j.points.forEach { p -> put(JSONArray().put(p.time).put(p.latitude).put(p.longitude)) }
            },
        )
        .put(
            "weather",
            JSONArray().apply {
                j.weather.forEach { w ->
                    put(
                        JSONObject().put("t", w.time).put("lat", w.position.latitude).put("lon", w.position.longitude)
                            .put("temp", w.temperature).put("code", w.weatherCode).put("mm", w.rainMm)
                            .put("wet", w.wet).put("place", w.place),
                    )
                }
            },
        )
        .put(
            "photos",
            JSONArray().apply {
                j.photos.forEach { ph ->
                    put(
                        JSONObject().put("id", ph.id).put("taken", ph.takenAt)
                            .put("lat", ph.position?.latitude).put("lon", ph.position?.longitude),
                    )
                }
            },
        )

    fun fromJson(o: JSONObject): Journey {
        val arr = o.getJSONArray("points")
        val points = (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            TrackPoint(p.getLong(0), p.getDouble(1), p.getDouble(2))
        }
        val photoArr = o.optJSONArray("photos")
        val photos = (0 until (photoArr?.length() ?: 0)).map { i ->
            val ph = photoArr!!.getJSONObject(i)
            JourneyPhoto(
                id = ph.getString("id"),
                takenAt = if (ph.has("taken")) ph.getLong("taken") else null,
                position = if (ph.has("lat") && ph.has("lon")) LatLon(ph.getDouble("lat"), ph.getDouble("lon")) else null,
            )
        }
        val weatherArr = o.optJSONArray("weather")
        val weather = (0 until (weatherArr?.length() ?: 0)).map { i ->
            val w = weatherArr!!.getJSONObject(i)
            WeatherSample(
                time = w.getLong("t"),
                position = LatLon(w.getDouble("lat"), w.getDouble("lon")),
                temperature = if (w.has("temp")) w.getDouble("temp") else null,
                weatherCode = if (w.has("code")) w.getInt("code") else null,
                rainMm = if (w.has("mm")) w.getDouble("mm") else null,
                wet = w.optBoolean("wet"),
                place = if (w.has("place")) w.getString("place") else null,
            )
        }
        return Journey(
            id = o.getString("id"),
            startTime = o.getLong("start"),
            endTime = o.getLong("end"),
            distanceKm = o.getDouble("km"),
            points = points,
            fromArea = o.optString("from").takeIf { o.has("from") && !o.isNull("from") && it.isNotBlank() },
            toArea = o.optString("to").takeIf { o.has("to") && !o.isNull("to") && it.isNotBlank() },
            name = o.optString("name").takeIf { o.has("name") && !o.isNull("name") && it.isNotBlank() },
            photos = photos,
            weather = weather,
            pausedMs = o.optLong("paused", 0),
            climbM = if (o.has("climb") && !o.isNull("climb")) o.getDouble("climb") else null,
        )
    }
}

/**
 * 足跡存在 App 私有空間（filesDir/journeys），不上傳：
 * - 騎乘中的定位點逐批附加到 recording.csv，程序被系統結束也只少最後幾十秒
 * - 結束後整理成 `<id>.json`（精簡後的軌跡，畫地圖用），原始點改名為 `<id>.csv` 保留
 */
class JourneyStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "journeys").apply { mkdirs() }
    private val photoRoot = File(dir, "photos")
    private val recording = File(dir, "recording.csv")
    private val geocoder = AddressGeocoder(context.applicationContext)

    val isRecording: Boolean get() = recording.exists()

    /** 開始新的紀錄；上一趟沒正常結束（程序被結束）時先把它存起來 */
    suspend fun begin() = withContext(Dispatchers.IO) {
        if (recording.exists()) finishLocked()
        recording.writeText("")
    }

    fun append(points: List<TrackPoint>) {
        if (points.isEmpty()) return
        runCatching { recording.appendText(points.joinToString("") { Track.encodePoint(it) + "\n" }) }
    }

    /** 暫停：之後的點是新的一段，中間的時間與距離不算 */
    fun pause() {
        runCatching { recording.appendText(Track.PAUSE_LINE + "\n") }
    }

    /** 結束紀錄並存成一趟足跡（附上騎乘中記下的天氣與爬升）；太短時不存，回傳 null */
    suspend fun finish(weather: List<WeatherSample> = emptyList(), climbM: Double? = null): Journey? =
        withContext(Dispatchers.IO) { finishLocked(weather, climbM) }

    /** App 開啟時：沒有在騎乘卻留著紀錄檔（程序被結束），補存起來 */
    suspend fun recover(active: Boolean) = withContext(Dispatchers.IO) {
        if (!active && recording.exists()) finishLocked()
    }

    private suspend fun finishLocked(weather: List<WeatherSample> = emptyList(), climbM: Double? = null): Journey? {
        val segments = runCatching { Track.parseSegments(recording.readLines()) }.getOrDefault(emptyList())
        val id = segments.firstOrNull()?.firstOrNull()?.time?.toString() ?: System.currentTimeMillis().toString()
        val built = Track.build(id, segments)
        if (built == null) {
            recording.delete()
            return null
        }
        val from = geocoder.reverse(built.points.first().latitude, built.points.first().longitude)?.area
        val to = geocoder.reverse(built.points.last().latitude, built.points.last().longitude)?.area
        val journey = built.copy(
            fromArea = from,
            toArea = to,
            weather = weather.filter { it.time in built.startTime - 60_000..built.endTime + 60_000 },
            climbM = climbM,
        )
        File(dir, "$id.json").writeText(Track.toJson(journey).toString())
        if (!recording.renameTo(File(dir, "$id.csv"))) recording.delete()
        _changed.value++
        return journey
    }

    /** 全部足跡，新的在前 */
    suspend fun list(): List<Journey> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { Track.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.startTime }
    }

    suspend fun rename(id: String, name: String?) = withContext(Dispatchers.IO) {
        val file = File(dir, "$id.json")
        val journey = runCatching { Track.fromJson(JSONObject(file.readText())) }.getOrNull() ?: return@withContext
        file.writeText(Track.toJson(journey.copy(name = name?.trim()?.takeIf { it.isNotEmpty() })).toString())
        _changed.value++
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        File(dir, "$id.csv").delete()
        File(photoRoot, id).deleteRecursively()
        _changed.value++
    }

    /** 足跡（含照片）佔用的空間（位元組），不含記錄中的這一趟 */
    suspend fun usageBytes(): Long = withContext(Dispatchers.IO) {
        dir.walkTopDown().filter { it.isFile && it != recording }.sumOf { it.length() }
    }

    /** 清除全部足跡與照片（記錄中的這一趟不受影響） */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().filter { it != recording }.forEach { it.deleteRecursively() }
        _changed.value++
    }

    fun photoFile(journeyId: String, photoId: String, thumbnail: Boolean = false): File =
        File(File(photoRoot, journeyId), if (thumbnail) "${photoId}_t.jpg" else "$photoId.jpg")

    /** 原始軌跡（定位照片用，比畫地圖的精簡軌跡準）；沒有時用精簡軌跡 */
    private fun trackOf(journey: Journey): List<TrackPoint> =
        runCatching { File(dir, "${journey.id}.csv").readLines().mapNotNull(Track::decodePoint) }.getOrNull()
            ?.let(Track::clean)?.takeIf { it.size >= 2 } ?: journey.points

    private fun load(id: String): Journey? =
        runCatching { Track.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    private fun save(journey: Journey) {
        File(dir, "${journey.id}.json").writeText(Track.toJson(journey).toString())
        _changed.value++
    }

    /**
     * 從相簿挑的照片：讀 EXIF 的拍攝時間與 GPS 放到路線上，壓縮成長邊 [PHOTO_EDGE] 的 JPEG
     * 與方形縮圖複製到 App 裡（相簿刪掉照片後足跡仍在）。回傳加入的照片。
     */
    suspend fun addPhotos(id: String, uris: List<Uri>): List<JourneyPhoto> = withContext(Dispatchers.IO) {
        val journey = load(id) ?: return@withContext emptyList()
        val track = trackOf(journey)
        val added = uris.mapNotNull { uri ->
            runCatching {
                val exif = app.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                val takenAt = exif?.let {
                    Track.parseExifTime(
                        it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: it.getAttribute(ExifInterface.TAG_DATETIME),
                        it.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: it.getAttribute(ExifInterface.TAG_OFFSET_TIME),
                    )
                }
                val latLong = FloatArray(2)
                @Suppress("DEPRECATION")
                val gps = if (exif?.getLatLong(latLong) == true) LatLon(latLong[0].toDouble(), latLong[1].toDouble()) else null
                val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    ?: ExifInterface.ORIENTATION_NORMAL
                val bitmap = decode(uri, PHOTO_EDGE, orientation) ?: return@runCatching null
                val photo = JourneyPhoto(UUID.randomUUID().toString(), takenAt, Track.placePhoto(track, takenAt, gps))
                val file = photoFile(id, photo.id).apply { parentFile?.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                photoFile(id, photo.id, thumbnail = true).outputStream().use { squareThumb(bitmap).compress(Bitmap.CompressFormat.JPEG, 80, it) }
                bitmap.recycle()
                photo
            }.getOrNull()
        }
        if (added.isNotEmpty()) {
            // 存檔前重讀一次，避免蓋掉這段時間的改名
            val latest = load(id) ?: journey
            save(latest.copy(photos = (latest.photos + added).sortedWith(compareBy(nullsLast<Long>()) { it.takenAt })))
        }
        added
    }

    /** 手動把照片放到路線上的 [fraction]（0 起點、1 終點） */
    suspend fun movePhoto(id: String, photoId: String, fraction: Double) = withContext(Dispatchers.IO) {
        val journey = load(id) ?: return@withContext
        val position = Track.positionAtFraction(journey.points, fraction) ?: return@withContext
        save(journey.copy(photos = journey.photos.map { if (it.id == photoId) it.copy(position = position) else it }))
    }

    suspend fun deletePhoto(id: String, photoId: String) = withContext(Dispatchers.IO) {
        val journey = load(id) ?: return@withContext
        photoFile(id, photoId).delete()
        photoFile(id, photoId, thumbnail = true).delete()
        save(journey.copy(photos = journey.photos.filterNot { it.id == photoId }))
    }

    private fun decode(uri: Uri, maxEdge: Int, orientation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val raw = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(raw.width, raw.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
        }
        val result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (result !== raw) raw.recycle()
        return result
    }

    private fun squareThumb(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, THUMB_EDGE, THUMB_EDGE, true)
    }

    companion object {
        const val PHOTO_EDGE = 1600
        const val THUMB_EDGE = 240

        private val _changed = MutableStateFlow(0)

        /** 足跡新增、改名或刪除時加一，畫面用來重新讀取 */
        val changed: StateFlow<Int> = _changed.asStateFlow()

        private val _saved = MutableStateFlow<Journey?>(null)

        /** 剛結束、存好的一趟，用來顯示摘要；看過後呼叫 [clearSaved] */
        val saved: StateFlow<Journey?> = _saved.asStateFlow()

        fun announce(journey: Journey) {
            _saved.value = journey
        }

        fun clearSaved() {
            _saved.value = null
        }
    }
}
