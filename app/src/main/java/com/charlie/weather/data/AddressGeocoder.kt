package com.charlie.weather.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 地址查詢結果：[area] 是行政區（例如內湖區），[address] 是完整地址。 */
data class AddressResult(
    val area: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    /** 只找到大概位置（鄉鎮或城市中心），不是精確地址 */
    val approximate: Boolean = false,
)

/** 用系統的 Geocoder（Google 地圖服務）把地址轉成座標，或把座標轉回地址。 */
class AddressGeocoder(private val context: Context) {

    val available: Boolean get() = Geocoder.isPresent()

    suspend fun search(query: String): List<AddressResult> = withContext(Dispatchers.IO) {
        if (!available || query.isBlank()) return@withContext emptyList()
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.TAIWAN).getFromLocationName(query.trim(), 6).orEmpty()
        }.getOrDefault(emptyList())
            .mapNotNull { it.toResult() }
            .distinctBy { it.address }
    }

    suspend fun reverse(latitude: Double, longitude: Double): AddressResult? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.TAIWAN).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull()?.toResult()?.copy(latitude = latitude, longitude = longitude)
    }

    private fun Address.toResult(): AddressResult? {
        if (!hasLatitude() || !hasLongitude()) return null
        val line = getAddressLine(0)?.let(::cleanAddress).orEmpty()
        val area = locality ?: subLocality ?: subAdminArea ?: adminArea ?: featureName ?: return null
        return AddressResult(
            area = area,
            address = line.ifBlank { listOfNotNull(adminArea, locality, thoroughfare, featureName).joinToString("") },
            latitude = latitude,
            longitude = longitude,
        )
    }

    companion object {
        /** 去掉郵遞區號與國名：「114台灣台北市內湖區…」→「台北市內湖區…」 */
        fun cleanAddress(line: String): String =
            line.trim()
                .replace(Regex("^\\d{3,6}\\s*"), "")
                .removePrefix("台灣").removePrefix("臺灣").removePrefix("Taiwan")
                .replace(Regex(",?\\s*(台灣|臺灣|Taiwan)$"), "")
                .trim()
    }
}
