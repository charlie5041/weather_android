package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaceSearchTest {
    private val places = PlaceSearch.parse(
        File("src/main/assets/taiwan_places.json").takeIf { it.exists() }?.readText()
            ?: File("app/src/main/assets/taiwan_places.json").readText(),
    )

    private fun names(query: String) = PlaceSearch.search(query, places).map { it.county + it.township }

    @Test
    fun containsAllCountiesAndTownships() {
        assertEquals(22, places.count { it.isCounty })
        assertEquals(368, places.count { !it.isCounty })
    }

    @Test
    fun taiAndTaiVariantsMatch() {
        assertEquals("臺中市", names("台中").first())
        assertEquals("臺中市", names("臺中").first())
        assertEquals("臺中市", names("台中市").first())
        assertEquals("臺北市", names("台北").first())
        assertEquals("臺東縣", names("台東").first())
    }

    @Test
    fun townshipByPartialName() {
        assertEquals("臺中市北屯區", names("北屯").first())
        assertEquals("新北市板橋區", names("板橋").first())
        assertEquals("臺中市北屯區", names("台中北屯").first())
        assertEquals("臺中市北屯區", names("臺中市北屯區").first())
        assertEquals("臺中市北屯區", names("台中市北屯區大坑").first())
    }

    @Test
    fun ambiguousTownshipsListAll() {
        // 「東區」在新竹市、嘉義市、臺南市都有
        val result = names("東區")
        assertTrue(result.containsAll(listOf("新竹市東區", "嘉義市東區", "臺南市東區")))
    }

    @Test
    fun countyRanksBeforeItsTownships() {
        val result = names("新竹")
        assertEquals(setOf("新竹市", "新竹縣"), result.take(2).toSet())
    }

    @Test
    fun variantsForOnlineSearch() {
        assertEquals(listOf("台中", "臺中"), PlaceSearch.variants("台中"))
        assertEquals(listOf("Tokyo"), PlaceSearch.variants("Tokyo"))
    }
}
