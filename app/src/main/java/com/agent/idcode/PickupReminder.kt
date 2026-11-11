package com.agent.idcode

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * ## v1.4.25：为什么「到点不响」
 * 上一版只有一条触发链：`WorkManager` 15 分钟周期任务。实测 ColorOS 上周期任务会被
 * 合并/推迟（工程 [KeepAlive] 里已有取证），而且 `ExistingPeriodicWorkPolicy.UPDATE`
 * 每改一次配置就把下次运行时间重置到 +15 分钟 ⇒ 用户设的时刻到了，Worker 还没醒。
 * 现在统一成 [tick] 一个出口，四条唤醒路径共用同一份判定与「当天该时刻只发一次」的账本：
 *  ① [RemindScheduler.armNext] 用 `setAlarmClock` 对每个时刻排**准时**闹钟（状态栏闹钟图标是它的代价）；
 *  ② [KeepAliveReceiver] / [TrackWorker] 任意一次现有后台唤醒顺带查一次；
 *  ③ 用户回前台（MainActivity.onResume）立刻补一次；
 *  ④ WorkManager 15 分钟周期继续兜底（[ExistingPeriodicWorkPolicy.KEEP] 不再被配置改动重置）。
 * 每条路径都可能被系统延迟，但 [CATCHUP_MIN] 容差内任意一条醒来就补发，超过容差安静跳过。
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

    /**
     * ★ v1.4.26：「到点用用户闹钟通道（setAlarmClock）」开关，默认**开**。
     *
     * [KeepAlive] 的兜底闹钟默认**不用** setAlarmClock（`debug_alarm_clock=false`），
     * 因为轮询不是用户设定的时刻，不该常驻一个状态栏闹钟图标；而提醒是用户亲手选的时刻，
     * 语义上就该像闹钟，且 ColorOS 上它是唯一实测不被推迟 72h 的准时通道。
     * 两者口径本来就不同 —— 但必须**各自可见、各自可控**，所以不再暗箱：给它一个开关。
     */
    private const val KEY_ALARM_CLOCK = "remind_alarm_clock"

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
        if (!on) sp(ctx).edit().remove(KEY_FIRED).apply()
        RemindScheduler.apply(ctx)
    }

    /** 到点是否走「用户闹钟」通道（默认开：最准时，代价是状态栏闹钟图标） */
    fun alarmClock(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ALARM_CLOCK, true)

    fun setAlarmClock(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ALARM_CLOCK, on).apply()
        Log.i(TAG, "提醒闹钟通道 = $on")
        RemindScheduler.armNext(ctx)
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
     * 这一轮该不该发、发哪个时刻。
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

    /**
     * ★ v1.4.25：下一个该响的绝对时间（给准时闹钟用）。
     * 只看未来 3 天里最早的、该提醒日还没发过的 slot；今天全发完就排明天最早那个。
     */
    fun nextSlotAt(ctx: Context, now: Long = System.currentTimeMillis()): Long? {
        if (!enabled(ctx)) return null
        val list = slots(ctx)
        if (list.isEmpty()) return null
        var best: Long? = null
        for (slot in list) {
            for (d in 0..2) {
                val c = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, slot / 60)
                    set(Calendar.MINUTE, slot % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, d)
                }
                val at = c.timeInMillis
                if (at <= now + 30_000L) continue
                if (firedSet(ctx, windowKey(ctx, at)).contains(slot.toString())) continue
                val b = best
                if (b == null || at < b) best = at
            }
        }
        return best
    }

    /**
     * ★★ v1.4.25：**所有唤醒路径的唯一出口**。
     * 到点 → 发一次 → 立刻记账；同一进程内多条路径同时到达也只会发一次（[dueSlot] 读的
     * 是同一份 SP 账本，@Synchronized 防并发）。返回实际提醒的件数。
     */
    @Synchronized
    fun tick(ctx: Context, reason: String): Int {
        // ★ v1.4.28：**每次 tick 都留一行诊断**。原来什么也没命中就静默返回，
        //   真机上只能看到"闹钟到点了但没响"，完全无法判断卡在哪一步。
        val slotDbg = dueSlot(ctx)
        Log.i(
            TAG,
            "[$reason] tick：enabled=${enabled(ctx)} 窗口=${fmt(window(ctx).first)}–" +
                    fmt(window(ctx).second) + " slots=${slots(ctx)} due=$slotDbg"
        )
        if (!enabled(ctx)) return 0
        val slot = slotDbg ?: return 0
        val n = try {
            RemindNotifier.notifyPending(ctx)
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
            0
        }
        // 无论有没有真的发出（可能 0 件待取），都记住这个时刻已处理，避免反复空跑
        markFired(ctx, slot)
        Log.i(TAG, "[$reason] 命中 ${fmt(slot)} ⇒ ${RemindDelivery.modeText(ctx)}，待取 $n 件")
        return n
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
                "${list.size}/$MAX_SLOTS 次 · 闹钟通道=${if (alarmClock(ctx)) "开" else "关"}"
    }
}

