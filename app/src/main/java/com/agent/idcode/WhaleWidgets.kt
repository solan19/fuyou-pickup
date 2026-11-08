package com.agent.idcode

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * 鲸鱼娘桌面小组件（vc297，同志 10-17 二次要求）。
 *
 * ## ★ 本次改了什么
 *   1. **形象换源**：弃用 AI 生成的色差版，改用社区现成 Q 版立绘
 *      （Neko3000/deepseek-whalechan，深藏青发色 + 深藏青女仆装，与官方「大飞鱼」一致）。
 *   2. **整幅铺满、不分割**：同志原话「小组件是大矩形，矩形内为鲸鱼娘形象，不要分割开来」。
 *      ⇒ 旧的「2×2 贴纸 + 顶部气泡」「4×2 左 60% 面板 + 右 40% 形象」全部废弃，
 *        现在两个尺寸都是**一张立绘铺满整个矩形**，台词改为浮在底部的半透明气泡（默认隐藏）。
 *   3. **台词库大扩张**：旧版只有 9 条，点两下就重复。现拆到 [WhaleLines]：
 *      · 闲聊池 200+ 条（贪吃白米饭/傲娇/粘人/怕胖/摸鱼/邀功/关心主人/碎碎念/卖萌）
 *      · 快递池 24 + 22 条，带防重复抽取（最近 25 条内不复用）
 *      · **有快递时，点击 10 次至少 7 次说快递**（报快递概率 0.75），无快递时全闲聊。
 *
 * ## 有哪两个
 *   · 鲸鱼娘 2×2 —— 立绘铺满 + 底部气泡（短句为主）
 *   · 鲸鱼娘 4×2 —— 立绘铺满 + 底部气泡（可带取件码/件数一行）
 *
 * ## 动效
 *   GIF 走 `res/drawable/whale_<kit>_anim.xml`（`<animated-image>` 包 GIF）。
 *   Android 28+ 的 AnimatedImageDrawable 由桌面进程自动循环播放，不受 RemoteViews 事务限制。
 *
 * ## 装扮自选（8 套预设）
 *   8 套对应 8 张不同姿势/表情的立绘（人在做不同的事），存 SharedPreferences 键 `whale_kit`。
 *
 * ## 交互（单击 / 双击）
 *   ★ 小组件收不到「长按」（被桌面占用），双击只能自己计时判定：
 *     第 1 次点击 → 记时间戳，显示气泡；若 420ms 内来第 2 次 → 判双击，开取件码页。
 *
 * ## 背景深浅色
 *   气泡底色/文字走 @color/wk_bubble / @color/wk_text（values=浅 / values-night=深）。
 *   launcher 以自身配置解析 RemoteViews 布局资源 ⇒ 系统切深浅色时自动切换。
 */
object WhaleWidgets {

    private const val PREFS = "idcode_prefs"
    private const val KEY_KIT = "whale_kit"

    /**
     * 8 套预设：key -> 显示名（顺序即选择器顺序）。
     *
     * ★ vc300：显示名统一为「装扮一」「装扮二」…，由 [cnNum] 循环生成。
     * ★ vc306：同志要求删除原 1/9/11/12（normal/headband/parfait/night）四套，
     *   剩 8 套重新连续编号（「装扮一」…「装扮八」，编号随列表顺序自动重排）。
     *   ⚠️ normal 原是默认套装 —— 默认改落到列表第一套（study）；
     *   老用户若存了被删的 key，kitOf 的兜底会自动回落到默认，不会开天窗。
     */
    val KITS: List<Pair<String, String>> = listOf(
        "study", "cool", "quiet", "retro", "cat",
        "rabbit", "bow", "whalehead"
    ).mapIndexed { i, key -> key to "装扮${cnNum(i + 1)}" }

    /** 当前选中的套装（默认列表第一套；存档了被删 key 的老用户自动回落） */
    fun kitOf(ctx: Context): String {
        val def = KITS[0].first
        val k = sp(ctx).getString(KEY_KIT, def) ?: def
        return if (KITS.any { it.first == k }) k else def
    }

