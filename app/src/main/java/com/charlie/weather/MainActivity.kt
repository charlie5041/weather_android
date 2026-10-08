package com.charlie.weather

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.charlie.weather.sync.WeatherNotifier
import com.charlie.weather.sync.WeatherSyncWorker
import com.charlie.weather.ui.Units
import com.charlie.weather.ui.WeatherApp

class MainActivity : ComponentActivity() {
    /** Google 地圖分享進來的路線連結，交給 [WeatherApp] 開啟沿路天氣 */
    private var sharedText by mutableStateOf<String?>(null)

    /** 點常用路線通知開啟時要開的路線 */
    private var favoriteRouteId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        Units.load(this)
        WeatherNotifier.createChannels(this)
        WeatherSyncWorker.schedule(this)
        if (savedInstanceState == null) {
            sharedText = sharedTextOf(intent)
            favoriteRouteId = intent?.getStringExtra(WeatherNotifier.EXTRA_FAVORITE_ROUTE)
        }
        setContent {
            WeatherApp(
                sharedText = sharedText,
                onSharedTextHandled = { sharedText = null },
                favoriteRouteId = favoriteRouteId,
                onFavoriteRouteHandled = { favoriteRouteId = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedTextOf(intent)?.let { sharedText = it }
        intent.getStringExtra(WeatherNotifier.EXTRA_FAVORITE_ROUTE)?.let { favoriteRouteId = it }
    }

    private fun sharedTextOf(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
}
