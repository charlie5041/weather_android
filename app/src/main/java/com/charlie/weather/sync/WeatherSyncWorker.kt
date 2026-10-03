package com.charlie.weather.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.charlie.weather.data.WeatherRepository
import com.charlie.weather.ui.Units
import com.charlie.weather.widget.WeatherWidget
import java.util.concurrent.TimeUnit

/** 每 30 分鐘在背景更新主要城市的天氣：重繪小工具並檢查是否需要通知。 */
class WeatherSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Units.load(applicationContext)
        val repo = WeatherRepository.get(applicationContext)
        val city = repo.primaryCity() ?: return Result.success()
        val weather = try {
            repo.fetch(city)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        WeatherWidget.update(applicationContext, city, weather)
        WeatherNotifier.check(applicationContext, city, weather)
        return Result.success()
    }

    companion object {
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
