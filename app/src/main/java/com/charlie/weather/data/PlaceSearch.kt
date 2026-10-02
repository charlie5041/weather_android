package com.charlie.weather.data

import org.json.JSONArray

/** 內建的台灣縣市與鄉鎮清單（取自中央氣象署鄉鎮預報），用於離線模糊搜尋。 */
data class TaiwanPlace(val county: String, val township: String, val latitude: Double, val longitude: Double) {
    val isCounty: Boolean get() = township.isEmpty()
    val name: String get() = if (isCounty) county else township

    fun toCity() = City(
        id = if (isCounty) "tw_$county" else "tw_${county}_$township",
        name = name,
        subtitle = if (isCounty) "臺灣" else county,
        latitude = latitude,
        longitude = longitude,
    )
}

object PlaceSearch {

    fun parse(json: String): List<TaiwanPlace> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            TaiwanPlace(p.getString(0), p.getString(1), p.getDouble(2), p.getDouble(3))
        }
    }

    /** 統一「台/臺」並去掉空白，讓「台中」可以找到「臺中市」。 */
    fun normalize(text: String): String =
        text.trim().replace('台', '臺').replace(" ", "").replace("　", "")

    /** 去掉行政區後綴：臺中市 → 臺中、北屯區 → 北屯 */
    private fun base(name: String): String =
        if (name.length > 2 && name.last() in "市縣區鄉鎮") name.dropLast(1) else name

    /**
     * 模糊搜尋：
     * - 「台中」「臺中市」→ 臺中市
     * - 「北屯」→ 臺中市北屯區
     * - 「台中北屯」「臺中市北屯區」→ 臺中市北屯區
     * 依符合程度排序：名稱完全相同 > 開頭相同 > 包含；同分時縣市優先。
     */
    fun search(query: String, places: List<TaiwanPlace>, limit: Int = 12): List<TaiwanPlace> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qBase = base(q)
        return places.mapNotNull { p ->
            val name = p.name
            val keys = buildList {
                add(name)
                add(base(name))
                if (!p.isCounty) {
                    add(p.county + name)
                    add(base(p.county) + name)
                    add(base(p.county) + base(name))
                }
            }
            val score = when {
                keys.any { it == q || it == qBase } -> 0
                keys.any { it.startsWith(q) } -> 1
                keys.any { q in it } -> 2
                // 輸入比地名長（例如「台中市北屯區大坑」）時，看是否包含完整的縣市＋鄉鎮
                !p.isCounty && q.contains(base(p.county)) && q.contains(base(name)) -> 3
                else -> return@mapNotNull null
            }
            Triple(p, score, if (p.isCounty) 0 else 1)
        }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .take(limit)
            .map { it.first }
    }

    /** 線上搜尋時一併嘗試的寫法（台／臺互換）。 */
    fun variants(query: String): List<String> {
        val q = query.trim()
        return listOf(q, q.replace('台', '臺'), q.replace('臺', '台')).distinct().filter { it.isNotEmpty() }
    }
}
