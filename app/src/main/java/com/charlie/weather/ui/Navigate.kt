package com.charlie.weather.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.charlie.weather.data.MapsNavigation
import com.charlie.weather.data.RouteData

/** 開 Google 地圖導航到這條路線的終點；沒有 Google 地圖時交給其他能開地圖網址的 App。 */
internal fun openNavigation(context: Context, data: RouteData) {
    val uri = Uri.parse(MapsNavigation.uri(data))
    val maps = Intent(Intent.ACTION_VIEW, uri).setPackage(MapsNavigation.GOOGLE_MAPS_PACKAGE)
    val intents = listOf(maps, Intent(Intent.ACTION_VIEW, uri))
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // 試下一個
        }
    }
    Toast.makeText(context, "找不到可以導航的地圖 App", Toast.LENGTH_LONG).show()
}