/**
 * ★ v1.4.25：到点提醒的**渠道分流**（同志 00:11 定）。
 * 「开着流体云 / 超级岛、且设备确实支持」⇒ 只推实况位，不再发普通通知；
 * 任何一路推失败 / 设备不支持 ⇒ 回退普通通知，绝不因为渠道判断把提醒弄丢。
 */
object RemindDelivery {

    private const val TAG = "RemindDelivery"

    /** 官方提升型通知（一加 ColorOS 流体云 / Android 16 promoted notification） */
    fun liveCapable(ctx: Context): Boolean = try {
        LiveUpdate.isEnabled(ctx) && LiveUpdate.canPostPromoted(ctx)
    } catch (_: Throwable) {
        false
    }

    /** 小米 HyperOS 焦点通知（超级岛） */
    fun islandCapable(ctx: Context): Boolean = try {
        IslandHelper.isEnabled(ctx) && IslandHelper.deviceAllowsIsland(ctx)
    } catch (_: Throwable) {
        false
    }

    fun capable(ctx: Context): Boolean = liveCapable(ctx) || islandCapable(ctx)

    /** 设置页 / 日志里给人看的一句话（回退时**必须说明原因**，见 [fallbackReason]） */
    fun modeText(ctx: Context): String = when {
        liveCapable(ctx) && islandCapable(ctx) -> "流体云 + 超级岛"
        liveCapable(ctx) -> "流体云实况位"
        islandCapable(ctx) -> "超级岛"
        else -> "普通通知（" + fallbackReason(ctx) + "）"
    }

    /**
     * ★ v1.4.26：为什么回退普通通知 —— 不能静默降级。
     *
     * [LiveUpdate.canPostPromoted] 只在 `SDK_INT >= 36` 才可能为真（提升型通知是 Android 16
     * 的 API），低于该版本的设备一律 false。原来设置页只显示「普通通知」，
     * 用户分不清是系统版本不够、开关没开、还是没授权。这里把三种原因分开讲清楚。
     */
    fun fallbackReason(ctx: Context): String {
        val liveWanted = try { LiveUpdate.isEnabled(ctx) } catch (_: Throwable) { false }
        val islandWanted = try { IslandHelper.isEnabled(ctx) } catch (_: Throwable) { false }
        if (!liveWanted && !islandWanted) return "流体云/超级岛都没开"
        if (liveWanted && Build.VERSION.SDK_INT < 36) return "系统低于 Android 16，无提升型通知"
        if (liveWanted) return "系统未授权提升型通知"
        return "本机不支持超级岛"
    }

