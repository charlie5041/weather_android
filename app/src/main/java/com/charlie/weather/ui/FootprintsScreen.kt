package com.charlie.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.BuildConfig
import com.charlie.weather.data.Journey
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.MapTile
import com.charlie.weather.data.TrackPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

private val FootSecondary = Color(0xFFA0A0A8)
private val FootTrack = Color(0xFF0A84FF)
private val FootEnd = Color(0xFFFF453A)
private val FootMapPlaceholder = Color(0xFFE8E6E1)

internal fun Journey.startDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), zone)

internal fun Journey.endDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), zone)

/** 例：1 小時 05 分、35 分鐘 */
internal fun durationLabel(minutes: Long): String =
    if (minutes >= 60) "%d 小時 %02d 分".format(Locale.US, minutes / 60, minutes % 60) else "$minutes 分鐘"

/** 例：12.3 公里 · 35 分鐘 */
internal fun journeyStats(j: Journey): String = "%.1f 公里 · %s".format(Locale.US, j.distanceKm, durationLabel(j.durationMinutes))

/**
 * 「足跡」分頁：每次騎乘或行車提醒自動記下的軌跡，依月份分組，新的在前。
 * 只存在手機裡，不上傳。
 */
@Composable
fun FootprintsScreen(
    journeys: List<Journey>,
    onOpen: (Journey) -> Unit,
    onOpenSettings: () -> Unit,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val groups = remember(journeys, zone) { journeys.groupBy { YearMonth.from(it.startDateTime(zone)) } }
    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("足跡", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "設定", tint = Color.White)
                }
            }
        }
        if (journeys.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PanelColor).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("還沒有足跡", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "在「出門」分頁開始騎乘或行車提醒，結束後就會自動存一趟足跡。只存在這支手機，不會上傳。",
                        color = FootSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
            return@LazyColumn
        }
        val thisMonth = groups[YearMonth.from(today)].orEmpty()
        item {
            Text(
                "本月 %d 趟 · %.1f 公里".format(Locale.US, thisMonth.size, thisMonth.sumOf { it.distanceKm }),
                color = FootSecondary,
                fontSize = 15.sp,
            )
        }
        groups.forEach { (month, list) ->
            item(key = "m$month") {
                Text(
                    if (month.year == today.year) "${month.monthValue} 月" else "${month.year} 年 ${month.monthValue} 月",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(list, key = { it.id }) { j -> JourneyRow(j, zone, onClick = { onOpen(j) }) }
        }
    }
}

@Composable
private fun JourneyRow(journey: Journey, zone: ZoneId, onClick: () -> Unit) {
    val start = journey.startDateTime(zone)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackSilhouette(
            journey.points,
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)).padding(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(journey.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${start.monthValue}/${start.dayOfMonth} ${weekdayLabel(start.dayOfWeek)} ${clock(start)}",
                color = FootSecondary,
                fontSize = 14.sp,
            )
            Text(journeyStats(journey), color = FootSecondary, fontSize = 14.sp)
        }
    }
}

/** 沒有地圖的軌跡剪影（列表縮圖用），保持經緯度比例 */
@Composable
internal fun TrackSilhouette(points: List<TrackPoint>, modifier: Modifier = Modifier, color: Color = FootTrack) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val kx = cos(Math.toRadians(points.first().latitude))
        val xs = points.map { it.longitude * kx }
        val ys = points.map { -it.latitude }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
        val scale = minOf(size.width / spanX, size.height / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun at(i: Int) = Offset((offX + (xs[i] - xs.min()) * scale).toFloat(), (offY + (ys[i] - ys.min()) * scale).toFloat())
        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(RouteDry, 3.dp.toPx(), at(0))
        drawCircle(FootEnd, 3.dp.toPx(), at(points.lastIndex))
    }
}

