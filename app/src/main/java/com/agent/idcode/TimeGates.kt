package com.agent.idcode

/**
 * ★★ vc617：**所有业务闸门集中在这里**（"多久算旧 / 多久算过期 / 多久才能再抓一次"）。
 *
 * ## 为什么要有这个文件
 * 改之前这些数字散在 6 个文件里（MainActivity / PddChannel / PddCodeStore / JdChannel …），
 * 想知道"取件码缓存多久过期"得全局搜 `86_400_000`。同志反馈"规则非常乱"，
 * 一半乱在这里：**同一种时间概念有多个写法，改一个忘一个**。
 *
 * ## 使用约定
 * 1. 只放"业务语义"的闸门；UI 动画时长属于 `UiTokens`，不要混进来；
 * 2. 新增闸门先在这里加常量，再到调用处引用 —— **不许在业务代码里写字面量**；
 * 3. 各文件里保留原来的字段名（`PICKED_KEEP_MS` 等），但值指向这里，避免大改调用方。
 */
object TimeGates {

    // ---------------- 数据新鲜度 ----------------

    /** 订单年龄上限：超过就不是"待取件"（15 天，同志 2026-10-31 定） */
    const val ORDER_MAX_AGE_DAYS = 15L

    /** 取件码缓存的有效期：超过 48h 未更新就不再显示（防止"昨天的码今天还挂着"） */
    const val CODE_CACHE_TTL_HOURS = 48L

    /** 「已取件」灰标的保留时长：打标后还留 24h，让用户知道这单取过了 */
    const val PICKED_TAG_KEEP_HOURS = 24L

    /** 拼多多枚举状态（track/status）的落盘有效期 */
    const val ENUM_STATUS_KEEP_DAYS = 7L

    /** 拼多多取件码缓存保留天数（与老单闸同向，7 天） */
    const val PDD_CODE_KEEP_DAYS = 7

    /** 在途缓存（transit_cache.json）的保留时长 */
    const val TRANSIT_CACHE_KEEP_MS = 6L * 60 * 60 * 1000

    /** 在途列表（上一轮抓取结果）的保留时长 */
    const val TRANSIT_LIST_KEEP_MS = 72L * 60 * 60 * 1000

    /** 京东收货地址缓存有效期 */
    const val JD_ADDR_TTL_MS = 30L * 24 * 3600_000L

    // ---------------- 抓取节流 ----------------

    /** 进页面自动刷新的防抖间隔（vc594：90s → 10s） */
    const val ORDER_LIST_MIN_INTERVAL_MS = 10_000L

    /** 单个数据源的单轮超时 */
    const val PER_SOURCE_TIMEOUT_MS = 20_000L

    /** 单张卡的补码作业限频（同一单多久内不再重试补码） */
    const val CODE_JOB_MIN_GAP_MS = 3L * 60_000L

    /** 补码作业的启动防抖 */
    const val CODE_JOB_MIN_INTERVAL_MS = 10_000L

    /** 拼多多登录失效后的静默期：这段时间内后台不再空跑（避免触碰风控） */
    const val PDD_DEAD_QUIET_MS = 6L * 60 * 60 * 1000

    // ---------------- 便捷换算 ----------------

    const val DAY_MS = 86_400_000L
    const val HOUR_MS = 3_600_000L

    /** 订单年龄上限（毫秒）——直接给比较用 */
    const val ORDER_MAX_AGE_MS = ORDER_MAX_AGE_DAYS * DAY_MS

    /** 取件码缓存 TTL（毫秒） */
    const val CODE_CACHE_TTL_MS = CODE_CACHE_TTL_HOURS * HOUR_MS

    /** 灰标保留（毫秒） */
    const val PICKED_TAG_KEEP_MS = PICKED_TAG_KEEP_HOURS * HOUR_MS

    /** 枚举状态保留（毫秒） */
    const val ENUM_STATUS_KEEP_MS = ENUM_STATUS_KEEP_DAYS * DAY_MS
}
