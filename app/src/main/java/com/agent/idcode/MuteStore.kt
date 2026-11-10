package com.agent.idcode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★★ v1.4.9：**黑名单改成"按包裹记录"**（原来是三个互不相干的扁平键）。
 *
 * ## 原来的问题（同志 2026-11-10 报「在途丢失一个化妆棉」）
 * 删一张卡会往名单里塞 3 个键：`pdd:订单号`、`源:运单号`、`取件码`。
 * 判定时只要**任一身份**命中就整件隐藏 —— 于是：
 * · **取件码是驿站格口码，天天复用**（`114-4-9453` 这批人用完下一批接着用）；
 * · **运单号也会被承运商回收再发**。
 * ⇒ 昨天删过的一件，今天新到的包裹撞上同一个码/同一个运单号，就**被当成旧件吃掉**。
 * 真机日志实证（2026-11-10 11:19:25）：`TransitMuted: 在途栏按名单剔除 3 件`
 * 而用户的 11 件拼多多在途里 3 件无辜消失（其中就有「化妆棉」）。
 *
 * ## 现在的模型
 * 一条记录 = 一个包裹的全部身份，存在一个 JSON 对象里：
 * ```
 * { id, src, orderSn, waybill, code, station, title, ts, permanent, legacy }
 * ```
 * 判定按**身份强度**分级，弱身份带时间窗：
 * | 身份 | 强度 | 规则 |
 * |---|---|---|
 * | 订单号 orderSn | 强 | 相同即命中，永久有效（拼多多订单号不会复用） |
 * | 运单号 waybill | 中 | 相同且**记录不超过 60 天**（运单号会被回收再发） |
 * | 取件码 code | 弱 | 码**和驿站都相同**且**记录不超过 7 天**（格口码天天复用） |
 *
 * 这样既保留"删了就真的不回来"的效果，又不会把以后的新件误伤。
 */
object MuteStore {

    private const val PREFS = "idcode_prefs"
    /** 新：记录数组（JSON 字符串，保序） */
    private const val KEY_V2 = "mute_records_v2"
    /** 旧：扁平键黑名单 / 永久隐藏 —— 只在迁移时读一次 */
    private const val KEY_OLD_KEYS = "mute_keys"
    private const val KEY_OLD_HIDDEN = "hide_keys"
    private const val KEY_OLD_PICKUP = "pickup_muted"
    private const val KEY_OLD_TRACK = "track_muted"
    /** 迁移完成标记（迁移后旧键清空，绝不重复迁回） */
    private const val KEY_MIGRATED = "mute_records_v2_migrated"
    /** ★ v1.4.9b：清理标记 —— v1.4.9 首版迁移把「裸取件码」当运单号收进来了，这里再洗一遍 */
    private const val KEY_CLEANED = "mute_records_v3_cleaned"

    /** 名单硬上限 —— 超了按 ts 淘汰最早的 */
    private const val MAX_RECORDS = 200
    /** 运单号复用窗口：超过这个天数的记录不再按运单号命中 */
    private const val WAYBILL_TTL_MS = 60L * 24 * 3600_000L
    /** 取件码复用窗口：超过这个天数的记录不再按取件码命中 */
    private const val CODE_TTL_MS = 7L * 24 * 3600_000L

