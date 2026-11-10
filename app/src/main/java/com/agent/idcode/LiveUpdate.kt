package com.agent.idcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import org.json.JSONArray

/**
 * OPPO 流体云 / Android 16 提升型通知（「实时更新」）—— 把取件码挂到系统的实况位上。
 *
 * ## 机理：这不是厂商私有 SDK，就是 Android 16 的标准能力
 * Android 16（API 36）引入「提升型常驻通知 / Live Update」：满足条件的通知会被系统提到
 * **通知抽屉顶部、锁屏、以及状态栏芯片**上。ColorOS 的「流体云」承载的就是它 ——
 * ⇒ **不需要任何 OPPO SDK、不需要推送服务**，把通知按 Android 16 的规则发出来就行。
 *
 * ★ 实证依据：参考软件「南风天气」(`com.skypulse.weather`) 的 dex 里正好有
 * `FluidCloudNotifier` / `ColorOS` / `fluid_cloud_state` / `Flyme` 这几个字符串，
 * 配合 `android.requestPromotedOngoing`、`android.shortCriticalText`，
 * 且 manifest 声明了 `POST_PROMOTED_NOTIFICATIONS` —— 它就是纯 Android 16 实现，
 * 一套代码同时吃到 ColorOS 流体云和 Flyme 灵动岛。
 *
 * ## 官方硬性条件（缺一条就不会被提升，下面逐条对着做）
 * 1. manifest 声明 `android.permission.POST_PROMOTED_NOTIFICATIONS`（**非**运行时权限）
 * 2. 用 `EXTRA_REQUEST_PROMOTED_ONGOING`（= `android.requestPromotedOngoing`）请求提升
 * 3. 必须 **ongoing**（`setOngoing(true)` / `FLAG_ONGOING_EVENT`）
 * 4. 必须设置 `contentTitle`
 * 5. 样式只能是 `BigTextStyle` / `CallStyle` / `ProgressStyle` / `MetricStyle`
 * 6. **不得**设自定义 `customContentView`（不能有 RemoteViews）
 * 7. **不得**是 group summary（不调 `setGroupSummary`）
 * 8. **不得** `setColorized(true)`（`setColor` 可以，两者不等价）
 * 9. 渠道重要性不能是 `IMPORTANCE_MIN`
 * 10. `setWhen` 要给**未来**时间戳
 *
 * ## ★ 为什么用 extras 直塞，而不是 `setRequestPromotedOngoing()`
 * 本项目 `compileSdk = 35`（本机只装了 `android-35` platform），而
 * `setRequestPromotedOngoing` / `ProgressStyle` 是 **API 36** 的符号 ⇒ 编译期根本拿不到。
 * 但这套东西**本质就是通知 extras**（平台实现里 `setRequestPromotedOngoing(b)` 无非是
 * `mN.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, b)`）⇒ 直接 `addExtras()` 塞进去
 * 效果完全一致，而且**不用升 compileSdk、不用动构建、不影响任何现有行为**
 * （老设备读不懂这个 extra，系统直接忽略，无害）。
 *
 * 查询能力（`canPostPromotedNotifications` / `hasPromotableCharacteristics`）同理，
 * 用反射调，**包 try/catch 并默认 false** —— 能力探测本身绝不能成为崩溃点。
 */
object LiveUpdate {

    private const val TAG = "LiveUpdate"

    /** 取件码专用渠道（与物流动态的 `idcode_tracking` 分开，用户可以单独关） */
    const val CHANNEL = "idcode_pickup_live"

