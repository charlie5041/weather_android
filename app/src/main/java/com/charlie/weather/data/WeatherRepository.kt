package com.charlie.weather.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** App、背景更新與小工具共用的天氣資料來源（Open-Meteo + 中央氣象署）。 */
class WeatherRepository private constructor(context: Context) {
    val store = CityStore(context)
    private val cwa = CwaRepository(context.cacheDir)

    /** 小工具與通知使用的城市：有定位時是「我的位置」，否則是列表第一個城市。 */
    fun primaryCity(): City? = store.loadLocationCity() ?: store.loadCities().firstOrNull()

    suspend fun fetch(city: City): Weather = coroutineScope {
        val forecast = async { WeatherApi.fetchForecastJson(city.latitude, city.longitude) }
        val airQuality = async { WeatherApi.fetchAirQualityJson(city.latitude, city.longitude) }
        val cwaData = async {
            try {
                cwa.load(city.latitude, city.longitude, allowNetwork = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val forecastJson = forecast.await()
        val airQualityJson = airQuality.await()
        val now = System.currentTimeMillis()
        val weather = withContext(Dispatchers.Default) {
            WeatherApi.parse(forecastJson, airQualityJson, now).withCwa(cwaData.await())
        }
        store.saveCache(city.id, CachedWeather(forecastJson, airQualityJson, now))
        weather
    }

    /** 只用磁碟快取（不連網）組出天氣資料。 */
    suspend fun cached(city: City): Weather? = withContext(Dispatchers.Default) {
        val cache = store.loadCache(city.id) ?: return@withContext null
        val base = runCatching { WeatherApi.parse(cache.forecastJson, cache.airQualityJson, cache.fetchedAtMillis) }.getOrNull()
            ?: return@withContext null
        val cwaData = runCatching { cwa.load(city.latitude, city.longitude, allowNetwork = false) }.getOrNull()
        base.withCwa(cwaData)
    }

    companion object {
        @Volatile
        private var instance: WeatherRepository? = null

        fun get(context: Context): WeatherRepository =
            instance ?: synchronized(this) {
                instance ?: WeatherRepository(context.applicationContext).also { instance = it }
            }
    }
}
