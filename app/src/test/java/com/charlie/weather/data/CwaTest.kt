package com.charlie.weather.data

import org.junit.Assert.assertEquals
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

        val tenAm = LocalDateTime.of(2026, 10, 2, 10, 0)
        assertEquals(fc!!.hourlyTemperature[tenAm]!!, merged.hourly.first { it.time == tenAm }.temperature, 0.001)
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
}
