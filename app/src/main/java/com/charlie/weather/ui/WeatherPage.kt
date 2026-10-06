package com.charlie.weather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.CommuteTrip
import com.charlie.weather.data.Weather
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val TextShadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 2f), 8f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityWeatherPage(
    city: City,
    ui: CityWeatherUi,
    onRefresh: () -> Unit,
    onOpenDetail: (DetailMetric, LocalDate?) -> Unit = { _, _ -> },
    onOpenTyphoon: () -> Unit = {},
    commute: CommuteTrip? = null,
) {
    val w = ui.weather
    val code = w?.current?.weatherCode ?: 1
    val isDay = w?.current?.isDay ?: true
    Box(Modifier.fillMaxSize()) {
        WeatherBackground(code, isDay)
        if (w == null) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(city.displayName, fontSize = 34.sp, color = Color.White, style = TextShadow)
                Spacer(Modifier.height(48.dp))
                if (ui.error != null && !ui.loading) {
                    Text(ui.error, color = Color.White, fontSize = 15.sp)
                    TextButton(onClick = onRefresh) { Text("重試", color = Color.White) }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            return@Box
        }

        val listState = rememberLazyListState()
        val headerCollapseThreshold = with(LocalDensity.current) { 220.dp.roundToPx() }
        val collapsed by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > headerCollapseThreshold }
        }

        PullToRefreshBox(isRefreshing = ui.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(city, w) }
                w.cwa?.alerts?.takeIf { it.isNotEmpty() }?.let { alerts ->
                    item { AlertsCard(alerts, Modifier.fillMaxWidth()) }
                }
                commute?.let { trip -> item { CommuteCard(trip, Modifier.fillMaxWidth()) } }
                item { HourlyCard(w, Modifier.fillMaxWidth()) { onOpenDetail(DetailMetric.TEMPERATURE, null) } }
                item { DailyCard(w, Modifier.fillMaxWidth()) { date -> onOpenDetail(DetailMetric.TEMPERATURE, date) } }
                w.typhoons.forEach { typhoon ->
                    item { TyphoonCard(typhoon, city, Modifier.fillMaxWidth(), onClick = onOpenTyphoon) }
                }
                w.airQuality?.let { aq -> item { AirQualityCard(aq, Modifier.fillMaxWidth()) } }
                item {
                    CardRow(
                        { UvCard(w, it) { onOpenDetail(DetailMetric.UV, null) } },
                        { SunCard(w, it) },
                    )
                }
                item { WindCard(w, Modifier.fillMaxWidth()) { onOpenDetail(DetailMetric.WIND, null) } }
                item {
                    CardRow(
                        { PrecipitationCard(w, it) { onOpenDetail(DetailMetric.PRECIPITATION, null) } },
                        { FeelsLikeCard(w, it) { onOpenDetail(DetailMetric.FEELS_LIKE, null) } },
                    )
                }
                item {
                    CardRow(
                        { HumidityCard(w, it) { onOpenDetail(DetailMetric.HUMIDITY, null) } },
                        { VisibilityCard(w, it) },
                    )
                }
                item {
                    CardRow(
                        { PressureCard(w, it) },
                        { CloudCoverCard(w, it) },
                    )
                }
                item { Footer(w, ui.error) }
                item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            }
        }

        AnimatedVisibility(
            visible = collapsed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val top = backgroundColors(code, isDay).first()
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(top, top.copy(alpha = 0.92f), top.copy(alpha = 0f))))
                    .statusBarsPadding()
                    .padding(top = 6.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(city.displayName, fontSize = 26.sp, color = Color.White, style = TextShadow)
                Text(
                    "${w.current.temperature.deg()} | ${w.current.conditionText()}",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    style = TextShadow,
                )
            }
        }
    }
}

@Composable
private fun CardRow(
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        left(Modifier.weight(1f).fillMaxHeight())
        right(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Header(city: City, w: Weather) {
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 小標：「我的位置」或使用者自訂的地點名稱（住家、公司…），下方大字是行政區
        val tag = city.label ?: if (city.isCurrentLocation) "我的位置" else null
        if (tag != null) {
            Text(tag, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, style = TextShadow)
        }
        Text(city.name, fontSize = 34.sp, color = Color.White, style = TextShadow)
        Text(
            w.current.temperature.deg(),
            fontSize = 100.sp,
            fontWeight = FontWeight.Thin,
            color = Color.White,
            lineHeight = 104.sp,
            modifier = Modifier.padding(start = 24.dp),
            style = TextShadow,
        )
        Text(w.current.conditionText(), fontSize = 20.sp, color = Color.White.copy(alpha = 0.9f), style = TextShadow)
        w.today?.let { today ->
            Row {
                Text("最高 ${today.temperatureMax.deg()}", fontSize = 20.sp, color = Color.White, style = TextShadow)
                Spacer(Modifier.width(12.dp))
                Text("最低 ${today.temperatureMin.deg()}", fontSize = 20.sp, color = Color.White, style = TextShadow)
            }
        }
    }
}

@Composable
private fun Footer(w: Weather, error: String?) {
    val updated = Instant.ofEpochMilli(w.fetchedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val lines = buildList {
        if (error != null) add("離線中 · 顯示先前的資料")
        add(if (w.cwa != null) "中央氣象署 · Open-Meteo" else "Open-Meteo")
        add("更新於 ${timeLabel(updated)}")
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEach {
            Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center)
        }
    }
}
