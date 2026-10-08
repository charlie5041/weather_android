package com.charlie.weather.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.charlie.weather.MainActivity
import com.charlie.weather.R
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.LocationProvider
import com.charlie.weather.data.RideTracker
import com.charlie.weather.data.RouteData
import com.charlie.weather.data.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 騎乘中模式：前景服務持續取得位置，常駐通知顯示前方天氣與抵達時間；
 * 前方 20 分鐘內會遇到雨時另外跳出提醒（同一段雨只提醒一次）。抵達終點或按「結束」時停止。
 * 路線資料只放在記憶體（[start] 傳入）；程序被系統回收後服務不會自行恢復。
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastLocation: Location? = null
    private var alertedPlace: String? = null
    private var listener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || route == null) {
            stopRide()
            return START_NOT_STICKY
        }
        WeatherNotifier.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, ID_ONGOING, ongoing("沿途天氣提醒", "正在取得位置…"), type)
        } catch (e: Exception) {
            // 沒有定位權限或系統不允許從背景啟動
            stopRide()
            return START_NOT_STICKY
        }
        _active.value = true
        alertedPlace = null
        startLocationUpdates()
        scope.launch { refreshLoop() }
        return START_NOT_STICKY
    }

    /** 沒有新位置時也每 2 分鐘重算（時間在走）；每 10 分鐘更新雷達短時預報 */
    private suspend fun refreshLoop() {
        var lastRadar = 0L
        while (scope.isActive) {
            if (System.currentTimeMillis() - lastRadar >= RADAR_MS) {
                val radar = withContext(Dispatchers.IO) { WeatherRepository.get(this@RideService).nowcast() }
                route = route?.copy(nowcast = radar ?: route?.nowcast)
                lastRadar = System.currentTimeMillis()
            }
            update()
            delay(REFRESH_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (listener != null || !LocationProvider(this).hasPermission()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                update()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { provider ->
                runCatching { lm.requestLocationUpdates(provider, LOCATION_MS, LOCATION_M, l, Looper.getMainLooper()) }
            }
        listener = l
    }

    private fun update() {
        val data = route ?: return
        val location = lastLocation ?: return
        val now = LocalDateTime.now()
        val progress = RideTracker.progress(data.path, LatLon(location.latitude, location.longitude))
        val status = RideTracker.status(data, progress, now)
        notify(ID_ONGOING, ongoing(status.title, status.text))
        val rain = status.rainAhead
        val minutes = status.minutesToRain
        if (rain != null && minutes != null && minutes <= ALERT_MINUTES) {
            val key = rain.place ?: rain.point.distanceKm.toInt().toString()
            if (key != alertedPlace) {
                alertedPlace = key
                notify(
                    ID_ALERT,
                    NotificationCompat.Builder(this, WeatherNotifier.CHANNEL_RIDE_ALERT)
                        .setSmallIcon(R.drawable.ic_stat_weather)
                        .setContentTitle("☔ ${status.title}")
                        .setContentText(status.text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(status.text))
                        .setContentIntent(openApp())
                        .setAutoCancel(true)
                        .build(),
                )
            }
        } else if (rain == null) {
            // 雨過了或預報改變，之後再遇到雨可以再提醒
            alertedPlace = null
        }
        if (status.arrived) stopRide()
    }

    private fun ongoing(title: String, text: String): Notification =
        NotificationCompat.Builder(this, WeatherNotifier.CHANNEL_RIDE)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(
                0, "結束",
                PendingIntent.getService(
                    this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: SecurityException) {
            // 沒有通知權限
        }
    }

    private fun stopRide() {
        listener?.let { l -> getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
        route = null
        _active.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener?.let { l -> getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        scope.cancel()
        _active.value = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.charlie.weather.ride.STOP"
        private const val ID_ONGOING = 5001
        private const val ID_ALERT = 5002
        private const val REFRESH_MS = 2 * 60_000L
        private const val RADAR_MS = 10 * 60_000L
        private const val LOCATION_MS = 20_000L
        private const val LOCATION_M = 100f
        private const val ALERT_MINUTES = 20L

        @Volatile
        private var route: RouteData? = null

        private val _active = MutableStateFlow(false)

        /** 騎乘中模式是否進行中（路線畫面用來切換按鈕） */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        fun start(context: Context, data: RouteData) {
            route = data
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
