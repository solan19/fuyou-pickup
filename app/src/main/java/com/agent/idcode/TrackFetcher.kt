package com.agent.idcode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * 后台抓取入口：**只抓「被跟踪单号所属的那些源」**，不做全量六源同步。
 *
 * ## ★ 本文件只放「不需要 Activity」的源
 * [TrackWorker] 跑在 WorkManager 起的进程里，**没有 Activity**。
 * 凡是签名里要 `Activity` 的通道（小米 `MiShopChannel`、
 * 拼多多 `PddChannel.fetch`）都不能在这儿直接调 —— 那是 Phase 2 的活（无窗口 WebView）。
 *
 * ## 当前已接入（纯 HTTP，Worker 里可直接跑）
 *  - **拼多多** `PddNative.scan`（原生 HTTP，不走 WebView；UI 侧没用它，正好给后台用）
 *  - **快递100 / 手动单号** `Kd100Channel.detectCompany` + `queryTrace`
 *
 * ## Phase 2（待做，无窗口 WebView，照抄参考项目手法）
 * 淘宝菜鸟 / 京东 / 小米商城+有品。关键三点：
 *   ① `WebView` 必须在**主线程**建（Worker 的 IO 线程没有 Looper 会崩）
 *   ② 用 `Context` 建、**从不 addView**，不挂窗口照样跑 JS
 *   ③ 用干净的 Chrome 移动版 UA（默认 UA 带 `wv)` 标记，容易被风控认出来）
 *
 * ## 约定
 * - 返回**尽可能多的** Item；某个源失败不影响其它源（各自 try/catch + 记日志）。
 * - 返回空列表 = 本次没拿到数据（正常情况，不要抛异常触发 retry）。
 * - 抛异常 = 整体失败（[TrackWorker] 会 retry）。
 */
object TrackFetcher {

    private const val TAG = "TrackFetcher"

    /** 单个数据源的结果状态。空列表只有在 ok=true 时才代表该源确实为空。 */
    data class SourceResult(
        val source: String,
        val items: List<ExpressStore.Item>,
        val ok: Boolean,
        val partial: Boolean = false,
        val error: String? = null,
        val pickupOk: Boolean = ok,
        val transitOk: Boolean = ok
    )

    /** 一轮后台抓取的结果；保留合并列表，另带各源状态供缓存层保护旧数据。 */
    data class FetchResult(
        val items: List<ExpressStore.Item>,
        val sources: Map<String, SourceResult>
    ) {
        fun source(key: String): SourceResult? = sources[key]
    }

    /** 手动单号的 SP 键（与 MainActivity 里那份一致：JSONArray，元素 {no, phone}） */
    private const val KEY_MANUAL = "manual_waybills"

    /**
     * ★ 同一时刻只允许一轮抓取。
     * 小米那条用「无窗口 WebView」，通道内部有 `running` 标志：第二轮进来会直接返回
     * "正在抓取中"，白跑一趟还拿不到数据。周期任务和一次性任务重叠时必然会撞，
     * 所以这里加一道锁（参考项目 SyncEngine 也是这么做的）。
     */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun fetch(ctx: Context, tracked: Set<String>): List<ExpressStore.Item> =
        fetchDetailed(ctx, tracked).items

    suspend fun fetchDetailed(ctx: Context, tracked: Set<String>): FetchResult =
        withContext(Dispatchers.IO) {
            if (!mutex.tryLock()) {
                Log.i(TAG, "上一轮还没抓完，本次跳过（避免并发撞小米的 running 标志）")
                return@withContext FetchResult(emptyList(), emptyMap())
            }
            try {
                doFetchDetailed(ctx)
            } finally {
                mutex.unlock()
            }
        }

