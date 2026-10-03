package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TyphoonTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader?.getResource("cwa/$name")).readText()

    @Test
    fun parsesActiveTyphoon() {
        val list = TyphoonParser.parse(fixture("W-C0034-005.json"))
        assertEquals(1, list.size)
        val t = list.first()
        assertEquals("彩雲", t.nameZh)
        assertEquals("CHOI-WAN", t.nameEn)
        assertEquals(12, t.past.size)
        val now = t.current
        assertEquals(LocalDateTime.of(2026, 10, 3, 2, 0), now.time)
        assertEquals(17.9, now.latitude, 0.001)
        assertEquals(144.7, now.longitude, 0.001)
        assertEquals(33.0, now.maxWind!!, 0.001)
        assertEquals(970, now.pressure)
        assertEquals(220.0, now.radius15!!, 0.001)
        assertEquals(70.0, now.radius25!!, 0.001)
        assertEquals("中度颱風", t.category)
        assertEquals("以每小時18公里速度，向北北東進行", t.movement)
        // 預報點時間 = 發布時間 + 預報時數
        assertEquals(LocalDateTime.of(2026, 10, 3, 8, 0), t.forecast.first().time)
        assertEquals(30.0, t.forecast.first().probabilityRadius!!, 0.001)
        assertTrue(t.forecast.zipWithNext().all { (a, b) -> a.time < b.time })
    }

    @Test
    fun noTyphoonGivesEmptyList() {
        assertTrue(TyphoonParser.parse("""{"cwaopendata":{"Dataset":{"TropicalCyclones":null}}}""").isEmpty())
        assertTrue(TyphoonParser.parse("""{"cwaopendata":{"Dataset":{}}}""").isEmpty())
    }
}
