package com.charlie.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 從中央氣象署開放資料（公開 S3，免授權碼）取得測站觀測、鄉鎮預報與天氣特報。
 * 原始檔案快取在 cacheDir，離線或啟動時可直接使用。
 */
class CwaRepository(cacheDir: File) {
    private val dir = File(cacheDir, "cwa").apply { mkdirs() }
    private val locks = mutableMapOf<String, Mutex>()
    private val stationCache = mutableMapOf<String, Pair<Long, List<CwaStation>>>()

    /** 台灣（含離島）範圍外直接回傳 null。 */
    suspend fun load(latitude: Double, longitude: Double, allowNetwork: Boolean): CwaData? = withContext(Dispatchers.IO) {
        if (latitude !in 21.5..26.6 || longitude !in 118.0..122.6) return@withContext null

        val manned = stations("Observation/O-A0003-001.json", OBSERVATION_TTL, allowNetwork)
        var observation = CwaParser.nearest(manned, latitude, longitude, MAX_STATION_KM)
        var allStations = manned
        if (observation == null) {
            val auto = stations("Observation/O-A0001-001.json", OBSERVATION_TTL, allowNetwork)
            observation = CwaParser.nearest(auto, latitude, longitude, MAX_STATION_KM)
            allStations = manned + auto
        }

        val county = observation?.station?.county
            ?: CwaParser.nearest(allStations, latitude, longitude, 60.0)?.station?.county
            ?: return@withContext observation?.let { CwaData(it, null, emptyList()) }

        val forecast = runCatching {
            val threeDay = CwaParser.threeDayForecastId(county)?.let { file("Forecast/$it.json", FORECAST_TTL, allowNetwork) }
            val weekly = CwaParser.weeklyForecastId(county)?.let { file("Forecast/$it.json", FORECAST_TTL, allowNetwork) }
            CwaParser.parseTownshipForecast(threeDay, weekly, latitude, longitude)
        }.getOrNull()

        val alerts = runCatching {
            file("Warning/W-C0033-001.json", OBSERVATION_TTL, allowNetwork)
                ?.let { CwaParser.parseAlerts(it, county, LocalDateTime.now(TAIWAN)) }
        }.getOrNull().orEmpty()

        if (observation == null && forecast == null && alerts.isEmpty()) null else CwaData(observation, forecast, alerts)
    }

    private suspend fun stations(path: String, ttl: Long, allowNetwork: Boolean): List<CwaStation> {
        val text = file(path, ttl, allowNetwork) ?: return emptyList()
        val modified = cacheFile(path).lastModified()
        synchronized(stationCache) {
            stationCache[path]?.takeIf { it.first == modified }?.let { return it.second }
        }
        val parsed = runCatching { CwaParser.parseStations(text) }.getOrDefault(emptyList())
        synchronized(stationCache) { stationCache[path] = modified to parsed }
        return parsed
    }

    private fun cacheFile(path: String) = File(dir, path.replace('/', '_'))

    /** 取得檔案內容：快取未過期就直接用，否則下載；下載失敗時退回舊的快取。 */
    private suspend fun file(path: String, ttl: Long, allowNetwork: Boolean): String? {
        val lock = synchronized(locks) { locks.getOrPut(path) { Mutex() } }
        return lock.withLock {
            val f = cacheFile(path)
            val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < ttl
            if (fresh || !allowNetwork) return@withLock f.takeIf { it.exists() }?.readText()
            try {
                val text = download("$BASE_URL/$path")
                val tmp = File(dir, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.writeText(text)
                    tmp.delete()
                }
                text
            } catch (e: IOException) {
                f.takeIf { it.exists() }?.readText()
            }
        }
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://cwaopendata.s3.ap-northeast-1.amazonaws.com"
        val TAIWAN: ZoneOffset = ZoneOffset.ofHours(8)
        private const val OBSERVATION_TTL = 10 * 60_000L
        private const val FORECAST_TTL = 60 * 60_000L
        private const val MAX_STATION_KM = 10.0
    }
}
