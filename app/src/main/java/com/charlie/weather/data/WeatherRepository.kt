package com.charlie.weather.data

import android.content.Context
import com.charlie.weather.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime

/** App、背景更新與小工具共用的天氣資料來源（Open-Meteo + 中央氣象署）。 */
class WeatherRepository private constructor(context: Context) {
    val store = CityStore(context)
    private val settings = AppSettings(context)
    private val cwa = CwaRepository(context.cacheDir)
    private val googleRoutes = BuildConfig.GOOGLE_MAPS_API_KEY.takeIf { it.isNotBlank() }?.let {
        GoogleRoutes(it, context.packageName, GoogleRoutes.certSha1(context))
    }

    private val tdx = BuildConfig.TDX_CLIENT_ID.takeIf { it.isNotBlank() && BuildConfig.TDX_CLIENT_SECRET.isNotBlank() }?.let {
        TdxRoadEvents(it, BuildConfig.TDX_CLIENT_SECRET)
    }

    /** 有 Google 金鑰：行車時間含路況，會隨出發時間改變 */
    val trafficAwareRoutes: Boolean get() = googleRoutes != null

    /** 小工具與通知使用的城市：有定位時是「我的位置」，否則是列表第一個城市。 */
    fun primaryCity(): City? = store.loadLocationCity() ?: store.loadCities().firstOrNull()

