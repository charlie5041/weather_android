package com.charlie.weather.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.charlie.weather.MainActivity
import com.charlie.weather.R
import com.charlie.weather.data.AppSettings
import com.charlie.weather.data.City
import com.charlie.weather.data.CityStore
import com.charlie.weather.data.Commute
import com.charlie.weather.data.RoutePlanner
import com.charlie.weather.data.TravelMode
import com.charlie.weather.data.Weather
import com.charlie.weather.data.WeatherRepository
import com.charlie.weather.ui.WeatherCodes
import com.charlie.weather.ui.clock
import com.charlie.weather.ui.conditionText
import com.charlie.weather.ui.deg
import com.charlie.weather.ui.hazardLine
import com.charlie.weather.ui.hourLabel
import com.charlie.weather.ui.modeEmoji
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

typealias CityWeather = Pair<City, Weather>

/** 降雨提醒、天氣特報與每日早晨天氣通知。 */
object WeatherNotifier {
    private const val CHANNEL_RAIN = "rain"
    private const val CHANNEL_WARNING = "warning"
    private const val CHANNEL_DAILY = "daily"
    private const val CHANNEL_ROUTE = "route"

    /** 騎乘中的常駐通知與前方降雨提醒 */
    const val CHANNEL_RIDE = "ride"
    const val CHANNEL_RIDE_ALERT = "ride_alert"
    const val CHANNEL_CAMERA = "speed_camera"

    /** 通知帶的常用路線 id，開啟 App 時直接開這條路線 */
    const val EXTRA_FAVORITE_ROUTE = "favorite_route_id"

    private const val ID_RAIN = 1001
    private const val ID_MORNING = 1002
    private const val ID_COMMUTE = 1003
    private const val ID_PLACE_RAIN_BASE = 3000
    private const val ID_WARNING_BASE = 2000
    private const val ID_ROUTE_BASE = 4000

