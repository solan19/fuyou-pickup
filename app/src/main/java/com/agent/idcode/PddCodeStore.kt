package com.agent.idcode

import android.content.Context
import org.json.JSONObject

/**
 * 拼多多取件码的**持久化缓存**（按订单号）。
 *
 * ## 为什么要它（2026-09-20 同志的原话）
 * > 每次点进取件码重新刷新数据，之前已加载出来的取件码常态显示、不会因刷新消失；
 * > 常态显示的取件码，当刷新后发现已取出就自动消除它。
 *
 * 取件码是**易失的**：它要靠现开一次订单详情页、从物流文案里正则抠出来
 * （见 [PddTraceChannel]），而这一步可能因超时/风控/页面时序失败。
 * 以前每次刷新全靠现抓 ⇒ 这次没抠到就**整个消失**（同志报的"重新打开又不显示了"）。
 * ⇒ 抠到就按订单号存下来，**常态显示**；消除只认"已取出"这个信号，不认时间。
 *
 * ★ 消除的唯一依据：`express/track/status` 里该单的状态**不再是 IN_CABINET**
 *   （OUT_CABINET / GOT / SIGN 都表示已经取走了）。不设过期时间 —— 同志要的就是"不因刷新消失"。
 */
object PddCodeStore {

    private const val PREFS = "idcode_prefs"
    private const val KEY = "pdd_code_cache"

    /**
     * ★ vc229：「已取件」标记的保留时长 —— 打上标记起 **24 小时**后才真正清除。
     *
     * 为什么要"标记 + 延迟删"而不是"发现已取件就立刻删"（同志 2026-10-12 要求）：
     *   取完件卡片转头就没了，用户分不清"是我取过了"还是"App 刷丢了"；
     *   留一张打了「已取件」的卡，既交代了去向，又不会一直占着列表（24h 后自动清掉）。
     */
    const val PICKED_KEEP_MS = TimeGates.PICKED_TAG_KEEP_MS

    /** 打上「已取件」标记了吗 */
    fun isPicked(c: Cached): Boolean = c.pickedAt != 0L

    /**
     * ★ vc609：**按文案判"这单其实已经取走了"**。
     *
     * 起因（同志 2026-10-30 截图）：缓存卡上写着「已取件 2026-10-24 … 您的快件已取走」，
     * 取件码 21-1-5009 还是 10-24 的旧码，却仍然占着取件码栏。
     * 根因：终态判定用的是**精确词表**（statusText in listOf("已取件"…)），
     * 而拼多多给的是长句 ⇒ 永不命中。
     *
     * 判定规则：文案里出现终态词即算已取走；但含「待确认 / 待收货 / 待取」时**不算**
     * （那是驿站代签、件还在架上，见 vc347 取证）。
     */
    /** ★★ vc617：终态词统一引用 [PickupVerdict]，不再单独维护一份 */
    private val DEAD_WORDS: List<String> =
        PickupVerdict.HARD_TERMINAL + PickupVerdict.SOFT_TERMINAL

    fun looksTaken(c: Cached): Boolean {
        val t = c.latest
        if (t.isBlank()) return false
        if (t.contains("待确认") || t.contains("待收货") || t.contains("待取")) return false
        return DEAD_WORDS.any { t.contains(it) }
    }

    /** 标记已满 24 小时 ⇒ 可以真正清除了 */
    fun pickedExpired(c: Cached, now: Long = System.currentTimeMillis()): Boolean =
        c.pickedAt != 0L && now - c.pickedAt > PICKED_KEEP_MS

    data class Cached(
        /** 来源：pdd / cainiao / jd（缓存键 = "src:单号"，一份存储管三个源） */
        val src: String,
        val code: String,
        val station: String,
        val waybill: String,
        val latest: String,
        val ts: Long,
        /** 0 = 还在待取；非 0 = 判定为「已取件」的时刻(ms) ⇒ 卡片打标记，24h 后清除 */
        val pickedAt: Long = 0L,
        /**
         * ★ vc655：**这个码是哪个账号补出来的**（"主号" / "副号1"…，空 = 未知）。
         *
         * 为什么要它：`PddChannel.lastTrackStatus` 是**全进程共用一张表**，
         * 主号和副号的状态会混在一起。出问题时（码莫名其妙消失 / 重复）
         * 日志里必须能看出"这码归谁"，否则只能猜。
         */
        val account: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("src", src)
            put("code", code)
            put("station", station)
            put("waybill", waybill)
            put("latest", latest)
            put("ts", ts)
            put("pickedAt", pickedAt)
            put("account", account)
        }

