package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MapsNavigationTest {
    private val work = City("b", "信義區", "", 25.033964, 121.564468, label = "公司")
    private val via = City("v", "松山區", "", 25.05, 121.57)

    @Test
    fun scooterNavigationStartsFromCurrentLocation() {
        val uri = MapsNavigation.uri(work, emptyList(), TravelMode.SCOOTER)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=25.033964,121.564468&travelmode=two-wheeler&dir_action=navigate",
            uri,
        )
        // 不帶起點才會直接進入導航（有起點會變成路線預覽）
        assertFalse(uri.contains("origin="))
    }

    @Test
    fun keepsWaypointsInOrder() {
        val uri = MapsNavigation.uri(work, listOf(via, via.copy(latitude = 25.06)), TravelMode.CAR)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=25.033964,121.564468" +
                "&waypoints=25.050000,121.570000%7C25.060000,121.570000&travelmode=driving&dir_action=navigate",
            uri,
        )
    }
}
