package com.charlie.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charlie.weather.data.AirQuality
import com.charlie.weather.data.City
import com.charlie.weather.data.CurrentConditions
import com.charlie.weather.data.CwaAlert
import com.charlie.weather.data.CwaSummary
import com.charlie.weather.data.DailyForecast
import com.charlie.weather.data.HourlyForecast
import com.charlie.weather.data.PlaceSearch
import com.charlie.weather.data.TyphoonParser
import com.charlie.weather.data.Weather
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * 產生各畫面的截圖（CI 會推到 ci-screenshots 分支），用來在發佈前檢查排版。
 * 動畫時鐘暫停，避免背景的無限動畫讓測試無法結束。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val taipei = City("geo_1", "臺北市", "臺灣", 25.04, 121.53)
    private val location = City("current_location", "大安區", "我的位置", 25.03, 121.54, isCurrentLocation = true)

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { content() } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w400dp-h2900dp-xhdpi")
    fun weatherPageFull() = capture("01_weather_page_full") {
        CityWeatherPage(location, CityWeatherUi(weather = sampleWeather(code = 2, typhoon = true)), onRefresh = {})
    }

    @Test
    @Config(qualifiers = "w400dp-h1400dp-xhdpi")
    fun routeResult() {
        val home = City("addr_1", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "住家")
        val work = City("addr_2", "信義區", "臺北市信義區", 25.03, 121.56, label = "公司")
        val dry = sampleWeather(code = 2)
        val wet = sampleWeather(code = 61)
        val path = com.charlie.weather.data.RoutePath(
            listOf(
                com.charlie.weather.data.LatLon(25.08, 121.57),
                com.charlie.weather.data.LatLon(25.07, 121.59),
                com.charlie.weather.data.LatLon(25.05, 121.58),
                com.charlie.weather.data.LatLon(25.03, 121.56),
            ),
            9.2, 26.0,
        )
        val points = com.charlie.weather.data.RoutePlanner.sample(path)
        val weathers = points.mapIndexed { i, _ -> if (i >= points.size / 2) wet else dry }
        val data = com.charlie.weather.data.RouteData(home, work, com.charlie.weather.data.TravelMode.SCOOTER, path, points, weathers)
        val now = dry.current.time
        val forecast = com.charlie.weather.data.RoutePlanner.evaluate(data, now, now)
        // 先把地圖圖磚載入記憶體（地圖寬 400 - 32 - 24 = 344dp、高 240dp），截圖才會有底圖；離線時只有路線
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                routeViewport(path.points, 688f, 480f, 2f).tiles().forEach { (tile, _) -> RouteMapTiles.load(context, tile) }
            }
        }
        capture("11_route") {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(16.dp),
            ) {
                RouteResult(forecast)
            }
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h420dp-xhdpi")
    fun commuteCard() = capture("10_commute") {
        val home = City("addr_1", "內湖區", "臺北市內湖區", 25.08, 121.57, label = "住家")
        val work = City("addr_2", "信義區", "臺北市信義區", 25.03, 121.56, label = "公司")
        val homeWeather = sampleWeather(code = 2)
        val workWeather = sampleWeather(code = 61)
        val now = homeWeather.current.time
        val trip = com.charlie.weather.data.Commute.trip(home, work, homeWeather, workWeather, now, 8, 18)
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF3A6EA5)).padding(16.dp),
        ) {
            CommuteCard(trip, androidx.compose.ui.Modifier.fillMaxWidth(), today = now.toLocalDate())
        }
    }

    @Test
    fun typhoonScreen() = capture("08_typhoon") {
        TyphoonScreen(sampleTyphoons(), location, onClose = {})
    }

    private fun sampleTyphoons() = TyphoonParser.parse(
        requireNotNull(javaClass.classLoader?.getResource("cwa/W-C0034-005.json")).readText(),
    )

    @Test
    fun weatherPageThunder() = capture("02_weather_page_thunder") {
        CityWeatherPage(taipei, CityWeatherUi(weather = sampleWeather(code = 95, alerts = true)), onRefresh = {})
    }

    @Test
    fun cityList() = capture("03_city_list") {
        CityListScreen(
            cities = listOf(location, City("addr_1", "內湖區", "臺北市內湖區瑞光路100號", 25.08, 121.57, label = "住家", address = "臺北市內湖區瑞光路100號"), taipei, City("geo_2", "東京", "日本", 35.68, 139.69)),
            weather = mapOf(
                location.id to CityWeatherUi(sampleWeather(code = 2)),
                "addr_1" to CityWeatherUi(sampleWeather(code = 3)),
                taipei.id to CityWeatherUi(sampleWeather(code = 61)),
                "geo_2" to CityWeatherUi(sampleWeather(code = 0, isDay = false)),
            ),
            query = "",
            results = emptyList(),
            searching = false,
            onQueryChange = {},
            onAdd = {},
            onSelect = {},
            onRemove = {},
            onMove = { _, _ -> },
            onOpenSettings = {},
            onClose = {},
        )
    }

    @Test
    fun placeEditor() = capture("09_place_editor") {
        PlaceEditorScreen(
            existing = City("addr_1", "內湖區", "臺北市內湖區瑞光路100號", 25.08, 121.57, label = "住家", address = "臺北市內湖區瑞光路100號"),
            onSearch = { emptyList() },
            onUseCurrentLocation = { null },
            onSave = { _, _ -> },
            onClose = {},
        )
    }

    @Test
    fun searchSingleLetter() = capture("07_search_t") {
        val taiwan = PlaceSearch.parse(File("src/main/assets/taiwan_places.json").readText())
        val world = PlaceSearch.parseWorld(File("src/main/assets/world_cities.json").readText())
        CityListScreen(
            cities = emptyList(),
            weather = emptyMap(),
            query = "t",
            results = PlaceSearch.search("t", taiwan).map { it.toCity() } + PlaceSearch.searchWorld("t", world).map { it.toCity() },
            searching = false,
            onQueryChange = {},
            onAdd = {},
            onSelect = {},
            onRemove = {},
            onMove = { _, _ -> },
            onOpenSettings = {},
            onClose = {},
        )
    }

    @Test
    fun detailTemperature() = capture("04_detail_temperature") {
        DetailScreen(location, sampleWeather(code = 2), DetailMetric.TEMPERATURE, null, onClose = {})
    }

    @Test
    fun detailPrecipitation() = capture("05_detail_precipitation") {
        DetailScreen(location, sampleWeather(code = 61), DetailMetric.PRECIPITATION, null, onClose = {})
    }

    @Test
    @Config(qualifiers = "w400dp-h1700dp-xhdpi")
    fun settings() = capture("06_settings") {
        SettingsScreen(primaryCityName = "大安區", onDataSourceChanged = {}, onClose = {})
    }

    private fun sampleWeather(code: Int, isDay: Boolean = true, alerts: Boolean = false, typhoon: Boolean = false): Weather {
        val offset = ZoneOffset.ofHours(8)
        val today = LocalDate.now(offset)
        val now = today.atTime(14, 20)
        val hourly = (0 until 240).map { i ->
            val t = today.atStartOfDay().plusHours(i.toLong())
            val temp = 26.0 + 4 * cos((t.hour - 14) / 24.0 * 2 * PI) - i / 48.0
            val rainy = code >= 51 && i % 24 in 13..18
            HourlyForecast(
                time = t,
                temperature = temp,
                weatherCode = if (rainy) code else if (i % 24 in 6..17) 2 else 1,
                precipitationProbability = if (rainy) 70 else (i * 7) % 30,
                precipitation = if (rainy) 1.2 else 0.0,
                isDay = t.hour in 6..17,
                humidity = 65 + (i % 24),
                apparentTemperature = temp + 1.5,
                windSpeed = 8.0 + (i % 12),
                windGusts = 18.0 + (i % 12),
                uvIndex = max(0.0, 9 * cos((t.hour - 12) / 12.0 * PI)),
            )
        }
        val daily = (0 until 10).map { d ->
            DailyForecast(
                date = today.plusDays(d.toLong()),
                weatherCode = listOf(2, 61, 0, 3, 80, 1, 2, 95, 0, 2)[d],
                temperatureMax = 30.0 - d % 4,
                temperatureMin = 23.0 - d % 3,
                precipitationProbability = listOf(20, 70, 0, 10, 60, 0, 20, 80, 0, 10)[d],
                precipitationSum = 0.0,
                sunrise = today.plusDays(d.toLong()).atTime(5, 43),
                sunset = today.plusDays(d.toLong()).atTime(17, 41),
                uvIndexMax = 8.0,
                description = if (d == 0) "多雲時晴" else null,
            )
        }
        return Weather(
            current = CurrentConditions(
                time = now, temperature = 29.4, apparentTemperature = 32.0, humidity = 68, dewPoint = 22.8,
                isDay = isDay, precipitation = 0.0, weatherCode = code, cloudCover = 45, pressure = 1011.0,
                windSpeed = 12.0, windDirection = 60, windGusts = 25.0, visibility = 24000.0, uvIndex = 7.0,
                description = if (code == 2) "多雲" else null,
            ),
            hourly = hourly,
            daily = daily,
            utcOffsetSeconds = 8 * 3600,
            airQuality = AirQuality(usAqi = 52, pm25 = 14.0, pm10 = 30.0, stationName = "古亭", status = "普通", pollutant = "細懸浮微粒"),
            fetchedAtMillis = System.currentTimeMillis(),
            cwa = CwaSummary(
                stationName = "臺北",
                stationDistanceKm = 2.3,
                observedAt = now.minusMinutes(10),
                township = "大安區",
                county = "臺北市",
                alerts = if (alerts) listOf(CwaAlert("大雷雨", "即時訊息", now, now.plusHours(2))) else emptyList(),
            ),
            typhoons = if (typhoon) sampleTyphoons() else emptyList(),
        )
    }
}
