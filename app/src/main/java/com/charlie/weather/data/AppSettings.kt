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

    /** 自訂地點（住家、公司…）也發送降雨提醒與天氣特報 */
    var placeAlerts: Boolean
        get() = prefs.getBoolean("place_alerts", true)
        set(value) = prefs.edit().putBoolean("place_alerts", value).apply()

    /** 有「住家」與「公司／學校」時在主畫面顯示通勤時段預報 */
    var commuteCard: Boolean
        get() = prefs.getBoolean("commute_card", true)
        set(value) = prefs.edit().putBoolean("commute_card", value).apply()

    /** 出門前推送通勤天氣 */
    var commuteNotify: Boolean
        get() = prefs.getBoolean("commute_notify", true)
        set(value) = prefs.edit().putBoolean("commute_notify", value).apply()

    /** 上班出發時間（小時） */
    var commuteMorningHour: Int
        get() = prefs.getInt("commute_morning_hour", 8)
        set(value) = prefs.edit().putInt("commute_morning_hour", value).apply()

    /** 下班出發時間（小時） */
    var commuteEveningHour: Int
        get() = prefs.getInt("commute_evening_hour", 18)
        set(value) = prefs.edit().putInt("commute_evening_hour", value).apply()

    /** "C" 或 "F" */
    var temperatureUnit: String
        get() = prefs.getString("temperature_unit", "C") ?: "C"
        set(value) = prefs.edit().putString("temperature_unit", value).apply()

    /** "KMH"、"MS" 或 "BEAUFORT" */
    var windUnit: String
        get() = prefs.getString("wind_unit", "KMH") ?: "KMH"
        set(value) = prefs.edit().putString("wind_unit", value).apply()

    /** 在台灣使用中央氣象署的觀測與預報 */
    var useCwa: Boolean
        get() = prefs.getBoolean("use_cwa", true)
        set(value) = prefs.edit().putBoolean("use_cwa", value).apply()

    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean("asked_notification_permission", false)
        set(value) = prefs.edit().putBoolean("asked_notification_permission", value).apply()

    // ---- 通知去重 ----

    fun lastRainNotifiedAt(cityId: String): Long = prefs.getLong("last_rain_notified_at_$cityId", 0)

    fun setLastRainNotifiedAt(cityId: String, value: Long) =
        prefs.edit().putLong("last_rain_notified_at_$cityId", value).apply()

    /** 最後一次通勤通知，格式為「出發時間」 */
    var lastCommuteNotified: String?
        get() = prefs.getString("last_commute_notified", null)
        set(value) = prefs.edit().putString("last_commute_notified", value).apply()

    var lastMorningDate: String?
        get() = prefs.getString("last_morning_date", null)
        set(value) = prefs.edit().putString("last_morning_date", value).apply()

    var notifiedWarnings: Set<String>
        get() = prefs.getStringSet("notified_warnings", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("notified_warnings", value).apply()
}
