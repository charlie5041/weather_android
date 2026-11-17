package com.charlie.weather.data

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 伺服器有回應、但不是 2xx。[reason] 是 Open-Meteo 等服務在錯誤 JSON 裡附的原因。 */
class HttpStatusException(val code: Int, val reason: String? = null) :
    IOException(if (reason.isNullOrBlank()) "HTTP $code" else "HTTP $code: $reason")

/** 從錯誤回應的內容取出原因（Open-Meteo：{"error":true,"reason":"..."}）。 */
internal fun errorReason(body: String?): String? =
    body?.let { runCatching { JSONObject(it).optString("reason").takeIf(String::isNotBlank) }.getOrNull() }

/** 不是 Open-Meteo 格式的錯誤回應：主機、Server 標頭與去掉 HTML 標籤後的內容開頭。 */
internal fun errorSummary(host: String?, server: String?, body: String?): String {
    val text = body.orEmpty().replace(Regex("(?s)<(script|style).*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(120)
    return listOfNotNull(host, server?.let { "Server $it" }, text.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

/**
 * 查詢失敗時給使用者看的原因：分清楚是手機連不上網、服務額度用完、服務故障還是 App 的錯，
 * 不要一律說「請檢查網路」。[what] 是失敗的東西，例如「沿路天氣」。
 */
fun fetchErrorMessage(e: Throwable, what: String): String {
    // 外層常是包起來的 IOException，往裡找第一個認得的原因；都不認得時用最裡面那個
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    val known = listOf(
        HttpStatusException::class.java, UnknownHostException::class.java, ConnectException::class.java,
        NoRouteToHostException::class.java, SocketTimeoutException::class.java, SSLException::class.java,
        JSONException::class.java,
    )
    val root = chain.firstOrNull { t -> known.any { it.isInstance(t) } } ?: chain.last()
    return when {
        root is HttpStatusException && root.code == 429 ->
            "天氣服務今天的免費查詢次數用完了，無法取得$what。通常隔天會恢復"
        root is HttpStatusException && root.code in 400..499 ->
            "天氣服務不接受這次查詢（錯誤 ${root.code}${root.reason?.let { "：$it" } ?: ""}）"
        root is HttpStatusException ->
            "天氣服務暫時故障（錯誤 ${root.code}），等一下再按重試"
        root is UnknownHostException || root is ConnectException || root is NoRouteToHostException ->
            "連不上天氣服務，無法取得$what。請確認網路可以上網"
        root is SocketTimeoutException ->
            "天氣服務回應太慢，無法取得$what。等一下再按重試"
        root is SSLException ->
            "無法安全連到天氣服務。請確認手機時間正確，或換個網路再試"
        root is JSONException ->
            "天氣服務回傳的資料讀不懂，等一下再按重試"
        root is IOException ->
            "無法取得$what（${root.message ?: root.javaClass.simpleName}）"
        else ->
            "無法取得$what，App 出錯了（${root.javaClass.simpleName}${root.message?.let { "：$it" } ?: ""}）"
    }
}
