package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTilesTest {
    private val tilePx = 512f

    @Test
    fun projectsToStandardTileNumbers() {
        // 臺北車站附近在 z12 的圖磚是 (3430, 1753)
        assertEquals(3430, (WebMercator.x(121.53, 12, 1f)).toInt())
        assertEquals(1753, (WebMercator.y(25.04, 12, 1f)).toInt())
    }

    @Test
    fun fitKeepsWholeRouteInsidePadding() {
        val route = listOf(LatLon(25.08, 121.57), LatLon(25.06, 121.59), LatLon(25.03, 121.56))
        val vp = WebMercator.fit(route, width = 688f, height = 480f, tilePx = tilePx, padPx = 56f)
        route.forEach { p ->
            val (x, y) = vp.project(p)
            assertTrue("x=$x", x in 56f - 1..688f - 56 + 1)
            assertTrue("y=$y", y in 56f - 1..480f - 56 + 1)
        }
        // 再放大一級就放不下
        val xs = route.map { WebMercator.x(it.longitude, vp.zoom + 1, tilePx) }
        val ys = route.map { WebMercator.y(it.latitude, vp.zoom + 1, tilePx) }
        assertTrue(xs.max() - xs.min() > 688 - 112 || ys.max() - ys.min() > 480 - 112)
    }

    @Test
    fun tilesCoverTheViewport() {
        val route = listOf(LatLon(25.08, 121.57), LatLon(25.03, 121.56))
        val vp = WebMercator.fit(route, 688f, 480f, tilePx, 56f)
        val tiles = vp.tiles()
        assertTrue(tiles.all { it.first.zoom == vp.zoom })
        val left = tiles.minOf { it.second.first }
        val top = tiles.minOf { it.second.second }
        val right = tiles.maxOf { it.second.first } + tilePx
        val bottom = tiles.maxOf { it.second.second } + tilePx
        assertTrue(left <= 0f && top <= 0f && right >= 688f && bottom >= 480f)
        assertTrue(tiles.size <= 9)
    }

    @Test
    fun singlePointUsesMaxZoom() {
        val vp = WebMercator.fit(listOf(LatLon(25.0, 121.5)), 688f, 480f, tilePx, 56f)
        assertEquals(WebMercator.MAX_ZOOM, vp.zoom)
        val (x, y) = vp.project(LatLon(25.0, 121.5))
        assertEquals(344f, x, 0.5f)
        assertEquals(240f, y, 0.5f)
    }
}
