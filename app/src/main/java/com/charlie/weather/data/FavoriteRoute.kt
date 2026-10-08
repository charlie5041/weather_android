package com.charlie.weather.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale

/** 常用路線的出發提醒：每週哪幾天、幾點出發。 */
data class RouteReminder(val days: Set<DayOfWeek>, val hour: Int, val minute: Int) {
    /**
     * 下一次出發時間；15 分鐘內剛過的出發時間也算（人可能還在路上）。
     * 沒有選任何一天時為 null。
     */
    fun next(now: LocalDateTime): LocalDateTime? =
        (0..7L).asSequence()
            .map { now.toLocalDate().plusDays(it).atTime(hour, minute) }
            .firstOrNull { it.dayOfWeek in days && !it.isBefore(now.minusMinutes(15)) }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/** 使用者存下來的路線；可以設定出發前提醒。 */
data class FavoriteRoute(
    val id: String,
    val name: String,
    val from: City,
    val to: City,
    val mode: TravelMode,
    val via: List<City> = emptyList(),
    val reminder: RouteReminder? = null,
) {
    /** 起點、途經點與終點相同（以座標比對，地點改名或重新搜尋也算同一條） */
    fun sameRoute(from: City, to: City, via: List<City>): Boolean =
        key(this.from) == key(from) && key(this.to) == key(to) && this.via.map(::key) == via.map(::key)

    private fun key(city: City) = if (city.isCurrentLocation) "current" else "%.4f,%.4f".format(Locale.US, city.latitude, city.longitude)
}

object FavoriteRoutes {
    /** 出門卡片顯示的「下一趟」：[withinHours] 小時內最早出發的常用路線 */
    fun upcoming(routes: List<FavoriteRoute>, now: LocalDateTime, withinHours: Long = 12): Pair<FavoriteRoute, LocalDateTime>? =
        routes.mapNotNull { route -> route.reminder?.next(now)?.let { route to it } }
            .filter { (_, time) -> time.isBefore(now.plusHours(withinHours)) }
            .minByOrNull { (_, time) -> time }

    fun toJson(routes: List<FavoriteRoute>): String = JSONArray(
        routes.map { r ->
            JSONObject()
                .put("id", r.id)
                .put("name", r.name)
                .put("from", r.from.toJson())
                .put("to", r.to.toJson())
                .put("mode", r.mode.name)
                .put("via", JSONArray(r.via.map { it.toJson() }))
                .put(
                    "reminder",
                    r.reminder?.let { rem ->
                        JSONObject()
                            .put("days", JSONArray(rem.days.sorted().map { it.value }))
                            .put("hour", rem.hour)
                            .put("minute", rem.minute)
                    } ?: JSONObject.NULL,
                )
        },
    ).toString()

    fun parse(json: String): List<FavoriteRoute> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                FavoriteRoute(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    from = o.getJSONObject("from").toCity(),
                    to = o.getJSONObject("to").toCity(),
                    mode = TravelMode.entries.firstOrNull { it.name == o.optString("mode") } ?: TravelMode.SCOOTER,
                    via = o.optJSONArray("via")?.let { v -> (0 until v.length()).map { v.getJSONObject(it).toCity() } }.orEmpty(),
                    reminder = o.optJSONObject("reminder")?.let { rem ->
                        val days = rem.getJSONArray("days")
                        RouteReminder(
                            days = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                            hour = rem.getInt("hour"),
                            minute = rem.getInt("minute"),
                        )
                    },
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
