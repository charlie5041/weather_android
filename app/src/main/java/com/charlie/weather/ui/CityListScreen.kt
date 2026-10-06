package com.charlie.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.charlie.weather.data.City
import com.charlie.weather.data.Weather

@Composable
fun CityListScreen(
    cities: List<City>,
    weather: Map<String, CityWeatherUi>,
    query: String,
    results: List<City>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onAdd: (City) -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (City) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    onAddPlace: () -> Unit = {},
    onEditPlace: (City) -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("天氣", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (query.isBlank()) {
                TextButton(onClick = { editing = !editing }) {
                    Text(
                        if (editing) "完成" else "編輯",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "設定", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }
        if (!editing) TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("搜尋城市") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "清除") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White,
                focusedPlaceholderColor = Color.Gray,
                unfocusedPlaceholderColor = Color.Gray,
                focusedLeadingIconColor = Color.Gray,
                unfocusedLeadingIconColor = Color.Gray,
                focusedTrailingIconColor = Color.Gray,
                unfocusedTrailingIconColor = Color.Gray,
            ),
        )

        if (query.isNotBlank() && !editing) {
            SearchResults(results, searching, onAdd)
        } else {
            if (editing) {
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    "＋ 新增住家、公司等地點",
                    color = Color(0xFF0A84FF),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onAddPlace)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
            ReorderableCityList(cities, weather, editing, onSelect, onRemove, onMove, onEditPlace)
        }
    }
}

/**
 * 城市列表（仿 iOS）：
 * - 一般模式：點卡片切換城市，向左滑刪除
 * - 編輯模式：按住右側 ≡ 拖曳排序；點左側 ⊖ 後再點「刪除」
 * 「我的位置」固定在最上方，不能刪除或移動。
 */
@Composable
private fun ReorderableCityList(
    cities: List<City>,
    weather: Map<String, CityWeatherUi>,
    editing: Boolean,
    onSelect: (Int) -> Unit,
    onRemove: (City) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onEditPlace: (City) -> Unit,
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var confirmingDeleteId by remember { mutableStateOf<String?>(null) }
    val currentCities by rememberUpdatedState(cities)
    val currentOnMove by rememberUpdatedState(onMove)

    LaunchedEffect(editing) {
        if (!editing) confirmingDeleteId = null
    }

    fun movable(key: Any?) = currentCities.any { it.id == key && !it.isCurrentLocation }

    fun dragBy(id: String, dy: Float) {
        dragOffset += dy
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == id } ?: return
        val center = current.offset + dragOffset + current.size / 2f
        val target = items.firstOrNull {
            it.key != id && movable(it.key) && center > it.offset && center < it.offset + it.size
        } ?: return
        currentOnMove(current.index, target.index)
        // 卡片已換到新位置，修正位移讓它維持在手指下方
        dragOffset -= (target.offset - current.offset)
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(if (editing) 8.dp else 10.dp),
        contentPadding = PaddingValues(top = 4.dp),
    ) {
        itemsIndexed(cities, key = { _, c -> c.id }) { index, city ->
            val dragging = city.id == draggingId
            val itemModifier = if (dragging) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        translationY = dragOffset
                        scaleX = 1.03f
                        scaleY = 1.03f
                    }
            } else {
                Modifier.animateItem()
            }
            val cityWeather = weather[city.id]?.weather
            Box(itemModifier) {
                when {
                    editing -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateContentSize()) {
                        if (city.isCurrentLocation) {
                            Spacer(Modifier.width(40.dp))
                        } else {
                            IconButton(
                                onClick = { confirmingDeleteId = if (confirmingDeleteId == city.id) null else city.id },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFFF3B30)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.width(10.dp).height(2.dp).background(Color.White))
                                }
                            }
                        }
                        CityRow(
                            city = city,
                            weather = cityWeather,
                            compact = true,
                            // 編輯模式點自訂地點（住家、公司…）可以修改名稱與地址
                            onClick = {
                                confirmingDeleteId = null
                                if (city.label != null) onEditPlace(city)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (confirmingDeleteId == city.id) {
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFF3B30))
                                    .clickable {
                                        confirmingDeleteId = null
                                        onRemove(city)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 22.dp),
                            ) {
                                Text("刪除", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else if (city.isCurrentLocation) {
                            Spacer(Modifier.width(44.dp))
                        } else {
                            DragHandle(
                                modifier = Modifier.pointerInput(city.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            confirmingDeleteId = null
                                            draggingId = city.id
                                            dragOffset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragBy(city.id, amount.y)
                                        },
                                        onDragEnd = {
                                            draggingId = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragOffset = 0f
                                        },
                                    )
                                },
                            )
                        }
                    }
                    city.isCurrentLocation -> CityRow(city, cityWeather, onClick = { onSelect(index) })
                    else -> SwipeToDeleteRow(onDelete = { onRemove(city) }) {
                        CityRow(city, cityWeather, onClick = { onSelect(index) })
                    }
                }
            }
        }
        item(key = "hint") {
            Column {
                Text(
                    if (editing) "按住右側 ≡ 拖曳可調整順序；點自訂地點可修改名稱與地址" else "向左滑可刪除；點右上角「編輯」可調整順序",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** iOS 風格的三橫線拖曳把手 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(Modifier.width(20.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.Gray))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnDelete()
                true
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFFF3B30))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = Color.White)
                    Text("刪除", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun SearchResults(results: List<City>, searching: Boolean, onAdd: (City) -> Unit) {
    if (searching && results.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    if (results.isEmpty()) {
        Text("沒有結果", color = Color.Gray, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    LazyColumn {
        items(results, key = { it.id }) { city ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onAdd(city) }
                    .padding(vertical = 12.dp),
            ) {
                Text(city.name, fontSize = 17.sp, color = Color.White)
                if (city.subtitle.isNotBlank()) {
                    Text(city.subtitle, fontSize = 13.sp, color = Color.Gray)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun CityRow(
    city: City,
    weather: Weather?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = weather?.let { backgroundColors(it.current.weatherCode, it.current.isDay) } ?: backgroundColors(1, true)
    val title = city.label ?: if (city.isCurrentLocation) "我的位置" else city.name
    val subtitle = if (city.isCurrentLocation || city.label != null) city.name else weather?.let { timeLabel(it.localNow()) } ?: city.subtitle
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp))
            .background(Brush.verticalGradient(colors))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 16.dp),
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = if (compact) 18.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, fontSize = if (compact) 13.sp else 14.sp, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
            if (!compact) {
                Spacer(Modifier.height(18.dp))
                Text(
                    weather?.current?.conditionText() ?: "",
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                weather?.current?.temperature?.deg() ?: "--",
                fontSize = if (compact) 30.sp else 48.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                lineHeight = if (compact) 34.sp else 52.sp,
            )
            if (!compact) {
                weather?.today?.let {
                    Text(
                        "最高 ${it.temperatureMax.deg()} 最低 ${it.temperatureMin.deg()}",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
