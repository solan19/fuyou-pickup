package com.agent.idcode

import android.content.Context
import org.json.JSONObject

/**
 * ★★ vc674：**「已取件」标记按包裹身份持久化**（订单号 / 运单号 / 取件码）。
 *
 * ## 为什么不能只写在缓存条目的 `pickedAt` 上
 * 同一件包裹在缓存里可能有多条：`pdd:订单号`、`cainiao:运单号`，甚至重启后被
 * 校园驿站/站点接口**重新写入**一条全新的（键和来源都变了、`pickedAt` 自然是 0）。
 * 旧做法只给当下那几条打标记，重启后新写进来的副本没有标记 ⇒
 * "打了已取件，退出重进又回来了"（同志 2026-11-03 实测）。
 *
 * ## 现在的口径
 * 标记记在**身份**上（取件码 / 运单号），[PddCodeStore.all] 读缓存时按身份补 `pickedAt`。
 * 于是不管这条件从哪个来源、以什么键重新出现，只要身份对得上就仍然是「已取件」。
 * 有效期与灰标一致（[PddCodeStore.PICKED_KEEP_MS] = 24h），过期自动失效。
 */
object PickedStore {

    private const val PREFS = "idcode_prefs"
    private const val KEY = "picked_ids"

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
                    val id = it.next()
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
            val s = id.trim()
            if (s.isEmpty()) continue
            val t = m[s] ?: continue
            if (t > best) best = t
        }
        return best
    }

    /** 打标（整件包裹的全部身份都记上，跨来源、跨重启都认） */
    fun mark(ctx: Context, ids: Collection<String>) {
        val m = load(ctx)
        val now = System.currentTimeMillis()
        var n = 0
        for (id in ids) {
            val s = id.trim()
            if (s.isEmpty() || s == "null") continue
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
            if (m.remove(id.trim()) != null) n++
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
