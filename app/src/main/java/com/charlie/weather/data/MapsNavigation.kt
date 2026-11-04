package com.charlie.weather.data

import java.util.Locale

/**
 * 交給 Google 地圖導航：一路晴在背景繼續做沿路天氣、測速提醒與足跡記錄。
 *
 * 用 Maps URLs 的 `dir_action=navigate`：不帶起點時從目前位置直接進入導航，
 * 可以帶途經點與交通方式（機車是 `two-wheeler`）。
 */
object MapsNavigation {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    fun uri(data: RouteData): String = uri(data.to, data.via, data.mode)

    fun uri(to: City, via: List<City>, mode: TravelMode): String = buildString {
        append("https://www.google.com/maps/dir/?api=1")
        append("&destination=").append(coord(to.latitude, to.longitude))
        if (via.isNotEmpty()) {
            append("&waypoints=").append(via.joinToString("%7C") { coord(it.latitude, it.longitude) })
        }
        append("&travelmode=").append(travelMode(mode))
        append("&dir_action=navigate")
    }

    fun travelMode(mode: TravelMode) = when (mode) {
        TravelMode.SCOOTER -> "two-wheeler"
        TravelMode.CAR -> "driving"
        TravelMode.BIKE -> "bicycling"
        TravelMode.WALK -> "walking"
    }

    private fun coord(lat: Double, lon: Double) = "%.6f,%.6f".format(Locale.US, lat, lon)
}
