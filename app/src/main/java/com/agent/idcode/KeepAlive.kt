package com.agent.idcode

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * ★★★ 曲线救国：**不常驻内存**的"唤醒锚点"（2026-10-12 新增）
 *
 * ## 要解决的问题
 * 实测（2026-10-12，ColorOS/PKX110）：**在最近任务里上滑划掉本 App**，ColorOS 会把
 * JobScheduler 里 WorkManager 的周期任务**一起撤掉** ⇒ 后台自动查快递停摆，且不会自己恢复。
 * 参考项目 `ExpressAssistant` 用的是同一套 WorkManager 周期任务，**同样有这个问题**。
 *
 * ## 为什么不用前台服务
 * 前台服务确实能扛住"划掉"，但它要**常驻一条通知 + 常驻内存**。同志明确不想为这个常驻。
 *
 * ## 这里的办法：AlarmManager 兜底闹钟
 * - **闹钟与 JobScheduler 是两套独立子系统** ⇒ 有可能不被"划掉"一起撤（本文件就是去验证/利用这一点）
 * - 用 `setAndAllowWhileIdle`（**非精确闹钟，不需要任何特殊权限**，符合项目铁律①）
 * - 被唤醒时**系统才把进程拉起来**，跑完就结束 ⇒ **不常驻内存**，平时零占用
 * - 每次醒来做三件事：① `TrackScheduler.apply()` 把被撤的周期任务补回来
 *   ② `TrackScheduler.runNow()` 立刻查一次快递 ③ 继续布置下一个兜底闹钟（自续期）
 *
 * ## 搭车：一组"豁免隐式广播"
 * Android 8+ 之后绝大多数隐式广播不能再在 manifest 里注册，但官方仍豁免了一批
 * （见 developer.android.com 的 "Implicit broadcast exceptions"）。这些广播来的时候
 * 顺手也做上面三件事，相当于白捡的唤醒点。**不要注册 ACTION_POWER_CONNECTED 之类的**
 * —— 它已经不在豁免名单里，注册了也收不到。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    /** 兜底闹钟的自定义动作（PendingIntent 显式指向本类，一定会送达） */
    const val ACTION_TICK = "com.agent.idcode.action.KEEPALIVE"

    /** 默认续期间隔（分钟）。与轮询间隔解耦：闹钟只是"补一次机会"，不必太频繁。 */
    const val DEFAULT_MIN = 25L

    private const val REQ = 0x1DC0DE
    private const val PREF = "idcode_prefs"
    private const val KEY_MIN = "keepalive_min"

    /**
     * ★ 间隔存 SP，而不是每次都用常量。
     *   踩过的坑（2026-10-12）：receiver 醒来自我续期时调的是 `arm(ctx)`（默认 25 分钟），
     *   把 MainActivity 里为调试设的短间隔（`--ei debug_alarm_min 3`）当场覆盖掉了
     *   ⇒ "3 分钟后就响"变成"25 分钟后"，测试白等。
     */
    fun storedMin(ctx: Context): Long = ctx
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getInt(KEY_MIN, DEFAULT_MIN.toInt()).toLong()

    fun setInterval(ctx: Context, min: Long) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MIN, min.toInt()).apply()
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ,
        Intent(ctx, KeepAliveReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 布置一次兜底闹钟（每次唤醒后自续期）。intervalMin ≤ 0 表示撤销；< 0 表示用存下来的值。 */
    fun arm(ctx: Context, intervalMin: Long = -1L) {
        val iv = if (intervalMin < 0) storedMin(ctx) else intervalMin
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (iv <= 0) {
            am.cancel(pending(ctx))
            Log.i(TAG, "兜底闹钟已撤销")
            return
        }
        val at = System.currentTimeMillis() + iv * 60_000L
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val useAlarmClock = sp.getBoolean("debug_alarm_clock", false)
        try {
            // ★★★ 2026-10-15 实测（PKX110/ColorOS）：
            //   ① setAndAllowWhileIdle：App 不活跃时触发时间被改写到约 +72 小时（形同虚设）
            //   ② setExactAndAllowWhileIdle（USE_EXACT_ALARM 已授予）：照样被推迟 72 小时
            //   ③ AOSP 电池白名单（deviceidle whitelist）：无效，ColorOS 走自家省电框架
            //   ⇒ 最后一条技术路：setAlarmClock（"用户闹钟"特权通道，状态栏会显示闹钟图标）。
            //     系统时钟 App 自己靠的就是它，ColorOS 大概率放行。副作用是状态栏图标，
            //     所以做成开关（debug_alarm_clock），让同志真机对比后决定用不用。
            if (useAlarmClock) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pending(ctx)), pending(ctx))
                Log.i(TAG, "兜底闹钟已布置（setAlarmClock 特权通道）：$iv 分钟后（$at）")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
                Log.i(TAG, "兜底闹钟已布置（精确）：$iv 分钟后（$at）")
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
                Log.i(TAG, "兜底闹钟已布置（非精确，精确权限不可用）：$iv 分钟后（$at）")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "布置兜底闹钟失败: ${e.message}")
        }
    }

    /** 撤销（用户关掉后台轮询时用） */
    fun disarm(ctx: Context) = arm(ctx, 0)
}

