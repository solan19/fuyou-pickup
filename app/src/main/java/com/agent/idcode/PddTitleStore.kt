package com.agent.idcode

import android.content.Context
import org.json.JSONObject

/**
 * ★★ v1.4.9：**订单号 → 商品标题** 的小字典。
 *
 * ## 为什么要有它（同志 2026-11-10 要求：取件码页面显示对应的快递标题）
 * 取件码栏的卡片有好几条来路：
 *  · 拼多多订单列表 → 有 `goodsName`；
 *  · 拼多多物流页补出来的卡（`PddEnrich` 按物流页新建）→ **没有标题**；
 *  · 菜鸟/京东 → 标题字段来路各不相同。
 * 结果就是同志截图里那种"只有一个码 + 菜鸟驿站 · 11-07下单"，看不出这单是什么东西。
 *
 * 做法：凡是见过标题就按订单号存一份（很小，只存一行字），
 * 渲染取件码卡时按订单号回填 —— 哪怕这张卡是从缓存/物流页补出来的。
 */
object PddTitleStore {

    private const val PREFS = "idcode_prefs"
    private const val KEY = "pdd_titles"
    /** 只留最近 500 条，别无限长 */
    private const val MAX = 500

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = try { sp(c).getString(KEY, "") } catch (_: Throwable) { "" } ?: ""
        if (raw.isBlank()) return out
        try {
            val o = JSONObject(raw)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optString(k)
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
        } catch (t: Throwable) {
            SafeRun.w("PddTitle", t)
        }
        return out
    }

    fun get(c: Context, orderSn: String): String {
        if (orderSn.isBlank()) return ""
        return all(c)[orderSn].orEmpty()
    }

    /** 记一条（值没变就不写盘，避免每轮渲染都触发 IO） */
    @Synchronized
    fun put(c: Context, orderSn: String, title: String) {
        val sn = orderSn.trim()
        val t = title.trim()
        if (sn.isEmpty() || t.isEmpty()) return
        val map = all(c)
        if (map[sn] == t) return
        map[sn] = t
        try {
            while (map.size > MAX) {
                val first = map.keys.firstOrNull() ?: break
                map.remove(first)
            }
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            sp(c).edit().putString(KEY, o.toString()).apply()
        } catch (e: Throwable) {
            SafeRun.w("PddTitle", e)
        }
    }

    /** 批量记（渲染时一次遍历写一遍，避免逐条开 Editor） */
    @Synchronized
    fun putAll(c: Context, pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        val map = all(c)
        var changed = false
        for ((k, v) in pairs) {
            val sn = k.trim()
            val t = v.trim()
            if (sn.isEmpty() || t.isEmpty()) continue
            if (map[sn] != t) {
                map[sn] = t
                changed = true
            }
        }
        if (!changed) return
        try {
            while (map.size > MAX) {
                val first = map.keys.firstOrNull() ?: break
                map.remove(first)
            }
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            sp(c).edit().putString(KEY, o.toString()).apply()
        } catch (e: Throwable) {
            SafeRun.w("PddTitle", e)
        }
    }
}
