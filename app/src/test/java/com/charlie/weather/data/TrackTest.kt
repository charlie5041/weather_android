package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    private val t0 = 1_760_000_000_000L

    /** 每 [stepS] 秒往北走 [stepDeg] 度（0.0001 度約 11 公尺） */
    private fun line(count: Int, stepS: Int = 3, stepDeg: Double = 0.0001) =
        (0 until count).map { TrackPoint(t0 + it * stepS * 1000L, 25.0 + it * stepDeg, 121.5, 5f) }

    @Test
    fun distanceOfOneHundredthDegreeIsAboutOnePointOneKm() {
        assertEquals(1.11, Track.distanceKm(LatLon(25.0, 121.5), LatLon(25.01, 121.5)), 0.01)
    }

    @Test
    fun rejectsInaccurateAndTeleportingPoints() {
        val a = TrackPoint(t0, 25.0, 121.5, 5f)
        assertTrue(Track.accept(null, a))
        assertFalse(Track.accept(a, TrackPoint(t0 + 3000, 25.0001, 121.5, 80f)))
        // 3 秒移動 1.1 公里 ≈ 1300 km/h
        assertFalse(Track.accept(a, TrackPoint(t0 + 3000, 25.01, 121.5, 5f)))
        // 時間倒退
        assertFalse(Track.accept(a, TrackPoint(t0 - 1000, 25.0001, 121.5, 5f)))
        assertTrue(Track.accept(a, TrackPoint(t0 + 3000, 25.0001, 121.5, 5f)))
    }

    @Test
    fun cleanDropsJumpButKeepsFollowingPoints() {
        val pts = line(5).toMutableList()
        pts.add(2, TrackPoint(t0 + 7000, 25.5, 121.5, 5f))
        val cleaned = Track.clean(pts)
        assertEquals(5, cleaned.size)
        assertTrue(cleaned.none { it.latitude == 25.5 })
    }

    @Test
    fun simplifyKeepsCornersAndDropsStraightPoints() {
        val north = line(20)
        val east = (1 until 20).map { TrackPoint(north.last().time + it * 3000L, north.last().latitude, 121.5 + it * 0.0001, 5f) }
        val simplified = Track.simplify(north + east)
        assertEquals(3, simplified.size)
        assertEquals(north.last(), simplified[1])
    }

    @Test
    fun shortRecordingsAreDiscarded() {
        // 約 220 公尺
        assertNull(Track.build("a", line(21)))
        // 5 公里但只有 1 分鐘：會被當成跳點清掉，也不會存
        assertNull(Track.build("b", line(20, stepS = 3, stepDeg = 0.0025)))
    }

    @Test
    fun buildsJourneyWithDistanceAndDuration() {
        // 400 點 × 11 公尺 ≈ 4.4 公里，20 分鐘
        val journey = Track.build("trip", line(401))
        assertNotNull(journey)
        journey!!
        assertEquals(4.45, journey.distanceKm, 0.05)
        assertEquals(20, journey.durationMinutes)
        assertEquals(13.3, journey.averageKmh, 0.3)
        // 直線精簡到只剩頭尾
        assertEquals(2, journey.points.size)
    }

    @Test
    fun pointLineRoundTrip() {
        val p = TrackPoint(t0, 25.123456, 121.654321, 12f)
        assertEquals(p, Track.decodePoint(Track.encodePoint(p)))
        val noAccuracy = TrackPoint(t0, 25.1, 121.6)
        assertEquals(noAccuracy, Track.decodePoint(Track.encodePoint(noAccuracy)))
        assertNull(Track.decodePoint("garbage"))
    }

    @Test
    fun jsonRoundTripAndTitle() {
        val j = Journey("1", t0, t0 + 600_000, 3.2, line(3).map { it.copy(accuracy = null) }, "中和區", "內湖區")
        val back = Track.fromJson(org.json.JSONObject(Track.toJson(j).toString()))
        assertEquals(j, back)
        assertEquals("中和區 → 內湖區", back.title)
        assertEquals("週末烏來", back.copy(name = "週末烏來").title)
        assertEquals("中和區一帶", back.copy(toArea = "中和區").title)
        assertEquals("騎乘紀錄", back.copy(fromArea = null, toArea = null).title)
    }
}
