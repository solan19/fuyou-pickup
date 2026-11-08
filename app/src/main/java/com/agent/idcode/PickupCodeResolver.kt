package com.agent.idcode

import android.app.Activity
import android.util.Log

/**
 * ★ vc303：取件码三级兜底解析链（同志脑图 2026-10-17）
 *
 * ## 判定
 * 拿到的取件码若是**单号后 5 位**（快递商官方自助码，非驿站真实格口码）⇒ 触发跨平台查询链。
 * 真实格口码形如 59-6-6012（驿站货架码），与单号尾号不同。
 *
 * ## 三级链
 *  ① 淘宝快递查询（菜鸟驿站频道）—— 桩：接口未接，恒返回 null（vc304 接入）
 *  ② 拼多多「我的快递」（express_trackbox/list 纯 HTTP 直连，~0.5s）
 *     · 站点名：track_desc 的「取件地址:」段 —— **已实测可用**（真机 18:05）
 *     · 格口码：列表响应无码，需详情/搜索接口（开发中，暂返回 null）
 *  ③ 兜底：保留原码（后 5 位）
 *
 * ## 限频
 * 列表级：fetchList 内置 10 分钟；单卡级：本类 30 分钟时间戳。
 */
object PickupCodeResolver {

    private const val TAG = "CodeResolver"
    private const val PREF = "idcode_prefs"
    private const val KEY_TS = "resolver_ts_"   // + 单号

    /** 解析结果 */
    class Resolution(
        val code: String,      // 最终码（真实格口码 or 原码兜底）
        val station: String,   // 驿站名（"" = 没拿到）
        val source: String     // real / pdd-list / none / throttled
    )

    /** 归一：只留字母数字 + 大写（JT3180960043943 与 jt-3180960043943 视为同号） */
    fun norm(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase()

    /**
     * ★ 短码判定（脑图「取件码 = 单号后5位?」）：
     * code 为空（无码）或 == 单号后 5 位 ⇒ 疑似"非真实格口码"⇒ 该走查询链
     */
    fun isShortCode(waybill: String, code: String): Boolean {
        if (code.isBlank()) return true
        val tail = norm(waybill).takeLast(5)
        return norm(code) == tail
    }

    /** 从「我的快递」列表里找同单号条目（归一匹配） */
    fun matchFromList(list: List<PddExpressChannel.ListEntry>, waybill: String): PddExpressChannel.ListEntry? =
        list.firstOrNull { norm(it.trackingNumber) == norm(waybill) }

    /** 单卡级限频：30 分钟内该单已解析过 ⇒ 跳过 */
    fun throttled(act: Activity, waybill: String): Boolean {
        val sp = act.getSharedPreferences(PREF, Activity.MODE_PRIVATE)
        val last = sp.getLong(KEY_TS + waybill, 0L)
        return System.currentTimeMillis() - last < 30L * 60_000L
    }

    /** 标记该单已解析（防重复打接口） */
    fun markDone(act: Activity, waybill: String) {
        act.getSharedPreferences(PREF, Activity.MODE_PRIVATE)
            .edit().putLong(KEY_TS + waybill, System.currentTimeMillis()).apply()
    }

    /**
     * 三级兜底解析（单卡粒度）。回调在主线程。
     *
     * 调用方约定：先 fetchList 拿全量列表（10 分钟限频、多卡共享一次 HTTP），
     * 再对每张卡调本方法（传 list 避免重复请求）。
     *
     * @param currentCode 当前已有的码（可能为空或后 5 位）
     * @return Resolution：code 至少为 currentCode（兜底不丢），station 可能补上
     */
    fun resolveFromList(
        act: Activity, waybill: String, currentCode: String, currentStation: String,
        list: List<PddExpressChannel.ListEntry>,
        onDone: (Resolution) -> Unit
    ) {
        // 非短码（已是真实格口码）⇒ 不解析
        if (!isShortCode(waybill, currentCode)) {
            onDone(Resolution(currentCode, currentStation, "real"))
            return
        }
        if (throttled(act, waybill)) {
            onDone(Resolution(currentCode, currentStation, "throttled"))
            return
        }
        Log.i(
            TAG,
            "短码判定成立（${currentCode.ifBlank { "无码" }} = 后5位）⇒ 三级链：$waybill"
        )
        // ① 淘宝快递查询（桩：菜鸟驿站频道接口 vc304 接入后在此优先查询）
        //    val tb = TbExpressProbe.query(waybill)  —— 暂未实现
        // ② 拼多多「我的快递」列表
        markDone(act, waybill)
        val hit = matchFromList(list, waybill)
        if (hit != null) {
            val station = currentStation.ifEmpty { hit.station }
            // ② 的列表响应无格口码（详情/搜索接口接入后在此填充真实码）
            Log.i(TAG, "拼多多命中：站=${hit.station} status=${hit.statusDesc}")
            onDone(
                Resolution(
                    currentCode, station,
                    if (station.isNotEmpty()) "pdd-list" else "none"
                )
            )
        } else {
            // ③ 兜底：列表无此单 ⇒ 保留原码
            Log.i(TAG, "拼多多列表无此单（跨平台列表 ${list.size} 条）⇒ 兜底后5位")
            onDone(Resolution(currentCode, currentStation, "none"))
        }
    }
}
