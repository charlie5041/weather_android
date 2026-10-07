package com.charlie.weather.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.charlie.weather.MainActivity
import com.charlie.weather.R
import com.charlie.weather.data.City
import com.charlie.weather.data.Weather
import com.charlie.weather.sync.WeatherSyncWorker
import com.charlie.weather.ui.Units
import com.charlie.weather.ui.WeatherCodes
import com.charlie.weather.ui.deg
import com.charlie.weather.ui.weekdayLabel
import java.time.LocalDate
import java.time.ZoneOffset

class WeatherWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Units.load(context)
        val snapshot = WidgetSnapshotStore.load(context)
        provideContent { WidgetContent(snapshot) }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 260.dp)

        /** 存下最新天氣並重繪所有小工具。 */
        suspend fun update(context: Context, city: City, weather: Weather) {
            WidgetSnapshotStore.save(context, city, weather)
            WeatherWidget().updateAll(context)
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WeatherSyncWorker.runNow(context)
    }
}

private val White = ColorProvider(Color.White)
private val Dim = ColorProvider(Color.White.copy(alpha = 0.75f))
private val Blue = ColorProvider(Color(0xFF8ED1FC))

private fun style(size: TextUnit, bold: Boolean = false, color: ColorProvider = White, align: TextAlign = TextAlign.Start) =
    TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align)

private fun backgroundRes(s: WidgetSnapshot?): Int {
    val code = s?.weatherCode ?: 1
    val day = s?.isDay ?: true
    return when {
        WeatherCodes.isThunder(code) -> R.drawable.widget_bg_thunder
        WeatherCodes.isSnow(code) -> R.drawable.widget_bg_snow
        WeatherCodes.isRain(code) -> if (day) R.drawable.widget_bg_rain_day else R.drawable.widget_bg_rain_night
        code == 45 || code == 48 -> R.drawable.widget_bg_fog
        code == 3 -> if (day) R.drawable.widget_bg_cloudy_day else R.drawable.widget_bg_cloudy_night
        else -> if (day) R.drawable.widget_bg_clear_day else R.drawable.widget_bg_clear_night
    }
}

@Composable
private fun WidgetContent(s: WidgetSnapshot?) {
    val size = LocalSize.current
    Box(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(backgroundRes(s)))
            .cornerRadius(22.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(14.dp),
    ) {
        if (s == null) {
            Text("開啟「出行看天氣」以載入資料", style = style(13.sp))
            return@Box
        }
        val wide = size.width >= 250.dp
        val tall = size.height >= 250.dp
        Column(GlanceModifier.fillMaxSize()) {
            if (wide) WideHeader(s) else SmallHeader(s)
            if (wide) {
                Spacer(GlanceModifier.defaultWeight())
                HoursRow(s)
            }
            if (wide && tall) {
                Spacer(GlanceModifier.height(8.dp))
                Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f))) {}
                Spacer(GlanceModifier.height(4.dp))
                DaysList(s, rows = if (size.height >= 330.dp) 6 else 5)
            }
        }
    }
}

private fun cityTitle(s: WidgetSnapshot) = if (s.isCurrentLocation) "➤ ${s.cityName}" else s.cityName

@Composable
private fun SmallHeader(s: WidgetSnapshot) {
    Column(GlanceModifier.fillMaxSize()) {
        Text(cityTitle(s), style = style(14.sp, bold = true), maxLines = 1)
        Text(s.temperature.deg(), style = style(40.sp))
        Spacer(GlanceModifier.defaultWeight())
        Text(WeatherCodes.emoji(s.weatherCode, s.isDay), style = style(16.sp))
        Text(s.description, style = style(12.sp, bold = true), maxLines = 1)
        Text("最高${s.high.deg()} 最低${s.low.deg()}", style = style(12.sp, bold = true), maxLines = 1)
    }
}

@Composable
private fun WideHeader(s: WidgetSnapshot) {
    Row(GlanceModifier.fillMaxWidth()) {
        Column(GlanceModifier.defaultWeight()) {
            Text(cityTitle(s), style = style(14.sp, bold = true), maxLines = 1)
            Text(s.temperature.deg(), style = style(38.sp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(WeatherCodes.emoji(s.weatherCode, s.isDay), style = style(16.sp, align = TextAlign.End))
            Text(s.description, style = style(12.sp, bold = true, align = TextAlign.End), maxLines = 1)
            Text("最高${s.high.deg()} 最低${s.low.deg()}", style = style(12.sp, bold = true, align = TextAlign.End), maxLines = 1)
        }
    }
}

@Composable
private fun HoursRow(s: WidgetSnapshot) {
    val hours = s.upcomingHours().take(6)
    Row(GlanceModifier.fillMaxWidth()) {
        hours.forEachIndexed { i, h ->
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (i == 0) "現在" else "${h.time.hour}時", style = style(11.sp, color = Dim, align = TextAlign.Center))
                Text(WeatherCodes.emoji(h.weatherCode, h.isDay), style = style(15.sp, align = TextAlign.Center))
                Text(h.temperature.deg(), style = style(13.sp, bold = true, align = TextAlign.Center))
            }
        }
    }
}

@Composable
private fun DaysList(s: WidgetSnapshot, rows: Int) {
    val today = LocalDate.now(ZoneOffset.ofTotalSeconds(s.utcOffsetSeconds))
    Column(GlanceModifier.fillMaxWidth()) {
        s.upcomingDays().take(rows).forEach { d ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (d.date == today) "今天" else weekdayLabel(d.date.dayOfWeek),
                    style = style(13.sp, bold = true),
                    modifier = GlanceModifier.width(44.dp),
                )
                Text(WeatherCodes.emoji(d.weatherCode, true), style = style(14.sp), modifier = GlanceModifier.width(28.dp))
                Text(
                    d.pop?.takeIf { it >= 20 }?.let { "$it%" } ?: "",
                    style = style(11.sp, bold = true, color = Blue),
                    modifier = GlanceModifier.width(40.dp),
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(d.low.deg(), style = style(13.sp, color = Dim, align = TextAlign.End), modifier = GlanceModifier.width(40.dp))
                Text(d.high.deg(), style = style(13.sp, bold = true, align = TextAlign.End), modifier = GlanceModifier.width(40.dp))
            }
        }
    }
}
