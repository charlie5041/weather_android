package com.charlie.weather.data

import java.time.Duration
import java.time.LocalDateTime

import kotlin.math.ln

/** 氣象署資料摘要，供畫面顯示來源與特報。 */
data class CwaSummary(
    val stationName: String?,
    val stationDistanceKm: Double?,
    val observedAt: LocalDateTime?,
    val township: String?,
    val county: String?,
    val alerts: List<CwaAlert>,
)

/**
 * 以中央氣象署資料覆蓋 Open-Meteo：
 * - 目前天氣：最近測站的實測值（觀測時間需在 90 分鐘內）
 * - 逐時：鄉鎮預報的逐時溫度、3 小時降雨機率與天氣現象
 * - 每日：鄉鎮一週預報的最高／最低溫、降雨機率與天氣現象（超出範圍的天數仍用 Open-Meteo）
 */
fun Weather.withCwa(cwa: CwaData?, now: LocalDateTime = localNow()): Weather {
    if (cwa == null) return this
    val forecast = cwa.forecast
    val observation = cwa.observation?.takeIf { obs ->
        obs.station.time?.let { Duration.between(it, now).abs().toMinutes() <= 90 } == true
    }
    val station = observation?.station

    var current = current
    if (station != null) {
        val temp = station.temperature ?: current.temperature
        val humidity = station.humidity ?: current.humidity
        current = current.copy(
            temperature = temp,
            apparentTemperature = current.apparentTemperature + (temp - current.temperature),
            humidity = humidity,
            dewPoint = dewPoint(temp, humidity) ?: current.dewPoint,
            windSpeed = station.windSpeedMs?.times(3.6) ?: current.windSpeed,
            windDirection = station.windDirection ?: current.windDirection,
            windGusts = station.gustMs?.times(3.6) ?: current.windGusts,
            pressure = station.pressure ?: current.pressure,
            uvIndex = station.uvIndex ?: current.uvIndex,
        )
    }
    val nowBlock = forecast?.blockAt(current.time)
    val conditionText = station?.weather ?: nowBlock?.weather
    val conditionCode = station?.weather?.let { CwaCodes.fromText(it) }
        ?: nowBlock?.wmoCode()
    if (conditionText != null) {
        current = current.copy(description = conditionText, weatherCode = conditionCode ?: current.weatherCode)
    }

    val hourly = if (forecast == null) hourly else hourly.map { h ->
        val block = forecast.blockAt(h.time)
        h.copy(
            temperature = forecast.hourlyTemperature[h.time] ?: h.temperature,
            precipitationProbability = block?.precipitationProbability ?: h.precipitationProbability,
            weatherCode = block?.wmoCode() ?: h.weatherCode,
        )
    }

    val today = current.time.toLocalDate()
    val daily = daily.map { d ->
        val c = forecast?.daily?.firstOrNull { it.date == d.date }
        var max = if (c?.max != null && c.hasDaytime) c.max else d.temperatureMax
        var min = c?.min ?: d.temperatureMin
        if (d.date == today) {
            val observed = listOfNotNull(station?.dailyHigh, station?.dailyLow, station?.temperature)
            max = (observed + max).max()
            min = (observed + min).min()
        }
        d.copy(
            temperatureMax = max,
            temperatureMin = min,
            precipitationProbability = c?.precipitationProbability ?: d.precipitationProbability,
            weatherCode = c?.let { CwaCodes.fromText(it.weather.orEmpty()) ?: it.weatherCode?.let(CwaCodes::toWmo) } ?: d.weatherCode,
            description = c?.weather ?: d.description,
        )
    }

    return copy(
        current = current,
        hourly = hourly,
        daily = daily,
        cwa = CwaSummary(
            stationName = station?.name,
            stationDistanceKm = observation?.distanceKm,
            observedAt = station?.time,
            township = forecast?.township,
            county = forecast?.county ?: cwa.observation?.station?.county,
            alerts = cwa.alerts,
        ),
    )
}

/** 優先以天氣文字判斷（較可靠），再退回天氣代碼對照。 */
private fun CwaBlock.wmoCode(): Int? = weather?.let { CwaCodes.fromText(it) } ?: weatherCode?.let { CwaCodes.toWmo(it) }

/** Magnus 公式計算露點。 */
private fun dewPoint(temperature: Double, humidity: Int): Double? {
    if (humidity <= 0) return null
    val a = 17.62
    val b = 243.12
    val gamma = ln(humidity / 100.0) + a * temperature / (b + temperature)
    return b * gamma / (a - gamma)
}

