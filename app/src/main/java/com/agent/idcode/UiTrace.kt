package com.agent.idcode

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * ★ vc503：**自动埋点**（点击 / 页面 / 数据），让日志"抓得全"不依赖人工打点。
 *
 * 三类：
 * 1) [tap]   —— MainActivity.dispatchTouchEvent 里统一记：坐标 + 命中的控件文字/类名 + 当前页面；
 * 2) [page]  —— 打开/关闭任何页面层（openWeb/closeWeb/设置/取件码/途中）都记一行；
 * 3) [data]  —— 缓存写入/删除、快照保存等数据变化。
 *
 * 全部走 `Log.i`（会被 LogStore 持续落盘）+ `LogEvents`（结构化 jsonl）。
 */
object UiTrace {

    @Volatile var page: String = "home"

    /** 全局点击（只记 ACTION_UP，成本极低） */
    fun tap(ctx: Context, x: Float, y: Float, hit: String) {
        val msg = "page=$page x=${x.toInt()} y=${y.toInt()} hit=$hit"
        Log.i("UiTap", msg)
        LogEvents.event(ctx, "tap", mapOf("page" to page, "x" to x.toInt(), "y" to y.toInt(), "hit" to hit.take(60)))
    }

    /** 页面切换 */
    fun page(ctx: Context, name: String, extra: String = "") {
        page = name
        val msg = "→ $name ${extra.take(80)}"
        Log.i("UiPage", msg)
        LogEvents.event(ctx, "page", mapOf("name" to name, "extra" to extra.take(120)))
    }

    /** 数据变化 */
    fun data(ctx: Context, what: String, detail: String = "") {
        Log.i("UiData", "$what ${detail.take(160)}")
        LogEvents.event(ctx, "data", mapOf("what" to what, "detail" to detail.take(200)))
    }

    /** 从坐标找命中的控件（浅层遍历，命中即返回；找不到返回空串） */
    fun hitTest(root: View?, x: Float, y: Float): String {
        val r = root ?: return ""
        return try {
            val loc = IntArray(2)
            r.getLocationOnScreen(loc)
            findHit(r, x, y, loc[0], loc[1], 0)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun findHit(v: View, sx: Float, sy: Float, ox: Int, oy: Int, depth: Int): String {
        if (depth > 6) return ""
        if (v.visibility != View.VISIBLE) return ""
        // 自己的屏幕坐标
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        if (sx < left || sx > left + v.width || sy < top || sy > top + v.height) return ""
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i) ?: continue
                val hit = findHit(c, sx, sy, ox, oy, depth + 1)
                if (hit.isNotEmpty()) return hit
            }
        }
        val text = when (v) {
            is TextView -> v.text?.toString().orEmpty()
            else -> v.contentDescription?.toString().orEmpty()
        }.replace("\n", " ").trim()
        val cls = v.javaClass.simpleName
        return if (text.isNotEmpty()) "$cls「${text.take(24)}」" else cls
    }
}
