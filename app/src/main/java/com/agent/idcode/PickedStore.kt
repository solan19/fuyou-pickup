package com.agent.idcode

import android.content.Context
import org.json.JSONObject

/**
 * ★★ vc674：**「已取件」标记按包裹身份持久化**（订单号 / 运单号）。
 *
 * ## 为什么不能只写在缓存条目的 `pickedAt` 上
 * 同一件包裹在缓存里可能有多条：`pdd:订单号`、`cainiao:运单号`，甚至重启后被
 * 校园驿站/站点接口**重新写入**一条全新的（键和来源都变了、`pickedAt` 自然是 0）。
 * 旧做法只给当下那几条打标记，重启后新写进来的副本没有标记 ⇒
 * "打了已取件，退出重进又回来了"（同志 2026-11-03 实测）。
 *
 * ## 现在的口径
 * 标记记在**身份**上（订单号 / 运单号），[PddCodeStore.all] 读缓存时按身份补 `pickedAt`。
 * 于是不管这条件从哪个来源、以什么键重新出现，只要身份对得上就仍然是「已取件」。
 * 有效期与灰标一致（[PddCodeStore.PICKED_KEEP_MS]）。
 *
 * ## ★★ v1.4.11：**取件码不再算身份**
 * 事故（同志 2026-11-10）：「114-4-9453 是今天刚到的润唇膏的码，我还没取」，
 * 可它昨天属于另一件、那件已经取走了 ⇒ 只要 `picked_ids` 里存过这个码，
 * 今天这枚码一出现就会被判「已取件」，卡上直接划掉、首页也不算待取。
 * **格口码会被驿站复用**（这与黑名单误伤化妆棉是同一个坑），
 * 拿它当身份等于让"上一件的取件动作"传染给下一件。
 * ⇒ 打标/查标/取消全部跳过形如 `1-2-3456` 的取件码，只认订单号与运单号；
 *    老数据里的码身份在 [load] 里读时就丢弃，下一次写盘自然清掉。
 */
object PickedStore {

    private const val PREFS = "idcode_prefs"
    private const val KEY = "picked_ids"

    /** 取件码形如 `114-4-9453` / `2-3-0009`（格口码，可复用，不能当身份） */
    private val CODE_LIKE = Regex("""^\d{1,3}-\d{1,3}-\d{1,4}$""")

    /** 过滤掉不能当身份的值；返回 null 表示这个 id 不参与标记 */
    private fun usable(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s == "null") return null
        if (CODE_LIKE.matches(s)) return null
        return s
    }

    /** 内存缓存：id → 打标时刻。避免 [PddCodeStore.all] 每次都解析 SP */
    private var mem: MutableMap<String, Long>? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context): MutableMap<String, Long> {
        mem?.let { return it }
        val out = HashMap<String, Long>()
        val now = System.currentTimeMillis()
        try {
            val raw = sp(ctx).getString(KEY, "")
            if (!raw.isNullOrBlank()) {
                val o = JSONObject(raw)
                val it = o.keys()
                while (it.hasNext()) {
                    // ★★ v1.4.11：老数据里的取件码身份直接丢掉（复用的码会误伤新件）
                    val id = usable(it.next()) ?: continue
                    val ts = o.optLong(id, 0L)
                    if (ts > 0 && now - ts < PddCodeStore.PICKED_KEEP_MS) out[id] = ts
                }
            }
        } catch (t: Throwable) {
            SafeRun.w("PickedStore", t)
        }
        mem = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context, map: Map<String, Long>) {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            sp(ctx).edit().putString(KEY, o.toString()).apply()
        } catch (t: Throwable) {
            SafeRun.w("PickedStore", t)
        }
    }

    /** 这些身份里有没有被标记过；返回最近的标记时刻（0 = 没标 / 已过期） */
    fun of(ctx: Context, ids: List<String>): Long {
        if (ids.isEmpty()) return 0L
        val m = load(ctx)
        var best = 0L
        for (id in ids) {
            val s = usable(id) ?: continue
            val t = m[s] ?: continue
            if (t > best) best = t
        }
        return best
    }

    /** 打标（整件包裹的订单号/运单号都记上，跨来源、跨重启都认；取件码跳过） */
    fun mark(ctx: Context, ids: Collection<String>) {
        val m = load(ctx)
        val now = System.currentTimeMillis()
        var n = 0
        for (id in ids) {
            val s = usable(id) ?: continue
            m[s] = now
            n++
        }
        if (n > 0) {
            save(ctx, m)
            android.util.Log.i("PickedStore", "标记已取件 $n 个身份")
        }
    }

    /** 取消打标 */
    fun unmark(ctx: Context, ids: Collection<String>) {
        val m = load(ctx)
        var n = 0
        for (id in ids) {
            val s = usable(id) ?: continue
            if (m.remove(s) != null) n++
        }
        if (n > 0) {
            save(ctx, m)
            android.util.Log.i("PickedStore", "取消已取件 $n 个身份")
        }
    }

    /** 清空（调试/重置用） */
    fun clear(ctx: Context) {
        mem = HashMap()
        sp(ctx).edit().remove(KEY).apply()
    }
}
