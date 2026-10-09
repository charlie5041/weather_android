package com.charlie.weather.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.charlie.weather.data.RouteForecast
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

/**
 * Google 地圖上的路線：依各段降雨程度著色（白色外框讓路線在地圖上清楚），
 * 起點綠色、終點紅色標記，中途取樣點畫小圓點。點地圖開啟 Google 地圖導航。
 */
@Composable
fun GoogleRouteMap(forecast: RouteForecast, onOpenMaps: () -> Unit, modifier: Modifier = Modifier) {
    val path = remember(forecast.data.path) { forecast.data.path.points.map { LatLng(it.latitude, it.longitude) } }
    val colors = remember(forecast) { segmentColors(forecast) }
    // 相鄰同色的路段合併成一條 Polyline，減少地圖上的物件數量
    val runs = remember(path, colors) {
        val result = mutableListOf<Pair<Color, List<LatLng>>>()
        colors.forEachIndexed { i, color ->
            val last = result.lastOrNull()
            if (last != null && last.first == color) {
                result[result.lastIndex] = color to (last.second + path[i + 1])
            } else {
                result += color to listOf(path[i], path[i + 1])
            }
        }
        result
    }
    val cameraState = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    val padding = with(LocalDensity.current) { 36.dp.roundToPx() }
    LaunchedEffect(loaded, path) {
        if (!loaded || path.isEmpty()) return@LaunchedEffect
        val bounds = LatLngBounds.builder().apply { path.forEach { include(it) } }.build()
        cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }
    val radar = visibleNowcast(forecast)
    val radarRaster = remember(radar, forecast.data.path) { radar?.let { nowcastRaster(it, forecast.data.path.points) } }
    val routeKm = forecast.data.path.distanceKm
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false),
        onMapLoaded = { loaded = true },
        onMapClick = { onOpenMaps() },
    ) {
        radarRaster?.let { raster ->
            // 雷達格點拉伸成地面圖層；每格一個像素，放大後呈方格狀
            val image = remember(raster) { BitmapDescriptorFactory.fromBitmap(raster.toBitmap()) }
            GroundOverlay(
                position = GroundOverlayPosition.create(LatLngBounds(LatLng(raster.south, raster.west), LatLng(raster.north, raster.east))),
                image = image,
            )
        }
        if (path.size >= 2) {
            Polyline(points = path, color = Color.White, width = 22f, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap())
            runs.forEach { (color, points) ->
                Polyline(points = points, color = color, width = 13f, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap())
            }
        }
        // 中途取樣點：半徑隨路線長度調整，縮放到整條路線時大小約略固定
        val dotMeters = (routeKm * 1000 * 0.006).coerceIn(15.0, 400.0)
        forecast.stops.drop(1).dropLast(1).forEach { stop ->
            Circle(
                center = LatLng(stop.point.position.latitude, stop.point.position.longitude),
                radius = dotMeters,
                fillColor = rainColor(stop),
                strokeColor = Color.White,
                strokeWidth = 4f,
                zIndex = 2f,
            )
        }
        forecast.data.cameras.forEach { c ->
            val position = LatLng(c.camera.position.latitude, c.camera.position.longitude)
            Marker(
                state = rememberMarkerState(key = "camera_$position", position = position),
                title = (if (c.camera.mobile) "移動式測速（使用者回報）" else "測速照相") + (c.camera.limit?.let { "・速限 $it" } ?: ""),
                snippet = c.camera.address,
                icon = BitmapDescriptorFactory.defaultMarker(
                    if (c.camera.mobile) BitmapDescriptorFactory.HUE_RED else BitmapDescriptorFactory.HUE_ORANGE,
                ),
                alpha = 0.9f,
            )
        }
        path.firstOrNull()?.let {
            Marker(
                state = rememberMarkerState(key = "from_$it", position = it),
                title = forecast.data.from.displayName,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
            )
        }
        path.lastOrNull()?.let {
            Marker(
                state = rememberMarkerState(key = "to_$it", position = it),
                title = forecast.data.to.displayName,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
            )
        }
    }
}
