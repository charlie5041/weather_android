package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCamerasTest {
    @Test
    fun parsesMoiRecordsSkippingTheFieldDescriptionRow() {
        val json = """
            {"success":true,"result":{"resource_id":"A01010000C-000674-011","records":[
              {"CityName":"縣市","RegionName":"轄區","Address":"設置地址","DeptNm":"管轄警局","BranchNm":"分局",
               "Longitude":"經度","Latitude":"緯度","direct":"拍攝方向","limit":"速限"},
              {"CityName":"臺北市","RegionName":"內湖區","Address":"成功路四段","DeptNm":"臺北市政府警察局","BranchNm":"內湖分局",
               "Longitude":"121.5900","Latitude":"25.0800","direct":"南向北","limit":"50"},
              {"CityName":"新北市","RegionName":"板橋區","Address":"","Longitude":"121.46","Latitude":"25.01","direct":"雙向","limit":"60公里"},
              {"CityName":"國外","Address":"錯誤座標","Longitude":"0","Latitude":"0","direct":"","limit":""}
            ]}}
        """.trimIndent()
        val (cameras, rows) = SpeedCameraRepository.parse(json)
        assertEquals(4, rows)
        assertEquals(2, cameras.size)
        assertEquals(SpeedCamera(LatLon(25.08, 121.59), "成功路四段", 50, "南向北"), cameras[0])
        // 沒有地址時用縣市與轄區；速限只取數字
        assertEquals("新北市板橋區", cameras[1].address)
        assertEquals(60, cameras[1].limit)
        // 存檔再讀回一樣
        assertEquals(cameras, SpeedCameraRepository.fromJson(SpeedCameraRepository.toJson(cameras)))
        assertEquals(emptyList<SpeedCamera>() to 0, SpeedCameraRepository.parse("""{"result":{}}"""))
    }

    @Test
    fun readsTheShootingDirection() {
        assertEquals(0.0, SpeedCameraRepository.heading("南向北")!!, 0.0)
        assertEquals(180.0, SpeedCameraRepository.heading("北向南")!!, 0.0)
        assertEquals(270.0, SpeedCameraRepository.heading("東向西")!!, 0.0)
        assertEquals(45.0, SpeedCameraRepository.heading("西南向東北")!!, 0.0)
        assertEquals(90.0, SpeedCameraRepository.heading("往東")!!, 0.0)
        assertEquals(180.0, SpeedCameraRepository.heading("南下")!!, 0.0)
        assertEquals(0.0, SpeedCameraRepository.heading("北向")!!, 0.0)
        assertNull(SpeedCameraRepository.heading("雙向"))
        assertNull(SpeedCameraRepository.heading("東西向"))
        assertNull(SpeedCameraRepository.heading("往臺北"))
        assertNull(SpeedCameraRepository.heading(null))
    }

    @Test
    fun keepsCamerasOnTheRouteFacingTheTravelDirection() {
        // 往北約 11 公里的直線
        val path = RoutePath(listOf(LatLon(25.0, 121.5), LatLon(25.1, 121.5)), distanceKm = 11.0, durationMinutes = 25.0)
        fun cam(lat: Double, lon: Double, direct: String?) = SpeedCamera(LatLon(lat, lon), "x", 50, direct)
        val northbound = cam(25.05, 121.5003, "南向北")
        val southbound = cam(25.05, 121.5003, "北向南")
        val both = cam(25.02, 121.5, "雙向")
        val parallelRoad = cam(25.07, 121.505, "南向北")
        val duplicate = cam(25.0501, 121.5002, "南向北")
        val found = SpeedCameraRepository.along(listOf(northbound, southbound, both, parallelRoad, duplicate), path)
        assertEquals(listOf(both, northbound), found.map { it.camera })
        assertEquals(0.2, found[0].fraction, 0.01)
        assertEquals(5.5, found[1].distanceKm, 0.1)
        // 反方向走時只有雙向與北向南的照相
        val back = SpeedCameraRepository.along(listOf(northbound, southbound, both), path.copy(points = path.points.reversed()))
        assertEquals(listOf(southbound, both), back.map { it.camera })
        assertTrue(SpeedCameraRepository.along(emptyList(), path).isEmpty())
    }

    @Test
    fun listsCamerasLastAmongHazards() {
        val route = RouteData(
            from = City("a", "A", "", 25.0, 121.5),
            to = City("b", "B", "", 25.1, 121.5),
            mode = TravelMode.SCOOTER,
            path = RoutePath(emptyList(), 11.0, 25.0),
            points = emptyList(),
            weathers = emptyList(),
            cameras = listOf(
                RouteCamera(SpeedCamera(LatLon(25.02, 121.5), "x", 50, null), 0.2, 2.2),
                RouteCamera(SpeedCamera(LatLon(25.05, 121.5), "y", 70, null), 0.5, 5.5),
                RouteCamera(SpeedCamera(LatLon(25.07, 121.5), "z", null, null), 0.7, 7.7),
            ),
        )
        val hazards = RouteForecast(route, java.time.LocalDateTime.of(2026, 10, 9, 8, 0), emptyList()).hazards
        assertEquals(RouteHazard.Cameras(3, 50, 70), hazards.last())
    }
}
