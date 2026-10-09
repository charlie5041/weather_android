package com.charlie.weather.data

import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 前方的測速照相與距離（公尺，取整到 50） */
data class CameraAhead(val camera: SpeedCamera, val meters: Int)

/** 騎乘中與行車提醒共用：找出前方的測速照相，組成通知與語音的文字。 */
object CameraAlerts {
    /** 前方這麼近（公里）就提醒；時速 50 約 36 秒 */
    const val ALERT_KM = 0.5

    /** 沒有路線時，照相要在行進方向左右這個角度內才算「前方」 */
    private const val CONE = 35.0

    /** 太近就當作已經經過（GPS 誤差），不再提醒 */
    private const val PASSED_KM = 0.03

    /** 同一處的識別，用來避免重複提醒 */
    fun key(camera: SpeedCamera): String =
        "%.5f,%.5f,%s".format(Locale.US, camera.position.latitude, camera.position.longitude, camera.travelHeading ?: "-")

    /** 有路線時：路線上前方 [km] 公里內的第一處 */
    fun onRoute(cameras: List<RouteCamera>, routeKm: Double, fraction: Double, km: Double = ALERT_KM): CameraAhead? {
        val here = routeKm * fraction
        val next = cameras.filter { it.distanceKm >= here - 0.02 }.minByOrNull { it.distanceKm } ?: return null
        val ahead = next.distanceKm - here
        if (ahead > km) return null
        return CameraAhead(next.camera, rounded(ahead))
    }

    /**
     * 沒有路線時：依目前位置與行進方位，找前方 [km] 公里內、拍攝方向相符的最近一處。
     * 沒有方位（停著或剛定位）時無法判斷前後，回傳 null。
     */
    fun ahead(cameras: List<SpeedCamera>, position: LatLon, bearing: Double?, km: Double = ALERT_KM): CameraAhead? {
        if (bearing == null) return null
        val kx = 111.32 * cos(Math.toRadians(position.latitude))
        val ky = 110.57
        val pad = km / 100.0
        return cameras.asSequence()
            .filter { abs(it.position.latitude - position.latitude) <= pad && abs(it.position.longitude - position.longitude) <= pad * 1.2 }
            .mapNotNull { c ->
                val dx = (c.position.longitude - position.longitude) * kx
                val dy = (c.position.latitude - position.latitude) * ky
                val d = sqrt(dx * dx + dy * dy)
                if (d > km || d < PASSED_KM) return@mapNotNull null
                if (SpeedCameraRepository.angleBetween(SpeedCameraRepository.bearing(position, c.position), bearing) > CONE) return@mapNotNull null
                val heading = c.travelHeading
                if (heading != null && SpeedCameraRepository.angleBetween(heading, bearing) > SpeedCameraRepository.MAX_ANGLE) return@mapNotNull null
                c to d
            }
            .minByOrNull { it.second }
            ?.let { (c, d) -> CameraAhead(c, rounded(d)) }
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    private fun rounded(km: Double) = ((km * 1000).coerceAtLeast(0.0) / 50).roundToInt() * 50

    private fun kind(camera: SpeedCamera) = if (camera.mobile) "移動式測速" else "測速照相"

    /** 通知標題，例如「📷 前方 300 公尺測速照相・速限 50」 */
    fun title(a: CameraAhead): String = buildString {
        append(if (a.meters <= 50) "📷 ${kind(a.camera)}" else "📷 前方 ${a.meters} 公尺${kind(a.camera)}")
        a.camera.limit?.let { append("・速限 $it") }
    }

    /** 通知內文：超速時提醒減速，加上地點或回報時間 */
    fun detail(a: CameraAhead, speedKmh: Int?, nowMs: Long = System.currentTimeMillis()): String = buildString {
        val limit = a.camera.limit
        if (limit != null && speedKmh != null && speedKmh > limit) append("目前時速約 $speedKmh，請減速。")
        val reported = a.camera.reportedAt
        if (a.camera.mobile && reported != null) {
            val minutes = ((nowMs - reported) / 60_000).coerceAtLeast(0)
            append(if (minutes < 1) "剛剛有人回報" else "$minutes 分鐘前有人回報")
        } else {
            append(a.camera.address)
        }
    }

    /** 語音播報的句子，例如「前方 300 公尺測速照相，速限 50，請減速」 */
    fun speech(a: CameraAhead, speedKmh: Int?): String = buildString {
        append(if (a.meters <= 50) kind(a.camera) else "前方 ${a.meters} 公尺${kind(a.camera)}")
        val limit = a.camera.limit
        if (limit != null) append("，速限 $limit")
        if (limit != null && speedKmh != null && speedKmh > limit) append("，請減速")
    }
}
