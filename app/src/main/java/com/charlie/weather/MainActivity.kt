package com.charlie.weather

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.charlie.weather.sync.WeatherNotifier
import com.charlie.weather.sync.WeatherSyncWorker
import com.charlie.weather.ui.Units
import com.charlie.weather.ui.WeatherApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        Units.load(this)
        WeatherNotifier.createChannels(this)
        WeatherSyncWorker.schedule(this)
        setContent { WeatherApp() }
    }
}
