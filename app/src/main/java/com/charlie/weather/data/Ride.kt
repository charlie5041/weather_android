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
        return RideStatus(title, "$offRoute$where一帶 · $amount。$remain", rain, minutes, arrived = false)
    }
}
