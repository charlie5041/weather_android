package com.charlie.weather.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.charlie.weather.data.AppSettings
import com.charlie.weather.data.City
import com.charlie.weather.data.Weather
import com.charlie.weather.data.WeatherRepository
import com.charlie.weather.ui.Units
import com.charlie.weather.widget.WeatherWidget
import java.util.concurrent.TimeUnit

/** 每 30 分鐘在背景更新主要城市與自訂地點的天氣：重繪小工具並檢查是否需要通知。 */
class WeatherSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Units.load(applicationContext)
        val repo = WeatherRepository.get(applicationContext)
        val city = repo.primaryCity() ?: return Result.success()
        // 行動網路（計量網路）時省流量：拉長氣象署資料的快取時間，雨量站只用快取
        val metered = applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        val weather = try {
            repo.fetch(city, lowData = metered)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        WeatherWidget.update(applicationContext, city, weather)
        WeatherNotifier.check(applicationContext, city to weather, placesWeather(repo, city, metered))
        return Result.success()
    }

    /** 其他自訂地點（住家、公司…）的天氣，供地點提醒與通勤通知使用；單一地點失敗就略過。 */
    private suspend fun placesWeather(repo: WeatherRepository, primary: City, metered: Boolean): List<Pair<City, Weather>> {
        val settings = AppSettings(applicationContext)
        if (!settings.placeAlerts && !settings.commuteNotify) return emptyList()
        return repo.store.loadCities()
            .filter { it.label != null && it.id != primary.id }
            .take(MAX_PLACES)
            .mapNotNull { place ->
                try {
                    place to repo.fetch(place, lowData = metered)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    repo.cached(place)?.let { place to it }
                }
            }
    }

    companion object {
        private const val MAX_PLACES = 5
        private const val PERIODIC = "weather_sync"
        private const val ONE_TIME = "weather_sync_now"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeatherSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeatherSyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
