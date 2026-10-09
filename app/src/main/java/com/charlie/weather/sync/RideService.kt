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
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.charlie.weather.MainActivity
import com.charlie.weather.R
import com.charlie.weather.data.AppSettings
import com.charlie.weather.data.CameraAhead
import com.charlie.weather.data.CameraAlerts
import com.charlie.weather.data.CameraReportRepository
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.LocationProvider
import com.charlie.weather.data.ReportResult
import com.charlie.weather.data.RideTracker
import com.charlie.weather.data.RouteCamera
import com.charlie.weather.data.RouteData
import com.charlie.weather.data.SpeedCamera
import com.charlie.weather.data.SpeedCameraRepository
import com.charlie.weather.data.TravelMode
import com.charlie.weather.data.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 前景服務持續取得位置，有兩種模式：
 * - 騎乘中（有路線，[start]）：常駐通知顯示前方天氣與抵達時間；前方 20 分鐘內會遇到雨時另外跳出提醒
 *   （同一段雨只提醒一次）。開啟測速照相提醒時，路線前方 500 公尺內有測速照相也會提醒。抵達終點時停止。
 * - 行車提醒（沒有目的地，[startDrive]）：依行進方向提醒前方的測速照相；停留超過 20 分鐘自動結束。
 * 兩種模式都可以從通知「回報測速」分享移動式測速，並可用語音播報。按「結束」時停止。
 * 路線資料只放在記憶體；程序被系統回收後服務不會自行恢復。
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: AppSettings
    private var loops: Job? = null
    private var lastLocation: Location? = null
    private var alertedPlace: String? = null
    private var lastStatusAt = 0L
    private var listener: LocationListener? = null

    /** 已提醒過的測速照相與提醒時間（同一處 [REALERT_MS] 內不重複） */
    private val alertedCameras = mutableMapOf<String, Long>()

    /** 行車提醒用的全部固定式測速照相 */
    private var fixedCameras: List<SpeedCamera> = emptyList()

    /** 使用者回報的移動式測速（定期更新） */
    private var mobileCameras: List<SpeedCamera> = emptyList()

    /** 騎乘中：路線上的測速照相（固定式加上最新的移動式回報） */
    private var routeCameras: List<RouteCamera> = emptyList()

    /** 行車提醒：最後一次移動超過 [STILL_KM] 的位置與時間，用來判斷停下來了 */
    private var anchor: Location? = null
    private var movedAt = 0L
    private var lastCamera: String? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val repository get() = WeatherRepository.get(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRide()
                return START_NOT_STICKY
            }
            ACTION_REPORT -> {
                if (listener == null) stopSelf() else scope.launch { report() }
                return START_NOT_STICKY
            }
        }
        if (route == null && !drive) {
            stopRide()
            return START_NOT_STICKY
        }
        WeatherNotifier.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val title = if (route != null) "沿途天氣提醒" else "行車提醒"
        try {
            ServiceCompat.startForeground(this, ID_ONGOING, ongoing(title, "正在取得位置…"), type)
        } catch (e: Exception) {
            // 沒有定位權限或系統不允許從背景啟動
            stopRide()
            return START_NOT_STICKY
        }
        _active.value = route != null
        _driving.value = route == null
        alertedPlace = null
        alertedCameras.clear()
        fixedCameras = emptyList()
        mobileCameras = emptyList()
        routeCameras = route?.cameras.orEmpty()
        anchor = null
        movedAt = System.currentTimeMillis()
        lastCamera = null
        lastStatusAt = 0L
        if (settings.voiceAlerts) initSpeech()
        // 切換模式時重新開始定位（間隔可能不同）與背景工作
        stopLocationUpdates()
        startLocationUpdates()
        loops?.cancel()
        loops = scope.launch {
            launch { refreshLoop() }
            launch { cameraLoop() }
        }
        return START_NOT_STICKY
    }

    /**
     * 載入測速照相：騎乘中補上設定開啟後才查的路線（或查詢時沒抓到），行車提醒載入全部；
     * 之後每 [MOBILE_MS] 更新一次移動式測速的回報。有測速照相後改用較頻繁的定位。
     */
    private suspend fun cameraLoop() {
        val data = route
        val needCameras = if (data != null) {
            data.mode != TravelMode.WALK && data.mode != TravelMode.BIKE && settings.speedCameras
        } else {
            true
        }
        if (!needCameras) return
        val hadCameras = routeCameras.isNotEmpty()
        if (data == null) {
            fixedCameras = withContext(Dispatchers.IO) { repository.allSpeedCameras() }
        } else if (data.cameras.isEmpty()) {
            val fixed = withContext(Dispatchers.IO) { repository.speedCameras() }
            val along = withContext(Dispatchers.Default) { SpeedCameraRepository.along(fixed, data.path) }
            route = route?.copy(cameras = along)
            routeCameras = along
        }
        if (!hadCameras && (fixedCameras.isNotEmpty() || routeCameras.isNotEmpty())) {
            stopLocationUpdates()
            startLocationUpdates()
        }
        while (scope.isActive) {
            refreshMobile()
            delay(MOBILE_MS)
        }
    }

    private suspend fun refreshMobile() {
        val reports = withContext(Dispatchers.IO) { repository.cameraReports.recent() }
        mobileCameras = reports
        val data = route ?: return
        val fixed = data.cameras.filterNot { it.camera.mobile }
        val mobile = withContext(Dispatchers.Default) { SpeedCameraRepository.along(reports, data.path) }
        val combined = (fixed + mobile).sortedBy { it.fraction }
        if (combined.isNotEmpty() && routeCameras.isEmpty()) {
            routeCameras = combined
            stopLocationUpdates()
            startLocationUpdates()
        } else {
            routeCameras = combined
        }
    }

    private fun stopLocationUpdates() {
        listener?.let { l -> getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
    }

    /**
     * 騎乘中：沒有新位置時也每 2 分鐘重算（時間在走），每 10 分鐘更新雷達短時預報。
     * 行車提醒：更新常駐通知，停留太久就結束。
     */
    private suspend fun refreshLoop() {
        var lastRadar = 0L
        while (scope.isActive) {
            if (route != null && System.currentTimeMillis() - lastRadar >= RADAR_MS) {
                val radar = withContext(Dispatchers.IO) { repository.nowcast() }
                route = route?.copy(nowcast = radar ?: route?.nowcast)
                lastRadar = System.currentTimeMillis()
            }
            if (route == null && System.currentTimeMillis() - movedAt >= STILL_MS) {
                notify(
                    ID_REPORT,
                    NotificationCompat.Builder(this, WeatherNotifier.CHANNEL_RIDE)
                        .setSmallIcon(R.drawable.ic_stat_weather)
                        .setContentTitle("行車提醒已結束")
                        .setContentText("已停留超過 ${STILL_MS / 60_000} 分鐘")
                        .setContentIntent(openApp())
                        .setAutoCancel(true)
                        .setTimeoutAfter(10 * 60_000L)
                        .build(),
                )
                stopRide()
                return
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
                trackMovement(location)
                checkCameras(location)
                // 有測速照相時定位很頻繁，天氣不必每次都重算
                if (System.currentTimeMillis() - lastStatusAt >= STATUS_MS) update()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        // 要提醒測速照相時需要每幾秒一次的位置，否則 20 秒、100 公尺一次就夠
        val cameras = route == null || routeCameras.isNotEmpty()
        val interval = if (cameras) CAMERA_LOCATION_MS else LOCATION_MS
        val distance = if (cameras) CAMERA_LOCATION_M else LOCATION_M
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { provider ->
                runCatching { lm.requestLocationUpdates(provider, interval, distance, l, Looper.getMainLooper()) }
            }
        listener = l
    }

    private fun trackMovement(location: Location) {
        val last = anchor
        if (last == null || last.distanceTo(location) >= STILL_KM * 1000) {
            anchor = location
            movedAt = System.currentTimeMillis()
        }
    }

    private fun speedKmh(location: Location): Int? =
        if (location.hasSpeed()) (location.speed * 3.6).roundToInt() else null

    /** 前方有測速照相時提醒；騎乘中偏離路線時不提醒（可能在別條路上） */
    private fun checkCameras(location: Location) {
        val position = LatLon(location.latitude, location.longitude)
        val speed = speedKmh(location)
        val data = route
        val found: CameraAhead? = if (data != null) {
            if (routeCameras.isEmpty()) return
            val progress = RideTracker.progress(data.path, position)
            if (progress.offRouteKm > CAMERA_OFF_ROUTE_KM) return
            CameraAlerts.onRoute(routeCameras, data.path.distanceKm, progress.fraction)
        } else {
            // 停著或很慢時 GPS 的方位不可靠
            val bearing = location.bearing.toDouble().takeIf { location.hasBearing() && (speed ?: 0) >= MIN_BEARING_KMH }
            CameraAlerts.ahead(fixedCameras + mobileCameras, position, bearing)
        }
        val ahead = found ?: return
        val key = CameraAlerts.key(ahead.camera)
        val now = System.currentTimeMillis()
        if (alertedCameras[key]?.let { now - it < REALERT_MS } == true) return
        alertedCameras[key] = now
        val title = CameraAlerts.title(ahead)
        val text = CameraAlerts.detail(ahead, speed)
        notify(
            ID_CAMERA,
            NotificationCompat.Builder(this, WeatherNotifier.CHANNEL_CAMERA)
                .setSmallIcon(R.drawable.ic_stat_weather)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setTimeoutAfter(CAMERA_NOTIFICATION_MS)
                .build(),
        )
        speak(CameraAlerts.speech(ahead, speed))
        if (route == null) {
            lastCamera = "%02d:%02d %s".format(Locale.US, LocalDateTime.now().hour, LocalDateTime.now().minute, title.removePrefix("📷 "))
            update()
        }
    }

    private fun update() {
        val location = lastLocation
        lastStatusAt = System.currentTimeMillis()
        val data = route
        if (data == null) {
            if (!drive) return
            val text = buildString {
                append(if (location == null) "正在取得位置…" else "前方 ${(CameraAlerts.ALERT_KM * 1000).toInt()} 公尺內有測速照相時提醒")
                if (fixedCameras.isEmpty() && mobileCameras.isEmpty() && location != null) append("（測速照相資料下載中或無法下載）")
                lastCamera?.let { append("\n上一處：$it") }
            }
            notify(ID_ONGOING, ongoing("行車提醒中", text))
            return
        }
        location ?: return
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
                speak(status.title)
            }
        } else if (rain == null) {
            // 雨過了或預報改變，之後再遇到雨可以再提醒
            alertedPlace = null
        }
        if (status.arrived) stopRide()
    }

    /** 回報目前位置有移動式測速 */
    private suspend fun report() {
        val location = lastLocation
        val result = if (location == null) {
            null
        } else {
            val speed = speedKmh(location) ?: 0
            val heading = location.bearing.toDouble().takeIf { location.hasBearing() && speed >= MIN_BEARING_KMH }
            repository.cameraReports.report(LatLon(location.latitude, location.longitude), heading)
        }
        val text = when (result) {
            null -> "還沒有取得位置，請稍後再試"
            ReportResult.SENT -> "已回報，${CameraReportRepository.TTL_MS / 3_600_000} 小時內會提醒經過的人"
            ReportResult.DUPLICATE -> "附近已經有人回報了"
            ReportResult.TOO_SOON -> "剛回報過，請稍後再試"
            ReportResult.UNAVAILABLE -> "這個版本沒有開啟回報功能"
            ReportResult.FAILED -> "回報失敗，請檢查網路"
        }
        notify(
            ID_REPORT,
            NotificationCompat.Builder(this, WeatherNotifier.CHANNEL_RIDE)
                .setSmallIcon(R.drawable.ic_stat_weather)
                .setContentTitle("回報移動式測速")
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000L)
                .build(),
        )
        speak(if (result == ReportResult.SENT) "已回報測速" else text)
        if (result == ReportResult.SENT) refreshMobile()
    }

    private fun initSpeech() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            if (engine.setLanguage(Locale.TAIWAN) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.CHINESE)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || !settings.voiceAlerts) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ride_${System.nanoTime()}")
    }

    private fun ongoing(title: String, text: String): Notification {
        val builder = NotificationCompat.Builder(this, WeatherNotifier.CHANNEL_RIDE)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        if (repository.cameraReports.available && (route == null || settings.speedCameras)) {
            builder.addAction(
                0, "回報測速",
                PendingIntent.getService(
                    this, 2, Intent(this, RideService::class.java).setAction(ACTION_REPORT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder
            .addAction(
                0, "結束",
                PendingIntent.getService(
                    this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

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
        stopLocationUpdates()
        loops?.cancel()
        loops = null
        route = null
        drive = false
        _active.value = false
        _driving.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        scope.cancel()
        tts?.shutdown()
        tts = null
        _active.value = false
        _driving.value = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.charlie.weather.ride.STOP"
        private const val ACTION_REPORT = "com.charlie.weather.ride.REPORT"
        private const val ID_ONGOING = 5001
        private const val ID_ALERT = 5002
        private const val ID_CAMERA = 5003
        private const val ID_REPORT = 5004
        private const val STATUS_MS = 15_000L
        private const val CAMERA_LOCATION_MS = 2_000L
        private const val CAMERA_LOCATION_M = 10f
        private const val CAMERA_OFF_ROUTE_KM = 0.1
        private const val CAMERA_NOTIFICATION_MS = 90_000L

        /** 同一處測速照相隔這麼久才會再提醒（繞回來再經過） */
        private const val REALERT_MS = 10 * 60_000L
        private const val MOBILE_MS = 3 * 60_000L

        /** 低於這個時速時 GPS 方位不可靠 */
        private const val MIN_BEARING_KMH = 8

        /** 行車提醒：在 [STILL_KM] 公里內停留 [STILL_MS] 就自動結束 */
        private const val STILL_KM = 0.2
        private const val STILL_MS = 20 * 60_000L
        private const val REFRESH_MS = 2 * 60_000L
        private const val RADAR_MS = 10 * 60_000L
        private const val LOCATION_MS = 20_000L
        private const val LOCATION_M = 100f
        private const val ALERT_MINUTES = 20L

        @Volatile
        private var route: RouteData? = null

        @Volatile
        private var drive = false

        private val _active = MutableStateFlow(false)
        private val _driving = MutableStateFlow(false)

        /** 騎乘中模式是否進行中（路線畫面用來切換按鈕） */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        /** 行車提醒（沒有目的地）是否進行中 */
        val driving: StateFlow<Boolean> = _driving.asStateFlow()

        fun start(context: Context, data: RouteData) {
            route = data
            drive = false
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }

        /** 沒有目的地的行車提醒：只提醒測速照相 */
        fun startDrive(context: Context) {
            route = null
            drive = true
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
