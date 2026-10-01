package com.charlie.weather.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 使用系統 LocationManager 取得位置（不依賴 Google Play 服務）。 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val last = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_MS) return last

        val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return last
        val fresh = withTimeoutOrNull(TIMEOUT_MS) { requestSingle(lm, provider) }
        return fresh ?: last
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingle(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    // API 29 以下這些方法沒有預設實作，必須覆寫
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    suspend fun placeName(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()?.let {
                it.locality ?: it.subLocality ?: it.subAdminArea ?: it.adminArea
            }
        }.getOrNull()
    }

    private companion object {
        const val FRESH_MS = 10 * 60_000L
        const val TIMEOUT_MS = 10_000L
    }
}
