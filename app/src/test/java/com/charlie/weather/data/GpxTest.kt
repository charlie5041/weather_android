package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    private fun parse(xml: String) = Gpx.parse(xml.byteInputStream())

    @Test
    fun writeThenParseKeepsPointsTimesAndSegments() {
        val a = listOf(TrackPoint(1_700_000_000_000, 25.0, 121.5), TrackPoint(1_700_000_010_000, 25.001, 121.5))
        val b = listOf(TrackPoint(1_700_000_600_000, 25.01, 121.51), TrackPoint(1_700_000_610_000, 25.011, 121.51))
        val xml = Gpx.write("中和區 → 內湖區 & <test>", listOf(a, b))
        val gpx = parse(xml)
        assertEquals("中和區 → 內湖區 & <test>", gpx.name)
        assertEquals(listOf(a, b), gpx.segments)
        assertEquals(0, gpx.withoutTime)
    }

    @Test
    fun readsOtherAppsGpxWithElevationAndNamespacePrefix() {
        val xml = """
            <?xml version="1.0"?>
            <gpx:gpx xmlns:gpx="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
              <gpx:metadata><gpx:name>Metadata name</gpx:name></gpx:metadata>
              <gpx:trk><gpx:trkseg>
                <gpx:trkpt lat="25.0" lon="121.5"><gpx:ele>10</gpx:ele><gpx:time>2026-10-21T00:30:00.500Z</gpx:time></gpx:trkpt>
                <gpx:trkpt lat="25.001" lon="121.5"><gpx:ele>30</gpx:ele><gpx:time>2026-10-21T08:30:10+08:00</gpx:time></gpx:trkpt>
              </gpx:trkseg></gpx:trk>
            </gpx:gpx>
        """.trimIndent()
        val gpx = parse(xml)
        assertEquals("Metadata name", gpx.name)
        assertEquals(1, gpx.segments.size)
        assertEquals(Gpx.parseTime("2026-10-21T00:30:00Z")!! + 500, gpx.segments[0][0].time)
        assertEquals(Gpx.parseTime("2026-10-21T00:30:10Z"), gpx.segments[0][1].time)
        assertEquals(listOf(10.0, 30.0), gpx.elevations[0])
    }

    @Test
    fun plannedRouteWithoutTimesIsCounted() {
        val xml = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <rte><rtept lat="25.0" lon="121.5"/><rtept lat="25.1" lon="121.5"/></rte>
              <trk><trkseg><trkpt lat="25.0" lon="121.5"/></trkseg></trk>
            </gpx>
        """.trimIndent()
        val gpx = parse(xml)
        assertTrue(gpx.segments.isEmpty())
        assertEquals(3, gpx.withoutTime)
    }

    @Test(expected = Exception::class)
    fun rejectsNonGpx() {
        parse("<kml><Document/></kml>")
    }

    @Test
    fun timeWithoutZoneIsUtc() {
        assertEquals(Gpx.parseTime("2026-10-21T00:30:00Z"), Gpx.parseTime("2026-10-21T00:30:00"))
        assertNull(Gpx.parseTime("yesterday"))
    }

    @Test
    fun climbFromElevations() {
        assertNull(Gpx.climbM(listOf(listOf(null, null))))
        val up = (0..40).map { it.toDouble() } // 每點升 1 公尺，共 40 公尺
        val climb = Gpx.climbM(listOf(up))!!
        assertTrue("climb=$climb", climb in 30.0..40.0)
    }
}
