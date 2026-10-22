package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePrivacyTest {
    /** 從 (25.0, 121.5) 往北的直線，只有頭尾兩點（像精簡後的軌跡）；0.01 度約 1.1 公里 */
    private fun north(km: Double) = listOf(LatLon(25.0, 121.5), LatLon(25.0 + km / 111.2, 121.5))

    private fun minDistanceM(route: SharedRoute, p: LatLon) =
        route.segments.flatten().minOf { Track.distanceKm(it, p) * 1000 }

    @Test
    fun trimDistanceIsStablePerJourneyAndWithinRange() {
        val ids = (1..50).map { "j$it" }
        ids.forEach { id ->
            val start = SharePrivacy.trimM(id, end = false)
            assertEquals(start, SharePrivacy.trimM(id, end = false))
            assertTrue(start in SharePrivacy.MIN_TRIM_M..SharePrivacy.MAX_TRIM_M)
            assertTrue(SharePrivacy.trimM(id, end = true) in SharePrivacy.MIN_TRIM_M..SharePrivacy.MAX_TRIM_M)
        }
        // 每趟裁的距離不一樣
        assertTrue(ids.map { SharePrivacy.trimM(it, end = false) }.toSet().size > 10)
    }

    @Test
    fun trimsBothEndsWithoutZones() {
        val points = north(5.0)
        val route = SharePrivacy.apply("a", points, emptyList())
        assertFalse(route.mapHidden)
        assertEquals(1, route.segments.size)
        val startGap = Track.distanceKm(points.first(), route.segments.first().first()) * 1000
        val endGap = Track.distanceKm(points.last(), route.segments.last().last()) * 1000
        assertEquals(route.trimStartM.toDouble(), startGap, SharePrivacy.STEP_M + 1)
        assertEquals(route.trimEndM.toDouble(), endGap, SharePrivacy.STEP_M + 1)
    }

    @Test
    fun removesEverythingInsideZoneEvenOnALongStraightSegment() {
        // 只有頭尾兩點的 5 公里直線，中間經過住家
        val points = north(5.0)
        val home = LatLon(25.0 + 2.5 / 111.2, 121.5)
        val route = SharePrivacy.apply("b", points, listOf(PrivacyZone(home, 500.0)))
        assertEquals(2, route.segments.size)
        assertTrue(minDistanceM(route, home) > 500)
    }

    @Test
    fun startInsideHomeZoneIsCutAtZoneEdgePlusTrim() {
        val points = north(6.0)
        val home = points.first()
        val route = SharePrivacy.apply("c", points, listOf(PrivacyZone(home, 500.0)))
        val gap = Track.distanceKm(home, route.segments.first().first()) * 1000
        // 隱私區邊界與裁切起點各有最多一個補點間距的誤差
        assertEquals(500.0 + route.trimStartM + SharePrivacy.STEP_M, gap, 2 * SharePrivacy.STEP_M + 1)
    }

    @Test
    fun hidesMapWhenLittleIsLeft() {
        // 1.5 公里、起點在住家：裁完不到 1 公里
        val points = north(1.5)
        val route = SharePrivacy.apply("d", points, listOf(PrivacyZone(points.first(), 500.0)))
        assertTrue(route.mapHidden)
        assertTrue(route.segments.isEmpty())
    }

    @Test
    fun hidesMapWhenTripStaysInsideZone() {
        val points = north(0.8)
        val route = SharePrivacy.apply("e", points, listOf(PrivacyZone(points.first(), 1000.0)))
        assertTrue(route.mapHidden)
        assertEquals(0.0, route.keptKm, 1e-9)
    }

    @Test
    fun zonesComeFromLabelledPlacesExceptExcluded() {
        val places = listOf(
            City("current_location", "大安區", "我的位置", 25.03, 121.54, isCurrentLocation = true),
            City("geo_1", "臺北市", "臺灣", 25.04, 121.53),
            City("home", "中和區", "", 25.0, 121.5, label = "住家"),
            City("work", "內湖區", "", 25.08, 121.57, label = "公司"),
        )
        val zones = SharePrivacy.zonesFrom(places, excludedIds = setOf("work"), radiusM = 200)
        assertEquals(listOf(PrivacyZone(LatLon(25.0, 121.5), 200.0)), zones)
    }
}
