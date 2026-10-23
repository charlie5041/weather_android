package com.charlie.weather.data

import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** 目前位置在路線上的進度 */
data class RideProgress(
    /** 0（起點）到 1（終點） */
    val fraction: Double,
    /** 離路線最近處的距離（公里），偏離路線時會變大 */
    val offRouteKm: Double,
    val remainingKm: Double,
)

/**
 * App 內進行中橫條與騎乘中畫面顯示的即時狀態，內容與常駐通知相同。
 * [routeName] 為 null 時是不設目的地的行車提醒，或 [recordOnly] 的單純記錄足跡。
 */
data class LiveRide(
    val routeName: String?,
    val title: String,
    val detail: String,
    val speedKmh: Int? = null,
    /** 最近一次測速照相提醒（提醒後一段時間內）的標題與內文 */
    val camera: String? = null,
    val cameraDetail: String? = null,
    /** 記錄足跡時目前的里程 */
    val recordedKm: Double? = null,
    /** 從「足跡」分頁開始的單純記錄（不提醒測速照相） */
    val recordOnly: Boolean = false,
    val paused: Boolean = false,
    /** 記錄足跡時：開始時間（epoch 毫秒）、已暫停的總時間與這次暫停的開始時間，畫面用來走秒 */
    val startedAt: Long? = null,
    val pausedMs: Long = 0,
    val pausedAt: Long? = null,
    /** 記錄足跡時目前的累計爬升（公尺） */
    val climbM: Double? = null,
    val gps: GpsSignal? = null,
) {
    /** 記錄的時間（扣掉暫停） */
    fun movingMs(now: Long): Long {
        val start = startedAt ?: return 0
        val pausing = pausedAt?.let { now - it } ?: 0
        return (now - start - pausedMs - pausing).coerceAtLeast(0)
    }

    /** 記錄足跡中但距離或時間還不夠，現在結束不會存 */
    fun tooShort(now: Long): Boolean = recordedKm != null && Track.tooShort(recordedKm, movingMs(now))
}

/** GPS 訊號：依最近一次 GPS 定位的精度與多久以前 */
enum class GpsSignal(val label: String) {
    SEARCHING("搜尋 GPS"),
    WEAK("GPS 弱"),
    FAIR("GPS 普通"),
    GOOD("GPS 良好");

    companion object {
        /** 超過這麼久沒有 GPS 定位就當作沒訊號 */
        const val STALE_MS = 15_000L

        fun of(accuracyM: Float?, ageMs: Long?): GpsSignal {
            if (ageMs == null || ageMs > STALE_MS) return SEARCHING
            val accuracy = accuracyM ?: return WEAK
            return when {
                accuracy > Track.MAX_ACCURACY_M -> WEAK
                accuracy > 12f -> FAIR
                else -> GOOD
            }
        }
    }
}

/**
 * 累計爬升：GPS 高度誤差大，先取最近幾個高度的平均，
 * 比參考高度高出 [thresholdM] 才算爬升，往下時把參考高度跟著降低。
 */
class ClimbCounter(private val thresholdM: Double = 5.0, private val window: Int = 5) {
    private val recent = ArrayDeque<Double>()
    private var reference: Double? = null

    var climbM: Double = 0.0
        private set

    /** 有沒有收到過高度 */
    var hasAltitude: Boolean = false
        private set

    fun add(altitudeM: Double) {
        hasAltitude = true
        recent.addLast(altitudeM)
        if (recent.size > window) recent.removeFirst()
        val smooth = recent.average()
        val ref = reference
        when {
            ref == null -> reference = smooth
            smooth - ref >= thresholdM -> {
                climbM += smooth - ref
                reference = smooth
            }
            smooth < ref -> reference = smooth
        }
    }

    /** 暫停後繼續：可能換了地方，高度重新起算（已累計的爬升保留） */
    fun restart() {
        recent.clear()
        reference = null
    }
}

/** 騎乘中通知的內容 */
data class RideStatus(
    val title: String,
    val text: String,
    /** 前方第一個會下雨的點；沒有時為 null */
    val rainAhead: RouteStop?,
    /** 幾分鐘後會遇到雨（[rainAhead] 的經過時間） */
    val minutesToRain: Long?,
    val arrived: Boolean,
)

