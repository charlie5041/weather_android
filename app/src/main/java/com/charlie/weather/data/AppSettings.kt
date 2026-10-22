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

    /** 舊版「通勤」設定（已併入常用路線），只在遷移時讀一次 */
    val legacyCommute: LegacyCommute
        get() = LegacyCommute(
            card = prefs.getBoolean("commute_card", true),
            notify = prefs.getBoolean("commute_notify", true),
            morningHour = prefs.getInt("commute_morning_hour", Commute.MORNING_HOUR),
            eveningHour = prefs.getInt("commute_evening_hour", Commute.EVENING_HOUR),
        )

    /** 舊版通勤設定已經轉成常用路線，並清掉不再使用的設定 */
    var commuteMigrated: Boolean
        get() = prefs.getBoolean("commute_migrated", false)
        set(value) {
            prefs.edit().putBoolean("commute_migrated", value).apply {
                if (value) {
                    listOf(
                        "commute_card", "commute_notify", "commute_morning_hour", "commute_evening_hour",
                        "commute_route_key", "commute_route_minutes", "commute_route_at", "last_commute_notified",
                    ).forEach(::remove)
                }
            }.apply()
        }

    /** 沿路天氣沿路線取樣的間距（公里） */
    var routeStepKm: Int
        get() = prefs.getInt("route_step_km", RoutePlanner.STEP_KM.toInt())
        set(value) = prefs.edit().putInt("route_step_km", value).apply()

    /** "C" 或 "F" */
    var temperatureUnit: String
        get() = prefs.getString("temperature_unit", "C") ?: "C"
        set(value) = prefs.edit().putString("temperature_unit", value).apply()

    /** "KMH"、"MS" 或 "BEAUFORT" */
    var windUnit: String
        get() = prefs.getString("wind_unit", "KMH") ?: "KMH"
        set(value) = prefs.edit().putString("wind_unit", value).apply()

    /** 測速照相提醒：沿路天氣列出、騎乘中與行車提醒接近時提醒（行車提醒也依這個開關） */
    var speedCameras: Boolean
        get() = prefs.getBoolean("speed_cameras", true)
        set(value) = prefs.edit().putBoolean("speed_cameras", value).apply()

    /** 騎乘中與行車提醒時記錄軌跡，存成足跡 */
    var recordJourneys: Boolean
        get() = prefs.getBoolean("record_journeys", true)
        set(value) = prefs.edit().putBoolean("record_journeys", value).apply()

    /** 分享足跡時，自訂地點周圍隱藏的半徑（公尺） */
    var sharePrivacyRadiusM: Int
        get() = prefs.getInt("share_privacy_radius_m", SharePrivacy.DEFAULT_RADIUS_M)
        set(value) = prefs.edit().putInt("share_privacy_radius_m", value).apply()

    /** 分享足跡時不當隱私區的自訂地點 id */
    var sharePrivacyExcluded: Set<String>
        get() = prefs.getStringSet("share_privacy_excluded", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("share_privacy_excluded", value).apply()

    /** 騎乘中與行車提醒用語音播報測速照相與前方降雨 */
    var voiceAlerts: Boolean
        get() = prefs.getBoolean("voice_alerts", true)
        set(value) = prefs.edit().putBoolean("voice_alerts", value).apply()

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

    var lastMorningDate: String?
        get() = prefs.getString("last_morning_date", null)
        set(value) = prefs.edit().putString("last_morning_date", value).apply()

    /** 已經通知過的常用路線出發，格式為「路線 id|出發時間」 */
    var notifiedRouteReminders: Set<String>
        get() = prefs.getStringSet("notified_route_reminders", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("notified_route_reminders", value).apply()

    var notifiedWarnings: Set<String>
        get() = prefs.getStringSet("notified_warnings", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("notified_warnings", value).apply()
}
