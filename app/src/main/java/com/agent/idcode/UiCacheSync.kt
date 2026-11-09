package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ vc281：后台轮询结果 → 第 4/5 栏持久缓存 的同步桥。
 *
 * ## 解决什么
 * [TrackWorker] 每 30 分钟在后台抓一轮，但结果只写进 ExpressStore 快照（发通知用）。
 * 第 4/5 栏 UI 读的是**各自的缓存**（PddCodeStore / transit_cache.json），两条链路完全不通：
 *  - 打开 App 看到的永远是"上一次手动刷新"留下的结果；
 *  - 第 5 栏又只在「全部源回调到齐」时才落盘（中途退出页面回调整体作废），缓存常常
 *    缺最近一轮 ⇒ 观感 = "过一阵再打开，上次刷出来的数据没了，要等重新抓才出数据"。
 *
 * ## 做法（同志 2026-10-16 定：轮询完就把数据喂给第 4/5 栏，打开即最新）
 * 1. **第 5 栏**：fresh 按源转成卡片 JSON，替换 transit_cache.json 里对应源的数组
 *    （cainiao → tb 键，与 UI 的淘宝在途同一格）。某源本轮**没抓到就不动它的格**
 *    （对齐 vc270c"失败绝不动缓存"口径）。manual 特殊：按单号 upsert，保留旧卡
 *    （"查不到"卡不能被后台的成功结果洗掉）。过滤口径与 UI parseInTransit 一致：
 *    只留**在途**件（ExpressStore.isTransport），有取件码的（已到驿站待取）归第 4 栏。
 * 2. **第 4 栏**：fresh 里**有取件码**的件写进 PddCodeStore（常态显示的秒显数据源）。
 *    只写 pdd/cainiao/jd 三个白名单源（缓存键前缀的既有口径，sf/mi/tb 的码继续走 UI 原路）。
 *    已打「已取件」标记的条目跳过不覆盖；同源同码冲突疑似抽取污染，照 UI vc270g 口径跳过。
 *
 * ## 线程
 * [TrackWorker] 在 WorkManager 线程跑，transit_cache.json 同时会被主线程读写
 * （saveTransitCache / loadTransitCache / saveTransitTraces / loadTransitTraceCache）⇒
 * 本类与 MainActivity 全部走 [FILE_LOCK] 同一把锁，读→改→写全程持锁，杜绝交错写坏 JSON。
 */
object UiCacheSync {

    private const val TAG = "UiCacheSync"

    /** transit_cache.json 读写互斥锁（MainActivity 侧同用这一把） */
    val FILE_LOCK = Any()

    /** TrackWorker 抓完落盘快照后调用。源状态缺失时不改该源持久缓存。 */
    fun syncFromWorker(
        ctx: Context,
        fresh: List<ExpressStore.Item>,
        sources: Map<String, TrackFetcher.SourceResult> = emptyMap()
    ) {
        try {
            syncTransitCache(ctx, fresh, sources)
        } catch (e: Throwable) {
            Log.w(TAG, "第5栏缓存同步失败: ${e.message}")
        }
        try {
            syncPickupCache(ctx, fresh, sources)
        } catch (e: Throwable) {
            Log.w(TAG, "取件码缓存同步失败: ${e.message}")
        }
        // ★ vc285（A/B 排障）：淘宝身份码白屏复现中，缓存清扫暂时停用 —— 待定位后决定去留
        // try {
        //     sweepStaleWebCache(ctx)
        // } catch (e: Throwable) {
        //     Log.w(TAG, "陈旧缓存清扫失败: ${e.message}")
        // }
    }

    // ---------------- 缓存瘦身（vc283）----------------