/**
 * 騎乘中模式：依目前位置找出在路線上的進度，重新估計前方各點的經過時間與天氣
 * （1 小時內的點用雷達短時預報）。
 */
object RideTracker {
    /** 離終點這麼近（公里）就算抵達 */
    const val ARRIVE_KM = 0.3

    /** 離路線超過這個距離（公里）就提示偏離路線 */
    const val OFF_ROUTE_KM = 1.0

    fun progress(path: RoutePath, position: LatLon): RideProgress {
        val pts = path.points
        if (pts.size < 2) return RideProgress(0.0, 0.0, path.distanceKm)
        val kx = 111.32 * cos(Math.toRadians(position.latitude))
        val ky = 110.57
        var walked = 0.0
        var best = Double.MAX_VALUE
        var bestAt = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val ax = (a.longitude - position.longitude) * kx
            val ay = (a.latitude - position.latitude) * ky
            val dx = (b.longitude - a.longitude) * kx
            val dy = (b.latitude - a.latitude) * ky
            val len = sqrt(dx * dx + dy * dy)
            val t = if (len == 0.0) 0.0 else ((-ax * dx - ay * dy) / (len * len)).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            val d = sqrt(x * x + y * y)
            if (d < best) {
                best = d
                bestAt = walked + t * len
            }
            walked += len
        }
        val total = walked.takeIf { it > 0 } ?: return RideProgress(0.0, best, path.distanceKm)
        val fraction = (bestAt / total).coerceIn(0.0, 1.0)
        return RideProgress(fraction, best, path.distanceKm * (1 - fraction))
    }

    /** 從目前進度起的前方各點：假設照原本的行車時間騎完剩下的路 */
    fun ahead(data: RouteData, fraction: Double, now: LocalDateTime): RouteForecast {
        val seconds = data.path.durationMinutes * 60
        val virtualDeparture = now.minusSeconds((seconds * fraction).roundToLong())
        val forecast = RoutePlanner.evaluate(data, virtualDeparture, now)
        return forecast.copy(stops = forecast.stops.filter { it.point.fraction >= fraction - 1e-9 })
    }

    fun status(data: RouteData, progress: RideProgress, now: LocalDateTime): RideStatus {
        val arrival = now.plusSeconds((data.path.durationMinutes * 60 * (1 - progress.fraction)).roundToLong())
        val remain = "還有 %.1f 公里，約 %02d:%02d 抵達".format(Locale.US, progress.remainingKm, arrival.hour, arrival.minute)
        if (progress.remainingKm <= ARRIVE_KM) {
            return RideStatus("已抵達${data.to.displayName}", "沿途提醒已結束", null, null, arrived = true)
        }
        val stops = ahead(data, progress.fraction, now).stops
        val rain = stops.firstOrNull { it.wet }
        val offRoute = if (progress.offRouteKm > OFF_ROUTE_KM) "已偏離路線，依最近的路段估計。" else ""
        if (rain == null) {
            val title = if (stops.any { it.hour == null && it.nowcastMm == null }) "前方部分路段暫無預報" else "前方不太會下雨"
            return RideStatus(title, offRoute + remain, null, null, arrived = false)
        }
        val minutes = Duration.between(now, rain.eta).toMinutes().coerceAtLeast(0)
        val where = rain.place ?: "前方 %.1f 公里處".format(Locale.US, (rain.point.distanceKm - (data.path.distanceKm - progress.remainingKm)).coerceAtLeast(0.0))
        val amount = rain.nowcastMm?.let { "雷達預估 1 小時 %.1f mm".format(Locale.US, it) } ?: "降雨機率 ${rain.probability}%"
        val heavy = rain.rainLevel == RainLevel.HEAVY
        val title = when {
            minutes <= 2 -> if (heavy) "目前路段有大雨" else "目前路段可能下雨"
            else -> "約 $minutes 分鐘後${if (heavy) "有大雨" else "可能下雨"}"
        }
        return RideStatus(title, "$offRoute${where}一帶 · $amount。$remain", rain, minutes, arrived = false)
    }
}
