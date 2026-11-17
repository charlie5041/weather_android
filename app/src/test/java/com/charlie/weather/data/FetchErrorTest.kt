package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FetchErrorTest {
    @Test fun quotaExceededIsNotBlamedOnNetwork() {
        val e = IOException("無法取得沿路預報", HttpStatusException(429, "Daily API request limit exceeded. Please try again tomorrow."))
        val msg = fetchErrorMessage(e, "沿路天氣")
        assertTrue(msg, msg.contains("免費查詢次數用完"))
        assertTrue(msg, !msg.contains("網路"))
    }

    @Test fun badRequestShowsServiceReason() {
        val msg = fetchErrorMessage(HttpStatusException(400, "Parameter 'forecast_days' must be between 0 and 16"), "沿路天氣")
        assertEquals("天氣服務不接受這次查詢（錯誤 400：Parameter 'forecast_days' must be between 0 and 16）", msg)
    }

    @Test fun serverErrorAsksToRetry() {
        assertTrue(fetchErrorMessage(HttpStatusException(503), "沿路天氣").contains("暫時故障（錯誤 503）"))
    }

    @Test fun noDnsMeansNoInternet() {
        val msg = fetchErrorMessage(IOException("wrap", UnknownHostException("api.open-meteo.com")), "沿路天氣")
        assertEquals("連不上天氣服務，無法取得沿路天氣。請確認網路可以上網", msg)
    }

    @Test fun timeout() {
        assertTrue(fetchErrorMessage(SocketTimeoutException(), "最新天氣").contains("回應太慢"))
    }

    @Test fun appBugNamesTheException() {
        val msg = fetchErrorMessage(NoSuchElementException("List is empty."), "沿路天氣")
        assertTrue(msg, msg.contains("App 出錯了（NoSuchElementException"))
    }

    @Test fun reasonFromOpenMeteoBody() {
        assertEquals("Daily API request limit exceeded.", errorReason("""{"error":true,"reason":"Daily API request limit exceeded."}"""))
        assertEquals(null, errorReason("<html>bad gateway</html>"))
    }
}
