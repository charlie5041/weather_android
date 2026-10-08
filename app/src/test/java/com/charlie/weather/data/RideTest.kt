package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RideTest {
    private val now = LocalDateTime.of(2026, 10, 6, 7, 0)
    private val home = City("a", "內湖區", "", 25.0, 121.5, label = "住家")
    private val work = City("b", "信義區", "", 25.1, 121.5, label = "公司")
    // 約 11 公里、60 分鐘的南北向直線
    private val path = RoutePath(listOf(LatLon(25.0, 121.5), LatLon(25.05, 121.5), LatLon(25.1, 121.5)), 11.1, 60.0)

    private fun weather(vararg pops: Int) = Weather(
        current = CurrentConditions(now, 25.0, 25.0, 70, 18.0, true, 0.0, 1, 30, 1012.0, 10.0, 90, 15.0, 20_000.0, 3.0),
        hourly = pops.mapIndexed { i, p -> HourlyForecast(now.plusHours(i.toLong()), 25.0, if (p >= 50) 61 else 2, p, 0.0, true) },
        daily = emptyList(),
        utcOffsetSeconds = 8 * 3600,
        airQuality = null,
        fetchedAtMillis = 0,
    )

    @Test
    fun progressProjectsOntoTheRoute() {
        val mid = RideTracker.progress(path, LatLon(25.05, 121.501))
        assertEquals(0.5, mid.fraction, 0.01)
        assertTrue(mid.offRouteKm < 0.2)
        assertEquals(5.55, mid.remainingKm, 0.1)
        // 偏離路線 2 公里
        assertTrue(RideTracker.progress(path, LatLon(25.05, 121.52)).offRouteKm > RideTracker.OFF_ROUTE_KM)
    }

    @Test
    fun statusWarnsAboutRainAheadAndArrival() {
        val points = RoutePlanner.sample(path)
        // 前半段乾，後半段 8 點起會下雨
        val dry = weather(0, 0, 0)
        val wetLater = weather(0, 80, 80)
        val data = RouteData(home, work, TravelMode.SCOOTER, path, points, points.map { if (it.fraction >= 0.75) wetLater else dry })

        // 7:40 騎到一半：終點附近在 8 點後經過，會下雨
        val t = now.plusMinutes(40)
        val half = RideTracker.status(data, RideTracker.progress(path, LatLon(25.05, 121.5)), t)
        assertFalse(half.arrived)
        val rain = half.rainAhead!!
        assertTrue(rain.point.fraction >= 0.75)
        assertTrue(half.title, half.title.startsWith("約 "))
        assertEquals(java.time.Duration.between(t, rain.eta).toMinutes(), half.minutesToRain)
        assertTrue(half.text, half.text.contains("抵達"))

        // 已經在終點
        val done = RideTracker.status(data, RideTracker.progress(path, LatLon(25.0995, 121.5)), t)
        assertTrue(done.arrived)
        assertNull(done.rainAhead)

        // 整路都乾
        val allDry = data.copy(weathers = points.map { dry })
        assertEquals("前方不太會下雨", RideTracker.status(allDry, RideTracker.progress(path, LatLon(25.01, 121.5)), now).title)
    }
}
