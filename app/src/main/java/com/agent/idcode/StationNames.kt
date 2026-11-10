package com.agent.idcode

/**
 * ★★ v1.4.8：**驿站名 / 轨迹状态句的区分。**
 *
 * ## 为什么要有这个文件
 * 同志 2026-11-10 报："显示已取件，但我没有取件"。
 * 真机 dump（pickup_cache.txt）：
 * ```
 * pdd:261107-067916295711958 | code=114-4-9453 | pickedAt=0
 *   station=已取件2026-11-09 19:11:45 您的快件已领取，收件人在[代收点](菜鸟驿站)取件…
 * pdd:261107-211822865951958 | code=73-3-5888  | pickedAt=799m前
 *   station=已取件2026-11-09 19:11:45 您的快件已领取，收件人在[代收点](菜鸟驿站)取件…
 * ```
 * 两个件的 `station`（本站点字段）里装的**不是地名，是一条签收轨迹**。
 *
 * 根因在 `TrackFetcher` 补轨迹那条链：原来直接
 * `parseTraceLines(text).firstOrNull { 含"驿站" || 含"代收点" || 含"快递柜" }`
 * 就当地名用 —— 而「您的快件已领取，收件人在[代收点](菜鸟驿站)取件」恰好同时命中
 * "代收点"和"驿站"，于是整句被当成站名写进缓存，卡片副标题就显示「已取件…」。
 * 用户看到「已取件」三个字，自然以为系统判定他取过件了。
 *
 * ## 判据
 * 地名是**短语**（"菜鸟驿站"、"丰巢快递柜"、"XX小区北门代收点"）；
 * 轨迹是**一句话**（带日期、"已…"状态词、"请联系/如有疑问"客服用语）。
 * 只要命中任何一条"像轨迹"的特征，就不许当地名用。
 */
object StationNames {

    /** 状态/动作词 —— 出现在地名里几乎不可能 */
    private val STATUS_WORDS = listOf(
        "已取件", "已领取", "已取走", "已签收", "已投递", "已收货", "已完成",
        "已到达", "已到站", "已入柜", "已退回", "已取消", "已退款",
        "派送中", "运输中", "待取件", "待收货", "待确认", "已存放",
        "取件码", "请联系", "如有疑问", "超时", "暂存", "签收人"
    )

    /** 以「2026-11-09 19:11:45」这类时间开头的一定是轨迹行 */
    private val TIME_PREFIX = Regex("^\\s*\\d{4}-\\d{1,2}-\\d{1,2}[ T]\\d{1,2}:\\d{2}")

    /** 地名是人给的点位名，不该超过这个长度；超了基本就是一句话 */
    private const val MAX_NAME_LEN = 40

    /** 是不是一条轨迹/状态句（= 不能当地名用） */
    fun isTraceSentence(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.length > MAX_NAME_LEN) return true
        if (TIME_PREFIX.containsMatchIn(t)) return true
        return STATUS_WORDS.any { t.contains(it) }
    }

    /** 清洗：像轨迹就返回空串（宁可没有站名，也不能显示"已取件"） */
    fun clean(s: String): String = if (isTraceSentence(s)) "" else s.trim()

    /**
     * 从轨迹行里挑站名（只在**原值本来就是空**时才用）。
     * 命中"驿站/代收点/快递柜"还不够，还必须是干净的地名。
     */
    fun fromTrace(lines: List<String>): String? {
        for (s in lines) {
            val t = s.trim()
            if (t.isEmpty()) continue
            if (!(t.contains("驿站") || t.contains("代收点") || t.contains("快递柜"))) continue
            if (isTraceSentence(t)) continue
            return t
        }
        return null
    }
}
