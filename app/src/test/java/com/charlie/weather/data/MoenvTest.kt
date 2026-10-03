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
}
