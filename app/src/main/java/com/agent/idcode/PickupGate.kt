package com.agent.idcode

import android.content.Context
import android.util.Log

/**
 * ★★ v1.4.18：**「哪些取件码还算待取、该给用户看」的唯一裁决点**。
 *
 * ## 为什么要有它
 * 同一件事（这个码还该不该出现）原来在四条链上**各判一遍**，每条都少一点东西 ——
 * 结果就是"第 4 栏已经没有的码，流体云 / 小组件 / 通知上还在"（同志 14:36 报：
 * 流体云仍显示 3 件，但实际只有 1 个取件码）。
 *
 * | 链路 | 旧口径 | 缺什么 |
 * |---|---|---|
 * | 第 4 栏 / 首页 | 有码 + 未取件 + **黑名单**（v1.4.16 补的） | — |
 * | 流体云 [`PickupPublish.pendingOf`] | 有码 + 未取件 | **黑名单** |
 * | 流体云 [`LiveUpdate.syncFromSnapshot`] | 有码 + 未取件 + ExpressStore 快照交集 | **黑名单**，且**与上一条不同源** |
 * | 桌宠岛 [`IslandHelper.syncFromSnapshot`] | 同上 | 同上 |
 *
 * 后三条没有黑名单闸，于是「清已取 / 长按删除」之后：缓存条目虽然被移除，
 * 但平台下一轮照样把件报成"在驿站待取"，补码链一跑条目又长回来
 * （`pickedAt = 0`、无标记）⇒ 它又成了一条"正常待取码"，被推上流体云。
 *
 * ## 另一个隐患：两个数据源互相覆盖
 * `PickupPublish` 用整个 [PddCodeStore]，`syncFromSnapshot` 用
 * 「ExpressStore 快照 ∩ PddCodeStore」，两者**经常不是同一个集合**
 * （快照是合并语义、会留陈旧条目；而快照里也可能缺刚抓到的码）。
 * 于是同一时刻两次 `sync` 传进去的列表不同，指纹来回翻 ⇒ 通知内容反复重发 /
 * 来回抖 —— 同志说的"流体云并没有遵循某个规则"就包含这一层。
 *
 * 现在四条链全部走 [aliveEntries]：**有码 + 未取件 + 不在黑名单**，单一来源、单一口径。
 *
 * ## 时间语义（照旧，不用在这层另写）
 * 全靠 [`MuteStore.Entry.holdUntil`]：
 *  · 「标记已取件」写的记录 `holdUntil = 24h 后` ⇒ 此刻 `blocks()` 返回 null ⇒ 灰卡期照常显示；
 *  · 「清已取 / 长按删除」写的记录 `holdUntil = 0` ⇒ 立刻命中 ⇒ 当轮就从所有链路消失。
 */
object PickupGate {

    private const val TAG = "PickupGate"

    /**
     * 当前**仍然有效**的取件码条目（`PddCodeStore` 的 key → 条目），新的在前。
     *
     * 判据只有三条，顺序即优先级：
     *  1. 得有码（空码 = 已被「一码多单」作废表抹掉，见 `PddCodeStore.sweepShared`）；
     *  2. 得没取走（`pickedAt == 0`）；
     *  3. 得不在黑名单里（`MuteStore.blocks` 非 null 就剔）。
     *
     * 任何一步抛异常都按"保留"处理 —— 宁可多显示一个码，也不能因为一次异常把用户手上的
     * 取件码从流体云上抹掉（那是他真正要去取的）。
     */
    fun aliveEntries(ctx: Context): List<Pair<String, PddCodeStore.Cached>> = try {
        val out = ArrayList<Pair<String, PddCodeStore.Cached>>()
        for ((k, c) in PddCodeStore.all(ctx)) {
            if (c.code.isBlank()) continue
            if (c.pickedAt != 0L) continue
            val orderSn = k.substringAfter(':', "")
            val hit = try {
                MuteStore.blocks(ctx, c.src, orderSn, c.waybill, c.code, c.station)
            } catch (t: Throwable) {
                SafeRun.w(TAG, t)
                null
            }
            if (hit != null) {
                Log.i(
                    TAG,
                    "名单里的码不发布：${c.code}（单 ${orderSn.take(20)}，记录 ${hit.id}）"
                )
                continue
            }
            out.add(k to c)
        }
        out.sortByDescending { it.second.ts }
        out
    } catch (t: Throwable) {
        SafeRun.w(TAG, t)
        emptyList()
    }

    /** 只要码集合（需要按码比对的场景用） */
    fun aliveCodes(ctx: Context): Set<String> = aliveEntries(ctx).map { it.second.code }.toSet()

    /**
     * 来源 → 承运商中文名。
     *
     * 为什么需要：`PddCodeStore.Cached` 只有 `src`（`pdd` / `jd` / `tb` / `mi` …），
     * 而流体云正文写的是「码 · 驿站 · 承运商」。原来这个字段从 `ExpressStore` 快照里取，
     * 改成从 `PddCodeStore` 单源之后要自己映射；认不出来的源就不写（别硬编一个错的）。
     */
    fun srcLabel(src: String): String = when (src.lowercase()) {
        "pdd" -> "拼多多"
        "jd" -> "京东"
        "tb" -> "淘宝"
        "mi" -> "小米"
        "cainiao" -> "菜鸟"
        else -> ""
    }

    /** 条目的"身份号"：优先运单号，其次 key 里的订单号（流体云 `no` 字段、去重用） */
    fun noOf(key: String, c: PddCodeStore.Cached): String =
        c.waybill.ifBlank { key.substringAfter(':', "") }
}