    fun saveKit(ctx: Context, key: String) {
        if (KITS.none { it.first == key }) return
        sp(ctx).edit().putString(KEY_KIT, key).apply()
    }

    fun kitName(key: String): String = KITS.firstOrNull { it.first == key }?.second ?: "装扮一"

    /** 1..8 -> 中文数字（一/二/…/八）。超出范围回退成阿拉伯数字 */
    private fun cnNum(n: Int): String = when (n) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
        5 -> "五"; 6 -> "六"; 7 -> "七"; 8 -> "八"
        else -> n.toString()
    }

    /**
     * 套装 key -> 静态立绘资源 id。
     * ★ vc298：同志要求取消 GIF 动图、全改静态图 ⇒ 直接引用 PNG，不再走 animated-image 包装。
     *   图片在 res/drawable-nodpi/（高清 640 高，且不被密度缩放）。
     * ★ vc306：normal/headband/parfait/night 四套退役（PNG 已删），兜底落第一套 study。
     */
    fun kitAnimRes(key: String): Int = when (key) {
        "study" -> R.drawable.whale_study
        "cool" -> R.drawable.whale_cool
        "quiet" -> R.drawable.whale_quiet
        "retro" -> R.drawable.whale_retro
        "cat" -> R.drawable.whale_cat
        "rabbit" -> R.drawable.whale_rabbit
        "bow" -> R.drawable.whale_bow
        "whalehead" -> R.drawable.whale_whalehead
        else -> R.drawable.whale_study
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 台词选取（vc297：交给 WhaleLines 处理） ----------------

    /**
     * 组装本次要说的台词。
     *
     * 返回 Triple(台词, 附加信息, 类型)，类型取值：
     *   2 = 有取件码、1 = 有在途件、0 = 无快递
     *
     * ★ 同志 2026-10-17 第二次修订的规则（vc298）：
     *   · **仅有取件码时**，点击 10 次至少 7 次要说取件码 ⇒ 报码概率 0.75。
     *   · **只有在途件、或什么都没有时**，**全部走词库原文对话**（不再报"路上有几个"）——
     *     因为件数已经由右下角蓝色圆徽标显示了，不必再用嘴说一遍。
     *   · 词库见 [WhaleLines]：闲聊 200+ 条 + 取件码 50+ 条，带防重复。
     */
    fun pickLine(ctx: Context): Triple<String, String, Int> {
        val codes = HomeWidgets.loadCodes(ctx)
        val transit = HomeWidgets.loadTransit(ctx)
        return when {
            codes.isNotEmpty() -> {
                // 有取件码：75% 报码，25% 闲聊
                val t = if (Math.random() < 0.75) WhaleLines.pickCode(codes.size)
                        else WhaleLines.pickChat()
                Triple(t, codes.take(3).joinToString(" "), 2)
            }
            transit.isNotEmpty() -> {
                // 仅在途件：**全部走词库原文闲聊**（件数交给右下角徽标说）
                Triple(WhaleLines.pickChat(), "", 1)
            }
            else -> {
                // 什么都没有：全部走词库原文闲聊里偏"空状态"的口吻
                Triple(WhaleLines.pickChat(), "", 0)
            }
        }
    }

    // ---------------- 刷新 ----------------

    /** App 侧换装 / 数据变化后调它：两个鲸鱼娘组件哪个在桌面上就刷哪个 */
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        try {
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, WhaleWidget2x2::class.java)))
                render(ctx, mgr, id, big = false)
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, WhaleWidget4x2::class.java)))
                render(ctx, mgr, id, big = true)
        } catch (t: Throwable) { SafeRun.w("WhaleWidgets", t) }
        android.util.Log.i("WhaleWidgets", "updateAll: kit=${kitOf(ctx)} 码=${HomeWidgets.loadCodes(ctx).size} 在途=${HomeWidgets.loadTransit(ctx).size}")
    }

    // ---------------- 渲染 ----------------

    /**
     * 取件码展示：把 N 个码压成"能显示的几个 + 还剩 X 个"。
     *
     * ★ 同志 10-17 第三条要求：
     *   「有多个取件码时则显示最多显示的几个，剩下的显示还剩X个，比如还剩7个这种」
     *
     * @param codes  全部取件码
     * @param cap    本尺寸最多显示几个（2×2 = 1，4×2 = 4）
     * @return 第一次改写列表，第 0 位是"码们"，第 1 位是"还剩 X 个"（没有则空串），第 2 位是"是否需要省略"
     */
    private fun splitCodes(codes: List<String>, cap: Int): Triple<String, String, Boolean> {
        if (codes.isEmpty()) return Triple("", "", false)
        val shown = codes.take(cap)
        val rest = codes.size - shown.size
        val shownText = shown.joinToString("  ")
        return if (rest > 0) Triple(shownText, "还剩 $rest 个", true)
        else Triple(shownText, "", false)
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int, big: Boolean) {
        val v = RemoteViews(ctx.packageName, if (big) R.layout.widget_whale_4x2 else R.layout.widget_whale_2x2)
        val kit = kitOf(ctx)

        // 立绘（静态图，两个尺寸都换图）
        v.setImageViewResource(R.id.wk_char, kitAnimRes(kit))

        // ★ vc298：右下角在途件数徽标 —— 只有在途件 > 0 时才显示
        val transitN = HomeWidgets.loadTransit(ctx).size
        if (transitN > 0) {
            v.setViewVisibility(R.id.wk_transit_badge, View.VISIBLE)
            v.setTextViewText(R.id.wk_transit_badge, if (transitN > 99) "99+" else transitN.toString())
        } else {
            v.setViewVisibility(R.id.wk_transit_badge, View.GONE)
        }

        // ★ vc298 第 6 条：**有取件码时，常驻显示取件码**（2×2 最多 1 个 / 4×2 最多 4 个 + 还剩 X 个）
        val codes = HomeWidgets.loadCodes(ctx)
        val cap = if (big) 4 else 1
        val (shownCodes, restText, _) = splitCodes(codes, cap)

        if (big) {
            // ---------- 4×2「分割 + 对话框」----------
            // vc300：同志要求删掉「装扮一」那行 ⇒ 不再给 wk_name 赋值（节点已收成 0dp 占位）
            v.setViewVisibility(R.id.wk_bubble, View.GONE)   // 分割式下不用浮动气泡
            if (codes.isNotEmpty()) {
                // 常驻：一句带取件码的话（词库原文）+ 码列表 + 还剩
                v.setTextViewText(R.id.wk_say, WhaleLines.pickCode(codes.size))
                val info = buildString {
                    append(shownCodes)
                    if (restText.isNotEmpty()) append("（$restText）")
                }
                v.setViewVisibility(R.id.wk_info, View.VISIBLE)
                v.setTextViewText(R.id.wk_info, info)
            } else {
                // 无码：常驻闲聊
                v.setTextViewText(R.id.wk_say, WhaleLines.pickChat())
                v.setViewVisibility(R.id.wk_info, View.GONE)
            }
        } else {
            // ---------- 2×2 整幅铺满 ----------
            if (codes.isNotEmpty()) {
                // ★ 有码 ⇒ 气泡**常驻显示**（不再等点击），内容 = 带码的话 + 码 + 还剩
                v.setViewVisibility(R.id.wk_bubble, View.VISIBLE)
                v.setTextViewText(R.id.wk_say, WhaleLines.pickCode(codes.size))
                if (shownCodes.isNotEmpty()) {
                    v.setViewVisibility(R.id.wk_info, View.VISIBLE)
                    v.setTextViewText(R.id.wk_info,
                        if (restText.isNotEmpty()) "$shownCodes（$restText）" else shownCodes)
                } else {
                    v.setViewVisibility(R.id.wk_info, View.GONE)
                }
            } else {
                // 无码：气泡收起，桌面清爽
                v.setViewVisibility(R.id.wk_bubble, View.GONE)
            }
        }

        // 点击：单击=说话（Receiver 内计时判定双击）
        v.setOnClickPendingIntent(R.id.wk_root, tapIntent(ctx, id))

        mgr.updateAppWidget(id, v)
    }

    /**
     * 单击/双击共用的 PendingIntent —— 带组件 id 与时间戳语义。
     * 不做 getActivity（会直接开 App），而是 getBroadcast 交回 Receiver 自己判定。
     */
    private fun tapIntent(ctx: Context, widgetId: Int): PendingIntent {
        val i = Intent(ctx, WhaleTapReceiver::class.java).apply {
            action = ACTION_TAP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        // requestCode 用 widgetId 区分（同一组件实例的多个副本各自独立）
        return PendingIntent.getBroadcast(
            ctx, widgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION_TAP = "com.agent.idcode.WHALE_TAP"

    /** 双击判定窗口（ms）。同志要求"双击开取件码页"，单击响应延后到这个窗口之后 */
    const val DOUBLE_MS = 420L

    /** 记录上一次点击时间（widgetId -> 时间戳）。静态变量即可：Receiver 与 App 同进程 */
    private val lastTap = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun recordTap(widgetId: Int): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastTap[widgetId]
        lastTap[widgetId] = now
        return prev != null && now - prev < DOUBLE_MS
    }

    /** 双击时清掉记录，避免第 3 次点击又被判成双击 */
    fun clearTap(widgetId: Int) {
        lastTap.remove(widgetId)
    }

    /** 打开取件码页（双击动作） */
    fun openPickup(ctx: Context) {
        try {
            val i = Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra(HomeWidgets.EXTRA_OPEN_PICKUP, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(i)
        } catch (t: Throwable) { SafeRun.w("WhaleWidgets", t) }
    }

    /**
     * 单击后「说话」。
     *
     * ★ vc298：
     *   · 两个尺寸的行为不同（同志要求 4×2 改回分割 + 对话框）：
     *     2×2（整幅铺满）—— 冒**浮动气泡**（wk_bubble）；4×2（分割）—— 台词写进**左侧常驻面板**。
     *   · 第 6 条：**有取件码时取件码是常驻的**，所以点击后如果还有码，
     *     换的是「另一句带码的话」而不是闲聊；码列表 / 还剩 X 个 保持不变。
     */
    fun showBubble(ctx: Context, mgr: AppWidgetManager, id: Int, big: Boolean, say: String, info: String) {
        val v = RemoteViews(ctx.packageName, if (big) R.layout.widget_whale_4x2 else R.layout.widget_whale_2x2)
        val kit = kitOf(ctx)
        v.setImageViewResource(R.id.wk_char, kitAnimRes(kit))

        // 徽标（两个尺寸都带）
        val transitN = HomeWidgets.loadTransit(ctx).size
        if (transitN > 0) {
            v.setViewVisibility(R.id.wk_transit_badge, View.VISIBLE)
            v.setTextViewText(R.id.wk_transit_badge, if (transitN > 99) "99+" else transitN.toString())
        } else {
            v.setViewVisibility(R.id.wk_transit_badge, View.GONE)
        }

        // 有码时，码列表 / 还剩 X 个 照常常驻显示（点击只换那句话）
        val codes = HomeWidgets.loadCodes(ctx)
        val cap = if (big) 4 else 1
        val (shownCodes, restText, _) = splitCodes(codes, cap)
        val infoFinal = when {
            codes.isEmpty() -> info
            restText.isNotEmpty() -> "$shownCodes（$restText）"
            else -> shownCodes
        }

        if (big) {
            // 分割式：台词进左侧面板（vc300：不再写 wk_name，那行已删）
            v.setTextViewText(R.id.wk_say, say)
            v.setViewVisibility(R.id.wk_bubble, View.GONE)
            if (infoFinal.isNotEmpty()) {
                v.setViewVisibility(R.id.wk_info, View.VISIBLE)
                v.setTextViewText(R.id.wk_info, infoFinal)
            } else {
                v.setViewVisibility(R.id.wk_info, View.GONE)
            }
        } else {
            // 整幅式：冒浮动气泡
            v.setViewVisibility(R.id.wk_bubble, View.VISIBLE)
            v.setTextViewText(R.id.wk_say, say)
            if (infoFinal.isNotEmpty()) {
                v.setViewVisibility(R.id.wk_info, View.VISIBLE)
                v.setTextViewText(R.id.wk_info, infoFinal)
            } else {
                v.setViewVisibility(R.id.wk_info, View.GONE)
            }
        }
        v.setOnClickPendingIntent(R.id.wk_root, tapIntent(ctx, id))
        mgr.updateAppWidget(id, v)
    }

    /**
     * 4 秒后复原。
     *
     * ★ vc299 修 Bug：**时长不固定**（有时 1 秒有时 2 秒就消失）。
     *   根因：每次单击都 post 一个新的 4 秒任务，**旧任务不会取消**；
     *   连续点几下时，先前的定时器会提前把后来的气泡收掉 ⇒ 观感上"随机时长"。
     *   修法：给每个组件一个**自增令牌（ticket）**，新的覆盖旧的；
     *   定时器醒来时先比对令牌，不是最新的就直接放弃 —— 保证"最后一次点击起算 4 秒"。
     */
    private val bubbleTicket = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** 全局单调递增的令牌发号器（不随组件重置，避免"新令牌撞上旧令牌"） */
    private val bubbleSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 气泡常驻时长（ms）。固定值，避免"每次不一样"的观感 */
    const val BUBBLE_MS = 4000L

    /** 作废该组件挂起的气泡定时器（双击开页、或数据刷新时调用） */
    fun cancelRestore(id: Int) {
        bubbleTicket.remove(id)
    }

    fun restoreLater(ctx: Context, id: Int, big: Boolean) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val my = bubbleSeq.incrementAndGet()
        bubbleTicket[id] = my
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 只有"自己仍是最新那次"才执行，旧的定时器一律作废
            if (bubbleTicket[id] != my) return@postDelayed
            bubbleTicket.remove(id)
            render(ctx, mgr, id, big)
        }, BUBBLE_MS)
    }
}

