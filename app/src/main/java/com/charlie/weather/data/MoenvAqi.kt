package com.charlie.weather.data

import org.json.JSONArray
import org.json.JSONObject

/** 環境部空氣品質監測站即時資料（資料集 aqx_p_432）。 */
data class MoenvStation(
    val name: String,
    val county: String,
    val latitude: Double,
    val longitude: Double,
    val aqi: Int?,
    val status: String?,
    val pollutant: String?,
    val pm25: Double?,
    val pm10: Double?,
    val publishTime: String?,
)

object MoenvParser {
    const val DATASET_URL = "https://data.moenv.gov.tw/api/v2/aqx_p_432"

    /** 回應可能是 {"records":[...]} 或直接是陣列；欄位名稱不分大小寫。 */
    fun parse(json: String): List<MoenvStation> {
        val trimmed = json.trimStart()
        val records = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val root = JSONObject(trimmed)
            root.optJSONArray("records") ?: root.optJSONArray("Records") ?: JSONArray()
        }
        return (0 until records.length()).mapNotNull { i ->
            val raw = records.optJSONObject(i) ?: return@mapNotNull null
            val r = raw.keys().asSequence().associate { it.lowercase() to raw.opt(it) }
            fun text(key: String) = (r[key] as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "-" && it != "ND" }
            fun num(key: String) = when (val v = r[key]) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }
            MoenvStation(
                name = text("sitename") ?: return@mapNotNull null,
                county = text("county").orEmpty(),
                latitude = num("latitude") ?: return@mapNotNull null,
                longitude = num("longitude") ?: return@mapNotNull null,
                aqi = num("aqi")?.toInt(),
                status = text("status"),
                pollutant = text("pollutant"),
                pm25 = num("pm2.5"),
                pm10 = num("pm10"),
                publishTime = text("publishtime"),
            )
        }
    }

    /** 最近、且有 AQI 數值的測站 */
    fun nearest(stations: List<MoenvStation>, latitude: Double, longitude: Double, maxKm: Double = 25.0): MoenvStation? =
        stations.filter { it.aqi != null }
            .map { it to CwaParser.distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= maxKm }
            .minByOrNull { it.second }
            ?.first
}
