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
import com.charlie.weather.data.Weather
import com.charlie.weather.ui.WeatherCodes
import com.charlie.weather.ui.conditionText
import com.charlie.weather.ui.deg
import com.charlie.weather.ui.hourLabel
import java.time.LocalDate
import java.time.LocalDateTime

/** 降雨提醒、天氣特報與每日早晨天氣通知。 */
object WeatherNotifier {
    private const val CHANNEL_RAIN = "rain"
    private const val CHANNEL_WARNING = "warning"
    private const val CHANNEL_DAILY = "daily"

    private const val ID_RAIN = 1001
    private const val ID_MORNING = 1002
    private const val ID_WARNING_BASE = 2000

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
                    description = "每天早上的今日天氣摘要"
                },
            ),
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun check(context: Context, city: City, weather: Weather, now: LocalDateTime = LocalDateTime.now()) {
        if (!canNotify(context)) return
        createChannels(context)
        val settings = AppSettings(context)
        if (settings.rainAlerts) checkRain(context, settings, city, weather)
        if (settings.warningAlerts) checkWarnings(context, settings, city, weather)
        if (settings.morningSummary) checkMorning(context, settings, city, weather, now)
    }

    /** 目前沒下雨，但未來 2 小時內降雨機率 ≥ 60% 時提醒（3 小時內不重複）。 */
    private fun checkRain(context: Context, settings: AppSettings, city: City, weather: Weather) {
        val current = weather.current
        if (WeatherCodes.isRain(current.weatherCode) && current.precipitation > 0) return
        if (System.currentTimeMillis() - settings.lastRainNotifiedAt < RAIN_COOLDOWN_MS) return
        val soon = weather.hourly
            .filter { it.time.isAfter(current.time) && !it.time.isAfter(current.time.plusHours(2)) }
            .firstOrNull { (it.precipitationProbability ?: 0) >= 60 && (WeatherCodes.isRain(it.weatherCode) || it.precipitation >= 0.2) }
            ?: return
        val pop = soon.precipitationProbability ?: 0
        notify(
            context, CHANNEL_RAIN, ID_RAIN,
            title = "${city.name}：即將下雨",
            text = "約${hourLabel(soon.time)}起可能${WeatherCodes.description(soon.weatherCode)}，降雨機率 $pop%。記得帶傘！",
        )
        settings.lastRainNotifiedAt = System.currentTimeMillis()
    }

    /** 所在縣市有新的天氣特報時通知（同一則特報只通知一次）。 */
    private fun checkWarnings(context: Context, settings: AppSettings, city: City, weather: Weather) {
        val alerts = weather.cwa?.alerts.orEmpty()
        val keys = alerts.associateBy { "${it.title}|${it.start}" }
        val notified = settings.notifiedWarnings
        keys.filterKeys { it !in notified }.forEach { (key, alert) ->
            val end = alert.end?.let { " · 至 ${it.monthValue}/${it.dayOfMonth} ${com.charlie.weather.ui.timeLabel(it)}" }.orEmpty()
            notify(
                context, CHANNEL_WARNING, ID_WARNING_BASE + (key.hashCode() and 0xfff),
                title = "⚠️ ${weather.cwa?.county ?: city.name}${alert.title}",
                text = "中央氣象署發布${alert.title}$end",
            )
        }
        // 只保留仍有效的特報，讓同名特報下次重新發布時能再通知
        settings.notifiedWarnings = keys.keys
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
            title = "${city.name} 今日天氣 ${weather.current.temperature.deg()}",
            text = "${day.description ?: WeatherCodes.description(day.weatherCode)}，${day.temperatureMin.deg()}～${day.temperatureMax.deg()}$pop。" +
                "目前${weather.current.conditionText()}。$alerts",
        )
        settings.lastMorningDate = today
    }

    private fun notify(context: Context, channel: String, id: Int, title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
