package com.charlie.weather.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Google Routes API：含即時與預測路況的行車時間（機車用 Google 地圖的「機車」路線）。
 * 需要 API 金鑰（GitHub Secret GOOGLE_MAPS_API_KEY）；沒有金鑰或查詢失敗時改用 OpenStreetMap 路線。
 *
 * 金鑰會隨 APK 發佈，因此請求帶上套件名稱與簽章憑證 SHA-1，
 * 讓金鑰可以在 Google Cloud 限制為只有這個 App 能使用。
 */
class GoogleRoutes(private val apiKey: String, private val packageName: String, private val certSha1: String?) {

    suspend fun route(from: LatLon, to: LatLon, mode: TravelMode, departure: LocalDateTime?, via: List<LatLon> = emptyList()): RoutePath? = try {
        parse(post(requestBody(from, to, mode, departure, Instant.now(), via)))
    } catch (e: IOException) {
        null
    } catch (e: org.json.JSONException) {
        null
    }

    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val conn = URL(URL_COMPUTE).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Goog-Api-Key", apiKey)
            conn.setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            conn.setRequestProperty("X-Android-Package", packageName)
            certSha1?.let { conn.setRequestProperty("X-Android-Cert", it) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val URL_COMPUTE = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private const val FIELD_MASK = "routes.duration,routes.distanceMeters,routes.polyline.geoJsonLinestring"

        fun travelMode(mode: TravelMode) = when (mode) {
            TravelMode.SCOOTER -> "TWO_WHEELER"
            TravelMode.CAR -> "DRIVE"
            TravelMode.BIKE -> "BICYCLE"
            TravelMode.WALK -> "WALK"
        }

        /**
         * 組出 computeRoutes 的請求。機車與汽車使用路況（TRAFFIC_AWARE），
         * 出發時間在未來時用預測路況；已經過或沒指定時就是現在的路況。
         */
        fun requestBody(
            from: LatLon,
            to: LatLon,
            mode: TravelMode,
            departure: LocalDateTime?,
            now: Instant,
            via: List<LatLon> = emptyList(),
        ): String {
            fun waypoint(p: LatLon) = JSONObject().put(
                "location",
                JSONObject().put("latLng", JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)),
            )
            val body = JSONObject()
                .put("origin", waypoint(from))
                .put("destination", waypoint(to))
                .put("travelMode", travelMode(mode))
                .put("polylineEncoding", "GEO_JSON_LINESTRING")
                .put("languageCode", "zh-TW")
                .put("units", "METRIC")
            if (via.isNotEmpty()) body.put("intermediates", JSONArray(via.map(::waypoint)))
            if (mode == TravelMode.SCOOTER || mode == TravelMode.CAR) {
                body.put("routingPreference", "TRAFFIC_AWARE")
                departure?.atZone(ZoneId.systemDefault())?.toInstant()
                    ?.takeIf { it.isAfter(now.plusSeconds(60)) }
                    ?.let { body.put("departureTime", it.toString()) }
            }
            return body.toString()
        }

        /** 解析回應；duration 是像 "1260s" 的字串。 */
        fun parse(json: String): RoutePath? {
            val route = JSONObject(json).optJSONArray("routes")?.optJSONObject(0) ?: return null
            val coords = route.optJSONObject("polyline")?.optJSONObject("geoJsonLinestring")?.optJSONArray("coordinates") ?: return null
            val points = (0 until coords.length()).mapNotNull { i ->
                coords.optJSONArray(i)?.let { LatLon(it.getDouble(1), it.getDouble(0)) }
            }
            if (points.size < 2) return null
            val seconds = route.optString("duration").removeSuffix("s").toDoubleOrNull() ?: return null
            return RoutePath(points, route.optDouble("distanceMeters", 0.0) / 1000, seconds / 60, source = RouteSource.GOOGLE)
        }

        /** App 簽章憑證的 SHA-1（大寫十六進位、不含冒號），對應 Google Cloud 金鑰的 Android 應用程式限制。 */
        fun certSha1(context: Context): String? = runCatching {
            val pm = context.packageManager
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            } ?: return@runCatching null
            MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }
}
