package com.charlie.weather.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.CwaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** 圖片的地理投影，用來把目前城市標在圖上。 */
private sealed interface MapProjection {
    /** 回傳 (x, y) 在圖片中的比例位置（0..1）；超出範圍回傳 null */
    fun project(latitude: Double, longitude: Double): Offset?

    data class LatLon(val west: Double, val east: Double, val south: Double, val north: Double) : MapProjection {
        override fun project(latitude: Double, longitude: Double): Offset? {
            val x = (longitude - west) / (east - west)
            val y = (north - latitude) / (north - south)
            return if (x in 0.0..1.0 && y in 0.0..1.0) Offset(x.toFloat(), y.toFloat()) else null
        }
    }

    data class Mercator(val west: Double, val east: Double, val south: Double, val north: Double) : MapProjection {
        private fun merc(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        override fun project(latitude: Double, longitude: Double): Offset? {
            val x = (longitude - west) / (east - west)
            val y = (merc(north) - merc(latitude)) / (merc(north) - merc(south))
            return if (x in 0.0..1.0 && y in 0.0..1.0) Offset(x.toFloat(), y.toFloat()) else null
        }
    }
}

private const val CWA_WEB = "https://www.cwa.gov.tw"
private val TaiwanSatellite = MapProjection.Mercator(115.976888855, 126.02300114, 19.100625745, 28.29937425)
/** 雷達圖（較大範圍）原始為 115–126.5°E、17.75–29.25°N，裁切成台灣周邊 117.5–124.5°E、20.5–27.5°N。 */
private val RadarCrop = CropFraction(left = 2.5 / 11.5, top = 1.75 / 11.5, right = 9.5 / 11.5, bottom = 8.75 / 11.5)
private val TaiwanRadar = MapProjection.LatLon(117.5, 124.5, 20.5, 27.5)

/** 以比例表示的裁切範圍（0..1）。 */
private data class CropFraction(val left: Double, val top: Double, val right: Double, val bottom: Double)

private sealed interface MapProduct {
    val title: String
    val note: String
    val projection: MapProjection?

    /** 氣象署網站的時間序列圖（每 10 分鐘一張），可播放動畫。 */
    data class Animated(
        override val title: String,
        override val note: String,
        override val projection: MapProjection?,
        val frameCount: Int,
        val maxDimension: Int,
        val crop: CropFraction?,
        /** 開啟時預設放大倍率（以目前城市為中心） */
        val initialZoom: Float,
        val url: (LocalDateTime) -> String,
        val fallbackS3Id: String,
        val fallbackExtension: String,
    ) : MapProduct

    /** 開放資料上的單張圖。 */
    data class Still(
        override val title: String,
        override val note: String,
        val id: String,
        val extension: String,
    ) : MapProduct {
        override val projection: MapProjection? get() = null
    }
}

private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
private val dashed = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")

private val products = listOf(
    MapProduct.Animated(
        title = "降雨",
        note = "雷達整合回波：顏色越暖代表雨勢越大。",
        projection = TaiwanRadar,
        frameCount = 12,
        maxDimension = 1100,
        crop = RadarCrop,
        initialZoom = 1.8f,
        url = { "$CWA_WEB/Data/radar/CV1_3600_${stamp.format(it)}.png" },
        fallbackS3Id = "O-A0058-001",
        fallbackExtension = "png",
    ),
    MapProduct.Animated(
        title = "衛星・紅外線",
        note = "向日葵衛星紅外線彩色雲圖：白色越亮代表雲頂越高、越可能有強降雨。",
        projection = TaiwanSatellite,
        frameCount = 18,
        maxDimension = 800,
        crop = null,
        initialZoom = 1f,
        url = { "$CWA_WEB/Data/satellite/TWI_IR1_CR_800/TWI_IR1_CR_800-${dashed.format(it)}.jpg" },
        fallbackS3Id = "O-C0042-002",
        fallbackExtension = "jpg",
    ),
    MapProduct.Animated(
        title = "衛星・可見光",
        note = "向日葵衛星可見光雲圖：接近肉眼所見，夜間會是暗的。",
        projection = TaiwanSatellite,
        frameCount = 12,
        maxDimension = 900,
        crop = null,
        initialZoom = 1f,
        url = { "$CWA_WEB/Data/satellite/TWI_VIS_Gray_1350/TWI_VIS_Gray_1350-${dashed.format(it)}.jpg" },
        fallbackS3Id = "O-C0042-008",
        fallbackExtension = "jpg",
    ),
    MapProduct.Still("溫度", "全臺測站溫度分布圖。", "O-A0038-001", "jpg"),
    MapProduct.Still("日雨量", "今日累積雨量分布圖。", "O-A0040-002", "jpg"),
)

private class Frame(val image: ImageBitmap, val time: LocalDateTime?)

/** 只保留目前產品的圖框，避免佔用過多記憶體。 */
private object FrameCache {
    private var key: String? = null
    private var savedAt = 0L
    private var frames: List<Frame> = emptyList()

