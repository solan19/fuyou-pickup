package com.agent.idcode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后台快递跟踪的**数据底座**：包裹快照 + 跟踪名单 + 轮询设置。
 *
 * ## 设计原则（对齐参考项目 Halo0sama/ExpressAssistant，MIT）
 * 1. **快照是全量的**，App 与 [TrackWorker] 共用同一份，谁跑谁覆盖写。
 * 2. 「有没有变化」只比 `(statusText, latest)` 这两个字符串，不做复杂 diff ——
 *    我们的包裹 JSON 里没有时间戳字段，而这两项任一变化都等价于「有新的物流动态」。
 * 3. **首次见到某个单号只记基线、不通知** —— 否则刚开跟踪就会被历史轨迹刷屏。
 * 4. **抓取为空不覆盖旧快照** —— 后台抓取偶发空页（尤其 WebView 那几条），
 *    拿空结果覆盖会把基线清掉，下次真变化时反而漏通知。
 *
 * ## 为什么单独一个 object 而不是塞进 MainActivity
 * [TrackWorker] 在**没有 Activity** 的进程里跑（WorkManager 起的），
 * 碰不到 MainActivity 的任何成员；所有跨进程要读的东西都必须落在 SP 里。
 */
object ExpressStore {

    private const val PREFS = "idcode_prefs"

    /** 全量包裹快照（JSON 数组字符串） */
    private const val KEY_SNAPSHOT = "track_snapshot"

    /** 已开启跟踪的单号集合（JSON 数组字符串）—— 仅「指定」模式用它 */
    private const val KEY_TRACKED = "track_nos"

    /** 「全部」模式下被手动屏蔽的单号（长按 = 不再提醒这单） */
    private const val KEY_MUTED = "track_muted"

    /** 跟踪范围：0 = 全部（默认），1 = 指定（手动长按） */
    private const val KEY_TRACK_MODE = "track_mode"

    /** 轮询间隔（分钟）。0 = 关闭，这是默认值 */
    private const val KEY_POLL_MIN = "poll_interval_min"

    /** WorkManager 的周期下限，低于它会直接抛 IllegalArgumentException */
    const val MIN_INTERVAL_MIN = 15

