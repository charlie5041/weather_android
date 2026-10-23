package com.charlie.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.GpsSignal
import com.charlie.weather.data.LiveRide
import java.util.Locale

private val LiveGreen = Color(0xFF30D158)
private val LiveBarColor = Color(0xFF0B3B2A)
private val LiveCamera = Color(0xFFFF9F0A)
private val LiveSecondary = Color(0xFFA0A0A8)

private val LivePaused = Color(0xFFFFD60A)

private fun liveLabel(live: LiveRide) = when {
    live.paused -> "已暫停"
    live.recordOnly -> "記錄足跡"
    live.routeName != null -> "騎乘中"
    else -> "行車提醒"
}

/** 例：1:05:09、12:30 */
internal fun clockLabel(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }
}

/** 每秒更新的現在時間（記錄中的時間走秒用） */
@Composable
private fun rememberNow(ticking: Boolean, clock: () -> Long): Long {
    val now by produceState(clock(), ticking) {
        while (ticking) {
            value = clock()
            delay(1000)
        }
    }
    return now
}

/**
 * 進行中橫條：騎乘中或行車提醒時固定在分頁列上方，三個分頁都看得到。
 * 點橫條打開騎乘中畫面；右邊直接結束。
 */
@Composable
fun LiveBar(live: LiveRide, onOpen: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LiveBarColor)
            .border(1.dp, LiveGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "打開騎乘中畫面", onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live.paused) LivePaused else LiveGreen))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    live.recordOnly || live.paused -> liveLabel(live) + live.recordedKm?.let { " · %.1f 公里".format(Locale.US, it) }.orEmpty()
                    live.routeName != null -> "騎乘中 · ${live.camera ?: live.title}"
                    else -> live.camera ?: live.title
                },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                live.routeName ?: if (live.recordOnly && !live.paused) "關掉螢幕也會繼續記錄" else live.detail.lineSequence().first(),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "結束",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(role = Role.Button, onClick = onStop)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * 騎乘中畫面：只放路上需要的資訊（前方天氣、最近的測速照相、時速、里程、時間、爬升、GPS 訊號），
 * 「回報測速」「暫停」「結束」做成大按鈕，戴手套也不容易按錯。
 */
@Composable
fun RideScreen(
    live: LiveRide,
    /** 可以回報移動式測速時才顯示按鈕 */
    onReportCamera: (() -> Unit)?,
    onStop: () -> Unit,
    onClose: () -> Unit,
    keepScreenOn: Boolean = false,
    /** 記錄足跡時才有：暫停與繼續 */
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    val now = rememberNow(live.startedAt != null && !live.paused, clock)
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(liveLabel(live), color = if (live.paused) LivePaused else LiveGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                live.routeName?.let { Text(it, color = LiveSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            live.gps?.let { GpsChip(it) }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起", tint = Color.White)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(live.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
            Text(live.detail, color = LiveSecondary, fontSize = 16.sp, lineHeight = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("時速", live.speedKmh?.toString() ?: "–", "公里", Modifier.weight(1f))
                live.recordedKm?.let { Metric("距離", "%.1f".format(Locale.US, it), "公里", Modifier.weight(1f)) }
            }
            if (live.startedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("時間", clockLabel(live.movingMs(now)), "", Modifier.weight(1f))
                    Metric("爬升", live.climbM?.let { "%.0f".format(Locale.US, it) } ?: "–", "公尺", Modifier.weight(1f))
                }
            }
            live.camera?.let { camera ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveCamera.copy(alpha = 0.15f))
                        .border(1.dp, LiveCamera.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Text(camera, color = LiveCamera, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    live.cameraDetail?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White, fontSize = 15.sp) }
                }
            }
        }
        Column(Modifier.padding(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!live.paused) onReportCamera?.let { BigButton("📷 回報測速", LiveCamera, Color.Black, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val toggle = if (live.paused) onResume else onPause
                if (live.startedAt != null && toggle != null) {
                    BigButton(
                        if (live.paused) "▶ 繼續" else "Ⅱ 暫停",
                        if (live.paused) LiveGreen else Color(0xFF2C2C2E),
                        if (live.paused) Color.Black else Color.White,
                        toggle,
                        Modifier.weight(1f),
                    )
                }
                BigButton("結束", Color(0xFF2C2C2E), Color.White, onStop, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(PanelColor).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = LiveSecondary, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            if (unit.isNotEmpty()) Text(" $unit", color = LiveSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** GPS 訊號：綠（良好）、黃（普通）、橘（弱）、灰（搜尋中） */
@Composable
private fun GpsChip(signal: GpsSignal) {
    val color = when (signal) {
        GpsSignal.GOOD -> LiveGreen
        GpsSignal.FAIR -> LivePaused
        GpsSignal.WEAK -> LiveCamera
        GpsSignal.SEARCHING -> LiveSecondary
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(signal.label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BigButton(text: String, background: Color, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 20.dp),
    )
}
