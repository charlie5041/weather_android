package com.charlie.weather.sync

import com.charlie.weather.data.City
import com.charlie.weather.data.CurrentConditions
import com.charlie.weather.data.HourlyForecast
import com.charlie.weather.data.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RainAlertTest {
    private val here = City("loc", "瑞光里", "臺北市內湖區", 25.07, 121.57, isCurrentLocation = true)
    private val work = City("addr_w", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "公司")
    private val home = City("addr_h", "板橋區", "新北市板橋區", 25.01, 121.46, label = "住家")

    private val now = LocalDateTime.of(2026, 10, 21, 19, 0)

    private fun weather(code: Int, pop: Int, mm: Double = 0.5) = Weather(
        current = CurrentConditions(now, 25.0, 25.0, 70, 18.0, false, 0.0, 3, 30, 1012.0, 10.0, 90, 15.0, 20_000.0, 0.0),
        hourly = listOf(HourlyForecast(now.plusHours(1), 24.0, code, pop, mm, isDay = false)),
        daily = emptyList(),
        utcOffsetSeconds = 8 * 3600,
        airQuality = null,
        fetchedAtMillis = 0,
    )

    @Test
    fun routeDepartingSoonCoversRainAlert() {
        // now 是 19:00；19:30 出發的常用路線會在出發提醒裡講沿路的雨
        val evening = com.charlie.weather.data.FavoriteRoute(
            "b", "下班", work, home, com.charlie.weather.data.TravelMode.SCOOTER,
            reminder = com.charlie.weather.data.RouteReminder(java.time.DayOfWeek.values().toSet(), 19, 30),
        )
        val plain = com.charlie.weather.data.FavoriteRoute("c", "回老家", home, work, com.charlie.weather.data.TravelMode.CAR)
        assertEquals(true, WeatherNotifier.routeCoversRain(true, listOf(plain, evening), now))
        // 出發提醒關掉時，降雨提醒照常
        assertEquals(false, WeatherNotifier.routeCoversRain(false, listOf(evening), now))
        assertEquals(false, WeatherNotifier.routeCoversRain(true, listOf(plain), now))
        assertEquals(false, WeatherNotifier.routeCoversRain(true, listOf(evening), now.minusHours(3)))
    }

    @Test
    fun rainSoonGivesAlert() {
        val alert = WeatherNotifier.rainAlert(weather(code = 80, pop = 80))!!
        assertEquals("即將下雨", alert.headline)
        assertEquals("約下午8時起可能短暫陣雨，降雨機率 80%。記得帶傘！", alert.text)
    }

    @Test
    fun lowChanceGivesNoAlert() {
        assertNull(WeatherNotifier.rainAlert(weather(code = 80, pop = 40)))
    }

    @Test
    fun identicalAlertsMergeIntoOne() {
        val rain = WeatherNotifier.rainAlert(weather(code = 80, pop = 80))!!
        val storm = WeatherNotifier.rainAlert(weather(code = 95, pop = 90))!!
        val merged = WeatherNotifier.mergeRainAlerts(listOf(here to rain, work to rain, home to storm))
        assertEquals(2, merged.size)
        assertEquals(listOf(here, work), merged[0].first)
        assertEquals(rain, merged[0].second)
        assertEquals(listOf(home), merged[1].first)
    }
}