/** 鲸鱼娘 2×2 */
class WhaleWidget2x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) WhaleWidgets.render(context, mgr, id, big = false)
    }
}

/** 鲸鱼娘 4×2 */
class WhaleWidget4x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) WhaleWidgets.render(context, mgr, id, big = true)
    }
}

/**
 * 点击接收器：单击 = 冒气泡说话；双击 = 打开取件码页。
 *
 * ★ 为什么不用 PendingIntent.getActivity 直接开 App：
 *   那样没法区分单击/双击（两次点击都会直接进 App）。所以点一下先回到这里：
 *   第 1 次 → 立即冒气泡（视觉上有反应）；第 2 次在 420ms 内 → 判双击，开页面。
 */
class WhaleTapReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WhaleWidgets.ACTION_TAP) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val mgr = AppWidgetManager.getInstance(context) ?: return

        // 判断是 4×2 还是 2×2（看这个 id 属于哪个 provider）
        val big = try {
            mgr.getAppWidgetIds(ComponentName(context, WhaleWidget4x2::class.java)).contains(id)
        } catch (_: Throwable) {
            false
        }

        if (WhaleWidgets.recordTap(id)) {
            // 双击：开取件码页。
            // ★ vc299：必须**同时作废**挂起的气泡定时器，否则第一次单击留下的
            //   4 秒定时器会在开页后把状态又刷一遍，观感上"气泡突然消失"。
            WhaleWidgets.clearTap(id)
            WhaleWidgets.cancelRestore(id)
            WhaleWidgets.openPickup(context)
            WhaleWidgets.render(context, mgr, id, big)
        } else {
            // 单击：冒气泡 + 4 秒后复原
            val line = WhaleWidgets.pickLine(context)
            WhaleWidgets.showBubble(context, mgr, id, big, line.first, line.second)
            WhaleWidgets.restoreLater(context, id, big)
        }
    }
}