    /**
     * 一条屏蔽记录。
     * @param permanent 「全部删除」进来的 —— 只能清 App 数据才恢复（与旧隐藏名单同义）
     * @param legacy    从旧扁平键迁移来的，没有标题、身份也可能不全
     */
    data class Entry(
        val id: String,
        val src: String = "",
        val orderSn: String = "",
        val waybill: String = "",
        val code: String = "",
        val station: String = "",
        val title: String = "",
        val ts: Long = 0L,
        val permanent: Boolean = false,
        val legacy: Boolean = false
    ) {
        /** 给页面看的一行身份摘要 */
        fun identityLine(): String {
            val out = ArrayList<String>()
            if (orderSn.isNotBlank()) out.add("单 $orderSn")
            if (waybill.isNotBlank() && norm(waybill) != norm(orderSn)) out.add("运单 $waybill")
            if (code.isNotBlank()) out.add("码 $code")
            if (station.isNotBlank()) out.add(station)
            return out.joinToString(" · ")
        }

        fun displayTitle(): String = when {
            title.isNotBlank() -> title
            orderSn.isNotBlank() -> "订单 " + orderSn.takeLast(6)
            waybill.isNotBlank() -> "运单 " + waybill.takeLast(6)
            code.isNotBlank() -> "取件码 $code"
            else -> "未命名包裹"
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("src", src)
            put("orderSn", orderSn)
            put("waybill", waybill)
            put("code", code)
            put("station", station)
            put("title", title)
            put("ts", ts)
            put("permanent", permanent)
            put("legacy", legacy)
        }

        companion object {
            fun from(o: JSONObject) = Entry(
                id = o.optString("id").ifBlank { System.nanoTime().toString() },
                src = o.optString("src"),
                orderSn = o.optString("orderSn"),
                waybill = o.optString("waybill"),
                code = o.optString("code"),
                station = o.optString("station"),
                title = o.optString("title"),
                ts = o.optLong("ts"),
                permanent = o.optBoolean("permanent"),
                legacy = o.optBoolean("legacy")
            )
        }
    }

    /** 取件码形态：`114-4-9453` / `73-3-5888` —— 驿站格口码，会被复用，绝不当运单号 */
    private val CODE_LIKE = Regex("""^\d{1,3}-\d{1,3}-\d{2,6}$""")

    private fun looksLikePickupCode(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return CODE_LIKE.matches(t)
    }

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 进程内小缓存：渲染时每张卡都要问一次名单，没必要每次重新解析 JSON */
    @Volatile
    private var cache: List<Entry>? = null
    @Volatile
    private var cacheAt = 0L

