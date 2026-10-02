package com.charlie.weather.data

import android.content.Context

/** 使用者設定與通知去重用的狀態。 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var rainAlerts: Boolean
        get() = prefs.getBoolean("rain_alerts", true)
        set(value) = prefs.edit().putBoolean("rain_alerts", value).apply()

    var warningAlerts: Boolean
        get() = prefs.getBoolean("warning_alerts", true)
        set(value) = prefs.edit().putBoolean("warning_alerts", value).apply()

    var morningSummary: Boolean
        get() = prefs.getBoolean("morning_summary", true)
        set(value) = prefs.edit().putBoolean("morning_summary", value).apply()

    /** 早晨天氣通知的時間（小時，0–23） */
    var morningHour: Int
        get() = prefs.getInt("morning_hour", 7)
        set(value) = prefs.edit().putInt("morning_hour", value).apply()

    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean("asked_notification_permission", false)
        set(value) = prefs.edit().putBoolean("asked_notification_permission", value).apply()

    // ---- 通知去重 ----

    var lastRainNotifiedAt: Long
        get() = prefs.getLong("last_rain_notified_at", 0)
        set(value) = prefs.edit().putLong("last_rain_notified_at", value).apply()

    var lastMorningDate: String?
        get() = prefs.getString("last_morning_date", null)
        set(value) = prefs.edit().putString("last_morning_date", value).apply()

    var notifiedWarnings: Set<String>
        get() = prefs.getStringSet("notified_warnings", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("notified_warnings", value).apply()
}
