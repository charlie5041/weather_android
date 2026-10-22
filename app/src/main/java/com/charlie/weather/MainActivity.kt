package com.charlie.weather

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
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

    /** 從通知的「開始騎乘」開啟：路線查好後直接開始騎乘中模式 */
    private var startFavoriteRide by mutableStateOf(false)

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
            openFavoriteIfAsked(intent)
            startDriveIfAsked(intent)
        }
        setContent {
            WeatherApp(
                sharedText = sharedText,
                onSharedTextHandled = { sharedText = null },
                favoriteRouteId = favoriteRouteId,
                startFavoriteRide = startFavoriteRide,
                onFavoriteRouteHandled = {
                    favoriteRouteId = null
                    startFavoriteRide = false
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedTextOf(intent)?.let { sharedText = it }
        openFavoriteIfAsked(intent)
        startDriveIfAsked(intent)
    }

    /** 點常用路線通知（或通知上的「開始騎乘」）開啟時要開的路線 */
    private fun openFavoriteIfAsked(intent: Intent?) {
        val id = intent?.getStringExtra(WeatherNotifier.EXTRA_FAVORITE_ROUTE) ?: return
        startFavoriteRide = intent.getBooleanExtra(WeatherNotifier.EXTRA_START_RIDE, false)
        favoriteRouteId = id
        // 按通知上的按鈕不會自動收起通知
        if (intent.hasExtra(WeatherNotifier.EXTRA_NOTIFICATION_ID)) {
            NotificationManagerCompat.from(this).cancel(intent.getIntExtra(WeatherNotifier.EXTRA_NOTIFICATION_ID, 0))
        }
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