    /** @param lowData 背景更新且使用行動網路時為 true，減少下載量 */
    suspend fun fetch(city: City, lowData: Boolean = false): Weather = coroutineScope {
        val forecast = async { WeatherApi.fetchForecastJson(city.latitude, city.longitude) }
        val airQuality = async { WeatherApi.fetchAirQualityJson(city.latitude, city.longitude) }
        val cwaData = async {
            if (!settings.useCwa) return@async null
            try {
                cwa.load(city.latitude, city.longitude, allowNetwork = true, lowData = lowData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val typhoons = async { typhoonsFor(city, allowNetwork = true) }
        val moenv = async { moenvStation(city, allowNetwork = true) }
        val forecastJson = forecast.await()
        val airQualityJson = airQuality.await()
        val now = System.currentTimeMillis()
        val weather = withContext(Dispatchers.Default) {
            WeatherApi.parse(forecastJson, airQualityJson, now).withCwa(cwaData.await())
        }
        store.saveCache(city.id, CachedWeather(forecastJson, airQualityJson, now))
        weather.withExtras(typhoons.await(), moenv.await())
    }

    /**
     * 規劃 [from] 到 [to] 的路線，並取得沿途各取樣點的逐時預報（台灣套用氣象署鄉鎮預報與雨量站）。
     * 預報一次查完，之後改出發時間只要用 [RoutePlanner.evaluate] 重新計算
     * （使用 Google 路況時，行車時間隨出發時間改變，需要重新查詢）。
     */
    suspend fun routeData(
        from: City,
        to: City,
        mode: TravelMode,
        departure: LocalDateTime? = null,
        via: List<City> = emptyList(),
    ): RouteData = routeOptions(from, to, mode, departure, via).first()

    /**
     * 同 [routeData]，[alternatives] 時另外規劃最多兩條替代路線，每條都查沿途天氣
     * （所有取樣點一次向 Open-Meteo 查詢）。第一條是建議路線。
     */
    suspend fun routeOptions(
        from: City,
        to: City,
        mode: TravelMode,
        departure: LocalDateTime? = null,
        via: List<City> = emptyList(),
        alternatives: Boolean = false,
    ): List<RouteData> = coroutineScope {
        fun City.latLon() = LatLon(latitude, longitude)
        val paths = RouteApi.routes(from.latLon(), to.latLon(), mode, departure, googleRoutes, via.map { it.latLon() }, alternatives)
        val sampled = paths.map { RoutePlanner.sample(it, stepKm = settings.routeStepKm.toDouble()) }
        val points = sampled.flatten()
        val forecasts = async { WeatherApi.fetchForecastJsons(points.map { it.position }, RoutePlanner.forecastDays(departure)) }
        val cwaData = async {
            if (!settings.useCwa) return@async points.map<RoutePoint, CwaData?> { null }
            // 第一個點先下載共用的檔案（測站、縣市預報、雨量站），其他點再並行使用快取
            val first = cwaAt(points.first().position)
            listOf(first) + points.drop(1).map { p -> async { cwaAt(p.position) } }.map { it.await() }
        }
        // 雷達短時預報只在 1 小時內出發時有用，其他時候不下載（約 2.7MB）
        val nowcast = async {
            val minutes = departure?.let { Duration.between(LocalDateTime.now(), it).toMinutes() } ?: 0
            if (!settings.useCwa || minutes !in -15..60) return@async null
            try {
                cwa.nowcast(allowNetwork = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        // 道路事件是「現在」的狀況，只在 3 小時內出發時查
        val events = async {
            val minutes = departure?.let { Duration.between(LocalDateTime.now(), it).toMinutes() } ?: 0
            if (tdx == null || minutes !in -15..180) return@async emptyList()
            tdx.events()
        }
        val now = System.currentTimeMillis()
        val jsons = forecasts.await()
        val cwas = cwaData.await()
        val radar = nowcast.await()
        val allEvents = events.await()
        val weathers = withContext(Dispatchers.Default) {
            points.indices.map { i ->
                jsons.getOrNull(i)?.let { json ->
                    runCatching { WeatherApi.parse(json, null, now).withCwa(cwas.getOrNull(i)) }.getOrNull()
                }
            }
        }
        var offset = 0
        paths.mapIndexed { k, path ->
            val count = sampled[k].size
            val near = withContext(Dispatchers.Default) { TdxRoadEvents.near(allEvents, path.points) }
            RouteData(from, to, mode, path, sampled[k], weathers.subList(offset, offset + count), via, radar, near).also { offset += count }
        }
    }

    /** 最新的雷達短時預報（騎乘中每 10 分鐘更新一次）；不使用氣象署資料或失敗時為 null */
    suspend fun nowcast(): RainNowcast? {
        if (!settings.useCwa) return null
        return try {
            cwa.nowcast(allowNetwork = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun cwaAt(p: LatLon): CwaData? = try {
        cwa.load(p.latitude, p.longitude, allowNetwork = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 東亞範圍內的城市才顯示颱風資訊。 */
    private suspend fun typhoonsFor(city: City, allowNetwork: Boolean): List<Typhoon> {
        if (!settings.useCwa || city.latitude !in -5.0..50.0 || city.longitude !in 95.0..165.0) return emptyList()
        return try {
            cwa.typhoons(allowNetwork)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 台灣城市：最近的環境部空品測站（需要 MOENV_API_KEY）。 */
    private suspend fun moenvStation(city: City, allowNetwork: Boolean): MoenvStation? {
        val key = BuildConfig.MOENV_API_KEY
        if (key.isBlank() || city.latitude !in 21.5..26.6 || city.longitude !in 118.0..122.6) return null
        return try {
            val url = "${MoenvParser.DATASET_URL}?api_key=$key&limit=1000&format=JSON"
            cwa.cachedText("moenv_aqx_p_432.json", url, MOENV_TTL, allowNetwork)
                ?.let { MoenvParser.nearest(MoenvParser.parse(it), city.latitude, city.longitude) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun Weather.withExtras(typhoons: List<Typhoon>, moenv: MoenvStation?): Weather = copy(
        typhoons = typhoons,
        airQuality = moenv?.let {
            AirQuality(
                usAqi = it.aqi ?: return@let null,
                pm25 = it.pm25,
                pm10 = it.pm10,
                stationName = it.name,
                status = it.status,
                pollutant = it.pollutant,
            )
        } ?: airQuality,
    )

    /** 只用磁碟快取（不連網）組出天氣資料。 */
    suspend fun cached(city: City): Weather? = withContext(Dispatchers.Default) {
        val cache = store.loadCache(city.id) ?: return@withContext null
        val base = runCatching { WeatherApi.parse(cache.forecastJson, cache.airQualityJson, cache.fetchedAtMillis) }.getOrNull()
            ?: return@withContext null
        val cwaData = if (settings.useCwa) runCatching { cwa.load(city.latitude, city.longitude, allowNetwork = false) }.getOrNull() else null
        base.withCwa(cwaData).withExtras(typhoonsFor(city, allowNetwork = false), moenvStation(city, allowNetwork = false))
    }

    companion object {
        private const val MOENV_TTL = 30 * 60_000L

        @Volatile
        private var instance: WeatherRepository? = null

        fun get(context: Context): WeatherRepository =
            instance ?: synchronized(this) {
                instance ?: WeatherRepository(context.applicationContext).also { instance = it }
            }
    }
}
