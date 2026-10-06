package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommuteTest {
    private val home = City("addr_h", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "住家")
    private val work = City("addr_w", "信義區", "臺北市信義區", 25.03, 121.56, label = "公司")

    // 2026-10-06 是週二
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    private fun hour(time: LocalDateTime, temp: Double, code: Int = 1, pop: Int? = 0, mm: Double = 0.0) =
        HourlyForecast(time, temp, code, pop, mm, isDay = true)

    private fun weather(vararg hours: HourlyForecast) = Weather(
        current = CurrentConditions(
            hours.first().time, 25.0, 25.0, 70, 18.0, true, 0.0, 1, 30, 1012.0, 10.0, 90, 15.0, 20_000.0, 3.0,
        ),
        hourly = hours.toList(),
        daily = emptyList(),
        utcOffsetSeconds = 8 * 3600,
        airQuality = null,
        fetchedAtMillis = 0,
    )

    @Test
    fun findsHomeAndWorkOrSchool() {
        assertEquals(home to work, Commute.homeAndWork(listOf(work, home)))
        val school = work.copy(id = "addr_s", label = "學校")
        assertEquals(home to school, Commute.homeAndWork(listOf(home, school)))
        assertNull(Commute.homeAndWork(listOf(home)))
    }

    @Test
    fun nextDepartureFollowsTheDay() {
        assertEquals(CommuteLeg.TO_WORK to at(6, 8), Commute.nextDeparture(at(6, 6), 8, 18))
        // 出發後 1 小時內仍顯示同一趟
        assertEquals(CommuteLeg.TO_WORK to at(6, 8), Commute.nextDeparture(at(6, 8, 40), 8, 18))
        assertEquals(CommuteLeg.TO_HOME to at(6, 18), Commute.nextDeparture(at(6, 9, 5), 8, 18))
        assertEquals(CommuteLeg.TO_WORK to at(7, 8), Commute.nextDeparture(at(6, 22), 8, 18))
    }

    @Test
    fun weekendSkipsToMonday() {
        // 10/9 週五晚上 → 10/12 週一早上
        assertEquals(CommuteLeg.TO_WORK to at(12, 8), Commute.nextDeparture(at(9, 20), 8, 18))
        assertEquals(CommuteLeg.TO_WORK to at(12, 8), Commute.nextDeparture(at(10, 9), 8, 18))
    }

    @Test
    fun tripUsesDepartureAndArrivalHours() {
        val homeWeather = weather(hour(at(6, 8), 22.0), hour(at(6, 9), 23.0))
        val workWeather = weather(hour(at(6, 8), 24.0), hour(at(6, 9), 27.0, code = 61, pop = 70))
        val trip = Commute.trip(home, work, homeWeather, workWeather, at(6, 7), 8, 18)
        assertEquals(home, trip.from)
        assertEquals(22.0, trip.fromHour!!.temperature, 0.0)
        assertEquals(27.0, trip.toHour!!.temperature, 0.0)
        assertTrue(trip.advice, trip.advice.contains("帶傘"))
        assertTrue(trip.advice, trip.advice.contains("較熱約 5°"))

        val evening = Commute.trip(home, work, homeWeather, workWeather, at(6, 12), 8, 18)
        assertEquals(work, evening.from)
        assertNull(evening.fromHour)
    }

    @Test
    fun adviceForCalmAndStormyWeather() {
        assertEquals("兩地天氣穩定，適合出門", Commute.advice(hour(at(6, 8), 24.0), hour(at(6, 9), 25.0)))
        assertTrue(Commute.advice(hour(at(6, 8), 24.0, code = 95), null).contains("雷雨"))
        assertTrue(Commute.advice(hour(at(6, 8), 24.0, mm = 12.0), null).contains("大雨"))
    }
}
