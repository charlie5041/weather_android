package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoenvTest {
    private val json = requireNotNull(javaClass.classLoader?.getResource("moenv/aqx_p_432.json")).readText()

    @Test
    fun parsesStationsAndMissingValues() {
        val stations = MoenvParser.parse(json)
        assertEquals(3, stations.size)
        val zhongshan = stations.first { it.name == "中山" }
        assertEquals(42, zhongshan.aqi)
        assertEquals("良好", zhongshan.status)
        assertEquals(11.0, zhongshan.pm25!!, 0.001)
        assertNull(zhongshan.pollutant)
        val datong = stations.first { it.name == "大同" }
        assertNull(datong.aqi)
        assertNull(datong.pm10)
    }

    @Test
    fun nearestSkipsStationsWithoutAqi() {
        val stations = MoenvParser.parse(json)
        // 大同站比中山站近，但正在維護沒有 AQI
        val near = MoenvParser.nearest(stations, 25.0632, 121.5133)
        assertEquals("中山", near?.name)
        assertNull(MoenvParser.nearest(stations, 35.68, 139.69))
    }

    @Test
    fun acceptsBareArrayAndUppercaseKeys() {
        val stations = MoenvParser.parse("""[{"SiteName":"板橋","County":"新北市","AQI":"55","Status":"普通","Latitude":"25.0129","Longitude":"121.4581"}]""")
        assertEquals("板橋", stations.single().name)
        assertEquals(55, stations.single().aqi)
    }

    @Test
    fun parsesRealApiResponse() {
        // 2026-10-03 實際從環境部 API 取得的回應（陣列格式）
        val real = requireNotNull(javaClass.classLoader?.getResource("moenv/aqx_p_432_real.json")).readText()
        val stations = MoenvParser.parse(real)
        assertEquals(3, stations.size)
        val xizhi = stations.first { it.name == "汐止" }
        assertEquals(52, xizhi.aqi)
        assertEquals("普通", xizhi.status)
        assertEquals("細懸浮微粒", xizhi.pollutant)
        assertEquals(14.0, xizhi.pm25!!, 0.001)
        assertNull(stations.first { it.name == "新店" }.pollutant)
        // 汐止火車站附近 → 汐止測站
        assertEquals("汐止", MoenvParser.nearest(stations, 25.0687, 121.6618)?.name)
    }
}
