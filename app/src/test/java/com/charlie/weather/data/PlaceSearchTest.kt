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

    private val world = PlaceSearch.parseWorld(
        File("src/main/assets/world_cities.json").takeIf { it.exists() }?.readText()
            ?: File("app/src/main/assets/world_cities.json").readText(),
    )

    private fun worldNames(query: String) = PlaceSearch.searchWorld(query, world).map { it.english }

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

    @Test
    fun abbreviationsAndAirportCodes() {
        assertEquals("New York", PlaceSearch.expandAlias("nyc"))
        assertEquals("Los Angeles", PlaceSearch.expandAlias("LA"))
        assertEquals("Tokyo", PlaceSearch.expandAlias(" NRT "))
        assertEquals(null, PlaceSearch.expandAlias("Tokyo"))
        // 台灣機場代碼對應到內建地名
        val tpe = PlaceSearch.expandAlias("TPE")!!
        assertEquals("桃園市", names(tpe).first())
        assertEquals("高雄市", names(PlaceSearch.expandAlias("khh")!!).first())
    }

    @Test
    fun singleLetterListsCities() {
        // 台灣縣市的英文名
        assertEquals(listOf("臺北市", "桃園市", "臺中市", "臺南市", "臺東縣", "新北市"), names("t"))
        // 世界城市：熱門城市優先
        val t = worldNames("t")
        assertEquals("Tokyo", t.first())
        assertEquals(20, t.size)
        assertTrue(t.all { it.lowercase().startsWith("t") })
        assertTrue(worldNames("K").first() in setOf("Kobe", "Kyoto", "Kuala Lumpur"))
    }

    @Test
    fun worldSearchByEnglishChineseAndAccents() {
        assertEquals("Tokyo", worldNames("tok").first())
        assertEquals("Tokyo", worldNames("東京").first())
        assertEquals("New York City", worldNames("york").first())
        assertEquals("New York City", worldNames("紐約").first())
        assertEquals("London", worldNames("倫敦").first())
        assertEquals("São Paulo", worldNames("sao paulo").first())
        assertEquals("Sydney", worldNames("雪梨").first())
        val tokyo = PlaceSearch.searchWorld("tokyo", world).first().toCity()
        assertEquals("東京", tokyo.name)
        assertEquals("Tokyo · 日本", tokyo.subtitle)
    }

    @Test
    fun taiwanCountyEnglishNames() {
        assertEquals("臺北市", names("Taipei").first())
        assertEquals("新北市", names("new taipei").first())
        assertEquals("高雄市", names("kaoh").first())
        assertEquals("Taipei · 臺灣", PlaceSearch.search("taipei", places).first().toCity().subtitle)
    }
}
