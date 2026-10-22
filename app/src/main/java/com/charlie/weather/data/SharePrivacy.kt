package com.charlie.weather.data

import kotlin.random.Random

/** 分享時要藏起來的地點：圓心 [center]、半徑 [radiusM] 公尺內的軌跡都不送出去。 */
data class PrivacyZone(val center: LatLon, val radiusM: Double)

/**
 * 套用隱私區後要分享的路線。
 * [segments] 是留下來的各段（經過隱私區時會斷開）；[mapHidden] 時留下的太少，分享卡不放地圖。
 * [trimStartM]、[trimEndM] 是這趟起訖點額外裁掉的距離（公尺）。
 */
data class SharedRoute(
    val segments: List<List<LatLon>>,
    val keptKm: Double,
    val totalKm: Double,
    val trimStartM: Int,
    val trimEndM: Int,
    val mapHidden: Boolean,
)

/**
 * 分享前把軌跡裡會透露住家、公司位置的部分拿掉（只影響送出去的內容，本機足跡不動）：
 * 1. 落在任一隱私區內的軌跡全部移除，包括中途經過的那一段。
 * 2. 剩下軌跡的頭尾再沿路線各裁掉 [MIN_TRIM_M]–[MAX_TRIM_M] 公尺。距離每趟不同，
 *    避免多趟都斷在同一個圓上、被拼出圓心；以足跡 id 當亂數種子，同一趟分享幾次都一樣。
 * 3. 裁完剩不到 [MIN_KEEP_KM] 公里或原本的 [MIN_KEEP_FRACTION] 時不放地圖。
 */
object SharePrivacy {
    const val MIN_TRIM_M = 200
    const val MAX_TRIM_M = 500
    const val MIN_KEEP_KM = 1.0
    const val MIN_KEEP_FRACTION = 0.3

    /** 隱私區半徑的選項（公尺）與預設值 */
    val RADIUS_OPTIONS = listOf(200, 500, 1000)
    const val DEFAULT_RADIUS_M = 500

    /** 先把軌跡補點到每段不超過這個長度（公尺），長直線穿過隱私區時也切得到 */
    const val STEP_M = 20.0

    /** 這趟起點（[end] 為 false）或終點額外裁掉的距離（公尺） */
    fun trimM(journeyId: String, end: Boolean): Int {
        val random = Random(journeyId.hashCode().toLong() * 31 + if (end) 1 else 0)
        return random.nextInt(MIN_TRIM_M, MAX_TRIM_M + 1)
    }

    /** 自訂了名稱（住家、公司、學校…）的地點都當隱私區，[excludedIds] 是使用者關掉的 */
    fun zonesFrom(places: List<City>, excludedIds: Set<String>, radiusM: Int): List<PrivacyZone> =
        places.filter { it.label != null && !it.isCurrentLocation && it.id !in excludedIds }
            .map { PrivacyZone(LatLon(it.latitude, it.longitude), radiusM.toDouble()) }

    fun apply(journeyId: String, points: List<LatLon>, zones: List<PrivacyZone>): SharedRoute {
        val trimStart = trimM(journeyId, end = false)
        val trimEnd = trimM(journeyId, end = true)
        val dense = densify(points, STEP_M)
        val cumulative = DoubleArray(dense.size)
        for (i in 1 until dense.size) cumulative[i] = cumulative[i - 1] + Track.distanceKm(dense[i - 1], dense[i]) * 1000
        val totalM = cumulative.lastOrNull() ?: 0.0
        val outside = BooleanArray(dense.size) { i -> zones.none { Track.distanceKm(it.center, dense[i]) * 1000 <= it.radiusM } }
        val first = outside.indexOfFirst { it }
        val last = outside.indexOfLast { it }
        val segments = mutableListOf<List<LatLon>>()
        if (first >= 0) {
            val from = cumulative[first] + trimStart
            val to = cumulative[last] - trimEnd
            var run = mutableListOf<LatLon>()
            for (i in dense.indices) {
                if (outside[i] && cumulative[i] >= from && cumulative[i] <= to) {
                    run += dense[i]
                } else if (run.isNotEmpty()) {
                    if (run.size >= 2) segments += run
                    run = mutableListOf()
                }
            }
            if (run.size >= 2) segments += run
        }
        val keptKm = segments.sumOf { seg -> (1 until seg.size).sumOf { Track.distanceKm(seg[it - 1], seg[it]) } }
        val totalKm = totalM / 1000
        val hidden = keptKm < MIN_KEEP_KM || keptKm < totalKm * MIN_KEEP_FRACTION
        return SharedRoute(if (hidden) emptyList() else segments, keptKm, totalKm, trimStart, trimEnd, hidden)
    }

    /** 在相鄰兩點之間等距補點，讓每段不超過 [stepM] 公尺 */
    fun densify(points: List<LatLon>, stepM: Double): List<LatLon> {
        if (points.size < 2) return points
        val out = ArrayList<LatLon>(points.size * 2)
        out += points.first()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val n = kotlin.math.ceil(Track.distanceKm(a, b) * 1000 / stepM).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toDouble() / n
                out += LatLon(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
            }
        }
        return out
    }
}
