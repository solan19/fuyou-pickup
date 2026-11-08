package com.agent.idcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * ★ vc321：小米超级岛（HyperOS 3 焦点通知）—— 照 Maling-Island 的
 * IslandNotificationHelper 一比一复刻，场景收敛为「取件码到站上岛」。
 *
 * ## 机理（与 Maling 相同）
 * 超级岛本质还是通知：往一条常驻通知里塞小米私有暗号 `miui.focus.param`（JSON，
 * ≤3072 字节）+ `miui.focus.pics`（本地图片 Icon parcelable）+ `miui.focus.actions`
 * （岛上按钮），HyperOS 3 识别后渲染成岛。
 *
 * ## 关键复刻点（都来自 Maling 源码实证）
 * · `"protocol": 3` 显式写在 param_v2 里；business 自定义字符串
 * · 图片走**本地 Icon parcelable**（不必传网图 URL）
 * · 岛上按钮：extras Bundle "miui.focus.actions" 放 Notification.Action，
 *   JSON hintInfo.actionInfo 引用 "miui.focus.action_complete"
 * · ★「保温两连发」：先发一条把 "narrowFont": true 换成 false 的暖场通知唤醒焦点系统，
 *   120ms 后发正式版 —— 上岛更稳（Maling 实测经验）
 * · 设备三查询：persist.sys.feature.island / notification_focus_protocol / canShowFocus
 * · 发布包在 IslandBypass（掐网绕白名单）里执行
 *
 * ## 本 App 的语义约束（同志口径，不许"优化"掉）
 * · 开关默认关；关掉 = 撤下全部岛通知（与流体云 LiveUpdate 同一套路）
 * · 非小米/红米澎湃OS 3 设备（能力探测不过）⇒ 一条都不发，防止在 ColorOS 上
 *   变成抽屉里的常驻垃圾通知
 * · ★ Shizuku 与 Stellar 都未授权 ⇒ 超级岛开关开不了（同志 2026-10-19 定）
 * · 岛上「已完成」按钮 = 用户手动撤岛（同一码不再自动重挂，24h 后失效）——
 *   **不改** PddCodeStore 的已取件状态（「已取件」唯一来源仍是长按标记，vc316）
 */
object IslandHelper {

    private const val TAG = "Island"
    private const val PREF = "idcode_prefs"
    private const val KEY_ENABLED = "island_enabled"
    private const val KEY_BYPASS = "island_bypass"
    private const val KEY_POSTED = "island_posted"
    private const val KEY_DISMISSED = "island_dismissed"
    private const val DISMISS_KEEP_MS = 24L * 3600_000L

    const val CHANNEL = "idcode_pickup_island"
    const val ACTION_COMPLETE = "com.agent.idcode.action.ISLAND_COMPLETE"
    const val EXTRA_NID = "nid"

    private const val PIC_MAIN = "miui.focus.pic_icon_main"
    private const val PIC_APP = "miui.focus.pic_app"
    private const val PIC_IM_BADGE = "miui.focus.pic_im_badge"
    private const val PIC_TICKER = "miui.focus.pic_ticker"
    private const val PIC_AOD = "miui.focus.pic_aod"
    private const val ACT_COMPLETE = "miui.focus.action_complete"

    /** 单线程串行发布：掐网-发布-恢复全程不并发（Maling 用 synchronized，这里靠单线程队列） */
    private val io = Executors.newSingleThreadExecutor()

    /** 与 LiveUpdate.Entry 同构（TrackWorker 一份列表喂两处） */
    data class Entry(
        val no: String,
        val code: String,
        val station: String = "",
        val company: String = ""
    )

