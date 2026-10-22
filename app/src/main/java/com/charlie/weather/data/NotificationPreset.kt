package com.charlie.weather.data

/** 各種通知的開關。 */
data class NotificationToggles(
    val rain: Boolean,
    val warnings: Boolean,
    val morning: Boolean,
    val places: Boolean,
    /** 常用路線出發前的沿路天氣 */
    val routes: Boolean,
    /** 騎乘中前方 20 分鐘內會遇到雨 */
    val rideRain: Boolean,
    /** 騎乘結束時的「足跡已儲存」「行車提醒已結束」 */
    val rideEnd: Boolean,
)

/**
 * 設定頁最上面的「通知量」：一次套用一組開關。個別開關改過、對不上任何一組時顯示為自訂。
 * 天氣特報、常用路線與騎乘中前方降雨在每一組都開著：這些是使用者自己設的或與安全有關。
 */
enum class NotificationPreset(val label: String, val description: String, val toggles: NotificationToggles) {
    FEW(
        "少量",
        "只有天氣特報、常用路線出發前的沿路天氣，以及騎乘中前方的雨。",
        NotificationToggles(rain = false, warnings = true, morning = false, places = false, routes = true, rideRain = true, rideEnd = false),
    ),
    STANDARD(
        "標準",
        "少量的內容，再加上第一個城市的降雨提醒。常用路線快出發時不另外發降雨提醒。",
        NotificationToggles(rain = true, warnings = true, morning = false, places = false, routes = true, rideRain = true, rideEnd = false),
    ),
    ALL(
        "全部",
        "所有通知都開：每日早晨天氣、住家公司等地點的降雨與特報、騎乘結束的通知。",
        NotificationToggles(rain = true, warnings = true, morning = true, places = true, routes = true, rideRain = true, rideEnd = true),
    ),
    ;

    companion object {
        /** 新安裝時的預設 */
        val DEFAULT = STANDARD

        /** 目前的開關對得上哪一組；都對不上（自訂）時為 null */
        fun of(toggles: NotificationToggles): NotificationPreset? = entries.firstOrNull { it.toggles == toggles }
    }
}