    /**
     * 兜底清扫：删除 cacheDir 里超过 48 小时没被碰过的文件。
     * 背景：系统"设置-存储"显示的「缓存」就是 cacheDir。各抓取通道退出时已
     * clearCache(true)（见各 Channel），但主界面的常驻 WebView（第 1/3 栏官方页面）
     * 和 WebView 的 Code Cache（V8 编译产物）还会慢慢积累 —— 这里按时间兜底。
     * 只删「缓存目录 + 48h 未访问」，不动 filesDir / 数据库 / 登录 cookie，零功能影响。
     * WebView 正在被主界面使用时，48h 阈值保证活跃文件不会被误删。
     */
    private fun sweepStaleWebCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 48L * 3600_000L
        var files = 0
        var bytes = 0L
        val roots = listOf(
            java.io.File(ctx.cacheDir, "WebView"),
            java.io.File(ctx.cacheDir, "Code Cache"),
        )
        fun walk(d: java.io.File) {
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    walk(f)
                    // 空目录顺手收掉（失败无所谓，下轮再试）
                    if (f.lastModified() < cutoff) try { f.delete() } catch (t: Throwable) { SafeRun.w("UiCacheSync", t) }
                } else if (f.lastModified() < cutoff) {
                    val len = f.length()
                    if (f.delete()) { files++; bytes += len }
                }
            }
        }
        for (r in roots) walk(r)
        if (files > 0) Log.i(TAG, "缓存清扫：删除 $files 个 48h 未用文件，回收 ${bytes / 1024}KB")
    }

    // ---------------- 第 5 栏：transit_cache.json ----------------

    /** 缓存格键（sources 里的 key）。cainiao 并进 tb —— UI 第 5 栏没有独立"菜鸟"格 */
    private fun transitCacheKeyOf(src: String): String? = when (src) {
        "pdd", "jd", "mi", "tb", "manual" -> src
        "cainiao" -> "tb"
        else -> null
    }

    /**
     * ★ vc701：**只能在 UI 侧产生的富字段** —— 后台快照（[ExpressStore.Item]）里根本没有它们：
     *  商品名、真运单号、物流页直链、订单列表自带的 ETA 提示、完整轨迹、收件人手机号。
     *  后台同步整格替换时如果不继承，每跑一轮后台，卡上的商品标签 / 运单号 / 预计到达就全没了
     *  （2026-11-09 实测：12:42 后台一轮之后，transit_cache 里 realWaybill / expressUrl / goodsName 全空）。
     */
    private val RICH_FIELDS = listOf(
        "goodsName", "realWaybill", "expressUrl", "etaHint", "traceLines", "phone"
    )

    /** 按单号找到旧卡，把新卡缺的富字段继承过来（新卡没有 = 空串；旧卡有就留） */
    private fun richMerge(oldArr: org.json.JSONArray?, fresh: JSONObject): JSONObject {
        val key = fresh.optString("waybillNo").ifEmpty { fresh.optString("orderSn") }
        if (oldArr == null || key.isEmpty()) return fresh
        var old: JSONObject? = null
        for (i in 0 until oldArr.length()) {
            val o = oldArr.optJSONObject(i) ?: continue
            if (o.optString("waybillNo").ifEmpty { o.optString("orderSn") } == key) {
                old = o
                break
            }
        }
        val prev = old ?: return fresh
        for (f in RICH_FIELDS) {
            if (fresh.optString(f).isBlank() && prev.optString(f).isNotBlank()) {
                fresh.put(f, prev.optString(f))
            }
        }
        return fresh
    }

    /** 快照条目 → 第 5 栏卡片。字段名与 renderInTransit 的消费口径一致 */
    private fun cardOf(it: ExpressStore.Item): JSONObject = JSONObject().apply {
        put("src", if (it.src == "cainiao") "tb" else it.src)
        // 后台快照没有商品名：有快递公司名用公司名，否则留空交给渲染层兜底
        // （"快递包裹"/"手动添加的单号"，与 UI 成功卡 goodsName="" 的行为一致）
        put("goodsName", if (it.src == "manual") "" else it.company)
        put("statusText", it.statusText)
        put("latest", it.latest)
        put("waybillNo", it.no)
        if (it.src == "pdd") put("orderSn", it.no)   // 第5栏拼多多卡身份键=订单号（与 UI 同口径）
    }

    private fun syncTransitCache(
        ctx: Context, fresh: List<ExpressStore.Item>, sources: Map<String, TrackFetcher.SourceResult>
    ) {
        val sourceOfKey = mapOf("pdd" to "pdd", "jd" to "jd", "mi" to "mi", "tb" to "cainiao", "manual" to "manual")
        val bySrc = LinkedHashMap<String, MutableList<ExpressStore.Item>>()
        val sourceItems = if (sources.isEmpty()) fresh else sources.values.flatMap { it.items }
        for (it in sourceItems) {
            val k = transitCacheKeyOf(it.src) ?: continue
            bySrc.getOrPut(k) { mutableListOf() }.add(it)
        }
        val updatable = LinkedHashSet<String>()
        for ((key, source) in sourceOfKey) {
            val result = sources[source] ?: continue
            if (result.transitOk) updatable.add(key)
        }
        if (sources.isEmpty()) updatable.addAll(bySrc.keys)
        if (updatable.isEmpty()) return
        synchronized(FILE_LOCK) {
            val f = java.io.File(ctx.filesDir, "transit_cache.json")
            val root = if (f.exists()) {
                try {
                    JSONObject(f.readText())
                } catch (t: Throwable) {
                    JSONObject()
                }
            } else JSONObject()
            val src = root.optJSONObject("sources") ?: JSONObject()
            val summary = ArrayList<String>()
            for (key in updatable) {
                val items = bySrc[key].orEmpty()
                // 与 UI parseInTransit 同口径：只留在途件；已到驿站待取（有码）的归第 4 栏
                val keep = items.filter { ExpressStore.isTransport(it) && it.pickupCode.isBlank() }
                if (key == "manual") {
                    // manual 按单号 upsert：保留旧卡（含"查不到"卡），同单号用后台结果覆盖
                    val old = src.optJSONArray(key) ?: JSONArray()
                    val merged = JSONArray()
                    val seen = HashSet<String>()
                    for (i in 0 until old.length()) {
                        val o = old.optJSONObject(i) ?: continue
                        val no = o.optString("waybillNo")
                        val rep = keep.firstOrNull { r -> r.no == no }
                        if (rep != null) {
                            merged.put(cardOf(rep)); seen.add(no)
                        } else {
                            merged.put(o)
                        }
                    }
                    for (r in keep) if (r.no !in seen) merged.put(cardOf(r))
                    src.put(key, merged)
                } else {
                    // 平台源整格替换（该源有返回才动，与 UI"成功才赋值"同口径）；
                    // 过滤后为空 = 服务端确实没有在途件 ⇒ 清空这格（旧卡不赖着）
                    val oldArr = src.optJSONArray(key)
                    val arr = JSONArray()
                    // ★ vc701：继承旧卡的富字段（商品名 / 真运单号 / 直链 / ETA / 轨迹）
                    for (r in keep) arr.put(richMerge(oldArr, cardOf(r)))
                    src.put(key, arr)
                }
                summary.add("$key=${keep.size}/${items.size}")
            }
            root.put("sources", src)
            root.put("ts", System.currentTimeMillis())
            f.writeText(root.toString())
            Log.i(TAG, "第5栏缓存已同步：${summary.joinToString(" | ")}")
            ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE).edit().putString(
                "last_transit_time",
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date())
            ).apply()
        }
    }

    // ---------------- 第 4 栏：PddCodeStore ----------------

    private fun syncPickupCache(
        ctx: Context,
        fresh: List<ExpressStore.Item>,
        sources: Map<String, TrackFetcher.SourceResult>
    ) {
        val whitelist = setOf("pdd", "cainiao", "jd")
        val cache = PddCodeStore.all(ctx)
        val sourceItems = if (sources.isEmpty()) fresh else sources.values.flatMap { it.items }
        var put = 0
        var skipPicked = 0
        var skipClash = 0
        for (item in sourceItems) {
            if (item.pickupCode.isBlank()) continue
            if (item.src !in whitelist) continue
            val sourceKey = if (item.src == "cainiao") "cainiao" else item.src
            val sourceResult = sources[sourceKey]
            if (sources.isNotEmpty() && (sourceResult == null || !sourceResult.pickupOk)) continue
            val key = "${item.src}:${item.no}"
            val old = cache[key]
            // 已打「已取件」标记的条目不覆盖 —— 标记是"这单取过了"的唯一凭据
            if (old != null && old.pickedAt != 0L) {
                skipPicked++
                continue
            }
            // 同源同码冲突 = 抽取污染（UI 侧 vc270g 同款守护）：别的单已持有同码 ⇒ 不写
            //
            // ★★ vc528：**同码不等于污染**！现实里「同一驿站取两件，两件的取件码完全一样」
            //   是常见情况（同一货架/同一批入站）。旧规则只要同源同码就丢，
            //   导致后台明明抓到了，缓存却永远写不进去 ⇒ 前台"0 个待取"。
            //   真机实证（2026-10-29 1117 日志）：
            //     `UiCacheSync 取件码缓存同步：写入=0 已取件保护=0 同码冲突跳过=1`
            //   现在改成：**只有"同码 + 驿站不同"才判污染**；驿站任一为空则不判（无法证明污染）。
            val clash = cache.entries.any { e ->
                e.key != key && e.value.src == item.src && e.value.code == item.pickupCode &&
                        e.value.station.isNotBlank() && item.stationText.isNotBlank() &&
                        e.value.station.trim() != item.stationText.trim()
            }
            if (clash) {
                skipClash++
                Log.i(TAG, "同码冲突跳过（站名不同）：$key code=${item.pickupCode} " +
                        "新站=${item.stationText.take(20)} 已有=${cache.entries.first { e ->
                            e.key != key && e.value.src == item.src && e.value.code == item.pickupCode
                        }.value.station.take(20)}")
                continue
            }
            val nc = PddCodeStore.Cached(
                src = item.src,
                code = item.pickupCode,
                station = item.stationText.take(60),
                waybill = item.no,
                latest = item.latest.take(220),
                ts = System.currentTimeMillis(),
                pickedAt = old?.pickedAt ?: 0L
            )
            if (old == null || old.code != nc.code || old.station != nc.station ||
                old.latest != nc.latest || old.waybill != nc.waybill
            ) {
                PddCodeStore.put(ctx, key, nc)
                cache[key] = nc
                put++
            }
        }
        if (put > 0 || skipPicked > 0 || skipClash > 0) {
            Log.i(TAG, "取件码缓存同步：写入=$put 已取件保护=$skipPicked 同码冲突跳过=$skipClash")
        }
    }
}
