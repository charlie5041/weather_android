package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 快取或更新失敗時的舊資料要對齊到現在，不能把前幾天當成「今天」。 */
class StaleWeatherTest {

    /** 在 [fetched] 抓下來的資料：逐時從當天 0 時起 10 天，每日 10 天 */
    private fun weatherFetchedAt(fetched: LocalDateTime): Weather {
        val start = fetched.toLocalDate().atStartOfDay()
        return Weather(
            current = CurrentConditions(
                time = fetched, temperature = 30.0, apparentTemperature = 32.0, humidity = 50, dewPoint = 10.0,
                isDay = true, precipitation = 0.0, weatherCode = 0, cloudCover = 0, pressure = 1010.0,
                windSpeed = 5.0, windDirection = 90, windGusts = 10.0, visibility = 20000.0, uvIndex = 2.0,
                description = "晴",
            ),
            hourly = (0 until 240).map { i ->
                val t = start.plusHours(i.toLong())
                HourlyForecast(t, temperature = t.hour.toDouble(), weatherCode = 3, precipitationProbability = 10, precipitation = 0.0, isDay = t.hour in 6..17)
            },
            daily = (0 until 10).map { i ->
                DailyForecast(
                    date = fetched.toLocalDate().plusDays(i.toLong()), weatherCode = 3, temperatureMax = 22.0,
                    temperatureMin = 18.0, precipitationProbability = 10, precipitationSum = 0.0,
                    sunrise = null, sunset = null, uvIndexMax = 5.0,
                )
            },
            utcOffsetSeconds = 8 * 3600,
            airQuality = null,
            fetchedAtMillis = 0,
        )
    }

    @Test
    fun freshDataIsUnchanged() {
        val fetched = LocalDateTime.of(2026, 11, 15, 15, 0)
        val w = weatherFetchedAt(fetched)
        assertSame(w, w.asOf(fetched.plusMinutes(20)))
    }

    @Test
    fun cacheFromThreeDaysAgoStartsToday() {
        // 週四 03:00 抓的資料，週日 15:15 打開
        val w = weatherFetchedAt(LocalDateTime.of(2026, 11, 12, 3, 0))
        val now = LocalDateTime.of(2026, 11, 15, 15, 15)
        val aligned = w.asOf(now)!!

        assertEquals(LocalDate.of(2026, 11, 15), aligned.today!!.date)
        assertEquals(7, aligned.daily.size)
        assertEquals(LocalDate.of(2026, 11, 15), aligned.hourly.first().time.toLocalDate())
        // 目前天氣改用這個小時的逐時預報
        assertEquals(LocalDateTime.of(2026, 11, 15, 15, 0), aligned.current.time)
        assertEquals(15.0, aligned.current.temperature, 0.001)
        assertEquals(3, aligned.current.weatherCode)
        assertNull(aligned.current.description)
    }

    @Test
    fun yesterdayEveningCacheAfterMidnight() {
        // 週六 20:30 抓的，週日 00:10 打開：今天是週日，目前天氣還在 90 分鐘內以外 → 用 0 時的預報
        val w = weatherFetchedAt(LocalDateTime.of(2026, 11, 14, 20, 30))
        val aligned = w.asOf(LocalDateTime.of(2026, 11, 15, 0, 10))!!
        assertEquals(LocalDate.of(2026, 11, 15), aligned.today!!.date)
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0), aligned.current.time)
    }

    @Test
    fun recentCurrentIsKeptAcrossMidnight() {
        // 23:50 抓的，00:20 打開：目前天氣還新，保留實測值，但「今天」換成新的一天
        val w = weatherFetchedAt(LocalDateTime.of(2026, 11, 14, 23, 50))
        val aligned = w.asOf(LocalDateTime.of(2026, 11, 15, 0, 20))!!
        assertEquals(30.0, aligned.current.temperature, 0.001)
        assertEquals(LocalDate.of(2026, 11, 15), aligned.today!!.date)
    }

    @Test
    fun fullyExpiredCacheIsDropped() {
        val w = weatherFetchedAt(LocalDateTime.of(2026, 11, 1, 3, 0))
        assertNull(w.asOf(LocalDateTime.of(2026, 11, 15, 15, 0)))
    }
}
