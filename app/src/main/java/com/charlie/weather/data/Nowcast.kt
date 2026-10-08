package com.charlie.weather.data

import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 中央氣象署「未來1小時雷達定量降雨預報」（F-B0046-001）：以雷達回波外延估計的未來 1 小時累積雨量（mm），
 * 0.0125°（約 1.4 公里）格點、每 10 分鐘更新。沒有雨的格點為 -99，這裡存成 0。
 * 只適合用在接下來 1 小時；外延法無法預測新生成的對流。
 */
class RainNowcast(
    /** 資料時間（本地時間）；預報涵蓋之後 1 小時 */
    val issued: LocalDateTime,
    /** 左下角第一個格點的經緯度 */
    val lon0: Double,
    val lat0: Double,
    val step: Double,
    val nx: Int,
    val ny: Int,
    private val mm: FloatArray,
) {
    fun value(i: Int, j: Int): Float = mm[j * nx + i]

    fun column(longitude: Double) = ((longitude - lon0) / step).roundToInt()

    fun row(latitude: Double) = ((latitude - lat0) / step).roundToInt()

    /** 格點中心的經緯度 */
    fun longitude(i: Int) = lon0 + i * step

    fun latitude(j: Int) = lat0 + j * step

    /**
     * 位置附近 [radius] 格內最大的 1 小時雨量（位置與外延都有誤差，取鄰近最大值）；
     * 超出雷達範圍時為 null。
     */
    fun at(latitude: Double, longitude: Double, radius: Int = 1): Double? {
        val i = column(longitude)
        val j = row(latitude)
        if (i !in 0 until nx || j !in 0 until ny) return null
        var max = 0f
        for (jj in (j - radius)..(j + radius)) {
            if (jj !in 0 until ny) continue
            for (ii in (i - radius)..(i + radius)) {
                if (ii in 0 until nx) max = maxOf(max, mm[jj * nx + ii])
            }
        }
        return max.toDouble()
    }

    /** 資料仍新（40 分鐘內）且 [time] 落在預報的 1 小時內 */
    fun covers(time: LocalDateTime, now: LocalDateTime): Boolean =
        Duration.between(issued, now).toMinutes() in -10..40 &&
            !time.isBefore(issued.minusMinutes(10)) && time.isBefore(issued.plusMinutes(70))

    companion object {
        /** 判斷「會淋到雨」的 1 小時雨量門檻（mm） */
        const val WET_MM = 0.5

        fun parse(json: String, zone: ZoneId = ZoneId.systemDefault()): RainNowcast? {
            val dataset = JSONObject(json).optJSONObject("cwaopendata")?.optJSONObject("dataset") ?: return null
            val params = dataset.optJSONObject("datasetInfo")?.optJSONObject("parameterSet") ?: return null
            val contents = dataset.optJSONObject("contents") ?: return null
            val nx = params.optString("GridDimensionX").toIntOrNull() ?: return null
            val ny = params.optString("GridDimensionY").toIntOrNull() ?: return null
            val step = params.optString("GridResolution").toDoubleOrNull() ?: return null
            val issued = runCatching {
                OffsetDateTime.parse(params.optString("DateTime")).atZoneSameInstant(zone).toLocalDateTime()
            }.getOrNull() ?: return null
            // 說明文字寫明第一點的位置（「左下角為第一點東經117.975、北緯19.975」）；沒有時用 StartPoint
            val first = Regex("""東經([0-9.]+)、北緯([0-9.]+)""").find(contents.optString("contentDescription"))
            val lon0 = first?.groupValues?.get(1)?.toDoubleOrNull() ?: params.optString("StartPointLongitude").toDoubleOrNull() ?: return null
            val lat0 = first?.groupValues?.get(2)?.toDoubleOrNull() ?: params.optString("StartPointLatitude").toDoubleOrNull() ?: return null

            val text = contents.optString("content")
            val values = FloatArray(nx * ny)
            var start = 0
            var k = 0
            while (start <= text.length && k < values.size) {
                var end = text.indexOf(',', start)
                if (end < 0) end = text.length
                val v = text.substring(start, end).trim().toFloatOrNull() ?: 0f
                values[k++] = if (v < 0f) 0f else v
                start = end + 1
            }
            if (k != values.size) return null
            return RainNowcast(issued, lon0, lat0, step, nx, ny, values)
        }
    }
}
