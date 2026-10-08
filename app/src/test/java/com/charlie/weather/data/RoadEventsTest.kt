package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class RoadEventsTest {
    @Test
    fun parsesLiveEventsWithWktPositions() {
        val json = """
            {"AuthorityCode":"NFB","LiveEvents":[
              {"EventTitle":"施工","Description":"內線車道封閉","Location":{"Other":"國道1號北向 25K"},
               "Positions":"POINT (121.5600 25.0500)","EndTime":"2026-10-08T18:00:00+08:00"},
              {"EventTitle":"事故","Description":"","Position":{"PositionLat":24.9,"PositionLon":121.2}},
              {"EventTitle":"沒有位置","Description":"略過"}
            ]}
        """.trimIndent()
        val events = TdxRoadEvents.parse(json, ZoneOffset.ofHours(8))
        assertEquals(2, events.size)
        assertEquals("施工", events[0].title)
        assertEquals("國道1號北向 25K", events[0].location)
        assertEquals(listOf(LatLon(25.05, 121.56)), events[0].positions)
        assertEquals(LocalDateTime.of(2026, 10, 8, 18, 0), events[0].end)
        // 沒有描述時用類別
        assertEquals("事故", events[1].description)
        assertNull(events[1].location)
        assertEquals(emptyList<RoadEvent>(), TdxRoadEvents.parse("""{"Foo":[]}"""))
        assertEquals(2, TdxRoadEvents.wkt("LINESTRING (121.1 25.1, 121.2 25.2)").size)
    }

    @Test
    fun keepsEventsNearTheRouteIncludingBetweenVertices() {
        // 一段約 11 公里的直線（兩個頂點），事件在中間旁邊 0.2 公里與 3 公里
        val path = listOf(LatLon(25.0, 121.5), LatLon(25.1, 121.5))
        fun at(lat: Double, lon: Double) = RoadEvent("施工", "x", null, listOf(LatLon(lat, lon)))
        val close = at(25.05, 121.502)
        val far = at(25.05, 121.53)
        assertEquals(listOf(close), TdxRoadEvents.near(listOf(close, far), path))
        assertTrue(TdxRoadEvents.distanceToSegmentKm(LatLon(25.05, 121.502), path[0], path[1]) < 0.3)
    }
}
