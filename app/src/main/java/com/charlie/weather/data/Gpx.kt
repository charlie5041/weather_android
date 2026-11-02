package com.charlie.weather.data

import org.w3c.dom.Element
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 讀到的 GPX 軌跡：[segments] 依 `<trkseg>` 分段（段與段之間當暫停），[elevations] 是各點的高度（沒有時為 null）。
 * [withoutTime] 是沒有時間的點數（含 `<rte>` 規劃路線）：多半是規劃好的路線，不是騎過的紀錄。
 */
data class GpxTrack(
    val name: String?,
    val segments: List<List<TrackPoint>>,
    val elevations: List<List<Double?>>,
    val withoutTime: Int = 0,
)

/** GPX 1.1 的匯出與讀取（只處理軌跡，不含照片與天氣）。 */
object Gpx {
    const val MIME = "application/gpx+xml"

    private val isoUtc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)

    /** 把足跡寫成 GPX；每一段是一個 `<trkseg>`（暫停或隱私區斷開的地方會分段） */
    fun write(name: String, segments: List<List<TrackPoint>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"一路晴\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        val first = segments.firstOrNull { it.isNotEmpty() }?.first()
        append("  <metadata>\n    <name>").append(escape(name)).append("</name>\n")
        first?.let { append("    <time>").append(isoUtc.format(Instant.ofEpochMilli(it.time))).append("</time>\n") }
        append("  </metadata>\n")
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <type>cycling</type>\n")
        segments.filter { it.isNotEmpty() }.forEach { seg ->
            append("    <trkseg>\n")
            seg.forEach { p ->
                append("      <trkpt lat=\"").append("%.6f".format(Locale.US, p.latitude))
                    .append("\" lon=\"").append("%.6f".format(Locale.US, p.longitude)).append("\">")
                    .append("<time>").append(isoUtc.format(Instant.ofEpochMilli(p.time))).append("</time></trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** GPX 的時間：通常是 UTC（…Z），也接受帶時區或不帶時區（視為 UTC）的寫法 */
    fun parseTime(text: String?): Long? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    /** 讀 GPX；不是 GPX（XML 壞掉）時丟出例外 */
    fun parse(input: InputStream): GpxTrack {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // 不讀外部實體（XXE）；Android 的實作不支援某些設定時就略過
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val root = doc.documentElement
        require(root.localNameOrTag() == "gpx") { "not a gpx file" }
        val segments = mutableListOf<List<TrackPoint>>()
        val elevations = mutableListOf<List<Double?>>()
        var withoutTime = 0
        var trackName: String? = null
        root.children("trk").forEach { trk ->
            if (trackName == null) trackName = trk.childText("name")
            trk.children("trkseg").forEach { seg ->
                val points = mutableListOf<TrackPoint>()
                val eles = mutableListOf<Double?>()
                seg.children("trkpt").forEach { pt ->
                    val lat = pt.getAttribute("lat").toDoubleOrNull()
                    val lon = pt.getAttribute("lon").toDoubleOrNull()
                    val time = parseTime(pt.childText("time"))
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
                    if (time == null) {
                        withoutTime++
                        return@forEach
                    }
                    points += TrackPoint(time, lat, lon)
                    eles += pt.childText("ele")?.trim()?.toDoubleOrNull()
                }
                if (points.isNotEmpty()) {
                    segments += points
                    elevations += eles
                }
            }
        }
        // 規劃路線（<rte>）沒有時間，算進「沒有時間」讓畫面說明
        root.children("rte").forEach { rte -> withoutTime += rte.children("rtept").size }
        val name = trackName?.trim()?.takeIf { it.isNotEmpty() }
            ?: root.children("metadata").firstOrNull()?.childText("name")?.trim()?.takeIf { it.isNotEmpty() }
        return GpxTrack(name, segments, elevations, withoutTime)
    }

    /** 由 GPX 的高度算累計爬升（和騎乘中一樣平滑、門檻）；沒有高度時為 null */
    fun climbM(elevations: List<List<Double?>>): Double? {
        if (elevations.none { seg -> seg.any { it != null } }) return null
        val counter = ClimbCounter()
        elevations.forEach { seg ->
            counter.restart()
            seg.forEach { e -> e?.let(counter::add) }
        }
        return counter.climbM
    }

    private fun Element.localNameOrTag(): String = localName ?: tagName.substringAfter(':')

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localNameOrTag() == name }
    }

    private fun Element.childText(name: String): String? = children(name).firstOrNull()?.textContent
}
