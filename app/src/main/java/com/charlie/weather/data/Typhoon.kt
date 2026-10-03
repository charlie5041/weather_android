package com.charlie.weather.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** 颱風的一個定位點（過去路徑或預報點）。風速單位 m/s、半徑單位 km。 */
data class TyphoonFix(
    val time: LocalDateTime,
    val latitude: Double,
    val longitude: Double,
    val maxWind: Double?,
    val maxGust: Double?,
    val pressure: Int?,
    val movingSpeed: Double?,
    val movingDirection: String?,
    val radius15: Double?,
    val radius25: Double?,
    /** 預報點的 70% 機率半徑 */
    val probabilityRadius: Double?,
)

data class Typhoon(
    val nameZh: String,
    val nameEn: String,
    val year: String,
    val number: String?,
    val past: List<TyphoonFix>,
    val forecast: List<TyphoonFix>,
    /** 例：以每小時18公里速度，向北北東進行 */
    val movement: String?,
) {
    val current: TyphoonFix get() = past.last()

    /** 中央氣象署的強度分級（近中心最大風速） */
    val category: String
        get() {
            val w = current.maxWind ?: 0.0
            return when {
                w < 17.2 -> "熱帶性低氣壓"
                w < 32.7 -> "輕度颱風"
                w < 51.0 -> "中度颱風"
                else -> "強烈颱風"
            }
        }

    val displayName: String get() = if (nameZh.isNotBlank()) nameZh else nameEn
}

/** 解析中央氣象署熱帶氣旋路徑（W-C0034-005）。 */
object TyphoonParser {

    fun parse(json: String): List<Typhoon> {
        val root = JSONObject(json).optJSONObject("cwaopendata") ?: return emptyList()
        val dataset = root.optJSONObject("Dataset") ?: root.optJSONObject("dataset") ?: return emptyList()
        val cyclones = dataset.optJSONObject("TropicalCyclones")?.opt("TropicalCyclone").asObjects()
        return cyclones.mapNotNull { tc ->
            val past = tc.optJSONObject("AnalysisData")?.opt("Fix").asObjects().mapNotNull { fix(it, null) }
            if (past.isEmpty()) return@mapNotNull null
            val lastPast = tc.optJSONObject("AnalysisData")?.opt("Fix").asObjects().lastOrNull()
            val forecast = tc.optJSONObject("ForecastData")?.opt("Fix").asObjects().mapNotNull { f ->
                val initial = parseTime(f.opt("InitialTime")) ?: return@mapNotNull null
                val hours = f.opt("ForecastHour").num()?.toLong() ?: return@mapNotNull null
                fix(f, initial.plusHours(hours))
            }
            Typhoon(
                nameZh = tc.optString("CwaTyphoonName").trim(),
                nameEn = tc.optString("TyphoonName").trim(),
                year = tc.optString("Year"),
                number = tc.optString("CwaTyNo").takeIf { it.isNotBlank() },
                past = past.sortedBy { it.time },
                forecast = forecast.sortedBy { it.time },
                movement = lastPast?.opt("MovingPrediction").asObjects()
                    .firstOrNull { it.optString("@lang").startsWith("zh") }
                    ?.optString("#text")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun fix(o: JSONObject, timeOverride: LocalDateTime?): TyphoonFix? {
        val time = timeOverride ?: parseTime(o.opt("DateTime")) ?: return null
        return TyphoonFix(
            time = time,
            latitude = o.opt("CoordinateLatitude").num() ?: return null,
            longitude = o.opt("CoordinateLongitude").num() ?: return null,
            maxWind = o.opt("MaxWindSpeed").num(),
            maxGust = o.opt("MaxGustSpeed").num(),
            pressure = o.opt("Pressure").num()?.toInt(),
            movingSpeed = o.opt("MovingSpeed").num(),
            movingDirection = (o.opt("MovingDirection") as? String)?.takeIf { it.isNotBlank() },
            radius15 = o.optJSONObject("Circle15ms")?.opt("Radius").num(),
            radius25 = o.optJSONObject("Circle25ms")?.opt("Radius").num(),
            probabilityRadius = o.opt("Radius70PercentProbability").num(),
        )
    }

    private fun Any?.asObjects(): List<JSONObject> = when (this) {
        is JSONArray -> (0 until length()).mapNotNull { optJSONObject(it) }
        is JSONObject -> listOf(this)
        else -> emptyList()
    }

    private fun Any?.num(): Double? = when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }

    private fun parseTime(value: Any?): LocalDateTime? =
        (value as? String)?.let { runCatching { OffsetDateTime.parse(it).toLocalDateTime() }.getOrNull() }
}
