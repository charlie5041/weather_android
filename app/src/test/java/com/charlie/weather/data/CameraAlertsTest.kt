package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAlertsTest {
    private fun cam(lat: Double, lon: Double, direct: String? = null, limit: Int? = 50) =
        SpeedCamera(LatLon(lat, lon), "成功路", limit, direct)

    @Test
    fun findsTheNextCameraOnTheRoute() {
        val a = cam(25.02, 121.5)
        val b = cam(25.05, 121.5)
        val cameras = listOf(RouteCamera(a, 0.2, 2.2), RouteCamera(b, 0.5, 5.5))
        // 在 1.9 公里處：前方 300 公尺是 a
        assertEquals(CameraAhead(a, 300), CameraAlerts.onRoute(cameras, 11.0, 1.9 / 11))
        // 剛經過 a 10 公尺仍算 a（GPS 誤差），之後換 b，但還太遠
        assertEquals(a, CameraAlerts.onRoute(cameras, 11.0, 2.21 / 11)?.camera)
        assertNull(CameraAlerts.onRoute(cameras, 11.0, 2.4 / 11))
        assertEquals(CameraAhead(b, 450), CameraAlerts.onRoute(cameras, 11.0, 5.05 / 11))
    }

    @Test
    fun findsCamerasAheadWithoutARoute() {
        val here = LatLon(25.0, 121.5)
        // 正北 300 公尺（往北拍）、正南 300 公尺、北邊但拍南向車道、東邊 300 公尺
        val north = cam(25.0027, 121.5, "南向北")
        val south = cam(24.9973, 121.5)
        val northOpposite = cam(25.0018, 121.5, "北向南")
        val east = cam(25.0, 121.503)
        val all = listOf(north, south, northOpposite, east)
        assertEquals(north, CameraAlerts.ahead(all, here, 0.0)?.camera)
        assertEquals(300, CameraAlerts.ahead(all, here, 0.0)?.meters)
        assertEquals(south, CameraAlerts.ahead(all, here, 180.0)?.camera)
        assertEquals(east, CameraAlerts.ahead(all, here, 90.0)?.camera)
        // 沒有方位時不知道哪邊是前方
        assertNull(CameraAlerts.ahead(all, here, null))
        // 太遠或已經在旁邊
        assertNull(CameraAlerts.ahead(listOf(cam(25.01, 121.5)), here, 0.0))
        assertNull(CameraAlerts.ahead(listOf(cam(25.0001, 121.5)), here, 0.0))
        // 回報者的方位：往西時不提醒往東的回報
        val report = SpeedCamera(LatLon(25.0, 121.497), "回報", null, null, mobile = true, bearing = 90.0)
        assertNull(CameraAlerts.ahead(listOf(report), here, 270.0))
    }

    @Test
    fun composesAlertText() {
        val a = CameraAhead(cam(25.0, 121.5), 300)
        assertEquals("📷 前方 300 公尺測速照相・速限 50", CameraAlerts.title(a))
        assertEquals("目前時速約 62，請減速。成功路", CameraAlerts.detail(a, 62))
        assertEquals("成功路", CameraAlerts.detail(a, 45))
        assertEquals("前方 300 公尺測速照相，速限 50，請減速", CameraAlerts.speech(a, 62))
        assertEquals("測速照相，速限 50", CameraAlerts.speech(a.copy(meters = 50), null))
        val mobile = CameraAhead(SpeedCamera(LatLon(25.0, 121.5), "回報", null, null, mobile = true, reportedAt = 0L), 200)
        assertEquals("📷 前方 200 公尺移動式測速", CameraAlerts.title(mobile))
        assertEquals("15 分鐘前有人回報", CameraAlerts.detail(mobile, 70, nowMs = 15 * 60_000L))
        assertTrue(CameraAlerts.key(a.camera) != CameraAlerts.key(mobile.camera.copy(bearing = 90.0)))
    }

    @Test
    fun readsAndDeduplicatesReports() {
        val now = 1_000_000L
        val r = CameraReportRepository.fromFields(25.0, 121.5, -90.0, now - 60_000, now + 60_000, now)!!
        assertTrue(r.mobile)
        assertEquals(270.0, r.bearing!!, 0.0)
        assertEquals(270.0, r.travelHeading!!, 0.0)
        assertNull(CameraReportRepository.fromFields(25.0, 121.5, null, now, now - 1, now))
        assertNull(CameraReportRepository.fromFields(0.0, 0.0, null, now, null, now))
        assertNull(CameraReportRepository.fromFields(null, 121.5, null, now, null, now))

        assertTrue(CameraReportRepository.isDuplicate(listOf(r), LatLon(25.0005, 121.5), 270.0))
        assertTrue(CameraReportRepository.isDuplicate(listOf(r), LatLon(25.0005, 121.5), null))
        // 對向或太遠不算重複
        assertFalse(CameraReportRepository.isDuplicate(listOf(r), LatLon(25.0005, 121.5), 90.0))
        assertFalse(CameraReportRepository.isDuplicate(listOf(r), LatLon(25.01, 121.5), 270.0))
    }
}
