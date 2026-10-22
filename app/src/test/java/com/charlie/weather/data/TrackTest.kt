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

    @Test
    fun photoTimeIsInterpolatedOnTheTrack() {
        val track = line(101) // 0–300 秒
        val mid = Track.positionAt(track, t0 + 150_000)!!
        assertEquals(25.005, mid.latitude, 1e-6)
        // 兩點之間：45 秒在第 15 點
        assertEquals(25.0015, Track.positionAt(track, t0 + 45_000)!!.latitude, 1e-6)
        // 出發前 5 分鐘拍的放起點，太早就不放
        assertEquals(track.first().position, Track.positionAt(track, t0 - 5 * 60_000))
        assertNull(Track.positionAt(track, t0 - 30 * 60_000))
    }

    @Test
    fun photoUsesGpsOnlyNearTheRoute() {
        val track = line(101)
        val near = LatLon(25.004, 121.505) // 約 500 公尺外
        val far = LatLon(25.004, 121.53) // 約 3 公里外
        assertEquals(near, Track.placePhoto(track, null, near))
        assertNull(Track.placePhoto(track, null, far))
        assertNull(Track.placePhoto(track, null, null))
        // 有拍攝時間時以時間為準
        assertEquals(25.005, Track.placePhoto(track, t0 + 150_000, far)!!.latitude, 1e-6)
    }

    @Test
    fun fractionAndPositionAreInverse() {
        val track = line(101)
        val p = Track.positionAtFraction(track, 0.25)!!
        assertEquals(25.0025, p.latitude, 1e-6)
        assertEquals(0.25, Track.fractionOf(track, p), 0.01)
        assertEquals(track.last().position, Track.positionAtFraction(track, 2.0))
    }

    @Test
    fun parsesExifTime() {
        val taipei = java.time.ZoneId.of("Asia/Taipei")
        val expected = java.time.LocalDateTime.of(2026, 10, 21, 8, 30, 5).atZone(taipei).toInstant().toEpochMilli()
        assertEquals(expected, Track.parseExifTime("2026:10:21 08:30:05", "+08:00", java.time.ZoneOffset.UTC))
        assertEquals(expected, Track.parseExifTime("2026:10:21 08:30:05", null, taipei))
        assertNull(Track.parseExifTime("    :  :     :  :  ", null, taipei))
        assertNull(Track.parseExifTime(null, null, taipei))
    }

    @Test
    fun photosSurviveJson() {
        val photos = listOf(JourneyPhoto("p1", t0, LatLon(25.001, 121.5)), JourneyPhoto("p2", null, null))
        val j = Journey("1", t0, t0 + 600_000, 3.2, line(3).map { it.copy(accuracy = null) }, photos = photos)
        assertEquals(photos, Track.fromJson(org.json.JSONObject(Track.toJson(j).toString())).photos)
    }

    private fun sample(minute: Int, wet: Boolean, place: String? = null) =
        WeatherSample(t0 + minute * 60_000L, LatLon(25.0, 121.5), 24.0, if (wet) 61 else 2, if (wet) 2.0 else 0.0, wet, place)

    @Test
    fun timelineListsRainAndPhotosInOrder() {
        val photo = JourneyPhoto("p", t0 + 12 * 60_000L, null)
        val j = Journey(
            "1", t0, t0 + 30 * 60_000L, 8.0, line(2),
            photos = listOf(photo, JourneyPhoto("q", null, null)),
            weather = listOf(sample(0, false), sample(10, true, "中和區"), sample(15, true), sample(20, false)),
        )
        val kinds = j.timeline.map { it::class.simpleName }
        assertEquals(listOf("Start", "RainStarts", "Photo", "RainStops", "End"), kinds)
        assertEquals("中和區", (j.timeline[1] as JourneyEvent.RainStarts).sample.place)
        assertTrue(j.metRain)
        assertEquals(10, j.rainMinutes)
    }

    @Test
    fun weatherSurvivesJson() {
        val weather = listOf(sample(0, false), sample(5, true, "內湖區"), WeatherSample(t0, LatLon(25.0, 121.5), null, null, null, false))
        val j = Journey("1", t0, t0 + 600_000, 3.2, line(3).map { it.copy(accuracy = null) }, weather = weather)
        assertEquals(weather, Track.fromJson(org.json.JSONObject(Track.toJson(j).toString())).weather)
    }
}
