package com.agent.idcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 快递跟踪通知。
 *
 * ## 三条设计（对齐参考项目）
 * 1. **通知 id 用单号 hashCode** ⇒ 同一个包裹只占一条通知，新动态自动覆盖旧的，
 *    不会攒成一屏；用户划掉后也不会「复活」旧的那条。
 * 2. **同一条合并显示**（`setOnlyAlertOnce(false)` + 覆盖式 id）：每次有新轨迹就再响一次，
 *    这是想要的（否则用户不知道又动了）。
 * 3. 通知渠道 `IMPORTANCE_DEFAULT`（有声音、不弹横幅）—— 物流动态不值得弹窗打断。
 *
 * 渠道必须**显式创建一次**（Android 8+ 不建渠道通知会被静默丢弃）。
 * 用平台 API 而不是 androidx 的 NotificationCompat：minSdk 31，
 * `Notification.Builder(ctx, channelId)` 本来就够用，少一层依赖。
 */
object TrackNotifier {

    const val CHANNEL = "idcode_tracking"

    /**
     * ★★ v1.4.28：**备用渠道**。
     *
     * 事故（同志 2026-11-11：「通过通知抓取到了取件码，但 app 不推送」）：
     * 真机 `dumpsys notification` 显示 `idcode_tracking` 的 `mImportance=0`
     * （= IMPORTANCE_NONE，被用户/系统关成了"关闭"）—— 而 Android 的规则是
     * **渠道一旦被用户改过，App 再也改不回来**（mUserLockedFields）。于是所有
     * `notifyUpdate` 都发进了一个"不存在"的渠道，静默丢弃。
     *
     * 解法是标准做法：原渠道被关 ⇒ 换一个**新 id** 的渠道（新渠道按默认重要性创建），
     * 用户仍可以单独关掉它，但不会因为历史误关而永久收不到任何通知。
     */
    private const val CHANNEL_FALLBACK = "idcode_tracking_v2"
    private const val CHANNEL_NAME = "快递跟踪"

    /** 当前该用哪个渠道：原渠道被关 ⇒ 备用渠道 */
    private fun channelId(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return CHANNEL
        val cur = nm.getNotificationChannel(CHANNEL) ?: return CHANNEL
        return if (cur.importance == NotificationManager.IMPORTANCE_NONE) CHANNEL_FALLBACK else CHANNEL
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val id = channelId(ctx)
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "被跟踪的快递有新物流动态时提醒"
            }
        )
    }

    /** Android 13+ 需要运行时授权；没授权时通知会被系统丢掉，需要提示用户去设置里开 */
    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 推送一条「有新动态」。
     * @param no       单号（决定通知 id，保证同单覆盖）
     * @param company  快递公司名（没有就用单号兜底）
     * @param text     最新轨迹（截 120 字，超出会折行太难看）
     * @param address  取件地址/驿站名（可选）—— ★ vc345：到站件的通知里也要能看到去哪取
     */
    fun notifyUpdate(ctx: Context, no: String, company: String, text: String, address: String = "") {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val title = if (company.isBlank()) "快递有新动态" else "$company 有新动态"
        val main = text.ifBlank { "物流状态已更新" }.take(120)
        val addr = address.trim().take(60)
        // 收起时只显示轨迹（一行）；展开后（BigText）带取件地址
        val full = if (addr.isEmpty()) main else "$main\n取件地址：$addr"
        // ★ vc345：收起时也把地址摆出来（到站件用户最想知道"去哪取"）；没地址才显示轨迹
        val collapsed = if (addr.isEmpty()) main else "取件地址：$addr"
        // ★ v1.4.28：渠道被关时自动用备用渠道（见 [channelId]）
        val n = Notification.Builder(ctx, channelId(ctx))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(no.hashCode(), n)
        } catch (_: Throwable) {
            // 渠道被用户关掉 / 权限被撤 都会走到这，静默即可 —— 不能因为发不出通知就让 Worker 挂掉
        }
    }

    /** 调试用：验证通知链路是否通（adb --es debug_notify "文案"） */
    fun notifyTest(ctx: Context, text: String) {
        notifyUpdate(ctx, "idcode_debug", "测试", text)
    }

    /** ★ vc432：登录过期提醒（顺丰会话约一天失效，且无法静默续期） */
    fun notifyLoginExpired(ctx: Context, source: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val full = source + " 的登录会话在服务端已失效（本地 Cookie 还在，但服务端不认）。\n" +
                "点此打开蜉蝣，在设置里重新登录一次即可恢复。"
        val n = Notification.Builder(ctx, channelId(ctx))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(source + " 登录已过期")
            .setContentText("点此打开蜉蝣重新登录")
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(("login_" + source).hashCode(), n)
        } catch (t: Throwable) { SafeRun.w("TrackNotifier", t) }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
