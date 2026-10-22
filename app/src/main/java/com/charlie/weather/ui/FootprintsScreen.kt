package com.charlie.weather.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.charlie.weather.data.JourneyEvent
import com.charlie.weather.data.JourneyPhoto
import com.charlie.weather.data.PrivacyZone
import com.charlie.weather.data.SharePrivacy
import com.charlie.weather.data.WeatherSample
import com.charlie.weather.data.Track
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
private val FootRain = Color(0xFF64D2FF)

internal fun Journey.startDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), zone)

internal fun Journey.endDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), zone)

/** 例：1 小時 05 分、35 分鐘 */
internal fun durationLabel(minutes: Long): String =
    if (minutes >= 60) "%d 小時 %02d 分".format(Locale.US, minutes / 60, minutes % 60) else "$minutes 分鐘"

/** 例：12.3 公里 · 35 分鐘 */
internal fun journeyStats(j: Journey): String = "%.1f 公里 · %s".format(Locale.US, j.distanceKm, durationLabel(j.durationMinutes))

/** 照片檔案的位置：(足跡 id, 照片 id, 是否縮圖) → 檔案 */
typealias PhotoFiles = (String, String, Boolean) -> File?

/** 縮圖放在記憶體，捲動列表或開關詳情時不必重讀 */
private val thumbCache = LruCache<String, ImageBitmap>(96)

/** 讀照片檔；縮圖會快取，原圖不快取（一張約 10MB） */
@Composable
private fun rememberPhoto(file: File?, thumbnail: Boolean): ImageBitmap? {
    val path = file?.path
    return produceState(if (thumbnail && path != null) thumbCache.get(path) else null, path) {
        if (value != null || path == null) return@produceState
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            ?.also { if (thumbnail) thumbCache.put(path, it) }
    }.value
}

/** 一趟足跡全部照片的縮圖（地圖上畫照片用） */
@Composable
internal fun rememberThumbs(journey: Journey, photoFiles: PhotoFiles): Map<String, ImageBitmap> {
    val thumbs = remember(journey.id) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(journey.photos) {
        journey.photos.forEach { ph ->
            if (ph.id in thumbs) return@forEach
            val path = photoFiles(journey.id, ph.id, true)?.path ?: return@forEach
            val bitmap = thumbCache.get(path)
                ?: withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }?.also { thumbCache.put(path, it) }
            bitmap?.let { thumbs[ph.id] = it }
        }
    }
    return thumbs
}

/**
 * 「足跡」分頁：每次騎乘或行車提醒自動記下的軌跡，依月份分組，新的在前。
 * 只存在手機裡，不上傳。
 */
@Composable
fun FootprintsScreen(
    journeys: List<Journey>,
    onOpen: (Journey) -> Unit,
    onOpenSettings: () -> Unit,
    photoFiles: PhotoFiles = { _, _, _ -> null },
    /** 騎乘中模式（從出門的路線開始）進行中 */
    rideActive: Boolean = false,
    /** 行車提醒（不設目的地）進行中 */
    driving: Boolean = false,
    canReportCameras: Boolean = false,
    recordJourneys: Boolean = true,
    speedCameras: Boolean = true,
    onStartDrive: () -> Unit = {},
    onStop: () -> Unit = {},
    onReportCamera: () -> Unit = {},
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
        item {
            when {
                driving -> ActiveCard(
                    if (recordJourneys) "行車提醒進行中・記錄足跡" else "行車提醒進行中",
                    onStop = onStop,
                    onReport = onReportCamera.takeIf { canReportCameras },
                )
                rideActive -> ActiveCard(
                    if (recordJourneys) "騎乘中・結束後存成足跡" else "騎乘中",
                    onStop = onStop,
                    onReport = onReportCamera.takeIf { canReportCameras },
                )
                else -> DriveCard(onStartDrive, recordJourneys, speedCameras)
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
                        "按上面的「開始」行車提醒，或在「出門」查路線後開始騎乘，結束後就會自動存一趟足跡。只存在這支手機，不會上傳。",
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
            items(list, key = { it.id }) { j -> JourneyRow(j, zone, photoFiles, onClick = { onOpen(j) }) }
        }
    }
}

