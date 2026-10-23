package com.charlie.weather.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 電池最佳化：開著時有些手機（小米、OPPO、vivo、三星等）關螢幕一段時間後會停掉背景定位，
 * 騎乘中提醒與足跡會斷掉。只引導使用者自己去改，不用需要特別權限的直接要求。
 */
object BackgroundPower {
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** 打開這個 App 的「應用程式資訊」（電池 → 不限制）；打不開時改開電池最佳化清單 */
    fun openSettings(context: Context) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(details) }.recoverCatching { context.startActivity(list) }
    }
}
