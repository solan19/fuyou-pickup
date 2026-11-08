package com.agent.idcode

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray

/**
 * 桌面小组件中心（vc289，同志 10-16 要求）。
 *
 * ## 有哪几个
 *   · 取件码 2×2 / 取件码 4×2 —— 待取件取件码格子（沿用旧 3×2 组件的数据链）
 *   · 途中快递 2×2 / 途中快递 4×2 —— 最新物流动态卡（状态词 + 承运商 + 最新轨迹 + 「全部快递 ×N」胶囊）
 *
 * ## 数据从哪来（沿用旧 3×2 组件的设计：小组件自己【不联网】）
 *   · 取件码：MainActivity 拉完取件码后写 [KEY_WIDGET_CODES]（见 publishWidgetCodes），推给桌面。
 *   · 途中快递：后台 TrackWorker 每轮抓完写 ExpressStore 快照，Worker 里顺手调 [updateAll] 推给桌面。
 *     快照过滤：只要"还在途"且**没有取件码**的件（有码的说明已到驿站待取，归取件码组件）。
 *
 * ## 样式（借鉴南风天气小组件；★ vc338 起只留浅/深两套底 + 自动切换）
 *   每个小组件可单独设「外观模式 + 不透明度」，存 SharedPreferences（键 wg_mat_<key> / wg_alpha_<key>），
 *   App 设置页「小组件设置」里改。外观模式四种：浅色(wmat_frost) / 深色(wmat_glass) /
 *   跟随手机深浅模式 / 自定义时间段 —— 后两种由 [resolvedDark] 在**渲染期**解析出实际深浅。
 *   不透明度走 ImageView.imageAlpha（0~255）——RemoteViews 能远程调用的有限方法之一。
 *   浅色底文字自动换深色，深色底用白色系。到点/系统切换后由下一轮 updateAll（轮询/开 App）推新样子。
 *
 * ## 为什么用 w_bg 这个 ImageView 而不是给根布局设背景
 *   RemoteViews 只能调"参数是 int/基本类型"的方法：圆角渐变背景必须是 drawable 资源，
 *   而「不透明度」要连续可调 —— ImageView.setImageResource(选材质) + setImageAlpha(调透明度)
 *   两个都是 RemoteViews 白名单方法，组合起来正好实现"材质 × 透明度"的全部组合。
 */
object HomeWidgets {

    // ---------------- 数据 / 点击 常量（沿用旧 PickupWidget 的键，不丢老数据） ----------------

    /** 取件码缓存（JSONArray 字符串），MainActivity.publishWidgetCodes 写入 */
    const val KEY_WIDGET_CODES = "widget_codes"
    /** 点取件码小组件 → 打开 App 直接进第 4 栏 */
    const val EXTRA_OPEN_PICKUP = "open_pickup"
    /** 点途中快递小组件 → 打开 App 直接进第 5 栏 */
    const val EXTRA_OPEN_TRANSIT = "open_transit"

    private const val PREFS = "idcode_prefs"

    // ---------------- 样式：外观模式 / 不透明度 ----------------
    //
    // ★ vc338（同志 10-19）：删「渐变卡片」「无背景」，原「磨砂玻璃/深色玻璃」更名「浅色/深色」，
    //   新增两种自动切换（跟随手机深浅模式 / 自定义时间段）。
    //   存储沿用 wg_mat_<key>（老数据不丢），取值空间换成下面的 0..3，读值时一次性迁移：
    //   渐变卡片(0)/深色玻璃(2) → 深色；磨砂玻璃(1)/无背景(3) → 浅色；从未设置过的键 → 默认「跟随手机」。

    const val MODE_LIGHT = 0   // 浅色（原磨砂玻璃 wmat_frost，浅底深字）
    const val MODE_DARK = 1    // 深色（原深色玻璃 wmat_glass，深底白字）
    const val MODE_AUTO = 2    // 跟随手机：系统深色模式开 → 深色，否则浅色
    const val MODE_TIMED = 3   // 自定义时间：wg_dark_start/end_<key> 时段内深色，其余浅色