    /** @return 成功推上实况位的件数；0 = 没推成（调用方回退普通通知） */
    fun push(ctx: Context, list: List<Pair<String, PddCodeStore.Cached>>): Int {
        var ok = 0
        if (liveCapable(ctx)) {
            val entries = list.map { (k, c) ->
                // ★ v1.4.26：承运商统一走 srcLabel（与 PickupPublish / syncFromSnapshot 同口径）
                LiveUpdate.Entry(
                    PickupGate.noOf(k, c).ifBlank { c.code }, c.code, c.station,
                    PickupGate.srcLabel(c.src)
                )
            }
            val n = try {
                LiveUpdate.pulse(ctx, entries, remind = true)
            } catch (t: Throwable) {
                SafeRun.w(TAG, t)
                0
            }
            if (n > 0) ok = n
        }
        if (islandCapable(ctx)) {
            val entries = list.map { (k, c) ->
                IslandHelper.Entry(
                    PickupGate.noOf(k, c).ifBlank { c.code }, c.code, c.station,
                    PickupGate.srcLabel(c.src)
                )
            }
            try {
                // IslandHelper.sync 每次都重发（publishOne 保温两连发），到点即重挂岛
                IslandHelper.sync(ctx, entries)
                ok = maxOf(ok, entries.size)
            } catch (t: Throwable) {
                SafeRun.w(TAG, t)
            }
        }
        return ok
    }
}

/** 汇总提醒通知：独立渠道、固定 id 覆盖式、点击直达取件码页；实况位可用时让位 */
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

        // ★ v1.4.25：岛/流体云优先 —— 成功了就不发普通通知
        if (RemindDelivery.capable(ctx)) {
            val n = try {
                RemindDelivery.push(ctx, list)
            } catch (t: Throwable) {
                SafeRun.w(TAG, t)
                0
            }
            if (n > 0) {
                Log.i(TAG, "已走 ${RemindDelivery.modeText(ctx)} 提醒 ${list.size} 件 ⇒ 不发普通通知")
                return list.size
            }
            Log.w(TAG, "实况位没推成 ⇒ 回退普通通知（不丢提醒）")
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
            Log.i(TAG, "已提醒 ${list.size} 件待取（普通通知）")
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
        PickupReminder.tick(applicationContext, "worker")
        Result.success()
    } catch (t: Throwable) {
        SafeRun.w("RemindWorker", t)
        Result.success()
    }
}

/** 精确闹钟到点：只做一次 tick，再把下一个时刻排上（自续期） */
class RemindAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val c = ctx.applicationContext
        Log.i("RemindAlarm", "准时闹钟到点：${intent.action}")
        try {
            PickupReminder.tick(c, "alarm")
        } catch (t: Throwable) {
            SafeRun.w("RemindAlarm", t)
        }
        try {
            RemindScheduler.armNext(c)
        } catch (t: Throwable) {
            SafeRun.w("RemindAlarm", t)
        }
    }
}

/** 排程：与后台轮询解耦 —— 轮询关掉，提醒照样工作 */
object RemindScheduler {

    private const val TAG = "RemindScheduler"
    private const val WORK = "idcode_pickup_remind"
    const val ACTION_REMIND = "com.agent.idcode.action.REMIND_TICK"
    private const val REQ = 0x524D

