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

    /**
     * 常用城市縮寫與機場代碼 → 搜尋用的完整名稱（不分大小寫）。
     * 台灣的代碼對應中文地名，可直接命中內建清單。
     */
    private val aliases: Map<String, String> = mapOf(
        // 台灣
        "TPE" to "桃園", "TSA" to "臺北", "KHH" to "高雄", "RMQ" to "臺中", "TNN" to "臺南", "HUN" to "花蓮",
        "TTT" to "臺東", "MZG" to "澎湖", "KNH" to "金門", "TPI" to "臺北", "KH" to "高雄", "TC" to "臺中",
        // 城市縮寫
        "NYC" to "New York", "NY" to "New York", "LA" to "Los Angeles", "SF" to "San Francisco",
        "DC" to "Washington", "LV" to "Las Vegas", "HK" to "Hong Kong", "SG" to "Singapore",
        "KL" to "Kuala Lumpur", "BJ" to "Beijing", "SH" to "Shanghai", "HCMC" to "Ho Chi Minh City",
        "UK" to "London", "STL" to "St. Louis", "PHL" to "Philadelphia", "CHI" to "Chicago",
        // 日韓
        "NRT" to "Tokyo", "HND" to "Tokyo", "TYO" to "Tokyo", "KIX" to "Osaka", "ITM" to "Osaka", "OSA" to "Osaka",
        "NGO" to "Nagoya", "FUK" to "Fukuoka", "CTS" to "Sapporo", "OKA" to "Naha", "SDJ" to "Sendai",
        "ICN" to "Seoul", "GMP" to "Seoul", "SEL" to "Seoul", "PUS" to "Busan", "CJU" to "Jeju",
        // 中港澳
        "HKG" to "Hong Kong", "MFM" to "Macau", "PVG" to "Shanghai", "SHA" to "Shanghai", "PEK" to "Beijing",
        "PKX" to "Beijing", "CAN" to "Guangzhou", "SZX" to "Shenzhen", "XMN" to "Xiamen", "CTU" to "Chengdu",
        // 東南亞、大洋洲
        "BKK" to "Bangkok", "DMK" to "Bangkok", "SIN" to "Singapore", "KUL" to "Kuala Lumpur", "MNL" to "Manila",
        "SGN" to "Ho Chi Minh City", "HAN" to "Hanoi", "DAD" to "Da Nang", "DPS" to "Denpasar", "CGK" to "Jakarta",
        "CNX" to "Chiang Mai", "HKT" to "Phuket", "CEB" to "Cebu", "PNH" to "Phnom Penh",
        "SYD" to "Sydney", "MEL" to "Melbourne", "BNE" to "Brisbane", "PER" to "Perth", "AKL" to "Auckland",
        // 美洲
        "LAX" to "Los Angeles", "SFO" to "San Francisco", "SEA" to "Seattle", "JFK" to "New York",
        "EWR" to "Newark", "LGA" to "New York", "ORD" to "Chicago", "BOS" to "Boston", "IAD" to "Washington",
        "DFW" to "Dallas", "IAH" to "Houston", "ATL" to "Atlanta", "MIA" to "Miami", "LAS" to "Las Vegas",
        "HNL" to "Honolulu", "SAN" to "San Diego", "YVR" to "Vancouver", "YYZ" to "Toronto", "YUL" to "Montreal",
        // 歐洲、中東、南亞
        "LHR" to "London", "LGW" to "London", "LON" to "London", "CDG" to "Paris", "PAR" to "Paris",
        "FRA" to "Frankfurt", "MUC" to "Munich", "AMS" to "Amsterdam", "FCO" to "Rome", "MXP" to "Milan",
        "MAD" to "Madrid", "BCN" to "Barcelona", "ZRH" to "Zurich", "VIE" to "Vienna", "PRG" to "Prague",
        "CPH" to "Copenhagen", "IST" to "Istanbul", "DXB" to "Dubai", "DOH" to "Doha", "DEL" to "Delhi",
        "BOM" to "Mumbai",
    )

    /** 若輸入是已知縮寫或機場代碼，回傳對應的完整名稱。 */
    fun expandAlias(query: String): String? = aliases[query.trim().uppercase()]

    /** 線上搜尋時一併嘗試的寫法（台／臺互換）。 */
    fun variants(query: String): List<String> {
        val q = query.trim()
        return listOf(q, q.replace('台', '臺'), q.replace('臺', '台')).distinct().filter { it.isNotEmpty() }
    }
}
