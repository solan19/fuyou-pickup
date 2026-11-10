package com.agent.idcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ★★ v1.4.24：**定时取件提醒 —— 完全由用户自定义，程序不内置任何业务时刻**。
 *
 * 配置形态（同志 23:02 定的最终口径）：
 *  · 用户选一个「允许提醒的时间段」（起–止，支持跨天，如 22:00–02:00）；段外一律不响；
 *  · 段内由用户**逐个指定具体提醒时刻**，每个点提醒一次；
 *  · 一天在该段内**最多 6 次**，满 6 个后禁止继续添加；
 *  · 「固定间隔」不是独立自动规则，只是设置页的「一键铺点」辅助：
 *    铺出来的点写进同一个时刻列表、同样占 6 个名额、可逐个删改。
 *
 * 默认：开关关闭、窗口全天 00:00–23:59、时刻列表为空 —— 用户没配过就绝不提醒。
 *
 * ## 为什么用「15 分钟周期 Worker + 到点补发」而不是精确闹钟
 * ColorOS 上 `setExactAndAllowWhileIdle` 实测会被推迟数十小时（工程里 [KeepAlive] 有取证），
 * WorkManager 周期任务也会被系统合并/延后。所以这里不依赖"准时唤醒"，而是每次 Worker
 * 醒来都算一遍：当前时刻落在哪个 slot 之后的 [CATCHUP_MIN] 分钟内、且该 slot 当天没发过
 * ⇒ 补发一次。这样延迟唤醒不漏提醒，超过容差就安静跳过（不会半夜突然响）。
 *
 * 数据源只走 [PickupGate.aliveEntries]（有码 + 未取 + 不在黑名单），不另写一份判断。
 */
object PickupReminder {

    /** 一个提醒日内最多提醒几次 */
    const val MAX_SLOTS = 6

    private const val TAG = "PickupReminder"
    private const val PREF = "idcode_prefs"
    private const val KEY_ENABLED = "remind_enabled"
    private const val KEY_START = "remind_window_start"
    private const val KEY_END = "remind_window_end"
    private const val KEY_TIMES = "remind_times"
    private const val KEY_FIRED = "remind_fired"