    private suspend fun doFetchDetailed(ctx: Context): FetchResult = withContext(Dispatchers.IO) {
        // ★ 伪造数据源（vc262 端到端测试专用）：有计划就直接返回，跳过所有真实抓取
        //   （不花快递100 的钱、不碰拼多多风控）。详见 FakeTrack.kt 头注。
        FakeTrack.next(ctx)?.let { fake ->
            Log.i(TAG, "走伪造数据源：${fake.no}「${fake.statusText}」")
            return@withContext FetchResult(listOf(fake), emptyMap())
        }

        val sourceResults = LinkedHashMap<String, SourceResult>()
        val out = ArrayList<ExpressStore.Item>()

        suspend fun runSource(
            key: String, label: String, startedAt: Long = System.currentTimeMillis(),
            block: suspend () -> SourceResult
        ) {
            val result = try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "源「$label」抓取失败: ${e.message}")
                SourceResult(key, emptyList(), false, error = e.message ?: "抓取异常")
            }
            sourceResults[key] = result
            out += result.items
            Log.i(TAG, "源[$label] 状态=${if (result.ok) if (result.partial) "部分" else "成功" else "失败"} " +
                    "${result.items.size} 条（${System.currentTimeMillis() - startedAt}ms）${result.error?.let { " err=$it" } ?: ""}")
        }

        val t0 = System.currentTimeMillis()
        runSource("cainiao", "淘宝/菜鸟", t0) { fetchCaiNiaoResult(ctx) }
        runSource("jd", "京东") { fetchJdResult(ctx) }
        runSource("pdd", "拼多多") { fetchPddResult(ctx) }
        runSource("manual", "快递100/手动") { fetchManualResult(ctx) }
        runSource("mi", "小米") { fetchMiResult(ctx) }

        val merged = dedupItems(out)
        Log.i(TAG, "抓取完成 ${merged.size} 条（各源合计 ${out.size}）")
        FetchResult(merged, sourceResults)
    }

    /**
     * ★★ 跨源去重 —— 原来那句 `distinctBy { it.no }` 是**无效的**。
     *
     * `Item.no` 在各源里含义不同：菜鸟是**运单号**，而**拼多多是订单号 `orderSn`**
     * （运单号要另开详情页才抠得到，常常是空的）⇒ 同一个件在两个源里 `no` 永远不等，
     * 直接 distinctBy 一条都去不掉。表现就是：同一个快递在「在途快递」里出现两张卡、
     * 同一个物流动态触发两条通知。判据与 MainActivity.dedupPkgs 保持一致：
     *   ① 运单号归一化后相同；② 都没有运单号时 取件码+驿站 相同。
     */
    private fun normNo(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase()

    private fun itemScore(it: ExpressStore.Item): Int {
        var s = 0
        if (it.pickupCode.isNotBlank()) s += 8
        if (it.company.isNotBlank()) s += 2
        if (it.latest.isNotBlank()) s += 2
        if (it.statusText.isNotBlank()) s += 1
        return s
    }

    private fun dedupItems(list: List<ExpressStore.Item>): List<ExpressStore.Item> {
        val byKey = LinkedHashMap<String, ExpressStore.Item>()
        for (it in list) {
            val key = when {
                it.no.isNotBlank() -> "W:" + normNo(it.no)
                it.pickupCode.isNotBlank() && it.stationText.isNotBlank() ->
                    "C:" + normNo(it.pickupCode) + ":" + it.stationText.take(20)
                else -> "U:${System.identityHashCode(it)}"
            }
            val prev = byKey[key]
            if (prev == null) {
                byKey[key] = it
                continue
            }
            val (keep, drop) = if (itemScore(it) > itemScore(prev)) it to prev else prev to it
            byKey[key] = keep.copy(
                pickupCode = keep.pickupCode.ifBlank { drop.pickupCode },
                latest = keep.latest.ifBlank { drop.latest },
                statusText = keep.statusText.ifBlank { drop.statusText },
                stationText = keep.stationText.ifBlank { drop.stationText },
                company = keep.company.ifBlank { drop.company }
            )
        }
        val merged = list.size - byKey.size
        if (merged > 0) Log.i(TAG, "跨源去重 ${list.size} → ${byKey.size}（合并 $merged 条）")
        return ArrayList(byKey.values)
    }

    /** ★ 吞掉单个源的异常：不能因为某个源超时，就把别家的数据一起丢了 */
    // ★ 必须 suspend：拼多多那条要走 enrichPddTraces（suspend），非 suspend 的 lambda 调不了
    private suspend inline fun guarded(
        name: String, block: suspend () -> List<ExpressStore.Item>
    ): List<ExpressStore.Item> =
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "源「$name」抓取失败: ${e.message}")
            emptyList()
        }

    // ---------------- 淘宝 / 菜鸟（隐形宿主页 + lib-mtop 现算签名）----------------

    /**
     * 淘宝/菜鸟：走 `CaiNiaoChannel` —— 隐形 WebView 加载官方驿站首页，
     * 在页面上下文里调 mtop（签名由页面 lib-mtop 现算），JSON 经 JS 桥回来。
     *
     * ★ `CaiNiaoChannel.fetch` 内部已经把活丢到主线程（WebView 只能在主线程建/操作），
     *   这里直接调即可，不用再套一层 Handler。
     */
    private suspend fun fetchCaiNiao(ctx: Context): List<ExpressStore.Item> = fetchCaiNiaoResult(ctx).items

    private suspend fun fetchCaiNiaoResult(ctx: Context): SourceResult {
        if (!TbChannel.isLoggedIn()) {
            Log.i(TAG, "淘宝未登录，跳过")
            return SourceResult("cainiao", emptyList(), false, error = "未登录")
        }
        val res = suspendCancellableCoroutine { cont ->
            CaiNiaoChannel.fetch(ctx) { r -> if (cont.isActive) cont.resume(r) }
        }
        Log.i(TAG, "菜鸟 ${res.pkgs.size} 条 err=${res.err} needLogin=${res.needLogin}")
        val items = res.pkgs.mapNotNull { ExpressStore.fromPkg(it) }
        val ok = res.err == null && !res.needLogin
        return SourceResult("cainiao", items, ok, error = res.err ?: if (res.needLogin) "需要登录" else null)
    }

    // ---------------- 京东（无窗口 WebView，JSONP 改写旁听）----------------

    /**
     * 京东：`JdChannel.fetch` 内部自己建 WebView，所以**必须从主线程调** ——
     * 这里用 `Handler(mainLooper).post` 丢回去（跟小米那条同样的处理）。
     * h5st 签名不用我们算：页面自己的 SDK 会算，我们只改写 JSONP callback 旁听响应。
     */
    private suspend fun fetchJd(ctx: Context): List<ExpressStore.Item> = fetchJdResult(ctx).items

    private suspend fun fetchJdResult(ctx: Context): SourceResult {
        if (!JdChannel.isLoggedIn(ctx)) {
            Log.i(TAG, "京东未登录，跳过")
            return SourceResult("jd", emptyList(), false, error = "未登录")
        }
        val pair = suspendCancellableCoroutine { cont ->
            var settled = false
            fun done(v: Pair<ArrayList<org.json.JSONObject>, String>) {
                if (settled) return
                settled = true
                if (cont.isActive) cont.resume(v)
            }
            val main = Handler(Looper.getMainLooper())
            main.postDelayed({ done(ArrayList<org.json.JSONObject>() to "超时") }, 60_000)
            main.post {
                try {
                    JdChannel.fetch(ctx) { pkgs, err -> done(pkgs to err) }
                } catch (e: Throwable) {
                    Log.w(TAG, "京东抓取异常: ${e.message}")
                    done(ArrayList<org.json.JSONObject>() to (e.message ?: "异常"))
                }
            }
        }
        val (pkgs, err) = pair
        val captures = JdChannel.lastCaptures
        Log.i(TAG, "京东 ${pkgs.size} 条 err=$err captures=${captures.size}")
        val items = pkgs.mapNotNull { ExpressStore.fromPkg(it) }
        val ok = err.isBlank() && captures.isNotEmpty()
        return SourceResult(
            "jd", items, ok, partial = err.isNotBlank() && items.isNotEmpty(),
            error = err.ifBlank { if (captures.isEmpty()) "未捕获到接口响应" else null }
        )
    }


    /**
     * ★ 不做主动 fetch：签名（`USER-SIGN`/`AUTH-TOKEN`）是页面的 axios 拦截器加的，
     */
        /**
     */
    // ---------------- 拼多多（原生 HTTP，不走 WebView）----------------

    private suspend fun fetchPdd(ctx: Context): List<ExpressStore.Item> = fetchPddResult(ctx).items

    private suspend fun fetchPddResult(ctx: Context): SourceResult {
        if (!PddChannel.isLoggedIn(ctx)) {
            // ★★ 登录失效超过静默期 ⇒ 连 PddNative 探活都不做。
            //   理由见 PddChannel.shouldSkipBackground：每次访问都在触碰拼多多风控，
            //   而这条链路已经注定拿不到数据（token 无法续期）。等用户重新登录即可恢复。
            if (PddChannel.shouldSkipBackground(ctx)) {
                Log.i(TAG, "拼多多未登录且已过静默期，后台跳过（不再空跑触碰风控）")
            } else {
                Log.i(TAG, "拼多多未登录，跳过")
            }
            return SourceResult("pdd", emptyList(), false, error = "未登录")
        }
        val ck = PddChannel.cookies(ctx)
        val uid = PddChannel.uid(ctx).ifEmpty { PddChannel.uidFromCookie(ck) }
        val token = Regex("PDDAccessToken=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
        val ses = PddNative.Session(token = token, uid = uid, cookie = ck)
        if (!ses.usable) return SourceResult("pdd", emptyList(), false, error = "登录态不可用")

        val rep = PddNative.scan(ses, maxPages = 2, cap = 40)
        Log.i(TAG, "拼多多 ${rep.items.size} 条（ok=${rep.ok} partial=${rep.partial} needLogin=${rep.needLogin} ${rep.message}）")
        if (rep.needLogin) {
            // ★ 服务端明确不认这份 cookie ⇒ 标记登录失效，UI 才不会再显示"已登录"
            //   （否则用户点身份码只会一直看到"请登录"，而设置页却说已登录）
            PddChannel.markLoginDead(ctx)
            return SourceResult("pdd", emptyList(), false, error = rep.message.ifBlank { "需要登录" })
        }
        val items = rep.items.mapNotNull { p ->
            if (p.orderSn.isBlank()) null
            else ExpressStore.Item(
                no = p.orderSn,
                src = "pdd",
                company = "",
                statusText = p.status,
                // 轨迹文本优先用"提示"（取件码那句），没有就用站点名 —— 阶段判定主要看这两个
                latest = p.tip.ifBlank { p.place },
                pickupCode = p.code,
                stationText = p.place
            )
        }
        // ★ vc428：**在途件也要抓**。
        //   以前这里只调 scan（只挑"待取"），在途件全被丢掉 ⇒ 后台永远看不到"运输中→已到站"，
        //   用户打开 App 才由前台链路发现。inTransitScan 是纯 HTTP，Worker 里可直接跑。
        val extra = ArrayList<ExpressStore.Item>()
        var transitOk = false
        var transitPartial = false
        var transitError: String? = null
        try {
            val tr = PddNative.inTransitScan(ses, maxPages = 2)
            transitOk = tr.ok
            transitPartial = tr.partial
            transitError = tr.message.ifBlank { null }
            Log.i(TAG, "拼多多在途 ${tr.items.size} 条（ok=${tr.ok} partial=${tr.partial} ${tr.message}）")
            if (tr.needLogin) PddChannel.markLoginDead(ctx)
            for (t in tr.items) {
                if (t.orderSn.isBlank()) continue
                extra.add(
                    ExpressStore.Item(
                        no = t.orderSn,
                        src = "pdd",
                        company = "",
                        statusText = t.status,
                        latest = t.latest,
                        pickupCode = "",
                        stationText = ""
                    )
                )
            }
        } catch (t: Throwable) {
            transitError = t.message ?: "在途抓取异常"
            Log.w(TAG, "拼多多在途抓取失败: ${t.message}")
        }
        // 只有取件码列表与在途列表都完整成功，源整体才允许替换缓存；
        // 途中格可独立按 transitOk 更新，取件码只在 scan 完整且详情补齐未失败时更新。
        val enriched = enrichPddTraces(ctx, items + extra)
        val sourceOk = rep.ok && !rep.partial && transitOk && !transitPartial
        return SourceResult(
            source = "pdd",
            items = enriched.items,
            ok = sourceOk && !enriched.partial,
            partial = rep.partial || transitPartial || enriched.partial || !transitOk,
            error = rep.message.ifBlank { transitError },
            pickupOk = rep.ok && !rep.partial && !enriched.partial,
            transitOk = transitOk && !transitPartial
        )
    }

    /**
     * ★ 拼多多「取件码/运单号」补齐（2026-09-20 照参考项目 `PddTraceFetcher` 补）。
     *
     * 为什么必须单独走这一步：拼多多的**订单列表接口里没有取件码**（`tracking_number` 还恒为空），
     * 取件码**只写在订单详情页的物流轨迹文案里**（形如「已暂存至XX驿站，取件码 31-5-7006」），
     * 而且那段轨迹是 **DOM 渲染**、不是可旁听的 JSON。
     * ⇒ 只能：加载 `order.html?order_sn=` → 点「查看物流」→ 读 `document.body.innerText` → 正则抠。
     *
     * ★ 成本控制：每个件要开一次详情页（约 10~20 秒），所以**每轮最多补 1 个**（vc267 从 2 降到 1，
     *   后台省电）、只补"缺取件码且在途/待取"的件。宁可慢一轮，也不要把 15 分钟的轮询撑爆。
     */
    private data class EnrichmentResult(val items: List<ExpressStore.Item>, val partial: Boolean)

    private suspend fun enrichPddTraces(
        ctx: Context, items: List<ExpressStore.Item>
    ): EnrichmentResult {
        // ★★ vc677：**加枚举门槛 —— 只给"确实在驿站待取"的单补轨迹**。
        //
        // 实证（2026-11-03 真机枚举全表）：
        //   · `IN_CABINET`   = 到驿站待取（该补，也才可能点到「查看物流」）
        //   · `SIGN` / `GOT` = **已取走**（桑葚干就是已取走、只是没在拼多多点确认收货）
        //   · `OUT_CABINET`  = 出柜/代签，件可能还在，但不是"驿站待取"
        //   · `ARRIVAL` / `DEPARTURE` / `SEND` = 还在路上
        // 这些单在订单页卡上印的是「运输中/已签收」，不含「待取件」「驿站」字样 ⇒
        // 定位必然失败（日志 `no-card|ambiguous=12`），却要白等 3 次重试 + 兜底 ≈ 26 秒。
        // **枚举缺失时保守照补**：宁可偶尔慢一次，不可漏码。
        val skipped = ArrayList<String>()
        val candidates = items.filter {
            if (it.pickupCode.isNotBlank() || !ExpressStore.isTransport(it)) return@filter false
            val st = PddChannel.lastTrackStatus[it.no]
            if (st != null && st != "IN_CABINET") {
                skipped.add("${it.no.takeLast(6)}=$st")
                return@filter false
            }
            true
        }.distinctBy { it.no }
        if (skipped.isNotEmpty()) {
            Log.i(TAG, "PDD 补齐跳过 ${skipped.size} 单（枚举非到站）：${skipped.joinToString("、")}")
        }
        if (candidates.isEmpty()) return EnrichmentResult(items, false)
        val prefs = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
        val cursor = prefs.getString("pdd_background_trace_cursor", "").orEmpty()
        val previous = candidates.indexOfFirst { it.no == cursor }
        val selected = candidates[(previous + 1).mod(candidates.size)]
        prefs.edit().putString("pdd_background_trace_cursor", selected.no).apply()
        val need = listOf(selected)
        Log.i(TAG, "PDD 后台无码补轨迹候选=${candidates.size} 本轮=${selected.no}（每轮限 1 单，轮转避免饥饿）")
        val byNo = HashMap<String, PddTraceChannel.Result>()
        var partial = false
        for (it in need) {
            val r = suspendCancellableCoroutine { cont ->
                // ★ vc342：后台任务排到最后 —— 用户正在看页面时，前台补码/轨迹优先
                PddTraceChannel.fetch(
                    ctx, it.no, priority = PddTraceChannel.PRI_BACKGROUND
                ) { res -> if (cont.isActive) cont.resume(res) }
            }
            Log.i(TAG, "拼多多轨迹补齐 ${it.no}: waybill=${r.waybillNo} pickup=${r.pickupCode} err=${r.err}")
            if (r.err != null && r.text.isBlank() && r.pickupCode.isNullOrBlank()) partial = true
            byNo[it.no] = r
        }
        val merged = items.map { it ->
            val r = byNo[it.no] ?: return@map it
            it.copy(
                pickupCode = r.pickupCode ?: it.pickupCode,
                latest = PddTraceChannel.parseTraceLines(r.text).firstOrNull() ?: it.latest,
                stationText = PddTraceChannel.parseTraceLines(r.text)
                    .firstOrNull { s -> s.contains("驿站") || s.contains("代收点") || s.contains("快递柜") }
                    ?: it.stationText
            )
        }
        return EnrichmentResult(merged, partial)
    }

    // ---------------- 小米（无窗口 WebView，Phase 2）----------------

    /**
     * 小米商城 + 有品：**无窗口 WebView** 旁听。
     *
     * ★★ 三个不能省的点（照抄参考项目 `Halo0sama/ExpressAssistant`，已验证可行）：
     * 1. **WebView 必须在主线程建** —— Worker 跑在 IO 线程上，没有 Looper，直接 new 会崩。
     *    所以整个抓取要 `Handler(mainLooper).post` 丢回主线程。
     * 2. **不挂窗口也能跑 JS** —— `MiShopChannel.attach()` 在没有 Activity 时会跳过 addView，
     *    参考项目的 `JdListFetcher` 就是这么做的（`WebView(context)` 从不 addView）。
     * 3. 小米那条内部自带 45 秒超时兜底，这里再加 50 秒的协程级超时，双保险。
     */
    private suspend fun fetchMi(ctx: Context): List<ExpressStore.Item> = fetchMiResult(ctx).items

    private suspend fun fetchMiResult(ctx: Context): SourceResult {
        if (!ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
                .getBoolean("mi_enabled", true)
        ) {
            Log.i(TAG, "小米数据源已关闭，跳过")
            return SourceResult("mi", emptyList(), false, error = "已关闭")
        }
        if (!MiChannel.isLoggedIn(ctx)) {
            Log.i(TAG, "小米未登录，跳过")
            return SourceResult("mi", emptyList(), false, error = "未登录")
        }
        return suspendCancellableCoroutine { cont ->
            var settled = false
            fun done(result: SourceResult) {
                if (settled) return
                settled = true
                if (cont.isActive) cont.resume(result)
            }
            val main = Handler(Looper.getMainLooper())
            main.postDelayed({ done(SourceResult("mi", emptyList(), false, error = "超时")) }, 50_000)
            main.post {
                try {
                    MiShopChannel.fetch(ctx) { captures, err ->
                        Log.i(TAG, "小米 ${captures.size} 条捕获 err=$err")
                        val parsed = (
                            MiShopChannel.parsePickup(captures) +
                                MiShopChannel.parseInTransit(captures)
                            ).mapNotNull { ExpressStore.fromPkg(it) }
                        val hasOrderResponse = captures.any { (url, body) ->
                            (url.contains("/v1/miorder/order_list") && body.contains("order_id")) ||
                                (url.contains("orderinfo/list") && body.contains("\"code\":0"))
                        }
                        val ok = err.isBlank() && hasOrderResponse
                        done(SourceResult(
                            "mi", parsed, ok,
                            partial = err.isNotBlank() && parsed.isNotEmpty(),
                            error = err.ifBlank { if (!hasOrderResponse) "未捕获订单响应" else null }
                        ))
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "小米抓取异常: ${e.message}")
                    done(SourceResult("mi", emptyList(), false, error = e.message ?: "异常"))
                }
            }
        }
    }

    // ---------------- 快递100 / 手动单号（纯 HTTP）----------------

    private fun fetchManual(ctx: Context): List<ExpressStore.Item> = fetchManualResult(ctx).items

    private fun fetchManualResult(ctx: Context): SourceResult {
        if (!Kd100Channel.isEnabled(ctx)) {
            Log.i(TAG, "快递100 已关闭，跳过手动单号")
            return SourceResult("manual", emptyList(), false, error = "已关闭")
        }
        val raw = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .getString(KEY_MANUAL, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (_: Throwable) {
            return SourceResult("manual", emptyList(), false, error = "手动单号列表解析失败")
        }
        val out = ArrayList<ExpressStore.Item>()
        val failed = ArrayList<String>()
        var attempted = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val no = o.optString("no")
            val phone = o.optString("phone")
            if (no.isBlank()) continue
            attempted++
            try {
                val r = Kd100Channel.queryTraceAuto(ctx, phone, no)
                val tr = r.trace
                if (tr == null) {
                    failed.add("$no(${r.why})")
                    continue
                }
                val last = tr.points.lastOrNull()
                if (last == null) {
                    failed.add("$no(接口成功但没有轨迹点)")
                    continue
                }
                out.add(
                    ExpressStore.Item(
                        no = no,
                        src = "manual",
                        company = if (r.comCode.isNotEmpty()) r.comCode else "手动添加",
                        statusText = if (tr.isReceived) "已签收"
                        else if (tr.eta.isNotEmpty()) "运输中" else "查询成功",
                        latest = listOfNotNull(
                            if (tr.eta.isNotEmpty()) "预计 ${tr.eta} 送达" else null,
                            last
                        ).joinToString(" · "),
                        stationText = ""
                    )
                )
            } catch (e: Throwable) {
                failed.add("$no(${e.message})")
                Log.w(TAG, "手动单号 $no 查询失败: ${e.message}")
            }
        }
        val ok = failed.isEmpty() && attempted == out.size
        Log.i(
            TAG,
            "手动单号 尝试=$attempted 成功 ${out.size} 条 / 失败 ${failed.size} 条" +
                    if (failed.isEmpty()) "" else " —— " + failed.joinToString(" | ")
        )
        return SourceResult(
            "manual", out, ok, partial = out.isNotEmpty() && !ok,
            error = if (failed.isEmpty()) null else failed.joinToString(" | ")
        )
    }
}
