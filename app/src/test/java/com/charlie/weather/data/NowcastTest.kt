package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class NowcastTest {
    private val taiwan = ZoneOffset.ofHours(8)

    /** 4×3 格、0.0125° 的小格點；只有 (2,1) 有雨 */
    private val json = """
        {"cwaopendata":{"dataset":{
          "datasetInfo":{"parameterSet":{"StartPointLongitude":"121.5","StartPointLatitude":"25",
            "GridResolution":"0.0125","DateTime":"2026-10-08T16:50:00+08:00","GridDimensionX":"4","GridDimensionY":"3"}},
          "contents":{"contentDescription":"左下角為第一點東經121.4875、北緯24.9875，先依次經向遞增，再緯向遞增",
            "content":"-9.900E+01,-9.900E+01,-9.900E+01,-9.900E+01,-9.900E+01,-9.900E+01,2.300E+00,-9.900E+01,-9.900E+01,-9.900E+01,-9.900E+01,1.000E-01"}}}}
    """.trimIndent()

    @Test
    fun parsesGridAndLooksUpNeighbourhood() {
        val n = RainNowcast.parse(json, taiwan)!!
        assertEquals(LocalDateTime.of(2026, 10, 8, 16, 50), n.issued)
        // 第一點位置以說明文字為準
        assertEquals(121.4875, n.lon0, 1e-9)
        assertEquals(24.9875, n.lat0, 1e-9)
        assertEquals(2.3f, n.value(2, 1), 1e-6f)
        assertEquals(0f, n.value(0, 0), 0f)
        // (2,1) 的中心
        val lat = n.latitude(1)
        val lon = n.longitude(2)
        assertEquals(2.3, n.at(lat, lon)!!, 1e-6)
        // 相鄰一格也取到附近最大值；只看自己那格則沒有雨
        assertEquals(2.3, n.at(lat, n.longitude(1))!!, 1e-6)
        assertEquals(0.0, n.at(lat, n.longitude(0), radius = 0)!!, 0.0)
        // 超出範圍
        assertNull(n.at(30.0, 121.5))
        assertNull(RainNowcast.parse("{}", taiwan))
    }

    @Test
    fun coversOnlyTheNextHourWhileFresh() {
        val n = RainNowcast.parse(json, taiwan)!!
        val now = LocalDateTime.of(2026, 10, 8, 17, 0)
        assertTrue(n.covers(now, now))
        assertTrue(n.covers(now.plusMinutes(50), now))
        assertFalse(n.covers(now.plusMinutes(70), now))
        // 資料超過 40 分鐘就不用
        assertFalse(n.covers(now.plusHours(1), now.plusHours(1)))
    }

    @Test
    fun radarOverridesHourlyForecastForTheNextHour() {
        val n = RainNowcast.parse(json, taiwan)!!
        val now = LocalDateTime.of(2026, 10, 8, 17, 0)
        val home = City("a", "A", "", n.latitude(1), n.longitude(2))
        val work = City("b", "B", "", n.latitude(1), n.longitude(2) + 0.2)
        val path = RoutePath(listOf(LatLon(home.latitude, home.longitude), LatLon(work.latitude, work.longitude)), 20.0, 120.0)
        val points = RoutePlanner.sample(path, stepKm = 10.0)
        val dryForecast = Weather(
            current = CurrentConditions(now, 25.0, 25.0, 70, 18.0, true, 0.0, 1, 30, 1012.0, 10.0, 90, 15.0, 20_000.0, 3.0),
            hourly = (0..3).map { HourlyForecast(now.plusHours(it.toLong()), 25.0, 2, 10, 0.0, true) },
            daily = emptyList(),
            utcOffsetSeconds = 8 * 3600,
            airQuality = null,
            fetchedAtMillis = 0,
        )
        val data = RouteData(home, work, TravelMode.SCOOTER, path, points, points.map { dryForecast }, nowcast = n)
        val forecast = RoutePlanner.evaluate(data, now, now)
        // 起點在雷達有雨的格子：預報 10% 但雷達 2.3 mm → 會下雨
        val first = forecast.stops.first()
        assertEquals(2.3, first.nowcastMm!!, 1e-6)
        assertTrue(first.wet)
        assertEquals(90, first.probability)
        assertEquals(RainLevel.WET, first.rainLevel)
        assertTrue(forecast.verdict.detail, forecast.verdict.detail.contains("雷達預估 1 小時 2.3 mm"))
        // 1 小時後才經過的終點不用雷達
        val last = forecast.stops.last()
        assertNull(last.nowcastMm)
        assertEquals(10, last.probability)
    }
}
