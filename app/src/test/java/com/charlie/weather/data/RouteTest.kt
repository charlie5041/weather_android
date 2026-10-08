package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RouteTest {
    private val home = City("addr_h", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "住家")
    private val work = City("addr_w", "信義區", "臺北市信義區", 25.03, 121.56, label = "公司")
    private val now = LocalDateTime.of(2026, 10, 6, 7, 0)

    private fun hour(time: LocalDateTime, pop: Int, code: Int = 2, mm: Double = 0.0) =
        HourlyForecast(time, 25.0, code, pop, mm, isDay = true)

    /** 07:00 起每小時的預報 */
    private fun weather(vararg pops: Int) = Weather(
        current = CurrentConditions(now, 25.0, 25.0, 70, 18.0, true, 0.0, 1, 30, 1012.0, 10.0, 90, 15.0, 20_000.0, 3.0),
        hourly = pops.mapIndexed { i, p -> hour(now.plusHours(i.toLong()), p, code = if (p >= 50) 61 else 2) },
        daily = emptyList(),
        utcOffsetSeconds = 8 * 3600,
        airQuality = null,
        fetchedAtMillis = 0,
    )

    @Test
    fun parsesOsrmRoute() {
        val json = """
            {"code":"Ok","routes":[{"distance":8123.4,"duration":1260.0,
              "geometry":{"type":"LineString","coordinates":[[121.57,25.08],[121.565,25.05],[121.56,25.03]]}}]}
        """.trimIndent()
        val path = RouteApi.parse(json)!!
        assertEquals(3, path.points.size)
        assertEquals(LatLon(25.08, 121.57), path.points.first())
        assertEquals(8.1234, path.distanceKm, 1e-6)
        assertEquals(21.0, path.durationMinutes, 1e-6)
        assertFalse(path.approximate)
        assertNull(RouteApi.parse("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun straightLineFallbackUsesModeSpeed() {
        val path = RouteApi.straightLine(LatLon(25.08, 121.57), LatLon(25.03, 121.56), TravelMode.SCOOTER)
        assertTrue(path.approximate)
        assertEquals(path.distanceKm / TravelMode.SCOOTER.fallbackKmh * 60, path.durationMinutes, 1e-6)
    }

    @Test
    fun viaPointsAreRoutedInOrder() {
        val url = RouteApi.url(LatLon(25.03, 121.56), LatLon(24.83, 121.77), TravelMode.CAR, listOf(LatLon(24.94, 121.71)))
        assertTrue(url.contains("/121.56000,25.03000;121.71000,24.94000;121.77000,24.83000?"))
        val line = RouteApi.straightLine(LatLon(25.03, 121.56), LatLon(24.83, 121.77), TravelMode.CAR, listOf(LatLon(24.94, 121.71)))
        assertEquals(3, line.points.size)
    }

    @Test
    fun osmDurationIsRaisedToCityPace() {
        // OSM 算 14.1 公里只要 15 分鐘（依速限、沒有紅綠燈），市區機車約 25 km/h → 約 34 分鐘
        val osm = RoutePath(listOf(LatLon(25.03, 121.49), LatLon(25.06, 121.57)), 14.1, 15.0)
        assertEquals(33.84, RouteApi.withCityPace(osm, TravelMode.SCOOTER).durationMinutes, 0.01)
        // 已經比市區平均慢時維持原值
        val slow = osm.copy(durationMinutes = 50.0)
        assertEquals(50.0, RouteApi.withCityPace(slow, TravelMode.SCOOTER).durationMinutes, 0.0)
    }

    @Test
    fun samplesEveryFewKilometersIncludingEnds() {
        val path = RoutePath(listOf(LatLon(25.0, 121.5), LatLon(25.1, 121.5)), 11.1, 30.0)
        val points = RoutePlanner.sample(path)
        // 約 11 公里、每 3 公里一段 → 4 段 5 點
        assertEquals(5, points.size)
        assertEquals(25.0, points.first().position.latitude, 1e-9)
        assertEquals(25.1, points.last().position.latitude, 1e-9)
        assertEquals(0.5, points[2].fraction, 1e-9)
        assertEquals(25.05, points[2].position.latitude, 1e-6)

        val long = RoutePath(listOf(LatLon(22.6, 120.3), LatLon(25.0, 121.5)), 350.0, 240.0)
        assertEquals(RoutePlanner.MAX_POINTS, RoutePlanner.sample(long).size)
    }

    @Test
    fun groupsConsecutiveStopsInSameTownship() {
        val places = listOf("新店區", "新店區", "坪林區", "坪林區", "坪林區", "頭城鎮", "礁溪鄉", "礁溪鄉")
        val stops = places.mapIndexed { i, place ->
            RouteStop(RoutePoint(LatLon(25.0, 121.5), i / 7.0, i * 10.0), now.plusMinutes(10L * i), place, null, rainingNow = i == 3)
        }
        val groups = RoutePlanner.group(stops)
        // 起點與終點各自一行；中途的坪林區三點合併
        assertEquals(listOf(1, 1, 3, 1, 1, 1), groups.map { it.stops.size })
        assertEquals(listOf("新店區", "新店區", "坪林區", "頭城鎮", "礁溪鄉", "礁溪鄉"), groups.map { it.first.place })
        // 正在下雨的點代表坪林區這一段
        assertEquals(stops[3], groups[2].worst)
    }

    private fun data(weathers: List<Weather?>): RouteData {
        val path = RoutePath(listOf(LatLon(25.08, 121.57), LatLon(25.03, 121.56)), 9.0, 120.0)
        val points = RoutePlanner.sample(path)
        assertEquals(weathers.size, points.size)
        return RouteData(home, work, TravelMode.SCOOTER, path, points, weathers)
    }

    @Test
    fun stopsUseForecastAtEachEta() {
        // 4 點、行程 2 小時：07:00、07:40、08:20、09:00 經過，各取最接近的整點預報
        val dry = weather(0, 10, 10, 10, 10)
        val wetLater = weather(0, 80, 80, 80, 80)
        val forecast = RoutePlanner.evaluate(data(listOf(dry, dry, wetLater, wetLater)), now, now)
        assertEquals(listOf(7, 7, 8, 9), forecast.stops.map { it.eta.hour })
        assertEquals(listOf(0, 10, 80, 80), forecast.stops.map { it.probability })
        assertTrue(forecast.summary, forecast.summary.contains("08:20"))
        assertTrue(forecast.summary, forecast.summary.contains("雨衣"))
        assertEquals(now.plusHours(2), forecast.arrival)
    }

    @Test
    fun suggestsLaterDepartureWhenRainPasses() {
        // 雨在 7–8 點，9 點後停
        val w = weather(90, 90, 10, 10, 10, 10)
        val path = RoutePath(listOf(LatLon(25.08, 121.57), LatLon(25.07, 121.57)), 1.0, 20.0)
        val points = RoutePlanner.sample(path)
        val forecast = RoutePlanner.evaluate(RouteData(home, work, TravelMode.SCOOTER, path, points, points.map { w }), now, now)
        assertEquals(90, forecast.maxProbability)
        val (time, pop) = forecast.betterDeparture!!
        assertEquals(10, pop)
        assertEquals(now.plusHours(2), time)
        assertTrue(forecast.summary, forecast.summary.contains("09:00 出發"))
    }

    @Test
    fun dryRouteHasNoSuggestion() {
        val w = weather(0, 0, 0, 0, 0)
        val forecast = RoutePlanner.evaluate(data(listOf(w, w, w, w)), now, now)
        assertEquals("沿途降雨機率低，適合出發", forecast.summary)
        assertNull(forecast.betterDeparture)
    }

    @Test
    fun timelineMergesNeighboursWithSameRain() {
        // 07:00 乾、07:40 沒資料、08:20 與 09:00 降雨 80%
        val dry = weather(0, 10, 10, 10, 10)
        val wetLater = weather(0, 80, 80, 80, 80)
        val forecast = RoutePlanner.evaluate(data(listOf(dry, null, wetLater, wetLater)), now, now)
        val spans = RoutePlanner.timeline(forecast.stops)
        assertEquals(listOf(RainLevel.DRY, RainLevel.UNKNOWN, RainLevel.WET), spans.map { it.level })
        // 每點代表到前後兩點中間；兩個會下雨的點合併成一段直到抵達
        assertEquals(0.0, spans[0].start, 1e-9)
        assertEquals(1 / 6.0, spans[1].start, 1e-9)
        assertEquals(0.5, spans[2].start, 1e-9)
        assertEquals(1.0, spans[2].end, 1e-9)
    }

    @Test
    fun rainLevelGradesProbabilityAndIntensity() {
        fun stop(hour: HourlyForecast?, raining: Boolean = false) =
            RouteStop(RoutePoint(LatLon(25.0, 121.5), 0.0, 0.0), now, null, hour, raining)
        assertEquals(RainLevel.UNKNOWN, stop(null).rainLevel)
        assertEquals(RainLevel.DRY, stop(hour(now, 20)).rainLevel)
        assertEquals(RainLevel.MAYBE, stop(hour(now, 30)).rainLevel)
        assertEquals(RainLevel.WET, stop(hour(now, 60, code = 61)).rainLevel)
        assertEquals(RainLevel.HEAVY, stop(hour(now, 90, code = 63, mm = 12.0)).rainLevel)
        assertEquals(RainLevel.HEAVY, stop(null, raining = true).rainLevel)
    }
}
