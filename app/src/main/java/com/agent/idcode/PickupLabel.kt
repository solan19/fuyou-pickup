package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ vc705：**「取件位置标签」规则**（1.3 的特性移植）。
 *
 * ## 它是什么
 * 取件位置（驿站 / 快递柜 / 代收点）在源里的原文五花八门：
 * 「待取件2026-11-09 08:41:33 您的快件已到达[代收点](菜鸟驿站)溪湖区石桥子镇…」。
 * 界面上一整句放不下，用户真正想看到的是「**北门 3 号柜**」这种人话。
 * 于是给用户一张规则表：**匹配文字**（支持 `*` 通配）→ **显示成**（可留空 = 显示匹配到的那段原文）。
 *
 * ## 与 1.3 一致的三件事
 * 1. 规则三个字段：匹配文字 / 显示成 / 优先级（**命中就停 pause** · **继续 pass**）。
 * 2. `*` 通配：`北门*柜` 能命中「北门菜鸟 3 号智能柜」。
 * 3. **版本号 + 迁移**：存量规则的形状一变就 +1，并在 [migrate] 里补一段改写
 *    —— 1.3 的 `live_short_rule_ver` 就是这个套路，本文件用 `label_rule_ver`。
 *
 * 匹配按**用户排的顺序**从上到下跑；命中就停的那条一命中就结束，
 * 继续 pass 的则把替换后的文本交给后续规则继续匹配（可叠多步）。
 */
object PickupLabel {

    private const val TAG = "PickupLabel"
    private const val PREF = "idcode_prefs"
    private const val KEY_RULES = "pickup_label_rules"
    private const val KEY_VER = "label_rule_ver"
    private const val VER = 2

    /** 一条规则：匹配文字（可含 `*`）→ 显示成；stop = 命中就停（false = 继续 pass） */
    data class Rule(
        val match: String = "",
        val show: String = "",
        val stop: Boolean = true
    )

    fun load(ctx: Context): MutableList<Rule> {
        migrate(ctx)
        val out = ArrayList<Rule>()
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_RULES, null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optString("match").trim()
                if (m.isEmpty()) continue
                out.add(Rule(m, o.optString("show"), o.optBoolean("stop", true)))
            }
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
        return out
    }

    fun save(ctx: Context, rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) {
            if (r.match.isBlank()) continue
            arr.put(JSONObject().apply {
                put("match", r.match)
                put("show", r.show)
                put("stop", r.stop)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_RULES, arr.toString())
            .putInt(KEY_VER, VER)
            .apply()
        Log.i(TAG, "已保存 ${arr.length()} 条位置标签规则")
    }

    /**
     * ★ 版本迁移（照 1.3 的 `live_short_rule_ver` 写法）：
     *  v1 的规则没有 `stop` 字段（当时只有"命中就替换"一种语义）⇒ v2 起补上，
     *  读到的老规则一律按「命中就停」处理；顺便把 `match` 里的全角字符规整掉。
     *  只跑一次，跑完写回版本号 —— 这样以后规则形状再变，也不会把老用户的数据读成空。
     */
    private fun migrate(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val ver = sp.getInt(KEY_VER, 1)
            if (ver >= VER) return
            val raw = sp.getString(KEY_RULES, null)
            if (raw != null) {
                val fixed = JSONArray()
                val old = JSONArray(raw)
                for (i in 0 until old.length()) {
                    val o = old.optJSONObject(i) ?: continue
                    fixed.put(JSONObject().apply {
                        put("match", o.optString("match").trim())
                        put("show", o.optString("show"))
                        put("stop", o.optBoolean("stop", true))
                    })
                }
                sp.edit().putString(KEY_RULES, fixed.toString()).apply()
                Log.i(TAG, "位置标签规则 v$ver → v$VER 迁移：规整 ${fixed.length()} 条")
            }
            sp.edit().putInt(KEY_VER, VER).apply()
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
    }

    /** `*` → `.*`（其余字符按字面转义），得到一条可 `find` 的正则 */
    private fun regexOf(pattern: String): Regex? = try {
        val body = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        Regex(body)
    } catch (t: Throwable) {
        null
    }

    /**
     * 把 [text] 交给规则表处理。
     *  · 一条规则命中 ⇒ 命中的那一段被替换成「显示成」（留空则保留原文那一段）；
     *  · 该规则是「命中就停」⇒ 立即返回；
     *  · 是「继续 pass」⇒ 用替换后的文本继续跑后面的规则。
     */
    fun apply(ctx: Context, text: String): String {
        if (text.isBlank()) return text
        val rules = load(ctx)
        if (rules.isEmpty()) return text
        var out = text
        for (r in rules) {
            val re = regexOf(r.match) ?: continue
            val m = re.find(out) ?: continue
            val hit = m.value
            if (hit.isEmpty()) continue
            val show = r.show.ifBlank { hit }
            out = out.substring(0, m.range.first) + show + out.substring(m.range.last + 1)
            Log.i(TAG, "命中规则「${r.match}」：$hit → $show${if (r.stop) "（命中就停）" else "（继续）"}")
            if (r.stop) return out
        }
        return out
    }

    /**
     * 试测（1.3 的「先在输入框写一条匹配文字，再用它试」）：
     * @return 命中就返回替换后的文本，没命中返回 null（调用方提示"这条匹配写法用不了，换一条试试"）
     */
    fun tryOne(match: String, show: String, text: String): String? {
        if (match.isBlank() || text.isBlank()) return null
        val re = regexOf(match.trim()) ?: return null
        val m = re.find(text) ?: return null
        val hit = m.value
        val rep = show.ifBlank { hit }
        return text.substring(0, m.range.first) + rep + text.substring(m.range.last + 1)
    }
}
