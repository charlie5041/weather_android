package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class FavoriteRouteTest {
    private val home = City("addr_h", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "住家")
    private val work = City("addr_w", "信義區", "臺北市信義區", 25.03, 121.56, label = "公司")

    // 2026-10-09 是星期五
    private val friday = LocalDateTime.of(2026, 10, 9, 7, 0)

    @Test
    fun reminderFindsNextDepartureDay() {
        val weekdays = RouteReminder(RouteReminder.WEEKDAYS, 8, 0)
        assertEquals(friday.withHour(8), weekdays.next(friday))
        // 剛出發 10 分鐘仍算這一趟；過了 15 分鐘就是下週一
        assertEquals(friday.withHour(8), weekdays.next(friday.withHour(8).withMinute(10)))
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 0), weekdays.next(friday.withHour(8).withMinute(20)))
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 30), RouteReminder(setOf(DayOfWeek.SATURDAY), 9, 30).next(friday))
        assertNull(RouteReminder(emptySet(), 8, 0).next(friday))
    }

    @Test
    fun upcomingIsEarliestWithinTwelveHours() {
        val morning = FavoriteRoute("a", "上班", home, work, TravelMode.SCOOTER, reminder = RouteReminder(RouteReminder.WEEKDAYS, 8, 0))
        val evening = FavoriteRoute("b", "下班", work, home, TravelMode.SCOOTER, reminder = RouteReminder(RouteReminder.WEEKDAYS, 18, 0))
        val plain = FavoriteRoute("c", "回老家", home, work, TravelMode.CAR)
        val routes = listOf(evening, plain, morning)
        assertEquals(morning to friday.withHour(8), FavoriteRoutes.upcoming(routes, friday))
        assertEquals(evening to friday.withHour(18), FavoriteRoutes.upcoming(routes, friday.withHour(9)))
        // 週五晚上之後下一趟是週一早上，超過 12 小時
        assertNull(FavoriteRoutes.upcoming(routes, friday.withHour(19)))
    }

    @Test
    fun sameRouteComparesCoordinates() {
        val route = FavoriteRoute("a", "上班", home, work, TravelMode.SCOOTER)
        assertTrue(route.sameRoute(home.copy(id = "route_x", label = null), work, emptyList()))
        assertFalse(route.sameRoute(work, home, emptyList()))
        assertFalse(route.sameRoute(home, work, listOf(work)))
    }

    @Test
    fun jsonRoundTrip() {
        val routes = listOf(
            FavoriteRoute(
                "a", "上班", home, work, TravelMode.BIKE, via = listOf(work.copy(id = "v")),
                reminder = RouteReminder(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), 7, 45),
            ),
            FavoriteRoute("b", "回家", work, home, TravelMode.WALK),
        )
        assertEquals(routes, FavoriteRoutes.parse(FavoriteRoutes.toJson(routes)))
        assertEquals(emptyList<FavoriteRoute>(), FavoriteRoutes.parse("not json"))
    }
}
