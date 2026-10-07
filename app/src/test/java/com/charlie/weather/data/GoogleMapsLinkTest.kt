package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleMapsLinkTest {
    @Test
    fun findsMapsUrlInSharedText() {
        assertEquals(
            "https://maps.app.goo.gl/AbCdEf123",
            GoogleMapsLink.findUrl("台北101 到 基隆廟口夜市\nhttps://maps.app.goo.gl/AbCdEf123"),
        )
        assertNull(GoogleMapsLink.findUrl("看看這個 https://example.com/maps"))
    }

    @Test
    fun parsesDirPathWithPlaceCoordinatesAndMode() {
        val url = "https://www.google.com/maps/dir/%E5%8F%B0%E5%8C%97101/%E5%9F%BA%E9%9A%86+%E5%BB%9F%E5%8F%A3/" +
            "@25.08,121.6,12z/data=!3m1!4b1!4m14!4m13" +
            "!1m5!1m1!1s0x3442abb6da9c9e1f:0x1206bcf082fd10a6!2m2!1d121.5654177!2d25.0329694" +
            "!1m5!1m1!1s0x345d4e2b:0x1!2m2!1d121.7431!2d25.1283!3e0?entry=ttu"
        val route = GoogleMapsLink.parse(url)!!
        assertEquals(TravelMode.CAR, route.mode)
        assertEquals(listOf("台北101", "基隆 廟口"), route.stops.map { it.name })
        assertEquals(LatLon(25.0329694, 121.5654177), route.stops[0].position)
        assertEquals(LatLon(25.1283, 121.7431), route.stops[1].position)
    }

    @Test
    fun currentLocationAndCoordinateSegmentsHaveNoDataCoordinates() {
        // 起點是「目前位置」（空段），途經點是座標，只有終點在 data 裡有座標
        val url = "https://www.google.com/maps/dir//25.05,121.6/%E7%A4%81%E6%BA%AA/" +
            "data=!4m9!4m8!1m0!1m0!1m5!1m1!1s0x1:0x2!2m2!1d121.77!2d24.83!3e9"
        val route = GoogleMapsLink.parse(url)!!
        assertEquals(3, route.stops.size)
        assertEquals(GoogleMapsLink.Stop(null, null), route.stops[0])
        assertEquals(LatLon(25.05, 121.6), route.stops[1].position)
        assertNull(route.stops[1].label)
        assertEquals(LatLon(24.83, 121.77), route.stops[2].position)
        assertEquals("礁溪", route.stops[2].label)
        assertEquals(TravelMode.SCOOTER, route.mode)
    }

    @Test
    fun parsesApiStyleUrl() {
        val route = GoogleMapsLink.parse(
            "https://www.google.com/maps/dir/?api=1&origin=25.03,121.56&destination=%E7%A4%81%E6%BA%AA" +
                "&waypoints=%E5%9D%AA%E6%9E%97%7C24.94,121.71&travelmode=walking",
        )!!
        assertEquals(TravelMode.WALK, route.mode)
        assertEquals(listOf("25.03,121.56", "坪林", "24.94,121.71", "礁溪"), route.stops.map { it.name })
        assertNull(route.stops[1].position)
    }

    @Test
    fun parsesLegacySaddrDaddr() {
        val route = GoogleMapsLink.parse("https://maps.google.com/maps?saddr=25.03,121.56&daddr=%E5%9D%AA%E6%9E%97+to:24.83,121.77")!!
        assertEquals(3, route.stops.size)
        assertEquals(LatLon(24.83, 121.77), route.stops[2].position)
    }

    @Test
    fun placeLinkIsNotARoute() {
        assertNull(GoogleMapsLink.parse("https://www.google.com/maps/place/%E5%8F%B0%E5%8C%97101/@25.03,121.56,17z"))
    }
}
