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

    @Test
    fun verdictLeadsWithWhenRainStarts() {
        val dry = weather(0, 10, 10, 10, 10)
        val wetLater = weather(0, 80, 80, 80, 80)
        val verdict = RoutePlanner.evaluate(data(listOf(dry, dry, wetLater, wetLater)), now, now).verdict
        assertEquals("08:20 起可能下雨", verdict.title)
        assertTrue(verdict.detail, verdict.detail.contains("80%"))
        assertTrue(verdict.detail, verdict.detail.contains("雨衣"))

        val allDry = weather(0, 0, 0, 0, 0)
        val dryVerdict = RoutePlanner.evaluate(data(listOf(allDry, allDry, allDry, allDry)), now, now).verdict
        assertEquals("沿途不太會下雨", dryVerdict.title)

        val allWet = weather(90, 90, 90, 90, 90)
        assertEquals("一出發就可能下雨", RoutePlanner.evaluate(data(listOf(allWet, dry, dry, dry)), now, now).verdict.title)
        assertEquals("沿途都會下雨", RoutePlanner.evaluate(data(listOf(allWet, allWet, allWet, allWet)), now, now).verdict.title)
        assertEquals("暫無預報", RoutePlanner.evaluate(data(listOf(null, null, null, null)), now, now).verdict.title)
    }

    @Test
    fun comparesDeparturesAndRecommendsDrierOne() {
        // 雨在 7–8 點，9 點後停；20 分鐘的路
        val w = weather(90, 90, 10, 10, 10, 10)
        val path = RoutePath(listOf(LatLon(25.08, 121.57), LatLon(25.07, 121.57)), 1.0, 20.0)
        val points = RoutePlanner.sample(path)
        val route = RouteData(home, work, TravelMode.SCOOTER, path, points, points.map { w })
        val departures = listOf(0L, 60L, 120L, 180L).map { now.plusMinutes(it) }
        val options = RoutePlanner.compare(route, departures, now)
        assertEquals(listOf(90, 90, 10, 10), options.map { it.risk })
        assertEquals(RainLevel.WET, options[0].level)
        assertEquals(RainLevel.DRY, options[2].level)
        // 同樣乾時取較早的
        assertEquals(now.plusHours(2), RoutePlanner.recommended(options, now)?.departure)
        // 已經選了乾的時間就不再建議
        assertNull(RoutePlanner.recommended(options, now.plusHours(2)))
        // 超出預報範圍的時間不建議
        val far = RoutePlanner.compare(route, listOf(now, now.plusDays(3)), now)
        assertEquals(RainLevel.UNKNOWN, far[1].level)
        assertNull(RoutePlanner.recommended(far, now))
    }

    @Test
    fun hazardsAlongTheRoute() {
        val start = now.withHour(17)
        fun h(time: LocalDateTime, temp: Double, gust: Double = 20.0) =
            HourlyForecast(time, temp, 2, 0, 0.0, isDay = true, apparentTemperature = temp, windSpeed = 10.0, windGusts = gust, uvIndex = 0.0)
        // 17:30 日落
        val day = DailyForecast(start.toLocalDate(), 2, 20.0, 12.0, 0, 0.0, null, start.withMinute(30), 3.0)
        val w = weather(0).copy(daily = listOf(day))
        val route = data(listOf(w, w, w, w))
        val rain = CwaAlert("大雨", "特報", null, start.plusHours(3))
        val wind = CwaAlert("陸上強風", "特報", start.plusHours(4), null)
        fun stop(i: Int, temp: Double, county: String, alerts: List<CwaAlert>, gust: Double = 20.0): RouteStop {
            val eta = start.plusMinutes(40L * i)
            return RouteStop(route.points[i], eta, null, h(eta, temp, gust), false, county, alerts)
        }
        val stops = listOf(
            stop(0, 13.0, "苗栗縣", listOf(rain)),
            stop(1, 14.0, "臺中市", listOf(rain, wind), gust = 45.0),
            stop(2, 16.0, "臺中市", listOf(rain, wind)),
            stop(3, 16.0, "彰化縣", emptyList()),
        )

        val scooter = RouteForecast(route, start, stops).hazards
        // 強風特報 21:00 才生效，經過時還沒開始
        assertEquals(RouteHazard.Alert("大雨特報", listOf("苗栗縣", "臺中市")), scooter[0])
        assertEquals(RouteHazard.Wind(45.0, stops[1]), scooter[1])
        assertEquals(RouteHazard.Sunset(start.withMinute(30), stops[1]), scooter[2])
        // 13°C、騎乘 40 km/h ＋ 風 10 km/h → 體感約 9.6°
        val cold = scooter[3] as RouteHazard.Cold
        assertEquals(9.6, cold.feelsLike, 0.1)
        assertTrue(cold.riding)
        assertEquals(stops[0], cold.stop)
        assertEquals(4, scooter.size)

        // 汽車：陣風 45 未達 55，也不看冷熱
        val car = RouteForecast(route.copy(mode = TravelMode.CAR), start, stops).hazards
        assertEquals(listOf("Alert", "Sunset"), car.map { it::class.simpleName })
    }

    @Test
    fun windChillOnlyWhenCool() {
        assertEquals(20.0, RoutePlanner.windChill(20.0, 50.0), 0.0)
        assertEquals(5.0, RoutePlanner.windChill(5.0, 3.0), 0.0)
        assertTrue(RoutePlanner.windChill(10.0, 40.0) < 6.5)
    }

    @Test
    fun parsesOsrmAlternativesAndDropsNearDuplicates() {
        val json = """
            {"code":"Ok","routes":[
              {"distance":8000,"duration":1200,"geometry":{"coordinates":[[121.57,25.08],[121.56,25.03]]}},
              {"distance":9500,"duration":1500,"geometry":{"coordinates":[[121.57,25.08],[121.58,25.05],[121.56,25.03]]}}]}
        """.trimIndent()
        val paths = RouteApi.parseAll(json)
        assertEquals(2, paths.size)
        assertEquals(9.5, paths[1].distanceKm, 1e-9)
        assertTrue(RouteApi.url(home.let { LatLon(it.latitude, it.longitude) }, LatLon(25.03, 121.56), TravelMode.CAR, alternatives = true).endsWith("&alternatives=true"))
        assertFalse(RouteApi.url(LatLon(25.08, 121.57), LatLon(25.03, 121.56), TravelMode.CAR, listOf(LatLon(25.05, 121.6)), alternatives = true).contains("alternatives"))

        val a = RoutePath(listOf(LatLon(25.0, 121.5), LatLon(25.1, 121.5)), 10.0, 30.0)
        val almostSame = a.copy(distanceKm = 10.2, durationMinutes = 30.5)
        val longer = a.copy(distanceKm = 12.0, durationMinutes = 31.0)
        assertEquals(listOf(a, longer), RouteApi.distinct(listOf(a, almostSame, longer)))
        assertEquals(3, RouteApi.distinct(List(5) { i -> a.copy(distanceKm = 10.0 + i * 2) }).size)
    }

    @Test
    fun forecastDaysCoverTheDepartureDay() {
        assertEquals(3, RoutePlanner.forecastDays(null, now))
        assertEquals(3, RoutePlanner.forecastDays(now.plusHours(3), now))
        assertEquals(5, RoutePlanner.forecastDays(now.plusDays(3), now))
        assertEquals(7, RoutePlanner.forecastDays(now.plusDays(10), now))
    }
}
