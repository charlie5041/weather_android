package com.charlie.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan

data class MapTile(val zoom: Int, val x: Int, val y: Int)

/**
 * 以 Web Mercator 把路線縮放到畫面大小，算出需要的地圖圖磚。
 * 座標單位是螢幕像素：一張圖磚在畫面上佔 [tilePx]。
 */
data class MapViewport(val zoom: Int, val tilePx: Float, val originX: Double, val originY: Double, val width: Float, val height: Float) {
    fun project(p: LatLon): Pair<Float, Float> =
        (WebMercator.x(p.longitude, zoom, tilePx) - originX).toFloat() to (WebMercator.y(p.latitude, zoom, tilePx) - originY).toFloat()

    /** 畫面範圍內的圖磚與它們左上角的螢幕座標。 */
    fun tiles(): List<Pair<MapTile, Pair<Float, Float>>> {
        val n = 1 shl zoom
        val x0 = floor(originX / tilePx).toInt()
        val y0 = floor(originY / tilePx).toInt().coerceAtLeast(0)
        val x1 = floor((originX + width) / tilePx).toInt()
        val y1 = floor((originY + height) / tilePx).toInt().coerceAtMost(n - 1)
        return (y0..y1).flatMap { ty ->
            (x0..x1).map { tx ->
                MapTile(zoom, ((tx % n) + n) % n, ty) to ((tx * tilePx - originX).toFloat() to (ty * tilePx - originY).toFloat())
            }
        }
    }
}

object WebMercator {
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 16

    fun x(lon: Double, zoom: Int, tilePx: Float): Double = (lon + 180) / 360 * tilePx * (1 shl zoom)

    fun y(lat: Double, zoom: Int, tilePx: Float): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(rad) + 1 / kotlin.math.cos(rad)) / PI) / 2 * tilePx * (1 shl zoom)
    }

    /** 選擇讓所有點（留 [padPx] 邊界）都放得下的最大縮放等級，並置中。 */
    fun fit(points: List<LatLon>, width: Float, height: Float, tilePx: Float, padPx: Float): MapViewport {
        val xs = points.map { x(it.longitude, 0, tilePx) }
        val ys = points.map { y(it.latitude, 0, tilePx) }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
        val scale = minOf((width - 2 * padPx) / spanX, (height - 2 * padPx) / spanY)
        val zoom = floor(log2(scale)).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = (1 shl zoom).toDouble()
        val centerX = (xs.max() + xs.min()) / 2 * factor
        val centerY = (ys.max() + ys.min()) / 2 * factor
        return MapViewport(zoom, tilePx, centerX - width / 2, centerY - height / 2, width, height)
    }
}

/**
 * 地圖圖磚：OpenStreetMap 標準圖磚（沒有 Google 金鑰時使用）。
 * 依 OSM 的圖磚使用規範帶上可辨識的 User-Agent，下載的 PNG 存在 cacheDir 30 天，
 * 同一條通勤路線幾乎不用再下載。
 */
class MapTileCache(cacheDir: File) {
    private val dir = File(cacheDir, "map_tiles").apply { mkdirs() }
    private val locks = mutableMapOf<MapTile, Mutex>()

    fun url(tile: MapTile) = "https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png"

    /** 圖磚的 PNG 內容；離線且沒有快取時為 null。 */
    suspend fun load(tile: MapTile): ByteArray? = withContext(Dispatchers.IO) {
        val lock = synchronized(locks) { locks.getOrPut(tile) { Mutex() } }
        lock.withLock {
            val f = File(dir, "${tile.zoom}_${tile.x}_${tile.y}.png")
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL) return@withLock f.readBytes()
            try {
                val bytes = download(url(tile))
                f.writeBytes(bytes)
                bytes
            } catch (e: IOException) {
                f.takeIf { it.exists() }?.readBytes()
            }
        }
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MyWeatherAndroid/1.0 (github.com/charlie5041/weather_android)")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TTL = 30L * 24 * 60 * 60_000
    }
}