/**
 * 兜底闹钟 + 豁免广播的统一入口。
 *
 * ★ 两条来源走同一个 receiver：
 *  ① 自己布置的闹钟（显式 PendingIntent）——**这是主力**
 *  ② 系统发出的几条"豁免隐式广播"（开机/时间变更/语言变更/USB/下一个闹钟变化/包被卸等）
 *     —— 白捡的唤醒点，来了就顺手补一次
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val act = intent.action ?: "(null)"
        Log.i("KeepAlive", "被唤醒：$act")
        // ★ 2026-10-15：setAlarmClock 排上后系统会发 NEXT_ALARM_CLOCK_CHANGED，
        //   本 receiver 又把它当唤醒点重新 arm ⇒ arm→广播→arm 链式循环（实测 5 连发）。
        //   除闹钟自身触发（ACTION_TICK，必须续期）外，其余唤醒一律 60 秒节流。
        val sp = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = sp.getLong("keepalive_last_arm_ms", 0L)
        val throttled = act != KeepAlive.ACTION_TICK && now - last < 60_000
        if (throttled) {
            Log.i("KeepAlive", "距上次处理仅 ${now - last}ms，本次跳过（防 NEXT_ALARM_CLOCK_CHANGED 链式）")
            return
        }
        try {
            // ① 把可能被"划掉"撤走的周期任务补回来
            TrackScheduler.apply(ctx)
            // ★ v1.4.24：开机 / 时间变更 / 时区变更后把定时提醒排程补回来
            RemindScheduler.apply(ctx)
            // ② 立刻查一次（同一个 worker，与周期任务共用一份判定逻辑）
            TrackScheduler.runNow(ctx)
            // ③ ★ vc264：调度权已交给常驻服务。这里只做"服务不在就拉它一把"的无特权自愈
            //    （闹钟本身在 ColorOS 上会被推迟约 72h，所以这只是聊胜于无的补充；
            //     真正可靠的自愈是 Shizuku 保护进程 + 系统重绑的通知监听/磁贴）
            if (!KeepAliveService.isRunning(ctx)) {
                // 注意：本类（KeepAliveReceiver）是独立类，拿不到 object KeepAlive 里的 TAG
                Log.i("KeepAlive", "常驻服务不在 ⇒ 借这次唤醒拉一把")
                KeepAliveService.start(ctx)
            }
        } catch (e: Throwable) {
            Log.w("KeepAlive", "重建/补跑失败: ${e.message}")
        }
        // ③ 自续期：把下一个兜底闹钟排上（漏了这一步就只醒一次）
        try {
            KeepAlive.arm(ctx)
            sp.edit().putLong("keepalive_last_arm_ms", now).apply()
        } catch (e: Throwable) {
            Log.w("KeepAlive", "续期失败: ${e.message}")
        }
    }
}
