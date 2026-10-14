package com.charlie.weather.data

/** 路線上一段道路：從起點算起 [startKm] 到 [endKm] 公里，路名是 [name]（沒有路名時為空字串）。 */
data class RoadSpan(val startKm: Double, val endKm: Double, val name: String)

/** 道路在高架（含快速道路、國道、陸橋）上，或是平面道路；看不出來時為 UNKNOWN。 */
enum class RoadLevel { ELEVATED, SURFACE, UNKNOWN }

/**
 * 分辨橋上、橋下的測速照相：比對照相地址與路線那一段的路名是不是同一層。
 * 只在兩邊都看得出來而且不同層時才排除，看不出來的一律保留，寧可多提醒也不漏掉。
 */
object RoadLevels {
    /** 路線路名：高架、快速道路、陸橋、高速公路都算高架層 */
    private val ELEVATED_ROAD = Regex("高架|快速|陸橋|高速公路")

    /** 匝道、出口、交流道在兩層之間，看不出來 */
    private val TRANSITION = Regex("匝道|出口|入口|交流道|下橋|上橋")

    /** 路名要有這些字才算是看得出來的路（Google 的「繼續直行」之類沒有路名） */
    private val ROAD_WORD = Regex("路|街|道|橋|線")

    /**
     * 照相地址：明寫高架、快速道路、陸橋、國道、高速公路的才算高架層。
     * 「匝道口」是匝道接平面道路的路口，不算。
     */
    private val ELEVATED_CAMERA = Regex("高架|快速道路|快速公路|陸橋|國道\\d|國道[一二三四五六七八九十]|高速公路")

    /** 台61線、台64線等編號公路有些是快速公路，地址沒寫清楚時看不出來 */
    private val NUMBERED_HIGHWAY = Regex("[台臺]\\d+")

    /** 路線上一段路的層：OSRM 的路名，或 Google 導航指示的文字 */
    fun ofRoad(name: String): RoadLevel = when {
        name.isBlank() || TRANSITION.containsMatchIn(name) -> RoadLevel.UNKNOWN
        ELEVATED_ROAD.containsMatchIn(name) -> RoadLevel.ELEVATED
        ROAD_WORD.containsMatchIn(name) -> RoadLevel.SURFACE
        else -> RoadLevel.UNKNOWN
    }

    /** 測速照相的層：使用者回報的移動式測速沒有地址，看不出來 */
    fun ofCamera(camera: SpeedCamera): RoadLevel = when {
        camera.mobile -> RoadLevel.UNKNOWN
        ELEVATED_CAMERA.containsMatchIn(camera.address) -> RoadLevel.ELEVATED
        NUMBERED_HIGHWAY.containsMatchIn(camera.address) -> RoadLevel.UNKNOWN
        else -> RoadLevel.SURFACE
    }

    /** 提醒裡標示照相所在的道路類型，例如「高架」「國道」；平面道路或看不出來時為 null */
    fun tag(camera: SpeedCamera): String? {
        if (camera.mobile) return null
        val a = camera.address
        return when {
            a.contains("高架") -> "高架"
            a.contains("國道") || a.contains("高速公路") -> "國道"
            a.contains("快速道路") || a.contains("快速公路") -> "快速道路"
            a.contains("陸橋") -> "陸橋"
            else -> null
        }
    }

    /**
     * 照相在路線上 [km] 公里處（前後 [marginKm] 內）的路段，有任何一段和照相同層或看不出來就保留；
     * 沒有路名資料時也保留。
     */
    fun matches(camera: SpeedCamera, km: Double, roads: List<RoadSpan>, marginKm: Double = 0.05): Boolean {
        val cameraLevel = ofCamera(camera)
        if (cameraLevel == RoadLevel.UNKNOWN || roads.isEmpty()) return true
        val nearby = roads.filter { it.endKm >= km - marginKm && it.startKm <= km + marginKm }
        if (nearby.isEmpty()) return true
        return nearby.any { span ->
            val level = ofRoad(span.name)
            level == RoadLevel.UNKNOWN || level == cameraLevel
        }
    }

    /** 依各段距離（公尺）與路名排成從起點算起的路段 */
    fun spans(steps: List<Pair<Double, String>>): List<RoadSpan> {
        var km = 0.0
        return steps.map { (meters, name) ->
            val start = km
            km += meters / 1000
            RoadSpan(start, km, name.trim())
        }
    }
}
