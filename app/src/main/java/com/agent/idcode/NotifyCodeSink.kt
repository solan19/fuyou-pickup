package com.agent.idcode

import android.content.Context
import android.util.Log

/**
 * ★★ v1.4.24：**通知取件码的唯一写入出口**。
 *
 * 改之前 [IdNotifyListener.persistCodeFrom] 直接 `PddCodeStore.put`，
 * 只做了「同码丢弃 / 同单号接口优先」两道判据，**没过黑名单**：
 * 用户长按删掉的码、或「清已取」清掉的码，只要对应 App 再发一条通知就会被贴回来，
 * 和 v1.4.16 / v1.4.18 反复修的那类「删了又回来」是同一个病根。
 *
 * 现在所有想写通知码的地方（真通知 / 自检模拟 / debug 入口）都只调 [persist]：
 *   白名单准入（调用方）→ 黑名单 → 作废码 → 同码去重 → 同单号冲突 → 写缓存 → 发布。
 *
 * 另一条规矩：**不落通知全文**。旧版把整条通知文本 `take(80)` 塞进 `latest`，
 * 里面常带收货人姓名、地址、商品名；现在只留「哪个平台通知抓到的」一句摘要。
 */
object NotifyCodeSink {

    private const val TAG = "NotifyCodeSink"

    data class Result(
        val written: Boolean,
        val reason: String,
        val code: String,
        val platform: String,
    )

    /**
     * @param platformId [NotifyRules.PLATFORMS] 里的 id（pdd / dy / tb / jd / xhs …）
     * @param text       通知原文（只用于提取驿站、单号，**不落盘**）
     * @param code       已由 [NotifyRules.pickCode] 抠出的取件码
     */
    fun persist(
        ctx: Context,
        platformId: String,
        text: String,
        code: String,
        station: String = NotifyRules.stationOf(text),
        waybill: String = NotifyRules.waybillOf(text),
    ): Result {
        val label = NotifyRules.labelOf(platformId).ifEmpty { "通知" }
        if (code.isBlank()) return Result(false, "没抠到取件码", code, platformId)

        // ① 屏蔽名单：src 传空 ⇒ [MuteStore.srcCompatible] 返回 true，
        //    用户从**任何平台**删掉的码都能挡住这条通知（通知码本来就跨平台复用）。
        val hit = try {
            MuteStore.blocks(ctx, "", "", waybill, code, station)
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
            null
        }
        if (hit != null) {
            val why = "通知码 $code 命中屏蔽名单（记录 ${hit.id}）⇒ 不写回"
            Log.i(TAG, why)
            return Result(false, why, code, platformId)
        }

        // ② 一码多单作废表（v1.4.15）：这类码是抽取污染，任何来源都不许再贴回来
        if (PddCodeStore.isDead(ctx, code)) {
            val why = "通知码 $code 已作废（一码多单）⇒ 拒写"
            Log.i(TAG, why)
            return Result(false, why, code, platformId)
        }

        // ③ 与既有缓存对账（口径照搬旧 persistCodeFrom，不另立判据）
        val all = PddCodeStore.all(ctx)
        if (all.values.any { it.code == code }) {
            return Result(false, "通知码 $code 已存在（与接口来源一致）⇒ 丢弃，不重复写入", code, platformId)
        }
        if (waybill.isNotBlank()) {
            val clash = all.values.firstOrNull {
                it.waybill.isNotBlank() && it.waybill == waybill && it.code != code
            }
            if (clash != null) {
                return Result(
                    false,
                    "通知码 $code 与接口码 ${clash.code}（单号 $waybill）不一致 ⇒ 按接口优先保留",
                    code, platformId
                )
            }
        }

        // ④ 写入（src 仍是 "ntf"，所有既有 src 判断链不变；平台信息放独立字段）
        PddCodeStore.put(ctx, "ntf:$code", PddCodeStore.Cached(
            src = "ntf",
            code = code,
            station = station,
            waybill = waybill,
            latest = "$label 通知抓到的取件码",
            ts = System.currentTimeMillis(),
            platform = platformId,
        ))

        // ⑤ 即时发布：小组件 / 流体云 / 岛 + 一条通知（notifyNew=false 避免二次打扰）
        TrackNotifier.notifyUpdate(ctx, "ntf:$code", label, "通知里的取件码 $code", station)
        try {
            PickupPublish.publish(ctx, notifyNew = false)
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }

        val why = "通知码 $code 已写入（$label · 站=$station · 单号=$waybill）"
        Log.i(TAG, why)
        return Result(true, why, code, platformId)
    }
}
