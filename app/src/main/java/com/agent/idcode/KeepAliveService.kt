package com.agent.idcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * ★★★ 常驻前台服务（vc264）
 *
 * ## 为什么改这条路（2026-10-15 实测的结论）
 * 原来是"唤醒式"：靠 AlarmManager 闹钟 + WorkManager 周期任务把进程叫醒干活。
 * 但今晚实测：ColorOS 会把**不活跃 App 的闹钟触发时间改写到约 +72 小时后** ——
 * 非精确闹钟、精确闹钟（`setExactAndAllowWhileIdle` + `USE_EXACT_ALARM`）、
 * 甚至 `setAlarmClock` 特权通道**全都被推迟**，AOSP 电池白名单也无效。
 * ⇒ 同志拍板：改成常驻（不再指望"到点叫醒"）。
 *
 * ## 手册三层地基之第一层（照《Android常驻防杀机制-施工手册》3.2）
 * - 通知渠道 `IMPORTANCE_LOW`：**不响铃、不震动、不弹窗**
 * - `setOngoing(true)`：**常驻且不可滑动清除**
 * 这样既抬了进程优先级（扛一键清理、扛划掉），又不招人烦。
 *
 * ## 调度权在这里
 * 服务起来后按 [ExpressStore.pollIntervalMin] 的间隔自己定时查快递
 * （复用 [TrackScheduler.runNow]，与后台 Worker 共用同一份比对/通知逻辑，不写第二遍）。
 * 轮询间隔设为 0（用户关闭）⇒ 服务自行停止，回到"不常驻"。
 *
 * ## 自限制（手册第 4 节，灵魂）
 * 启动即过 [KeepAliveGuard.isForceStopped]：用户在设置里点了「强行停止」⇒ **不再自启**。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        // ③ 尊重强制停止的判断放在 [onStartCommand]：onCreate 拿不到 intent，
        //    分不清是"用户打开 App"还是"后台自愈"，会误杀用户主动启动的情况。
        startAsForeground()
        KeepAliveToken.ensure(this)
        scheduleNextPoll(3_000L)          // 起来 3 秒后先跑一次（刚开机/重启时及时）
        // 可选增强：**只有设置页开关打开时才连保护进程**（同志 10-15 改成开关式）。
        // 开关关着就完全不碰对应框架 —— 不申请权限、不起独立进程。
        // 两个框架并存：各走各的路径，装了哪个走哪个。
        if (ShizukuBridge.isEnabled(this)) {
            ShizukuBridge.bindWatchdog(this)
        }
        if (StellarBridge.isEnabled(this)) {
            StellarBridge.bindWatchdog(this)
        }
        Log.i(TAG, "常驻服务已启动")
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // ★★★ vc264k（同志 03:43 要求"通知栏别挂着它"）：
        //   Android 硬规则：前台服务**必须挂一条通知**（这是它换防杀能力的代价），
        //   "真删"的唯一途径是放弃前台服务 ⇒ 防杀降一大档，不做。
        //   能做到的最优解：**IMPORTANCE_MIN**（最低档 ⇒ ColorOS 会把它折叠收纳，
        //   几乎不可见）+ 锁屏隐藏 + 静默 + 文案精简。
        //   （渠道重要性可以下调；已存在的同名渠道重调 createNotificationChannel 即生效）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台快递跟踪", NotificationManager.IMPORTANCE_MIN)
                .apply {
                    description = "保持快递动态跟踪可用（不响铃、不震动、不显示在锁屏）"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
        )
        // ★ 清掉旧渠道（vc264k 遗留）：旧渠道 `idcode_keep_service` 已被系统"锁定"
        //   （App 降不了它的 importance，实测改成 MIN 后 dumpsys 仍 mImportance=2）
        //   ⇒ 换新渠道 ID（首次创建即 MIN 档），旧渠道顺手删掉（无通知挂着时才删得掉，失败无害）
        try {
            nm.deleteNotificationChannel("idcode_keep_service")
        } catch (t: Throwable) { SafeRun.w("KeepAliveService", t) }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            // 文案精简到最短（MIN 档下本就几乎不可见，能折叠成一行小字）
            .setContentTitle("蜉蝣")
            .setContentText("后台跟踪中")
            .setContentIntent(pi)
            .setOngoing(true)              // ★ 常驻、不可滑掉
            .setShowWhen(false)
            // 静默靠渠道层面保证：MIN 档 + 渠道未配置 sound/vibration ⇒ 不响不震
            .setVisibility(Notification.VISIBILITY_SECRET)   // 锁屏不显示
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTI_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    /** 按当前设置间隔排下一次查询；间隔 ≤ 0（用户关闭轮询）⇒ 停止常驻 */
    private fun scheduleNextPoll(delayMs: Long) {
        pollRunnable?.let { handler.removeCallbacks(it) }
        val min = ExpressStore.pollIntervalMin(this)
        if (min <= 0) {
            Log.i(TAG, "轮询已关闭（间隔=0）⇒ 停止常驻，不再占用后台")
            stopSelf()
            return
        }
        pollRunnable = Runnable {
            Log.i(TAG, "定时查询一次快递（间隔 ${min} 分钟）")
            try {
                TrackScheduler.runNow(this)
            } catch (t: Throwable) {
                Log.w(TAG, "定时查询异常: ${t.message}")
            }
            scheduleNextPoll(min * 60_000L)
        }
        handler.postDelayed(pollRunnable!!, delayMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ③ 尊重强制停止 —— 但**只针对后台自愈**：
        //   用户自己打开 App 是明确意图（EXTRA_FROM_USER），必须照常起服务；
        //   否则会出现"用户强制停止过一次，之后他主动开 App 也永远没服务"的死局。
        val fromUser = intent?.getBooleanExtra(EXTRA_FROM_USER, false) ?: false
        if (!fromUser && KeepAliveGuard.isForceStopped(this)) {
            Log.i(TAG, "后台自愈 + 检测到已被强制停止 ⇒ 尊重用户，停止")
            stopSelf()
            return START_NOT_STICKY
        }
        // 设置里改了间隔 ⇒ 用一次 startService 触发重排
        pollRunnable?.let { scheduleNextPoll(ExpressStore.pollIntervalMin(this) * 60_000L) }
        return START_STICKY          // 被系统杀后尽量重建
    }

    /** ★ 课题一（后台低占用）的落地：系统内存紧张时主动放手，反而更不容易被杀 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "系统内存紧张 level=$level ⇒ 主动释放缓存")
        try {
            Runtime.getRuntime().gc()
        } catch (t: Throwable) { SafeRun.w("KeepAliveService", t) }
    }

    override fun onDestroy() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        // ★ 正常停止时销毁令牌，别让暗号长期躺在磁盘上（手册要求）
        KeepAliveToken.clear(this)
        Log.i(TAG, "常驻服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "KeepAliveService"
        /** ★ v2（vc264k）：老 ID `idcode_keep_service` 被系统锁定（降不了重要性），换新 ID 重开 */
        const val CHANNEL = "idcode_keep_service_v2"
        private const val NOTI_ID = 1001
        /** 标记"这次是用户主动打开 App 拉起来的"，用于区分后台自愈（后者要尊重强制停止） */
        const val EXTRA_FROM_USER = "from_user"

        /**
         * 拉起常驻服务。
         * @param fromUser true = 用户主动打开 App（**不受"强制停止"限制**，用户意图最优先）
         *                 false = 后台自愈（开机/闹钟/看门狗），此时要尊重用户曾经的「强行停止」
         */
        fun start(ctx: Context, fromUser: Boolean = false) {
            if (!fromUser && KeepAliveGuard.isForceStopped(ctx)) {
                Log.i(TAG, "已被强制停止 ⇒ 不自愈")
                return
            }
            if (ExpressStore.pollIntervalMin(ctx) <= 0) {
                Log.i(TAG, "轮询已关闭 ⇒ 不拉起常驻服务")
                return
            }
            try {
                val i = Intent(ctx, KeepAliveService::class.java)
                    .putExtra(EXTRA_FROM_USER, fromUser)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "拉起常驻服务失败: ${t.message}")
            }
        }

        /** 服务是否在运行（用 ActivityManager 查，API 兼容到 15） */
        fun isRunning(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            return try {
                am.getRunningServices(Int.MAX_VALUE).any {
                    it.service == ComponentName(ctx, KeepAliveService::class.java)
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
