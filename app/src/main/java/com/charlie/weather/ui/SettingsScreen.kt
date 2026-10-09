package com.charlie.weather.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.charlie.weather.data.AppSettings
import com.charlie.weather.data.RoutePlanner
import com.charlie.weather.sync.WeatherNotifier
import com.charlie.weather.sync.WeatherSyncWorker

private val SectionColor = Color(0xFF1C1C1E)

@Composable
fun SettingsScreen(primaryCityName: String?, onDataSourceChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var rain by remember { mutableStateOf(settings.rainAlerts) }
    var warnings by remember { mutableStateOf(settings.warningAlerts) }
    var morning by remember { mutableStateOf(settings.morningSummary) }
    var morningHour by remember { mutableIntStateOf(settings.morningHour) }
    var canNotify by remember { mutableStateOf(WeatherNotifier.canNotify(context)) }
    var useCwa by remember { mutableStateOf(settings.useCwa) }
    var placeAlerts by remember { mutableStateOf(settings.placeAlerts) }
    var commuteCard by remember { mutableStateOf(settings.commuteCard) }
    var commuteNotify by remember { mutableStateOf(settings.commuteNotify) }
    var commuteMorning by remember { mutableIntStateOf(settings.commuteMorningHour) }
    var commuteEvening by remember { mutableIntStateOf(settings.commuteEveningHour) }
    var routeStep by remember { mutableIntStateOf(settings.routeStepKm) }
    var speedCameras by remember { mutableStateOf(settings.speedCameras) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = WeatherNotifier.canNotify(context) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = granted && WeatherNotifier.canNotify(context)
        if (!granted) openNotificationSettings(context)
    }

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
            Text("設定", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }

        if (!canNotify) {
            Section {
                Text("通知目前未開啟", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("開啟後才能收到降雨提醒、天氣特報與每日天氣。", color = Color.Gray, fontSize = 14.sp)
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                }) { Text("開啟通知", color = Color(0xFF0A84FF), fontSize = 16.sp) }
            }
        }

        SectionTitle("單位")
        Section {
            ChoiceRow("溫度", TemperatureUnit.entries, Units.temperature, { it.label }) {
                Units.temperature = it
                settings.temperatureUnit = it.name
                WeatherSyncWorker.runNow(context)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            ChoiceRow("風速", WindUnit.entries, Units.wind, { if (it == WindUnit.BEAUFORT) "蒲福風級" else it.label }) {
                Units.wind = it
                settings.windUnit = it.name
            }
        }

        SectionTitle("資料來源")
        Section {
            ToggleRow(
                "使用中央氣象署資料",
                "在台灣以最近測站實測、鄉鎮預報與天氣特報取代 Open-Meteo；關閉後全部使用 Open-Meteo",
                useCwa,
            ) {
                useCwa = it
                settings.useCwa = it
                onDataSourceChanged()
            }
        }

        SectionTitle("通知")
        Section {
            ToggleRow("降雨提醒", "未來 2 小時內降雨機率達 60% 時提醒（3 小時內不重複）", rain) {
                rain = it
                settings.rainAlerts = it
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            ToggleRow("天氣特報", "中央氣象署對所在縣市發布豪雨、強風、颱風等特報時通知", warnings) {
                warnings = it
                settings.warningAlerts = it
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            ToggleRow("每日早晨天氣", "每天早上推送今日溫度、降雨機率與特報摘要", morning) {
                morning = it
                settings.morningSummary = it
            }
            if (morning) {
                HourChips(listOf(6, 7, 8, 9), morningHour) {
                    morningHour = it
                    settings.morningHour = it
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            ToggleRow("自訂地點提醒", "住家、公司等自訂地點也會收到降雨提醒與天氣特報", placeAlerts) {
                placeAlerts = it
                settings.placeAlerts = it
            }
        }
        Text(
            "通知與小工具以「${primaryCityName ?: "城市列表第一個城市"}」為準（城市列表最上方的城市），背景約每 30 分鐘更新一次。",
            color = Color.Gray,
            fontSize = 13.sp,
        )

        SectionTitle("通勤")
        Section {
            ToggleRow("通勤預報卡片", "有「住家」與「公司」（或「學校」）地點時，在主畫面顯示下一趟通勤兩地的天氣", commuteCard) {
                commuteCard = it
                settings.commuteCard = it
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            ToggleRow("通勤天氣通知", "平日出發前 1.5 小時內推送一次兩地天氣與帶傘提醒", commuteNotify) {
                commuteNotify = it
                settings.commuteNotify = it
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Text("上班出發", color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp))
            HourChips(listOf(7, 8, 9, 10), commuteMorning) {
                commuteMorning = it
                settings.commuteMorningHour = it
            }
            Text("下班出發", color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
            HourChips(listOf(17, 18, 19, 20), commuteEvening) {
                commuteEvening = it
                settings.commuteEveningHour = it
            }
        }
        Text(
            "在城市列表點「＋ 新增住家、公司等地點」設定兩地地址；只計算平日，週末會顯示下週一的通勤。",
            color = Color.Gray,
            fontSize = 13.sp,
        )

        SectionTitle("沿路天氣")
        Section {
            ChoiceRow("取樣間距（公里）", RoutePlanner.STEP_OPTIONS, routeStep, { it.toString() }) {
                routeStep = it
                settings.routeStepKm = it
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            ToggleRow("測速照相提醒", "機車與汽車路線列出沿途的固定式測速照相；騎乘中模式接近前 500 公尺提醒", speedCameras) {
                speedCameras = it
                settings.speedCameras = it
            }
        }
        Text(
"沿路線每隔幾公里查一次天氣。間距越小越細，但沿途清單越長；一條路線最多查 ${RoutePlanner.MAX_POINTS} 個點，路線很長時間距會自動放大。\n" +
                "測速照相使用警政署公布的固定式測速執法地點（每週更新），不含移動式測速，與現場可能不同，請依實際速限行駛。",
            color = Color.Gray,
            fontSize = 13.sp,
        )

        SectionTitle("桌面小工具")
        Section {
            Text("在桌面空白處長按 → 小工具 → 找到「出行看天氣」，拖曳到桌面即可。", color = Color.White, fontSize = 15.sp)
            Text("可調整成小、中、大三種尺寸：中型顯示逐時預報，大型再加上每日預報。", color = Color.Gray, fontSize = 14.sp)
            TextButton(onClick = { WeatherSyncWorker.runNow(context) }) {
                Text("立即更新小工具", color = Color(0xFF0A84FF), fontSize = 16.sp)
            }
        }

        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        Text(
            "版本 ${version ?: "-"}\n資料來源：中央氣象署、環境部、警政署（政府資料開放授權條款）、Open-Meteo（CC BY 4.0）、" +
                "GeoNames（CC BY 4.0）、Natural Earth",
            color = Color.Gray,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun HourChips(hours: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        hours.forEach { hour ->
            val active = hour == selected
            Text(
                "${hour}:00",
                color = if (active) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White else Color(0xFF3A3A3C))
                    .clickable { onSelect(hour) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SectionColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF2C2C2E)).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                val active = option == selected
                Text(
                    label(option),
                    color = if (active) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) Color.White else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            Text(description, color = Color.Gray, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158), checkedThumbColor = Color.White),
        )
    }
}