    /**
     * 一条包裹记录。字段与第 4 栏的 `JSONObject` 对应，只挑「跟踪需要 + 通知要显示」的那几个，
     * 不整包存 JSON —— 避免以后上游字段改名就把旧快照读废。
     */
    data class Item(
        val no: String,
        val src: String = "",
        val company: String = "",
        val statusText: String = "",
        val latest: String = "",
        val pickupCode: String = "",
        val stationText: String = ""
    ) {
        /** 变化判定的指纹 */
        fun fingerprint(): String = "$statusText\u0001$latest"

        fun toJson(): JSONObject = JSONObject().apply {
            put("no", no)
            put("src", src)
            put("company", company)
            put("statusText", statusText)
            put("latest", latest)
            put("pickupCode", pickupCode)
            put("stationText", stationText)
        }

        companion object {
            fun from(o: JSONObject) = Item(
                no = o.optString("no"),
                src = o.optString("src"),
                company = o.optString("company"),
                statusText = o.optString("statusText"),
                latest = o.optString("latest"),
                pickupCode = o.optString("pickupCode"),
                stationText = o.optString("stationText")
            )
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 快照 ----------------

    fun load(ctx: Context): List<Item> {
        val raw = sp(ctx).getString(KEY_SNAPSHOT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Item.from(it) }?.takeIf { it.no.isNotBlank() }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        sp(ctx).edit().putString(KEY_SNAPSHOT, arr.toString()).apply()
    }

    /** 清掉明确属于某个来源的快照与跟踪单号；来源不明的旧记录保留。 */
    fun clearSource(ctx: Context, source: String) {
        val aliases = if (source == "tb") setOf("tb", "cainiao") else setOf(source)
        val old = load(ctx)
        val removedNos = old.filter { it.src in aliases }.map { it.no }.toSet()
        val remaining = old.filterNot { it.src in aliases }
        if (remaining.size != old.size) save(ctx, remaining)
        val tracked = trackedNos(ctx)
        // 纯单号跟踪项没有来源信息，不能因为同号就误删；只删除明确带来源前缀的旧项。
        val kept = tracked.filterNot { no ->
            aliases.any { alias -> no.startsWith("$alias:") }
        }.toSet()
        if (kept.size != tracked.size) {
            val arr = JSONArray()
            for (no in kept) arr.put(no)
            sp(ctx).edit().putString(KEY_TRACKED, arr.toString()).apply()
        }
    }

    /**
     * 用新抓到的列表覆盖快照；**新列表为空时保持旧快照不动**（防误清基线）。
     * 返回是否真的写入了。
     */
    fun mergeSnapshot(ctx: Context, fresh: List<Item>): Boolean {
        if (fresh.isEmpty()) return false
        save(ctx, fresh)
        return true
    }

    // ---------------- 跟踪名单 ----------------

    fun trackedNos(ctx: Context): Set<String> {
        val raw = sp(ctx).getString(KEY_TRACKED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun isTracked(ctx: Context, no: String): Boolean = no in trackedNos(ctx)

    fun setTracked(ctx: Context, no: String, on: Boolean) {
        if (no.isBlank()) return
        val set = trackedNos(ctx).toMutableSet()
        if (on) set.add(no) else set.remove(no)
        val arr = JSONArray()
        for (s in set) arr.put(s)
        sp(ctx).edit().putString(KEY_TRACKED, arr.toString()).apply()
    }

    fun toggleTracked(ctx: Context, no: String): Boolean {
        val now = !isTracked(ctx, no)
        setTracked(ctx, no, now)
        return now
    }

    // ---------------- 轮询设置 ----------------

    /** 轮询间隔（分钟）。0 = 关闭（默认） */
    fun pollIntervalMin(ctx: Context): Int = sp(ctx).getInt(KEY_POLL_MIN, 0)

    fun savePollIntervalMin(ctx: Context, minutes: Int) {
        sp(ctx).edit().putInt(KEY_POLL_MIN, minutes.coerceIn(0, 720)).apply()
    }

    /** 开启跟踪时若从未配置过轮询，自动补 15 分钟（与参考项目同款体验） */
    fun ensurePollingDefault(ctx: Context) {
        if (pollIntervalMin(ctx) <= 0) savePollIntervalMin(ctx, MIN_INTERVAL_MIN)
    }

    // ---------------- 跟踪范围（同志 2026-09-20 定的规则）----------------

    /** 全部：所有包裹都跟踪，但只在「派件中 / 已到达」两个节点发声 */
    const val MODE_ALL = 0

    /** 指定：只有长按标记过的才跟踪，有变化就通知（原始效果） */
    const val MODE_MANUAL = 1

    /**
     * 包裹所处的阶段。用来把通知压到最低噪音：
     * 同志定的规则是「全部默认跟踪，但**只有派件中、已到达**两种情况才通知」——
     * 运输中每一跳都弹一次等于骚扰，所以按"阶段跃迁"而不是"文案变化"来判。
     */
    enum class Stage { NORMAL, DELIVERING, ARRIVED, DONE }

    private val W_DELIVERING = listOf(
        "派送", "派件", "投递", "送货", "正在派", "派件员", "出库派送", "安排投递", "配送中", "送件"
    )

    /** 「到取件点」的**强**关键词。注意这里刻意**不含泛化的「到达」** —— 见 [W_TRANSIT_HUB] */
    private val W_ARRIVED = listOf(
        "待取", "取件", "入柜", "入站", "驿站", "快递柜", "自提", "已存放", "已放至",
        "取件码", "柜机", "代收点", "已到店", "请前往", "领取", "已代收"
    )

    /**
     * ★★ 中转语义（2026-09-20 闭环自检抓出来的真 bug）。
     *
     * 运输轨迹里「**已到达郑州转运中心**」是最常见的一句，而它跟「已到达取件点」是两回事。
     * 原来的关键词表里有泛化的「到达」⇒ 每到一个中转站都会被判成 ARRIVED ⇒
     * **每个中转站误报一次「已到达」**。自检用例直接把它抓了出来（9/10）。
     * ⇒ 判 ARRIVED 之前先排除中转词。
     */
    private val W_TRANSIT_HUB = listOf(
        "转运", "中转", "集散", "分拨", "经转", "发往", "离开", "航空", "干线", "互换局"
    )

    private val W_DONE = listOf(
        "已签收", "已取件", "已取货", "已收货", "已完成", "已取消", "已退款", "交易关闭", "已退回"
    )

    /**
     * 从 statusText + latest 判阶段。
     *
     * 判定顺序**不能换**：
     *   ① DONE 最优先（「已签收」里可能同时含别的词）
     *   ② DELIVERING（「派送」优先级高于「到达」：「已到达网点，正在派送」应算派件中）
     *   ③ **中转排除**（「已到达XX转运中心」不算到达）
     *   ④ ARRIVED 强关键词
     *   ⑤ 泛化的「到达/已到」兜底（此时中转已被 ③ 排除，所以「快件已到达」这种也能认出来）
     */
    fun stageOf(item: Item): Stage {
        val s = item.statusText + " " + item.latest
        if (W_DONE.any { s.contains(it) }) return Stage.DONE
        if (W_DELIVERING.any { s.contains(it) }) return Stage.DELIVERING
        if (W_TRANSIT_HUB.any { s.contains(it) }) return Stage.NORMAL
        if (W_ARRIVED.any { s.contains(it) }) return Stage.ARRIVED
        if (s.contains("到达") || s.contains("已到")) return Stage.ARRIVED
        return Stage.NORMAL
    }

    fun trackMode(ctx: Context): Int = sp(ctx).getInt(KEY_TRACK_MODE, MODE_ALL)

    fun saveTrackMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_TRACK_MODE, if (mode == MODE_MANUAL) MODE_MANUAL else MODE_ALL).apply()
    }

    // ---------------- ★ 闭环：通知判定（唯一来源）----------------

    /**
     * 「这条该不该发通知」—— **整个后台跟踪链路的唯一判定点**。
     *
     * ★ 为什么单独抽出来：`TrackWorker` 和**闭环自检**（设置页的调试入口）都要走同一套判定，
     *   在两处各写一遍迟早走叉 —— 自检会变成"测了另一份逻辑"，等于没测。
     *
     * @param prev 上一份快照里的同号件；**null = 首次见到** ⇒ 运输中/已完成只记基线，
     *             但**已在驿站/派件中的补报一条**（vc301，见方法体内注释）
     * @return 非空 = 该通知，值同时作为原因文案（日志/自检输出都靠它）
     *
     * 判定口径（同志 2026-09-20 定）：
     *  - **全部模式**：所有包裹都跟踪，但只在**阶段跃迁到「派件中」或「已到达」**时通知
     *    （判跃迁不判文案 —— 运输中每一跳都弹一次等于骚扰）；
     *  - **指定模式**：只盯名单里的，有变化就通知（原效果）；
     *  - 两种模式下，`muted`（全部模式下长按屏蔽的）都不通知。
     */
    fun notifyReason(
        prev: Item?, now: Item, mode: Int, tracked: Set<String>, muted: Set<String>
    ): String? {
        val watched = if (mode == MODE_ALL) now.no !in muted else now.no in tracked
        if (!watched) return null
        if (prev == null) {
            // ★ vc301：首见到站/派件 ⇒ **补报**。以前首见一律只记基线，但"首见"的成因
            //   往往是：旧版本漏收该源（2026-10-17 极兔件实测漏报）、新装 App、或该单
            //   刚进列表 —— 此时件已经在驿站甚至已派件，"只记基线"会让用户永远收不到
            //   这条**最该知道**的通知。运输中/已完成的首见照旧静默（不刷屏、不扰）。
            return when (stageOf(now)) {
                Stage.ARRIVED -> "已到达"
                Stage.DELIVERING -> "派件中"
                else -> null
            }
        }
        return if (mode == MODE_ALL) {
            val from = stageOf(prev)
            val to = stageOf(now)
            when {
                to == from -> null                            // 阶段没动
                to == Stage.DELIVERING -> "派件中"
                to == Stage.ARRIVED -> "已到达"
                else -> null                                  // 回到运输中/完成：不打扰
            }
        } else {
            if (prev.fingerprint() != now.fingerprint()) "有变化" else null
        }
    }

    // ---------------- 屏蔽名单（「全部」模式下的例外）----------------

    // ★★ vc620：屏蔽名单统一到 [MuteStore]（原来这里是独立的 `track_muted`，
    //   于是"卡片删了但后台还通知"）。保留函数名，调用方不用改。
    fun mutedNos(ctx: Context): Set<String> = MuteStore.nos(ctx)

    fun isMuted(ctx: Context, no: String): Boolean = MuteStore.isMutedNo(ctx, no)

    fun toggleMuted(ctx: Context, no: String): Boolean = MuteStore.toggleNo(ctx, no)

    @Deprecated("已合并到 MuteStore.toggleNo", ReplaceWith("MuteStore.toggleNo(ctx, no)"))
    private fun toggleMutedLegacy(ctx: Context, no: String): Boolean {
        if (no.isBlank()) return false
        val set = mutedNos(ctx).toMutableSet()
        val now = if (no in set) {
            set.remove(no); false
        } else {
            set.add(no); true
        }
        writeSet(ctx, KEY_MUTED, set)
        return now
    }

    // ---------------- 集合读写 ----------------

    private fun readSet(ctx: Context, key: String): Set<String> {
        val raw = sp(ctx).getString(key, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun writeSet(ctx: Context, key: String, set: Set<String>) {
        val arr = JSONArray()
        for (s in set) arr.put(s)
        sp(ctx).edit().putString(key, arr.toString()).apply()
    }

    /** 是否在途（决定该单还要不要继续轮询）。判据与第 4 栏的「统一取件规则」保持一致 */
    fun isTransport(item: Item): Boolean {
        val st = item.statusText
        val done = listOf(
            "已取货", "已取件", "已签收", "已收货", "已完成", "已取消", "已退款", "交易关闭"
        )
        return done.none { st.contains(it) }
    }

    /** 从第 4 栏的包裹 JSON 造一条快照记录（App 侧抓完数据后调用） */
    fun fromPkg(p: JSONObject): Item? {
        val no = p.optString("waybillNo").ifBlank { p.optString("pickupCode") }
        if (no.isBlank()) return null
        val src = p.optString("src")
        // ★ vc345：取件地址可能落在三个字段里 —— 菜鸟/淘宝写 stationText；
        //   拼多多直连补卡写 stationName/nickName（vc341）；快递柜单有时只有柜名。
        //   流体云与通知都要用它，出口处统一收一次，别让"卡上有、通知里没有"。
        val station = p.optString("stationText").ifBlank {
            p.optString("stationName").ifBlank {
                if (src == "pdd") p.optString("nickName") else ""
            }
        }
        return Item(
            no = no,
            src = src,
            company = p.optString("upCpName").ifBlank { p.optString("upCpCode") },
            statusText = p.optString("statusText"),
            latest = p.optString("latest").ifBlank { station },
            pickupCode = p.optString("pickupCode"),
            stationText = station
        )
    }
}
