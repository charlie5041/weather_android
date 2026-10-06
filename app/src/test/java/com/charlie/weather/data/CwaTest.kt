package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CwaTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("cwa/$name")) { "missing fixture $name" }.readText()

    private val taipei101Lat = 25.0340
    private val taipei101Lon = 121.5645

    @Test
    fun parsesStationsAndMissingValues() {
        val stations = CwaParser.parseStations(fixture("O-A0003-001.json"))
        assertEquals(4, stations.size)
        val taipei = stations.first { it.name == "臺北" }
        assertEquals(30.1, taipei.temperature!!, 0.001)
        assertEquals(70, taipei.humidity)
        assertEquals("晴", taipei.weather)
        assertEquals("臺北市", taipei.county)
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 30), taipei.time)
        val missing = stations.first { it.name == "南沙島" }
        assertNull(missing.temperature)
        assertNull(missing.weather)
        assertNull(missing.humidity)
    }

    @Test
    fun picksNearestStationWithinRange() {
        val stations = CwaParser.parseStations(fixture("O-A0003-001.json"))
        val obs = CwaParser.nearest(stations, taipei101Lat, taipei101Lon, 10.0)
        assertNotNull(obs)
        assertEquals("臺北", obs!!.station.name)
        assertTrue(obs.distanceKm < 10)
        assertNull(CwaParser.nearest(stations, 35.68, 139.69, 10.0))
    }

    @Test
    fun parsesTownshipForecast() {
        val fc = CwaParser.parseTownshipForecast(fixture("F-D0047-061.json"), fixture("F-D0047-063.json"), 25.0516, 121.5690)
        assertNotNull(fc)
        fc!!
        assertEquals("臺北市", fc.county)
        assertEquals("松山區", fc.township)
        assertEquals(25.0, fc.hourlyTemperature[LocalDateTime.of(2026, 10, 2, 6, 0)]!!, 0.001)
        assertTrue(fc.hourlyTemperature.size >= 48)
        val block = fc.blockAt(LocalDateTime.of(2026, 10, 2, 7, 0))
        assertNotNull(block)
        assertNotNull(block!!.weather)
        assertNotNull(block.precipitationProbability)

        val today = fc.daily.first()
        assertEquals(LocalDate.of(2026, 10, 2), today.date)
        assertEquals(30.0, today.max!!, 0.001)
        assertEquals(25.0, today.min!!, 0.001)
        assertTrue(today.hasDaytime)
        assertEquals("多雲短暫陣雨", today.weather)
        assertTrue(fc.daily.size >= 7)
    }

    @Test
    fun parsesAlertsForCounty() {
        val json = fixture("W-C0033-001.json")
        val now = LocalDateTime.of(2026, 10, 2, 9, 0)
        val taoyuan = CwaParser.parseAlerts(json, "桃園市", now)
        assertEquals(1, taoyuan.size)
        assertEquals("陸上強風特報", taoyuan.first().title)
        assertEquals(LocalDateTime.of(2026, 10, 2, 23, 0), taoyuan.first().end)
        assertEquals(1, CwaParser.parseAlerts(json, "台中市", now).size)
        assertTrue(CwaParser.parseAlerts(json, "臺北市", now).isEmpty())
        assertTrue(CwaParser.parseAlerts(json, "桃園市", LocalDateTime.of(2026, 10, 3, 0, 0)).isEmpty())
    }

    @Test
    fun forecastIdsForCounties() {
        assertEquals("F-D0047-061", CwaParser.threeDayForecastId("台北市"))
        assertEquals("F-D0047-063", CwaParser.weeklyForecastId("臺北市"))
        assertEquals("F-D0047-085", CwaParser.threeDayForecastId("金門縣"))
        assertNull(CwaParser.threeDayForecastId("東京都"))
    }

    @Test
    fun mergesCwaIntoOpenMeteo() {
        val stations = CwaParser.parseStations(fixture("O-A0003-001.json"))
        val obs = CwaParser.nearest(stations, taipei101Lat, taipei101Lon, 10.0)
        val fc = CwaParser.parseTownshipForecast(fixture("F-D0047-061.json"), fixture("F-D0047-063.json"), taipei101Lat, taipei101Lon)
        val alerts = CwaParser.parseAlerts(fixture("W-C0033-001.json"), "臺北市", LocalDateTime.of(2026, 10, 2, 9, 0))

        val now = LocalDateTime.of(2026, 10, 2, 9, 45)
        val base = openMeteoWeather(now)
        val merged = base.withCwa(CwaData(obs, fc, alerts), now)

        assertEquals(30.1, merged.current.temperature, 0.001)
        assertEquals("晴", merged.current.description)
        assertEquals(0, merged.current.weatherCode)
        assertEquals(70, merged.current.humidity)
        // 體感溫度隨實測溫度平移
        assertEquals(base.current.apparentTemperature + (30.1 - 20.0), merged.current.apparentTemperature, 0.001)

        // 10:00 的逐時溫度 = 鄉鎮預報 + 實測偏差（觀測 09:30，半小時後權重 1 - 0.5/6）
        val tenAm = LocalDateTime.of(2026, 10, 2, 10, 0)
        val bias = merged.cwa!!.temperatureBias!!
        assertEquals(fc!!.hourlyTemperature[tenAm]!! + bias * (1 - 0.5 / 6), merged.hourly.first { it.time == tenAm }.temperature, 0.001)
        assertEquals(fc.blockAt(tenAm)!!.precipitationProbability, merged.hourly.first { it.time == tenAm }.precipitationProbability)

        val today = merged.daily.first()
        assertTrue(today.temperatureMax >= 30.1)
        assertEquals("多雲短暫陣雨", today.description)
        assertEquals(80, today.weatherCode)
        // 超出一週預報範圍的日子保持 Open-Meteo
        assertEquals(base.daily.last(), merged.daily.last())

        val summary = merged.cwa!!
        assertEquals("臺北", summary.stationName)
        assertEquals("松山區", summary.township)
    }

    @Test
    fun staleObservationIsIgnored() {
        val stations = CwaParser.parseStations(fixture("O-A0003-001.json"))
        val obs = CwaParser.nearest(stations, taipei101Lat, taipei101Lon, 10.0)
        val now = LocalDateTime.of(2026, 10, 2, 15, 0)
        val merged = openMeteoWeather(now).withCwa(CwaData(obs, null, emptyList()), now)
        assertEquals(20.0, merged.current.temperature, 0.001)
        assertNull(merged.cwa!!.stationName)
    }

    private fun openMeteoWeather(now: LocalDateTime): Weather {
        val start = now.toLocalDate().atStartOfDay()
        return Weather(
            current = CurrentConditions(
                time = now, temperature = 20.0, apparentTemperature = 21.0, humidity = 50, dewPoint = 10.0,
                isDay = true, precipitation = 0.0, weatherCode = 3, cloudCover = 80, pressure = 1010.0,
                windSpeed = 5.0, windDirection = 90, windGusts = 10.0, visibility = 20000.0, uvIndex = 2.0,
            ),
            hourly = (0 until 72).map { i ->
                HourlyForecast(start.plusHours(i.toLong()), 20.0, 3, 10, 0.0, true)
            },
            daily = (0 until 10).map { i ->
                DailyForecast(
                    date = now.toLocalDate().plusDays(i.toLong()), weatherCode = 3, temperatureMax = 22.0,
                    temperatureMin = 18.0, precipitationProbability = 10, precipitationSum = 0.0,
                    sunrise = null, sunset = null, uvIndexMax = 5.0,
                )
            },
            utcOffsetSeconds = 8 * 3600,
            airQuality = null,
            fetchedAtMillis = 0,
        )
    }

    // ---------- 雨量站與溫度修正 ----------

    @Test
    fun parsesRainGauges() {
        val gauges = CwaParser.parseRainGauges(fixture("O-A0002-001.json"))
        assertEquals(7, gauges.size)
        val xinyi = gauges.first { it.name == "信義" }
        assertEquals(0.0, xinyi.past10Min!!, 0.001)
        assertEquals(0.5, xinyi.today!!, 0.001)
        assertEquals(LocalDateTime.of(2026, 10, 6, 13, 0), xinyi.time)
    }

    @Test
    fun rainNowFromRealData() {
        // 2026-10-06 13:00 實測：附近剛下過（市政中心 1 小時 0.5 毫米），但最近 10 分鐘沒有雨
        val rain = CwaParser.rainNow(CwaParser.parseRainGauges(fixture("O-A0002-001.json")), taipei101Lat, taipei101Lon)!!
        assertEquals("信義", rain.stationName)
        assertFalse(rain.raining)
        assertEquals(0.5, rain.pastHour, 0.001)
        assertNull(CwaParser.rainNow(CwaParser.parseRainGauges(fixture("O-A0002-001.json")), 35.68, 139.69))
    }

    @Test
    fun rainNowUsesNearbyGauges() {
        fun gauge(name: String, lat: Double, past10: Double, past1h: Double) =
            RainGauge(name, name, lat, taipei101Lon, null, past10, past1h, past1h)
        // 最近的站沒雨，但 3 公里內另一站正在下雨 → 判定下雨，雨勢取最大值
        val gauges = listOf(
            gauge("近", taipei101Lat, 0.0, 0.0),
            gauge("中", taipei101Lat + 0.02, 1.5, 4.0),
            gauge("遠", taipei101Lat + 0.2, 9.0, 30.0),
        )
        val rain = CwaParser.rainNow(gauges, taipei101Lat, taipei101Lon)!!
        assertEquals("近", rain.stationName)
        assertTrue(rain.raining)
        assertEquals(9.0, rain.ratePerHour, 0.001)
        assertEquals(4.0, rain.pastHour, 0.001)
    }

    private fun rainNow(raining: Boolean, rate: Double, pastHour: Double, now: LocalDateTime) =
        RainNow("信義", 0.4, now.minusMinutes(10), raining, rate, pastHour, pastHour)

    @Test
    fun gaugesTurnClearIntoRain() {
        val stations = CwaParser.parseStations(fixture("O-A0003-001.json"))
        val obs = CwaParser.nearest(stations, taipei101Lat, taipei101Lon, 10.0)
        val now = LocalDateTime.of(2026, 10, 2, 9, 45)
        // 測站天氣是「晴」，但附近雨量站每小時約 3 毫米
        val merged = openMeteoWeather(now).withCwa(CwaData(obs, null, emptyList(), rainNow(true, 3.0, 1.0, now)), now)
        assertEquals("雨", merged.current.description)
        assertEquals(63, merged.current.weatherCode)
        assertEquals(1.0, merged.current.precipitation, 0.001)
        val heavy = openMeteoWeather(now).withCwa(CwaData(obs, null, emptyList(), rainNow(true, 45.0, 20.0, now)), now)
        assertEquals("豪雨", heavy.current.description)
    }

    @Test
    fun dryGaugesRemoveRainFromForecastText() {
        val fc = CwaParser.parseTownshipForecast(fixture("F-D0047-061.json"), fixture("F-D0047-063.json"), taipei101Lat, taipei101Lon)
        val now = LocalDateTime.of(2026, 10, 2, 9, 45)
        val base = openMeteoWeather(now).copy(current = openMeteoWeather(now).current.copy(weatherCode = 61, description = "陰短暫陣雨"))
        // 沒有測站觀測時，目前天氣來自預報（可能含雨）；附近雨量站一小時都沒雨 → 改成陰
        val merged = base.withCwa(CwaData(null, fc, emptyList(), rainNow(false, 0.0, 0.0, now)), now)
        assertTrue(merged.current.weatherCode !in 51..67 && merged.current.weatherCode !in 80..82)
        assertTrue(merged.current.description in setOf("陰", "多雲"))
        // 剛下過（過去 1 小時仍有雨量）就不改
        val recent = base.withCwa(CwaData(null, fc, emptyList(), rainNow(false, 0.0, 0.5, now)), now)
        assertEquals(base.withCwa(CwaData(null, fc, emptyList()), now).current.weatherCode, recent.current.weatherCode)
    }

    @Test
    fun staleRainDataIsIgnored() {
        val now = LocalDateTime.of(2026, 10, 2, 9, 45)
        val stale = RainNow("信義", 0.4, now.minusHours(2), true, 10.0, 5.0, 5.0)
        val merged = openMeteoWeather(now).withCwa(CwaData(null, null, emptyList(), stale), now)
        assertEquals(3, merged.current.weatherCode)
        assertNull(merged.cwa!!.rain)
    }

    @Test
    fun temperatureBiasDecaysOverSixHours() {
        val stations = CwaParser.parseStations(fixture("O-A0003-001.json"))
        val obs = CwaParser.nearest(stations, taipei101Lat, taipei101Lon, 10.0)!!
        val fc = CwaParser.parseTownshipForecast(fixture("F-D0047-061.json"), fixture("F-D0047-063.json"), taipei101Lat, taipei101Lon)!!
        val now = LocalDateTime.of(2026, 10, 2, 9, 45)
        val merged = openMeteoWeather(now).withCwa(CwaData(obs, fc, emptyList()), now)

        // 偏差 = 實測 30.1° − 預報在 09:30 的內插值
        val nine = fc.hourlyTemperature[LocalDateTime.of(2026, 10, 2, 9, 0)]!!
        val ten = fc.hourlyTemperature[LocalDateTime.of(2026, 10, 2, 10, 0)]!!
        val bias = 30.1 - (nine + ten) / 2
        assertEquals(bias, merged.cwa!!.temperatureBias!!, 0.001)

        fun temp(h: Int) = merged.hourly.first { it.time == LocalDateTime.of(2026, 10, 2, h, 0) }.temperature
        fun cwa(h: Int) = fc.hourlyTemperature[LocalDateTime.of(2026, 10, 2, h, 0)]!!
        assertEquals(cwa(12) + bias * (1 - 2.5 / 6), temp(12), 0.001)
        // 6 小時後（15:30 之後）回到原本的預報；觀測之前的時段不變
        assertEquals(cwa(16), temp(16), 0.001)
        assertEquals(cwa(9), temp(9), 0.001)
        // 今日最高溫涵蓋修正後的時段
        assertTrue(merged.daily.first().temperatureMax >= merged.hourly.filter { it.time.toLocalDate() == now.toLocalDate() && it.time.isAfter(now) }.maxOf { it.temperature } - 0.001)
    }
}
