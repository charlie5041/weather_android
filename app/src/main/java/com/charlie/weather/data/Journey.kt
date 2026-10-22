package com.charlie.weather.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 軌跡上的一個定位點；[time] 是 epoch 毫秒，[accuracy] 是定位精度（公尺）。 */
data class TrackPoint(val time: Long, val latitude: Double, val longitude: Double, val accuracy: Float? = null) {
    val position get() = LatLon(latitude, longitude)
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
) {
    val durationMinutes: Long get() = ((endTime - startTime) / 60_000).coerceAtLeast(0)

    /** 平均時速（公里），不扣掉停下來的時間 */
    val averageKmh: Double get() = if (endTime > startTime) distanceKm / ((endTime - startTime) / 3_600_000.0) else 0.0

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

    /** 整理原始點成一趟足跡；太短（誤觸）時回傳 null */
    fun build(id: String, raw: List<TrackPoint>): Journey? {
        val points = clean(raw)
        if (points.size < 2) return null
        val distance = distanceKm(points)
        if (distance < MIN_KM || points.last().time - points.first().time < MIN_MS) return null
        return Journey(id, points.first().time, points.last().time, distance, simplify(points))
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
        .put(
            "points",
            JSONArray().apply {
                j.points.forEach { p -> put(JSONArray().put(p.time).put(p.latitude).put(p.longitude)) }
            },
        )

    fun fromJson(o: JSONObject): Journey {
        val arr = o.getJSONArray("points")
        val points = (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            TrackPoint(p.getLong(0), p.getDouble(1), p.getDouble(2))
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
        )
    }
}

/**
 * 足跡存在 App 私有空間（filesDir/journeys），不上傳：
 * - 騎乘中的定位點逐批附加到 recording.csv，程序被系統結束也只少最後幾十秒
 * - 結束後整理成 `<id>.json`（精簡後的軌跡，畫地圖用），原始點改名為 `<id>.csv` 保留
 */
class JourneyStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "journeys").apply { mkdirs() }
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

    /** 結束紀錄並存成一趟足跡；太短時不存，回傳 null */
    suspend fun finish(): Journey? = withContext(Dispatchers.IO) { finishLocked() }

    /** App 開啟時：沒有在騎乘卻留著紀錄檔（程序被結束），補存起來 */
    suspend fun recover(active: Boolean) = withContext(Dispatchers.IO) {
        if (!active && recording.exists()) finishLocked()
    }

    private suspend fun finishLocked(): Journey? {
        val raw = runCatching { recording.readLines().mapNotNull(Track::decodePoint) }.getOrDefault(emptyList())
        val id = raw.firstOrNull()?.time?.toString() ?: System.currentTimeMillis().toString()
        val built = Track.build(id, raw)
        if (built == null) {
            recording.delete()
            return null
        }
        val from = geocoder.reverse(built.points.first().latitude, built.points.first().longitude)?.area
        val to = geocoder.reverse(built.points.last().latitude, built.points.last().longitude)?.area
        val journey = built.copy(fromArea = from, toArea = to)
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
        _changed.value++
    }

    companion object {
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
