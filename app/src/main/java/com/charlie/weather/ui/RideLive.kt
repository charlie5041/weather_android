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
import com.charlie.weather.data.LiveRide
import java.util.Locale

private val LiveGreen = Color(0xFF30D158)
private val LiveBarColor = Color(0xFF0B3B2A)
private val LiveCamera = Color(0xFFFF9F0A)
private val LiveSecondary = Color(0xFFA0A0A8)

private fun liveLabel(live: LiveRide) = if (live.routeName != null) "騎乘中" else "行車提醒"

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
        Box(Modifier.size(8.dp).clip(CircleShape).background(LiveGreen))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (live.routeName != null) "騎乘中 · ${live.camera ?: live.title}" else live.camera ?: live.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                live.routeName ?: live.detail.lineSequence().first(),
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
 * 騎乘中畫面：只放路上需要的資訊（前方天氣、最近的測速照相、時速、里程），
 * 「回報測速」與「結束」做成大按鈕並上下分開，戴手套也不容易按錯。
 */
@Composable
fun RideScreen(
    live: LiveRide,
    /** 可以回報移動式測速時才顯示按鈕 */
    onReportCamera: (() -> Unit)?,
    onStop: () -> Unit,
    onClose: () -> Unit,
    keepScreenOn: Boolean = false,
) {
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
                Text(liveLabel(live), color = LiveGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                live.routeName?.let { Text(it, color = LiveSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
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
                live.recordedKm?.let { Metric("已騎", "%.1f".format(Locale.US, it), "公里", Modifier.weight(1f)) }
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
            onReportCamera?.let { BigButton("📷 回報測速", LiveCamera, Color.Black, it) }
            BigButton("結束", Color(0xFF2C2C2E), Color.White, onStop)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(PanelColor).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = LiveSecondary, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(" $unit", color = LiveSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun BigButton(text: String, background: Color, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 20.dp),
    )
}
