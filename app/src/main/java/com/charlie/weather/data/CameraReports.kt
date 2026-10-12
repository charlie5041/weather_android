package com.charlie.weather.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Date
import kotlin.math.cos
import kotlin.math.sqrt

/** 回報的結果 */
enum class ReportResult { SENT, DUPLICATE, TOO_SOON, UNAVAILABLE, FAILED }

/**
 * 使用者回報的移動式測速，存在 Firebase Firestore 的 camera_reports（以匿名登入讀寫）。
 * 每筆回報 [TTL_MS] 後失效。需要 CI 放入 google-services.json，並在 Firebase 主控台啟用
 * Firestore 與匿名登入、部署 firestore.rules；沒有設定時回報功能不顯示，查詢結果是空的。
 */
class CameraReportRepository(context: Context) {
    private val app = context.applicationContext
    private val lock = Mutex()
    private var cache: Pair<Long, List<SpeedCamera>>? = null
    private var lastReportAt = 0L

    /** 最近一次回報失敗的原因（給使用者看，例如「Firebase 尚未啟用匿名登入」） */
    @Volatile
    var lastError: String? = null
        private set

    /** 這個版本有 Firebase 設定 */
    val available: Boolean get() = FirebaseApp.getApps(app).isNotEmpty()

    /** 還沒失效的回報；結果快取 [CACHE_MS]，失敗時沿用上次的結果 */
    suspend fun recent(): List<SpeedCamera> = lock.withLock {
        if (!available) return@withLock emptyList()
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < CACHE_MS }?.let { return@withLock it.second }
        try {
            withTimeout(TIMEOUT_MS) {
                signIn()
                val snapshot = FirebaseFirestore.getInstance().collection(COLLECTION)
                    .whereGreaterThan("expiresAt", Timestamp.now())
                    .limit(MAX_REPORTS)
                    .get()
                    .await()
                val reports = snapshot.documents.mapNotNull { doc ->
                    fromFields(
                        doc.getDouble("lat"),
                        doc.getDouble("lon"),
                        doc.getDouble("heading"),
                        doc.getTimestamp("createdAt")?.toDate()?.time,
                        doc.getTimestamp("expiresAt")?.toDate()?.time,
                        now,
                    )
                }
                cache = now to reports
                reports
            }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) cache?.second.orEmpty() else throw e
        } catch (e: Exception) {
            cache?.second.orEmpty()
        }
    }

    /** 回報目前位置有移動式測速；[heading] 是行進方位角（沒有時任何方向都會提醒） */
    suspend fun report(position: LatLon, heading: Double?): ReportResult {
        if (!available) return ReportResult.UNAVAILABLE
        val now = System.currentTimeMillis()
        if (now - lastReportAt < MIN_INTERVAL_MS) return ReportResult.TOO_SOON
        if (isDuplicate(recent(), position, heading)) return ReportResult.DUPLICATE
        return try {
            withTimeout(TIMEOUT_MS) {
                val uid = signIn()
                val fields = hashMapOf<String, Any>(
                    "lat" to position.latitude,
                    "lon" to position.longitude,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to Timestamp(Date(now + TTL_MS)),
                    "uid" to uid,
                )
                heading?.let { fields["heading"] = it }
                FirebaseFirestore.getInstance().collection(COLLECTION).add(fields).await()
            }
            lastReportAt = now
            lock.withLock { cache = null }
            ReportResult.SENT
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            failed(e)
        } catch (e: Exception) {
            failed(e)
        }
    }

    /** 記下失敗原因，並送到 Crashlytics（非致命）方便查 Firebase 設定問題 */
    private fun failed(e: Exception): ReportResult {
        lastError = describe(e)
        runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
        return ReportResult.FAILED
    }

    private suspend fun signIn(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return it.uid }
        return auth.signInAnonymously().await().user?.uid ?: throw IllegalStateException("no user")
    }

    companion object {
        const val COLLECTION = "camera_reports"

        /** 回報有效時間：移動式測速通常只在一個地方停一兩個小時 */
        const val TTL_MS = 2 * 60 * 60_000L
        private const val CACHE_MS = 3 * 60_000L
        private const val TIMEOUT_MS = 15_000L
        private const val MIN_INTERVAL_MS = 2 * 60_000L
        private const val MAX_REPORTS = 500L

        /** 附近已經有人回報（同方向）就不再新增 */
        private const val DUPLICATE_KM = 0.15

        /** 把 Firebase 的錯誤轉成看得懂的原因 */
        fun describe(e: Exception): String = when (e) {
            is TimeoutCancellationException -> "連線逾時，請檢查網路"
            is FirebaseNetworkException -> "網路連線失敗"
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED", "ERROR_ADMIN_RESTRICTED_OPERATION" -> "Firebase 尚未啟用匿名登入"
                else -> "Firebase 登入失敗（${e.errorCode}）"
            }
            is FirebaseFirestoreException -> when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Firestore 規則不允許（請確認已發布 firestore.rules）"
                FirebaseFirestoreException.Code.NOT_FOUND -> "找不到 Firestore 資料庫"
                FirebaseFirestoreException.Code.UNAVAILABLE -> "連不上 Firestore，請檢查網路"
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> "Firebase 尚未登入（請確認已啟用匿名登入）"
                else -> "Firestore 錯誤（${e.code.name}）"
            }
            else -> e.message?.take(80) ?: e.javaClass.simpleName
        }

        fun fromFields(lat: Double?, lon: Double?, heading: Double?, createdAt: Long?, expiresAt: Long?, now: Long): SpeedCamera? {
            if (lat == null || lon == null || lat !in 21.5..26.6 || lon !in 118.0..122.6) return null
            if (expiresAt != null && expiresAt <= now) return null
            return SpeedCamera(
                position = LatLon(lat, lon),
                address = "使用者回報的移動式測速",
                limit = null,
                direction = null,
                mobile = true,
                bearing = heading?.takeIf { !it.isNaN() }?.let { ((it % 360) + 360) % 360 },
                reportedAt = createdAt ?: now,
            )
        }

        fun isDuplicate(reports: List<SpeedCamera>, position: LatLon, heading: Double?): Boolean {
            val kx = 111.32 * cos(Math.toRadians(position.latitude))
            return reports.any { r ->
                val dx = (r.position.longitude - position.longitude) * kx
                val dy = (r.position.latitude - position.latitude) * 110.57
                val sameWay = heading == null || r.bearing == null ||
                    SpeedCameraRepository.angleBetween(heading, r.bearing) <= SpeedCameraRepository.MAX_ANGLE
                sqrt(dx * dx + dy * dy) <= DUPLICATE_KM && sameWay
            }
        }
    }
}
