package com.charlie.weather.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

data class City(
    val id: String,
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val isCurrentLocation: Boolean = false,
)

data class CurrentConditions(
    val time: LocalDateTime,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val dewPoint: Double,
    val isDay: Boolean,
    val precipitation: Double,
    val weatherCode: Int,
    val cloudCover: Int,
    val pressure: Double,
    val windSpeed: Double,
    val windDirection: Int,
    val windGusts: Double,
    val visibility: Double,
    val uvIndex: Double,
    /** 氣象署的天氣描述（例如「多雲時晴」）；沒有時以天氣代碼產生 */
    val description: String? = null,
)

data class HourlyForecast(
    val time: LocalDateTime,
    val temperature: Double,
    val weatherCode: Int,
    val precipitationProbability: Int?,
    val precipitation: Double,
    val isDay: Boolean,
    val humidity: Int? = null,
    val apparentTemperature: Double = Double.NaN,
    val windSpeed: Double = Double.NaN,
    val windGusts: Double = Double.NaN,
    val uvIndex: Double = Double.NaN,
)

data class DailyForecast(
    val date: LocalDate,
    val weatherCode: Int,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val precipitationProbability: Int?,
    val precipitationSum: Double,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val uvIndexMax: Double,
    val description: String? = null,
)

data class AirQuality(
    val usAqi: Int,
    val pm25: Double?,
    val pm10: Double?,
)

data class Weather(
    val current: CurrentConditions,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
    val utcOffsetSeconds: Int,
    val airQuality: AirQuality?,
    val fetchedAtMillis: Long,
    /** 有套用中央氣象署資料時才有值 */
    val cwa: CwaSummary? = null,
) {
    /** 城市當地的現在時間 */
    fun localNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    val today: DailyForecast? get() = daily.firstOrNull()
}
