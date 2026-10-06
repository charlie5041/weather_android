package com.charlie.weather.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class GoogleRoutesTest {
    private val home = LatLon(25.08, 121.57)
    private val work = LatLon(25.03, 121.56)
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    @Test
    fun scooterUsesTwoWheelerWithTrafficAndFutureDeparture() {
        val departure = LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneId.systemDefault())
        val body = JSONObject(GoogleRoutes.requestBody(home, work, TravelMode.SCOOTER, departure, now))
        assertEquals("TWO_WHEELER", body.getString("travelMode"))
        assertEquals("TRAFFIC_AWARE", body.getString("routingPreference"))
        assertEquals("2026-10-06T01:00:00Z", body.getString("departureTime"))
        assertEquals(25.08, body.getJSONObject("origin").getJSONObject("location").getJSONObject("latLng").getDouble("latitude"), 0.0)
        assertEquals("GEO_JSON_LINESTRING", body.getString("polylineEncoding"))
    }

    @Test
    fun pastDepartureMeansNowAndBikeHasNoTraffic() {
        val past = LocalDateTime.ofInstant(now.minusSeconds(600), ZoneId.systemDefault())
        val car = JSONObject(GoogleRoutes.requestBody(home, work, TravelMode.CAR, past, now))
        assertFalse(car.has("departureTime"))
        val bike = JSONObject(GoogleRoutes.requestBody(home, work, TravelMode.BIKE, past, now))
        assertEquals("BICYCLE", bike.getString("travelMode"))
        assertFalse(bike.has("routingPreference"))
    }

    @Test
    fun parsesRouteResponse() {
        val json = """
            {"routes":[{"distanceMeters":8650,"duration":"1530s",
              "polyline":{"geoJsonLinestring":{"type":"LineString","coordinates":[[121.57,25.08],[121.565,25.05],[121.56,25.03]]}}}]}
        """.trimIndent()
        val path = GoogleRoutes.parse(json)!!
        assertEquals(RouteSource.GOOGLE, path.source)
        assertEquals(8.65, path.distanceKm, 1e-9)
        assertEquals(25.5, path.durationMinutes, 1e-9)
        assertEquals(LatLon(25.03, 121.56), path.points.last())
        assertFalse(path.approximate)
        assertNull(GoogleRoutes.parse("{}"))
    }

    @Test
    fun straightLineIsMarkedAsEstimate() {
        assertTrue(RouteApi.straightLine(home, work, TravelMode.WALK).approximate)
    }
}
