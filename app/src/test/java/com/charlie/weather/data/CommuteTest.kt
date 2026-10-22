package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommuteTest {
    private val home = City("addr_h", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "住家")
    private val work = City("addr_w", "信義區", "臺北市信義區", 25.03, 121.56, label = "公司")
    private val legacy = LegacyCommute(card = true, notify = true, morningHour = 9, eveningHour = 19)

    private fun ids(): () -> String {
        var n = 0
        return { "id${n++}" }
    }

    @Test
    fun findsHomeAndWorkOrSchool() {
        assertEquals(home to work, Commute.homeAndWork(listOf(work, home)))
        val school = work.copy(id = "addr_s", label = "學校")
        assertEquals(home to school, Commute.homeAndWork(listOf(home, school)))
        assertNull(Commute.homeAndWork(listOf(home)))
    }

    @Test
    fun routesAreWeekdayToWorkAndBack() {
        val (toWork, toHome) = Commute.routes(home, work, TravelMode.BIKE, 9, 19, ids())
        assertEquals(FavoriteRoute("id0", "上班", home, work, TravelMode.BIKE, reminder = RouteReminder(RouteReminder.WEEKDAYS, 9, 0)), toWork)
        assertEquals(FavoriteRoute("id1", "下班", work, home, TravelMode.BIKE, reminder = RouteReminder(RouteReminder.WEEKDAYS, 19, 0)), toHome)
        // 週二早上 8 點：下一趟是 9 點上班
        assertEquals(toWork to LocalDateTime.of(2026, 10, 6, 9, 0), FavoriteRoutes.upcoming(listOf(toWork, toHome), LocalDateTime.of(2026, 10, 6, 8, 0)))
    }

    @Test
    fun migratesLegacyCommuteIntoTwoRoutes() {
        val other = FavoriteRoute("x", "回老家", home, work.copy(latitude = 24.0), TravelMode.CAR)
        val result = Commute.migrate(listOf(other), listOf(home, work), legacy, TravelMode.SCOOTER, ids())!!
        assertEquals(listOf("回老家", "上班", "下班"), result.map { it.name })
        assertEquals(RouteReminder(RouteReminder.WEEKDAYS, 9, 0), result[1].reminder)
        assertEquals(RouteReminder(RouteReminder.WEEKDAYS, 19, 0), result[2].reminder)
    }

    @Test
    fun migrationKeepsExistingSameRoute() {
        val mine = FavoriteRoute("m", "去公司", home, work, TravelMode.CAR, reminder = RouteReminder(RouteReminder.WEEKDAYS, 7, 30))
        val bare = FavoriteRoute("b", "回家", work, home, TravelMode.CAR)
        val result = Commute.migrate(listOf(mine, bare), listOf(home, work), legacy, TravelMode.SCOOTER, ids())!!
        // 已設提醒的保持原樣；沒設提醒的補上下班時間，不另外建
        assertEquals(listOf(mine, bare.copy(reminder = RouteReminder(RouteReminder.WEEKDAYS, 19, 0))), result)
    }

    @Test
    fun noMigrationWhenNothingToDo() {
        // 通勤卡與通知都關了
        assertNull(Commute.migrate(emptyList(), listOf(home, work), legacy.copy(card = false, notify = false), TravelMode.SCOOTER))
        // 沒有公司
        assertNull(Commute.migrate(emptyList(), listOf(home), legacy, TravelMode.SCOOTER))
        // 兩條都已經有提醒
        val routes = Commute.routes(home, work, TravelMode.SCOOTER, newId = ids())
        assertNull(Commute.migrate(routes, listOf(home, work), legacy, TravelMode.SCOOTER))
    }

    @Test
    fun hasRoutesMatchesByCoordinates() {
        assertFalse(Commute.hasRoutes(emptyList(), home, work))
        val renamed = work.copy(id = "search_1", label = null, name = "台北 101")
        assertTrue(Commute.hasRoutes(listOf(FavoriteRoute("a", "上班", home, renamed, TravelMode.SCOOTER)), home, work))
    }
}
