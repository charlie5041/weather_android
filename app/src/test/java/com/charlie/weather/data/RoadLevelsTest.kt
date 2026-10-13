package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadLevelsTest {
    private fun cam(address: String, mobile: Boolean = false) = SpeedCamera(LatLon(25.0, 121.5), address, 50, null, mobile = mobile)

    @Test
    fun classifiesRouteRoadNames() {
        // OSRM 實際的路名
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofRoad("市民大道高架道路"))
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofRoad("環河快速道路"))
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofRoad("民生陸橋"))
        assertEquals(RoadLevel.SURFACE, RoadLevels.ofRoad("市民大道三段"))
        assertEquals(RoadLevel.SURFACE, RoadLevels.ofRoad("環河南路二段"))
        // 匝道、出口在兩層之間；沒有路名看不出來
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofRoad("光復南路出口匝道"))
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofRoad(""))
        // Google 的導航指示
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofRoad("向右轉，進入市民大道高架道路"))
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofRoad("從市民大道高架道路下交流道"))
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofRoad("繼續直行"))
    }

    @Test
    fun classifiesCameraAddresses() {
        // 警政署資料實際的地址寫法
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofCamera(cam("鹿港鎮台61線177至168.1公里(快速道路)")))
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofCamera(cam("市民大道高架道路往東")))
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofCamera(cam("國道1號 25.3K 北向")))
        assertEquals(RoadLevel.SURFACE, RoadLevels.ofCamera(cam("市民大道三段與建國南路口")))
        // 匝道口在平面路口；沒寫清楚的台XX線可能是快速公路
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofCamera(cam("台68線竹東匝道口與北興路三段")))
        assertEquals(RoadLevel.SURFACE, RoadLevels.ofCamera(cam("竹120線11.5公里竹林交流道北上匝道口")))
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofCamera(cam("台68線12公里處")))
        assertEquals(RoadLevel.UNKNOWN, RoadLevels.ofCamera(cam("使用者回報", mobile = true)))
        assertEquals("快速道路", RoadLevels.tag(cam("台61線170.11公里(快速道路)")))
        assertEquals("高架", RoadLevels.tag(cam("市民大道高架道路")))
        assertNull(RoadLevels.tag(cam("成功路四段")))
    }

    @Test
    fun skipsCamerasOnTheOtherLevel() {
        // 0–1 公里在平面的市民大道，1–2 公里上了高架
        val roads = RoadLevels.spans(listOf(1000.0 to "市民大道三段", 1000.0 to "市民大道高架道路"))
        val elevated = cam("市民大道高架道路往東")
        val surface = cam("市民大道三段與建國南路口")
        assertFalse(RoadLevels.matches(elevated, 0.5, roads))
        assertTrue(RoadLevels.matches(surface, 0.5, roads))
        assertTrue(RoadLevels.matches(elevated, 1.5, roads))
        assertFalse(RoadLevels.matches(surface, 1.5, roads))
        // 在兩段交界附近、看不出來的照相、沒有路名資料：都保留
        assertTrue(RoadLevels.matches(elevated, 1.02, roads))
        assertTrue(RoadLevels.matches(cam("台68線12公里處"), 0.5, roads))
        assertTrue(RoadLevels.matches(elevated, 0.5, emptyList()))
    }

    @Test
    fun alongDropsTheCameraOnTheElevatedRoadAbove() {
        // 往東約 2 公里的直線：平面道路下方與高架上方各有一支同方向的照相（座標幾乎一樣）
        val points = listOf(LatLon(25.044, 121.53), LatLon(25.044, 121.55))
        val surfacePath = RoutePath(points, 2.0, 5.0, roads = RoadLevels.spans(listOf(2000.0 to "市民大道四段")))
        val above = SpeedCamera(LatLon(25.0441, 121.54), "市民大道高架道路往東", 70, "西向東")
        val below = SpeedCamera(LatLon(25.0440, 121.545), "市民大道四段往東", 50, "西向東")
        assertEquals(listOf(below), SpeedCameraRepository.along(listOf(above, below), surfacePath).map { it.camera })
        val elevatedPath = surfacePath.copy(roads = RoadLevels.spans(listOf(2000.0 to "市民大道高架道路")))
        assertEquals(listOf(above), SpeedCameraRepository.along(listOf(above, below), elevatedPath).map { it.camera })
        // 沒有路名（直線估計、舊資料）時兩支都提醒
        assertEquals(2, SpeedCameraRepository.along(listOf(above, below), surfacePath.copy(roads = emptyList())).size)
    }

    @Test
    fun keepsRoadNamesFromOsrmAndGoogle() {
        val osrm = """
            {"code":"Ok","routes":[{"distance":2000,"duration":180,
              "geometry":{"type":"LineString","coordinates":[[121.53,25.044],[121.55,25.044]]},
              "legs":[{"steps":[{"distance":1200,"name":"市民大道三段"},{"distance":800,"name":"市民大道高架道路"}]}]}]}
        """.trimIndent()
        val osrmRoads = RouteApi.parse(osrm)!!.roads
        assertEquals(listOf("市民大道三段", "市民大道高架道路"), osrmRoads.map { it.name })
        assertEquals(1.2, osrmRoads[1].startKm, 1e-9)
        assertEquals(2.0, osrmRoads[1].endKm, 1e-9)
        val google = """
            {"routes":[{"duration":"300s","distanceMeters":2000,
              "polyline":{"geoJsonLinestring":{"type":"LineString","coordinates":[[121.53,25.044],[121.55,25.044]]}},
              "legs":[{"steps":[{"distanceMeters":500,"navigationInstruction":{"instructions":"向東走市民大道四段"}},
                                {"distanceMeters":1500,"navigationInstruction":{"instructions":"向右轉，進入市民大道高架道路"}}]}]}]}
        """.trimIndent()
        val roads = GoogleRoutes.parse(google)!!.roads
        assertEquals(2, roads.size)
        assertEquals(0.5, roads[1].startKm, 1e-9)
        assertEquals(RoadLevel.ELEVATED, RoadLevels.ofRoad(roads[1].name))
    }

    @Test
    fun tagsElevatedCamerasInAlerts() {
        val a = CameraAhead(SpeedCamera(LatLon(25.0, 121.5), "市民大道高架道路往東", 70, null), 300)
        assertEquals("📷 前方 300 公尺測速照相（高架）・速限 70", CameraAlerts.title(a))
        assertEquals("前方 300 公尺測速照相，在高架上，速限 70", CameraAlerts.speech(a, 60))
    }
}