    // ---------------- 开关（idcode_prefs，与设置页同一份） ----------------

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    /** 开/关立即生效：关 = 撤下全部岛通知（不碰任何系统设置） */
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) cancelAll(ctx)
    }

    fun bypassEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BYPASS, false)

    fun setBypass(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BYPASS, on).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun nm(ctx: Context): NotificationManager? =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    // ---------------- 设备能力三查询（Maling 同款） ----------------

    /** persist.sys.feature.island —— 设备有没有超级岛硬件/系统支持 */
    fun isSupportIsland(): Boolean = try {
        val m = Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        m.invoke(null, "persist.sys.feature.island", false) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    /** notification_focus_protocol —— 1/2/3 = 澎湃OS 1/2/3 焦点通知协议版本 */
    fun focusProtocol(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
    } catch (_: Throwable) {
        0
    }

    /** 本 App 的焦点通知权限开关（未走白名单审批时通常是 false —— 掐网绕的就是它） */
    fun hasFocusPermission(ctx: Context): Boolean = try {
        val uri = android.net.Uri.parse("content://miui.statusbar.notification.public")
        val args = Bundle().apply { putString("package", ctx.packageName) }
        ctx.contentResolver.call(uri, "canShowFocus", null, args)
            ?.getBoolean("canShowFocus", false) ?: false
    } catch (_: Throwable) {
        false
    }

    /** 本机是否值得发岛通知（属性缺失但协议设置在 ⇒ 也算 HyperOS） */
    fun deviceAllowsIsland(ctx: Context): Boolean =
        isSupportIsland() || focusProtocol(ctx) > 0

    /** 设置页一行状态：支持/协议/权限/引擎 */
    fun statusText(ctx: Context): String =
        "岛${if (isSupportIsland()) "支持" else "未支持"} · 协议v${focusProtocol(ctx)} · " +
                "权限${if (hasFocusPermission(ctx)) "已开" else "未开"} · 引擎:${IslandBypass.engineName()}"

    fun diag(ctx: Context): String = buildString {
        appendLine("=== 超级岛状态 ===")
        appendLine("设备支持 persist.sys.feature.island = ${isSupportIsland()}")
        appendLine("焦点协议 notification_focus_protocol = ${focusProtocol(ctx)}（1/2/3=澎湃OS1/2/3）")
        appendLine("本 App 焦点权限 canShowFocus = ${hasFocusPermission(ctx)}（false 才需要绕过）")
        appendLine("绕过引擎 = ${IslandBypass.engineName()}")
        appendLine("开关 = 上岛${if (isEnabled(ctx)) "开" else "关"} · 绕过${if (bypassEnabled(ctx)) "开" else "关"}")
        appendLine("已挂岛 ${postedIds(ctx).size} 条")
    }

    // ---------------- 渠道 ----------------

    fun ensureChannel(ctx: Context) {
        val manager = nm(ctx) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "取件码（超级岛）", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "快递到站后，把取件码挂上小米超级岛（HyperOS 焦点通知）"
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    // ---------------- 同步（全量语义，与 LiveUpdate.sync 同构） ----------------

    /**
     * 全量同步：该上岛的更新/新增，取走/消失的撤下。
     * want 由调用方给（TrackWorker 的 live 列表 / syncFromSnapshot 的快照）。
     */
    fun sync(ctx: Context, entries: List<Entry>) {
        if (!isEnabled(ctx)) {
            cancelAll(ctx)
            return
        }
        // ★ 非澎湃OS 设备一条不发（防止 ColorOS 上变成常驻垃圾通知）
        if (!deviceAllowsIsland(ctx)) {
            Log.i(TAG, "本机无超级岛能力 ⇒ 不发岛通知")
            return
        }
        ensureChannel(ctx)
        val want = entries
            .filter { it.no.isNotBlank() && it.code.isNotBlank() }
            .distinctBy { it.code }
            .filter { !isDismissed(ctx, idOf(it.no)) }
            .take(6)
        val wantIds = want.map { idOf(it.no) }.toSet()
        io.submit {
            val manager = nm(ctx) ?: return@submit
            for (e in want) {
                try {
                    publishOne(ctx, e)
                } catch (t: Throwable) {
                    Log.w(TAG, "上岛失败 ${e.no}: ${t.message}")
                }
            }
            for (old in postedIds(ctx) - wantIds) {
                try {
                    manager.cancel(old)
                    Log.i(TAG, "撤岛 id=$old（码已不在）")
                } catch (t: Throwable) { SafeRun.w("IslandHelper", t) }
            }
            cleanDismissed(ctx, wantIds)
            savePosted(ctx, wantIds)
            if (want.isNotEmpty()) {
                Log.i(TAG, "岛上同步 ${want.size} 条: " +
                        want.joinToString(" | ") { "${it.code}@${it.station.take(8)}" })
            }
        }
    }

    /** App 启动/刷新时从持久化快照补推（与 LiveUpdate.syncFromSnapshot 同一套过滤） */
    fun syncFromSnapshot(ctx: Context) {
        if (!isEnabled(ctx)) return
        val items = try {
            ExpressStore.load(ctx)
        } catch (_: Throwable) {
            return
        }
        if (items.isEmpty()) return
        val cachedCodes = try {
            PddCodeStore.all(ctx).values.filter { it.pickedAt == 0L }.map { it.code }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
        val entries = items
            .filter {
                it.pickupCode.isNotBlank() &&
                        it.pickupCode in cachedCodes &&
                        ExpressStore.stageOf(it) == ExpressStore.Stage.ARRIVED
            }
            .map { Entry(it.no, it.pickupCode, it.stationText, it.company) }
        sync(ctx, entries)
    }

    /** 关闭功能 / 清场：撤下全部 + 清记录 */
    fun cancelAll(ctx: Context) {
        val manager = nm(ctx) ?: return
        val ids = postedIds(ctx)
        for (id in ids) {
            try {
                manager.cancel(id)
            } catch (t: Throwable) { SafeRun.w("IslandHelper", t) }
        }
        if (ids.isNotEmpty()) Log.i(TAG, "已撤下全部岛通知 ${ids.size} 条")
        savePosted(ctx, emptySet())
        prefs(ctx).edit().remove(KEY_DISMISSED).apply()
    }

    /** 岛上「已完成」按钮回调：撤岛 + 记忆（同一码不再自动重挂，24h 失效） */
    fun onCompleteAction(ctx: Context, nid: Int) {
        try {
            nm(ctx)?.cancel(nid)
        } catch (t: Throwable) { SafeRun.w("IslandHelper", t) }
        savePosted(ctx, postedIds(ctx) - nid)
        val map = dismissed(ctx).toMutableMap()
        map[nid] = System.currentTimeMillis()
        saveDismissed(ctx, map)
        Log.i(TAG, "岛上点「已完成」id=$nid（24h 内不再自动重挂）")
    }

    // ---------------- 发布（Maling 同款：保温两连发 + 掐网包裹） ----------------

    private fun publishOne(ctx: Context, e: Entry) {
        ensureChannel(ctx)
        val nid = idOf(e.no)
        val json = buildJson(ctx, e)
        val warmJson = json.replace("\"narrowFont\": true", "\"narrowFont\": false")
        val manager = nm(ctx) ?: return
        IslandBypass.runIfEnabled(ctx, bypassEnabled(ctx)) {
            // ① 暖场（narrowFont=false，唤醒焦点系统）
            manager.notify(nid, buildNotification(ctx, e, nid, warmJson))
            Thread.sleep(120)
            // ② 正式版
            manager.notify(nid, buildNotification(ctx, e, nid, json))
        }
        Log.i(TAG, "已发布岛通知 code=${e.code} id=$nid")
    }

    private fun buildNotification(ctx: Context, e: Entry, nid: Int, json: String): Notification {
        val brand = if (e.company.isNotBlank()) e.company else "快递"
        val title = "$brand · 取件码"
        val body = "取件码：${e.code}" + if (e.station.isNotBlank()) " · ${e.station}" else ""
        val large: Bitmap? = try {
            BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)
        } catch (_: Throwable) {
            null
        }
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .addExtras(buildPicsBundle(ctx, large))
            .addExtras(buildActionsBundle(ctx, nid))
            .addAction(Notification.Action.Builder(null, "已完成", completePi(ctx, nid)).build())
        if (large != null) builder.setLargeIcon(large)
        return builder.build().also {
            it.extras.putString("miui.focus.param", json)
        }
    }

    // ---------------- JSON（Maling PICKUP_TEMPLATE 原样，business 换成自家） ----------------

    private val PICKUP_TEMPLATE = """
{
  "param_v2": {
    "protocol": 3,
    "business": "mayfly_pickup_code",
    "updatable": true,
    "ticker": "{{ticker}}",
    "tickerPic": "miui.focus.pic_ticker",
    "enableFloat": true,
    "isShowNotification": true,
    "islandFirstFloat": true,
    "aodTitle": "{{contentText}}",
    "aodPic": "miui.focus.pic_aod",
    "outEffectSrc": "outer_glow",
    "picInfo": {"type": 1, "pic": "miui.focus.pic_app", "loop": false, "autoplay": false, "number": 0},
    "smallWindowInfo": {"targetPage": "{{targetPage}}"},
    "param_island": {
      "islandProperty": 1,
      "islandOrder": false,
      "dismissIsland": false,
      "needCloseAnimation": true,
      "shareData": {
        "pic": "miui.focus.pic_icon_main",
        "title": "{{shareTitle}}",
        "content": "{{chatContent}}",
        "shareContent": "{{shareContent}}"
      },
      "bigIslandArea": {
        "imageTextInfoLeft": {
          "type": 1,
          "picInfo": {"type": 1, "pic": "miui.focus.pic_icon_main", "loop": false, "autoplay": false, "number": 0},
          "textInfo": {"title": "{{aRegionBrand}}", "content": "", "showHighlightColor": false, "narrowFont": true}
        },
        "textInfo": {"frontTitle": "", "title": "{{capsuleCode}}", "content": "", "showHighlightColor": false, "narrowFont": true}
      },
      "smallIslandArea": {
        "imageTextInfoRight": {
          "type": 6,
          "textInfo": {"title": "{{smallCode}}", "showHighlightColor": false, "narrowFont": true},
          "picInfo": {"type": 4, "pic": "miui.focus.pic_icon_main"}
        }
      }
    },
    "iconTextInfo": {
      "animIconInfo": {"type": 0, "src": "miui.focus.pic_icon_main", "srcDark": "miui.focus.pic_icon_main"},
      "title": "{{expandTopTitle}}",
      "content": "{{expandTopSubtitle}}",
      "subContent": "",
      "colorTitle": "#000000",
      "colorTitleDark": "#FFFFFF",
      "colorContent": "#666666",
      "colorContentDark": "#AAAAAA",
      "colorSubContent": "#666666",
      "colorSubContentDark": "#AAAAAA"
    },
    "hintInfo": {
      "type": 1,
      "title": "{{expandBottomBrand}}",
      "content": "{{expandBottomTag}}",
      "colorTitle": "#000000",
      "colorTitleDark": "#FFFFFF",
      "colorContent": "#FFFFFF",
      "colorContentDark": "#FFFFFF",
      "colorContentBg": "#4A4A4F",
      "actionInfo": {
        "action": "miui.focus.action_complete",
        "actionTitle": "已完成",
        "actionTitleColor": "#FFFFFF",
        "actionBgColor": "#4A4A4F",
        "actionIntentType": 1
      }
    }
  }
}
"""

    private fun buildJson(ctx: Context, e: Entry): String {
        val brand = if (e.company.isNotBlank()) e.company else "快递"
        val code = e.code.trim().replace('－', '-').replace('：', ':').replace('／', '/')
        val station = compact(e.station, "驿站", 22)
        val contentText = "取件码：$code"
        val shareContent = buildString {
            append(brand).append(" 取件码")
            append("\n码：").append(code)
            if (e.station.isNotBlank()) append("\n地点：").append(e.station.trim())
        }
        return PICKUP_TEMPLATE
            .replace("{{ticker}}", escape(contentText))
            .replace("{{shareTitle}}", escape(compact("$brand 取件码", contentText, 28)))
            .replace("{{shareContent}}", escape(shareContent))
            .replace("{{aRegionBrand}}", escape(compact(brand, code, 18)))
            .replace("{{chatContent}}", escape(station))
            .replace("{{contentText}}", escape(contentText))
            .replace("{{expandTopTitle}}", escape(compact(brand, code, 18)))
            .replace("{{expandTopSubtitle}}", escape(station))
            .replace("{{expandBottomBrand}}", escape(compact(code, code, 16)))
            .replace("{{expandBottomTag}}", escape(compact("取件码", "取件码", 10)))
            .replace("{{targetPage}}", ctx.packageName + ".MainActivity")
            .replace("{{capsuleCode}}", escape(code))
            .replace("{{smallCode}}", escape(code))
    }

    /** JSON 字符串转义（与 Maling escape 相同：反斜杠、引号、换行折空格） */
    private fun escape(v: String): String =
        v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun compact(value: String, fallback: String, maxLength: Int): String {
        val text = value.replace("\n", " ").trim().ifBlank { fallback.trim() }
        return if (text.length <= maxLength) text else text.take(maxLength - 1) + "…"
    }

    // ---------------- 图片 / 按钮（本地 Icon parcelable，Maling 同款） ----------------

    private fun buildPicsBundle(ctx: Context, large: Bitmap?): Bundle {
        val appIcon = Icon.createWithResource(ctx, R.mipmap.ic_launcher)
        val main = if (large != null) Icon.createWithBitmap(large) else appIcon
        val transparent = Icon.createWithBitmap(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        )
        val pics = Bundle().apply {
            putParcelable(PIC_MAIN, main)
            putParcelable(PIC_APP, appIcon)
            putParcelable(PIC_IM_BADGE, transparent)
            putParcelable(PIC_TICKER, main)
            putParcelable(PIC_AOD, main)
        }
        return Bundle().apply { putBundle("miui.focus.pics", pics) }
    }

    private fun buildActionsBundle(ctx: Context, nid: Int): Bundle {
        val act = Notification.Action.Builder(null, "已完成", completePi(ctx, nid)).build()
        val actions = Bundle().apply { putParcelable(ACT_COMPLETE, act) }
        return Bundle().apply { putBundle("miui.focus.actions", actions) }
    }

    private fun completePi(ctx: Context, nid: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, nid,
        Intent(ctx, IslandActionReceiver::class.java).apply {
            action = ACTION_COMPLETE
            putExtra(EXTRA_NID, nid)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ---------------- 已发 / 已撤记录 ----------------

    private fun idOf(no: String): Int = ("island:" + no).hashCode()

    private fun postedIds(ctx: Context): Set<Int> {
        val raw = prefs(ctx).getString(KEY_POSTED, null) ?: return emptySet()
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
        prefs(ctx).edit().putString(KEY_POSTED, arr.toString()).apply()
    }

    private fun dismissed(ctx: Context): Map<Int, Long> {
        val raw = prefs(ctx).getString(KEY_DISMISSED, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length() step 2).mapNotNull { i ->
                val id = arr.optInt(i)
                val ts = arr.optLong(i + 1)
                if (id != 0 && ts > 0) id to ts else null
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun saveDismissed(ctx: Context, map: Map<Int, Long>) {
        val arr = JSONArray()
        for ((id, ts) in map) {
            arr.put(id)
            arr.put(ts)
        }
        prefs(ctx).edit().putString(KEY_DISMISSED, arr.toString()).apply()
    }

    private fun isDismissed(ctx: Context, nid: Int): Boolean {
        val ts = dismissed(ctx)[nid] ?: return false
        return System.currentTimeMillis() - ts < DISMISS_KEEP_MS
    }

    private fun cleanDismissed(ctx: Context, activeIds: Set<Int>) {
        val now = System.currentTimeMillis()
        val kept = dismissed(ctx).filter { (id, ts) ->
            id in activeIds && now - ts < DISMISS_KEEP_MS
        }
        saveDismissed(ctx, kept)
    }

    // ---------------- 实验入口（--ez debug_island true） ----------------

    /**
     * 发一条假的「待取件 88-8-8888」：
     * HyperOS 上应变超级岛；其它机器上是普通常驻通知（实验不受门禁限制）。
     * 返回诊断文本（调用方落盘）。
     */
    fun debugPublish(ctx: Context): String {
        ensureChannel(ctx)
        val e = Entry("ISLAND_DEBUG", "88-8-8888", "测试驿站（超级岛实验）", "蜉蝣")
        val json = buildJson(ctx, e)
        io.submit {
            try {
                publishOne(ctx, e)
                // ★ 60s 后自动撤：实验看一眼就够，别在任何机器上留一条撤不掉的常驻通知
                Thread.sleep(60_000L)
                nm(ctx)?.cancel(idOf(e.no))
                Log.i(TAG, "debug 岛通知已到时自动撤下")
            } catch (t: Throwable) {
                Log.w(TAG, "debug 岛通知失败: ${t.message}")
            }
        }
        return buildString {
            appendLine("=== 超级岛实验 ===")
            appendLine(statusText(ctx))
            appendLine("开关 = 上岛${if (isEnabled(ctx)) "开" else "关"} · 绕过${if (bypassEnabled(ctx)) "开" else "关"}（绕过已并入上岛开关，开岛即开绕过）")
            appendLine("JSON 字节数 = ${json.toByteArray(Charsets.UTF_8).size}（上限 3072）")
            appendLine("实验码 = 88-8-8888 @ 测试驿站（超级岛实验）")
            appendLine("判读：HyperOS 亮岛=成功；只有常驻通知=被白名单拦（引擎日志见 IslandBypass：Shizuku 优先、Stellar 兜底）")
        }
    }
}