    fun get(title: String): List<Frame>? = synchronized(this) {
        if (key == title && System.currentTimeMillis() - savedAt < 5 * 60_000) frames else null
    }

    fun put(title: String, value: List<Frame>) = synchronized(this) {
        key = title
        frames = value
        savedAt = System.currentTimeMillis()
    }
}

private fun decode(bytes: ByteArray, maxDimension: Int, crop: CropFraction? = null): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val rect = crop?.let {
        Rect(
            (it.left * bounds.outWidth).toInt(),
            (it.top * bounds.outHeight).toInt(),
            (it.right * bounds.outWidth).toInt(),
            (it.bottom * bounds.outHeight).toInt(),
        )
    } ?: Rect(0, 0, bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (maxOf(rect.width(), rect.height()) / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = if (crop != null) {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
        try {
            decoder?.decodeRegion(rect, options)
        } finally {
            decoder?.recycle()
        }
    } else {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } ?: throw IOException("無法解碼圖片")
    return bitmap.asImageBitmap()
}

/**
 * 依時間往回抓取最近的圖框（最新的先抓），每抓完一批就回報，畫面能先顯示最新一張。
 * 若網站無法連線，退回開放資料的單張最新圖。
 */
private suspend fun loadFrames(product: MapProduct, onProgress: (List<Frame>) -> Unit): List<Frame> = withContext(Dispatchers.IO) {
    when (product) {
        is MapProduct.Still -> {
            val image = decode(httpBytes("${CwaRepository.BASE_URL}/Observation/${product.id}.${product.extension}"), 2400)
            listOf(Frame(image, s3Time(product.id)))
        }
        is MapProduct.Animated -> {
            val now = LocalDateTime.now(CwaRepository.TAIWAN)
            val latest = now.withSecond(0).withNano(0).minusMinutes((now.minute % 10).toLong())
            // 多抓幾個時間點，因為最新一張通常還沒產出
            val times = (0 until product.frameCount + 3).map { latest.minusMinutes(10L * it) }
            val loaded = sortedMapOf<LocalDateTime, Frame>()
            for (batch in times.chunked(4)) {
                val results = batch.map { t ->
                    async {
                        runCatching { t to Frame(decode(httpBytes(product.url(t)), product.maxDimension, product.crop), t) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
                results.forEach { (t, f) -> loaded[t] = f }
                if (loaded.isNotEmpty()) {
                    val partial = loaded.values.toList().takeLast(product.frameCount)
                    withContext(Dispatchers.Main) { onProgress(partial) }
                }
                if (loaded.size >= product.frameCount) break
            }
            if (loaded.isEmpty()) {
                val image = decode(httpBytes("${CwaRepository.BASE_URL}/Observation/${product.fallbackS3Id}.${product.fallbackExtension}"), product.maxDimension, product.crop)
                listOf(Frame(image, s3Time(product.fallbackS3Id)))
            } else {
                loaded.values.toList().takeLast(product.frameCount)
            }
        }
    }
}

private fun s3Time(id: String): LocalDateTime? = runCatching {
    val meta = String(httpBytes("${CwaRepository.BASE_URL}/Observation/$id.json"))
    Regex("\"(?:DateTime|Datetime)\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
        ?.let { OffsetDateTime.parse(it).toLocalDateTime() }
}.getOrNull()

private fun httpBytes(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MyWeather")
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

/** 類似 iOS 天氣的「降雨」地圖：預設播放氣象署雷達回波動畫，可切換衛星雲圖等。 */
@Composable
fun MapScreen(city: City?, onClose: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val product = products[selected]
    var frames by remember { mutableStateOf<List<Frame>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(product, reloadKey) {
        error = null
        val cached = if (reloadKey == 0) FrameCache.get(product.title) else null
        if (cached != null) {
            frames = cached
            index = cached.lastIndex
            return@LaunchedEffect
        }
        frames = emptyList()
        loading = true
        try {
            val result = loadFrames(product) { partial ->
                frames = partial
                index = partial.lastIndex
            }
            frames = result
            index = result.lastIndex
            FrameCache.put(product.title, result)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (frames.isEmpty()) error = "無法載入圖資，請檢查網路連線"
        }
        loading = false
    }

    // 播放動畫：每張 0.45 秒，最新一張停留較久
    LaunchedEffect(frames, playing, loading) {
        if (!playing || loading || frames.size < 2) return@LaunchedEffect
        while (true) {
            delay(if (index == frames.lastIndex) 1500 else 450)
            index = if (index >= frames.lastIndex) 0 else index + 1
        }
    }

    val frame = frames.getOrNull(index.coerceIn(0, (frames.size - 1).coerceAtLeast(0)))

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                val time = frame?.time?.let { "${it.monthValue}/${it.dayOfMonth} ${timeLabel(it)}" }
                Text(
                    listOfNotNull(time, "中央氣象署").joinToString(" · "),
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
            }
            IconButton(onClick = { reloadKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新整理", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(products.size) { i ->
                val active = i == selected
                Text(
                    products[i].title,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color.Black else Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Color.White else Color(0xFF2C2C2E))
                        .clickable {
                            selected = i
                            reloadKey = 0
                            playing = true
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            frame?.let { ZoomableMap(it.image, product, city) }
            if (frames.isEmpty() && error == null) CircularProgressIndicator(color = Color.White)
            error?.let { Text(it, color = Color.White) }
        }

        if (product is MapProduct.Animated && frames.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playing = !playing }) {
                    if (playing) {
                        Text("❚❚", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White)
                    }
                }
                Slider(
                    value = index.toFloat(),
                    onValueChange = {
                        playing = false
                        index = it.roundToInt().coerceIn(0, frames.lastIndex)
                    },
                    valueRange = 0f..frames.lastIndex.toFloat(),
                    steps = (frames.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                if (loading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 12.dp).size(18.dp))
                }
            }
        }

        Text(
            product.note + "  雙指縮放、點兩下放大。",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ZoomableMap(image: ImageBitmap, product: MapProduct, city: City?) {
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        // 圖片以 Fit 方式置中，計算實際顯示範圍
        val imageRatio = image.width.toFloat() / image.height
        val drawW = minOf(boxW, boxH * imageRatio)
        val drawH = drawW / imageRatio
        val left = (boxW - drawW) / 2
        val top = (boxH - drawH) / 2
        val point = city?.let { product.projection?.project(it.latitude, it.longitude) }

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = ((drawW * s - boxW) / 2).coerceAtLeast(0f)
            val maxY = ((drawH * s - boxH) / 2).coerceAtLeast(0f)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        val initialZoom = (product as? MapProduct.Animated)?.initialZoom ?: 1f
        var scale by remember(product.title) { mutableFloatStateOf(initialZoom) }
        var offset by remember(product.title) {
            // 以目前城市為中心放大
            val target = point?.let { Offset(left + it.x * drawW, top + it.y * drawH) } ?: Offset(boxW / 2, boxH / 2)
            mutableStateOf(clamp((Offset(boxW / 2, boxH / 2) - target) * initialZoom, initialZoom))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(product.title) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                        scale = newScale
                        offset = clamp(offset + pan, newScale)
                    }
                }
                .pointerInput(product.title) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 3f
                            val center = Offset(boxW / 2, boxH / 2)
                            scale = target
                            offset = clamp((center - tap) * (target - 1f), target)
                        }
                    })
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            Image(image, contentDescription = product.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (point != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(left + point.x * drawW, top + point.y * drawH)
                    val r = 6.dp.toPx() / scale
                    drawCircle(Color.White, radius = r * 1.5f, center = center)
                    drawCircle(Color(0xFF0A84FF), radius = r, center = center)
                }
            }
        }
    }
}
