package com.charlie.weather.data

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class CommuteLeg(val label: String) { TO_WORK("上班"), TO_HOME("下班") }

/** 一趟通勤：出發地在出發時間的天氣、目的地在抵達時間（約 1 小時後）的天氣。 */
data class CommuteTrip(
    val leg: CommuteLeg,
    val departure: LocalDateTime,
    val from: City,
    val to: City,
    val fromHour: HourlyForecast?,
    val toHour: HourlyForecast?,
) {
    val arrival: LocalDateTime get() = departure.plusHours(1)
    val advice: String get() = Commute.advice(fromHour, toHour)
}

/** 通勤時段預報：以「住家」與「公司」（沒有公司時用「學校」）兩個自訂地點計算。 */
object Commute {
    const val HOME = "住家"
    private val WORK = listOf("公司", "學校")

    fun homeAndWork(cities: List<City>): Pair<City, City>? {
        val home = cities.firstOrNull { it.label == HOME } ?: return null
        val work = WORK.firstNotNullOfOrNull { label -> cities.firstOrNull { it.label == label } } ?: return null
        return home to work
    }

    /** 下一趟（或正在進行的）通勤；只算平日，週末跳到下週一早上。 */
    fun nextDeparture(now: LocalDateTime, morningHour: Int, eveningHour: Int): Pair<CommuteLeg, LocalDateTime> {
        var date = now.toLocalDate()
        while (true) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                val morning = date.atTime(morningHour, 0)
                val evening = date.atTime(eveningHour, 0)
                // 出發後一小時內仍視為這一趟，方便路上查看
                if (now.isBefore(morning.plusHours(1))) return CommuteLeg.TO_WORK to morning
                if (now.isBefore(evening.plusHours(1))) return CommuteLeg.TO_HOME to evening
            }
            date = date.plusDays(1)
        }
    }

    fun trip(
        home: City,
        work: City,
        homeWeather: Weather?,
        workWeather: Weather?,
        now: LocalDateTime,
        morningHour: Int,
        eveningHour: Int,
    ): CommuteTrip {
        val (leg, departure) = nextDeparture(now, morningHour, eveningHour)
        val (from, to) = if (leg == CommuteLeg.TO_WORK) home to work else work to home
        val (fromWeather, toWeather) = if (leg == CommuteLeg.TO_WORK) homeWeather to workWeather else workWeather to homeWeather
        return CommuteTrip(
            leg, departure, from, to,
            fromHour = fromWeather?.let { hourAt(it, departure) },
            toHour = toWeather?.let { hourAt(it, departure.plusHours(1)) },
        )
    }

    /** 取該整點的逐時預報；沒有剛好的整點時取 1 小時內最接近的。 */
    fun hourAt(weather: Weather, time: LocalDateTime): HourlyForecast? {
        val target = time.truncatedTo(ChronoUnit.HOURS)
        return weather.hourly.firstOrNull { it.time.truncatedTo(ChronoUnit.HOURS) == target }
            ?: weather.hourly
                .filter { abs(ChronoUnit.MINUTES.between(it.time, time)) <= 60 }
                .minByOrNull { abs(ChronoUnit.MINUTES.between(it.time, time)) }
    }

    private fun isWet(h: HourlyForecast) =
        (h.precipitationProbability ?: 0) >= 50 || h.precipitation >= 0.5 ||
            h.weatherCode in 51..67 || h.weatherCode in 80..82 || h.weatherCode in 95..99

    fun advice(from: HourlyForecast?, to: HourlyForecast?): String {
        val hours = listOfNotNull(from, to)
        if (hours.isEmpty()) return "暫無預報資料"
        val tips = mutableListOf<String>()
        when {
            hours.any { it.weatherCode in 95..99 } -> tips += "可能有雷雨，記得帶傘"
            hours.any { it.precipitation >= 10 } -> tips += "可能有大雨，記得帶傘"
            hours.any(::isWet) -> tips += "可能下雨，記得帶傘"
        }
        if (from != null && to != null && !from.temperature.isNaN() && !to.temperature.isNaN()) {
            val diff = to.temperature - from.temperature
            if (abs(diff) >= 4) tips += "目的地${if (diff > 0) "較熱" else "較涼"}約 ${abs(diff).toInt()}°"
        }
        return if (tips.isEmpty()) "兩地天氣穩定，適合出門" else tips.joinToString("；")
    }
}
