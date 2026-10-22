package com.charlie.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.City
import com.charlie.weather.data.Commute
import com.charlie.weather.data.TravelMode

private val SetupSecondary = Color(0xFFA0A0A8)
private val SetupChip = Color(0xFF3A3A3C)
private const val STEPS = 3

/**
 * 第一次使用的出門設定：怎麼出門、住家公司在哪、平日幾點出門。
 * 完成時建立上下班兩條常用路線（有住家與公司時），自訂地點也就是分享足跡的隱私區。每一步都可以跳過。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    places: List<City>,
    initialMode: TravelMode,
    /** 新增或修改住家、公司：[existing] 為 null 時新增並預先選好 [label] */
    onEditPlace: (label: String, existing: City?) -> Unit,
    onFinish: (mode: TravelMode, morningHour: Int, eveningHour: Int) -> Unit,
    onSkip: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var morning by rememberSaveable { mutableIntStateOf(Commute.MORNING_HOUR) }
    var evening by rememberSaveable { mutableIntStateOf(Commute.EVENING_HOUR) }
    val commute = Commute.homeAndWork(places)
    val home = places.firstOrNull { it.label == Commute.HOME }
    val work = commute?.second ?: places.firstOrNull { it.label == "公司" }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEPS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) Accent else SetupChip),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${step + 1} / $STEPS", color = SetupSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                "略過",
                color = SetupSecondary,
                fontSize = 15.sp,
                modifier = Modifier.clickable(role = Role.Button, onClick = onSkip).padding(8.dp),
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                0 -> {
                    Title("你通常怎麼出門？")
                    Body("查沿路天氣、建立上下班路線時預設用這個交通方式；機車與單車會提醒帶雨衣。之後每次查路線都可以改。")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TravelMode.entries.forEach { m ->
                            Chip("${modeEmoji(m)} ${m.label}", m == mode) { mode = m }
                        }
                    }
                }
                1 -> {
                    Title("住家和公司在哪裡？")
                    Body("用來建立上下班路線。分享足跡時，這兩個地方附近的路線也會自動藏起來。只存在這支手機。")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelColor).padding(horizontal = 16.dp)) {
                        PlaceRow("住家", home) { onEditPlace(Commute.HOME, home) }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        PlaceRow(work?.label ?: "公司", work) { onEditPlace(work?.label ?: "公司", work) }
                    }
                    Body("上學的話，在地點名稱選「學校」。沒有固定通勤可以直接按「下一步」。")
                }
                else -> {
                    Title("平日幾點出門？")
                    if (commute != null) {
                        Body("出發前 1.5 小時內會通知沿路天氣，「出門」分頁也會顯示下一趟會不會淋雨。")
                        Text("上班", color = Color.White, fontSize = 17.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(7, 8, 9, 10).forEach { h -> Chip("$h:00", h == morning) { morning = h } }
                        }
                        Text("下班", color = Color.White, fontSize = 17.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(17, 18, 19, 20).forEach { h -> Chip("$h:00", h == evening) { evening = h } }
                        }
                        Body("會建立「上班」「下班」兩條常用路線，之後可以在「出門」分頁改時間或刪除。")
                    } else {
                        Body("還沒有設定住家和公司，所以先不建立上下班路線。之後在「設定 → 我的地點」加上，「出門」分頁就可以一鍵建立。")
                    }
                }
            }
        }
        Text(
            if (step < STEPS - 1) "下一步" else "完成",
            color = Color.Black,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable(role = Role.Button) {
                    if (step < STEPS - 1) step++ else onFinish(mode, morning, evening)
                }
                .padding(vertical = 15.dp),
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
}

@Composable
private fun Body(text: String) {
    Text(text, color = SetupSecondary, fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else SetupChip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun PlaceRow(label: String, place: City?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = SetupSecondary, fontSize = 13.sp)
            Text(
                place?.let { it.address ?: it.name } ?: "設定地址",
                color = if (place != null) Color.White else Accent,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = SetupSecondary, fontSize = 20.sp)
    }
}
