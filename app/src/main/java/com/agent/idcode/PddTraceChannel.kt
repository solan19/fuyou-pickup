package com.agent.idcode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拼多多「订单详情 → 物流页」轨迹抓取 —— **取件码的唯一来源**。
 *
 * ## 为什么必须有这个文件（2026-09-20 查参考项目后补上）
 * 参考项目 `Halo0sama/ExpressAssistant` 的 `docs/PDD_REAL.md` 把实情写得很清楚，
 * 两条我们之前踩在坑里的结论：
 * 1. **订单列表接口里 `tracking_number` 恒为空** —— 真实运单号**只有详情页才有**；
 * 2. **拼多多的物流轨迹是 DOM 渲染的，不是可捕获的 JSON** ——
 *    所以「旁听接口」这条路**永远拿不到轨迹，也就拿不到取件码**。
 *
 * 而拼多多 App 里显示的取件码（如 `31-5-7006`）**就写在轨迹文案里**
 * （形如「您的快件已暂存至XX驿站，取件码 31-5-7006」）⇒ 必须**抠文本**。
 *
 * ## 链路（2026-09-26 定型 —— **列表页直点「查看物流」**）
 * ```
 * 隐藏 WebView 加载 mobile.yangkeduo.com/orders.html
 *   → 按订单号在列表里**定位目标订单卡**（卡的链接就是 order.html?order_sn=<单号>…）
 *   → 点这张卡自己的「查看物流」
 *   → 跳 goods_express.html?tracking_number=<运单号>&…（★ 运单号只在这个 URL 里，列表接口恒空）
 *   → 读 document.body.innerText（截 30000 字）
 *   → 正则抠取件码 + 切轨迹行
 * ```
 *
 * ## ★★ 为什么不再走「深链详情页」（本项目铁律 #3：同一方案两次无效即换机理）
 * 1. 2026-09-20：裸 `loadUrl(order.html?order_sn=…)` 被重定向到 login.html
 *    ⇒ 改成「先开 orders.html，再从页面内部 location.href 跳详情页」绕过。
 * 2. 2026-09-26 真机录像推翻它 —— 服务端**依然把详情页弹回列表页**：
 *    ```
 *    18:01:20.694 page: order.html?order_sn=260919-402485471601195   ← 进去了
 *    18:01:21.635 page: orders.html?refer_page_name=order_detail…    ← 1 秒后被弹回
 *    18:01:23.210 click 查看物流 -> no-btn                            ← 不在详情页，找不到按钮
 *    18:01:34.704 textLen=1359 pickup=null                           ← 抠的是列表页文本
 *    ```
 *    补齐 `&page_from=1&main_orders=1`（官方 `order_link_url` 那两个参数）也照样被弹回
 *    ⇒ 不是参数问题，是服务端不接受这种进入方式。
 *
 * 而 **列表页每张订单卡本来就自带「查看物流」按钮**，点它同样进物流页，还少一跳。
 * ⚠️ 参考项目的警告仍然有效：**一旦离开订单页就不要再点**，页面会自己继续跳
 * `express_service.html` / `unique_logistic_chat.html`（物流客服聊天），点进去就回不来了。
 */
object PddTraceChannel {

    private const val TAG = "PddTraceChannel"

    // ★ 不再有 `TRACE_PAGE`（深链详情页常量）—— 那条路已废，详见文件头。
    //   入口只有 [PddChannel.ORDERS_URL] 这一个：订单列表页。

    /**
     * 取件码正则 —— **与京东通道里那条保持同一口径**（参考项目 GoodsPresentation.pickupRe）。
     * ★ 关键：第二组**允许连字符**，否则 `31-5-7006` 这种「货架-层-序号」的码抠不出来。
     */
    private val PICKUP_RE = Regex(
        "(取件码|取货码|提货码|自提码|驿站码|取件凭证|取件验证码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9\\-]{1,14})"
    )

