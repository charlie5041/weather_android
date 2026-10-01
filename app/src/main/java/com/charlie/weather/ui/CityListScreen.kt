package com.charlie.weather.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onClose: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<City?>(null) }

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
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "關閉", tint = Color.White)
            }
        }
        TextField(
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

        if (query.isNotBlank()) {
            SearchResults(results, searching, onAdd)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp),
            ) {
                itemsIndexed(cities, key = { _, c -> c.id }) { index, city ->
                    CityRow(
                        city = city,
                        weather = weather[city.id]?.weather,
                        onClick = { onSelect(index) },
                        onLongClick = { if (!city.isCurrentLocation) pendingDelete = city },
                    )
                }
                item {
                    Text(
                        "長按城市可刪除",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }

    pendingDelete?.let { city ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("刪除「${city.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(city)
                    pendingDelete = null
                }) { Text("刪除", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CityRow(city: City, weather: Weather?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = weather?.let { backgroundColors(it.current.weatherCode, it.current.isDay) } ?: backgroundColors(1, true)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(colors))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (city.isCurrentLocation) "我的位置" else city.name,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (city.isCurrentLocation) city.name else weather?.let { timeLabel(it.localNow()) } ?: city.subtitle,
                fontSize = 14.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                weather?.let { WeatherCodes.description(it.current.weatherCode) } ?: "",
                fontSize = 14.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(weather?.current?.temperature?.deg() ?: "--", fontSize = 48.sp, fontWeight = FontWeight.Light, color = Color.White, lineHeight = 52.sp)
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
