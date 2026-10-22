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
import android.widget.Toast
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
import com.charlie.weather.data.Commute
import com.charlie.weather.data.JourneyStore
import com.charlie.weather.data.LatLon
import com.charlie.weather.data.LocationProvider
import com.charlie.weather.data.RainNowcast
import com.charlie.weather.data.ReportResult
import com.charlie.weather.data.LiveRide
import com.charlie.weather.data.RideTracker
import com.charlie.weather.data.RouteCamera
import com.charlie.weather.data.RouteData
import com.charlie.weather.data.SpeedCamera
import com.charlie.weather.data.SpeedCameraRepository
import com.charlie.weather.data.Track
import com.charlie.weather.data.TrackPoint
import com.charlie.weather.data.WeatherSample
import com.charlie.weather.data.TravelMode
import com.charlie.weather.data.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

/**
 * 前景服務持續取得位置，有兩種模式：
 * - 騎乘中（有路線，[start]）：常駐通知顯示前方天氣與抵達時間；前方 20 分鐘內會遇到雨時另外跳出提醒
 *   （同一段雨只提醒一次）。開啟測速照相提醒時，路線前方 500 公尺內有測速照相也會提醒。抵達終點時停止。
 * - 行車提醒（沒有目的地，[startDrive]）：依行進方向提醒前方的測速照相；停留超過 20 分鐘自動結束。
 * 兩種模式都可以從通知「回報測速」分享移動式測速，並可用語音播報。按「結束」時停止。
 * 開啟足跡紀錄時（預設開），從開始到結束的軌跡會存成一趟足跡（[JourneyStore]），切換模式不會中斷。
 * 路線資料只放在記憶體；程序被系統回收後服務不會自行恢復。
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: AppSettings
    private var loops: Job? = null
    private var lastLocation: Location? = null

    /** 最近幾秒的 GPS 位置，用來自己算時速（模擬定位或部分手機回報的速度不可靠） */
    private val recentFixes = ArrayDeque<Location>()
    private var lastGpsAt = 0L
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

    /** 最近一次測速照相提醒的標題、內文與時間（App 內騎乘中畫面顯示） */
    private var cameraAlert: Pair<String, String>? = null
    private var cameraAlertAt = 0L

    /** 足跡紀錄：還沒寫入檔案的點、上一個記下的點與目前里程 */
    private var recording = false
    private var recordJob: Job? = null
    private val pendingPoints = mutableListOf<TrackPoint>()
    private var lastTrackPoint: TrackPoint? = null
    private var recordedKm = 0.0
    private val weatherSamples = mutableListOf<WeatherSample>()

    /** 行車提醒記錄足跡時用的雷達短時預報（騎乘中用路線資料裡的） */
    private var driveRadar: RainNowcast? = null
    private val journeys by lazy { JourneyStore(this) }

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
                val fromApp = intent.getBooleanExtra(EXTRA_FROM_APP, false)
                if (listener == null) stopSelf() else scope.launch { report(fromApp) }
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
        cameraAlert = null
        _live.value = LiveRide(
            routeName = route?.let { "${it.from.displayName} → ${it.to.displayName}" },
            title = if (route != null) "騎乘中" else "行車提醒中",
            detail = "正在取得位置…",
        )
        alertedPlace = null
        alertedCameras.clear()
        recentFixes.clear()
        lastGpsAt = 0L
        fixedCameras = emptyList()
        mobileCameras = emptyList()
        routeCameras = route?.cameras.orEmpty()
        anchor = null
        movedAt = System.currentTimeMillis()
        lastCamera = null
        lastStatusAt = 0L
        if (settings.voiceAlerts) initSpeech()
        if (!recording && settings.recordJourneys) startRecording()
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
        val needCameras = settings.speedCameras && (data == null || (data.mode != TravelMode.WALK && data.mode != TravelMode.BIKE))
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
            } else if (route == null && recording && System.currentTimeMillis() - lastRadar >= RADAR_MS) {
                driveRadar = withContext(Dispatchers.IO) { repository.nowcast() } ?: driveRadar
                lastRadar = System.currentTimeMillis()
            }
            if (route == null) sampleDriveWeather()
            if (route == null && System.currentTimeMillis() - movedAt >= STILL_MS) {
                if (settings.rideEndNotices) notify(
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
                val now = System.currentTimeMillis()
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    lastGpsAt = now
                    recentFixes.addLast(location)
                    while (recentFixes.size > 2 && location.time - recentFixes.first().time > SPEED_WINDOW_MS) recentFixes.removeFirst()
                } else if (now - lastGpsAt < GPS_FRESH_MS) {
                    // 有 GPS 時不用網路定位（精度差，混在一起會讓位置跳動）
                    return
                }
                lastLocation = location
                speedKmh(location)?.let { speed -> _live.update { it?.copy(speedKmh = speed) } }
                record(location)
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
        // 要提醒測速照相或記錄足跡時需要每幾秒一次的位置，否則 20 秒、100 公尺一次就夠
        val frequent = recording || (route == null && settings.speedCameras) || routeCameras.isNotEmpty()
        val interval = if (frequent) CAMERA_LOCATION_MS else LOCATION_MS
        val distance = if (frequent) CAMERA_LOCATION_M else LOCATION_M
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { provider ->
                runCatching { lm.requestLocationUpdates(provider, interval, distance, l, Looper.getMainLooper()) }
            }
        listener = l
    }

    private fun startRecording() {
        recording = true
        pendingPoints.clear()
        lastTrackPoint = null
        recordedKm = 0.0
        weatherSamples.clear()
        driveRadar = null
        recordJob = scope.launch {
            withContext(Dispatchers.IO) { journeys.begin() }
            while (isActive) {
                delay(FLUSH_MS)
                flushPoints()
                update()
            }
        }
    }

    /** 把精度夠、不是跳點的位置加進足跡 */
    private fun record(location: Location) {
        if (!recording) return
        val point = TrackPoint(location.time, location.latitude, location.longitude, location.accuracy.takeIf { location.hasAccuracy() })
        val previous = lastTrackPoint
        if (!Track.accept(previous, point)) return
        if (previous != null) recordedKm += Track.distanceKm(previous.position, point.position)
        lastTrackPoint = point
        pendingPoints += point
    }

    private suspend fun flushPoints() {
        if (pendingPoints.isEmpty()) return
        val batch = pendingPoints.toList()
        pendingPoints.clear()
        withContext(Dispatchers.IO) { journeys.append(batch) }
    }

    /** 結束足跡紀錄：服務停止後仍在背景整理存檔，存好時通知 */
    private fun finishRecording() {
        if (!recording) return
        recording = false
        val job = recordJob
        recordJob = null
        val batch = pendingPoints.toList()
        pendingPoints.clear()
        val weather = weatherSamples.toList()
        weatherSamples.clear()
        val store = journeys
        val app = applicationContext
        val notifySaved = settings.rideEndNotices
        saveScope.launch {
            job?.cancelAndJoin()
            store.append(batch)
            val journey = store.finish(weather) ?: return@launch
            JourneyStore.announce(journey)
            // App 開著時有「足跡已儲存」的對話框；通知可以在設定關掉
            if (!notifySaved) return@launch
            val text = "%s · %.1f 公里 · %d 分鐘".format(Locale.US, journey.title, journey.distanceKm, journey.durationMinutes)
            try {
                NotificationManagerCompat.from(app).notify(
                    ID_JOURNEY,
                    NotificationCompat.Builder(app, WeatherNotifier.CHANNEL_RIDE)
                        .setSmallIcon(R.drawable.ic_stat_weather)
                        .setContentTitle("足跡已儲存")
                        .setContentText(text)
                        .setContentIntent(
                            PendingIntent.getActivity(
                                app, 3,
                                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            ),
                        )
                        .setAutoCancel(true)
                        .build(),
                )
            } catch (e: SecurityException) {
                // 沒有通知權限
            }
        }
    }

    private fun trackMovement(location: Location) {
        val last = anchor
        if (last == null || last.distanceTo(location) >= STILL_KM * 1000) {
            anchor = location
            movedAt = System.currentTimeMillis()
        }
    }

    /**
     * 目前時速：取「定位回報的速度」與「最近幾秒移動距離換算的速度」較低者，
     * 避免回報速度異常時誤提醒「請減速」。
     */
    private fun speedKmh(location: Location): Int? {
        val first = recentFixes.firstOrNull()
        val last = recentFixes.lastOrNull()
        val seconds = if (first != null && last != null) (last.time - first.time) / 1000.0 else 0.0
        return CameraAlerts.speedKmh(
            reportedKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
            meters = if (first != null && last != null) first.distanceTo(last).toDouble() else null,
            seconds = seconds,
        )
    }

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
        cameraAlert = title to text
        cameraAlertAt = now
        _live.update { it?.copy(camera = title, cameraDetail = text) }
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
                when {
                    !settings.speedCameras -> append(if (recording) "測速照相提醒已關閉，只記錄足跡" else "測速照相提醒已關閉（設定 → 騎乘時）")
                    location == null -> append("正在取得位置…")
                    else -> append("前方 ${(CameraAlerts.ALERT_KM * 1000).toInt()} 公尺內有測速照相時提醒")
                }
                if (settings.speedCameras && fixedCameras.isEmpty() && mobileCameras.isEmpty() && location != null) append("（測速照相資料下載中或無法下載）")
                lastCamera?.let { append("\n上一處：$it") }
            }
            notify(ID_ONGOING, ongoing("行車提醒中", text))
            publish("行車提醒中", text)
            return
        }
        location ?: return
        val now = LocalDateTime.now()
        val progress = RideTracker.progress(data.path, LatLon(location.latitude, location.longitude))
        val status = RideTracker.status(data, progress, now)
        notify(ID_ONGOING, ongoing(status.title, status.text))
        publish(status.title, status.text)
        sampleWeather(data, progress.fraction, location, now)
        val rain = status.rainAhead
        val minutes = status.minutesToRain
        if (rain != null && minutes != null && minutes <= ALERT_MINUTES) {
            val key = rain.place ?: rain.point.distanceKm.toInt().toString()
            if (key != alertedPlace && settings.rideRainAlerts) {
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

    /** 更新 App 內的進行中橫條與騎乘中畫面 */
    private fun publish(title: String, text: String) {
        val camera = cameraAlert?.takeIf { System.currentTimeMillis() - cameraAlertAt < CAMERA_NOTIFICATION_MS }
        _live.value = LiveRide(
            routeName = route?.let { "${it.from.displayName} → ${it.to.displayName}" },
            title = title,
            detail = text,
            speedKmh = lastLocation?.let(::speedKmh),
            camera = camera?.first,
            cameraDetail = camera?.second,
            recordedKm = recordedKm.takeIf { recording },
        )
    }

    /** 足跡記錄中：每 [WEATHER_SAMPLE_MS] 記一次所在位置的天氣（用已查好的預報與雷達） */
    private fun sampleWeather(data: RouteData, fraction: Double, location: Location, now: LocalDateTime) {
        if (!recording) return
        val time = System.currentTimeMillis()
        if (weatherSamples.lastOrNull()?.let { time - it.time < WEATHER_SAMPLE_MS } == true) return
        val stop = RideTracker.ahead(data, fraction, now).stops.firstOrNull() ?: return
        val radarMm = data.nowcast?.takeIf { it.covers(now, now) }?.at(location.latitude, location.longitude)
        val mm = radarMm ?: stop.nowcastMm ?: stop.hour?.precipitation
        val wet = if (radarMm != null) stop.rainingNow || radarMm >= RainNowcast.WET_MM else stop.wet
        weatherSamples += WeatherSample(
            time = time,
            position = LatLon(location.latitude, location.longitude),
            temperature = stop.hour?.temperature,
            weatherCode = stop.hour?.weatherCode,
            rainMm = mm,
            wet = wet,
            place = stop.place,
        )
    }

    /**
     * 行車提醒記錄足跡時的天氣：「我的位置」已快取的逐時預報（背景約 30 分鐘更新）加上
     * 每 10 分鐘的雷達短時預報，不另外查預報。
     */
    private suspend fun sampleDriveWeather() {
        if (!recording) return
        val location = lastLocation ?: return
        val time = System.currentTimeMillis()
        if (weatherSamples.lastOrNull()?.let { time - it.time < WEATHER_SAMPLE_MS } == true) return
        val now = LocalDateTime.now()
        val hour = withContext(Dispatchers.Default) { repository.primaryCity()?.let { repository.cached(it) } }
            ?.hourly?.lastOrNull { !it.time.isAfter(now) }
        val radarMm = driveRadar?.takeIf { it.covers(now, now) }?.at(location.latitude, location.longitude)
        if (hour == null && radarMm == null) return
        weatherSamples += WeatherSample(
            time = time,
            position = LatLon(location.latitude, location.longitude),
            temperature = hour?.temperature,
            weatherCode = hour?.weatherCode,
            rainMm = radarMm ?: hour?.precipitation,
            wet = if (radarMm != null) radarMm >= RainNowcast.WET_MM else hour?.let(Commute::isWet) == true,
        )
    }

    /** 回報目前位置有移動式測速；從 App 按的時候另外跳出 Toast */
    private suspend fun report(fromApp: Boolean = false) {
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
            ReportResult.FAILED -> "回報失敗：" + (repository.cameraReports.lastError ?: "請檢查網路")
        }
        if (fromApp) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
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
            .setSubText(if (recording) "足跡記錄中 · %.1f 公里".format(Locale.US, recordedKm) else null)
            .setContentIntent(openApp())
        if (repository.cameraReports.available && settings.speedCameras) {
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
        finishRecording()
        loops?.cancel()
        loops = null
        route = null
        drive = false
        _active.value = false
        _driving.value = false
        _live.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        // 被系統結束時：已收到的點先寫入，下次開啟 App 時補存成一趟足跡
        if (recording) {
            journeys.append(pendingPoints.toList())
            pendingPoints.clear()
            recordJob?.cancel()
            recording = false
        }
        scope.cancel()
        tts?.shutdown()
        tts = null
        _active.value = false
        _driving.value = false
        _live.value = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.charlie.weather.ride.STOP"
        private const val ACTION_REPORT = "com.charlie.weather.ride.REPORT"
        private const val ID_ONGOING = 5001
        private const val ID_ALERT = 5002
        private const val ID_CAMERA = 5003
        private const val ID_REPORT = 5004
        private const val ID_JOURNEY = 5005

        /** 足跡的定位點每隔這麼久寫入檔案一次 */
        private const val FLUSH_MS = 30_000L

        /** 足跡的天氣每隔這麼久記一次 */
        private const val WEATHER_SAMPLE_MS = 3 * 60_000L
        private const val STATUS_MS = 15_000L
        private const val CAMERA_LOCATION_MS = 2_000L
        private const val CAMERA_LOCATION_M = 10f
        private const val CAMERA_OFF_ROUTE_KM = 0.1
        private const val CAMERA_NOTIFICATION_MS = 90_000L

        /** 同一處測速照相隔這麼久才會再提醒（繞回來再經過） */
        private const val REALERT_MS = 10 * 60_000L
        private const val MOBILE_MS = 3 * 60_000L

        private const val EXTRA_FROM_APP = "from_app"

        /** 自己算時速用的時間範圍 */
        private const val SPEED_WINDOW_MS = 6_000L

        /** 這段時間內有 GPS 定位就不用網路定位 */
        private const val GPS_FRESH_MS = 10_000L

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

        /** 存足跡用，服務停止後仍要把檔案整理完 */
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        private val _live = MutableStateFlow<LiveRide?>(null)

        /** 進行中時的即時狀態（進行中橫條、騎乘中畫面）；沒有進行中時為 null */
        val live: StateFlow<LiveRide?> = _live.asStateFlow()

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

        /** 從 App 內回報移動式測速（騎乘中或行車提醒進行中才有位置與行進方向） */
        fun report(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_REPORT).putExtra(EXTRA_FROM_APP, true))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
