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
import com.charlie.weather.sync.WeatherNotifier
import com.charlie.weather.sync.WeatherSyncWorker

private val SectionColor = Color(0xFF1C1C1E)

@Composable
fun SettingsScreen(primaryCityName: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var rain by remember { mutableStateOf(settings.rainAlerts) }
    var warnings by remember { mutableStateOf(settings.warningAlerts) }
    var morning by remember { mutableStateOf(settings.morningSummary) }
    var morningHour by remember { mutableIntStateOf(settings.morningHour) }
    var canNotify by remember { mutableStateOf(WeatherNotifier.canNotify(context)) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf(6, 7, 8, 9).forEach { hour ->
                        val active = hour == morningHour
                        Text(
                            "${hour}:00",
                            color = if (active) Color.Black else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (active) Color.White else Color(0xFF3A3A3C))
                                .clickable {
                                    morningHour = hour
                                    settings.morningHour = hour
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        Text(
            "通知與小工具以「${primaryCityName ?: "城市列表第一個城市"}」為準（城市列表最上方的城市），背景約每 30 分鐘更新一次。",
            color = Color.Gray,
            fontSize = 13.sp,
        )

        SectionTitle("桌面小工具")
        Section {
            Text("在桌面空白處長按 → 小工具 → 找到「我的天氣」，拖曳到桌面即可。", color = Color.White, fontSize = 15.sp)
            Text("可調整成小、中、大三種尺寸：中型顯示逐時預報，大型再加上每日預報。", color = Color.Gray, fontSize = 14.sp)
            TextButton(onClick = { WeatherSyncWorker.runNow(context) }) {
                Text("立即更新小工具", color = Color(0xFF0A84FF), fontSize = 16.sp)
            }
        }

        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        Text("版本 ${version ?: "-"} · 資料來源：中央氣象署、Open-Meteo", color = Color.Gray, fontSize = 12.sp)
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