    private fun invalidate() {
        cache = null
        cacheAt = 0L
    }

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }.uppercase()

    /** 订单号/运单号/码这类占位符不算身份 */
    private fun usable(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        val low = t.lowercase()
        if (low == "null" || low == "undefined" || low == "none") return false
        return norm(t).isNotEmpty()
    }

    private fun fresh(ts: Long, now: Long, ttl: Long): Boolean =
        ts <= 0L || now - ts <= ttl

    // ---------------- 读 ----------------

    /** 全部记录（含永久隐藏），按 ts 升序 */
    @Synchronized
    fun entries(c: Context): MutableList<Entry> {
        val now = System.currentTimeMillis()
        val hit = cache
        if (hit != null && now - cacheAt < 3000L) return ArrayList(hit)
        migrateIfNeeded(c)
        val out = ArrayList<Entry>()
        val raw = try { sp(c).getString(KEY_V2, "") } catch (_: Throwable) { "" } ?: ""
        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(Entry.from(o))
                }
            } catch (t: Throwable) {
                SafeRun.w("MuteStore", t)
            }
        }
        // ★ v1.4.9b：把「运单号字段里其实是取件码」的历史记录洗一遍（一次性）
        cleanedOnce(c, out)
        cache = out
        cacheAt = now
        return ArrayList(out)
    }

    /**
     * ★ v1.4.9b：**一次性清洗**。
     *
     * v1.4.9 首版迁移只按"有没有 `code:` 前缀"判弱身份，于是**裸取件码**（`114-4-9453`）
     * 被当成运单号收了进来 —— 真机日志里立刻复现：名单里的 `73-3-5888` 又把一张取件码卡吃掉。
     * 这里按**形态**再判一次（`数字-数字-数字` 就是格口码）：
     *  · 只有这个码、没有订单号 ⇒ 整条丢弃（它本来就是复用率极高的弱身份）；
     *  · 还有订单号 ⇒ 只把码从 `waybill` 挪到 `code`，记录保留（订单号才是真身份）。
     * @return 是否有改动
     */
    private fun cleanedOnce(c: Context, list: MutableList<Entry>): Boolean {
        val p = sp(c)
        if (p.getBoolean(KEY_CLEANED, false)) return false
        var dropped = 0
        var moved = 0
        val it = list.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.waybill.isBlank() || !looksLikePickupCode(e.waybill)) continue
            if (e.orderSn.isBlank() && e.code.isBlank()) {
                it.remove()
                dropped++
            } else {
                val idx = list.indexOf(e)
                list[idx] = e.copy(
                    waybill = "",
                    code = e.code.ifBlank { e.waybill },
                    station = e.station
                )
                moved++
            }
        }
        if (dropped > 0 || moved > 0) {
            saveAll(c, list)
            android.util.Log.i("MuteStore", "清洗历史名单：丢弃纯取件码记录 $dropped 条，码归位 $moved 条")
        }
        p.edit().putBoolean(KEY_CLEANED, true).apply()
        return dropped + moved > 0
    }

    /** 普通黑名单（可移出） */
    fun active(c: Context): List<Entry> = entries(c).filter { !it.permanent }

    /** 永久隐藏（「全部删除」进来的） */
    fun hiddenEntries(c: Context): List<Entry> = entries(c).filter { it.permanent }

    fun hiddenCount(c: Context): Int = hiddenEntries(c).size

    @Synchronized
    fun saveAll(c: Context, list: List<Entry>) {
        try {
            val sorted = list.sortedBy { it.ts }
            val keep = if (sorted.size > MAX_RECORDS) sorted.takeLast(MAX_RECORDS) else sorted
            val arr = JSONArray()
            for (e in keep) arr.put(e.toJson())
            sp(c).edit().putString(KEY_V2, arr.toString()).apply()
            invalidate()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    // ---------------- 判 ----------------

    /**
     * 这个包裹是否在名单里；命中返回那条记录（给日志用）。
     *
     * @param src      卡片来源
     * @param orderSn  订单号（拼多多）
     * @param waybill  真运单号（`realWaybill` 优先）
     * @param code     取件码
     * @param station  驿站名（取件码命中必须两边都有且相同）
     */
    fun blocks(
        c: Context,
        src: String,
        orderSn: String,
        waybill: String,
        code: String,
        station: String
    ): Entry? {
        val os = norm(orderSn)
        val wb = norm(waybill)
        val cd = norm(code)
        val st = norm(station)
        if (os.isEmpty() && wb.isEmpty() && cd.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (e in entries(c)) {
            if (!srcCompatible(e.src, src)) continue
            val eos = norm(e.orderSn)
            val ewb = norm(e.waybill)
            val ecd = norm(e.code)
            // ① 订单号：最强，永久有效
            if (eos.isNotEmpty() && os.isNotEmpty() && eos == os) return e
            // ② 运单号：准强，限 60 天（会被承运商回收再发）
            if (ewb.isNotEmpty() && wb.isNotEmpty() && ewb == wb && fresh(e.ts, now, WAYBILL_TTL_MS)) return e
            // ③ 取件码：弱，必须同驿站 + 限 7 天（格口码天天复用）
            if (ecd.isNotEmpty() && cd.isNotEmpty() && ecd == cd &&
                st.isNotEmpty() && norm(e.station).isNotEmpty() && norm(e.station) == st &&
                fresh(e.ts, now, CODE_TTL_MS)
            ) return e
        }
        return null
    }

    private fun srcCompatible(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return true
        if (a == b) return true
        // 淘宝与菜鸟是同一条链路的两种叫法
        return (a == "tb" && b == "cainiao") || (a == "cainiao" && b == "tb")
    }

    // ---------------- 写 ----------------

    /** 加一条记录（同身份自动合并，不会重复堆三条） */
    @Synchronized
    fun addEntry(c: Context, e: Entry) {
        if (!usable(e.orderSn) && !usable(e.waybill) && !usable(e.code)) return
        val list = entries(c)
        val now = if (e.ts > 0) e.ts else System.currentTimeMillis()
        val exist = list.firstOrNull { old ->
            (usable(e.orderSn) && usable(old.orderSn) && norm(old.orderSn) == norm(e.orderSn)) ||
                    (usable(e.waybill) && usable(old.waybill) && norm(old.waybill) == norm(e.waybill)) ||
                    (usable(e.code) && usable(old.code) && norm(old.code) == norm(e.code) &&
                            norm(old.station) == norm(e.station))
        }
        if (exist != null) {
            val merged = exist.copy(
                src = exist.src.ifBlank { e.src },
                orderSn = exist.orderSn.ifBlank { e.orderSn },
                waybill = exist.waybill.ifBlank { e.waybill },
                code = exist.code.ifBlank { e.code },
                station = exist.station.ifBlank { e.station },
                title = exist.title.ifBlank { e.title },
                ts = now,
                permanent = exist.permanent || e.permanent
            )
            list[list.indexOf(exist)] = merged
        } else {
            list.add(e.copy(id = e.id.ifBlank { "m" + now + "-" + list.size }, ts = now))
        }
        saveAll(c, list)
        android.util.Log.i(
            "MuteStore",
            "加入屏蔽记录 ${e.src}/${e.orderSn} 运单=${e.waybill} 码=${e.code} 共 ${list.size} 条"
        )
    }

    fun remove(c: Context, id: String) {
        val list = entries(c)
        if (list.removeAll { it.id == id }) saveAll(c, list)
    }

    /**
     * 按"身份 token"删（兼容旧调用点）：token 可以是 `src:单号`，也可以是裸单号/裸码。
     * 任何一条记录的身份与 token 相同就整条删掉。
     */
    fun removeKey(c: Context, token: String) {
        if (token.isBlank()) return
        val bare = token.substringAfter(':')
        val t = norm(bare)
        val list = entries(c)
        val removed = list.removeAll {
            norm(it.orderSn) == t || norm(it.waybill) == t || norm(it.code) == t
        }
        if (removed) saveAll(c, list)
    }

    /** 全部移除（保留永久隐藏） */
    fun clear(c: Context) {
        val list = entries(c).filter { it.permanent }
        saveAll(c, list)
    }

    /** 全部删除 = 把现有记录全部标成永久隐藏 */
    fun hideAll(c: Context) {
        val list = entries(c).map { it.copy(permanent = true) }
        saveAll(c, list)
    }

    /** 彻底清空（含永久隐藏；调试用） */
    fun clearAll(c: Context) {
        try {
            sp(c).edit().remove(KEY_V2).apply()
            invalidate()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    /** 只清某个来源的记录（退出账号等场景） */
    fun clearSource(c: Context, source: String) {
        val aliases = if (source == "tb") setOf("tb", "cainiao") else setOf(source)
        val list = entries(c).filterNot { it.src in aliases }
        saveAll(c, list)
    }

    // ---------------- 给后台通知用的纯单号视图 ----------------

    /**
     * 后台通知只看**强身份**（订单号 / 运单号）。
     * ★ 刻意不把取件码算进来 —— 码会被复用，拿它去静音会把以后的新件一起吞掉。
     */
    fun nos(c: Context): Set<String> {
        val out = LinkedHashSet<String>()
        for (e in entries(c)) {
            if (usable(e.orderSn)) out.add(e.orderSn)
            if (usable(e.waybill)) out.add(e.waybill)
        }
        return out
    }

    fun isMutedNo(c: Context, no: String): Boolean {
        if (no.isBlank()) return false
        val t = norm(no)
        return entries(c).any { norm(it.orderSn) == t || norm(it.waybill) == t }
    }

    /** 后台「不再提醒」：按纯单号加一条（运单号身份，60 天窗口） */
    fun toggleNo(c: Context, no: String): Boolean {
        if (no.isBlank()) return false
        val t = norm(no)
        val list = entries(c)
        val hit = list.firstOrNull { norm(it.orderSn) == t || norm(it.waybill) == t }
        return if (hit != null) {
            list.remove(hit)
            saveAll(c, list)
            false
        } else {
            addEntry(c, Entry(id = "n" + System.currentTimeMillis(), waybill = no, ts = System.currentTimeMillis()))
            true
        }
    }

    // ---------------- 旧数据迁移 ----------------

    /**
     * 旧扁平键 → 记录。只在第一次读时跑一次。
     *
     * 旧键形态：`src:no` / `no` / `code:码`。迁移规则刻意**偏保守**：
     *  · `pdd:YYMMDD-xxxx958` 这种 → 当成**订单号**（强身份，永久有效）—— 这是用户真正删掉的件；
     *  · `code:码` 与纯数字短码 → **直接丢弃**。它们正是误伤元凶：
     *    驿站格口码天天复用，留着就会继续吞掉以后的新件（同志丢的「化妆棉」就是这条路）；
     *  · 其余 `src:单号` / 裸长单号 → 当成**运单号**（60 天窗口）。
     * 丢掉的弱身份不会造成"删了又回来"的循环 —— 那些件如果真的还在，用户再删一次即可，
     * 而删过的东西会以记录制重新写入（订单号 → 永久有效）。
     */
    private fun migrateIfNeeded(c: Context) {
        val p = sp(c)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        try {
            val out = ArrayList<Entry>()
            val now = System.currentTimeMillis()
            var droppedCode = 0
            var n = 0
            fun push(raw: String, permanent: Boolean) {
                val s = raw.trim()
                if (s.isEmpty()) return
                val src = if (s.contains(':')) s.substringBefore(':') else ""
                val no = if (s.contains(':')) s.substringAfter(':') else s
                if (!usable(no)) return
                // ① 取件码类弱身份：不迁移（形态判定 —— 裸码和 `code:` 前缀都算）
                if (src == "code" || looksLikePickupCode(no)) {
                    droppedCode++
                    return
                }
                // ② 拼多多订单号（YYMMDD-一串数字958）：强身份
                val isPddSn = src == "pdd" && Regex("""^\d{6}-\d{6,}""").containsMatchIn(no)
                val e = if (isPddSn) {
                    Entry(id = "g" + n, src = "pdd", orderSn = no, ts = now, permanent = permanent, legacy = true)
                } else {
                    Entry(id = "g" + n, src = src, waybill = no, ts = now, permanent = permanent, legacy = true)
                }
                n++
                if (out.none { it.orderSn == e.orderSn && it.waybill == e.waybill && it.code == e.code }) out.add(e)
            }
            fun readSet(key: String, permanent: Boolean) {
                p.getStringSet(key, null)?.forEach { push(it, permanent) }
            }
            readSet(KEY_OLD_KEYS, false)
            readSet(KEY_OLD_HIDDEN, true)
            readSet(KEY_OLD_PICKUP, false)
            readSet(KEY_OLD_TRACK, false)
            if (out.isNotEmpty()) {
                saveAll(c, out)
                android.util.Log.i(
                    "MuteStore",
                    "旧名单迁移 → 记录 ${out.size} 条（丢弃弱身份 $droppedCode 条）：" +
                            out.joinToString(",") { "${it.src}/${it.orderSn}/${it.waybill}" }
                )
            } else if (droppedCode > 0) {
                android.util.Log.i("MuteStore", "旧名单里只有弱身份（取件码）$droppedCode 条 —— 全部丢弃，避免误伤新件")
            }
            p.edit()
                .putBoolean(KEY_MIGRATED, true)
                .remove(KEY_OLD_KEYS)
                .remove(KEY_OLD_HIDDEN)
                .remove(KEY_OLD_PICKUP)
                .remove(KEY_OLD_TRACK)
                .apply()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    /** 诊断：把整份名单打一行日志（排查"在途少了件"必备） */
    fun dump(c: Context): String {
        val list = entries(c)
        return list.joinToString(" | ") { e ->
            "${e.src}/${e.orderSn}/${e.waybill}/${e.code}@${e.ts}${if (e.permanent) "!" else ""}"
        }
    }
}
