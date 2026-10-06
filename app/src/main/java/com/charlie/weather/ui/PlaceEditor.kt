package com.charlie.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charlie.weather.data.AddressResult
import com.charlie.weather.data.City
import kotlinx.coroutines.launch

private val PresetLabels = listOf("住家", "公司", "學校")
private const val OtherLabel = "其他"
private val PanelColor = Color(0xFF1C1C1E)
private val Accent = Color(0xFF0A84FF)

/**
 * 新增或編輯自訂地點：選擇名稱（住家、公司、學校或自訂），
 * 輸入地址查詢精確位置，或直接使用目前位置。
 */
@Composable
fun PlaceEditorScreen(
    existing: City?,
    onSearch: suspend (String) -> List<AddressResult>,
    onUseCurrentLocation: suspend () -> AddressResult?,
    onSave: (label: String, result: AddressResult) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val initialLabel = existing?.label
    var labelChoice by remember { mutableStateOf(initialLabel?.takeIf { it in PresetLabels } ?: if (initialLabel != null) OtherLabel else PresetLabels.first()) }
    var customLabel by remember { mutableStateOf(initialLabel?.takeIf { it !in PresetLabels }.orEmpty()) }
    var query by remember { mutableStateOf(existing?.address.orEmpty()) }
    var results by remember { mutableStateOf<List<AddressResult>>(emptyList()) }
    var selected by remember {
        mutableStateOf(existing?.let { AddressResult(it.name, it.address ?: it.subtitle, it.latitude, it.longitude) })
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val label = if (labelChoice == OtherLabel) customLabel.trim() else labelChoice
    val canSave = selected != null && label.isNotEmpty() && !busy

    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        scope.launch {
            busy = true
            message = null
            results = onSearch(query)
            busy = false
            if (results.isEmpty()) message = "找不到這個地址，請輸入更完整的地址，例如「臺北市內湖區瑞光路 100 號」。"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("取消", color = Accent, fontSize = 17.sp) }
            Text(
                if (existing == null) "新增地點" else "編輯地點",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(onClick = { selected?.let { onSave(label, it) } }, enabled = canSave) {
                Text("儲存", color = if (canSave) Accent else Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("名稱")
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (PresetLabels + OtherLabel).forEach { option ->
                        val active = option == labelChoice
                        Text(
                            option,
                            color = if (active) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (active) Color.White else Color(0xFF3A3A3C))
                                .clickable { labelChoice = option }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (labelChoice == OtherLabel) {
                    Spacer(Modifier.height(10.dp))
                    DarkTextField(customLabel, { customLabel = it.take(12) }, "例如：爸媽家、健身房")
                }
            }

            SectionLabel("地址")
            Panel {
                DarkTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "輸入地址或地標",
                    trailing = {
                        IconButton(onClick = ::search) { Icon(Icons.Filled.Search, contentDescription = "搜尋", tint = Color.Gray) }
                    },
                    onSearch = ::search,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) {
                            scope.launch {
                                busy = true
                                message = null
                                val here = onUseCurrentLocation()
                                busy = false
                                if (here != null) {
                                    selected = here
                                    query = here.address
                                    results = emptyList()
                                } else {
                                    message = "無法取得目前位置，請確認已允許定位權限。"
                                }
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    Text(" 使用目前位置", color = Accent, fontSize = 15.sp)
                }
                if (busy) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                results.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = r
                                query = r.address
                                results = emptyList()
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(r.address, color = Color.White, fontSize = 15.sp)
                        Text(if (r.approximate) "${r.area}（大概位置）" else r.area, color = Color.Gray, fontSize = 13.sp)
                    }
                }
                message?.let { Text(it, color = Color(0xFFFF9F0A), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }

            selected?.let { place ->
                SectionLabel("已選擇")
                Panel {
                    Text(label.ifEmpty { "（請輸入名稱）" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(place.address, color = Color.White, fontSize = 15.sp)
                    Text(
                        if (place.approximate) "${place.area} · 只找到大概位置，會使用該鄉鎮的天氣" else place.area,
                        color = Color.Gray,
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                "會使用離這個地址最近的氣象站與雨量站，比整個城市的天氣更貼近該地點。",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelColor).padding(12.dp),
        content = content,
    )
}

@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    trailing: (@Composable () -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        trailingIcon = trailing,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF2C2C2E),
            unfocusedContainerColor = Color(0xFF2C2C2E),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedPlaceholderColor = Color.Gray,
            unfocusedPlaceholderColor = Color.Gray,
        ),
    )
}
