package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★ vc502：**结构化事件日志**（events-YYYYMMDD.jsonl）。
 *
 * 用途：把「哪个源、耗时多少、拿到几条、报什么错」写成一行一个 JSON。
 * 这样排障时不用逐行 grep 文本日志，直接看事件就能定位：
 * ```
 * {"t":"2026-10-28 20:51:03.221","ev":"source_done","where":"pickup","src":"pdd","ms":1240,"count":2,"err":null}
 * ```
 * 文件与普通日志同目录，导出 zip 时自动带上。
 */
object LogEvents {

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val dfDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    private val lock = Any()

    private fun file(ctx: Context): File =
        File(LogStore.dir(ctx), "events-${dfDay.format(Date())}.jsonl")

    /** 通用事件 */
    fun event(ctx: Context, ev: String, fields: Map<String, Any?> = emptyMap()) {
        try {
            val o = JSONObject()
            o.put("t", df.format(Date()))
            o.put("ev", ev)
            for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
            val line = o.toString()
            synchronized(lock) {
                file(ctx).appendText(line + "\n")
            }
            Log.i("LogEvents", line)
        } catch (t: Throwable) {
            Log.w("LogEvents", "写事件失败: ${t.message}")
        }
    }

    /** 单源完成（取件码 / 在途） */
    fun source(ctx: Context, where: String, src: String, count: Int?, err: String? = null, ms: Long? = null) {
        event(
            ctx, "source_done",
            buildMap {
                put("where", where)
                put("src", src)
                put("count", count)
                if (ms != null) put("ms", ms)
                if (!err.isNullOrBlank()) put("err", err.take(200))
            }
        )
    }

    /** 刷新轮次开始 */
    fun roundStart(ctx: Context, where: String, sources: Collection<String>) {
        event(ctx, "refresh_start", mapOf("where" to where, "sources" to sources.joinToString(",")))
    }

    /** 账号级完成（拼多多副号等） */
    fun account(ctx: Context, label: String, ms: Long, scanned: Int, codes: Int, err: String? = null) {
        event(
            ctx, "account_done",
            buildMap {
                put("account", label)
                put("ms", ms)
                put("scanned", scanned)
                put("codes", codes)
                if (!err.isNullOrBlank()) put("err", err.take(200))
            }
        )
    }
}