    private const val RAIN_COOLDOWN_MS = 3 * 60 * 60_000L

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RAIN, "降雨提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "未來 2 小時內可能下雨時通知"
                },
                NotificationChannel(CHANNEL_WARNING, "天氣特報", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "中央氣象署發布所在縣市的天氣特報時通知"
                },
                NotificationChannel(CHANNEL_DAILY, "每日天氣", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "每天早上的今日天氣摘要與通勤天氣"
                },
                NotificationChannel(CHANNEL_ROUTE, "常用路線", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "常用路線出發前的沿路天氣"
                },
                NotificationChannel(CHANNEL_RIDE, "騎乘中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "騎乘中模式的常駐通知：前方天氣與抵達時間"
                },
                NotificationChannel(CHANNEL_RIDE_ALERT, "前方降雨", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "騎乘中前方 20 分鐘內會遇到雨時通知"
                },
                NotificationChannel(CHANNEL_CAMERA, "測速照相", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "騎乘中接近固定式測速照相時通知"
                },
            ),
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 檢查是否需要通知。[primary] 是城市列表第一個城市；[places] 是其他自訂地點（住家、公司…），
     * 開啟「自訂地點提醒」時也會檢查降雨與特報，並用來計算通勤天氣。
     */
    fun check(
        context: Context,
        primary: CityWeather,
        places: List<CityWeather> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        if (!canNotify(context)) return
        createChannels(context)
        val settings = AppSettings(context)
        val alertTargets = listOf(primary) + if (settings.placeAlerts) places else emptyList()
        if (settings.rainAlerts) alertTargets.forEach { (city, weather) -> checkRain(context, settings, city, weather, city.id == primary.first.id) }
        if (settings.warningAlerts) checkWarnings(context, settings, alertTargets)
        if (settings.morningSummary) checkMorning(context, settings, primary.first, primary.second, now)
        if (settings.commuteNotify) checkCommute(context, settings, listOf(primary) + places, now)
    }

    /** 目前沒下雨，但未來 2 小時內降雨機率 ≥ 60% 時提醒（每個地點 3 小時內不重複）。 */
    private fun checkRain(context: Context, settings: AppSettings, city: City, weather: Weather, isPrimary: Boolean) {
        val current = weather.current
        if (WeatherCodes.isRain(current.weatherCode) && current.precipitation > 0) return
        if (System.currentTimeMillis() - settings.lastRainNotifiedAt(city.id) < RAIN_COOLDOWN_MS) return
        val soon = weather.hourly
            .filter { it.time.isAfter(current.time) && !it.time.isAfter(current.time.plusHours(2)) }
            .firstOrNull { (it.precipitationProbability ?: 0) >= 60 && (WeatherCodes.isRain(it.weatherCode) || it.precipitation >= 0.2) }
            ?: return
        val pop = soon.precipitationProbability ?: 0
        val heavy = when {
            WeatherCodes.isThunder(soon.weatherCode) -> "即將有雷雨"
            soon.precipitation >= 10 -> "即將下大雨"
            else -> "即將下雨"
        }
        notify(
            context, CHANNEL_RAIN, if (isPrimary) ID_RAIN else ID_PLACE_RAIN_BASE + (city.id.hashCode() and 0xfff),
            title = "${city.displayName}：$heavy",
            text = "約${hourLabel(soon.time)}起可能${WeatherCodes.description(soon.weatherCode)}，降雨機率 $pop%。記得帶傘！",
        )
        settings.setLastRainNotifiedAt(city.id, System.currentTimeMillis())
    }

    /** 各地點所在縣市有新的天氣特報時通知（同縣市、同一則特報只通知一次）。 */
    private fun checkWarnings(context: Context, settings: AppSettings, targets: List<CityWeather>) {
        val notified = settings.notifiedWarnings
        val active = mutableSetOf<String>()
        targets.forEach { (city, weather) ->
            val county = weather.cwa?.county
            weather.cwa?.alerts.orEmpty().forEach { alert ->
                val legacyKey = "${alert.title}|${alert.start}"
                val key = "${county.orEmpty()}|$legacyKey"
                if (!active.add(key)) return@forEach
                if (key in notified || (city.id == targets.first().first.id && legacyKey in notified)) return@forEach
                val end = alert.end?.let { " · 至 ${it.monthValue}/${it.dayOfMonth} ${com.charlie.weather.ui.timeLabel(it)}" }.orEmpty()
                val place = if (city.label != null) "（${city.displayName}）" else ""
                notify(
                    context, CHANNEL_WARNING, ID_WARNING_BASE + (key.hashCode() and 0xfff),
                    title = "⚠️ ${county ?: city.displayName}${alert.title}$place",
                    text = "中央氣象署發布${alert.title}$end",
                )
            }
        }
        // 只保留仍有效的特報，讓同名特報下次重新發布時能再通知
        settings.notifiedWarnings = active
    }

    /** 出門前 90 分鐘內推送一次通勤天氣（住家 ↔ 公司／學校）。 */
    private fun checkCommute(context: Context, settings: AppSettings, all: List<CityWeather>, now: LocalDateTime) {
        val (home, work) = Commute.homeAndWork(all.map { it.first }) ?: return
        val weatherOf = all.associate { it.first.id to it.second }
        val mode = CityStore(context).loadLastRoute()?.mode ?: TravelMode.SCOOTER
        val trip = Commute.trip(
            home, work, weatherOf[home.id], weatherOf[work.id], now,
            settings.commuteMorningHour, settings.commuteEveningHour,
            travelMinutes = settings.lastCommuteMinutes(Commute.routeKey(home, work, mode)),
        )
        val minutes = java.time.Duration.between(now, trip.departure).toMinutes()
        if (minutes !in 0..90) return
        val key = trip.departure.toString()
        if (settings.lastCommuteNotified == key) return
        if (trip.fromHour == null && trip.toHour == null) return
        fun line(place: City, hour: com.charlie.weather.data.HourlyForecast?) = hour?.let {
            "${place.displayName} ${hourLabel(it.time)} ${WeatherCodes.description(it.weatherCode)} ${it.temperature.deg()}" +
                (it.precipitationProbability?.let { p -> "，降雨 $p%" }.orEmpty())
        } ?: "${place.displayName} 暫無資料"
        notify(
            context, CHANNEL_DAILY, ID_COMMUTE,
            title = "${trip.leg.label}通勤天氣：${trip.advice}",
            text = "${line(trip.from, trip.fromHour)}\n${line(trip.to, trip.toHour)}",
        )
        settings.lastCommuteNotified = key
    }

    /**
     * 常用路線在設定的出發時間前 90 分鐘內，查一次沿路天氣並通知結論與提醒
     * （每一趟只通知一次；查詢失敗就等下次背景更新再試）。
     */
    suspend fun checkFavoriteRoutes(context: Context, repo: WeatherRepository, now: LocalDateTime = LocalDateTime.now()) {
        if (!canNotify(context)) return
        val settings = AppSettings(context)
        val notified = settings.notifiedRouteReminders
        val due = repo.store.loadFavoriteRoutes()
            .mapNotNull { route -> route.reminder?.next(now)?.let { route to it } }
            .filter { (route, departure) -> Duration.between(now, departure).toMinutes() in 0..90 && "${route.id}|$departure" !in notified }
        if (due.isEmpty()) return
        createChannels(context)
        // 起終點是「目前位置」時用最後一次定位的座標
        val location = repo.store.loadLocationCity()
        fun latest(city: City) = if (city.isCurrentLocation) location ?: city else city
        val sent = mutableSetOf<String>()
        due.forEach { (route, departure) ->
            val forecast = try {
                val data = repo.routeData(latest(route.from), latest(route.to), route.mode, departure, route.via.map(::latest))
                RoutePlanner.evaluate(data, departure, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@forEach
            }
            val verdict = forecast.verdict
            val hazards = forecast.hazards.map { hazard -> hazardLine(hazard).let { (icon, text) -> "$icon $text" } }
            notify(
                context, CHANNEL_ROUTE, ID_ROUTE_BASE + (route.id.hashCode() and 0xfff),
                title = "${modeEmoji(route.mode)} ${route.name} ${clock(departure)} 出發：${verdict.title}",
                text = (listOf(verdict.detail) + hazards).joinToString("\n"),
                routeId = route.id,
            )
            sent += "${route.id}|$departure"
        }
        // 只留一天內的紀錄，集合不會越來越大
        settings.notifiedRouteReminders = (notified + sent).filter { key ->
            runCatching { LocalDateTime.parse(key.substringAfter('|')) }.getOrNull()?.isAfter(now.minusDays(1)) == true
        }.toSet()
    }

    /** 每天到了設定時間後的第一次背景更新時，發送今日天氣摘要。 */
    private fun checkMorning(context: Context, settings: AppSettings, city: City, weather: Weather, now: LocalDateTime) {
        val today = LocalDate.now().toString()
        if (settings.lastMorningDate == today) return
        if (now.hour < settings.morningHour || now.hour >= settings.morningHour + 4) return
        val day = weather.today ?: return
        val pop = day.precipitationProbability?.let { "，降雨機率 $it%" }.orEmpty()
        val alerts = weather.cwa?.alerts.orEmpty().joinToString("、") { it.title }.let { if (it.isEmpty()) "" else "\n⚠️ $it" }
        notify(
            context, CHANNEL_DAILY, ID_MORNING,
            title = "${city.displayName} 今日天氣 ${weather.current.temperature.deg()}",
            text = "${day.description ?: WeatherCodes.description(day.weatherCode)}，${day.temperatureMin.deg()}～${day.temperatureMax.deg()}$pop。" +
                "目前${weather.current.conditionText()}。$alerts",
        )
        settings.lastMorningDate = today
    }

    private fun notify(context: Context, channel: String, id: Int, title: String, text: String, routeId: String? = null) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        routeId?.let { intent.putExtra(EXTRA_FAVORITE_ROUTE, it) }
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // 使用者在背景期間撤銷了通知權限
        }
    }
}
