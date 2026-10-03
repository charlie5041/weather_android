package com.charlie.weather.widget

import android.content.Context
import com.charlie.weather.data.City
import com.charlie.weather.data.Weather
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class WidgetHour(val time: LocalDateTime, val weatherCode: Int, val isDay: Boolean, val temperature: Double, val pop: Int?)

data class WidgetDay(val date: LocalDate, val weatherCode: Int, val low: Double, val high: Double, val pop: Int?)

/** 小工具顯示所需的精簡資料，於每次更新天氣時存起來。 */
data class WidgetSnapshot(
    val cityName: String,
    val isCurrentLocation: Boolean,
    val temperature: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val description: String,
    val high: Double,
    val low: Double,
    val utcOffsetSeconds: Int,
    val hours: List<WidgetHour>,
    val days: List<WidgetDay>,
    val updatedAt: Long,
) {
    fun upcomingHours(): List<WidgetHour> {
        val now = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds)).truncatedTo(ChronoUnit.HOURS)
        return hours.filter { !it.time.isBefore(now) }
    }

    fun upcomingDays(): List<WidgetDay> {
        val today = LocalDate.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
        return days.filter { !it.date.isBefore(today) }
    }
}

object WidgetSnapshotStore {
    private const val PREFS = "widget"
    private const val KEY = "snapshot"

    fun save(context: Context, city: City, weather: Weather) {
        val start = weather.current.time.truncatedTo(ChronoUnit.HOURS)
        val hours = weather.hourly.filter { !it.time.isBefore(start) }.take(30)
        val today = weather.today
        val o = JSONObject()
            .put("city", city.name)
            .put("loc", city.isCurrentLocation)
            .put("temp", weather.current.temperature)
            .put("code", weather.current.weatherCode)
            .put("day", weather.current.isDay)
            .put("desc", weather.current.description ?: com.charlie.weather.ui.WeatherCodes.description(weather.current.weatherCode))
            .put("high", today?.temperatureMax ?: 0.0)
            .put("low", today?.temperatureMin ?: 0.0)
            .put("offset", weather.utcOffsetSeconds)
            .put("updated", System.currentTimeMillis())
            .put("hours", JSONArray().apply {
                hours.forEach { h ->
                    put(JSONObject().put("t", h.time.toString()).put("c", h.weatherCode).put("d", h.isDay)
                        .put("v", h.temperature).put("p", h.precipitationProbability ?: -1))
                }
            })
            .put("days", JSONArray().apply {
                weather.daily.forEach { d ->
                    put(JSONObject().put("t", d.date.toString()).put("c", d.weatherCode)
                        .put("lo", d.temperatureMin).put("hi", d.temperatureMax).put("p", d.precipitationProbability ?: -1))
                }
            })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun load(context: Context): WidgetSnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val hours = o.getJSONArray("hours")
            val days = o.getJSONArray("days")
            WidgetSnapshot(
                cityName = o.getString("city"),
                isCurrentLocation = o.optBoolean("loc"),
                temperature = o.getDouble("temp"),
                weatherCode = o.getInt("code"),
                isDay = o.optBoolean("day", true),
                description = o.optString("desc"),
                high = o.optDouble("high"),
                low = o.optDouble("low"),
                utcOffsetSeconds = o.optInt("offset"),
                hours = (0 until hours.length()).map { i ->
                    val h = hours.getJSONObject(i)
                    WidgetHour(
                        LocalDateTime.parse(h.getString("t")), h.getInt("c"), h.optBoolean("d", true),
                        h.getDouble("v"), h.optInt("p", -1).takeIf { it >= 0 },
                    )
                },
                days = (0 until days.length()).map { i ->
                    val d = days.getJSONObject(i)
                    WidgetDay(
                        LocalDate.parse(d.getString("t")), d.getInt("c"), d.getDouble("lo"), d.getDouble("hi"),
                        d.optInt("p", -1).takeIf { it >= 0 },
                    )
                },
                updatedAt = o.optLong("updated"),
            )
        }.getOrNull()
    }
}
