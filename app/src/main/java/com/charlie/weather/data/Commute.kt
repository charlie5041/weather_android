package com.charlie.weather.data

import java.util.UUID

/**
 * 通勤：以「住家」與「公司」（沒有公司時用「學校」）兩個自訂地點建立「上班」「下班」兩條常用路線。
 * 舊版的通勤卡與通勤通知已併入常用路線，[migrate] 負責把舊設定轉成路線。
 */
object Commute {
    const val HOME = "住家"
    const val MORNING_HOUR = 8
    const val EVENING_HOUR = 18
    private val WORK = listOf("公司", "學校")

    fun homeAndWork(cities: List<City>): Pair<City, City>? {
        val home = cities.firstOrNull { it.label == HOME } ?: return null
        val work = WORK.firstNotNullOfOrNull { label -> cities.firstOrNull { it.label == label } } ?: return null
        return home to work
    }

    /** 平日上班（住家 → 公司）、下班（公司 → 住家）兩條路線 */
    fun routes(
        home: City,
        work: City,
        mode: TravelMode,
        morningHour: Int = MORNING_HOUR,
        eveningHour: Int = EVENING_HOUR,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<FavoriteRoute> = listOf(
        FavoriteRoute(newId(), "上班", home, work, mode, reminder = RouteReminder(RouteReminder.WEEKDAYS, morningHour, 0)),
        FavoriteRoute(newId(), "下班", work, home, mode, reminder = RouteReminder(RouteReminder.WEEKDAYS, eveningHour, 0)),
    )

    /** 已經有住家 → 公司的常用路線（不管有沒有設出發時間） */
    fun hasRoutes(favorites: List<FavoriteRoute>, home: City, work: City): Boolean =
        favorites.any { it.sameRoute(home, work, emptyList()) }

    /**
     * 把上下班兩條路線加進 [favorites]：已經有相同起訖（沒有途經點）的路線就不重複建，
     * 它還沒設出發時間時補上平日的提醒。
     */
    fun merge(favorites: List<FavoriteRoute>, commute: List<FavoriteRoute>): List<FavoriteRoute> {
        var result = favorites
        commute.forEach { route ->
            val existing = result.firstOrNull { it.sameRoute(route.from, route.to, emptyList()) }
            result = when {
                existing == null -> result + route
                existing.reminder == null -> result.map { if (it.id == existing.id) it.copy(reminder = route.reminder) else it }
                else -> result
            }
        }
        return result
    }

    /**
     * 舊版通勤設定轉成常用路線。有住家與公司、而且通勤卡或通勤通知有開時才轉；
     * 回傳 null 表示不用改。
     */
    fun migrate(
        favorites: List<FavoriteRoute>,
        cities: List<City>,
        legacy: LegacyCommute,
        mode: TravelMode,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<FavoriteRoute>? {
        if (!legacy.card && !legacy.notify) return null
        val (home, work) = homeAndWork(cities) ?: return null
        val merged = merge(favorites, routes(home, work, mode, legacy.morningHour, legacy.eveningHour, newId))
        return merged.takeIf { it != favorites }
    }

    /** 這個小時可能下雨（降雨機率 ≥ 50%、有雨量或雨天代碼） */
    fun isWet(h: HourlyForecast) =
        (h.precipitationProbability ?: 0) >= 50 || h.precipitation >= 0.5 ||
            h.weatherCode in 51..67 || h.weatherCode in 80..82 || h.weatherCode in 95..99
}

/** 舊版設定頁「通勤」區塊的設定，只用來遷移 */
data class LegacyCommute(val card: Boolean, val notify: Boolean, val morningHour: Int, val eveningHour: Int)
