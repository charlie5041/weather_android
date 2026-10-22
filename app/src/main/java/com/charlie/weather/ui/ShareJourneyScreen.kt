package com.charlie.weather.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.charlie.weather.data.Journey
import com.charlie.weather.data.PrivacyZone
import com.charlie.weather.data.SharePrivacy
import com.charlie.weather.data.SharedRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 分享一趟足跡：預覽的就是實際送出去的圖（已套用隱私區），可以改標題、選照片、決定要不要顯示出發時刻。
 */
@Composable
fun ShareJourneyScreen(
    journey: Journey,
    zones: List<PrivacyZone>,
    radiusM: Int,
    photoFiles: PhotoFiles,
    onClose: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route = remember(journey.id, journey.points, zones) { SharePrivacy.apply(journey.id, journey.points.map { it.position }, zones) }
    val thumbs = rememberThumbs(journey, photoFiles)
    var title by remember(journey.id) { mutableStateOf(journey.title) }
    var showClock by remember(journey.id) { mutableStateOf(false) }
    var photoIds by remember(journey.id) { mutableStateOf(journey.photos.take(ShareCard.MAX_PHOTOS).map { it.id }) }
    var attachPhotos by remember(journey.id) { mutableStateOf(false) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val options = ShareCardOptions(title, showClock, photoIds)
    LaunchedEffect(options, route) {
        // 打字時不要每個字都重畫
        delay(300)
        card = ShareCard.render(context, journey, route, options, photoFiles, zone)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分享足跡", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White) }
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(ShareCard.WIDTH / ShareCard.HEIGHT.toFloat()).clip(RoundedCornerShape(12.dp)).background(PanelColor),
                contentAlignment = Alignment.Center,
            ) {
                val image = card
                if (image != null) {
                    Image(image.asImageBitmap(), contentDescription = "分享卡預覽", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            Text(privacyNote(route, zones.size, radiusM), color = Color(0xFFA0A0A8), fontSize = 14.sp)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(40) },
                singleLine = true,
                label = { Text("標題") },
                modifier = Modifier.fillMaxWidth(),
            )
            ShareToggle("顯示出發時刻", "加上路線，出發時刻會透露作息", showClock) { showClock = it }
            if (journey.photos.isNotEmpty()) {
                Text("放上分享卡的照片（最多 ${ShareCard.MAX_PHOTOS} 張）", color = Color.White, fontSize = 16.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(journey.photos, key = { it.id }) { ph ->
                        val selected = ph.id in photoIds
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PanelColor)
                                .then(if (selected) Modifier.border(3.dp, Accent, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable(role = Role.Checkbox) {
                                    photoIds = when {
                                        selected -> photoIds - ph.id
                                        photoIds.size < ShareCard.MAX_PHOTOS -> photoIds + ph.id
                                        else -> photoIds
                                    }
                                },
                        ) {
                            thumbs[ph.id]?.let {
                                Image(it, contentDescription = "照片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            if (selected) {
                                Text(
                                    "${photoIds.indexOf(ph.id) + 1}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 7.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                if (photoIds.isNotEmpty()) {
                    ShareToggle("一起附上照片", "除了分享卡，也送出選的照片（不含拍攝地點等資訊）", attachPhotos) { attachPhotos = it }
                }
            }
            ActionButton(if (sharing) "準備中…" else "分享", Accent, Modifier.fillMaxWidth()) {
                val image = card ?: return@ActionButton
                if (sharing) return@ActionButton
                sharing = true
                scope.launch {
                    try {
                        val files = if (attachPhotos) photoIds.mapNotNull { photoFiles(journey.id, it, false)?.takeIf { f -> f.exists() } } else emptyList()
                        val start = journey.startDateTime(zone)
                        ShareCard.share(context, image, files, "一路晴足跡_%04d%02d%02d".format(start.year, start.monthValue, start.dayOfMonth))
                    } catch (e: Exception) {
                        Toast.makeText(context, "無法分享：${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        sharing = false
                    }
                }
            }
            Text("分享卡在手機上產生，不會上傳；手機裡的足跡不受影響。", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 例：已藏起 2 個自訂地點周圍 500 公尺內的路線，起點與終點也各多藏了 320、410 公尺。 */
internal fun privacyNote(route: SharedRoute, zoneCount: Int, radiusM: Int): String {
    val trims = "起點與終點各多藏了 ${route.trimStartM}、${route.trimEndM} 公尺"
    val zonesText = if (zoneCount == 0) {
        "還沒有設定住家、公司等地點，只有$trims。"
    } else {
        "已藏起 $zoneCount 個自訂地點周圍 ${if (radiusM >= 1000) "${radiusM / 1000} 公里" else "$radiusM 公尺"}內的路線，$trims。"
    }
    return if (route.mapHidden) "$zonesText 裁完剩下的路線太短，分享卡不放地圖。" else zonesText
}

@Composable
private fun ShareToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(description, color = Color.Gray, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158), checkedThumbColor = Color.White),
        )
    }
}