        companion object {
            fun from(o: JSONObject): Cached = Cached(
                src = o.optString("src"),
                code = o.optString("code"),
                station = o.optString("station"),
                waybill = o.optString("waybill"),
                latest = o.optString("latest"),
                ts = o.optLong("ts"),
                // 旧数据没有这个字段 ⇒ optLong 给 0（按"还在待取"处理）
                pickedAt = o.optLong("pickedAt"),
                account = o.optString("account")
            )
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ★ vc281：后台 UiCacheSync（WorkManager 线程）现在也会写这份缓存，
    //   与 UI 主线程的读写可能撞车（后写的整表覆盖先写的单条）⇒ 全部方法加锁串行。
    @Synchronized
    fun all(ctx: Context): LinkedHashMap<String, Cached> {
        val out = LinkedHashMap<String, Cached>()
        val raw = sp(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return out
        return try {
            val o = JSONObject(raw)
            val it = o.keys()
            val nowMs = System.currentTimeMillis()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optJSONObject(k) ?: continue
                var c = Cached.from(v)
                // ★★ vc674：缓存条目自己没标「已取件」时，按**包裹身份**（取件码/运单号）
                //   再问一次 [PickedStore] —— 重启后同件被别的来源重新写进来（新键、
                //   pickedAt=0）时，就靠这一步把标记找回来，否则「退出重进又回来了」。
                if (c.pickedAt == 0L) {
                    val t = PickedStore.of(ctx, listOf(c.code, c.waybill))
                    if (t > 0L) c = c.copy(pickedAt = t)
                }
                // ★ vc605：**48h 未更新且还没打灰标的条目不再返回**。
                //   同志实测：昨天 18:54 抓到的 6 个码，今天平台已经 0 件了，首页还挂着"6 件待取"
                //   ⇒ 观感就是"假数据"。这里只过滤不删除，真正清盘交给"平台说 0 件"那条链。
                if (c.pickedAt == 0L && c.ts > 0 && nowMs - c.ts > 48L * 3600_000L) continue
                out[k] = c
            }
            out
        } catch (_: Throwable) {
            out
        }
    }

    fun get(ctx: Context, sn: String): Cached? = all(ctx)[sn]

    @Synchronized
    /**
     * ★ vc470：写缓存时**自动保留「已取件」标记**（pickedAt）。
     *
     * 同志 10-26 报：长按打上「已取件」后，一刷新标记就没了。
     * 根因：刷新链里有多处 `put(... Cached(...))` 是**整条重建**（补码/补地址/enrich 写码），
     * 新 Cached 的 pickedAt 默认 0 ⇒ 把用户手动打的标记覆盖掉了。
     * 这里统一兜住：新值 pickedAt=0 且旧值有标记 ⇒ 沿用旧标记。
     * 需要**真正清除**标记时走 [putForce]（「取消已取件」用）。
     */
    fun put(ctx: Context, sn: String, c: Cached) {
        if (sn.isBlank() || c.code.isBlank()) return
        val map = all(ctx)
        val old = map[sn]
        // ★ vc655：pickedAt 与 account 都沿用旧值（新值为空时），避免整条重建把归属抹掉
        val merged = c.copy(
            pickedAt = if (c.pickedAt == 0L) old?.pickedAt ?: 0L else c.pickedAt,
            account = if (c.account.isBlank()) old?.account.orEmpty() else c.account
        )
        map[sn] = merged
        save(ctx, map)
    }

    /** 跳过保留逻辑（用户主动取消「已取件」时用） */
    fun putForce(ctx: Context, sn: String, c: Cached) {
        if (sn.isBlank() || c.code.isBlank()) return
        val map = all(ctx)
        map[sn] = c
        save(ctx, map)
    }

    /** 该单已取走 ⇒ 从缓存删除（自动消除，卡片随之消失） */
    @Synchronized
    fun remove(ctx: Context, sn: String) {
        if (sn.isBlank()) return
        val map = all(ctx)
        if (map.remove(sn) == null) return
        save(ctx, map)
    }

    private fun save(ctx: Context, map: Map<String, Cached>) {
        val o = JSONObject()
        for ((k, v) in map) if (v.code.isNotBlank()) o.put(k, v.toJson())
        sp(ctx).edit().putString(KEY, o.toString()).apply()
    }
}