@Composable
private fun JourneyRow(journey: Journey, zone: ZoneId, photoFiles: PhotoFiles, onClick: () -> Unit) {
    val start = journey.startDateTime(zone)
    val cover = rememberPhoto(journey.photos.firstOrNull()?.let { photoFiles(journey.id, it.id, true) }, thumbnail = true)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
        } else {
            TrackSilhouette(
                journey.points,
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)).padding(8.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(journey.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${start.monthValue}/${start.dayOfMonth} ${weekdayLabel(start.dayOfWeek)} ${clock(start)}",
                color = FootSecondary,
                fontSize = 14.sp,
            )
            Text(
                journeyStats(journey) + (if (journey.metRain) " · 遇到雨" else "") +
                    if (journey.photos.isNotEmpty()) " · ${journey.photos.size} 張照片" else "",
                color = FootSecondary,
                fontSize = 14.sp,
            )
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

/** 一趟足跡的詳情：地圖與照片、數據、加照片、改名與刪除。 */
@Composable
fun FootprintDetailScreen(
    journey: Journey,
    onRename: (String?) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onAddPhotos: (List<Uri>) -> Unit = {},
    onMovePhoto: (JourneyPhoto, Double) -> Unit = { _, _ -> },
    onDeletePhoto: (JourneyPhoto) -> Unit = {},
    photoFiles: PhotoFiles = { _, _, _ -> null },
    /** 正在處理剛挑的照片 */
    addingPhotos: Boolean = false,
    /** 開啟時直接打開相簿（結束騎乘的摘要按「加照片」） */
    pickOnOpen: Boolean = false,
    onPickHandled: () -> Unit = {},
    googleMap: Boolean = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank(),
    /** 分享時要藏起來的地點（住家、公司…） */
    privacyZones: List<PrivacyZone> = emptyList(),
    privacyRadiusM: Int = SharePrivacy.DEFAULT_RADIUS_M,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var renaming by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) onAddPhotos(uris)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(pickOnOpen) {
        if (pickOnOpen) {
            onPickHandled()
            pick()
        }
    }
    val thumbs = rememberThumbs(journey, photoFiles)
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
            IconButton(onClick = { sharing = true }) {
                Icon(Icons.Filled.Share, contentDescription = "分享", tint = Color.White)
            }
            // 改名稱、刪除不常用，收進選單；刪除不再跟常用動作一樣顯眼
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = PanelColor) {
                    DropdownMenuItem(
                        text = { Text("改名稱", color = Color.White) },
                        onClick = {
                            menu = false
                            renaming = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("刪除", color = FootEnd) },
                        onClick = {
                            menu = false
                            deleting = true
                        },
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }
        TrackMap(
            journey.points,
            googleMap = googleMap,
            modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(14.dp)),
            photos = journey.photos,
            thumbs = thumbs,
            onPhoto = { viewing = it.id },
        )
        PhotoStrip(journey, thumbs, addingPhotos, onAdd = pick, onOpen = { viewing = it.id })
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
            weatherSummary(journey)?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            journey.timeline.forEach { ev ->
                val time = clock(LocalDateTime.ofInstant(Instant.ofEpochMilli(ev.time), zone))
                when (ev) {
                    is JourneyEvent.Start -> TimeRow(RouteDry, time, listOfNotNull("出發" + journey.fromArea?.let { "・$it" }.orEmpty(), ev.weather?.let(::weatherText)).joinToString("　"))
                    is JourneyEvent.End -> TimeRow(FootEnd, time, listOfNotNull("抵達" + journey.toArea?.let { "・$it" }.orEmpty(), ev.weather?.let(::weatherText)).joinToString("　"))
                    is JourneyEvent.RainStarts -> TimeRow(FootRain, time, "開始下雨" + ev.sample.place?.let { "（${it}一帶）" }.orEmpty())
                    is JourneyEvent.RainStops -> TimeRow(FootSecondary, time, "雨停了")
                    is JourneyEvent.Photo -> Row(
                        Modifier.clickable(role = Role.Button) { viewing = ev.photo.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(time, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(56.dp))
                        thumbs[ev.photo.id]?.let {
                            Image(it, contentDescription = "照片", contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("拍照", color = FootSecondary, fontSize = 15.sp)
                    }
                }
            }
        }
        Text(
            "足跡與照片只存在這支手機，不會上傳。照片依拍攝時間放到路線上，沒有拍攝時間的可以手動放。" +
                "按右上角分享時，會先藏起住家、公司等地點附近與起訖點的路線。",
            color = Color.Gray,
            fontSize = 13.sp,
        )
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
    journey.photos.firstOrNull { it.id == viewing }?.let { photo ->
        PhotoViewer(
            journey = journey,
            photo = photo,
            file = photoFiles(journey.id, photo.id, false),
            zone = zone,
            onMove = { onMovePhoto(photo, it) },
            onDelete = {
                viewing = null
                onDeletePhoto(photo)
            },
            onClose = { viewing = null },
        )
    }
    if (sharing) {
        ShareJourneyScreen(journey, privacyZones, privacyRadiusM, photoFiles, onClose = { sharing = false }, zone = zone)
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

private const val MAX_PICK = 30

/** 地圖下方的照片列：依拍攝時間排序，最後是「加照片」 */
@Composable
private fun PhotoStrip(journey: Journey, thumbs: Map<String, ImageBitmap>, adding: Boolean, onAdd: () -> Unit, onOpen: (JourneyPhoto) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(journey.photos, key = { it.id }) { ph ->
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(PanelColor).clickable(role = Role.Button) { onOpen(ph) }) {
                thumbs[ph.id]?.let { Image(it, contentDescription = "照片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (ph.position == null) {
                    Text(
                        "未定位",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(vertical = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        item(key = "add") {
            Column(
                Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(PanelColor).clickable(role = Role.Button, enabled = !adding, onClick = onAdd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (adding) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Text("＋", color = Accent, fontSize = 24.sp)
                    Text("加照片", color = Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 看一張照片：拍攝時間、在路線上的位置（可用滑桿手動調整）、刪除 */
@Composable
private fun PhotoViewer(
    journey: Journey,
    photo: JourneyPhoto,
    file: File?,
    zone: ZoneId,
    onMove: (Double) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val image = rememberPhoto(file, thumbnail = false)
    var fraction by remember(photo.id, photo.position) {
        mutableStateOf(photo.position?.let { Track.fractionOf(journey.points, it) }?.toFloat() ?: 0.5f)
    }
    var adjusting by remember(photo.id) { mutableStateOf(false) }
    var confirmDelete by remember(photo.id) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    photo.takenAt?.let {
                        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone)
                        "${t.monthValue}/${t.dayOfMonth} ${clock(t)} 拍攝"
                    } ?: "沒有拍攝時間",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White) }
            }
            Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(image, contentDescription = "照片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            Text(
                if (photo.position == null) "還沒放到路線上：拍攝時間不在這趟騎乘期間，或照片沒有拍攝時間。" else "已放在路線上。",
                color = FootSecondary,
                fontSize = 14.sp,
            )
            if (adjusting || photo.position == null) {
                TrackSilhouetteWithMarker(journey.points, fraction.toDouble(), Modifier.fillMaxWidth().height(120.dp))
                Slider(
                    value = fraction,
                    onValueChange = {
                        fraction = it
                        adjusting = true
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("起點", color = FootSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("終點", color = FootSecondary, fontSize = 12.sp)
                }
                ActionButton("放在這裡", Accent, Modifier.fillMaxWidth()) {
                    adjusting = false
                    onMove(fraction.toDouble())
                }
            } else {
                ActionButton("調整位置", Color.White, Modifier.fillMaxWidth()) { adjusting = true }
            }
            ActionButton("從足跡移除這張照片", FootEnd, Modifier.fillMaxWidth()) { confirmDelete = true }
            Text("只會刪掉足跡裡的副本，相簿裡的照片不受影響。", color = Color.Gray, fontSize = 13.sp)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PanelColor,
            title = { Text("移除這張照片？", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("移除", color = FootEnd) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Accent) } },
        )
    }
}

/** 軌跡剪影加上 [fraction] 處的標記（手動調整照片位置時預覽） */
@Composable
private fun TrackSilhouetteWithMarker(points: List<TrackPoint>, fraction: Double, modifier: Modifier) {
    val marker = remember(points, fraction) { Track.positionAtFraction(points, fraction) }
    Box(modifier) {
        TrackSilhouette(points, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            if (points.size < 2 || marker == null) return@Canvas
            val kx = cos(Math.toRadians(points.first().latitude))
            val xs = points.map { it.longitude * kx }
            val ys = points.map { -it.latitude }
            val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
            val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
            val scale = minOf(size.width / spanX, size.height / spanY)
            val offX = (size.width - spanX * scale) / 2
            val offY = (size.height - spanY * scale) / 2
            val c = Offset((offX + (marker.longitude * kx - xs.min()) * scale).toFloat(), (offY + (-marker.latitude - ys.min()) * scale).toFloat())
            drawCircle(Color.White, 8.dp.toPx(), c)
            drawCircle(Color(0xFFFF9F0A), 5.5.dp.toPx(), c)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = FootSecondary, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 例：25° 局部多雲 */
private fun weatherText(w: WeatherSample): String? =
    listOfNotNull(w.temperature?.let { "${it.inTemperatureUnit().roundToInt()}°" }, w.weatherCode?.let(WeatherCodes::description))
        .joinToString(" ").ifEmpty { null }

/** 例：途中遇到雨約 13 分鐘；沒有天氣紀錄（行車提醒）時為 null */
internal fun weatherSummary(j: Journey): String? = when {
    j.weather.isEmpty() -> null
    j.metRain -> "途中遇到雨" + j.rainMinutes.takeIf { it > 0 }?.let { "約 $it 分鐘" }.orEmpty()
    else -> "一路沒有下雨"
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
internal fun ActionButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
fun JourneySavedDialog(journey: Journey, onAddPhotos: () -> Unit, onDismiss: () -> Unit) {
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
                weatherSummary(journey)?.let { Text(it, color = FootSecondary, fontSize = 14.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onAddPhotos) { Text("加照片", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍後", color = Accent) } },
    )
}

/** 有 Google 金鑰時用 Google 地圖，否則用 OpenStreetMap 圖磚畫軌跡。 */
@Composable
internal fun TrackMap(
    points: List<TrackPoint>,
    googleMap: Boolean,
    modifier: Modifier = Modifier,
    photos: List<JourneyPhoto> = emptyList(),
    thumbs: Map<String, ImageBitmap> = emptyMap(),
    onPhoto: (JourneyPhoto) -> Unit = {},
) {
    val placed = photos.filter { it.position != null }
    if (googleMap) GoogleTrackMap(points, placed, thumbs, onPhoto, modifier) else TileTrackMap(points, placed, thumbs, onPhoto, modifier)
}

/** 圓形、白框的照片縮圖（Google 地圖的標記用） */
private fun circleBitmap(image: ImageBitmap, sizePx: Int): android.graphics.Bitmap {
    val out = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = sizePx / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r, paint)
    val border = sizePx * 0.08f
    val path = android.graphics.Path().apply { addCircle(r, r, r - border, android.graphics.Path.Direction.CW) }
    canvas.clipPath(path)
    canvas.drawBitmap(
        image.asAndroidBitmap(),
        null,
        android.graphics.RectF(border, border, sizePx - border, sizePx - border),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    return out
}

@Composable
private fun GoogleTrackMap(points: List<TrackPoint>, photos: List<JourneyPhoto>, thumbs: Map<String, ImageBitmap>, onPhoto: (JourneyPhoto) -> Unit, modifier: Modifier) {
    val markerPx = with(LocalDensity.current) { 44.dp.roundToPx() }
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
        photos.forEach { ph ->
            val pos = ph.position ?: return@forEach
            val thumb = thumbs[ph.id] ?: return@forEach
            val latLng = LatLng(pos.latitude, pos.longitude)
            key(ph.id) {
                val icon = remember(thumb) { BitmapDescriptorFactory.fromBitmap(circleBitmap(thumb, markerPx)) }
                Marker(
                    state = rememberMarkerState(key = "photo_${ph.id}_$latLng", position = latLng),
                    icon = icon,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 3f,
                    onClick = {
                        onPhoto(ph)
                        true
                    },
                )
            }
        }
    }
}

@Composable
private fun TileTrackMap(points: List<TrackPoint>, photos: List<JourneyPhoto>, thumbs: Map<String, ImageBitmap>, onPhoto: (JourneyPhoto) -> Unit, modifier: Modifier) {
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
        val photoRadius = 20 * density
        Canvas(
            Modifier.fillMaxSize().pointerInput(viewport, photos) {
                detectTapGestures { tap ->
                    photos
                        .mapNotNull { ph -> ph.position?.let { viewport.project(it) }?.let { (x, y) -> ph to (Offset(x, y) - tap).getDistance() } }
                        .filter { it.second <= photoRadius * 1.3f }
                        .minByOrNull { it.second }
                        ?.let { onPhoto(it.first) }
                }
            },
        ) {
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
            photos.forEach { ph ->
                val thumb = thumbs[ph.id] ?: return@forEach
                val (x, y) = viewport.project(ph.position ?: return@forEach)
                val c = Offset(x, y)
                drawCircle(Color.White, photoRadius, c)
                val inner = photoRadius - 2.dp.toPx()
                clipPath(Path().apply { addOval(Rect(c, inner)) }) {
                    drawImage(
                        thumb,
                        dstOffset = IntOffset((x - inner).roundToInt(), (y - inner).roundToInt()),
                        dstSize = IntSize((inner * 2).roundToInt(), (inner * 2).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
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
