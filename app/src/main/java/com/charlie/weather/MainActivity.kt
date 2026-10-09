package com.charlie.weather

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.charlie.weather.data.LocationProvider
import com.charlie.weather.sync.RideService
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
            startDriveIfAsked(intent)
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
        startDriveIfAsked(intent)
    }

    /** 快速設定方塊開啟時開始行車提醒（App 在前景才能啟動定位的前景服務） */
    private fun startDriveIfAsked(intent: Intent?) {
        if (intent?.action != ACTION_START_DRIVE) return
        if (LocationProvider(this).hasPermission()) {
            RideService.startDrive(this)
            Toast.makeText(this, "已開始行車提醒：前方有測速照相時通知", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要定位權限才能提醒前方的測速照相", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_START_DRIVE = "com.charlie.weather.START_DRIVE"
    }

    private fun sharedTextOf(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
}
