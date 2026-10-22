package com.charlie.weather.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.charlie.weather.data.Journey
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.MapTile
import com.charlie.weather.data.MapViewport
import com.charlie.weather.data.SharedRoute
import com.charlie.weather.data.WebMercator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** 分享卡上可以調整的內容 */
data class ShareCardOptions(val title: String, val showClock: Boolean, val photoIds: List<String>)

/**
 * 足跡分享卡：1080 × 1350（IG 直式比例）的圖片，在手機上畫好，不上傳任何東西。
 * 地圖只畫套用隱私區後的 [SharedRoute]；底圖用 OpenStreetMap 圖磚。
 */
object ShareCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    const val MAX_PHOTOS = 3
    private const val PAD = 56f
    private const val TILE_PX = 512f

    private val background = android.graphics.Color.BLACK
    private val panel = PanelColor.toArgb()
    private val secondary = 0xFFA0A0A8.toInt()
    private val track = 0xFF0A84FF.toInt()
    private val mapPlaceholder = 0xFFE8E6E1.toInt()

    /** 地圖在卡片上的範圍：有照片時地圖較矮 */
    private fun mapRect(withPhotos: Boolean) = RectF(PAD, 220f, WIDTH - PAD, if (withPhotos) 740f else 1060f)

    fun viewport(route: SharedRoute, withPhotos: Boolean): MapViewport? {
        val points = route.segments.flatten().takeIf { it.isNotEmpty() } ?: return null
        val rect = mapRect(withPhotos)
        return WebMercator.fit(points, rect.width(), rect.height(), TILE_PX, padPx = 64f)
    }

    /** 下載（或從快取讀）圖磚、讀照片，畫出分享卡 */
    suspend fun render(
        context: Context,
        journey: Journey,
        route: SharedRoute,
        options: ShareCardOptions,
        photoFiles: PhotoFiles,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Bitmap = coroutineScope {
        val photos = withContext(Dispatchers.IO) {
            options.photoIds.take(MAX_PHOTOS).mapNotNull { id ->
                photoFiles(journey.id, id, false)?.takeIf { it.exists() }?.let { decodeSquare(it, 480) }
            }
        }
        // 地圖大小取決於實際讀得到的照片
        val viewport = viewport(route, photos.isNotEmpty())
        val tiles = viewport?.tiles().orEmpty().map { (tile, _) ->
            async { RouteMapTiles.load(context, tile)?.let { tile to it.asAndroidBitmap() } }
        }
        val loaded = tiles.awaitAll().filterNotNull().toMap()
        withContext(Dispatchers.Default) { draw(journey, route, options, viewport, loaded, photos, zone) }
    }

    /** 照片裁成正方形並縮小（原圖最長邊 1600 px，卡片上只需要約 300 px） */
    private fun decodeSquare(file: File, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val shortEdge = minOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (shortEdge / (sample * 2) >= edge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    /** 畫分享卡（不讀檔、不連網，截圖測試也用這個） */
    fun draw(
        journey: Journey,
        route: SharedRoute,
        options: ShareCardOptions,
        viewport: MapViewport?,
        tiles: Map<MapTile, Bitmap>,
        photos: List<Bitmap>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        val contentWidth = WIDTH - 2 * PAD

        val title = text(60f, android.graphics.Color.WHITE, bold = true)
        val shownTitle = TextUtils.ellipsize(options.title.ifBlank { journey.title }, title, contentWidth, TextUtils.TruncateAt.END)
        canvas.drawText(shownTitle, 0, shownTitle.length, PAD, 120f, title)
        val start = journey.startDateTime(zone)
        val date = "${start.year}/${start.monthValue}/${start.dayOfMonth} ${weekdayLabel(start.dayOfWeek)}" +
            if (options.showClock) " ${clock(start)} 出發" else ""
        canvas.drawText(date, PAD, 178f, text(36f, secondary))

        val withPhotos = photos.isNotEmpty()
        val map = mapRect(withPhotos)
        drawMap(canvas, map, route, viewport, tiles)

        var y = map.bottom + 50f
        val stats = listOf(
            "距離" to "%.1f 公里".format(Locale.US, journey.distanceKm),
            "時間" to durationLabel(journey.durationMinutes),
            "平均時速" to "%.0f 公里".format(Locale.US, journey.averageKmh),
        )
        val column = contentWidth / stats.size
        val label = text(30f, secondary).apply { textAlign = Paint.Align.CENTER }
        val value = text(48f, android.graphics.Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
        stats.forEachIndexed { i, (name, v) ->
            val cx = PAD + column * (i + 0.5f)
            canvas.drawText(name, cx, y + 30f, label)
            canvas.drawText(v, cx, y + 92f, value)
        }
        y += 130f

        weatherLine(journey)?.let {
            val line = TextUtils.ellipsize(it, text(36f, android.graphics.Color.WHITE), contentWidth, TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, PAD, y + 36f, text(36f, android.graphics.Color.WHITE))
            y += 66f
        }

        if (withPhotos) {
            val gap = 16f
            val side = (contentWidth - gap * (MAX_PHOTOS - 1)) / MAX_PHOTOS
            val top = y + 10f
            photos.take(MAX_PHOTOS).forEachIndexed { i, photo ->
                val dst = RectF(PAD + i * (side + gap), top, PAD + i * (side + gap) + side, top + side)
                canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(dst, 24f, 24f, Path.Direction.CW) })
                canvas.drawBitmap(photo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
            }
        }

        canvas.drawText("一路晴", PAD, HEIGHT - 36f, text(32f, Accent.toArgb(), bold = true))
        if (!route.mapHidden) {
            canvas.drawText("地圖 © OpenStreetMap 貢獻者", WIDTH - PAD, HEIGHT - 38f, text(24f, secondary).apply { textAlign = Paint.Align.RIGHT })
        }
        return out
    }

    private fun drawMap(canvas: Canvas, rect: RectF, route: SharedRoute, viewport: MapViewport?, tiles: Map<MapTile, Bitmap>) {
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, 32f, 32f, Path.Direction.CW) })
        if (route.mapHidden || viewport == null) {
            canvas.drawColor(panel)
            val msg = text(34f, secondary).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("這趟大多在隱私區內，不顯示路線", rect.centerX(), rect.centerY() + 12f, msg)
            canvas.restore()
            return
        }
        canvas.drawColor(mapPlaceholder)
        val size = kotlin.math.ceil(viewport.tilePx).toInt() + 1
        viewport.tiles().forEach { (tile, offset) ->
            val bitmap = tiles[tile] ?: return@forEach
            val left = (rect.left + offset.first).roundToInt()
            val top = (rect.top + offset.second).roundToInt()
            canvas.drawBitmap(bitmap, null, Rect(left, top, left + size, top + size), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        fun at(p: LatLon) = viewport.project(p).let { (x, y) -> rect.left + x to rect.top + y }
        val outline = stroke(android.graphics.Color.WHITE, 18f)
        val line = stroke(track, 10f)
        val paths = route.segments.map { seg ->
            Path().apply {
                seg.forEachIndexed { i, p ->
                    val (x, y) = at(p)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        }
        paths.forEach { canvas.drawPath(it, outline) }
        paths.forEach { canvas.drawPath(it, line) }
        canvas.restore()
    }

    /** 例：途中遇到雨約 13 分鐘 · 18–23° */
    internal fun weatherLine(journey: Journey): String? {
        val summary = weatherSummary(journey) ?: return null
        val temps = journey.weather.mapNotNull { it.temperature?.inTemperatureUnit()?.roundToInt() }
        val range = when {
            temps.isEmpty() -> null
            temps.min() == temps.max() -> "${temps.min()}°"
            else -> "${temps.min()}–${temps.max()}°"
        }
        return listOfNotNull(summary, range).joinToString(" · ")
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    /**
     * 用系統分享送出分享卡；[photos] 是要一起附上的照片（足跡裡的副本，加入時已重新壓縮、不含 EXIF）。
     * 檔案放在 cacheDir/share，每次分享前清掉上一次的。
     */
    suspend fun share(context: Context, card: Bitmap, photos: List<File>, fileName: String) {
        val uris = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share")
            dir.deleteRecursively()
            dir.mkdirs()
            val cardFile = File(dir, "$fileName.jpg")
            cardFile.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val files = listOf(cardFile) + photos.mapIndexed { i, f -> File(dir, "${fileName}_照片${i + 1}.jpg").also { f.copyTo(it, overwrite = true) } }
            files.map { FileProvider.getUriForFile(context, "${context.packageName}.share", it) }
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        intent.type = "image/jpeg"
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享足跡"))
    }
}
