package com.charlie.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * 從 Google 地圖分享的路線連結取出起點、途經點、終點與交通方式。
 *
 * 分享出來的通常是短網址（maps.app.goo.gl/…），要先跟著轉址拿到完整網址，常見的格式有：
 * - `google.com/maps/dir/起點/途經/終點/@緯度,經度,縮放/data=…!2m2!1d經度!2d緯度…!3e0`
 * - `google.com/maps/dir/?api=1&origin=…&destination=…&waypoints=…&travelmode=…`
 * - `maps.google.com/maps?saddr=…&daddr=…+to:…`
 */
object GoogleMapsLink {

    /** 路線上的一個地點：有座標時直接使用，否則用 [name] 查地址；兩者都沒有代表「目前位置」。 */
    data class Stop(val name: String?, val position: LatLon?) {
        /** 可以顯示的地名（網址裡只寫座標時為 null） */
        val label: String? get() = name?.takeIf { latLon(it) == null }
    }

    data class Route(val stops: List<Stop>, val mode: TravelMode?)

    private val URL_REGEX = Regex("""https?://\S+""")
    private val LAT_LON = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val DATA_COORD = Regex("""!2m2!1d(-?\d+(?:\.\d+)?)!2d(-?\d+(?:\.\d+)?)""")
    private val DATA_MODE = Regex("""!3e(\d+)""")

    /** 分享文字中的第一個 Google 地圖網址；其他網址或沒有網址時回傳 null。 */
    fun findUrl(text: String): String? =
        URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ')', '」', '。') }.firstOrNull(::isMapsUrl)

    fun isMapsUrl(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "maps.app.goo.gl" || host == "goo.gl" ||
            (host.contains("google.") && (host.startsWith("maps.") || URI(url).path.orEmpty().startsWith("/maps")))
    }

    /** 短網址跟著轉址（最多 5 次），回傳最後的完整網址。 */
    suspend fun expand(url: String): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(5) {
            val host = URI(current).host?.lowercase().orEmpty()
            if (host != "maps.app.goo.gl" && host != "goo.gl") return@withContext current
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code !in 300..399 || location == null) throw IOException("HTTP $code")
                current = URL(URL(current), location).toString()
            } finally {
                conn.disconnect()
            }
        }
        current
    }

    /** 解析完整網址；不是路線（例如只是一個地點）時回傳 null。 */
    fun parse(url: String): Route? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        val query = queryParams(uri.rawQuery)
        return when {
            path.startsWith("/maps/dir") && query.containsKey("destination") -> {
                val stops = listOf(query["origin"]?.let(::stop) ?: Stop(null, null)) +
                    query["waypoints"].orEmpty().split('|').filter { it.isNotBlank() }.map(::stop) +
                    stop(query.getValue("destination"))
                Route(stops, apiMode(query["travelmode"]))
            }
            path.startsWith("/maps/dir/") -> parseDirPath(path)
            query.containsKey("daddr") -> {
                val dest = query.getValue("daddr").split(" to:").filter { it.isNotBlank() }.map(::stop)
                if (dest.isEmpty()) return null
                Route(listOf(query["saddr"]?.let(::stop) ?: Stop(null, null)) + dest, legacyMode(query["dirflg"]))
            }
            else -> null
        }?.takeIf { it.stops.size >= 2 }
    }

    /** `/maps/dir/起點/終點/@…/data=…`：地名在路徑段，座標放在 data 參數裡。 */
    private fun parseDirPath(path: String): Route? {
        val parts = path.removePrefix("/maps/dir/").split('/')
        val stopEnd = parts.indexOfFirst { it.startsWith("@") || it.startsWith("data=") }.let { if (it < 0) parts.size else it }
        val names = parts.take(stopEnd).map { decode(it).trim() }
        // 結尾的空段是網址最後的斜線，不是地點
        val segments = names.dropLastWhile { it.isEmpty() }
        if (segments.size < 2) return null
        val data = parts.drop(stopEnd).firstOrNull { it.startsWith("data=") }.orEmpty()
        val coords = DATA_COORD.findAll(data).map { LatLon(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }.toList()
        // data 只為「有名稱的地點」記座標；本身就是座標或「目前位置」（空白）的段沒有
        val named = segments.indices.filter { segments[it].isNotEmpty() && latLon(segments[it]) == null }
        val stops = segments.mapIndexed { i, s ->
            val position = latLon(s) ?: if (coords.size == named.size) coords.getOrNull(named.indexOf(i)) else null
            Stop(s.ifEmpty { null }, position)
        }
        val mode = DATA_MODE.findAll(data).lastOrNull()?.groupValues?.get(1)?.let(::dataMode)
        return Route(stops, mode)
    }

    private fun stop(value: String): Stop {
        val v = value.trim()
        return Stop(v.ifEmpty { null }, latLon(v))
    }

    private fun latLon(s: String): LatLon? = LAT_LON.find(s)?.let { m ->
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }

    private fun dataMode(code: String) = when (code) {
        "0" -> TravelMode.CAR
        "1" -> TravelMode.BIKE
        "2" -> TravelMode.WALK
        "9" -> TravelMode.SCOOTER
        else -> null
    }

    private fun apiMode(mode: String?) = when (mode?.lowercase()) {
        "driving" -> TravelMode.CAR
        "bicycling" -> TravelMode.BIKE
        "walking" -> TravelMode.WALK
        "two-wheeler", "twowheeler" -> TravelMode.SCOOTER
        else -> null
    }

    private fun legacyMode(flag: String?) = when (flag) {
        "d" -> TravelMode.CAR
        "b" -> TravelMode.BIKE
        "w" -> TravelMode.WALK
        else -> null
    }

    private fun queryParams(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val key = it.substringBefore('=')
            decode(key) to decode(it.substringAfter('=', ""))
        }

    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%20"), "UTF-8")
}
