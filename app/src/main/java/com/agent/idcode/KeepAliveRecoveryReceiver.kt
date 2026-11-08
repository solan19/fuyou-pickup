package com.agent.idcode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 复活接收器（手册 3.4）
 *
 * ## 为什么它必须 exported 又要加权限
 * exported = 才收得到 shell（Shizuku）发来的 `am broadcast`；
 * 但 exported 意味着**任何 App 都能给它发广播** ⇒ 两道锁：
 *  ① Manifest 里 `android:permission="android.permission.DUMP"` —— 普通 App 拿不到 DUMP 权限，
 *     所以它们发的广播系统在投递前就拦掉了（只有 shell / root / 系统能发）
 *  ② 即便发进来了，还要过令牌校验（[KeepAliveToken.matches]，常量时间比对）
 *
 * ## 自限制在这里生效（手册第 4 节）
 * - 息屏跳过：[KeepAliveGuard.isScreenOn]
 * - 尊重强制停止：[KeepAliveGuard.isForceStopped]
 * - 熔断：10 分钟内 ≥3 次 ⇒ 不再复活 [KeepAliveGuard.allowAnotherRecovery]
 */
class KeepAliveRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "${context.packageName}$ACTION_SUFFIX") return

        // ① 令牌校验
        val token = intent.getStringExtra("recovery_token")
        if (!KeepAliveToken.matches(token, KeepAliveToken.current(context))) {
            Log.w(TAG, "令牌不匹配，忽略")
            return
        }

        // ② 自限制：息屏 / 被强制停止 / 熔断
        if (!KeepAliveGuard.isScreenOn(context)) {
            Log.i(TAG, "息屏中 ⇒ 不复活（省电）")
            return
        }
        if (KeepAliveGuard.isForceStopped(context)) {
            Log.i(TAG, "已被强制停止 ⇒ 尊重用户，不复活")
            return
        }
        if (!KeepAliveGuard.allowAnotherRecovery(context)) {
            Log.w(TAG, "熔断生效 ⇒ 本次不复活")
            return
        }

        // ③ 拉起服务（内部还会再过一次"轮询是否关闭"的判断）
        Log.i(TAG, "收到合法复活广播 ⇒ 拉起常驻服务")
        KeepAliveService.start(context)
    }

    companion object {
        private const val TAG = "KeepAliveRecovery"
        const val ACTION_SUFFIX = ".action.RECOVER_SERVICE"
    }
}
