package com.agent.idcode

import android.content.Context
import android.util.Log

/**
 * ★ vc699：把「当前未取件的取件码」一次性推给 **桌面小组件 + 流体云 + 通知**。
 *
 * ## 为什么需要它（同志 2026-11-09 报的现象）
 * 「有快递到了，小组件不更新、也不推通知，只有点开软件才更新取件码。」
 *
 * 根因：这条「发布」链原来**只存在于 MainActivity**（`publishWidgetCodes` 在取件码页渲染时调）。
 * 后台 Worker 每轮确实抓了取件码、也确实写进了 [PddCodeStore]（见 `UiCacheSync.syncFromWorker`），
 * 但**没有人把它推给小组件 / 流体云 / 通知** —— 小组件的数据源是 SP 里的 `widget_codes`，
 * 而那份数据只有前台渲染时才写。于是后台抓得再多，桌面上也是上一轮的旧码。
 *
 * 这里抽成一个公共出口：谁抓到码谁调一次，前台后台同一份逻辑，格式也只有一个版本。
 */
object PickupPublish {

    private const val TAG = "PickupPublish"
    private const val PREF = "idcode_prefs"
    /** 上一次发布出去（= 用户可见）的码集合，用来判断"哪些是新到的" */
    private const val KEY_LAST_CODES = "publish_last_codes"

    /**
     * 数据源：`PddCodeStore` 里**仍然有效**的条目。
     *
     * ★★ v1.4.18：改走 [PickupGate.aliveEntries] —— 原来只判「有码 + 未取件」，
     * **没有过黑名单**，于是「清已取 / 长按删除」掉的码照样被推上流体云、写进小组件、
     * 还可能再弹一条通知（同志 14:36：流体云显示 3 件，实际只有 1 个取件码）。
     * 现在整个 App 只有这一个「哪些码该发布」的出口。
     */
    private fun pendingOf(ctx: Context): List<PddCodeStore.Cached> =
        PickupGate.aliveEntries(ctx).map { it.second }

    /**
     * 发布一次。
     *
     * @param notifyNew 新出现的码要不要发通知（后台 true：用户没开 App 也得知道"快递到了"；
     *                  前台 false：卡片已经显示了，再弹一条是重复打扰）
     * @return 本次**新出现**的码数量（0 = 没有新东西，调用方可据此不做额外动作）
     */
    fun publish(ctx: Context, notifyNew: Boolean = true): Int {
        val list = pendingOf(ctx)

        // ① 桌面小组件：`widget_codes` 是它唯一的数据源，格式 {src, code} 的 JSON 数组
        try {
            val arr = org.json.JSONArray()
            for (c in list) {
                arr.put(org.json.JSONObject().apply {
                    put("src", c.src)
                    put("code", c.code)
                })
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(HomeWidgets.KEY_WIDGET_CODES, arr.toString())
                .apply()
            HomeWidgets.updateAll(ctx)
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }

        // ② 流体云 / 实况位：未取件的码聚类成**一条**（vc699 起）
        //   ★★ v1.4.26：承运商字段原来传 ""，而补推链（LiveUpdate / IslandHelper 的
        //   syncFromSnapshot）传的是 PickupGate.srcLabel(src) ⇒ 同一张卡显不显示承运商，
        //   取决于哪条链最后写；指纹又只看取件码，内容不一致也不会自愈。
        //   现在所有入口统一用 srcLabel，一张卡只有一种长相。
        try {
            LiveUpdate.sync(ctx, list.map {
                LiveUpdate.Entry(it.waybill, it.code, it.station, PickupGate.srcLabel(it.src))
            })
            IslandHelper.sync(ctx, list.map {
                IslandHelper.Entry(it.waybill, it.code, it.station, PickupGate.srcLabel(it.src))
            })
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }

        // ③ 新到的码 ⇒ 通知（后台发现"快递到了"就该提醒）
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val prev = try {
            sp.getStringSet(KEY_LAST_CODES, null)?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        val now = list.map { it.code }.toSet()
        val fresh = now - prev
        if (notifyNew && fresh.isNotEmpty()) {
            if (!TrackNotifier.canNotify(ctx)) {
                Log.i(TAG, "新到 ${fresh.size} 个取件码，但没有通知权限 ⇒ 只更新组件/流体云")
            } else {
                for (c in list.filter { it.code in fresh }) {
                    try {
                        TrackNotifier.notifyUpdate(
                            ctx,
                            no = "pickup:" + c.waybill.ifBlank { c.code },
                            company = "取件码已到",
                            text = "取件码 " + c.code,
                            address = c.station
                        )
                    } catch (t: Throwable) {
                        SafeRun.w(TAG, t)
                    }
                }
            }
        }
        sp.edit().putStringSet(KEY_LAST_CODES, now).apply()
        Log.i(TAG, "发布：待取 ${list.size} 个码（新到 ${fresh.size} 个）")
        return fresh.size
    }

    /**
     * ★ vc699：只**记账**不发布 —— 前台（第 4 栏渲染）自己会写 widget_codes，
     * 但它不碰"哪些码已经给用户看过了"这份账本；不记账的话，用户刚在 App 里看到的新码，
     * 下一轮后台还会当成"新到"再推一条通知。
     */
    fun markSeen(ctx: Context) {
        try {
            val now = pendingOf(ctx).map { it.code }.toSet()
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_LAST_CODES, now).apply()
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
    }
}
