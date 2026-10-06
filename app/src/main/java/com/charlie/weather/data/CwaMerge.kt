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
    /** 即時降雨（附近雨量站），有資料時才有值 */
    val rain: RainNow? = null,
    /** 套用在未來 6 小時逐時溫度上的實測修正量（°C） */
    val temperatureBias: Double? = null,
)

/**
 * 以中央氣象署資料覆蓋 Open-Meteo：
 * - 目前天氣：最近測站的實測值（觀測時間需在 90 分鐘內）；是否下雨以附近雨量站為準
 * - 逐時：鄉鎮預報的逐時溫度、3 小時降雨機率與天氣現象；未來 6 小時溫度依實測偏差修正
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

    // 以附近雨量站修正「現在有沒有下雨」（資料需在 40 分鐘內）
    val rain = cwa.rain?.takeIf { r -> r.time?.let { Duration.between(it, now).abs().toMinutes() <= 40 } == true }
    if (rain != null) current = current.withRain(rain)

    var hourly = if (forecast == null) hourly else hourly.map { h ->
        val block = forecast.blockAt(h.time)
        val temperature = forecast.hourlyTemperature[h.time] ?: h.temperature
        h.copy(
            temperature = temperature,
            apparentTemperature = h.apparentTemperature + (temperature - h.temperature),
            precipitationProbability = block?.precipitationProbability ?: h.precipitationProbability,
            weatherCode = block?.wmoCode() ?: h.weatherCode,
        )
    }

    // 以實測與預報的差距修正未來 6 小時的逐時溫度，並隨時間線性減弱
    val bias = station?.let { st ->
        val observed = st.temperature ?: return@let null
        val obsTime = st.time ?: return@let null
        val forecastAtObs = interpolateTemperature(hourly, obsTime) ?: return@let null
        (observed - forecastAtObs).takeIf { kotlin.math.abs(it) in 0.3..8.0 }
    }
    if (bias != null && station?.time != null) {
        val obsTime = station.time
        hourly = hourly.map { h ->
            val hoursAhead = Duration.between(obsTime, h.time).toMinutes() / 60.0
            if (hoursAhead <= 0 || hoursAhead >= BIAS_HOURS) return@map h
            val delta = bias * (1 - hoursAhead / BIAS_HOURS)
            h.copy(temperature = h.temperature + delta, apparentTemperature = h.apparentTemperature + delta)
        }
    }

    val today = current.time.toLocalDate()
    val daily = daily.map { d ->
        val c = forecast?.daily?.firstOrNull { it.date == d.date }
        var max = if (c?.max != null && c.hasDaytime) c.max else d.temperatureMax
        var min = c?.min ?: d.temperatureMin
        if (d.date == today) {
            // 今日高低溫也要涵蓋實測值與修正後的剩餘時段
            val upcoming = if (bias != null) {
                hourly.filter { it.time.toLocalDate() == today && it.time.isAfter(current.time) }.map { it.temperature }
            } else {
                emptyList()
            }
            val observed = listOfNotNull(station?.dailyHigh, station?.dailyLow, station?.temperature) + upcoming
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
            rain = rain,
            temperatureBias = bias,
        ),
    )
}

private const val BIAS_HOURS = 6.0

private fun isRainCode(code: Int) = code in 51..67 || code in 80..82 || code in 95..99

/**
 * 依雨量站修正目前天氣：
 * - 附近有雨、但天氣描述沒有雨 → 依雨勢改成小雨／雨／大雨／豪雨
 * - 天氣描述有雨、但附近雨量站一小時內都沒有雨 → 改成陰或多雲（雷雨除外，雷雨可能還沒落到雨量站）
 */
private fun CurrentConditions.withRain(rain: RainNow): CurrentConditions {
    val thunder = weatherCode in 95..99
    return when {
        rain.raining && !isRainCode(weatherCode) -> {
            val (code, text) = when {
                rain.ratePerHour >= 40 -> 65 to "豪雨"
                rain.ratePerHour >= 10 -> 65 to "大雨"
                rain.ratePerHour >= 2.5 -> 63 to "雨"
                else -> 61 to "小雨"
            }
            copy(weatherCode = code, description = text, precipitation = maxOf(precipitation, rain.pastHour))
        }
        rain.raining -> copy(precipitation = maxOf(precipitation, rain.pastHour))
        !rain.raining && rain.pastHour == 0.0 && isRainCode(weatherCode) && !thunder -> {
            val overcast = description?.contains("陰") == true || weatherCode == 3
            copy(weatherCode = if (overcast) 3 else 2, description = if (overcast) "陰" else "多雲", precipitation = 0.0)
        }
        else -> this
    }
}

/** 逐時溫度在任意時間點的線性內插 */
private fun interpolateTemperature(hourly: List<HourlyForecast>, time: LocalDateTime): Double? {
    val before = hourly.lastOrNull { !it.time.isAfter(time) } ?: return null
    val after = hourly.firstOrNull { it.time.isAfter(time) } ?: return before.temperature
    val span = Duration.between(before.time, after.time).toMinutes().toDouble()
    if (span <= 0) return before.temperature
    val t = Duration.between(before.time, time).toMinutes() / span
    return before.temperature + (after.temperature - before.temperature) * t
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

