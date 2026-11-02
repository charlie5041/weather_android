package com.charlie.weather.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * 暫停記錄時用系統的活動偵測（Activity Recognition）判斷開始移動了：
 * 開始走路、跑步、騎車、坐車，或離開「靜止」時通知 [RideService] 自動繼續。
 * 只在暫停期間註冊；靠手機本來就在跑的感測器，暫停期間不必定位。
 */
object MovementDetector {
    private val MOVING = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.WALKING, DetectedActivity.RUNNING)

    /** Android 10 起要使用者允許「體能活動」權限；更舊的版本安裝時就有 */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** 這個活動轉換是不是「開始移動了」 */
    fun isMovement(activityType: Int, transitionType: Int): Boolean =
        if (activityType == DetectedActivity.STILL) {
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
        } else {
            activityType in MOVING && transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        }

    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val transitions = MOVING.map { type ->
            ActivityTransition.Builder().setActivityType(type).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        } + ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
        return runCatching {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
        }.isSuccess
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        if (!hasPermission(context)) return
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context)) }
    }

    // 活動偵測要把結果放進 Intent 的 extras，Android 12 起必須是 mutable
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, MovementReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
    )
}

/** 收到活動轉換：開始移動了就請騎乘服務自動繼續記錄 */
class MovementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        if (result.transitionEvents.any { MovementDetector.isMovement(it.activityType, it.transitionType) }) {
            RideService.autoResume(context)
        }
    }
}