    /** 4 个小组件的存储键 */
    const val W_P2 = "p2"
    const val W_P4 = "p4"
    const val W_T2 = "t2"
    const val W_T4 = "t4"
    val ALL_KEYS = listOf(W_P2, W_P4, W_T2, W_T4)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读外观模式（首次读值时把旧 0..3 材质一次性迁移成新模式） */
    fun modeOf(ctx: Context, key: String): Int {
        val p = sp(ctx)
        if (!p.getBoolean("wg_mode_v2", false)) {
            for (k in ALL_KEYS) {
                val old = p.getInt("wg_mat_$k", -1)
                if (old >= 0) {
                    p.edit().putInt("wg_mat_$k", if (old == 1 || old == 3) MODE_LIGHT else MODE_DARK).apply()
                }
            }
            p.edit().putBoolean("wg_mode_v2", true).apply()
        }
        return p.getInt("wg_mat_$key", MODE_AUTO)
    }

    fun saveMode(ctx: Context, key: String, v: Int) {
        sp(ctx).edit().putInt("wg_mat_$key", v.coerceIn(0, 3)).apply()
    }

    fun alphaOf(ctx: Context, key: String): Int = sp(ctx).getInt("wg_alpha_$key", 100)
    fun saveAlpha(ctx: Context, key: String, v: Int) {
        sp(ctx).edit().putInt("wg_alpha_$key", v.coerceIn(0, 100)).apply()
    }

    // ---- vc338 自动切换：跟随手机 / 自定义时间 ----