/** 一趟足跡的詳情：地圖、數據、改名與刪除。 */
@Composable
fun FootprintDetailScreen(
    journey: Journey,
    onRename: (String?) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    googleMap: Boolean = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val start = journey.startDateTime(zone)
    val end = journey.endDateTime(zone)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(journey.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${start.year}/${start.monthValue}/${start.dayOfMonth} ${weekdayLabel(start.dayOfWeek)}",
                    color = FootSecondary,
                    fontSize = 15.sp,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }
        TrackMap(
            journey.points,
            googleMap = googleMap,
            modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(14.dp)),
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelColor).padding(vertical = 14.dp),
        ) {
            Stat("距離", "%.1f 公里".format(Locale.US, journey.distanceKm), Modifier.weight(1f))
            Stat("時間", durationLabel(journey.durationMinutes), Modifier.weight(1f))
            Stat("平均時速", "%.0f 公里".format(Locale.US, journey.averageKmh), Modifier.weight(1f))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelColor).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimeRow(RouteDry, clock(start), "出發" + journey.fromArea?.let { "・$it" }.orEmpty())
            TimeRow(FootEnd, clock(end), "抵達" + journey.toArea?.let { "・$it" }.orEmpty())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("改名稱", Color.White, Modifier.weight(1f)) { renaming = true }
            ActionButton("刪除", FootEnd, Modifier.weight(1f)) { deleting = true }
        }
        Text("足跡只存在這支手機，不會上傳。", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
    if (renaming) {
        var text by remember { mutableStateOf(journey.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = PanelColor,
            title = { Text("改名稱", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    placeholder = { Text(journey.copy(name = null).title) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    onRename(text)
                }) { Text("確定", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消", color = Accent) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = PanelColor,
            title = { Text("刪除這趟足跡？", color = Color.White) },
            text = { Text("刪除後無法復原。", color = FootSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("刪除", color = FootEnd) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消", color = Accent) } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = FootSecondary, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TimeRow(color: Color, time: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(time, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(56.dp))
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun ActionButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** 結束騎乘後的摘要：可以直接查看這趟足跡 */
@Composable
fun JourneySavedDialog(journey: Journey, onView: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("足跡已儲存", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackSilhouette(journey.points, Modifier.fillMaxWidth().height(120.dp))
                Text(journey.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    journeyStats(journey) + " · 平均 %.0f 公里/小時".format(Locale.US, journey.averageKmh),
                    color = FootSecondary,
                    fontSize = 14.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onView) { Text("查看", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍後", color = Accent) } },
    )
}

/** 有 Google 金鑰時用 Google 地圖，否則用 OpenStreetMap 圖磚畫軌跡。 */
@Composable
internal fun TrackMap(points: List<TrackPoint>, googleMap: Boolean, modifier: Modifier = Modifier) {
    if (googleMap) GoogleTrackMap(points, modifier) else TileTrackMap(points, modifier)
}

@Composable
private fun GoogleTrackMap(points: List<TrackPoint>, modifier: Modifier) {
    val path = remember(points) { points.map { LatLng(it.latitude, it.longitude) } }
    val cameraState = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    val padding = with(LocalDensity.current) { 36.dp.roundToPx() }
    LaunchedEffect(loaded, path) {
        if (!loaded || path.isEmpty()) return@LaunchedEffect
        val bounds = LatLngBounds.builder().apply { path.forEach { include(it) } }.build()
        cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false),
        onMapLoaded = { loaded = true },
    ) {
        if (path.size >= 2) {
            Polyline(points = path, color = Color.White, width = 20f, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap())
            Polyline(points = path, color = FootTrack, width = 12f, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap())
            Circle(center = path.first(), radius = 25.0, fillColor = RouteDry, strokeColor = Color.White, strokeWidth = 4f)
            Circle(center = path.last(), radius = 25.0, fillColor = FootEnd, strokeColor = Color.White, strokeWidth = 4f)
        }
    }
}

@Composable
private fun TileTrackMap(points: List<TrackPoint>, modifier: Modifier) {
    val context = LocalContext.current
    val positions = remember(points) { points.map { LatLon(it.latitude, it.longitude) } }
    BoxWithConstraints(modifier.background(FootMapPlaceholder)) {
        val density = LocalDensity.current.density
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (positions.isEmpty() || widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints
        val viewport = remember(positions, widthPx, heightPx, density) { routeViewport(positions, widthPx, heightPx, density) }
        val tiles = remember(viewport) { viewport.tiles() }
        val images = remember(viewport) {
            mutableStateMapOf<MapTile, ImageBitmap>().apply {
                tiles.forEach { (tile, _) -> RouteMapTiles.cached(tile)?.let { put(tile, it) } }
            }
        }
        LaunchedEffect(viewport) {
            tiles.filter { (tile, _) -> tile !in images }.forEach { (tile, _) ->
                launch { RouteMapTiles.load(context, tile)?.let { images[tile] = it } }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val tileSize = ceil(viewport.tilePx).toInt() + 1
            tiles.forEach { (tile, offset) ->
                val image = images[tile] ?: return@forEach
                drawImage(
                    image,
                    dstOffset = IntOffset(offset.first.roundToInt(), offset.second.roundToInt()),
                    dstSize = IntSize(tileSize, tileSize),
                    filterQuality = FilterQuality.Medium,
                )
            }
            val pts = positions.map { viewport.project(it).let { (x, y) -> Offset(x, y) } }
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                }
                drawPath(path, Color.White, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, FootTrack, style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(Color.White, 8.dp.toPx(), pts.first())
            drawCircle(RouteDry, 5.5.dp.toPx(), pts.first())
            drawCircle(Color.White, 8.dp.toPx(), pts.last())
            drawCircle(FootEnd, 5.5.dp.toPx(), pts.last())
        }
        Text(
            "© OpenStreetMap 貢獻者",
            color = Color(0xFF555555),
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.White.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
