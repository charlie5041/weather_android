package com.charlie.weather.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedRoute(val from: City, val to: City, val mode: TravelMode, val via: List<City> = emptyList())

data class CachedWeather(val forecastJson: String, val airQualityJson: String?, val fetchedAtMillis: Long)

/** 以 SharedPreferences 保存城市清單與最近一次的天氣資料（開啟 App 時可立即顯示）。 */
class CityStore(context: Context) {
    private val prefs = context.getSharedPreferences("weather_store", Context.MODE_PRIVATE)

    fun loadCities(): List<City> {
        val raw = prefs.getString(KEY_CITIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).toCity() }
        }.getOrDefault(emptyList())
    }

    fun saveCities(cities: List<City>) {
        val arr = JSONArray()
        cities.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CITIES, arr.toString()).apply()
    }

    fun loadLocationCity(): City? =
        prefs.getString(KEY_LOCATION, null)?.let { runCatching { JSONObject(it).toCity() }.getOrNull() }

    fun saveLocationCity(city: City?) {
        prefs.edit().apply {
            if (city == null) remove(KEY_LOCATION) else putString(KEY_LOCATION, city.toJson().toString())
        }.apply()
    }

    fun loadCache(cityId: String): CachedWeather? {
        val raw = prefs.getString("cache_$cityId", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CachedWeather(
                forecastJson = o.getString("forecast"),
                airQualityJson = if (o.isNull("aq")) null else o.getString("aq"),
                fetchedAtMillis = o.getLong("at"),
            )
        }.getOrNull()
    }

    fun saveCache(cityId: String, cache: CachedWeather) {
        val o = JSONObject()
            .put("forecast", cache.forecastJson)
            .put("aq", cache.airQualityJson ?: JSONObject.NULL)
            .put("at", cache.fetchedAtMillis)
        prefs.edit().putString("cache_$cityId", o.toString()).apply()
    }

    fun removeCache(cityId: String) {
        prefs.edit().remove("cache_$cityId").apply()
    }

    /** 上次在「路線降雨」查詢的起點、終點與交通方式 */
    fun loadLastRoute(): SavedRoute? {
        val raw = prefs.getString(KEY_LAST_ROUTE, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            SavedRoute(
                from = o.getJSONObject("from").toCity(),
                to = o.getJSONObject("to").toCity(),
                mode = TravelMode.entries.firstOrNull { it.name == o.optString("mode") } ?: TravelMode.SCOOTER,
                via = o.optJSONArray("via")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).toCity() } }.orEmpty(),
            )
        }.getOrNull()
    }

    fun saveLastRoute(route: SavedRoute) {
        val o = JSONObject()
            .put("from", route.from.toJson())
            .put("to", route.to.toJson())
            .put("mode", route.mode.name)
            .put("via", JSONArray(route.via.map { it.toJson() }))
        prefs.edit().putString(KEY_LAST_ROUTE, o.toString()).apply()
    }

    private fun City.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("subtitle", subtitle)
        .put("lat", latitude)
        .put("lon", longitude)
        .put("loc", isCurrentLocation)
        .put("label", label ?: JSONObject.NULL)
        .put("address", address ?: JSONObject.NULL)

    private fun JSONObject.toCity() = City(
        id = getString("id"),
        name = getString("name"),
        subtitle = optString("subtitle"),
        latitude = getDouble("lat"),
        longitude = getDouble("lon"),
        isCurrentLocation = optBoolean("loc"),
        label = optString("label").takeIf { !isNull("label") && it.isNotBlank() },
        address = optString("address").takeIf { !isNull("address") && it.isNotBlank() },
    )

    private companion object {
        const val KEY_CITIES = "cities"
        const val KEY_LOCATION = "location_city"
        const val KEY_LAST_ROUTE = "last_route"
    }
}
