package com.agent.idcode

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * ★ 系统绑定型服务（手册第 3 层：manifest 清单 ④）
 *
 * ## 它的第一价值是"防杀"，不是"听通知"
 * `NotificationListenerService` 由**系统（SystemUI/NMS）亲自绑定** ⇒
 *   ① 系统绑定的服务优先级天然高一档
 *   ② 进程死了系统会**自动重绑**（等于白捡一条复活路径）
 *
 * ## 第二价值：白捡通知里的取件码（★ v1.4.24 平台化）
 * 规则全部收敛到 [NotifyRules]，写入全部收敛到 [NotifyCodeSink]：
 *   包名 → 白名单平台 → 该平台关键词 → 强取件语义闸 → 抠码 → 唯一写入出口。
 *
 * 与 v1.4.23 之前的差别：
 *   · 不再对**任何 App** 的通知跑同一套正则（营销 push 里的数字不再被当码）；
 *   · 抖音 / 小红书 / 团购自提的「提货码 / 自提码 / 货架号 / 取件凭证」能认了；
 *   · 写入前补上黑名单与作废码闸，删掉的码不会被通知贴回来；
 *   · 条目带 `platform`，取件码页显示「通知·抖音」而不是笼统的「通知」；
 *   · 不再把通知全文塞进 `latest`（只留平台摘要）。
 *
 * ⚠️ 前提：用户要在系统设置里开「通知使用权」；没开时本服务不会被绑定，
 *   也不影响蜉蝣其它任何功能（只是少了这一层）。
 *
 * ★ vc316（同志 2026-10-18 定）：**App 不再自己猜「件取没取走」** ——
 *   「已取件」只由用户长按取件码卡片手动打标记（24h 后自动清）。本服务只保留"白捡取件码"这一件事。
 */
class IdNotifyListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "系统已绑定通知监听（系统绑定型服务生效 ⇒ 进程被杀会自动重绑）")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听被解绑（用户关闭了通知使用权或系统回收）")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val n = s.notification ?: return
        // 自己发的通知别再抓，会循环
        if (s.packageName == packageName) return

        // ★★ v1.4.24 唯一准入闸：总开关 + 包名在白名单 + 该平台被用户勾选
        if (!NotifyPrefs.isAllowed(this, s.packageName)) return
        val platform = NotifyRules.platformOf(s.packageName) ?: return

        val extras = n.extras ?: return
        val pool = StringBuilder()
        appendText(pool, extras.getCharSequence(Notification.EXTRA_TITLE))
        appendText(pool, extras.getCharSequence(Notification.EXTRA_TEXT))
        appendText(pool, extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        appendText(pool, extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null) for (l in lines) appendText(pool, l)

        val text = pool.toString()
        if (text.isBlank()) return

        // ① 有取件语义才允许写码；没抠到就只当"到站通知"触发一次即时抓取
        if (NotifyRules.hasPickupContext(text)) {
            val code = NotifyRules.pickCode(text)
            if (code.isNotBlank()) {
                val r = NotifyCodeSink.persist(this, platform.id, text, code)
                Log.i(TAG, "${platform.label} → ${r.reason}")
                // ★ vc708：顺手触发一次即时抓取（补齐轨迹、地址、其它平台的码），5 分钟节流
                maybeTriggerImmediateFetch(s.packageName)
                return
            }
            Log.i(TAG, "${platform.label}：像取件消息但没抠到码（来源 ${s.packageName}）")
        }

        // ② 弱信号也值得抓一轮：到站 / 派件类通知常不带码，码由接口给
        if (NotifyRules.looksLikeExpress(text, platform)) {
            maybeTriggerImmediateFetch(s.packageName)
        }
    }

    /**
     * ★★ vc708：**事件驱动即时抓取**（5 分钟节流）。
     * 快递类通知到得比 WorkManager 周期准，把它当触发器立刻跑一次 [TrackScheduler.runNow]。
     */
    private fun maybeTriggerImmediateFetch(pkg: String) {
        try {
            val sp = getSharedPreferences("idcode_prefs", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = sp.getLong("notify_fetch_at", 0L)
            val gap = now - last
            if (gap < 5 * 60_000L) {
                Log.i(TAG, "通知触发抓取：${gap / 1000}s 前刚跑过，跳过（来源 $pkg）")
                return
            }
            sp.edit().putLong("notify_fetch_at", now).apply()
            Log.i(TAG, "通知触发即时抓取（来源 $pkg）")
            TrackScheduler.runNow(this)
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
    }

    companion object {
        private const val TAG = "IdNotifyListener"

        // ---- 兼容旧调用点（自检页「模拟一条取件码通知」、旧 debug 入口）----
        // 真正的规则在 NotifyRules、写入在 NotifyCodeSink；这里只转发，避免两处各判一遍。
        fun looksLikeExpress(text: String): Boolean = NotifyRules.looksLikeExpress(text)
        fun pickCode(text: String): String = NotifyRules.pickCode(text)
        fun waybillOf(text: String): String = NotifyRules.waybillOf(text)
        fun stationOf(text: String): String = NotifyRules.stationOf(text)

        /** 自检 / 模拟通知：默认按拼多多样例平台写入，仍走 [NotifyCodeSink] 全部门禁 */
        fun persistCodeFrom(
            ctx: Context,
            text: String,
            code: String,
            platformId: String = "pdd",
        ): String = NotifyCodeSink.persist(ctx, platformId, text, code).reason

        private fun appendText(sb: StringBuilder, cs: CharSequence?) {
            if (!cs.isNullOrEmpty()) sb.append(cs).append(' ')
        }
    }
}
