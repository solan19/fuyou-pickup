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
     * ★★ v1.4.15：**已作废的取件码**（一行一个，持久）。
     *
     * 同志 2026-11-10 14:09：「73-3-5888 是沐浴露的取件码，因为我已经取件了，但是他还在缓存，
     * 所以会造成重复的出现，并且没有匹配的快递」。
     *
     * 格口码一个时刻只属于一件。同一个码在缓存里挂到 ≥2 张单上，只可能是抽取污染
     * （详情页把上一次的码复用到别的单）⇒ 这个码对**任何**一单都不能再作数。
     * 但抓取链下一轮会照旧把它抠出来重新写进来 —— 所以光清缓存没用，得记住"这码是废的"。
     * 只在「重置取件码缓存」时整表清掉（届时连缓存一起重抓，等于重来一遍）。
     */
    private const val KEY_DEAD = "pdd_code_dead"

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
        val account: String = "",
        /**
         * ★ v1.4.24：**这条码是哪个 App 的通知送来的**（平台 id，见 [NotifyRules]）。
         * 空 = 接口来源或旧数据。`src` 仍是 "ntf"，所有既有 src 判断链不受影响。
         */
        val platform: String = ""
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
            put("platform", platform)
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
                account = o.optString("account"),
                // 旧数据没有这个字段 ⇒ optString 给空串（按"接口/未知来源"处理）
                platform = o.optString("platform")
            )
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ★ vc281：后台 UiCacheSync（WorkManager 线程）现在也会写这份缓存，
    //   与 UI 主线程的读写可能撞车（后写的整表覆盖先写的单条）⇒ 全部方法加锁串行。
    @Synchronized
    fun all(ctx: Context): LinkedHashMap<String, Cached> = load(ctx, skipDead = true)

    /** 原始条目（含作废码）—— 只有 [sweepShared] 需要，用来发现"一码多单" */
    @Synchronized
    fun allRaw(ctx: Context): LinkedHashMap<String, Cached> = load(ctx, skipDead = false)

    /**
     * @param skipDead 遇到 [deadCodes] 里的码怎么办。
     * · true（读给界面/裁决用）：**只把码抹掉，条目留着** —— 条目里有 pickedAt/waybill，
     *   抹掉整条的话「已取件」标记跟着消失，那件反而会重新回到在途栏；
     * · false（清扫用）：原样返回，否则清扫永远看不到那条码。
     */
    private fun load(ctx: Context, skipDead: Boolean): LinkedHashMap<String, Cached> {
        val out = LinkedHashMap<String, Cached>()
        val raw = sp(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return out
        return try {
            val o = JSONObject(raw)
            val it = o.keys()
            val nowMs = System.currentTimeMillis()
            val dead = if (skipDead) deadCodes(ctx) else HashMap<String, Long>()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optJSONObject(k) ?: continue
                var c = Cached.from(v)
                if (c.code.isNotBlank() && dead.containsKey(normCode(c.code))) c = c.copy(code = "")
                // ★★ vc674：缓存条目自己没标「已取件」时，按**包裹身份**（订单号/运单号）
                //   再问一次 [PickedStore] —— 重启后同件被别的来源重新写进来（新键、
                //   pickedAt=0）时，就靠这一步把标记找回来，否则「退出重进又回来了」。
                //   ★★ v1.4.11：**不再拿取件码去问**。格口码会被驿站复用（同志 11-10：
                //   114-4-9453 昨天属于已取走的件、今天分给了刚到站的润唇膏），
                //   用码反查会让"上一件的取件动作"传染给下一件。
                if (c.pickedAt == 0L) {
                    // ★★ v1.4.28：通知来源的键是 `ntf:<取件码>`，尾巴就是码本身 —— 拿它去问
                    //   [PickedStore] 等于「用码反查」，而格口码会被驿站回收复用（v1.4.11 已明令禁止
                    //   用码判身份）。后果：刚抓到的通知码可能立刻被上一件的取件动作传染成
                    //   「已取件」⇒ 页面上根本不显示（同志报的「抓到的码刷新后就没了」的一条路径）。
                    //   通知条目只在有运单号时才按身份回查。
                    val ids = if (c.src == "ntf") {
                        listOfNotNull(c.waybill.takeIf { it.isNotBlank() })
                    } else {
                        listOf(c.waybill, k.substringAfter(':'))
                    }
                    val t = if (ids.isEmpty()) 0L else PickedStore.of(ctx, ids)
                    if (t > 0L) c = c.copy(pickedAt = t)
                }
                // ★★ v1.4.8：**历史脏站名自愈**。1.4.8 之前 [TrackFetcher] 会把
                //   「已取件…您的快件已领取，收件人在[代收点](菜鸟驿站)取件」这种**轨迹句**
                //   当成站名写进来，卡片副标题于是显示"已取件"，用户以为系统判他取过件了。
                //   读的时候直接丢掉脏值（不在这里写盘 —— all() 是高频读路径），
                //   显示退回「无站名」，下一轮抓取会按真实站点字段重补。
                if (c.station.isNotBlank() && StationNames.isTraceSentence(c.station)) {
                    c = c.copy(station = "")
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
        // ★★ v1.4.15：作废码一律拒写 —— 否则抓取链每轮都会把这个借来的码重新贴回去
        if (isDead(ctx, c.code)) {
            android.util.Log.i(
                "PddCodeMatch",
                "拒写已作废的码 ${c.code} → $sn（一码多单污染，见 sweepShared）"
            )
            return
        }
        val map = all(ctx)
        val old = map[sn]
        // ★ vc655：pickedAt 与 account 都沿用旧值（新值为空时），避免整条重建把归属抹掉
        val merged = c.copy(
            pickedAt = if (c.pickedAt == 0L) old?.pickedAt ?: 0L else c.pickedAt,
            account = if (c.account.isBlank()) old?.account.orEmpty() else c.account,
            // ★ v1.4.24：整条重建的补码作业不该把通知来源的平台标签抹掉
            platform = if (c.platform.isBlank()) old?.platform.orEmpty() else c.platform
        )
        map[sn] = merged
        save(ctx, map)
    }

    /** 跳过保留逻辑（用户主动取消「已取件」时用） */
    fun putForce(ctx: Context, sn: String, c: Cached) {
        if (sn.isBlank() || c.code.isBlank()) return
        if (isDead(ctx, c.code)) {
            android.util.Log.i("PddCodeMatch", "拒写已作废的码 ${c.code} → $sn（putForce）")
            return
        }
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

    /**
     * ★★ v1.4.13：**清空全部取件码缓存**（同志 2026-11-10 13:50：「可以增加一个选项，用户可以点击，
     * 此时清除取件码的所有缓存和数据，重新抓取」）。
     *
     * 用于码彻底乱掉的时候（同一个码挂在好几单、真码被借来的码顶掉、首页少了某件）——
     * 与其一条条猜哪条是错的，不如整份丢掉重抓一次，让平台自己重新报一遍。
     * 只动取件码这一份存储；在途缓存与黑名单不归这里管。
     */
    @Synchronized
    fun clearAll(ctx: Context) {
        try {
            sp(ctx).edit().remove(KEY).remove(KEY_DEAD).apply()
            android.util.Log.i("PddCache", "已清空全部取件码缓存（含作废码表）")
        } catch (t: Throwable) {
            SafeRun.w("PddCache", t)
        }
    }

    // ---------------- ★★ v1.4.15：作废码表 ----------------

    private fun normCode(s: String) = s.trim().lowercase()

    /**
     * 作废码的**有效期** —— 与取件码本身的认人时限同一个口径（[MuteStore] 的 7 天）。
     * 格口码会被驿站回收再发，永久作废等于把这个码号段废掉。
     */
    private const val DEAD_TTL_MS = 7L * 86_400_000L

    /** 已作废的码 → 作废时刻（小写比较；超过 7 天的自动失效） */
    @Synchronized
    fun deadCodes(ctx: Context): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        val raw = sp(ctx).getString(KEY_DEAD, "") ?: ""
        if (raw.isBlank()) return out
        val now = System.currentTimeMillis()
        for (line in raw.split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val code = t.substringBefore('|').trim()
            val ts = t.substringAfter('|', "0").trim().toLongOrNull() ?: 0L
            if (code.isEmpty()) continue
            if (ts > 0L && now - ts > DEAD_TTL_MS) continue
            out[code] = ts
        }
        return out
    }

    fun isDead(ctx: Context, code: String): Boolean {
        val c = normCode(code)
        return c.isNotEmpty() && deadCodes(ctx).containsKey(c)
    }

    @Synchronized
    fun markDead(ctx: Context, code: String) {
        val c = normCode(code)
        if (c.isEmpty()) return
        val map = deadCodes(ctx)
        if (map.containsKey(c)) return
        map[c] = System.currentTimeMillis()
        sp(ctx).edit()
            .putString(KEY_DEAD, map.entries.joinToString("\n") { "${it.key}|${it.value}" })
            .apply()
    }

    /**
     * ★★ v1.4.15：**一码多单 ⇒ 该码整批作废**（扫一遍缓存，参数为空，可每轮调用）。
     *
     * 判据只有一条：同一个非空取件码出现在 ≥2 个缓存条目上。
     * 处理分两种：
     * · 其中**有**条目打了「已取件」⇒ 那条才是真主（同志：「73-3-5888 是沐浴露的，
     *   我已经取件了」），留它走完 24h 灰卡倒计时，**其余条目删掉**；
     * · 一个都没打「已取件」⇒ 这码本身靠不住（格口码早就换人了），**全删**，
     *   让补码链按各自订单重抓。
     *
     * 无论哪种，码都进 [KEY_DEAD]，之后 [put]/[putForce] 一律拒写 —— 不拒的话
     * 下一轮抓取会原样把它贴回来，用户看到的就是"删了又出现、点开还找不到对应快递"。
     *
     * @return 本轮作废了几个码
     */
    @Synchronized
    fun sweepShared(ctx: Context): Int {
        // 必须是 allRaw：用 all() 的话作废码已被抹空，永远看不出"一码多单"
        val map = allRaw(ctx)
        if (map.isEmpty()) return 0
        val byCode = HashMap<String, MutableList<String>>()
        for ((k, c) in map) {
            val cd = c.code.trim()
            if (cd.isNotEmpty()) byCode.getOrPut(cd) { ArrayList() }.add(k)
        }
        var killed = 0
        for ((cd, keys) in byCode) {
            if (keys.size < 2) continue
            // ★★ v1.4.28：**通知来源不算「一码多单」污染**。
            //   同一件快递常常先被通知抓到（写 `ntf:<码>`），随后平台接口也报出同一个码
            //   （写 `pdd:<单号>`）—— 这是**同一件**，不是"一个码挂了好几单"。
            //   旧逻辑一律 markDead + 全删 ⇒ 用户看到的就是「刷新后码消失」，
            //   而且这个码 7 天内还被禁止再写回来。
            //   现在：有 ntf 条目时，保留平台条目（更可信），只在没有平台条目时保留 ntf，
            //   并且**不作废**这个码。
            val ntfKeys = keys.filter { (map[it]?.src ?: "") == "ntf" }
            if (ntfKeys.isNotEmpty()) {
                val keep = keys.firstOrNull { (map[it]?.src ?: "") != "ntf" } ?: ntfKeys.first()
                val dropNtf = keys.filter { it != keep }
                for (k in dropNtf) map.remove(k)
                android.util.Log.i(
                    "PddCodeMatch",
                    "码 $cd 同时来自通知与接口 ⇒ 视为同一件，保留 $keep，合并 ${dropNtf.size} 条（不作废）"
                )
                continue
            }
            markDead(ctx, cd)
            killed++
            val owner = keys.firstOrNull { (map[it]?.pickedAt ?: 0L) != 0L }
            val drop = if (owner != null) keys.filter { it != owner } else keys
            for (k in drop) map.remove(k)
            android.util.Log.i(
                "PddCodeMatch",
                "码 $cd 被 ${keys.size} 单共用 ⇒ 作废${if (owner != null) "（真主 ${owner.substringAfter(':')} 已取件，其余 ${drop.size} 条删除）" else "（无主，${drop.size} 条全删）"}"
            )
        }
        if (killed > 0) save(ctx, map)
        return killed
    }

    private fun save(ctx: Context, map: Map<String, Cached>) {
        val o = JSONObject()
        for ((k, v) in map) if (v.code.isNotBlank()) o.put(k, v.toJson())
        sp(ctx).edit().putString(KEY, o.toString()).apply()
    }
}
