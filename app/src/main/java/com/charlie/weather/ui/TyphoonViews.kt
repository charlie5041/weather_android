package com.charlie.weather.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.CwaParser
import com.charlie.weather.data.Typhoon
import com.charlie.weather.data.TyphoonFix
import org.json.JSONArray
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/** 颱風路徑圖用的東亞陸地輪廓（Natural Earth 1:50m，由 tools/gen_east_asia_land.py 產生）。 */
object LandShapes {
    @Volatile
    private var cache: List<FloatArray>? = null

    fun load(context: Context): List<FloatArray> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val arr = JSONArray(context.assets.open("east_asia_land.json").bufferedReader().use { it.readText() })
            (0 until arr.length()).map { i ->
                val p = arr.getJSONArray(i)
                FloatArray(p.length()) { p.getDouble(it).toFloat() }
            }
        }.getOrDefault(emptyList()).also { cache = it }
    }
}

private val Ocean = Color(0xFF14233A)
private val Land = Color(0xFF3B4A45)
private val TrackPast = Color.White
private val TrackForecast = Color(0xFFFFD60A)
private val Gale = Color(0xFFFF9F0A)
private val Storm = Color(0xFFFF453A)

private fun distanceText(city: City, t: Typhoon): String {
    val km = CwaParser.distanceKm(city.latitude, city.longitude, t.current.latitude, t.current.longitude)
    return "距離${city.name}約 ${"%,d".format(km.roundToInt())} 公里"
}