    /** ★ v1.4.28：进程内定时器（见 [armInProcess]），必须在主线程 */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun alarmPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ,
        Intent(ctx, RemindAlarmReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, REQ + 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun apply(ctx: Context) {
        val wm = try {
            WorkManager.getInstance(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager 不可用：${t.message}")
            armNext(ctx)   // 精确闹钟不依赖 WorkManager
            return
        }
        val slots = PickupReminder.slots(ctx)
        if (!PickupReminder.enabled(ctx) || slots.isEmpty()) {
            wm.cancelUniqueWork(WORK)
            cancelAlarm(ctx)
            Log.i(TAG, "提醒关闭或无有效时刻 ⇒ 撤销排程")
            return
        }
        // ★ v1.4.25：KEEP 而非 UPDATE —— 改一个时刻不该把周期任务的下次运行重置到 +15 分钟
        val req = PeriodicWorkRequestBuilder<RemindWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        Log.i(TAG, "已排程：15 分钟兜底一次，共 ${slots.size} 个提醒时刻")
        armNext(ctx)
        armInProcess(ctx)
    }

    /**
     * ★★ v1.4.28：**进程内定时器** —— ColorOS 上唯一稳的通道。
     *
     * 真机实证（2026-11-11 14:23，PJZ110/ColorOS）：
     *   `OplusAppStartupManager: prevent start com.agent.idcode, cmp …RemindAlarmReceiver
     *    by broadcast system[alarmManger]` —— 系统的「应用启动管理」**直接拦掉了闹钟广播**，
     *   闹钟到点了，接收器根本没跑起来。这就是「手动测试能发、到点不发」的根因。
     *
     * 只要进程还活着，主线程 Handler 到点就能精确触发，完全不经过广播，系统拦不着。
     * 进程被清掉时这条通道自然失效 —— 那时靠闹钟（可能被拦）+ 回前台的 120 分钟补发兜底。
     */
    fun armInProcess(ctx: Context) {
        try {
            uiHandler.removeCallbacksAndMessages(null)
            val at = PickupReminder.nextSlotAt(ctx) ?: return
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L)
            val app = ctx.applicationContext
            uiHandler.postDelayed({
                Log.i(TAG, "进程内定时器到点（已等 ${delay}ms）")
                try {
                    PickupReminder.tick(app, "inproc")
                } catch (t: Throwable) {
                    SafeRun.w(TAG, t)
                }
                armInProcess(app)
            }, delay + 300L)
            Log.i(TAG, "进程内定时器已排：${delay}ms 后")
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
    }

    /** 下一个提醒时刻排一个「用户闹钟」通道 —— ColorOS 上唯一实测不被推迟 72h 的准时通道 */
    fun armNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = PickupReminder.nextSlotAt(ctx)
        if (at == null) {
            cancelAlarm(ctx)
            return
        }
        val atStr = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
        // ★ v1.4.26：闹钟通道做成开关（见 PickupReminder.KEY_ALARM_CLOCK）。
        //   与 KeepAlive 的口径差异不再暗箱：轮询不该占闹钟图标，用户亲手设的提醒该像闹钟。
        if (PickupReminder.alarmClock(ctx)) {
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), alarmPi(ctx))
                Log.i(TAG, "准时闹钟（用户闹钟通道）→ $atStr")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "setAlarmClock 失败，退回精确闹钟：${t.message}")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(ctx))
                Log.i(TAG, "精确闹钟（未走闹钟通道）→ $atStr")
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(ctx))
                Log.i(TAG, "非精确闹钟（无精确权限 / 已关闹钟通道）→ $atStr")
            }
        } catch (t2: Throwable) {
            SafeRun.w(TAG, t2)
        }
    }

    private fun cancelAlarm(ctx: Context) {
        try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(alarmPi(ctx))
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
        Log.i(TAG, "准时闹钟已撤销")
    }

    /** 设置页「立即试一条」/ debug 入口 —— 走的是与到点完全相同的渠道分流 */
    fun testNow(ctx: Context): Int = RemindNotifier.notifyPending(ctx)

    /**
     * ★★ v1.4.28：**自愈排程**。
     *
     * ColorOS 会撤掉不活跃 App 的闹钟（工程里到处都有这条实证）。原来只有
     * 「改配置」才会重排一次，于是闹钟被系统吞掉之后，用户设的时刻就再也不会响
     * —— 而「立即试一条」是直接发通知，看起来一切正常（同志报的 bug 4 就是这个形态）。
     *
     * 现在每次回前台都幂等地重排一次：已有就用同一个 PendingIntent 覆盖，没有就补上。
     */
    fun ensureArmed(ctx: Context) {
        try {
            if (!PickupReminder.enabled(ctx)) return
            val slots = PickupReminder.slots(ctx)
            if (slots.isEmpty()) {
                Log.i(TAG, "提醒已开但没有任何时刻 ⇒ 不排程（设置页会提示先加时刻）")
                return
            }
            armNext(ctx)
            armInProcess(ctx)
            Log.i(TAG, "自愈排程：${slots.size} 个时刻，下一个 " +
                    (PickupReminder.nextSlotAt(ctx)?.let {
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))
                    } ?: "（无）"))
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
    }
}