    /** Worker 被推迟多久以内仍补发（分钟）；超过就当这一轮错过，不打扰 */
    private const val CATCHUP_MIN = 120
    private const val DAY_MIN = 1440
    private const val DAY_MS = 24L * 3600_000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 配置读写 ----------------

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        Log.i(TAG, "定时提醒 = $on")
        RemindScheduler.apply(ctx)
    }

    /** 窗口（分钟数，0..1439）；起止相等视作全天，避免零长窗口把所有时刻都滤掉 */
    fun window(ctx: Context): Pair<Int, Int> {
        val s = sp(ctx).getInt(KEY_START, 0)
        val e = sp(ctx).getInt(KEY_END, DAY_MIN - 1)
        return if (s == e) Pair(0, DAY_MIN - 1) else Pair(s, e)
    }

    fun setWindow(ctx: Context, start: Int, end: Int) {
        val s = start.coerceIn(0, DAY_MIN - 1)
        val e = end.coerceIn(0, DAY_MIN - 1)
        sp(ctx).edit().putInt(KEY_START, s).putInt(KEY_END, e).apply()
        Log.i(TAG, "提醒窗口 = ${fmt(s)}–${fmt(e)}")
        RemindScheduler.apply(ctx)
    }

    /** 用户指定的提醒时刻（原始列表，未按窗口过滤） */
    fun times(ctx: Context): List<Int> {
        val raw = sp(ctx).getString(KEY_TIMES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until DAY_MIN }.distinct().sorted()
    }

    fun setTimes(ctx: Context, list: List<Int>) {
        val clean = list.filter { it in 0 until DAY_MIN }.distinct().sorted().take(MAX_SLOTS)
        sp(ctx).edit().putString(KEY_TIMES, clean.joinToString(",")).apply()
        Log.i(TAG, "提醒时刻 = " + clean.joinToString("、") { fmt(it) })
        RemindScheduler.apply(ctx)
    }

    /** 返回 false = 已满 6 个或该时刻已存在 */
    fun addTime(ctx: Context, minute: Int): Boolean {
        val cur = times(ctx).toMutableList()
        if (cur.size >= MAX_SLOTS || cur.contains(minute)) return false
        cur.add(minute)
        setTimes(ctx, cur)
        return true
    }

    fun removeTime(ctx: Context, minute: Int) {
        setTimes(ctx, times(ctx) - minute)
    }

    /** 「按间隔一键铺点」：只写进时刻列表，不引入第二条自动规则 */
    fun fillByInterval(ctx: Context, intervalMin: Int): Int {
        if (intervalMin <= 0) return 0
        val (s, e) = window(ctx)
        val len = windowLength(s, e)
        val out = ArrayList<Int>()
        var off = 0
        while (off <= len && out.size < MAX_SLOTS) {
            out.add((s + off) % DAY_MIN)
            off += intervalMin
        }
        setTimes(ctx, out)
        return out.size
    }

    // ---------------- 时刻计算 ----------------

    private fun windowLength(s: Int, e: Int): Int =
        if (s <= e) e - s else DAY_MIN - s + e

    private fun offsetOf(minute: Int, s: Int): Int = (minute - s + DAY_MIN) % DAY_MIN

    /** 落在窗口内、去重、按窗口内先后排序、封顶 6 个的提醒时刻 */
    fun slots(ctx: Context): List<Int> {
        val (s, e) = window(ctx)
        val len = windowLength(s, e)
        return times(ctx)
            .filter { offsetOf(it, s) <= len }
            .sortedBy { offsetOf(it, s) }
            .take(MAX_SLOTS)
    }

    private fun dayKey(ts: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(ts))

    /** 跨天窗口里，凌晨 0..end 属于「前一天开始的那个提醒日」，共用一个 key */
    private fun windowKey(ctx: Context, now: Long): String {
        val (s, e) = window(ctx)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val mod = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val base = if (s > e && mod <= e) now - DAY_MS else now
        return dayKey(base)
    }

    private fun firedSet(ctx: Context, key: String): Set<String> {
        val raw = sp(ctx).getString(KEY_FIRED, "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(';')
            .filter { it.startsWith("$key|") }
            .map { it.substringAfter('|') }
            .toSet()
    }

    /** 只保留当前提醒日的记录 ⇒ 跨天自动清理，不会无限堆积 */
    fun markFired(ctx: Context, slot: Int, now: Long = System.currentTimeMillis()) {
        val key = windowKey(ctx, now)
        val cur = sp(ctx).getString(KEY_FIRED, "").orEmpty()
            .split(';').filter { it.isNotBlank() && it.startsWith("$key|") }.toMutableList()
        val entry = "$key|$slot"
        if (!cur.contains(entry)) cur.add(entry)
        sp(ctx).edit().putString(KEY_FIRED, cur.joinToString(";")).apply()
    }

    /**
     * 这一轮 Worker 该不该发、发哪个时刻。
     * 取「已到点且未发过」里**最近**的一个；更早的过期点静默跳过，避免一次补发多条。
     */
    fun dueSlot(ctx: Context, now: Long = System.currentTimeMillis()): Int? {
        if (!enabled(ctx)) return null
        val (s, e) = window(ctx)
        val len = windowLength(s, e)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val mod = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val off = offsetOf(mod, s)
        if (off > len) return null
        val fired = firedSet(ctx, windowKey(ctx, now))
        var due: Int? = null
        for (slot in slots(ctx)) {
            val elapsed = off - offsetOf(slot, s)
            if (elapsed in 0..CATCHUP_MIN && !fired.contains(slot.toString())) due = slot
        }
        return due
    }

    /** 自检 / 设置页显示用：09:05（手工补零，避开 String.format 的重载解析差异） */
    fun fmt(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    /** L3 自检：把当前配置算成人话，便于自检页一眼看出对不对 */
    fun describe(ctx: Context): String {
        val (s, e) = window(ctx)
        val list = slots(ctx)
        return "开关=${if (enabled(ctx)) "开" else "关"} · 窗口 ${fmt(s)}–${fmt(e)} · " +
                "时刻 ${if (list.isEmpty()) "未设置" else list.joinToString("、") { fmt(it) }} · " +
                "${list.size}/$MAX_SLOTS 次"
    }
}

/** 汇总提醒通知：独立渠道、固定 id 覆盖式、点击直达取件码页 */
object RemindNotifier {

    const val CHANNEL = "idcode_pickup_remind"
    private const val TAG = "RemindNotifier"
    private const val NOTI_ID = 0x524D44

    /** @return 实际提醒的件数；0 = 没发（无待取件 / 无权限 / 失败） */
    fun notifyPending(ctx: Context): Int {
        val list = PickupGate.aliveEntries(ctx)
        if (list.isEmpty()) {
            Log.i(TAG, "当前无待取件码 ⇒ 不提醒")
            return 0
        }
        if (!TrackNotifier.canNotify(ctx)) {
            Log.i(TAG, "没有通知权限 ⇒ 跳过（不弹窗、不报错）")
            return 0
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return 0
        ensureChannel(nm)
        val brief = list.take(3).joinToString("、") { it.second.code }
        val more = if (list.size > 3) " 等 ${list.size} 件" else ""
        val station = list.firstOrNull()?.second?.station.orEmpty()
        val full = buildString {
            append("待取 ${list.size} 件：$brief$more")
            if (station.isNotBlank()) append("\n取件地点：$station")
            append("\n点此打开取件码页")
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("还有 ${list.size} 件快递待取")
            .setContentText("$brief$more")
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setContentIntent(openPickup(ctx))
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(NOTI_ID, n)
            Log.i(TAG, "已提醒 ${list.size} 件待取")
            list.size
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
            0
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "取件提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在你设定的时间点提醒还有快递未取"
            }
        )
    }

    private fun openPickup(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(HomeWidgets.EXTRA_OPEN_PICKUP, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** 每 15 分钟醒一次，按配置判断到点没有；不做网络请求，只读本地缓存 */
class RemindWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        val c = applicationContext
        val slot = PickupReminder.dueSlot(c)
        if (slot != null) {
            val n = RemindNotifier.notifyPending(c)
            // 无论有没有真的发出（可能 0 件待取），都记住这个时刻已处理，避免反复空跑
            PickupReminder.markFired(c, slot)
            Log.i("RemindWorker", "命中 ${PickupReminder.fmt(slot)}，待取 $n 件")
        }
        Result.success()
    } catch (t: Throwable) {
        SafeRun.w("RemindWorker", t)
        Result.success()
    }
}

/** 排程：与后台轮询解耦 —— 轮询关掉，提醒照样工作 */
object RemindScheduler {

    private const val TAG = "RemindScheduler"
    private const val WORK = "idcode_pickup_remind"

    fun apply(ctx: Context) {
        val wm = try {
            WorkManager.getInstance(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager 不可用：${t.message}")
            return
        }
        val slots = PickupReminder.slots(ctx)
        if (!PickupReminder.enabled(ctx) || slots.isEmpty()) {
            wm.cancelUniqueWork(WORK)
            Log.i(TAG, "提醒关闭或无有效时刻 ⇒ 撤销排程")
            return
        }
        val req = PeriodicWorkRequestBuilder<RemindWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        Log.i(TAG, "已排程：15 分钟检查一次，共 ${slots.size} 个提醒时刻")
    }

    /** 设置页「立即试一条」/ debug 入口 */
    fun testNow(ctx: Context): Int = RemindNotifier.notifyPending(ctx)
}
