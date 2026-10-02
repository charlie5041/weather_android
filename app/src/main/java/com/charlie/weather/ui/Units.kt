package com.charlie.weather.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.charlie.weather.data.AppSettings
import kotlin.math.roundToInt

enum class TemperatureUnit(val label: String) { C("°C"), F("°F") }

enum class WindUnit(val label: String) { KMH("km/h"), MS("m/s"), BEAUFORT("級") }

/** 目前的顯示單位；以 Compose state 保存，切換後畫面會自動更新。 */
object Units {
    var temperature by mutableStateOf(TemperatureUnit.C)
    var wind by mutableStateOf(WindUnit.KMH)

    fun load(context: Context) {
        val settings = AppSettings(context)
        temperature = runCatching { TemperatureUnit.valueOf(settings.temperatureUnit) }.getOrDefault(TemperatureUnit.C)
        wind = runCatching { WindUnit.valueOf(settings.windUnit) }.getOrDefault(WindUnit.KMH)
    }
}

/** 攝氏轉為目前單位的數值 */
fun Double.inTemperatureUnit(): Double = if (Units.temperature == TemperatureUnit.F) this * 9 / 5 + 32 else this

private val beaufortLimitsMs = doubleArrayOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)

/** km/h 轉為目前單位的數字（不含單位） */
fun windValue(kmh: Double): String {
    if (kmh.isNaN()) return "--"
    return when (Units.wind) {
        WindUnit.KMH -> kmh.roundToInt().toString()
        WindUnit.MS -> {
            val ms = kmh / 3.6
            if (ms < 10) "%.1f".format(ms) else ms.roundToInt().toString()
        }
        WindUnit.BEAUFORT -> {
            val ms = kmh / 3.6
            (beaufortLimitsMs.indexOfFirst { ms < it }.takeIf { it >= 0 } ?: 12).toString()
        }
    }
}

/** 例：12 km/h、3.4 m/s、3 級 */
fun windText(kmh: Double): String = if (Units.wind == WindUnit.BEAUFORT) "${windValue(kmh)} 級" else "${windValue(kmh)} ${Units.wind.label}"