/** 主畫面上的颱風卡片：名稱、強度、距離、移動方向與縮圖。 */
@Composable
fun TyphoonCard(typhoon: Typhoon, city: City, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassCard("颱風", modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(typhoon.displayName, fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.padding(start = 8.dp))
            Text(typhoon.nameEn, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(typhoon.category, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(distanceText(city, typhoon), typhoon.movement).joinToString("，") + "。",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.9f),
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(10.dp))
        TyphoonMap(
            typhoons = listOf(typhoon),
            city = city,
            modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}

/**
 * 颱風路徑圖：陸地輪廓、過去路徑（白）、預報路徑（黃虛線）與 70% 機率圈、
 * 目前的七級／十級暴風圈，以及城市位置（藍點）。
 */
@Composable
fun TyphoonMap(typhoons: List<Typhoon>, city: City?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val land = remember { LandShapes.load(context) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.background(Ocean)) {
        // 範圍：所有路徑點與城市，四周留 3 度
        val lats = typhoons.flatMap { t -> (t.past + t.forecast).map { it.latitude } } + listOfNotNull(city?.latitude)
        val lons = typhoons.flatMap { t -> (t.past + t.forecast).map { it.longitude } } + listOfNotNull(city?.longitude)
        if (lats.isEmpty()) return@Canvas
        var south = lats.min() - 3
        var north = lats.max() + 3
        var west = lons.min() - 3
        var east = lons.max() + 3
        val cosLat = cos(Math.toRadians((south + north) / 2)).toFloat()
        // 依畫布比例補齊範圍，讓地圖不變形
        val scale = minOf(size.width / ((east - west).toFloat() * cosLat), size.height / (north - south).toFloat())
        val spanLon = size.width / (scale * cosLat)
        val spanLat = size.height / scale
        val cLon = (west + east) / 2
        val cLat = (south + north) / 2
        west = cLon - spanLon / 2
        east = cLon + spanLon / 2
        north = cLat + spanLat / 2
        south = cLat - spanLat / 2
        fun p(lat: Double, lon: Double) = Offset(((lon - west) * scale * cosLat).toFloat(), ((north - lat) * scale).toFloat())
        fun km(radius: Double) = (radius / 111.32 * scale).toFloat()

        // 經緯線
        val grid = Color.White.copy(alpha = 0.08f)
        var g = (Math.floor(west / 5) * 5)
        while (g <= east) {
            drawLine(grid, p(north, g), p(south, g), strokeWidth = 1f)
            g += 5
        }
        g = Math.floor(south / 5) * 5
        while (g <= north) {
            drawLine(grid, p(g, west), p(g, east), strokeWidth = 1f)
            g += 5
        }

        land.forEach { poly ->
            val path = Path()
            for (i in poly.indices step 2) {
                val o = p(poly[i + 1].toDouble(), poly[i].toDouble())
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(path, Land)
        }

        typhoons.forEach { t -> drawTyphoon(t, ::p, ::km) }

        city?.let {
            val c = p(it.latitude, it.longitude)
            drawCircle(Color.White, radius = 6.dp.toPx(), center = c)
            drawCircle(Color(0xFF0A84FF), radius = 4.5.dp.toPx(), center = c)
            val label = measurer.measure(it.name, TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
            drawText(label, topLeft = Offset(c.x + 8.dp.toPx(), c.y - label.size.height / 2f))
        }
    }
}

private fun DrawScope.drawTyphoon(t: Typhoon, p: (Double, Double) -> Offset, km: (Double) -> Float) {
    // 預報 70% 機率圈
    t.forecast.forEach { f ->
        f.probabilityRadius?.let { r ->
            drawCircle(TrackForecast.copy(alpha = 0.10f), radius = km(r), center = p(f.latitude, f.longitude))
            drawCircle(TrackForecast.copy(alpha = 0.35f), radius = km(r), center = p(f.latitude, f.longitude), style = Stroke(1.dp.toPx()))
        }
    }
    val now = t.current
    val center = p(now.latitude, now.longitude)
    // 目前暴風圈
    now.radius15?.let { drawCircle(Gale.copy(alpha = 0.22f), radius = km(it), center = center) }
    now.radius25?.let { drawCircle(Storm.copy(alpha = 0.35f), radius = km(it), center = center) }

    val past = Path().apply {
        t.past.forEachIndexed { i, f -> p(f.latitude, f.longitude).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
    }
    drawPath(past, TrackPast, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    t.past.forEach { drawCircle(TrackPast, radius = 2.dp.toPx(), center = p(it.latitude, it.longitude)) }

    if (t.forecast.isNotEmpty()) {
        val future = Path().apply {
            moveTo(center.x, center.y)
            t.forecast.forEach { f -> p(f.latitude, f.longitude).let { lineTo(it.x, it.y) } }
        }
        drawPath(future, TrackForecast, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
        t.forecast.forEach { drawCircle(TrackForecast, radius = 2.5.dp.toPx(), center = p(it.latitude, it.longitude)) }
    }
    drawCircle(Color.White, radius = 7.dp.toPx(), center = center)
    drawCircle(Storm, radius = 5.dp.toPx(), center = center)
}

/** 颱風詳細頁：可縮放的路徑圖、目前強度與各時段預報。 */
@Composable
fun TyphoonScreen(typhoons: List<Typhoon>, city: City, onClose: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val typhoon = typhoons.getOrNull(selected) ?: typhoons.first()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E10)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${typhoon.displayName}  ${typhoon.nameEn}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "${typhoon.category} · 中央氣象署 ${timeLabelWithDate(typhoon.current)}",
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }
        if (typhoons.size > 1) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                typhoons.forEachIndexed { i, t ->
                    val active = i == selected
                    Text(
                        t.displayName,
                        color = if (active) Color.Black else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) Color.White else Color(0xFF2C2C2E))
                            .clickable { selected = i }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1.2f) 1f else 2.5f
                        if (scale == 1f) offset = Offset.Zero
                    })
                },
        ) {
            TyphoonMap(
                typhoons = typhoons,
                city = city,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
        Text(
            "白線：過去路徑　黃色虛線：預報路徑（圓圈為 70% 機率範圍）　橘／紅：七級／十級暴風圈",
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val now = typhoon.current
            InfoSection {
                InfoRow("目前位置", "北緯 ${now.latitude}°、東經 ${now.longitude}°")
                InfoRow("距離", distanceText(city, typhoon).removePrefix("距離"))
                now.maxWind?.let { InfoRow("近中心最大風速", "${it.roundToInt()} m/s（${windText(it * 3.6)}）") }
                now.maxGust?.let { InfoRow("瞬間最大陣風", "${it.roundToInt()} m/s（${windText(it * 3.6)}）") }
                now.pressure?.let { InfoRow("中心氣壓", "$it hPa") }
                now.radius15?.let { InfoRow("七級風暴風半徑", "${it.roundToInt()} 公里") }
                now.radius25?.let { InfoRow("十級風暴風半徑", "${it.roundToInt()} 公里") }
                typhoon.movement?.let { InfoRow("移動", it) }
            }
            if (typhoon.forecast.isNotEmpty()) {
                Text("路徑預報", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                InfoSection {
                    typhoon.forecast.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        ForecastRow(f)
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private fun timeLabelWithDate(f: TyphoonFix) = "${f.time.monthValue}/${f.time.dayOfMonth} ${timeLabel(f.time)}"

@Composable
private fun InfoSection(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1E))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { content() }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun ForecastRow(f: TyphoonFix) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(timeLabelWithDate(f), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(0.42f))
        Column(Modifier.weight(0.58f)) {
            Text("${f.latitude}°N, ${f.longitude}°E", color = Color.White, fontSize = 14.sp)
            Text(
                listOfNotNull(
                    f.maxWind?.let { "風速 ${it.roundToInt()} m/s" },
                    f.pressure?.let { "$it hPa" },
                    f.probabilityRadius?.let { "70% 半徑 ${max(0, it.roundToInt())} 公里" },
                ).joinToString(" · "),
                color = Color.Gray,
                fontSize = 12.sp,
            )
        }
    }
}
