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
    private val parseLocks = mutableMapOf<String, Mutex>()
    private val stationCache = mutableMapOf<String, Pair<Long, List<CwaStation>>>()
    private val gaugeCache = mutableMapOf<String, Pair<Long, List<RainGauge>>>()

    /** 鄉鎮預報索引；一個縣市兩個檔案，只留最近用過的幾個（每個約為檔案大小的兩倍） */
    private val townshipCache = object : LinkedHashMap<String, Pair<Long, TownshipIndex?>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, TownshipIndex?>>) = size > MAX_TOWNSHIP_FILES
    }
    private var nowcastCache: Pair<Long, RainNowcast?>? = null

    /**
     * 台灣（含離島）範圍外直接回傳 null。
     * @param lowData 背景更新且使用行動網路時為 true：拉長快取時間，雨量站（1.7MB）只用快取
     */
    suspend fun load(latitude: Double, longitude: Double, allowNetwork: Boolean, lowData: Boolean = false): CwaData? = withContext(Dispatchers.IO) {
        if (latitude !in 21.5..26.6 || longitude !in 118.0..122.6) return@withContext null
        val observationTtl = if (lowData) LOW_DATA_OBSERVATION_TTL else OBSERVATION_TTL
        val forecastTtl = if (lowData) LOW_DATA_FORECAST_TTL else FORECAST_TTL

        val manned = stations("Observation/O-A0003-001.json", observationTtl, allowNetwork)
        var observation = CwaParser.nearest(manned, latitude, longitude, MAX_STATION_KM)
        var allStations = manned
        if (observation == null) {
            val auto = stations("Observation/O-A0001-001.json", observationTtl, allowNetwork)
            observation = CwaParser.nearest(auto, latitude, longitude, MAX_STATION_KM)
            allStations = manned + auto
        }

        val county = observation?.station?.county
            ?: CwaParser.nearest(allStations, latitude, longitude, 60.0)?.station?.county
            ?: return@withContext observation?.let { CwaData(it, null, emptyList()) }

        val forecast = runCatching {
            val threeDay = CwaParser.threeDayForecastId(county)?.let { townships("Forecast/$it.json", forecastTtl, allowNetwork) }
            val weekly = CwaParser.weeklyForecastId(county)?.let { townships("Forecast/$it.json", forecastTtl, allowNetwork) }
            CwaParser.parseTownshipForecast(threeDay, weekly, latitude, longitude)
        }.getOrNull()

        val alerts = runCatching {
            file("Warning/W-C0033-001.json", observationTtl, allowNetwork)
                ?.let { CwaParser.parseAlerts(it, county, LocalDateTime.now(TAIWAN)) }
        }.getOrNull().orEmpty()

        val rain = runCatching {
            CwaParser.rainNow(rainGauges(OBSERVATION_TTL, allowNetwork && !lowData), latitude, longitude)
        }.getOrNull()

        if (observation == null && forecast == null && alerts.isEmpty() && rain == null) {
            null
        } else {
            CwaData(observation, forecast, alerts, rain)
        }
    }

    private suspend fun rainGauges(ttl: Long, allowNetwork: Boolean): List<RainGauge> =
        parsed("Observation/O-A0002-001.json", ttl, allowNetwork, gaugeCache) {
            runCatching { CwaParser.parseRainGauges(it) }.getOrDefault(emptyList())
        }.orEmpty()

    private suspend fun stations(path: String, ttl: Long, allowNetwork: Boolean): List<CwaStation> =
        parsed(path, ttl, allowNetwork, stationCache) {
            runCatching { CwaParser.parseStations(it) }.getOrDefault(emptyList())
        }.orEmpty()

    private suspend fun townships(path: String, ttl: Long, allowNetwork: Boolean): TownshipIndex? =
        parsed(path, ttl, allowNetwork, townshipCache) { runCatching { CwaParser.indexTownships(it) }.getOrNull() }

    /**
     * 解析過的檔案內容，依檔案時間快取在記憶體。路線上很多點同時查詢時，
     * 同一個檔案只讀取、解析一次（其他點等待後直接用結果）；快取還有效時不必讀檔，
     * 避免多個執行緒同時把數 MB 的檔案讀進記憶體而用光記憶體。
     */
    private suspend fun <T> parsed(
        path: String,
        ttl: Long,
        allowNetwork: Boolean,
        cache: MutableMap<String, Pair<Long, T>>,
        parse: (String) -> T,
    ): T? {
        val lock = synchronized(parseLocks) { parseLocks.getOrPut(path) { Mutex() } }
        return lock.withLock {
            val f = cacheFile(path)
            val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < ttl
            if (fresh || !allowNetwork) {
                val modified = f.lastModified()
                synchronized(cache) { cache[path]?.takeIf { it.first == modified } }?.let { return@withLock it.second }
            }
            val text = file(path, ttl, allowNetwork) ?: return@withLock null
            val modified = f.lastModified()
            synchronized(cache) { cache[path]?.takeIf { it.first == modified } }?.let { return@withLock it.second }
            val value = parse(text)
            synchronized(cache) { cache[path] = modified to value }
            value
        }
    }

    private fun cacheFile(path: String) = File(dir, path.replace('/', '_'))

    /** 取得開放資料檔案內容：快取未過期就直接用，否則下載；下載失敗時退回舊的快取。 */
    private suspend fun file(path: String, ttl: Long, allowNetwork: Boolean): String? =
        cachedText(path.replace('/', '_'), "$BASE_URL/$path", ttl, allowNetwork)

    /** 任意網址的文字快取（也給環境部 AQI 使用）。 */
    suspend fun cachedText(key: String, url: String, ttl: Long, allowNetwork: Boolean): String? = withContext(Dispatchers.IO) {
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        lock.withLock {
            val f = File(dir, key)
            val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < ttl
            if (fresh || !allowNetwork) return@withLock f.takeIf { it.exists() }?.readText()
            try {
                val text = download(url)
                val tmp = File(dir, "$key.tmp")
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

    /** 中央氣象署發布中的颱風（無颱風時為空）。 */
    suspend fun typhoons(allowNetwork: Boolean): List<Typhoon> =
        file("Warning/W-C0034-005.json", TYPHOON_TTL, allowNetwork)
            ?.let { runCatching { TyphoonParser.parse(it) }.getOrNull() }
            .orEmpty()

    /** 未來 1 小時雷達定量降雨預報（約 2.7MB，每 10 分鐘更新；解析結果依檔案時間快取在記憶體） */
    suspend fun nowcast(allowNetwork: Boolean): RainNowcast? {
        val path = "Forecast/F-B0046-001.json"
        val text = file(path, NOWCAST_TTL, allowNetwork) ?: return null
        val modified = cacheFile(path).lastModified()
        synchronized(this) {
            nowcastCache?.takeIf { it.first == modified }?.let { return it.second }
        }
        val parsed = withContext(Dispatchers.Default) { runCatching { RainNowcast.parse(text) }.getOrNull() }
        synchronized(this) { nowcastCache = modified to parsed }
        return parsed
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
        private const val LOW_DATA_OBSERVATION_TTL = 30 * 60_000L
        private const val LOW_DATA_FORECAST_TTL = 3 * 60 * 60_000L
        private const val MAX_STATION_KM = 10.0
        private const val TYPHOON_TTL = 30 * 60_000L
        private const val NOWCAST_TTL = 10 * 60_000L
        private const val MAX_TOWNSHIP_FILES = 6
    }
}