    /** ★ vc280：专用字段里抠出来的码值也要过这道形妆检 —— 防对象序列化残留/空串混进来 */
    private val CODE_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9\\-]{1,14}")

    /** 从轨迹/任意文本里抠取件码 */
    fun pickupCodeFrom(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return PICKUP_RE.find(text)?.groupValues?.get(2)
    }

    /** 抓取结果 */
    class Result(
        /** 物流页 URL（运单号在 query 里） */
        val pageUrl: String = "",
        /** 页面纯文本（已截断） */
        val text: String = "",
        /** 抓到的运单号（从物流页 URL 的 tracking_number 参数取） */
        val waybillNo: String = "",
        val err: String? = null,
        /**
         * ★ vc280：物流页 `rawData.store.traceData.shipping` 里的**专用取件码字段**
         * （`pickCode` / `cabinetInfo.verifyCode`）。
         *
         * 为什么加它：10-16 小米机实证 —— 16/21 段轨迹全拿到了，但轨迹文本里
         * **一个码值都没有**（只有"请您凭取件码领取快件"这种官话）；码值一直在
         * 页面顶部「取件码 XXX / 复制」那张卡里，对应的就是 `pickCode` 字段。
         * 旧代码只正则抠文本 ⇒ 页面加载成功也永远 code=空。
         */
        val pickCodeField: String = "",
        /** ★ vc280：整页 DOM 扫到的取件码（老办法只扫前 1500 字还滤掉日期行，码卡片在轨迹后面时永远扫不到） */
        val domCode: String = ""
    ) {
        val pickupCode: String?
            get() = codeOf(pickCodeField) ?: codeOf(domCode) ?: pickupCodeFrom(text)

        /** 字段值只认"像码"的（字母数字+连字符 2~15 位）；不像就当作没有，交回给下一级 */
        private fun codeOf(s: String?): String? =
            s?.trim()?.takeIf { it.isNotEmpty() && it.matches(CODE_SHAPE) }

        /**
         * ★ 物流页 URL 里的**真实订单号**（`goods_express.html?order_sn=…`）。
         *
         * 为什么必须认它：我们是按"列表页第一个待取件"点进去的，**未必等于调用时传入的
         * order_sn**（2026-09-26 实测：传 260919，实际跳进 260923 的物流页）。
         * 回填时若还按传入的单号去找卡片，就会把这张单的取件码写到另一张单上。
         */
        val realOrderSn: String
            get() = Regex("[?&]order_sn=([^&]+)").find(pageUrl)?.groupValues?.getOrNull(1).orEmpty()
    }

    private var worker: WebView? = null
    private var running = false
    private var delivered = false
    private val main = Handler(Looper.getMainLooper())

    // ================= ★ vc342：请求排队（替代"忙就拒绝"） =================
    //
    // 背景（P0-2）：全进程只有**一个**拼多多物流页通道，而它是五路共用的 ——
    //   ①第 4 栏补码 ②native 直链兜底 ③第 5 栏轨迹增强 ④后台轮询 ⑤调试探针。
    //   旧实现用布尔 `running`：谁先占谁赢，后来的直接收到「正在抓取中」**被丢掉**，
    //   于是用户看到"时好时坏"。vc339 只做到"忙拒不烧额度"，请求本身还是丢了。
    //
    // 现在改成**队列**：请求一律排队，同一时刻仍只开一个页面（风控不变），
    //   前台优先、后台让路且排队超时自动放弃（不能堵住 UI）。

    /** 前台（用户正看着的栏） */
    const val PRI_FOREGROUND = 0

    /** 后台轮询：给前台让路，排队太久就放弃 */
    const val PRI_BACKGROUND = 1

    /** 两次开页之间的最小间隔（连续开页会触碰风控） */
    private const val JOB_GAP_MS = 1_200L

    /** 单个任务最长占用（页面自身超时 30/55s，这里只兜"页面卡死"） */
    private const val JOB_HARD_CAP_MS = 75_000L

    /** 后台任务在队列里最多等这么久 —— 超时放弃，别堵住前台 */
    private const val BG_WAIT_BUDGET_MS = 45_000L

    private class Job(
        val key: String,
        val pri: Int,
        val seq: Long,
        val enqueuedAt: Long,
        val action: ((Result) -> Unit) -> Unit,
        val cb: (Result) -> Unit
    ) {
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val jobLock = Any()
    private val jobs = ArrayList<Job>()
    private var jobSeq = 0L
    private var activeJob: Job? = null
    private var lastJobEndAt = 0L

    private fun enqueue(
        key: String, pri: Int,
        action: ((Result) -> Unit) -> Unit,
        cb: (Result) -> Unit
    ) {
        var dup = false
        synchronized(jobLock) {
            // 同一单已在排队/正在跑 ⇒ 不再重复开页（数据到手后下一轮渲染自然上屏）
            if (jobs.any { it.key == key } || activeJob?.key == key) {
                dup = true
            } else {
                jobs.add(Job(key, pri, ++jobSeq, System.currentTimeMillis(), action, cb))
                jobs.sortWith(compareBy({ it.pri }, { it.seq }))
            }
        }
        if (dup) {
            Log.i(TAG, "任务已在队列/进行中，跳过重复请求：$key")
            cb(Result(err = "已在队列"))
            return
        }
        pump()
    }

    private fun pump() {
        val job: Job
        synchronized(jobLock) {
            if (activeJob != null || jobs.isEmpty()) return
            val now = System.currentTimeMillis()
            val it = jobs.iterator()
            while (it.hasNext()) {
                val j = it.next()
                if (j.pri > PRI_FOREGROUND && now - j.enqueuedAt > BG_WAIT_BUDGET_MS) {
                    it.remove()
                    j.delivered.set(true)
                    Log.i(TAG, "后台任务排队超时，放弃：${j.key}")
                    main.post { j.cb(Result(err = "排队超时")) }
                }
            }
            if (jobs.isEmpty()) return
            job = jobs.removeAt(0)
            activeJob = job
        }
        val wait = (lastJobEndAt + JOB_GAP_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        main.postDelayed({
            Log.i(TAG, "开始任务 ${job.key}（剩余 ${jobs.size}）")
            // 硬兜底：页面卡死时也必须放行队列
            main.postDelayed({
                complete(job, Result(err = "超时(队列兜底)"))
            }, JOB_HARD_CAP_MS)
            try {
                job.action { res -> complete(job, res) }
            } catch (t: Throwable) {
                complete(job, Result(err = "异常:${t.message}"))
            }
        }, wait)
    }

    private fun complete(job: Job, res: Result) {
        if (!job.delivered.compareAndSet(false, true)) return
        // ★ vc342：队列兜底超时 = 页面卡死。必须**强拆这个页面**再放行下一个任务 ——
        //   否则残留页面的迟到回调会把 `delivered` 置真，下一个任务永远等不到回调。
        if (res.err?.contains("队列兜底") == true) {
            delivered = true
            detach()
            running = false
        }
        synchronized(jobLock) {
            if (activeJob === job) activeJob = null
            lastJobEndAt = System.currentTimeMillis()
        }
        try {
            job.cb(res)
        } catch (t: Throwable) {
            Log.w(TAG, "任务回调异常：${t.message}")
        }
        main.postDelayed({ pump() }, JOB_GAP_MS)
    }

    /** 队列里还有多少活儿（诊断用） */
    fun queueSize(): Int = synchronized(jobLock) { jobs.size + if (activeJob != null) 1 else 0 }

    /** ★ 诊断开关（vc244）：只为弄清「展开」这一步到底发生了什么。验完即关。 */
    private const val DEBUG_DEEP = false
    /** 与 PddChannel 同一条旁听正则（那边是 private，这里抄一份） */
    private val HOOK_PATTERN = "/proxy\\/api|yangkeduo|pinduoduo|mdkd/i"
    /** 落盘目录 */
    private var dumpDir: java.io.File? = null

    @Volatile
    var lastResult: Result? = null
        private set

    /** 抓一个订单的详情页轨迹。**阻塞式回调（主线程）**，必定回调一次。
     *  ★ vc215：[anchorWords] 可传 —— 列表页点卡锚点词（原来写死「待取件/驿站」只服务第 4 栏；
     *    第 5 栏在途单卡上印的是「运输中/派送…」，必须传对应的词才点得到目标卡）。
     *  ★ anchorWords 必须排在 onDone **前面**：trailing lambda 永远绑最后一个参数，
     *    放后面会把 onDone 撑没（2026-09-27 编译实测）。 */
    fun fetch(
        ctx: Context, orderSn: String,
        anchorWords: List<String> = listOf("待取件", "驿站"),
        priority: Int = PRI_FOREGROUND,
        sessionCookie: String? = null,
        onDone: (Result) -> Unit
    ) {
        if (orderSn.isBlank()) {
            onDone(Result(err = "订单号为空"))
            return
        }
        if (sessionCookie.isNullOrBlank() && !PddChannel.isLoggedIn(ctx.applicationContext)) {
            onDone(Result(err = "拼多多未登录 —— 请到设置页登录后再试"))
            return
        }
        // ★ vc342：不再"忙就拒绝"，改排队；前台优先、后台让路
        enqueue("sn:$orderSn", priority, { done -> fetchNow(ctx, orderSn, anchorWords, sessionCookie, done) }, onDone)
    }

    /** 真正的抓取（队列保证同一时刻只有一个任务在跑） */
    private fun fetchNow(
        ctx: Context, orderSn: String,
        anchorWords: List<String>,
        sessionCookie: String?,
        onDone: (Result) -> Unit
    ) {
        val app = ctx.applicationContext
        main.post {
            if (orderSn.isBlank()) {
                onDone(Result(err = "订单号为空"))
                return@post
            }
            // ★ 没登录就别白跑一趟 —— 副号任务用传入的独立会话，不看全局主号 jar。
            if (sessionCookie.isNullOrBlank() && !PddChannel.isLoggedIn(app)) {
                onDone(Result(err = "拼多多未登录 —— 请到设置页登录后再试"))
                return@post
            }
            // ★★ 登录失效超过静默期 ⇒ 后台别再来开详情页。
            //   这里是**最贵**的一条：每个缺码的件都要开一次订单详情页、点「查看物流」，
            //   而轨迹是 DOM 渲染的、只能抠文本 —— 注定失败还一路触碰风控。
            //   （见 PddChannel.shouldSkipBackground 的说明。）
            if (PddChannel.shouldSkipBackground(app)) {
                onDone(Result(err = "拼多多登录已失效太久 —— 请重新登录后再试"))
                return@post
            }
            running = true
            delivered = false
            var pageUrl = ""
            var collectStarted = false
            var clicking = false
            var clicks = 0

            // ★ 诊断模式必须把** Activity **传进去：只有挂到真实窗口上 WebView 才有尺寸，
            //   页面里的元素才有坐标（否则 getBoundingClientRect 全是 0，真人点击无从下手）。
            val wv = buildWebView(app, ctx as? Activity)
            worker = wv
            dumpDir = app.getExternalFilesDir(null)
            // ★ 装旁听钩子：看「展开」到底有没有发接口（document-start，早于页面 JS）
            if (DEBUG_DEEP) {
                val hjs = WebHook.hookJs(HOOK_PATTERN)
                val docStart = WebHook.attachDocumentStart(
                    wv, hjs, setOf("https://mobile.yangkeduo.com", "https://m.pinduoduo.net")
                )
                if (!docStart) WebHook.attachFallback(wv, hjs)
            }
            // ★★ 必须先注入拼多多 cookie —— 不注入会被直接重定向到 login.html。
            //    副号补码时使用副号自己的 cookie，不能借用全局主号 jar。
            val restoreCookie = if (sessionCookie?.isNotBlank() == true) PddChannel.cookies(app) else ""
            val cookieForTask = sessionCookie?.takeIf { it.isNotBlank() } ?: restoreCookie
            PddChannel.injectCookies(cookieForTask)

            fun finish(res: Result) {
                if (delivered) return
                delivered = true
                running = false
                lastResult = res
                detach()
                if (restoreCookie.isNotBlank()) {
                    try { PddChannel.injectCookies(restoreCookie) }
                    catch (t: Throwable) { SafeRun.w("PddTraceChannel", t) }
                }
                Log.i(TAG, "done url=${res.pageUrl.take(90)} textLen=${res.text.length} " +
                        "waybill=${res.waybillNo} pickup=${res.pickupCode} err=${res.err}")
                onDone(res)
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    // ★ 拼多多 App deep link 一律拦掉：跳走就看不到了
                    val u = req.url.toString()
                    return u.startsWith("pinduoduo://") || u.startsWith("xunmeng://") ||
                            u.startsWith("pddopen://")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageUrl = url
                    Log.i(TAG, "page: ${url.take(140)}")
                    if (url.contains("login.html") || url.contains("passport")) {
                        finish(Result(url, "", "", "需要登录拼多多"))
                        return
                    }
                    // 到了物流页 ⇒ 直接读文本收工（运单号在 URL 里、取件码在轨迹文案里）
                    if (url.contains("goods_express") || url.contains("express_service") ||
                        url.contains("logistic") || url.contains("tracking_number=")
                    ) {
                        // ★ 刚 onPageFinished 时物流详情往往还没渲染出来 —— 实测立刻读只有
                        //   938 字、只有一条「已到达」，取件码那行根本没铺出来 ⇒ 给它 3.5 秒再读。
                        main.postDelayed({
                            if (!delivered) collect(view, pageUrl) { finish(it) }
                        }, 3500)
                        return
                    }
                    // ★★ 只认订单列表页：在这一页**按订单号定位**目标卡，再点它自己的「查看物流」。
                    //   （深链详情页那条路已两次证伪，见文件头注释。）
                    if (url.contains("orders.html") && clicks < 3) {
                        if (clicking) return
                        clicking = true
                        clicks++
                        main.postDelayed({
                            if (delivered) { clicking = false; return@postDelayed }
                            // 先滚到底让懒加载的订单卡都渲染出来，再定位
                            WebHook.scrollToBottom(view)
                            main.postDelayed({
                                if (delivered) { clicking = false; return@postDelayed }
                                clickLogistics(view, orderSn, anchorWords) { ok ->
                                    Log.i(TAG, "click 查看物流 -> $ok")
                                    clicking = false
                                    // 干等超时很难一眼看出卡在哪一步 ⇒ 失败当场把列表页结构摊开
                                    if (ok.startsWith("no-")) {
                                        Log.w(TAG, "列表页定位失败：$ok")
                                        if (clicks == 1) diagListDom(view, orderSn)
                                    }
                                }
                            }, 1200)
                        }, 3000)
                        if (!collectStarted) {
                            collectStarted = true
                            // 兜底：点了但不跳转时，也把当前页文本收回来（至少可能有轨迹）
                            main.postDelayed({ collect(view, pageUrl) { finish(it) } }, 20_000)
                        }
                    }
                }
            }
            // ★ 入口是**订单列表页**（不是 order.html）—— 见上面「借道」那段注释
            wv.loadUrl(PddChannel.ORDERS_URL)
            // ★ vc270i：超时降概率 —— 小米机实测偶发"页面白屏"（GPU 着色器失败/水合瞬断），
            //   55s 时页面 textLen=0 直接认输太亏。改成：先问一下页面到底有没有文本，
            //   白板 ⇒ **重载一次再等 45s**（重载不烧外层重试次数）；有文本但没点位 ⇒ 照旧认输。
            var reloadedOnce = false
            main.postDelayed({
                if (delivered) return@postDelayed
                try {
                    wv.evaluateJavascript(
                        "(function(){try{return (document.body?document.body.innerText.length:0)}catch(e){return -1}})()"
                    ) { v ->
                        main.post {
                            if (delivered) return@post
                            val len = v?.trim()?.trim('"')?.toIntOrNull() ?: 0
                            if (len <= 0 && !reloadedOnce) {
                                reloadedOnce = true
                                Log.w(TAG, "55s 空白页（textLen=0）⇒ 重载一次再等 45s")
                                wv.loadUrl(PddChannel.ORDERS_URL)
                                main.postDelayed({
                                    if (!delivered) finish(Result(pageUrl, "", "", "超时(重载后)"))
                                }, 45_000L)
                            } else {
                                finish(Result(pageUrl, "", "", "超时"))
                            }
                        }
                    }
                } catch (_: Throwable) {
                    finish(Result(pageUrl, "", "", "超时"))
                }
            }, if (DEBUG_DEEP) 110_000L else 55_000L)
        }
    }
    /**
     * ★★ vc252：已知「查看物流」直链 ⇒ **直接开这一页**读轨迹，不再借道订单列表页点按钮。
     *
     * 直链来自订单列表接口 `order_buttons[].type_value.url`（[PddNative.expressUrlOf]），
     * 形如 `goods_express.html?tracking_number=…&shipping_id=..&order_sn=…&goods_id=…&hide_address=1`。
     *
     * 好处：省掉「加载 orders.html（重页面）→ 滚到底 → 按锚点词找卡 → 点『查看物流』」这一串，
     * 既快（少一个大页面、少 3s 滚动 + 3.5s 等待）又准 —— 不靠猜锚点词，**从根上杜绝张冠李戴**
     * （vc215 那次事故：锚点点错单，极兔的运单号配邮政 1 月轨迹填到了韵达卡上）。
     *
     * ★ 已知风险：拼多多有时会把**直接深链**弹回推荐流（order_express_group）。
     *   弹回时 [Result.err] 带 `bounced`，调用方退回快递100 —— 行为不比原来差。
     *
     * @param url 相对或绝对 URL；相对会自动补 `https://mobile.yangkeduo.com/`
     * @param waybill 已知运单号（填了就当作兜底，页面 URL 抠不到时用它）
     */
    fun fetchByUrl(
        ctx: Context, url: String, waybill: String = "",
        priority: Int = PRI_FOREGROUND,
        sessionCookie: String? = null,
        onDone: (Result) -> Unit
    ) {
        val full = if (url.startsWith("http")) url
        else "https://mobile.yangkeduo.com/" + url.trimStart('/')
        if (sessionCookie.isNullOrBlank() && !PddChannel.isLoggedIn(ctx.applicationContext)) {
            onDone(Result(err = "拼多多未登录 —— 请到设置页登录后再试"))
            return
        }
        enqueue("url:$full", priority, { done -> fetchByUrlNow(ctx, full, waybill, sessionCookie, done) }, onDone)
    }

    /** 真正的"直开物流页"（队列保证串行） */
    private fun fetchByUrlNow(
        ctx: Context, full: String, waybill: String, sessionCookie: String?, onDone: (Result) -> Unit
    ) {
        val app = ctx.applicationContext
        main.post {
            running = true
            delivered = false
            var landed = ""
            val wv = buildWebView(app, ctx as? Activity)
            worker = wv
            val restoreCookie = if (sessionCookie?.isNotBlank() == true) PddChannel.cookies(app) else ""
            val cookieForTask = sessionCookie?.takeIf { it.isNotBlank() } ?: restoreCookie
            PddChannel.injectCookies(cookieForTask)

            fun finish(res: Result) {
                if (delivered) return
                delivered = true
                running = false
                lastResult = res
                detach()
                if (restoreCookie.isNotBlank()) {
                    try { PddChannel.injectCookies(restoreCookie) }
                    catch (t: Throwable) { SafeRun.w("PddTraceChannel", t) }
                }
                Log.i(TAG, "fetchByUrl done url=${res.pageUrl.take(90)} " +
                        "wb=${res.waybillNo} textLen=${res.text.length} err=${res.err}")
                onDone(res)
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url.toString()
                    return u.startsWith("pinduoduo://") || u.startsWith("xunmeng://") ||
                            u.startsWith("pddopen://")
                }

                override fun onPageFinished(view: WebView, u: String) {
                    landed = u
                    Log.i(TAG, "fetchByUrl page: ${u.take(140)}")
                    if (u.contains("login.html") || u.contains("passport")) {
                        finish(Result(u, "", waybill, "需要登录拼多多"))
                        return
                    }
                    if (u.contains("goods_express") || u.contains("tracking_number=")) {
                        // ★ SSR 直出，2.5s 足够脚本把 rawData 铺好（老路要等 3.5s 是因为
                        //   前面还有"滚动+点击"两段时间，页面本身没那么慢）
                        main.postDelayed({
                            if (!delivered) collect(view, u) { finish(it) }
                        }, 2500)
                        return
                    }
                    // 被弹回列表/推荐流：立刻认输，别在这儿干等超时
                    finish(Result(u, "", waybill, "bounced"))
                }
            }
            wv.loadUrl(full)
            // ★ vc253：实测直链 ~6s 就取到轨迹（2026-10-14 顺丰单），兜底 20s 足够
            // ★ vc280：20s → 30s —— 10-16 小米机（Android 17 WebView）4 单里 2 单
            //   20s 内 onPageFinished 都没来（纯超时，非风控）⇒ 慢机器直接判死太冤
            main.postDelayed({
                if (!delivered) finish(Result(landed, "", waybill, "超时"))
            }, 30_000L)
        }
    }
    /**
     * 在**订单列表页**里认出"待取件"那张订单卡，再点它自己的「查看物流」。
     *
     * ## 为什么改成"按卡片文本认"，而不是按订单号（2026-09-26 DOM 体检的结论）
     * 体检输出的硬证据：`DOM snHit=0 viewLogistics=18`
     * —— 页面里**没有任何元素带订单号**（href / data-order-sn / data-sn / order_sn /
     * data-id 全试过），而且 React 虚拟列表里同时挂着 **18 个**「查看物流」。
     * ⇒ 按单号定位这条路**不存在**，只能按"卡上写了什么"来认。
     *
     * ## 认什么
     * 卡上会直接印物流摘要（实测文本：`待取件 | 极兔速递：快件已由…送达代收点暂存,取件地址:…快递驿站`），
     * 所以按「待取件 / 驿站 / 代收点 / 暂存」认出的，正是取件码栏要的那些件。
     *
     * ## 认不出就宁可不点
     * 与项目「去重宁可漏合并、不可误合并」同一口径：把别的订单的物流当成这一单，比什么都没有更糟。
     */
    private fun clickLogistics(
        wv: WebView, orderSn: String, anchorWords: List<String>, cb: (String) -> Unit
    ) {
        // ★ vc215：锚点词注入 JS（原写死「待取件/驿站」）；JSON 字面量安全转义
        val wordsJson = JSONArray(anchorWords).toString()
        val js = """(function(){
          try {
            var sn = '$orderSn';
            var words = $wordsJson;
            var all = document.querySelectorAll('*');
            /* ① 找印着锚点词的**最小**元素 —— 列表页会把物流摘要直接印在卡上
                 （第 4 栏实测：`待取件 | 极兔速递：快件已由…送达代收点暂存…`；
                   第 5 栏在途单卡印的是「运输中 | 韵达快递：已离开XX分拨交付中心…」）。
                 DOM 里没有任何元素带订单号（体检 snHit=0），只能从这段文本反查是哪张卡。
                 ★ 取"最小的"而不是"第一个"：否则会选中整个列表容器，点错到别的订单上
                 （2026-09-26 实测就这么跳到过一条「运输中」的 260926 单）。 */
            var anchor = null, aLen = 1e9;
            for (var i = 0; i < all.length; i++) {
              var t = (all[i].innerText || '');
              var hit = false;
              for (var w = 0; w < words.length; w++) {
                if (t.indexOf(words[w]) >= 0) { hit = true; break; }
              }
              if (!hit) continue;
              if (t.length < aLen) { anchor = all[i]; aLen = t.length; }
            }
            /* ② 从锚点往上找到那张订单卡（同一张卡上必然印着「查看物流」） */
            var card = null;
            if (anchor) {
              var c = anchor, step = 0;
              while (c.parentElement && step < 8) {
                c = c.parentElement; step++;
                if ((c.innerText || '').indexOf('查看物流') >= 0) { card = c; break; }
              }
            }
            /* ③ 只在卡内点「查看物流」；锚点找不到才退回全页（且只有一个按钮时） */
            var pool = card ? card.querySelectorAll('*') : all;
            var vis = [], hid = [];
            for (var j = 0; j < pool.length; j++) {
              var s = (pool[j].innerText || '').trim();
              if (s !== '查看物流' && s !== '查物流' && s !== '物流详情') continue;
              var r = pool[j].getBoundingClientRect();
              // ★ 不**只**收有尺寸的：隐藏元素（React 虚拟列表里没铺开的那些）的 el.click()
              //   照样派发事件。只是优先选可见的，都不可见时退而取第一个。
              (r.height > 0 && r.width > 0) ? vis.push(pool[j]) : hid.push(pool[j]);
            }
            var btns = vis.length ? vis : hid;
            if (!btns.length) return 'no-btn|anchor=' + (anchor ? aLen : 'none');
            if (!card && btns.length > 1) return 'no-card|ambiguous=' + btns.length;
            btns[0].scrollIntoView();
            btns[0].click();
            return 'ok|sn=' + sn + '|anchor=' + (anchor ? aLen : 'none') +
                   '|card=' + (card ? 'y' : 'n') + '|tag=' + btns[0].tagName +
                   '|vis=' + vis.length + '|hid=' + hid.length;
          } catch(e) { return 'ERR ' + e; }
        })()"""
        try {
            wv.evaluateJavascript(js) { v -> cb(WebHook.unquoteJsString(v)) }
        } catch (e: Throwable) {
            cb("EX ${e.message}")
        }
    }

    /**
     * 定位失败时的一次性 DOM 体检 —— **把列表页的结构摊开写在日志里**。
     *
     * 为什么值得留这段代码：拼多多 H5 的订单卡结构没有公开文档，全靠猜，
     * 而一旦它改版，[clickLogistics] 只会返回一句 `no-card`，看不出到底哪里断了。
     * 有了这份体检，下次改版时不用重编译也能一眼看出该改哪个选择器。
     */
    private fun diagListDom(wv: WebView, orderSn: String) {
        val js = """(function(){
          try {
            var sn = '$orderSn';
            var all = document.querySelectorAll('*');
            var hits = [], snHit = 0;
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              var mark = [];
              ['href','data-order-sn','data-sn','order_sn','data-order-id','data-id']
                .forEach(function(a){
                  var v = el.getAttribute ? (el.getAttribute(a) || '') : '';
                  if (v.indexOf(sn) >= 0) mark.push(a);
                });
              if (mark.length) {
                snHit++;
                if (hits.length < 4) {
                  hits.push('<' + el.tagName + ' class="' + String(el.className).slice(0,40) +
                            '" attrs=' + mark.join(',') + '>');
                }
              }
            }
            var btns = [];
            for (var j = 0; j < all.length; j++) {
              if ((all[j].innerText || '').trim() === '查看物流') btns.push(all[j]);
            }
            var chain = [];
            if (btns.length) {
              var c = btns[0];
              chain.push('[' + c.tagName + '.' + String(c.className).slice(0,36) + ']');
              for (var k = 0; k < 4 && c.parentElement; k++) {
                c = c.parentElement;
                chain.push(c.tagName + '.' + String(c.className).slice(0,36));
              }
            }
            // 「查看物流」本身就是 <A> —— 它的 href 很可能直接指向物流页（含 tracking_number）
            var hrefs = [];
            for (var q = 0; q < btns.length && q < 4; q++) {
              hrefs.push('<' + btns[q].tagName + ' href="' +
                         (btns[q].getAttribute('href') || '').slice(0, 70) + '">');
            }
            return 'DOM snHit=' + snHit + ' viewLogistics=' + btns.length +
                   ' | hits=' + hits.join(' ;; ') +
                   ' | btn0=' + chain.join(' > ') +
                   ' | btnHrefs=' + hrefs.join(' ;; ');
          } catch(e) { return 'DOM ERR ' + e; }
        })()"""
        try {
            wv.evaluateJavascript(js) { v -> Log.w(TAG, "DOM体检: ${WebHook.unquoteJsString(v)}") }
        } catch (e: Throwable) {
            Log.w(TAG, "DOM体检失败: ${e.message}")
        }
    }

    /**
     * 读物流页。
     *
     * ## ★★ vc250：首选「读页面里现成的结构化数据」，不再费劲去点「展开」
     * 2026-10-14 三级取证的结论（详见本文件 [traceDataJs] 的注释）：
     *   · 真人点一下「展开」⇒ 轨迹从 1 段变 17 段；
     *   · 但这 17 段**没有触发任何网络请求** —— 数据本来就在页面里；
     *   · 拼多多拒绝 JS 合成事件（`isTrusted=false` 的一律不认），
     *     el.click() / 完整 touch 派发 / 延长等待 全部无效（同一机理三次证伪）；
     *   · 真正的数据在 `window.rawData.store.traceData.shipping.traces[]` 里，
     *     17 段全都在，还带 status / statusTag，比抠 DOM 文本准得多。
     * ⇒ 直接读它。**读不到**（页面改版）才退回「点展开 + 抠文本」的老办法。
     */
    private fun collect(wv: WebView, url: String, cb: (Result) -> Unit) {
        val wbFromUrl = Regex("[?&]tracking_number=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()

        fun deliver(text: String, wb: String, field: String = "", dom: String = "") {
            val w = wb.ifEmpty { wbFromUrl }
            cb(Result(url, text, w, if (text.length < 20) "页面文本为空" else null, field, dom))
        }

        /** 老办法：点「展开」→ 等 → 读 innerText */
        fun fallbackRead() {
            try {
                wv.evaluateJavascript(expandJs()) { r ->
                    Log.i(TAG, "展开轨迹: ${WebHook.unquoteJsString(r)}")
                    main.postDelayed({
                        wv.evaluateJavascript(DOM_TEXT_JS) { v -> deliver(WebHook.unquoteJsString(v), "") }
                    }, 3500)
                }
            } catch (e: Throwable) {
                wv.evaluateJavascript(DOM_TEXT_JS) { v -> deliver(WebHook.unquoteJsString(v), "") }
            }
        }

        try {
            wv.evaluateJavascript(traceDataJs()) { v ->
                val raw = WebHook.unquoteJsString(v)
                if (DEBUG_DEEP) dumpFile("pdd_tracedata.txt", raw)
                val p = parseTraceData(raw)
                if (p != null && p.lines.isNotEmpty()) {
                    Log.i(TAG, "★ 页面数据直取 ${p.lines.size} 段轨迹（不必点「展开」）waybill=${p.trackingNumber}" +
                            (if (p.pickCode.isNotEmpty()) " pickCode=${p.pickCode}"
                            else if (p.verifyCode.isNotEmpty()) " verifyCode=${p.verifyCode}"
                            else " 专用字段无码（signCode=${p.signCode.ifEmpty { "空" }}）"))
                    // 再补一段 DOM 文本：取件码有时写在轨迹之外（驿站那块），别让它漏掉
                    wv.evaluateJavascript(DOM_TEXT_JS) { dv ->
                        val dom = WebHook.unquoteJsString(dv)
                        // ★ DOM 文本只补 1500 字：取件码/驿站/快递员那块就在页面最上面，够了；
                        //   再往后全是推荐流。
                        // ★★ vc253：补全段时**丢掉所有含日期的行** —— 页面最上面印的那条
                        //   「已签收 2026-08-24 12:03:31」和轨迹首行是同一条，原样补进来就会
                        //   在末尾多出一条重复的「已签收」（2026-10-14 顺丰单实测）。
                        //   轨迹我们已从 rawData 全量拿到了，DOM 只用来补"轨迹之外"的信息。
                        val extra = dom.take(1500).split("\n")
                            .filter { it.isNotBlank() && !DATE_RE.containsMatchIn(it) }
                            .joinToString("\n")
                        // ★ vc280：码卡片在轨迹列表**后面**时，上面那个 1500 字窗口永远扫不到它
                        //   ⇒ 再对整页 innerText 扫一遍（与轨迹文本的正则兜底互不依赖）
                        wv.evaluateJavascript(CODE_SCAN_JS) { cv ->
                            val domCode = WebHook.unquoteJsString(cv)
                            deliver(
                                if (extra.isEmpty()) p.lines.joinToString("\n")
                                else p.lines.joinToString("\n") + "\n" + extra,
                                p.trackingNumber,
                                p.pickCode.ifEmpty { p.verifyCode },
                                domCode
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "页面数据里没有轨迹，退回「点展开 + 读文本」")
                    fallbackRead()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读页面数据失败: ${e.message}")
            fallbackRead()
        }
    }

    private const val DOM_TEXT_JS =
        "(function(){return document.body ? document.body.innerText.slice(0,30000) : 'no-body';})()"

    /**
     * ★ vc280：**整页**扫取件码 —— 不限字数、不看日期行。
     *
     * 老办法的窗口是 `dom.take(1500)` 再滤掉含日期的行：码卡片（「取件码 XXX / 复制」）
     * 只要排在轨迹列表**后面**就永远扫不到（10-16 小米机：页面加载成功、16/21 段轨迹
     * 俱全，code 却恒空）。这里对全部 innerText 跑同一套正则，与 rawData 字段互补。
     */
    private val CODE_SCAN_JS = "(function(){try{var t=document.body?document.body.innerText:'';" +
            "var m=t.match(/(取件码|取货码|提货码|自提码|驿站码|取件凭证|取件验证码)[:：\\s]*" +
            "([A-Za-z0-9][A-Za-z0-9\\-]{1,14})/);" +
            "return m?m[2]:'';}catch(e){return ''}})()"

    /** ★ vc253：一行里带 `yyyy-mm-dd` ⇒ 那是轨迹行（我们自己已全量拿到，DOM 里那份是重复） */
    private val DATE_RE = Regex("\\d{4}-\\d{2}-\\d{2}")

    private class TraceData(
        val lines: List<String>,
        val trackingNumber: String,
        /** ★ vc280：shipping.pickCode（驿站货架码，主力码源） */
        val pickCode: String = "",
        /** ★ vc280：cabinetInfo.verifyCode（快递柜验证码） */
        val verifyCode: String = "",
        /** ★ vc280：signCodeInfo（签收码，只打日志不当码用） */
        val signCode: String = ""
    )

    /** 把 traceDataJs 吐回来的 JSON 变成「时间+状态+文案」的行 */
    private fun parseTraceData(raw: String): TraceData? {
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optBoolean("ok") != true) return null
            val arr = o.optJSONArray("traces") ?: return null
            val lines = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val t = it.optString("t")
                if (t.isBlank()) continue
                val st = it.optString("s")
                val d = it.optString("d")
                // 一行 = 状态 + 时间 + 文案。★ 文案截到 150 字：
                //   parseTraceLines 会丢掉超过 200 字的行（那是防推荐流噪声的），别撞上。
                lines.add((st + t + " " + d).trim())
            }
            if (lines.isEmpty()) null else TraceData(
                lines, o.optString("trackingNumber"),
                o.optString("pickCode"), o.optString("verifyCode"), o.optString("signCode")
            )
        } catch (e: Throwable) {
            Log.w(TAG, "解析页面轨迹数据失败: ${e.message}")
            null
        }
    }
    /**
     * 点「展开」—— **移动端 H5 只认触摸事件**，所以 mousedown/up/click 之外
     * 还要补 touchstart/touchend，两条路都派发一遍。
     */
    private fun expandJs(): String = """(function(){
      try {
        var log = [];
        function fire(el){
          try {
            var r = el.getBoundingClientRect();
            var cx = r.left + (r.width/2 || 1), cy = r.top + (r.height/2 || 1);
            function ev(Ctor, name, extra){
              try {
                var o = {bubbles:true, cancelable:true, composed:true, view:window,
                         clientX:cx, clientY:cy};
                if (extra) { for (var k in extra) { o[k] = extra[k]; } }
                el.dispatchEvent(new Ctor(name, o));
              } catch(e) { log.push(name + '-err'); }
            }
            ev(MouseEvent, 'mousedown');
            ev(MouseEvent, 'mouseup');
            ev(MouseEvent, 'click');
            try {
              var t = new Touch({identifier:1, target:el, clientX:cx, clientY:cy});
              ev(TouchEvent, 'touchstart', {touches:[t], targetTouches:[t], changedTouches:[t]});
              ev(TouchEvent, 'touchend', {touches:[], targetTouches:[], changedTouches:[t]});
            } catch(e) { log.push('touch-err'); }
            try { el.scrollIntoView(); } catch(e) {}
            el.click();
          } catch(e) { log.push('fire-err'); }
        }
        var targets = [];
        var byText = document.querySelectorAll('div,span,a,button,li,p');
        for (var i = 0; i < byText.length; i++) {
          var t = (byText[i].innerText || '').trim();
          if (t === '展开' || t === '展开全部' || t === '查看更多') {
            targets.push(byText[i]);
            if (byText[i].parentElement) targets.push(byText[i].parentElement);
          }
        }
        var byClass = document.querySelectorAll('.express-show, .label-show');
        for (var j = 0; j < byClass.length; j++) targets.push(byClass[j]);
        var uniq = [];
        for (var k = 0; k < targets.length; k++) {
          if (uniq.indexOf(targets[k]) < 0) uniq.push(targets[k]);
        }
        for (var m = 0; m < uniq.length; m++) fire(uniq[m]);
        return 'fired=' + uniq.length + ' log=' + log.join('|');
      } catch(err) { return 'ERR ' + err; }
    })()"""

    /**
     * 翻开整个页面找那 17 段轨迹 —— **不需要点「展开」**。
     *
     * 三处一起查：
     *  ① `document.documentElement.outerHTML`：SSR 直出时常把完整数据塞在内联 script 里；
     *  ② 常见 SSR 数据容器（__INITIAL_STATE__ / __NUXT__ / __NEXT_DATA__ …）；
     *  ③ 遍历 window 自有属性，找含轨迹文案的对象。
     */
    private fun stateJs(): String = """(function(){
      try {
        var probes = ['快件已领取', '您已提交订单', '打印拣货单', '订单确认',
                      '商家已发货', '正在为您派件', '已揽收'];
        var h = document.documentElement.outerHTML;
        var inHtml = {};
        for (var i = 0; i < probes.length; i++) {
          inHtml[probes[i]] = h.indexOf(probes[i]);
        }
        var names = ['__INITIAL_STATE__', '__NUXT__', '__NEXT_DATA__', '__PRELOADED_STATE__',
                     'rawData', '__rawData', '_SSR_DATA', '__DATA__', '__INIT_DATA__'];
        var ssr = [];
        for (var j = 0; j < names.length; j++) {
          if (window[names[j]]) {
            var v = window[names[j]];
            var str = '';
            try { str = JSON.stringify(v); } catch(e) { str = '<不能序列化>'; }
            ssr.push(names[j] + ' | type=' + (typeof v) + ' | len=' + str.length);
          }
        }
        var scripts = [];
        var ss = document.querySelectorAll('script');
        for (var k = 0; k < ss.length && scripts.length < 12; k++) {
          var t = ss[k].textContent || '';
          if (t && (t.indexOf('trace') >= 0 || t.indexOf('快件') >= 0 ||
                    t.indexOf('express') >= 0)) {
            scripts.push('#' + k + ' type=' + (ss[k].type || '') + ' len=' + t.length +
                         ' head=' + t.slice(0, 160));
          }
        }
        var hits = [];
        var keys = Object.keys(window);
        for (var m = 0; m < keys.length && hits.length < 25; m++) {
          var key = keys[m], v2;
          try { v2 = window[key]; } catch(e) { continue; }
          if (!v2 || typeof v2 !== 'object') continue;
          var s2 = '';
          try { s2 = JSON.stringify(v2); } catch(e) { continue; }
          if (!s2 || s2.length > 3000000) continue;
          if (s2.indexOf('trace') >= 0 &&
              (s2.indexOf('快件') >= 0 || s2.indexOf('揽收') >= 0)) {
            hits.push(key + ' | len=' + s2.length);
          }
        }
        return JSON.stringify({
          htmlLen: h.length,
          probesInHtml: inHtml,
          ssr: ssr,
          scripts: scripts,
          winHits: hits,
          keyCount: keys.length
        });
      } catch(e) { return JSON.stringify({ err: String(e) }); }
    })()"""

    /**
     * 从页面里直接取**完整轨迹** —— 不用点「展开」。
     *
     * ## 数据在哪（2026-10-14 真机取证）
     * 物流页是 SSR 直出的，服务端把整包数据写进内联 script：
     * ```
     * window.rawData = { store: { traceData: { shipping: {
     *     trackingNumber: "YT0714785611281", shippingName: "圆通快递", address: "…",
     *     traces: [ { info:"快件已领取…", time:"2026-10-12 12:26:24",
     *                 displayTime:"…", status:"SIGN", statusTag:"已取件",
     *                 district/city/province/postmanName/… }, ×17 ]
     * } } } }
     * ```
     * 页面默认只**渲染**最新 1 条，其余藏在数据里等「展开」——但数据一直在。
     *
     * ## 为什么非要绕这一下（拼多多的反自动化）
     * 「展开」只认**真实的**用户操作：`event.isTrusted === false` 的一律不处理。
     * 已三次证伪：el.click()（vc220）、延长等待（vc227）、
     * mousedown/up/click + touchstart/touchend 全套派发连点三轮（vc245）——全部无效。
     * 而**系统级真实触摸**（adb input tap）一击就中（vc247，liCount 1→17）。
     * ⇒ 后台没有界面、也不可能去点，所以正确解是**别点，直接读数据**。
     *
     * ## 比抠 DOM 文本强在哪
     * 之前只能拿到「最新 1 条」，且是从渲染后的文字里正则抠；
     * 现在 17 段全有，还自带 `status`（SIGN/IN_CABINET/SEND/ARRIVAL/DEPARTURE/GOT/
     * PRINT/CONFIRM/CREATE）和 `statusTag`（已取件/待取件/派送中/运输中/…），
     * 连「提交订单 / 打印拣货单 / 商家已发货」这些**快递100 没有的电商侧节点**也有。
     */
    private fun traceDataJs(): String = """(function(){
      try {
        var d = window.rawData || {};
        var st = (d.store || {});
        var td = st.traceData || {};
        var sp = td.shipping || {};
        var arr = sp.traces || [];
        /* ★ 少数条目只有英文状态码（实测：CREATE / PRINT 两条没给中文标签），
           直接显示英文很扎眼 ⇒ 补一张兜底表。中文标签优先，没有才用这张表。 */
        var MAP = { CREATE: '已下单', CONFIRM: '已确认', PRINT: '已打印', PICK: '拣货中',
                    GOT: '已揽件', DEPARTURE: '运输中', ARRIVAL: '运输中', SEND: '派送中',
                    IN_CABINET: '待取件', SIGN: '已取件', SIGNED: '已签收',
                    DELIVERED: '已签收', PICKUP: '已取件',
                    /* 2026-10-14 顺丰单实测：'CHECK' 没有中文标签 ⇒ 直接显示英文很扎眼 */
                    CHECK: '已验货', PACK: '已打包', PACKED: '已打包', PACKAGE: '已打包',
                    SORTING: '分拣中', SORTED: '分拣中', OUT: '已出库',
                    TRANSIT: '运输中', DELIVERING: '派送中', ARRIVED: '已到达' };
        var out = [];
        for (var i = 0; i < arr.length && i < 60; i++) {
          var it = arr[i] || {};
          var t = String(it.displayTime || it.time || '');
          var s = String(it.statusTag || it.traceStatusTip ||
                         MAP[String(it.status || '')] || it.status || '');
          var info = String(it.info || '').replace(/\s+/g, ' ');
          if (!t) continue;
          out.push({ t: t, s: s, d: info.slice(0, 150) });
        }
        /* ★ vc280：取件码的**专用字段** —— 轨迹文本里往往只有"请您凭取件码领取快件"这种
           官话（10-16 小米机实证：16/21 段轨迹全拿到，文本里一个码值都没有），
           码值在页面顶部「取件码 XXX / 复制」那张卡里，数据源就是这几个字段：
             · shipping.pickCode             驿站货架码（主力）
             · shipping.cabinetInfo.verifyCode 快递柜验证码（柜件）
             · shipping.signCodeInfo         签收码（只打日志诊断，不当码用 —— 未必是取件码）
           值可能是字符串、数字或对象 ⇒ codeStr 都试一遍。 */
        function codeStr(v){
          if (v == null) return '';
          if (typeof v === 'string' || typeof v === 'number') return String(v);
          if (typeof v === 'object') {
            var ks = ['code','pickCode','pick_code','pickupCode','pickup_code',
                      'verificationCode','verify_code','verifyCode','num','number','value'];
            for (var i = 0; i < ks.length; i++) {
              var x = v[ks[i]];
              if (x != null && String(x).length) return String(x);
            }
          }
          return '';
        }
        var ci = sp.cabinetInfo || {};
        return JSON.stringify({
          ok: out.length > 0,
          n: out.length,
          trackingNumber: String(sp.trackingNumber || ''),
          shippingName: String(sp.shippingName || ''),
          address: String(sp.address || '').slice(0, 120),
          pickCode: codeStr(sp.pickCode),
          verifyCode: codeStr(ci.verifyCode),
          signCode: codeStr(sp.signCodeInfo),
          traces: out
        });
      } catch(e) { return JSON.stringify({ ok: false, err: String(e) }); }
    })()"""

    /** 把 window.rawData 原文 + 通用扫描结果一起倒出来（诊断用） */
    private fun dumpRawJs(): String = """(function(){
      try {
        var root = window.rawData;
        var raw = '';
        try { raw = JSON.stringify(root); } catch(e) { raw = '<序列化失败: ' + e + '>'; }
        var out = [];
        var seen = 0;
        function walk(v, depth, path){
          if (depth > 14 || seen > 20000 || out.length > 80) return;
          if (v == null) return;
          if (typeof v === 'string') { seen++; return; }
          if (typeof v !== 'object') { seen++; return; }
          seen++;
          var vals = [];
          var childKeys = [];
          for (var k in v) {
            var sv = v[k];
            if (typeof sv === 'string') vals.push(k + '=' + sv);
            else if (sv && typeof sv === 'object') childKeys.push(k);
          }
          var joined = vals.join(' ~ ');
          var m = joined.match(/\d{4}-\d{2}-\d{2}[ T]?\d{0,2}:?\d{0,2}:?\d{0,2}/);
          if (m && joined.length > 8) {
            out.push({ path: path, hit: m[0], str: joined.slice(0, 500) });
          }
          for (var i = 0; i < childKeys.length; i++) {
            walk(v[childKeys[i]], depth + 1, path + '/' + childKeys[i]);
          }
        }
        walk(root, 0, 'rawData');
        return JSON.stringify({ rawLen: raw.length, raw: raw.slice(0, 110000), hits: out });
      } catch(e) { return JSON.stringify({ err: String(e) }); }
    })()"""

    /** 量出「展开」按钮在 **WebView 视口内** 的坐标（PC 再加上 wv.x/wv.y 就是屏幕坐标） */
    private fun coordsJs(): String = """(function(){
      try {
        var btn = document.querySelector('.express-show .label-show');
        if (!btn) {
          var els = document.querySelectorAll('div,span,a,button');
          for (var i = 0; i < els.length; i++) {
            if ((els[i].innerText || '').trim() === '展开') { btn = els[i]; break; }
          }
        }
        if (!btn) return JSON.stringify({ err: 'no-btn' });
        try { btn.scrollIntoView({ block: 'center' }); } catch(e) {}
        var r = btn.getBoundingClientRect();
        var vv = window.visualViewport;
        return JSON.stringify({
          found: true, left: r.left, top: r.top, w: r.width, h: r.height,
          text: (btn.innerText || '').trim(), cls: String(btn.className),
          docW: document.documentElement.clientWidth,
          docH: document.documentElement.clientHeight,
          dpr: window.devicePixelRatio,
          vvScale: vv ? vv.scale : 1,
          vvW: vv ? vv.width : 0
        });
      } catch(e) { return JSON.stringify({ err: String(e) }); }
    })()"""

    /** 等真人点完之后，每 1.5s 数一次 <li>，多出来就收工 */
    private fun pollTraces(wv: WebView, n: Int, next: () -> Unit) {
        if (n > 22) {
            Log.i(TAG, "等真人点击超时（约 33s）")
            dumpCaptures(wv) { next() }
            return
        }
        main.postDelayed({
            if (delivered) return@postDelayed
            wv.evaluateJavascript(probeJs()) { v ->
                val st = WebHook.unquoteJsString(v)
                val li = Regex("""\"liCount\":\s*(\d+)""").find(st)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                Log.i(TAG, "真人点击后轮询 #$n liCount=$li")
                if (li > 1) {
                    dumpFile("pdd_deep_dom.txt", st)
                    dumpCaptures(wv) { next() }
                } else {
                    pollTraces(wv, n + 1, next)
                }
            }
        }, 1500)
    }

    /** 点完「展开」后的状态体检：<li> 几条？按钮变成「收起」了吗？ */
    private fun probeJs(): String = """(function(){
      try {
        var lis = document.querySelectorAll('.traces-list li');
        var txt = document.body ? document.body.innerText : '';
        var btn = document.querySelector('.express-show .label-show');
        var wrap = document.querySelector('.express-traces-container');
        var dates = txt.match(/\d{4}-\d{2}-\d{2}/g) || [];
        return JSON.stringify({
          liCount: lis.length,
          btnText: btn ? (btn.innerText||'').trim() : 'none',
          btnClass: btn ? String(btn.className) : 'none',
          innerDates: dates.length,
          innerLen: txt.length,
          tracesHtml: wrap ? wrap.outerHTML.slice(0, 8000) : '',
          innerText: txt.slice(0, 6000)
        });
      } catch(e) { return JSON.stringify({ err: String(e) }); }
    })()"""

    /** 把「点展开之后」新发的请求落盘 —— 看它到底调了哪个接口 */
    private fun dumpCaptures(wv: WebView, next: () -> Unit) {
        WebHook.readCaptures(wv) { caps ->
            val sb = StringBuilder()
            sb.append("点「展开」后新增捕获 ${caps.size} 条\n\n")
            for ((u, b) in caps) {
                val n = Regex("\\d{4}-\\d{2}-\\d{2}").findAll(b).count()
                sb.append("dates=$n len=${b.length} url=${u.take(140)}\n")
                // ★ 只把「不像推荐流」的响应展开：推荐流特征是 goods_list
                if (n >= 2 && !b.contains("goods_list")) {
                    sb.append("  ★疑似轨迹: ${b.take(4000)}\n\n")
                }
            }
            Log.i(TAG, "DEEP(cap): ${sb.toString().take(700)}")
            dumpFile("pdd_deep_cap.txt", sb.toString())
            next()
        }
    }

    private fun dumpFile(name: String, text: String) {
        try {
            val d = dumpDir ?: return
            java.io.File(d, name).writeText(text)
            Log.i(TAG, "落盘 $name (${text.length} 字符)")
        } catch (e: Throwable) {
            Log.w(TAG, "落盘失败 $name: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(ctx: Context, host: Activity? = null): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // ★ 诊断模式关掉"缩放到适合宽度"：否则 CSS px ≠ 屏幕 px，坐标换算要乘系数。
            //   生产保持原样（关掉会让某些页面排版变窄，没必要动）。
            useWideViewPort = !DEBUG_DEEP
            loadWithOverviewMode = !DEBUG_DEEP
            // ★ 干净 Chrome 移动版 UA（默认 UA 带 wv) 容易被认出来），
            //   且必须与登录页/旁听页**同一个设备身份**（见 [PddChannel.BROWSER_UA]）
            userAgentString = PddChannel.BROWSER_UA
        }
        // ★ 诊断模式：网页要摆在**屏幕上**（真人点击得有真实坐标），并染个半透明底色方便肉眼确认
        wv.setBackgroundColor(if (DEBUG_DEEP) 0x66FF0000.toInt() else 0)
        // ★★ 必须给真实尺寸、并且**移出屏幕**而不是 INVISIBLE/GONE（2026-09-26 实测）
        //   走过的三种坑，按代价排序：
        //   ① 1×1 —— viewport 只有 1px，React 虚拟列表不铺开；
        //   ② GONE —— 不参与布局，页面根本不渲染；
        //   ③ INVISIBLE —— 节点都在（体检数到 18 个「查看物流」），但 Chromium 视其为不可见，
        //      不给元素布局尺寸，getBoundingClientRect() 全为 0 ⇒ 带尺寸过滤就一个都选不中。
        //   ⇒ 正解：保持 VISIBLE，用 translationX 把它推到屏幕外。布局照算，点击照派发。
        if (DEBUG_DEEP) {
            wv.layoutParams = ViewGroup.LayoutParams(430, 900)
        } else {
            wv.layoutParams = ViewGroup.LayoutParams(360, 640)
            wv.translationX = -20000f
        }
        // 有 Activity 就挂上；后台没有就不挂 —— 不挂窗口照样能跑 JS
        (host ?: (ctx as? Activity))?.let { act ->
            try {
                if (DEBUG_DEEP) (act.window?.decorView as? ViewGroup)?.addView(wv)
                // 诊断模式：挪到屏幕左上角偏下，别被状态栏盖住，也别压到系统手势区
                if (DEBUG_DEEP) {
                    wv.x = 0f
                    wv.y = 260f
                    wv.bringToFront()
                }
            } catch (t: Throwable) { SafeRun.w("PddTraceChannel", t) }
        }
        return wv
    }

    private fun detach() {
        try {
            (worker?.parent as? ViewGroup)?.removeView(worker)
            worker?.destroy()
        } catch (t: Throwable) { SafeRun.w("PddTraceChannel", t) }
        worker = null
    }

    /**
     * 从物流页纯文本里切出轨迹行（参考项目 PDD_REAL §4 的结构；容错为主）。
     * @return 时间倒序的 "时间 内容" 列表
     */
    fun parseTraceLines(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 200) continue
            // 噪声行：按钮文案 / 推荐流 / 商品价格
            if (s in setOf("复制", "展开", "收起", "查看物流", "物流详情", "订单编号", "收货地址")) continue
            if (s.contains("即将恢复") || s.contains("本店已拼") || s.contains("¥")) continue
            // ★ vc220：**必须含日期**——物流页「展开」后推荐流会铺进来（商品名如「…衣柜…」
            //   含关键词「柜」误命中，03:38 实测混进第 2 条）；而 PDD 轨迹行结构是
            //   「内容+时间」连写（运输中2026-09-26 23:24:35），必有日期 ⇒ 日期是唯一可靠判据。
            if (Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) {
                out.add(s)
            }
        }
        return out
    }

    /**
     * ★ vc365：从**原文**里抠驿站地址（不再要求行内带日期）。
     *
     * 为什么需要它：`parseTraceLines` 只保留含 `yyyy-MM-dd` 的行，而拼多多物流页的
     * 「取件地址：XX驿站」/「快件已存放至XX驿站」经常是**独立一行、不带日期**，
     * 在进入抠站名之前就被丢掉 ⇒ 卡片底部只能退回「待取件 + 时间」。
     */
    private val STATION_WORDS = listOf(
        "快递驿站", "驿站", "代收点", "快递柜", "智能柜", "自提点",
        "服务点", "服务站", "便利店", "超市"
    )

    fun stationFromText(text: String): String? {
        if (text.isBlank()) return null
        for (raw in text.split('\n')) {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 160) continue
            if (s in setOf("复制", "展开", "收起", "查看物流", "物流详情", "订单编号", "收货地址")) continue
            if (s.contains("¥") || s.contains("本店已拼") || s.contains("即将恢复")) continue
            if (s.startsWith("收货地址")) continue
            if (!STATION_WORDS.any { s.contains(it) }) continue
            if (s.contains("取件地址")) {
                Regex("取件地址[:：]\\s*([^,，。\\n]{2,60})").find(s)?.groupValues?.get(1)?.trim()
                    ?.let { if (it.length >= 2) return it.take(60) }
            }
            // 地址行可能是「菜鸟驿站-学校店」；保留整行，交给上层统一清理，
            // 不在第一个“驿站”处提前截断。
            return s.take(60)
        }
        return null
    }

    /** 详情页轨迹 → 我们的包裹 JSON 字段（供第4/5栏与后台共用） */    fun applyTo(pkg: JSONObject, r: Result) {
        try {
            if (r.waybillNo.isNotEmpty() && pkg.optString("waybillNo").isEmpty()) {
                pkg.put("waybillNo", r.waybillNo)
            }
            val lines = parseTraceLines(r.text)
            if (lines.isNotEmpty()) {
                val last = lines.first()
                pkg.put("latest", last)
                pkg.put("tip", last)
            }
            val code = r.pickupCode
            if (!code.isNullOrEmpty() && pkg.optString("pickupCode").isEmpty()) {
                pkg.put("pickupCode", code)
            }
            // 驿站/站点：先按原文找（地址行常不带日期），退回轨迹行
            val station = stationFromText(r.text)
                ?: lines.firstOrNull { it.contains("驿站") || it.contains("代收点") || it.contains("快递柜") }
            if (station != null && pkg.optString("stationText").isEmpty()) {
                pkg.put("stationText", station.take(60))
            }
        } catch (t: Throwable) { SafeRun.w("PddTraceChannel", t) }
    }
}