    /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`（API 36 常量，手写以免依赖编译期符号） */
    private const val EXTRA_PROMOTED = "android.requestPromotedOngoing"

    /** `Notification.EXTRA_SHORT_CRITICAL_TEXT` —— 状态栏芯片上的极短文案 */
    private const val EXTRA_SHORT_CRITICAL = "android.shortCriticalText"

    /** ColorOS 上引导用户开启「提升型通知」的设置页 action */
    private const val ACTION_PROMOTED_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

    /** 官方 `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`（部分 ROM 用这个） */
    private const val ACTION_MANAGE_PROMOTED = "android.settings.APP_NOTIFICATION_PROMOTED_SETTINGS"

    private const val PREF = "idcode_prefs"
    /** ★ vc704：状态栏芯片的字符预算 —— ColorOS 流体云实测约 10 个半角字符宽（与 1.3 同口径） */
    private const val SHORT_TEXT_MAX = 10
    /** ★ vc704：短文案规则的**版本号**。规则一改就 +1，并在 [migrateShortRules] 里补一段迁移 */
    private const val KEY_SHORT_VER = "live_short_rule_ver"
    private const val SHORT_VER = 2
    private const val KEY_POSTED = "live_posted_nos"
    /** ★ vc467：内容指纹（id → code|station|company 的 hash），**内容没变就不重发** */
    private const val KEY_FP = "live_fp"
    /** ★ vc467：用户划掉/点掉的记录（id → ts），内容没变就不重挂 */
    private const val KEY_DISMISS = "live_dismissed"
    /** ★ vc468：每个 id 上次真正发布的时刻（同码 10 分钟内不重发，防抖动） */
    private const val KEY_TS = "live_post_ts"
    const val ACTION_DISMISS = "com.agent.idcode.LIVE_DISMISS"

/**
 * App 侧总开关：用户**想不想**把取件码挂到流体云（默认开）。
 *
 * ★★ 它和系统那层「提升型通知」权限是**两件事**（2026-09-22 同志定的口径）：
 *    · 这个开关 = 我想不想走这条路；
 *    · 系统权限   = 这条路在不在。
 *    所以**关掉它只做两件事：不上报 + 把已发的撤下**，
 *    **绝不碰系统权限、绝不跳系统设置**（以前拨开关会跳系统设置，那是错的）。
 */
private const val KEY_ENABLED = "live_enabled"

/** 用户是否启用流体云（App 侧意愿，默认开） */
fun isEnabled(ctx: Context): Boolean =
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

/**
 * 设置开关并**立刻生效**：
 *   开 ⇒ 按持久化快照补推一次；关 ⇒ 全部撤下（[cancelAll]）。
 * 只动自家状态，不碰系统设置。
 */
fun setEnabled(ctx: Context, on: Boolean) {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_ENABLED, on).apply()
    if (on) syncFromSnapshot(ctx) else cancelAll(ctx)
}

    /** 「已到达/待取」的判定词（与 [ExpressStore] 的 W_ARRIVED 同口径，这里只用来选卡片文案） */
    private val ARRIVED_WORDS = listOf(
        "待取", "取件", "入柜", "入站", "驿站", "快递柜", "自提", "已存放",
        "已放至", "取件码", "柜机", "代收点", "已到店", "请前往", "领取", "已代收"
    )

    /**
     * 「已到达取件点」判定 —— 与 [ExpressStore.stageOf] 的 ARRIVED 同一套词表。
     * 只有到了驿站/快递柜才值得占实况位（在途件既没有取件码，也不该占着流体云）。
     */
    fun isArrived(text: String): Boolean = ARRIVED_WORDS.any { text.contains(it) }

    // ---------------- 渠道 ----------------

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // ★ 条件 9：不能是 IMPORTANCE_MIN。取件码要显眼，用 DEFAULT。
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "取件码（流体云）", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "快递到达驿站后，把取件码常驻显示在系统实况位（流体云 / 状态栏）"
                setShowBadge(true)
                enableVibration(false)
            }
        )
    }

    // ---------------- 能力探测（全部反射 + 兜底，绝不抛） ----------------

    /** `NotificationManager.canPostPromotedNotifications()` —— 设备/用户是否允许提升（API 36） */
    fun canPostPromoted(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 36) {
            false
        } else {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            val r = nm?.javaClass?.getMethod("canPostPromotedNotifications")?.invoke(nm)
            r as? Boolean ?: false
        }
    } catch (e: Throwable) {
        Log.i(TAG, "canPostPromotedNotifications 不可用: ${e.message}")
        false
    }

    /** `Notification.hasPromotableCharacteristics()` —— 这条通知本身够不够格被提升（API 36） */
    private fun promotableCharacteristics(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT < 36) false
        else n.javaClass.getMethod("hasPromotableCharacteristics").invoke(n) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    /** 跳到系统「提升型通知」开关页；跳不过去就退到本应用通知设置 */
    fun openPromotedSettings(ctx: Context): Boolean {
        for (a in listOf(ACTION_PROMOTED_SETTINGS, ACTION_MANAGE_PROMOTED)) {
            try {
                ctx.startActivity(Intent(a).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("android.provider.extra.APP_PACKAGE", ctx.packageName)
                })
                return true
            } catch (t: Throwable) { SafeRun.w("LiveUpdate", t) }
        }
        // 兜底：应用详情页的通知设置（所有 ROM 都有）
        return try {
            ctx.startActivity(
                Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("android.provider.extra.APP_PACKAGE", ctx.packageName)
                }
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------- 发 / 取消 ----------------

    /** 一条取件码实况位需要的信息 */
    data class Entry(
        val no: String,
        val code: String,
        val station: String = "",
        val company: String = ""
    )

    /**
     * ★★ v1.4.25：**到点提醒专用的「重推」**。
     *
     * [sync] 是全量同步，码集合没变时会被指纹挡住（6 小时不重发）——常驻流体云本来
     * 就不该反复弹。但「定时提醒到点」是一次明确的事件，用户要看到它重新亮一下，
     * 所以这里绕过指纹、先撤后发，并把标题换成「取件提醒 · …」。
     *
     * @return 成功重推的条数；0 = 没推成（调用方据此回退普通通知）
     */
    fun pulse(ctx: Context, entries: List<Entry>, remind: Boolean = true): Int {
        if (!isEnabled(ctx)) return 0
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return 0
        val want = entries
            .filter { it.no.isNotBlank() && it.code.isNotBlank() }
            .distinctBy { it.code }
            .filter { e ->
                try {
                    MuteStore.blocks(ctx, "", "", e.no, e.code, e.station) == null
                } catch (_: Throwable) {
                    true
                }
            }
            .take(6)
        if (want.isEmpty()) return 0
        val id = idOf("all")
        val now = System.currentTimeMillis()
        return try {
            nm.cancel(id)
            nm.notify(id, buildCluster(ctx, want, remind))
            val fpMap = loadFp(ctx).toMutableMap()
            val tsMap = loadTs(ctx).toMutableMap()
            val posted = postedIds(ctx).toMutableSet()
            val disMap = loadDismissed(ctx).toMutableMap()
            fpMap[id] = want.map { it.code }.sorted().joinToString("§")
            tsMap[id] = now
            posted.add(id)
            disMap.remove(id)   // 用户划掉过也允许「到点提醒」重新亮一次
            saveFp(ctx, fpMap)
            saveTs(ctx, tsMap)
            savePosted(ctx, posted)
            saveDismissed(ctx, disMap)
            Log.i(TAG, "到点重推流体云：${want.size} 条（提醒态=$remind）")
            want.size
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
            0
        }
    }

    /**
     * 同步实况位：该显示的显示、该更新的更新、**消失的取消**。
     *
     * ★ 为什么要有「同步」而不是「单发」：取件码会因为「已取出」而消失
     * （见 MainActivity.syncPickupCodeCache 的消除逻辑），如果只发不撤，
     * 流体云上会挂着一个已经取走的码，比不显示还糟。
     *
     * @param entries 当前**所有**该上流体云的取件码（调用方给全量，不是增量）
     */
    fun sync(ctx: Context, entries: List<Entry>) {
        ensureChannel(ctx)
        migrateShortRules(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // ★★ vc699：**聚类** —— 未取件的取件码合并成**一条**通知（原来一个码一条）。
        //   同志要求：流体云 \/ 实况位上别铺一排小卡片，一个「待取 N 件」的聚合体更像回事；
        //   码本身在展开态（BigTextStyle）里逐行列出，一眼看全。
        val id = idOf("all")

        // App 侧总开关：关掉就把已发的全部撤下（下面 posted 分支自然会走到）
        if (!isEnabled(ctx)) {
            cancelAll(ctx)
            return
        }

        // 只上「没取走」的：有取件码、且不是终态。终端态由调用方过滤，这里再兜一道。
        // 按**取件码**去重（不是按单号）—— 同一个码会带两个不同单号（订单号 / 运单号口径）。
        //
        // ★★ v1.4.18：**最后一道闸 —— 名单里的码绝不上岛**。
        //   上游已经都走 [PickupGate.aliveEntries] 了，但流体云是用户天天盯着的门面，
        //   多一道按「码 / 运单号」的兜底值得：以后谁新加一条数据源忘了过 PickupGate，
        //   也不会把用户亲手删掉的码又摆回实况位。
        //   这里 `src` 传空串 —— MuteStore.srcCompatible 对空串放行（= 不限来源）。
        val want = entries
            .filter { it.no.isNotBlank() && it.code.isNotBlank() }
            .distinctBy { it.code }
            .filter { e ->
                val hit = try {
                    MuteStore.blocks(ctx, "", "", e.no, e.code, e.station)
                } catch (_: Throwable) {
                    null
                }
                if (hit != null) Log.i(TAG, "名单里的码不上岛：${e.code}（记录 ${hit.id}）")
                hit == null
            }
            .take(6)                     // 最多 6 条，跟桌面小组件一个量级

        val fpMap = loadFp(ctx).toMutableMap()
        val disMap = loadDismissed(ctx).toMutableMap()
        val tsMap = loadTs(ctx).toMutableMap()
        val posted = postedIds(ctx).toMutableSet()
        val now = System.currentTimeMillis()

        // ① 一条可取件都没有 ⇒ 撤下（★ vc472 口径不变：30 分钟宽限，防源抖动来回弹）
        if (want.isEmpty()) {
            if (posted.contains(id)) {
                val ts = tsMap[id] ?: 0L
                if (ts != 0L && now - ts < 30 * 60_000L) {
                    Log.i(TAG, "暂不撤下聚类实况位（发布未满 30 分钟，防抖动）")
                } else {
                    try {
                        nm.cancel(id)
                    } catch (t: Throwable) { SafeRun.w("LiveUpdate", t) }
                    posted.remove(id)
                    Log.i(TAG, "待取件已空 ⇒ 撤下聚类实况位")
                }
            }
            fpMap.remove(id)
            tsMap.remove(id)
            saveFp(ctx, fpMap)
            saveTs(ctx, tsMap)
            savePosted(ctx, posted)
            saveDismissed(ctx, disMap)
            return
        }

        // ② 指纹 = 全部码排序后拼接（码集合没变就不重发；驿站文案变化只在正文体现）
        val fp = want.map { it.code }.sorted().joinToString("§")
        val prev = fpMap[id]
        val changed = prev != fp
        val inCooldown = !changed && (now - (tsMap[id] ?: 0L) < 6 * 3600_000L)
        if (changed && disMap.remove(id) != null) {
            Log.i(TAG, "内容有变，恢复聚类实况位")
        }
        if (inCooldown) {
            Log.i(TAG, "同内容 6 小时内，跳过重发（${want.size} 个码）")
        } else if (!changed) {
            Log.i(TAG, "内容未变，跳过重发（${want.size} 个码）")
        } else if (!disMap.containsKey(id)) {
            try {
                nm.notify(id, buildCluster(ctx, want))
                fpMap[id] = fp
                tsMap[id] = now
                posted.add(id)
                Log.i(TAG, "聚类上岛 ${want.size} 条：" +
                        want.joinToString(" | ") { "${it.code}@${it.station.take(8)}" })
            } catch (ex: Throwable) {
                Log.w(TAG, "发聚类实况位失败: ${ex.message}")
            }
        }

        // ③ 清掉旧的「一码一条」残留 id（历史版本发出去的），否则升级后旧卡会一直挂着
        val legacy = posted.filter { it != id }
        for (old in legacy) {
            try {
                nm.cancel(old)
                Log.i(TAG, "撤下旧的单码实况位 id=$old")
            } catch (t: Throwable) { SafeRun.w("LiveUpdate", t) }
        }
        posted.removeAll(legacy)

        saveFp(ctx, fpMap)
        saveTs(ctx, tsMap)
        savePosted(ctx, posted)
        saveDismissed(ctx, disMap)
    }

    /**
     * ★ vc699：聚类通知的构造。
     *   1 个码 ⇒ 与旧版单码通知同款（标题「取件码 XX」）；
     *   多个 ⇒ 标题「待取快递 N 件」，正文逐行列「码 · 驿站 · 承运商」。
     *   状态栏芯片（shortCriticalText）只有约 8 个半角字符宽：单码给码本身，多码给「取件码N件」。
     */
    private fun buildCluster(ctx: Context, list: List<Entry>, remind: Boolean = false): Notification {
        val one = list.size == 1
        val title = when {
            remind && one -> "取件提醒 · 取件码 ${list[0].code}"
            remind -> "取件提醒 · 待取快递 ${list.size} 件"
            one -> "取件码 ${list[0].code}"
            else -> "待取快递 ${list.size} 件"
        }
        val body = list.mapIndexed { i, e ->
            buildString {
                if (!one) append(i + 1).append(". ")
                append(e.code)
                if (e.station.isNotBlank()) append(" · ").append(e.station.take(20))
                if (e.company.isNotBlank()) append(" · ").append(e.company)
            }
        }.joinToString("\n")
        val shortText = if (remind) "取件提醒" else if (one) shortOf(list[0].code) else "取件码${list.size}件"
        val extras = Bundle().apply {
            putBoolean(EXTRA_PROMOTED, true)
            putCharSequence(EXTRA_SHORT_CRITICAL, shortText)
        }
        val sub = if (one) list[0].station.take(24) else "共 ${list.size} 个取件码"
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .addExtras(extras)
            .setWhen(System.currentTimeMillis() + 6 * 60 * 60 * 1000L)
            .setShowWhen(true)
            .apply { if (sub.isNotBlank()) setSubText(sub) }
            .setOnlyAlertOnce(true)
            .setDeleteIntent(dismissPi(ctx, idOf_no = "all"))
            .setContentIntent(openApp(ctx))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
            .also {
                Log.i(TAG, "构造聚类实况位 ${list.size} 条 自检: promotable=${promotableCharacteristics(it)}")
            }
    }

    /**
     * ★★ vc704：短文案规则的**版本迁移**（照 1.3 的做法）。
     *
     * 为什么要它：短文案的排版规则、通知 id 口径一变，**存量状态就会指错对象** ——
     *  vc699 把「一个码一条通知」改成「聚类一条」后，老版本按单码 id 记的
     *  `live_posted_nos` / 指纹表就指向了已经不存在的 id：
     *   · 旧通知撤不掉 ⇒ 升级后流体云上会一直挂着几条僵尸卡；
     *   · 指纹对不上 ⇒ 同一个码会重新弹一次。
     * 迁移做法与 1.3 一致：读版本号 → 老版本就重写存量状态 → 写回新版本号（只跑一次）。
     */
    private fun migrateShortRules(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val ver = sp.getInt(KEY_SHORT_VER, 1)
            if (ver >= SHORT_VER) return
            val legacy = postedIds(ctx)
            val cluster = idOf("all")
            val keep = legacy.filter { it == cluster }.toSet()
            savePosted(ctx, keep)
            saveFp(ctx, emptyMap())
            saveTs(ctx, emptyMap())
            sp.edit().putInt(KEY_SHORT_VER, SHORT_VER).apply()
            Log.i(TAG, "短文案规则 v$ver → v$SHORT_VER：清掉 ${legacy.size - keep.size} 条旧单码记录（" +
                    "升级前是「一个码一条通知」，那些 id 已经不存在了）")
        } catch (t: Throwable) {
            SafeRun.w(TAG, t)
        }
    }

    /**
     * ★ vc704：状态栏芯片文案 —— 预算 [SHORT_TEXT_MAX] 个半角字符。
     *  1) 码本身就塞得下 ⇒ 原样（带分隔符更好读）
     *  2) 去分隔符后塞得下 ⇒ 去分隔符（`29-3-7011` → `2937011`）
     *  3) 还是超长 ⇒ **优先保尾号**（`...` 前缀略掉）—— 取件时对的是后几位，尾号最有辨识度
     */
    private fun shortOf(code: String): String {
        val compact = code.replace("-", "").replace(" ", "")
        return when {
            code.length <= SHORT_TEXT_MAX -> code
            compact.length <= SHORT_TEXT_MAX -> compact
            else -> compact.takeLast(SHORT_TEXT_MAX - 1)
        }
    }

    /**
     * ★ 从**持久化快照**补推一次（App 启动时调）。
     *
     * 为什么需要它：实况位的数据源不该只依赖"这一轮抓取成功"。
     * 实测遇到过——拼多多的登录态被服务端判失效（`pdd_login_ok=false`）后整个源被跳过，
     * 于是**已经抓到手的取件码**也一起从界面上消失了。而快照（[ExpressStore]）是持久化的、
     * 全量的，拿它兜一道，能保证"只要取件码曾经抓到过、且件还在驿站"，流体云上就一直挂着。
     */
    fun syncFromSnapshot(ctx: Context) {
        // ★★ v1.4.18：**数据源收敛到 [PddCodeStore] 一处**（经 [PickupGate] 统一过滤）。
        //
        //   旧实现是「ExpressStore 快照 ∩ PddCodeStore 的码」，有两个毛病：
        //   ① **不过黑名单** —— 「清已取 / 长按删除」掉的码只要被平台重新报成待取，
        //      就照样挂上流体云（同志 14:36 报的就是这个：流体云 3 件、实际 1 个码）；
        //   ② **与 [PickupPublish] 不同源** —— 那条链用整个 PddCodeStore，这条用快照交集，
        //      两者集合经常不一样（快照是合并语义、会留陈旧条目，也可能缺刚抓到的码）⇒
        //      同一时刻两次 sync 传进去的列表不同，指纹来回翻，通知内容反复重发/抖动。
        //      这就是"流体云并没有遵循某个规则"的另一半原因。
        //
        //   现在两条链都调 [PickupGate.aliveEntries]：有码 + 未取件 + 不在黑名单，单一口径。
        //   顺带修掉 [items.isEmpty() 就 return] 的老毛病 —— 空列表也要交给 sync()，
        //   它内部才会走「撤下」分支，否则码全删光时流体云会一直挂着一张僵尸卡。
        val entries = PickupGate.aliveEntries(ctx).map { (k, c) ->
            Entry(PickupGate.noOf(k, c), c.code, c.station, PickupGate.srcLabel(c.src))
        }
        Log.i(TAG, "从缓存补推 ${entries.size} 条：" +
                entries.joinToString(" | ") { "${it.code}@${it.station.take(6)}" })
        sync(ctx, entries)
    }

    /** 全部撤下（用户关掉功能 / 清空时用） */
    fun cancelAll(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ids = postedIds(ctx)
        for (id in ids) {
            try {
                nm.cancel(id)
            } catch (t: Throwable) { SafeRun.w("LiveUpdate", t) }
        }
        savePosted(ctx, emptySet())
        // 留个证据：设置里关掉流体云后，日志能直接看到"确实撤了 N 条"
        if (ids.isNotEmpty()) Log.i(TAG, "已撤下全部实况位 ${ids.size} 条（设置里关闭了流体云）")
    }

    private fun build(ctx: Context, e: Entry): Notification {
        // 状态栏芯片要极短：优先「取件码」四个字 + 数字太大放不下，所以只放码
        // ★ vc469：折叠态只有 ~8 个半角字符的宽度（左侧是系统图标位，放不了自定义文字）。
        //   以前 `take(8)` 会把 29-3-7011 截成 29-3-701 —— 现在**去分隔符后再塞**：
        //   29-3-7011 → 2937011（7 字符，完整保留数字）；标题/正文仍是带分隔符的原码。
        val compact = e.code.replace("-", "").replace(" ", "")
        val shortText = when {
            e.code.length <= 8 -> e.code
            compact.length <= 8 -> compact
            else -> compact.take(8)
        }
        val title = "取件码 ${e.code}"
        val body = buildString {
            // ★ vc345：流体云里也要有**取件地址**（同志 2026-10-20 要求）——
            //   以前 body 只是一串"驿站名 · 承运商"，地址为空时甚至只剩"已到驿站"，
            //   用户在实况位上只能看到码、不知道去哪个驿站。加显式标签，别让人猜。
            if (e.station.isNotBlank()) append("取件地址：").append(e.station)
            if (e.company.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(e.company)
            }
            if (isEmpty()) append("已到驿站，去取件")
        }
        val extras = Bundle().apply {
            // ★ 条件 2：请求提升（等价于 setRequestPromotedOngoing(true)）
            putBoolean(EXTRA_PROMOTED, true)
            // 状态栏芯片文案
            putCharSequence(EXTRA_SHORT_CRITICAL, shortText)
        }
        return Notification.Builder(ctx, CHANNEL)
            // ★ vc307：同志要求流体云左边的图标换成 **App 图标**（原来是系统日程本小图标
            //   android.R.drawable.ic_menu_agenda，看着与蜉蝣无关）。
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)                                  // ★ 条件 4
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))     // ★ 条件 5
            .setOngoing(true)                                        // ★ 条件 3
            .addExtras(extras)                                       // ★ 条件 2
            // ★ 条件 10：未来时间戳（系统据此显示"还有多久"而不是"多久以前"）
            .setWhen(System.currentTimeMillis() + 6 * 60 * 60 * 1000L)
            .setShowWhen(true)
            // ★ vc365：收起态也带一眼地址（完整地址仍在正文 body 里）
            .apply { if (e.station.isNotBlank()) setSubText(e.station.take(24)) }
            .setOnlyAlertOnce(true)                                  // 更新不反复响
            // ★ vc467：划掉（系统删除）也记一笔 —— 以前只认"点已完成"，划掉等于没记
            .setDeleteIntent(dismissPi(ctx, idOf_no = e.no))
            .setContentIntent(openApp(ctx))
            .setVisibility(Notification.VISIBILITY_PUBLIC)           // 锁屏可见（流体云也在锁屏）
            .build()
            .also {
                Log.i(
                    TAG,
                    "构造实况位 code=${e.code} 自检: promotable=${promotableCharacteristics(it)}"
                )
            }
    }

    /** 通知 id：必须和物流动态那条区分开（否则互相覆盖） */
    private fun idOf(no: String): Int = ("live:" + no).hashCode()

    // ---------------- vc467：指纹 / 划掉记录 ----------------

    /**
     * ★ vc471：指纹**只认码**（不再包含驿站/公司）。
     * 同志反馈：降频后仍"时不时弹"——根因是驿站文案会随轨迹更新（"待取件 11:23"→"12:05"…），
     * 指纹一变就重发 ⇒ 15 分钟一轮刚好错开 10 分钟冷却，看着像随机弹。
     * 现在：**只有取件码本身变了才算变化**；驿站文案变化只在卡片/通知正文里体现，不重发。
     */
    private fun fpOf(e: Entry): String = e.code

    private fun loadFp(ctx: Context): Map<Int, String> = try {
        val o = org.json.JSONObject(
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_FP, "{}") ?: "{}"
        )
        o.keys().asSequence().associate { it.toInt() to o.optString(it) }
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun saveFp(ctx: Context, m: Map<Int, String>) {
        val o = org.json.JSONObject()
        for ((k, v) in m) o.put(k.toString(), v)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_FP, o.toString()).apply()
    }

    private fun loadDismissed(ctx: Context): Map<Int, Long> = try {
        val o = org.json.JSONObject(
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DISMISS, "{}") ?: "{}"
        )
        o.keys().asSequence().associate { it.toInt() to o.optLong(it) }
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun loadTs(ctx: Context): Map<Int, Long> = try {
        val o = org.json.JSONObject(
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TS, "{}") ?: "{}"
        )
        o.keys().asSequence().associate { it.toInt() to o.optLong(it) }
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun saveTs(ctx: Context, m: Map<Int, Long>) {
        val o = org.json.JSONObject()
        for ((k, v) in m) o.put(k.toString(), v)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_TS, o.toString()).apply()
    }

    private fun saveDismissed(ctx: Context, m: Map<Int, Long>) {
        val o = org.json.JSONObject()
        for ((k, v) in m) o.put(k.toString(), v)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISMISS, o.toString()).apply()
    }

    /** 系统里划掉（或点掉）这条实况位：记下来，内容没变就不再重挂 */
    fun onDismissed(ctx: Context, id: Int) {
        val m = loadDismissed(ctx).toMutableMap()
        m[id] = System.currentTimeMillis()
        saveDismissed(ctx, m)
        Log.i(TAG, "用户划掉实况位 id=$id（内容不变则不再重挂）")
    }

    private fun dismissPi(ctx: Context, idOf_no: String): PendingIntent {
        val id = idOf(idOf_no)
        val i = Intent(ctx, LiveDismissReceiver::class.java)
            .setAction(ACTION_DISMISS)
            .putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------- 已发记录（用于撤销） ----------------

    private fun postedIds(ctx: Context): Set<Int> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_POSTED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optInt(it).takeIf { v -> v != 0 } }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun savePosted(ctx: Context, ids: Set<Int>) {
        val arr = JSONArray()
        for (i in ids) arr.put(i)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_POSTED, arr.toString()).apply()
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

    // ---------------- 自检（`--ez debug_live_selftest true`） ----------------

    private fun hasPromotedPermission(ctx: Context): Boolean = try {
        val pi = ctx.packageManager.getPackageInfo(
            ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS
        )
        pi.requestedPermissions?.contains("android.permission.POST_PROMOTED_NOTIFICATIONS") == true
    } catch (_: Throwable) {
        false
    }

    /**
     * 把官方那 10 条硬性条件**逐条对着真通知验一遍**。
     *
     * 为什么要自检而不是"看着代码觉得没问题"：提升型通知的条件是**AND 关系**，
     * 缺任意一条系统就默默不提升（不报错、不崩溃，只是流体云不出现），
     * 靠肉眼读代码极容易漏。这里构造一条真通知，把每条都读出来，
     * 最后用平台自己的 `hasPromotableCharacteristics()` 给终判。
     */
    @Suppress("DEPRECATION")
    fun selfTest(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ensureChannel(ctx)
        val ch = nm?.getNotificationChannel(CHANNEL)
        val n = build(ctx, Entry("SELFTEST", "31-5-7006", "测试驿站", "测试公司"))
        val style = n.extras.getString("android.template")
        return buildString {
            appendLine("=== 流体云 / 提升型通知自检 ===")
            appendLine("Android SDK = ${Build.VERSION.SDK_INT}（需 ≥ 36）")
            appendLine("① POST_PROMOTED_NOTIFICATIONS 已声明 = " + hasPromotedPermission(ctx))
            appendLine("   POST_NOTIFICATIONS 运行时授权 = " + TrackNotifier.canNotify(ctx) + "（否 ⇒ 通知直接发不出）")
            appendLine("② extras[requestPromotedOngoing] = " + n.extras.getBoolean(EXTRA_PROMOTED))
            appendLine("③ ongoing(FLAG_ONGOING_EVENT) = " + ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0))
            appendLine("④ contentTitle = " + n.extras.getCharSequence(Notification.EXTRA_TITLE))
            appendLine("⑤ 样式 template = $style")
            // 注：`Notification.EXTRA_CUSTOM_CONTENT_VIEW` 这个常量并不存在（编译报 Unresolved），
            //     自定义视图是存在 **字段** 上的，所以这里读字段而不是 extra key。
            appendLine("⑥ 自定义 contentView = " + (n.contentView != null) + "（必须 false）")
            appendLine("⑧ colorized = " + n.extras.getBoolean(Notification.EXTRA_COLORIZED) + "（必须 false）")
            appendLine("⑨ 渠道重要性 = " + ch?.importance + "（不能是 " + NotificationManager.IMPORTANCE_MIN + "）")
            appendLine("⑩ setWhen = " + n.`when` + "（当前 " + System.currentTimeMillis() + " ⇒ 未来 = " + (n.`when` > System.currentTimeMillis()) + "）")
            appendLine("短文案 = " + n.extras.getCharSequence(EXTRA_SHORT_CRITICAL))
            appendLine("---- 平台终判 ----")
            appendLine("hasPromotableCharacteristics = " + promotableCharacteristics(n))
            appendLine("canPostPromotedNotifications  = " + canPostPromoted(ctx))
            appendLine("（两个都 true 才会出现在流体云上；后者是设备/用户开关，去系统设置里开）")
        }
    }

    // ---------------- 诊断（设置页 / 自检用） ----------------

    fun diag(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val ch = nm?.getNotificationChannel(CHANNEL)
        return buildString {
            append("Android SDK=").append(Build.VERSION.SDK_INT)
            append("（≥36 才有提升型通知）\n")
            append("设备/用户是否允许提升 = ").append(canPostPromoted(ctx)).append('\n')
            append("渠道 = ").append(if (ch != null) "已建（重要性 ${ch.importance}）" else "未建").append('\n')
            append("已上流体云条数 = ").append(postedIds(ctx).size).append('\n')
            append("通知总开关 = ").append(if (TrackNotifier.canNotify(ctx)) "已授权" else "未授权")
            append("（未授权时任何通知都发不出）")
        }
    }
}
