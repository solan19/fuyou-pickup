package com.agent.idcode

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.regex.Pattern

/**
 * ★ 系统绑定型服务（手册第 3 层：manifest 清单 ④）
 *
 * ## 它的第一价值是"防杀"，不是"听通知"
 * `NotificationListenerService` 由**系统（SystemUI/NMS）亲自绑定** ⇒
 *   ① 系统绑定的服务优先级天然高一档
 *   ② 进程死了系统会**自动重绑**（等于白捡一条复活路径）
 * 手册把它列为"可选但强烈建议"，正是这个原因。
 *
 * ## 顺带的第二价值：白捡通知里的取件码
 * 菜鸟 / 微信 / 各快递 App 的到件通知里常常**直接写着取件码** ——
 * 这是"别人替你跑"的免费通道（不花快递100 的钱、不需要登录任何电商账号）。
 * 解析规则借鉴 GitHub `fanhua12/express-pickup`：先看像不像快递消息，再用正则抠码。
 *
 * ⚠️ 前提：用户要在系统设置里开「通知使用权」；没开时本服务不会被绑定，
 *   也不影响蜉蝣其它任何功能（只是少了这一层）。
 *
 * ★ vc316（同志 2026-10-18 定）：**App 不再自己猜「件取没取走」** ——
 *   既不靠系统通知（没装菜鸟 App 就没这条通道），也不靠数据源推断。
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
        val n = sbn?.notification ?: return
        // 自己发的通知别再抓，会循环
        if (sbn.packageName == packageName) return
        val extras = n.extras ?: return

        val pool = StringBuilder()
        appendText(pool, extras.getCharSequence(Notification.EXTRA_TITLE))
        appendText(pool, extras.getCharSequence(Notification.EXTRA_TEXT))
        appendText(pool, extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        appendText(pool, extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null) for (l in lines) appendText(pool, l)

        val text = pool.toString()
        // 先合起来看一眼像不像快递消息，不像就直接走（省得每条通知都白忙）
        if (!looksLikeExpress(text)) return

        val code = pickCode(text)
        if (code.isBlank()) {
            Log.i(TAG, "像快递消息但没抠到取件码（来源 ${sbn.packageName}）")
            return
        }
        Log.i(TAG, "从通知里抠到取件码 $code（来源 ${sbn.packageName}）")
        TrackNotifier.notifyUpdate(this, "ntf:$code", "", "通知里的取件码 $code")
    }

    companion object {
        private const val TAG = "IdNotifyListener"

        /** 快递消息关键词闸（照 express-pickup 的思路） */
        private val HINTS = listOf(
            "取件", "取货", "提货", "快递", "速递", "包裹", "驿站", "快递柜",
            "丰巢", "速递易", "菜鸟", "派件", "签收", "代收点", "快件", "送达", "柜"
        )

        /** 取件码正则：优先认"取件码X"这种明写，其次认驿站附近的横杠格口码 */
        private val P_CODE = Pattern.compile(
            "(?:取件码|取货码|提货码|提取码|凭码|取件口令|取件密码)[是为：:\\s]*([0-9A-Za-z]{2,6}(?:-[0-9A-Za-z]{1,6}){0,3})"
        )
        private val P_NEAR = Pattern.compile(
            "([0-9A-Za-z]{2,6}(?:-[0-9A-Za-z]{1,6}){1,3})(?=[^0-9A-Za-z]{0,8}(?:取件|驿站|柜|代收点|自提))"
        )

        fun looksLikeExpress(text: String): Boolean = HINTS.any { text.contains(it) }

        fun pickCode(text: String): String {
            var m = P_CODE.matcher(text)
            if (m.find()) return m.group(1)?.trim().orEmpty()
            m = P_NEAR.matcher(text)
            if (m.find()) return m.group(1)?.trim().orEmpty()
            return ""
        }

        private fun appendText(sb: StringBuilder, cs: CharSequence?) {
            if (!cs.isNullOrEmpty()) sb.append(cs).append(' ')
        }
    }
}
