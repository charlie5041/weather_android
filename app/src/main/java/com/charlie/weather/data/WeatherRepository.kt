package com.charlie.weather.data

import android.content.Context
import com.charlie.weather.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** App、背景更新與小工具共用的天氣資料來源（Open-Meteo + 中央氣象署）。 */
class WeatherRepository private constructor(context: Context) {
    val store = CityStore(context)
    private val settings = AppSettings(context)
    private val cwa = CwaRepository(context.cacheDir)

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