    /** 手机系统当前是否深色模式（与应用主题「跟随系统」同一判定口径：uiMode 低 5 位 == 0x20） */
    fun systemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and 0x30) == 0x20

    /** "22:30" → 1350（分钟数） */
    private fun mins(hhmm: String): Int {
        val p = hhmm.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 跨零点（如 22:00~07:00）分两段判断 —— 与应用主题定时同一套逻辑 */
    private fun inDarkWindow(start: String, end: String): Boolean {
        val cal = java.util.Calendar.getInstance()
        val cur = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = mins(start)
        val e = mins(end)
        if (s == e) return false
        return if (s < e) cur in s until e else (cur >= s || cur < e)
    }

    /** 定时模式的深色时段（每组件各一组；默认 22:00~07:00，与应用主题默认一致） */
    fun darkStartOf(ctx: Context, key: String): String = sp(ctx).getString("wg_dark_start_$key", "22:00") ?: "22:00"
    fun darkEndOf(ctx: Context, key: String): String = sp(ctx).getString("wg_dark_end_$key", "07:00") ?: "07:00"
    fun saveDarkWindow(ctx: Context, key: String, start: String, end: String) {
        sp(ctx).edit().putString("wg_dark_start_$key", start).putString("wg_dark_end_$key", end).apply()
    }

    /** ★ 渲染期解析：这个组件**现在**该用深底(true)还是浅底(false) */
    fun resolvedDark(ctx: Context, key: String): Boolean = when (modeOf(ctx, key)) {
        MODE_DARK -> true
        MODE_AUTO -> systemDark(ctx)
        MODE_TIMED -> inDarkWindow(darkStartOf(ctx, key), darkEndOf(ctx, key))
        else -> false
    }

    /** 浅色底（wmat_frost）前景文字换深色；深色底用白字 */
    fun isLightMat(ctx: Context, key: String): Boolean = !resolvedDark(ctx, key)

    /** 把「外观 + 不透明度」套到布局里的 w_bg（ImageView）上 */
    fun applyStyle(v: RemoteViews, ctx: Context, key: String) {
        v.setImageViewResource(R.id.w_bg, if (resolvedDark(ctx, key)) R.drawable.wmat_glass else R.drawable.wmat_frost)
        val a = (alphaOf(ctx, key) * 255 / 100).coerceIn(0, 255)
        v.setInt(R.id.w_bg, "setImageAlpha", a)
    }

    // ---------------- 自定义图片（vc294，同志 10-16 要求）----------------
    //
    // 规则：**没有物流信息时**，组件铺满显示这张自定义图（把文字全藏掉）；
    // 有物流信息时照常显示物流内容（用材质背景）。
    // 图片在「小组件设置」里选：系统相册挑图 → 自带截取编辑器（MainActivity.widgetCropLayer）
    // 按组件比例截好 → 存 filesDir/wg_img_<key>.png（每个组件各一张）。
    //
    // ★ 为什么渲染时要再裁圆角：w_bg 是 ImageView，fitXY 会把方图直接铺满 —— 组件的四角
    //   圆角全靠背景 drawable 自带，换成 Bitmap 就没了。所以这里预先把四角裁圆再交给 RemoteViews。

    /** 某组件的自定义图文件（每个组件各一张） */
    fun imgFile(ctx: Context, key: String): java.io.File = java.io.File(ctx.filesDir, "wg_img_$key.png")

    fun hasImage(ctx: Context, key: String): Boolean = try {
        imgFile(ctx, key).length() > 0L
    } catch (_: Throwable) {
        false
    }

    fun clearImage(ctx: Context, key: String) {
        try { imgFile(ctx, key).delete() } catch (t: Throwable) { SafeRun.w("HomeWidgets", t) }
    }

    /** 存图：长边压到 640px 再落 PNG（组件就巴掌大，再大白占内存/超 RemoteViews 事务限制） */
    fun saveImage(ctx: Context, key: String, src: Bitmap): Boolean = try {
        val long = maxOf(src.width, src.height)
        val bmp = if (long > 640) {
            val r = 640f / long
            Bitmap.createScaledBitmap(src, (src.width * r).toInt().coerceAtLeast(1), (src.height * r).toInt().coerceAtLeast(1), true)
        } else src
        imgFile(ctx, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        true
    } catch (t: Throwable) {
        android.util.Log.w("HomeWidgets", "保存组件图片失败: ${t.message}")
        false
    }

    /**
     * 渲染用的图：按传入的**组件真实宽高比**做 centerCrop（居中裁满、绝不拉伸）
     * + 四角裁圆。出图控制在 RemoteViews 事务限制内。
     *
     * ★ vc335 拉伸 bug 修复（同志 10-19 报）：旧版硬缩到 400×400 / 640×320（假设 2×2=1:1、
     *   4×2=2:1），但①预览容器是 160×130/280×130 ⇒ 全设备预览横向拉；②小米/红米桌面格子
     *   宽窄高长 ⇒ 桌面纵向拉。现在 aspect 由调用方给真实值（桌面=getAppWidgetOptions 的
     *   MIN_WIDTH/MIN_HEIGHT，预览/截图编辑器=典型格口 110:110、250:110），源图先按比例
     *   居中裁剪再缩放，任何设备上都不变形。
     */
    fun widgetBitmap(ctx: Context, key: String, aspect: Float = 1f): Bitmap? {
        val f = imgFile(ctx, key)
        if (!f.exists()) return null
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            val a = aspect.coerceIn(0.3f, 3.5f)
            // 目标 px：按比例分 配长边 640，RGB_565 省一半体积
            val tw: Int
            val th: Int
            if (a >= 1f) {
                tw = 640
                th = (640 / a).toInt().coerceAtLeast(1)
            } else {
                th = 640
                tw = (640 * a).toInt().coerceAtLeast(1)
            }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= tw) sample *= 2
            val dec = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = android.graphics.BitmapFactory.decodeFile(f.absolutePath, dec) ?: return null
            // ★ centerCrop：源图比例 ≠ 目标比例时，居中裁到目标比例（裁边不变形）
            val rawA = raw.width.toFloat() / raw.height.toFloat()
            val cropped = if (kotlin.math.abs(rawA - a) < 0.01f) raw else run {
                val cw: Int
                val ch: Int
                if (rawA > a) {
                    ch = raw.height
                    cw = (raw.height * a).toInt().coerceAtLeast(2)
                } else {
                    cw = raw.width
                    ch = (raw.width / a).toInt().coerceAtLeast(2)
                }
                val cx = (raw.width - cw) / 2
                val cy = (raw.height - ch) / 2
                Bitmap.createBitmap(raw, cx, cy, cw.coerceAtMost(raw.width), ch.coerceAtMost(raw.height))
            }
            if (cropped != raw) raw.recycle()
            val scaled = Bitmap.createScaledBitmap(cropped, tw, th, true)
            if (scaled != cropped) cropped.recycle()
            // 裁四角圆角（比例沿用 wmat 材质的 18dp/110dp ≈ 0.16）。
            // ★ 输出必须 ARGB_8888：RGB_565 没有透明通道，圆角外没画到的地方默认是黑 ⇒
            //   桌面上四角出现黑边（vc294 真机实测）。源图仍用 565 省体积，输出才转 8888。
            val r = (minOf(tw, th) * 0.16f).coerceAtLeast(8f)
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.shader = android.graphics.BitmapShader(scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(android.graphics.RectF(0f, 0f, tw.toFloat(), th.toFloat()), r, r, paint)
            if (scaled != out) scaled.recycle()
            out
        } catch (t: Throwable) {
            android.util.Log.w("HomeWidgets", "读取组件图片失败: ${t.message}")
            null
        }
    }

    /**
     * 组件的真实宽高比（w/h）：有 widget 实例时用系统给的 MIN_WIDTH/MIN_HEIGHT（dp），
     * 没有就退回典型格口（2×2=110:110=1:1，4×2=250:110≈2.27:1 —— 与 AppWidget 规范口径一致）。
     */
    fun aspectOf(opts: android.os.Bundle?, big: Boolean): Float {
        val defW = if (big) 250 else 110
        val defH = 110
        val w = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW) ?: defW
        val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defH) ?: defH
        if (w <= 0 || h <= 0) return if (big) 250f / 110f else 1f
        return w.toFloat() / h.toFloat()
    }

    // ---------------- 数据读取 ----------------

    /**
     * ★ vc603：清空桌面小组件里的取件码并把组件重画一次。
     * 这是**独立于 PddCodeStore 的第二份数据**（`widget_codes`），
     * 只清主缓存的话，桌面上那个"还有 1 个取件码"会一直挂着（同志实测报的）。
     */
    fun clearCodes(ctx: Context, source: String? = null) {
        try {
            if (source.isNullOrBlank()) {
                sp(ctx).edit().remove(KEY_WIDGET_CODES).apply()
            } else {
                val raw = sp(ctx).getString(KEY_WIDGET_CODES, null)
                if (!raw.isNullOrEmpty()) {
                    val aliases = if (source == "tb") setOf("tb", "cainiao") else setOf(source)
                    val old = JSONArray(raw)
                    val kept = JSONArray()
                    for (i in 0 until old.length()) {
                        val item = old.opt(i)
                        val itemSource = (item as? org.json.JSONObject)?.optString("src").orEmpty()
                        if (itemSource.isBlank() || itemSource !in aliases) kept.put(item)
                    }
                    sp(ctx).edit().putString(KEY_WIDGET_CODES, kept.toString()).apply()
                }
            }
            updateAll(ctx)
        } catch (t: Throwable) { SafeRun.w("HomeWidgets", t) }
    }

    /** 读取新格式的来源标记码，同时兼容旧版裸字符串记录。 */
    fun loadCodes(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY_WIDGET_CODES, null)
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val code = when (item) {
                    is org.json.JSONObject -> item.optString("code")
                    else -> arr.optString(i)
                }
                if (code.isNotEmpty()) out.add(code)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 在途件（vc289b，同志 10-16 反馈：不能只有拼多多 —— 任何数据源有快递都要上组件）：
     * ① transit_cache.json 的 sources（第 5 栏的全源缓存：pdd/jd/mi/sf/tb/manual，菜鸟并入 tb）
     *    —— **只以它为准**，保证组件件数与第 5 栏一致；
     * ② ExpressStore 后台快照【只补动态文本，不新增条目】（vc290b 修正：快照是"合并保留"
     *    机制，历史件永远赖在里面，拿它计数会数出一堆早已完结的陈年旧件 —— 曾数出 17
     *    而第 5 栏只有 1 件）；
     * 过滤：还没到手（isTransport）且没有取件码（有码的归取件码组件）。
     * 排序：已到达的排最前（组件头版位置给"已到驿站"的最有用），其余按原序。
     */
    fun loadTransit(ctx: Context): List<ExpressStore.Item> {
        val out = LinkedHashMap<String, ExpressStore.Item>()
        fun put(it: ExpressStore.Item) {
            if (it.no.isBlank()) return
            if (!ExpressStore.isTransport(it) || it.pickupCode.isNotBlank()) return
            val old = out[it.no]
            when {
                old == null -> out[it.no] = it
                old.latest.isBlank() && it.latest.isNotBlank() -> out[it.no] = it
            }
        }
        // ① 全源缓存（transit_cache.json，与第 5 栏同一份数据；读时与后台同步互斥）
        try {
            synchronized(UiCacheSync.FILE_LOCK) {
                val f = java.io.File(ctx.filesDir, "transit_cache.json")
                if (f.exists()) {
                    val root = org.json.JSONObject(f.readText())
                    // 缓存 24 小时内都认（组件看重"有货"，比第 5 栏的 6h 更宽容）
                    if (System.currentTimeMillis() - root.optLong("ts") < 24L * 3600_000L) {
                        val src = root.optJSONObject("sources")
                        if (src != null) {
                            for (key in src.keys()) {
                                val arr = src.optJSONArray(key) ?: continue
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val no = o.optString("waybillNo").ifBlank { o.optString("orderSn") }
                                    put(
                                        ExpressStore.Item(
                                            no = no,
                                            src = o.optString("src"),
                                            company = o.optString("goodsName"),
                                            statusText = o.optString("statusText"),
                                            latest = o.optString("latest")
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) { SafeRun.w("HomeWidgets", t) }
        // ② 后台快照：只补"缓存里有这单、但没有动态文本"的情况，绝不新增条目（vc290b）
        try {
            for (it in ExpressStore.load(ctx)) {
                val old = out[it.no]
                if (old != null && old.latest.isBlank() &&
                    it.latest.isNotBlank() &&
                    ExpressStore.isTransport(it) && it.pickupCode.isBlank()
                ) out[it.no] = it
            }
        } catch (t: Throwable) { SafeRun.w("HomeWidgets", t) }
        // ③ 已到达的排最前（sortedByDescending 是稳定排序，其余保持原序）
        return out.values.toList()
            .sortedByDescending { ExpressStore.stageOf(it) == ExpressStore.Stage.ARRIVED }
    }

    /**
     * 轨迹文案里的客服电话 / 广告尾巴清洗（与 MainActivity.traceStep 的 vc231 同一套正则）。
     * 组件显示空间比详情页更小，"（如遇物流问题无需找商家/平台，拨打专属电话：…）"
     * 这种尾巴会把真正的物流动态挤没 —— 渲染前一律洗掉。
     * ⚠️ 只删明确是联系/营销的片段，不动地点、驿站名、动作本身；删空则保留原文。
     */
    private val TRACE_ADS = listOf(
        Regex("【[^】]*(?:物流问题|专属热线|专属电话|专属号码|外呼|请放心接听|投诉|致电|联系客服|温馨提示|有事呼叫|无需找商家)[^】]*】"),
        Regex("[（(][^）)]*(?:物流问题|专属热线|专属电话|拨打专属)[^）)]*[)）]"),
        Regex("[,，][^。]*(?:如有疑问|如有取件码|如您未收到|有问题请|请电联|请联系|网点电话|投诉电话)[^。]*。?$"),
        Regex("[（(]\\d{7,12}[)）]")
    )

    fun cleanTrace(raw: String): String {
        var body = raw
        for (ad in TRACE_ADS) body = ad.replace(body, "")
        body = Regex("\\s{2,}").replace(body.trim(), " ")
        return if (body.isEmpty()) raw.trim() else body
    }

    /** 状态词（沿用第 5 栏的阶段判定，口径一致） */
    fun statusWord(item: ExpressStore.Item): String = when (ExpressStore.stageOf(item)) {
        ExpressStore.Stage.DELIVERING -> "派送中"
        ExpressStore.Stage.ARRIVED -> "已到达"
        else -> "运输中"
    }

    // ---------------- 刷新 ----------------

    /** App 侧任何数据变化后调它：4 个组件哪个在桌面上就刷哪个（带日志：验证"轮询→组件"链路用） */
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        updateProvider(ctx, mgr, PickupWidget2x2::class.java) { c, m, id -> renderPickup(c, m, id, cols = 2, rows = 2) }
        updateProvider(ctx, mgr, PickupWidget4x2::class.java) { c, m, id -> renderPickup(c, m, id, cols = 4, rows = 2) }
        updateProvider(ctx, mgr, TransitWidget2x2::class.java) { c, m, id -> renderTransit(c, m, id, big = false) }
        updateProvider(ctx, mgr, TransitWidget4x2::class.java) { c, m, id -> renderTransit(c, m, id, big = true) }
        // vc296：鲸鱼娘组件一起吃这份数据（台词/信息区跟着取件码与在途件走）
        try {
            WhaleWidgets.updateAll(ctx)
        } catch (t: Throwable) { SafeRun.w("HomeWidgets", t) }
        android.util.Log.i("HomeWidgets", "updateAll: 在途=${loadTransit(ctx).size} 码=${loadCodes(ctx).size}")
    }

    private inline fun updateProvider(
        ctx: Context, mgr: AppWidgetManager,
        cls: Class<out AppWidgetProvider>,
        render: (Context, AppWidgetManager, Int) -> Unit
    ) {
        try {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            for (id in ids) render(ctx, mgr, id)
        } catch (t: Throwable) { SafeRun.w("HomeWidgets", t) }
    }

    // ---------------- 取件码组件渲染 ----------------

    /**
     * 按【格子实际尺寸】和码长算字号（沿用旧 3×2 组件的算法）。
     * 取件码 4~8 位不固定，写死字号必然有长码被截断（显示不全就等于没用）。
     * monospace 数字每字符约占 0.62em，这里用 0.66 留余量；高度方向占格高的一半。
     */
    private fun sizeFor(len: Int, cellW: Float, cellH: Float): Float {
        if (len <= 0) return 13f
        val byWidth = cellW / (len * 0.66f)
        val byHeight = cellH * 0.5f
        return minOf(byWidth, byHeight).coerceIn(9f, 24f)
    }

    fun renderPickup(ctx: Context, mgr: AppWidgetManager, id: Int, cols: Int, rows: Int) {
        val big = cols * rows > 4
        val key = if (big) W_P4 else W_P2
        val v = RemoteViews(ctx.packageName, if (big) R.layout.widget_pickup_4x2 else R.layout.widget_pickup_2x2)
        val light: Boolean

        // 组件实例的真实尺寸（dp）——自定义图按它出图（vc335 拉伸修复），格子字号也用它
        val opts = mgr.getAppWidgetOptions(id)
        val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (big) 250 else 110)
            ?: if (big) 250 else 110
        val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) ?: 110

        // vc289b（同志 10-16）：2×2 最多显示 3 个码；超过时标题改「更多×X」（X=全部-3）
        val all = loadCodes(ctx)
        val max = if (big) 8 else 3
        val codes = all.take(max)
        // ★ vc294：没有待取快递且设了自定义图 ⇒ 图铺满组件，文字全藏
        //   ★ vc335：按组件真实宽高比出图（小米/红米格子比例≠1:1 也不变形）
        if (codes.isEmpty() && hasImage(ctx, key)) {
            v.setImageViewBitmap(R.id.w_bg, widgetBitmap(ctx, key, aspectOf(opts, big)))
            v.setViewVisibility(R.id.w_title, View.GONE)
            v.setViewVisibility(R.id.widget_empty, View.GONE)
            v.setViewVisibility(R.id.widget_grid, View.GONE)
            v.setOnClickPendingIntent(R.id.widget_root, mainIntent(ctx, EXTRA_OPEN_PICKUP))
            mgr.updateAppWidget(id, v)
            return
        }
        applyStyle(v, ctx, key)
        light = isLightMat(ctx, key)
        val title = if (!big && all.size > 3) "更多×${all.size - 3}" else "取件码"
        v.setTextViewText(R.id.w_title, "📦 $title")
        v.setTextColor(R.id.w_title, if (light) 0xB3000000.toInt() else 0xB3FFFFFF.toInt())
        v.setTextColor(R.id.widget_empty, if (light) 0x80000000.toInt() else 0x80FFFFFF.toInt())

        val cellIds = if (big) PICKUP_CELLS_8 else PICKUP_CELLS_4
        if (codes.isEmpty()) {
            v.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            v.setViewVisibility(R.id.widget_grid, View.GONE)
        } else {
            v.setViewVisibility(R.id.widget_empty, View.GONE)
            v.setViewVisibility(R.id.widget_grid, View.VISIBLE)

            // 算每格可用宽高 → 字号
            val cellW = (wDp - ROOT_PADDING_DP * 2) / cols - CELL_MARGIN_DP * 2
            val cellH = (hDp - ROOT_PADDING_DP * 2) / rows - CELL_MARGIN_DP * 2

            for (i in cellIds.indices) {
                val code = codes.getOrNull(i)
                if (code.isNullOrEmpty()) {
                    v.setViewVisibility(cellIds[i], View.INVISIBLE)
                } else {
                    v.setViewVisibility(cellIds[i], View.VISIBLE)
                    v.setTextViewText(cellIds[i], code)
                    v.setTextColor(cellIds[i], if (light) 0xE6000000.toInt() else 0xFFFFFFFF.toInt())
                    v.setTextViewTextSize(
                        cellIds[i], TypedValue.COMPLEX_UNIT_SP,
                        sizeFor(code.length, cellW, cellH)
                    )
                }
            }
        }

        v.setOnClickPendingIntent(R.id.widget_root, mainIntent(ctx, EXTRA_OPEN_PICKUP))
        mgr.updateAppWidget(id, v)
    }

    // ---------------- 途中快递组件渲染 ----------------

    fun renderTransit(ctx: Context, mgr: AppWidgetManager, id: Int, big: Boolean) {
        val key = if (big) W_T4 else W_T2
        val v = RemoteViews(ctx.packageName, if (big) R.layout.widget_transit_4x2 else R.layout.widget_transit_2x2)

        val items = loadTransit(ctx)
        // ★ vc294：没有在途快递且设了自定义图 ⇒ 图铺满组件，文字全藏
        //   ★ vc335：按组件真实宽高比出图（小米/红米格子比例≠2:1 也不变形）
        if (items.isEmpty() && hasImage(ctx, key)) {
            v.setImageViewBitmap(R.id.w_bg, widgetBitmap(ctx, key, aspectOf(mgr.getAppWidgetOptions(id), big)))
            v.setViewVisibility(R.id.w_status, View.GONE)
            v.setViewVisibility(R.id.w_tag, View.GONE)
            v.setViewVisibility(R.id.w_trace, View.GONE)
            v.setViewVisibility(R.id.w_pill, View.GONE)
            if (big) v.setViewVisibility(R.id.w_trace2, View.GONE)
            v.setOnClickPendingIntent(R.id.widget_root, mainIntent(ctx, EXTRA_OPEN_TRANSIT))
            mgr.updateAppWidget(id, v)
            return
        }
        applyStyle(v, ctx, key)
        val light = isLightMat(ctx, key)

        val first = items.firstOrNull()

        // 主行：状态词 + 最新动态（vc289b：承运商 chip 按同志要求删除）
        if (first == null) {
            v.setTextViewText(R.id.w_status, "暂无在途")
            v.setViewVisibility(R.id.w_tag, View.GONE)
            // ★ vc338（同志 10-19）：空态写「暂无物流信息」，别再放宣传语
            v.setTextViewText(R.id.w_trace, "暂无物流信息")
            v.setViewVisibility(R.id.w_pill, View.GONE)
            if (big) v.setViewVisibility(R.id.w_trace2, View.GONE)
        } else {
            v.setTextViewText(R.id.w_status, statusWord(first))
            v.setViewVisibility(R.id.w_tag, View.VISIBLE)
            v.setTextViewText(R.id.w_trace, cleanTrace(first.latest).ifBlank { "暂无最新动态" })
            // 胶囊规则（vc289b）：1 件不显示；≥2 件才显示"全部快递 ×N"
            if (items.size >= 2) {
                v.setViewVisibility(R.id.w_pill, View.VISIBLE)
                v.setTextViewText(R.id.w_pill, "📦 全部快递 ×${items.size}")
            } else {
                v.setViewVisibility(R.id.w_pill, View.GONE)
            }
            if (big) {
                val second = items.getOrNull(1)
                if (second == null) {
                    v.setViewVisibility(R.id.w_trace2, View.GONE)
                } else {
                    v.setViewVisibility(R.id.w_trace2, View.VISIBLE)
                    v.setTextViewText(R.id.w_trace2, cleanTrace(second.latest).ifBlank { "暂无最新动态" })
                }
            }
        }

        // 配色（磨砂玻璃=浅底黑字，其余=深底白字）
        val primary = if (light) 0xE6000000.toInt() else 0xFFFFFFFF.toInt()
        val secondary = if (light) 0x99000000.toInt() else 0xB3FFFFFF.toInt()
        v.setTextColor(R.id.w_status, primary)
        v.setTextColor(R.id.w_trace, primary)
        if (big) v.setTextColor(R.id.w_trace2, secondary)
        v.setTextColor(R.id.w_tag, 0xFFFFFFFF.toInt())
        v.setTextColor(R.id.w_pill, if (light) 0xCC000000.toInt() else 0xCCFFFFFF.toInt())

        v.setOnClickPendingIntent(R.id.widget_root, mainIntent(ctx, EXTRA_OPEN_TRANSIT))
        mgr.updateAppWidget(id, v)
    }

    // ---------------- 公共 ----------------

    /** 点小组件 → 打开 App（带 extra：第 4 栏 / 第 5 栏） */
    private fun mainIntent(ctx: Context, extra: String): PendingIntent = PendingIntent.getActivity(
        ctx, if (extra == EXTRA_OPEN_PICKUP) 1 else 2,
        Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(extra, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 布局里的内边距与每格 margin（dp），算可用格子尺寸时要减掉 */
    private const val ROOT_PADDING_DP = 5f
    private const val CELL_MARGIN_DP = 2f

    /** 取件码格子 id：行序从左到右、从上到下 */
    private val PICKUP_CELLS_4 = intArrayOf(
        R.id.w_code_1, R.id.w_code_2, R.id.w_code_3, R.id.w_code_4
    )
    private val PICKUP_CELLS_8 = intArrayOf(
        R.id.w_code_1, R.id.w_code_2, R.id.w_code_3, R.id.w_code_4,
        R.id.w_code_5, R.id.w_code_6, R.id.w_code_7, R.id.w_code_8
    )
}

/**
 * 取件码小组件 2×2（2 列 × 2 行，最多 4 个码）。渲染逻辑统一在 [HomeWidgets.renderPickup]。
 */
class PickupWidget2x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) HomeWidgets.renderPickup(context, mgr, id, cols = 2, rows = 2)
    }
    // ★ vc335：组件在桌面上被拖动/调整大小时，按新尺寸重出图（自定义图跟随真实比例）
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        HomeWidgets.renderPickup(context, mgr, id, cols = 2, rows = 2)
    }
}

/** 取件码小组件 4×2（4 列 × 2 行，最多 8 个码） */
class PickupWidget4x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) HomeWidgets.renderPickup(context, mgr, id, cols = 4, rows = 2)
    }
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        HomeWidgets.renderPickup(context, mgr, id, cols = 4, rows = 2)
    }
}

/** 途中快递小组件 2×2：状态词 + 承运商 + 最新一条动态 + 「全部快递 ×N」胶囊 */
class TransitWidget2x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) HomeWidgets.renderTransit(context, mgr, id, big = false)
    }
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        HomeWidgets.renderTransit(context, mgr, id, big = false)
    }
}

/** 途中快递小组件 4×2：同上，中部可多显示一条动态 */
class TransitWidget4x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) HomeWidgets.renderTransit(context, mgr, id, big = true)
    }
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        HomeWidgets.renderTransit(context, mgr, id, big = true)
    }
}
