package com.charlie.weather.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.charlie.weather.data.WeatherRepository
import com.charlie.weather.ui.Units
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 常用路線通知的「出發前 15 分再提醒」：收起通知，到出發前 15 分鐘再查一次沿路天氣並通知。 */
class RouteSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routeId = intent.getStringExtra(WeatherNotifier.EXTRA_FAVORITE_ROUTE) ?: return
        val departure = intent.getStringExtra(EXTRA_DEPARTURE)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(WeatherNotifier.EXTRA_NOTIFICATION_ID, 0))
        val delay = Duration.between(LocalDateTime.now(), departure.minusMinutes(BEFORE_MINUTES)).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<RouteReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(WeatherNotifier.EXTRA_FAVORITE_ROUTE to routeId, EXTRA_DEPARTURE to departure.toString()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("route_snooze_$routeId", ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val EXTRA_DEPARTURE = "departure"
        const val BEFORE_MINUTES = 15L
    }
}

/** 延後的常用路線提醒：出發前再查一次沿路天氣並通知（已經出發就不通知）。 */
class RouteReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val routeId = inputData.getString(WeatherNotifier.EXTRA_FAVORITE_ROUTE) ?: return Result.success()
        val departure = inputData.getString(RouteSnoozeReceiver.EXTRA_DEPARTURE)
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return Result.success()
        val now = LocalDateTime.now()
        if (now.isAfter(departure.plusMinutes(5)) || !WeatherNotifier.canNotify(applicationContext)) return Result.success()
        Units.load(applicationContext)
        val repo = WeatherRepository.get(applicationContext)
        val route = repo.store.loadFavoriteRoutes().firstOrNull { it.id == routeId } ?: return Result.success()
        WeatherNotifier.createChannels(applicationContext)
        val sent = WeatherNotifier.notifyFavoriteRoute(applicationContext, repo, route, departure, now)
        return if (sent || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}
