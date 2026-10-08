package com.charlie.weather.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.charlie.weather.data.City
import com.charlie.weather.data.FavoriteRoute
import com.charlie.weather.data.RouteForecast
import com.charlie.weather.data.RoutePlanner
import com.charlie.weather.data.Weather
import com.charlie.weather.data.WeatherRepository
import com.charlie.weather.ui.Units
import com.charlie.weather.ui.clock
import com.charlie.weather.ui.conditionText
import com.charlie.weather.ui.deg
import com.charlie.weather.ui.hazardLine
import com.charlie.weather.ui.modeEmoji
import com.charlie.weather.ui.reminderLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** 車機首頁：目前位置的天氣與常用路線（點了查沿路天氣）。 */
class WeatherCarScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = WeatherRepository.get(carContext)
    private var city: City? = null
    private var weather: Weather? = null
    private var loading = true

    init {
        Units.load(carContext)
        lifecycleScope.launch {
            city = repo.primaryCity()
            weather = city?.let { repo.cached(it) }
            loading = weather == null
            invalidate()
            val fresh = city?.let { c ->
                try {
                    repo.fetch(c)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            if (fresh != null) weather = fresh
            loading = false
            invalidate()
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder().setTitle("出行看天氣").setHeaderAction(Action.APP_ICON)
        if (loading) return builder.setLoading(true).build()

        val now = ItemList.Builder()
        val w = weather
        val c = city
        if (w != null && c != null) {
            val today = w.today
            now.addItem(
                Row.Builder()
                    .setTitle("${c.displayName} ${w.current.temperature.deg()} ${w.current.conditionText()}")
                    .apply {
                        today?.let { d ->
                            addText("${d.temperatureMin.deg()}～${d.temperatureMax.deg()}" + (d.precipitationProbability?.let { " · 降雨機率 $it%" } ?: ""))
                        }
                        w.cwa?.alerts?.takeIf { it.isNotEmpty() }?.let { alerts -> addText("⚠️ " + alerts.joinToString("、") { it.title }) }
                    }
                    .build(),
            )
        } else {
            now.addItem(Row.Builder().setTitle("無法取得天氣").addText("請在手機上開啟 App 並允許定位").build())
        }
        builder.addSectionedList(SectionedItemList.create(now.build(), "目前天氣"))

        val favorites = repo.store.loadFavoriteRoutes().take(MAX_ROUTES)
        val routes = ItemList.Builder()
        if (favorites.isEmpty()) {
            routes.addItem(Row.Builder().setTitle("還沒有常用路線").addText("在手機的沿路天氣按「☆ 常用」加入").build())
        } else {
            favorites.forEach { route ->
                routes.addItem(
                    Row.Builder()
                        .setTitle("${modeEmoji(route.mode)} ${route.name}")
                        .addText(route.reminder?.let { reminderLabel(it) } ?: "查看現在出發的沿路天氣")
                        .setBrowsable(true)
                        .setOnClickListener { screenManager.push(RouteCarScreen(carContext, route)) }
                        .build(),
                )
            }
        }
        builder.addSectionedList(SectionedItemList.create(routes.build(), "常用路線"))
        return builder.build()
    }

    private companion object {
        /** 車機清單的列數有上限，保守一點 */
        const val MAX_ROUTES = 5
    }
}

/** 一條常用路線現在出發的沿路結論與提醒。 */
class RouteCarScreen(carContext: CarContext, private val route: FavoriteRoute) : Screen(carContext) {
    private val repo = WeatherRepository.get(carContext)
    private var forecast: RouteForecast? = null
    private var failed = false

    init {
        load()
    }

    private fun load() {
        forecast = null
        failed = false
        lifecycleScope.launch {
            val departure = LocalDateTime.now()
            // 起終點是「目前位置」時用最後一次定位
            val location = repo.store.loadLocationCity()
            fun latest(c: City) = if (c.isCurrentLocation) location ?: c else c
            forecast = try {
                val data = repo.routeData(latest(route.from), latest(route.to), route.mode, departure, route.via.map(::latest))
                RoutePlanner.evaluate(data, departure)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                null
            }
            invalidate()
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val f = forecast
        when {
            f != null -> {
                val verdict = f.verdict
                pane.addRow(Row.Builder().setTitle(verdict.title).addText(verdict.detail).build())
                pane.addRow(
                    Row.Builder()
                        .setTitle("${clock(f.departure)} 出發 → ${clock(f.arrival)} 抵達")
                        .addText("%.1f 公里".format(java.util.Locale.US, f.data.path.distanceKm))
                        .build(),
                )
                // 面板最多 4 列
                f.hazards.take(2).forEach { hazard ->
                    val (icon, text) = hazardLine(hazard)
                    pane.addRow(Row.Builder().setTitle("$icon $text").build())
                }
                pane.addAction(Action.Builder().setTitle("重新整理").setOnClickListener { load(); invalidate() }.build())
            }
            failed -> {
                pane.addRow(Row.Builder().setTitle("無法取得沿途天氣").addText("請檢查網路連線").build())
                pane.addAction(Action.Builder().setTitle("重試").setOnClickListener { load(); invalidate() }.build())
            }
            else -> pane.setLoading(true)
        }
        return PaneTemplate.Builder(pane.build()).setTitle(route.name).setHeaderAction(Action.BACK).build()
    }
}
