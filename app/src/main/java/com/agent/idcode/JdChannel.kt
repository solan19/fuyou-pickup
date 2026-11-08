package com.agent.idcode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 京东通道：应用内登录 + 抓订单/物流。
 *
 * ## 为什么必须"旁听"而不是自己请求
 * 京东订单接口 `api.m.jd.com/client.action?functionId=order_list_m&…` 带 **h5st 签名**
 * （body 绑定、时间敏感，由京东页面自己的 JS 现算）。自算既难又天天失效。
 * ⇒ 让官方订单页自己去发请求，我们用 [WebHook] 把响应原文抄出来。
 *
 * 京东的特殊之处：订单列表走 **JSONP**（动态插 `<script src="…&callback=xxxx">`），
 * 所以除了钩 XHR/fetch，还要**把 callback 改写成我们自己的全局函数**（见 WebHook.hijackJsonp）。
 *
 * ## 页面
 * `trade.m.jd.com/order/orderlist_jdm.shtml?orderType=all` —— 移动端订单中心 SPA，
 * 上滑触发无限滚动加载，所以抓取时要"边滚边读 window.name"。
 *
 * 原始响应会落盘到 `jd_capture.txt`（受 [DEBUG_EXPORT] 控制），用于现场核对字段。
 */
object JdChannel {

    private const val TAG = "JdChannel"

    /** 调试：把抓到的原始响应写文件 */
    var DEBUG_EXPORT = true

    const val KEY_COOKIES = "jd_cookies"

    /** 「最近一次业务调用有没有被服务端否掉」。缺省 true —— 刚登录时不该被误判 */
    private const val KEY_LOGIN_OK = "jd_login_ok"
    /**
     * 登录页。
     * ★ 必须是 `/login/login` 这个完整路径：`plogin.m.jd.com` 的**根路径是京东的 404 页**
     *   （标题"页面找不到了…4S后跳转到首页"），实测 `…/login/login` 才返回真实登录页。
     */
    const val LOGIN_URL = "https://plogin.m.jd.com/login/login"
    const val ORDERS_URL =
        "https://trade.m.jd.com/order/orderlist_jdm.shtml?orderType=all&source=m_outer_jx_order"

    /** 京东相关域（cookie 注入 / 清理）。登录态是 `pt_key`/`pt_pin`（域级 .jd.com） */
    private val HOSTS = listOf(
        "https://www.jd.com", "https://jd.com", "https://m.jd.com",
        "https://api.m.jd.com", "https://plogin.m.jd.com", "https://passport.jd.com",
        "https://trade.m.jd.com", "https://wqs.jd.com", "https://home.m.jd.com",
        "https://my.m.jd.com", "https://order.m.jd.com"
    )

    /**
     * 钩子匹配范围。京东订单/物流相关接口都在这几个域下；
     * 注意 JSONP 那条路不受此正则限制（改写 callback 是全局生效的）。
     */
    private const val URL_PATTERN = "/order_list|deal_wuliu|api\\.m\\.jd\\.com|trade\\.m\\.jd\\.com/i"

    /** ★ vc360：运单号探针用 —— 在详情页里自动点「查看物流」那一下 */
    private val CLICK_WULIU_JS = """
        (function(){
          var els=document.querySelectorAll('a,div,span,button,li');
          for(var i=0;i<els.length;i++){
            var s=(els[i].innerText||'').trim();
            if(s==='查看物流'||s==='查看轨迹'||s==='物流跟踪'||s==='查看全程跟踪'||s==='物流信息'){els[i].click();return 'clicked:'+s;}
          }
          return 'notfound';
        })()
    """.trimIndent().replace("\n", "")

    /** 京东移动端 UA：订单页要移动版，另外与用户真实机型不同也算一层"换设备标识" */
    private const val JD_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-S918B Build/TP1A.220624.014) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private var worker: WebView? = null
    private var poller: Runnable? = null
    private var running = false

    /**
     * 本次抓取有没有被甩到 `plogin` 登录挑战页。
     *
     * ★ 为什么需要它：**"jar 里有没有 pt_key"不是登录有效的证据** ——
     *   我们注入的那份（可能早过期）本身就在 jar 里，所以"有 pt_key"永远成立。
     *   真正的硬证据是「**被甩到登录页 + 本次一条都没抓到**」，
     *   两个条件同时满足才判登录失效（否则风控误判的瞬时挑战页会误杀登录态）。
     */
    @Volatile
    private var challenged = false
    private val main = Handler(Looper.getMainLooper())

    /** 最近一次抓取的原始响应（途中快递栏用它做宽口径解析） */
    @Volatile
    var lastCaptures: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    private var appCtx: Context? = null

    // ---------- 登录态 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)

    fun cookies(ctx: Context): String = prefs(ctx).getString(KEY_COOKIES, "").orEmpty()

    /**
     * ★★ 登录判据：`pt_key` + `pt_pin` 齐全 **且最近一次业务调用没被服务端否掉**。
     *
     * 只看 cookie 名会"假已登录"：cookie 过期后 jar 里字段照样在，而服务端会把我们
     * 甩到 `plogin.m.jd.com` 登录挑战页 —— 抓取静默拿回 0 条，UI 却一直显示"已登录"。
     * （与拼多多 2026-09-20 那次 `PDDAccessToken` 同一个机理，判登录态不能猜 cookie 名。）
     * `markLoginDead` 由挑战页处理处回调。
     */
    fun loginOk(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOGIN_OK, true)

    fun isLoggedIn(ctx: Context): Boolean {
        val ck = cookies(ctx)
        return ck.contains("pt_key=") && ck.contains("pt_pin=") && loginOk(ctx)
    }

    /** 服务端把我们甩到登录页时调它 —— 立刻反映到 UI，别让用户以为还在登录态里 */
    fun markLoginDead(ctx: Context) {
        if (!loginOk(ctx)) return
        prefs(ctx).edit().putBoolean(KEY_LOGIN_OK, false).apply()
        Log.w(TAG, "命中登录挑战页 ⇒ 标记京东登录态失效")
    }

    fun markLoginOk(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_LOGIN_OK, true).apply()
    }

    /** 账号标签：用 pt_pin（京东的登录名，通常就是用户名） */
    fun pin(ctx: Context): String {
        val m = Regex("(?:^|;\\s*)pt_pin=([^;]+)").find(cookies(ctx))
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun saveCookies(ctx: Context, ck: String) {
        // 换了新 cookie 就认为登录有效；真失效由服务端回码/挑战页再打回去
        prefs(ctx).edit().putString(KEY_COOKIES, ck).putBoolean(KEY_LOGIN_OK, true).apply()
    }

    fun clearLogin(ctx: Context) {
        prefs(ctx).edit().remove(KEY_COOKIES).putBoolean(KEY_LOGIN_OK, true).apply()
        clearCookies()
    }

    /** 登录成功后从 jar 汇总（jd 的域很多，逐个读再合并） */
    fun captureCookiesFromJar(): String {
        val cm = CookieManager.getInstance()
        val parts = LinkedHashMap<String, String>()
        for (h in HOSTS) {
            val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
            if (ck.isEmpty()) continue
            for (kv in ck.split(';')) {
                val t = kv.trim()
                if (t.isEmpty() || !t.contains('=')) continue
                parts[t.substringBefore('=')] = t
            }
        }
        return parts.values.joinToString("; ")
    }

    /**
     * 把存的 cookie 写回 jar。
     * ★ `pt_key`/`pt_pin` 是 `.jd.com` 域级 cookie，写回时必须显式带 domain，
     *   否则会退化成 host-only，`api.m.jd.com`/`trade.m.jd.com` 就收不到（跟淘宝同一个坑）。
     */
    fun injectCookies(ck: String) {
        if (ck.isBlank()) return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for (h in HOSTS) {
            for (part in ck.split(";")) {
                val kv = part.trim()
                if (kv.isEmpty() || !kv.contains('=')) continue
                try { cm.setCookie(h, kv) } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
            }
        }
        for (part in ck.split(";")) {
            val kv = part.trim()
            if (kv.isEmpty() || !kv.contains('=')) continue
            if (kv.substringBefore('=') !in listOf("pt_key", "pt_pin", "pt_token", "pin", "thor")) continue
            try { cm.setCookie("https://www.jd.com", "$kv; domain=.jd.com; path=/") } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
        }
        cm.flush()
    }

    /** 只清京东域（绝不碰淘宝/拼多多的会话） */
    fun clearCookies() {
        val cm = CookieManager.getInstance()
        for (h in HOSTS) {
            val old = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
            if (old.isBlank()) continue
            for (part in old.split(';')) {
                val name = part.trim().substringBefore('=')
                if (name.isNotEmpty()) {
                    try { cm.setCookie(h, "$name=; Max-Age=0") } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
                }
            }
        }
        cm.flush()
    }

    // ---------- 抓取 ----------

    fun fetch(ctx: Context, onDone: (ArrayList<JSONObject>, String) -> Unit) {
        if (running) {
            onDone(ArrayList(), "正在抓取中")
            return
        }
        val ck = cookies(ctx)
        if (ck.isBlank()) {
            onDone(ArrayList(), "未登录")
            return
        }
        running = true
        challenged = false
        appCtx = ctx.applicationContext
        injectCookies(ck)

        // ★ WebView 用 Context 建：后台（Worker）里没有 Activity 也要能跑
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = JD_UA
        }
        wv.setBackgroundColor(Color.TRANSPARENT)

        // ★ hijackJsonp = true：京东订单列表走 JSONP，必须改写 callback 才抄得到
        val js = WebHook.hookJs(URL_PATTERN, hijackJsonp = true)
        val docStart = WebHook.attachDocumentStart(
            wv, js, setOf("https://trade.m.jd.com", "https://wqs.jd.com")
        )

        var finished = false
        var delivered = false
        val captures = ArrayList<Pair<String, String>>()

        /** 收尾（只执行一次）：轮询收工传空串，兜底定时器传"超时" */
        fun deliver(err: String) {
            if (delivered) return
            delivered = true
            finished = true
            finish(captures, err, onDone)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!docStart) WebHook.attachFallback(view, js)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (finished) return
                // 京东风控挑战：跳安全验证页时**静默等待**（不弹窗打断），
                // 页面内完成验证后会自己跳回订单页；顺便回存新 cookie 防再次脱线
                    if (url.contains("plogin") || url.contains("nopasswordcmcc")) {
                        Log.i(TAG, "login challenge (silent): $url")
                        challenged = true
                        main.postDelayed({
                            val fresh = captureCookiesFromJar()
                            // 页面内登录/一键登录完成后 cookie 会刷新 —— 那就回存一份。
                            // ⚠️ 但这不能反推"登录有效"：jar 里本来就有我们注入的 pt_key。
                            //    真失效由 finish() 按「被甩到登录页 + 一条没抓到」来判。
                            if (fresh.contains("pt_key=")) appCtx?.let { saveCookies(it, fresh) }
                        }, 2500)
                    }
                main.postDelayed({
                    startPolling(ctx, view, captures) { done ->
                        if (done) deliver("")
                    }
                }, 3000)
            }
        }
        attach(ctx, wv)
        worker = wv
        wv.loadUrl(ORDERS_URL)

        // 兜底：最多 50 秒（正常情况下轮询会先收工并立即 deliver，走不到这里）
        main.postDelayed({
            if (running && !delivered) deliver("超时")
        }, 50_000)
    }

    /**
     * 轮询 window.name + 上滑翻页。
     * 京东订单页是 SPA 无限滚动：不滚就不会有新请求，也就没有新数据。
     */
    private fun startPolling(
        ctx: Context, wv: WebView, captures: ArrayList<Pair<String, String>>, markDone: (Boolean) -> Unit
    ) {
        var rounds = 0
        var quiet = 0
        var total = -1
        lateinit var step: Runnable
        step = Runnable {
            if (wv !== worker) return@Runnable
            WebHook.readCaptures(wv) { got ->
                val before = captures.size
                for (g in got) if (captures.none { it.second == g.second }) captures.add(g)
                rounds++
                if (captures.size == before) quiet++ else quiet = 0
                if (DEBUG_EXPORT && got.isNotEmpty()) dump(got, "round$rounds")
                // 已拿到的条数 ≥ totalNum（京东在响应里给了总数）→ 可以收工
                if (total < 0) total = parseTotal(captures)
                val have = parse(captures).size
                if ((total > 0 && have >= total) || rounds >= 14 || quiet >= 4) {
                    markDone(true)
                    return@readCaptures
                }
                swipeUp(wv)
                poller = step
                main.postDelayed(step, 3000)
            }
        }
        poller = step
        main.postDelayed(step, 1000)
    }

    /** 京东订单页的上滑：滚动到底 + 模拟一次向上拖拽（SPA 靠 touch 事件触发加载） */
    private fun swipeUp(wv: WebView) {
        val js = """
            (function(){
              try { window.scrollTo(0, document.body ? document.body.scrollHeight : 0); } catch(e) {}
              try {
                var els = document.querySelectorAll('*');
                for (var i = 0; i < els.length; i++) {
                  var e = els[i];
                  if (e.scrollHeight > e.clientHeight + 80) { e.scrollTop = e.scrollHeight; }
                }
              } catch(e) {}
              return true;
            })()
        """.trimIndent()
        try { wv.evaluateJavascript(js, null) } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
    }

    private fun finish(
        captures: List<Pair<String, String>>, err: String,
        onDone: (ArrayList<JSONObject>, String) -> Unit
    ) {
        running = false
        poller?.let { main.removeCallbacks(it) }
        poller = null
        lastCaptures = captures.toList()
        detach()
        if (DEBUG_EXPORT) dump(captures, "final")
        val pkgs = parse(captures)
        // ★ 抓到响应但没解析出订单 = 正常空态（没有待取件），不算失败
        val reason = when {
            err.isNotEmpty() -> err
            captures.isEmpty() -> "没抓到接口响应（可能未登录或页面未加载）"
            else -> ""
        }
        Log.i(TAG, "done captures=${captures.size} pkgs=${pkgs.size} reason=$reason challenged=$challenged")
        // ★★ 登录失效的**硬证据**：被甩到登录挑战页 **且** 本次一条都没抓到。
        //   两个条件同时成立才判死 —— 单独"命中挑战页"不够（风控会瞬时挑战后自己跳回去，
        //   页面内登录也可能成功），单独"0 条"也不够（正常空态就是 0 条）。
        //   判死之后设置页/入口才会如实显示"未登录"，用户才不会对着一个假登录态干等。
        if (challenged && pkgs.isEmpty()) {
            appCtx?.let { markLoginDead(it) }
        }
        onDone(pkgs, reason)
    }

    fun cancel() {
        running = false
        poller?.let { main.removeCallbacks(it) }
        poller = null
        detach()
    }

    private fun attach(ctx: Context, wv: WebView) {
        try {
            // ★ 后台没有 Activity ⇒ 拿不到 decorView 就不挂（挂上只是为了 1x1 隐藏显示）。
            //   参考项目已验证：不挂窗口照样能跑 JS。
            val decor = (ctx as? Activity)?.window?.decorView as? ViewGroup ?: return
            decor.addView(wv, ViewGroup.LayoutParams(1, 1))
        } catch (e: Throwable) {
            Log.w(TAG, "attach failed: $e")
        }
    }

    private fun detach() {
        try {
            val wv = worker ?: return
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
        worker = null
    }

    // ---------- 解析 ----------

    /** 取件码：京东没有专用字段，只能从轨迹文本里抠（与参考项目一致） */
    private val PICKUP_RE = Regex(
        "(?:取件码|取货码|提货码|自提码|取件凭证|取件验证码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9-]{1,14})"
    )

    /** 手机号字段的候选名（京东各版本字段名不一） */
    private val PHONE_KEYS = listOf(
        "contactPhone", "receiverPhone", "mobile", "phone", "tel", "phoneNum", "consigneePhone"
    )

    /**
     * 从订单对象里挖收件手机号（给快递100 查询用，免得用户手填）。
     * 用候选名 + 递归找 11 位数字，字段名换了也不会整个失效。
     */
    private fun findPhone(node: Any?): String {
        val m = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
        fun scan(o: Any?, depth: Int): String? {
            if (depth > 4 || o == null) return null
            when (o) {
                is JSONObject -> {
                    for (k in PHONE_KEYS) {
                        val v = o.optString(k)
                        if (v.length >= 7) m.find(v)?.let { return it.value }
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        scan(o.opt(keys.next()), depth + 1)?.let { return it }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until o.length()) {
                        scan(o.opt(i), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }
        return scan(node, 0).orEmpty()
    }

    /** 订单总数（京东响应里的 totalNum，用来判断是否翻完） */
    private fun parseTotal(captures: List<Pair<String, String>>): Int {
        for ((_, body) in captures) {
            if (!body.contains("totalNum")) continue
            try {
                val b = JSONObject(body).optJSONObject("body") ?: continue
                val t = b.optInt("totalNum", -1)
                if (t > 0) return t
            } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
        }
        return -1
    }

    /**
     * 终态/异常态：这些订单不用再去取，一律不显示。
     *
     * ★ 「完成」必须在这里 —— 实测京东外卖送达后状态就是「完成」，
     *   不加的话外卖会全部混进取件码列表（同志实测反馈）。
     */
    private val DROP_STATUS = listOf(
        "取消", "关闭", "退款", "售后", "待付款", "未支付",
        "完成", "已签收", "已收货", "交易成功",
        // ★ "待评价" = 已签收后的评价期（2026-09-20 举一反三：与拼多多"交易成功混入"同构——
        //   它原来写在下面的 PICKUP_STATUS 里，会把已完成的件当取件件留在取件码页）
        "待评价"
    )

    /**
     * 取件相关状态词：只有命中这些、或本身带取件码的订单才算「要去取的件」。
     *
     * 为什么这么卡：京东订单里的**外卖**绝大多数是「完成」（被 DROP 挡掉），
     * 但"配送中"的外卖不含"派送"二字，会被下面这条白名单自然过滤掉
     * —— 比去猜"外卖类型字段"可靠得多（订单结构里 orderType/shopType 对快递和外卖是同一个值，区分不了）。
     */
    private val PICKUP_STATUS = listOf(
        "待收货", "待取件", "派送", "派件", "已到站", "待自提", "已入柜", "已放",
        "驿站", "快递柜", "自提柜"
        // ★ "待评价"已挪进 DROP_STATUS（2026-09-20 举一反三，见上）
    )

    /**
     * 解析 `body.orderList[]` → 我们的包裹卡片结构。
     *
     * 过滤是**两层**：
     *   ① 终态/异常态直接丢（含"完成"，这是外卖的主要状态）；
     *   ② 剩下的还要**像取件件**才留：要么状态命中取件词，要么已经带取件码。
     * 这样外卖（"完成"/"配送中"）进不来，而真正待取的快递不会漏。
     */
    fun parse(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for ((_, body) in captures) {
            if (!body.contains("orderList")) continue
            try {
                val b = JSONObject(body).optJSONObject("body") ?: continue
                val arr = b.optJSONArray("orderList") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val orderId = o.optString("orderId")
                    if (orderId.isEmpty() || !seen.add(orderId)) continue

                    val st = o.optJSONObject("orderStatusInfo")
                    val status = st?.optString("orderStatusName").orEmpty()
                        .ifBlank { st?.optString("stateTip").orEmpty() }
                    // ① 终态 / 异常态：不用去取
                    // ★ vc279：丢弃原因落日志 —— 本项目已三次栽在"静默丢弃"上
                    //   （vc182 手动单号被误伤 / 快递100 盲试假轨迹 / 在途不显示无法定位），
                    //   京东这条路以前一点证据都没有；有码/无码件为什么不显示，日志一眼定位。
                    if (DROP_STATUS.any { status.contains(it) }) {
                        Log.i("JdParse", "丢弃 $orderId：终态词「$status」")
                        continue
                    }

                    val prog = o.optJSONObject("progressInfo")
                    val latest = prog?.optString("content").orEmpty()
                        .ifBlank { prog?.optString("stateTip").orEmpty() }
                        .ifBlank { st?.optString("stateTip").orEmpty() }
                        .ifBlank { status }
                    val trackLink = prog?.optString("progressLink").orEmpty()
                    val ware = o.optJSONArray("wareInfoList")
                    val goods = if (ware != null && ware.length() > 0) {
                        ware.optJSONObject(0)?.optString("wareName").orEmpty()
                    } else ""
                    val code = PICKUP_RE.find(latest)?.groupValues?.get(1)?.trim().orEmpty()

                    // ② 得像「要去取的件」：有取件码，或状态命中取件词
                    val pickupLike = code.isNotEmpty() ||
                        PICKUP_STATUS.any { status.contains(it) || latest.contains(it) }
                    if (!pickupLike) {
                        Log.i("JdParse", "丢弃 $orderId：非取件态（status=$status latest=${latest.take(40)}）")
                        continue
                    }

                    val pkg = JSONObject()
                    pkg.put("src", "jd")
                    pkg.put("pickupCode", code)
                    pkg.put("waybillNo", orderId)
                    pkg.put("shelfNo", "")
                    pkg.put("upCpName", "京东")
                    pkg.put("nickName", latest)
                    pkg.put("tip", latest)
                    pkg.put("goodsName", goods)
                    pkg.put("statusText", status)
                    pkg.put("trackLink", trackLink)
                    out.add(pkg)
                }
            } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
        }
        return out
    }

    /** 途中快递栏的宽口径：一切**非终态**订单（运输中/派送中/待收货/待自提），带商品名和最新轨迹 */
    fun parseInTransit(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for ((_, body) in captures) {
            if (!body.contains("orderList")) continue
            try {
                val b = JSONObject(body).optJSONObject("body") ?: continue
                val arr = b.optJSONArray("orderList") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val orderId = o.optString("orderId")
                    if (orderId.isEmpty() || !seen.add(orderId)) continue
                    val st = o.optJSONObject("orderStatusInfo")
                    val status = st?.optString("orderStatusName").orEmpty()
                        .ifBlank { st?.optString("stateTip").orEmpty() }
                    if (DROP_STATUS.any { status.contains(it) }) continue
                    val prog = o.optJSONObject("progressInfo")
                    val latest = prog?.optString("content").orEmpty()
                        .ifBlank { st?.optString("stateTip").orEmpty() }
                    val ware = o.optJSONArray("wareInfoList")
                    val goods = if (ware != null && ware.length() > 0) {
                        ware.optJSONObject(0)?.optString("wareName").orEmpty()
                    } else ""
                    val pkg = JSONObject()
                    pkg.put("src", "jd")
                    pkg.put("goodsName", goods)
                    pkg.put("statusText", status)
                    pkg.put("latest", latest)
                    pkg.put("trackLink", prog?.optString("progressLink").orEmpty())
                    pkg.put("waybillNo", orderId)
                    // 收件手机号：给快递100 校验用（免得再让用户手填）。
                    // 字段名各版本不一，容错扫一遍常见的，取第一个 11 位数字。
                    pkg.put("phone", findPhone(o))
                    out.add(pkg)
                }
            } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
        }
        return out
    }

    /** 诊断：把 jd 各域 cookie 名 + 页面信息落盘 */
    fun diagnose(ctx: Context, pageJson: String) {
        try {
            val cm = CookieManager.getInstance()
            val sb = StringBuilder()
            sb.append("\n===== jd diag @ ").append(System.currentTimeMillis()).append(" =====\n")
            sb.append("page=").append(pageJson.take(700)).append('\n')
            for (h in HOSTS) {
                val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
                val names = ck.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
                sb.append("[").append(h).append("] n=").append(names.size)
                sb.append("  ").append(names.joinToString(",")).append('\n')
                if (ck.contains("pt_key=")) sb.append("      <-- 该域有 pt_key\n")
            }
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            java.io.File(dir, "jd_diag.txt").appendText(sb.toString())
        } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
    }

    private fun dump(captures: List<Pair<String, String>>, tag: String) {
        if (captures.isEmpty()) return
        val ctx = appCtx ?: return
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val sb = StringBuilder()
            sb.append("\n===== $tag @ ${System.currentTimeMillis()} =====\n")
            for ((u, b) in captures) {
                sb.append("--- URL: ").append(u.ifBlank { "(jsonp)" }).append('\n')
                // 留 20000：曾用 6000 把订单 JSON 截断，导致事后没法核对字段（外卖那次就吃了这个亏）
                sb.append(b.take(20_000)).append('\n')
            }
            java.io.File(dir, "jd_capture.txt").appendText(sb.toString())
        } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
    }

    /**
     * 打开**订单详情页**并回收数据（vc278 骨架 / vc279 复用）。
     *
     * 背景：订单列表 JSON（`orderList[]`）实测**没有任何地址字段**（2500 单样本全文
     * 0 个 address/consignee 字样），而无取件码的件（京东物流/顺丰）要在第 4 栏显示
     * 「已到达<地址>」（同志 10-16 需求）⇒ 地址只能在详情页里拿。
     *
     * ★ vc279 实测（deal_id=3596253016444701）：详情页旁听响应里的 `progressList[]`
     *   有一项 `progressId=="address"`，它的 `tip` 就是**完整收货地址**：
     *   {"content":"蜉蝣 153****1884","progressId":"address","tip":"河南商丘市柘城县…601"}
     *   （另有 `orderStatusInfo.status/orderStatusName`、物流页直链 progressLink，备用）
     *
     * 回调保证**必达且只达一次**：onPageFinished+2.5s 收数据；20s 硬超时按无数据收尾，
     * 调用方（含串行队列）不用管 WebView 生命周期，不会卡死后续。
     */
    private fun openDetailPage(
        ctx: Context,
        dealId: String,
        probeWaybill: Boolean = false,
        onDone: (caps: List<Pair<String, String>>, domText: String) -> Unit
    ) {
        val ck = cookies(ctx)
        if (ck.isBlank()) {
            Log.w(TAG, "详情页：未登录（cookie 空）")
            onDone(emptyList(), "")
            return
        }
        appCtx = ctx.applicationContext
        injectCookies(ck)
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = JD_UA
        }
        wv.setBackgroundColor(Color.TRANSPARENT)
        val js = WebHook.hookJs(URL_PATTERN, hijackJsonp = true)
        val docStart = WebHook.attachDocumentStart(
            wv, js, setOf("https://trade.m.jd.com", "https://wqs.jd.com")
        )
        val url = "https://trade.m.jd.com/order/n_detail_jdm.shtml" +
            "?deal_id=$dealId&source=m_inner_orderList_orderDetail"
        val caps = ArrayList<Pair<String, String>>()
        var done = false
        // 20s 硬超时：onPageFinished 没来（风控/白屏）也必须收尾，别让队列卡死
        val timeout = Runnable {
            if (done) return@Runnable
            done = true
            Log.w(TAG, "详情页 20s 超时（onPageFinished 没来）deal=$dealId")
            if (worker === wv) detach()
            onDone(caps, "")
        }
        main.postDelayed(timeout, 20_000)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                if (!docStart) WebHook.attachFallback(view, js)
            }

            override fun onPageFinished(view: WebView, u: String) {
                // 详情页是 SPA：等 2.5s 让它把数据渲染进 DOM 再一次性收（响应 + 文本）
                main.postDelayed({
                    if (done) return@postDelayed
                    WebHook.readCaptures(view) { got -> caps.addAll(got) }
                    if (!probeWaybill) {
                        done = true
                        main.removeCallbacks(timeout)
                        view.evaluateJavascript(
                            "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()"
                        ) { txt ->
                            if (worker === wv) detach()
                            onDone(caps, txt.orEmpty())
                        }
                        return@postDelayed
                    }
                    // ★ vc360：运单号探针 —— 自动点「查看物流」，让物流详情接口把运单号发出来
                    view.evaluateJavascript(CLICK_WULIU_JS) { r ->
                        Log.i(TAG, "运单号探针：点查看物流 → ${r?.trim()?.trim('"').orEmpty()}")
                    }
                    main.postDelayed({
                        if (done) return@postDelayed
                        done = true
                        main.removeCallbacks(timeout)
                        WebHook.readCaptures(view) { got -> caps.addAll(got) }
                        view.evaluateJavascript(
                            "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()"
                        ) { txt ->
                            if (worker === wv) detach()
                            onDone(caps, txt.orEmpty())
                        }
                    }, 8000)
                }, 2500)
            }
        }
        attach(ctx, wv)
        worker = wv
        wv.loadUrl(url)
        Log.i(TAG, "debug_jd_detail：打开详情页 deal_id=$dealId")
    }

    /**
     * `progressList[]` 里地址那项的正则：`progressId=="address"`，地址在 `tip`。
     * 两种键序都容错（实测是 content → progressId → tip，但不敢赌）；
     * `[^{}]` 保证不跨对象匹配，不会把邻近对象的 tip 抢过来。
     */
    private val ADDR_RE_A =
        Regex("\"progressId\"\\s*:\\s*\"address\"[^{}]{0,600}?\"tip\"\\s*:\\s*\"([^\"]+)\"")
    private val ADDR_RE_B =
        Regex("\"tip\"\\s*:\\s*\"([^\"]+)\"[^{}]{0,600}?\"progressId\"\\s*:\\s*\"address\"")

    /** 从旁听响应里抠收货地址；抠不到返回空串（调用方降级，不炸）。 */
    fun extractAddress(caps: List<Pair<String, String>>): String {
        for ((_, b) in caps) {
            if (!b.contains("progressId") || !b.contains("address")) continue
            ADDR_RE_A.find(b)?.let { return it.groupValues[1].trim() }
            ADDR_RE_B.find(b)?.let { return it.groupValues[1].trim() }
        }
        return ""
    }

    // ── vc279：收货地址缓存（无码件「已到达<地址>」的数据源，拿下后不再开页面）──
    private val addrMem = HashMap<String, String>()
    private var addrDiskLoaded = false
    private const val ADDR_FILE = "jd_address.json"
    private const val ADDR_TTL_MS = TimeGates.JD_ADDR_TTL_MS

    private fun addrFile(ctx: Context) = java.io.File(ctx.filesDir, ADDR_FILE)

    private fun loadAddrDisk(ctx: Context) {
        if (addrDiskLoaded) return
        addrDiskLoaded = true
        try {
            val f = addrFile(ctx)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val now = System.currentTimeMillis()
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                if (now - o.optLong("ts") > ADDR_TTL_MS) continue   // 超 30 天重新抓
                val a = o.optString("a")
                if (a.isNotEmpty()) addrMem[k] = a
            }
            Log.i(TAG, "地址缓存读回 ${addrMem.size} 条")
        } catch (e: Throwable) {
            Log.w(TAG, "地址缓存读取失败: ${e.message}")
        }
    }

    /** 进程内优先，没有再读磁盘（30 天内有效）。拿不到返回 null。 */
    fun cachedAddress(ctx: Context, dealId: String): String? {
        if (addrMem.containsKey(dealId)) return addrMem[dealId]
        loadAddrDisk(ctx)
        return addrMem[dealId]
    }

    /** 落一条地址（读-改-写，别把磁盘上其它 dealId 冲掉）。 */
    fun saveAddress(ctx: Context, dealId: String, addr: String) {
        if (addr.isEmpty()) return
        addrMem[dealId] = addr
        try {
            val root = JSONObject()
            val f = addrFile(ctx)
            if (f.exists()) {
                try {
                    val old = JSONObject(f.readText())
                    for (k in old.keys()) root.put(k, old.opt(k))
                } catch (t: Throwable) { SafeRun.w("JdChannel", t) }
            }
            root.put(dealId, JSONObject().put("a", addr).put("ts", System.currentTimeMillis()))
            f.writeText(root.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "地址缓存写入失败: ${e.message}")
        }
    }

    /**
     * vc279：拿订单的**收货地址**（无码件「已到达<地址>」的生产路径）。
     * 带缓存：命中直接回调，不开页面；未命中开详情页抠一次并落缓存。
     */
    fun fetchDetailAddress(ctx: Context, dealId: String, cb: (String) -> Unit) {
        cachedAddress(ctx, dealId)?.let {
            cb(it)
            return
        }
        openDetailPage(ctx, dealId) { caps, _ ->
            val addr = extractAddress(caps)
            if (addr.isNotEmpty()) saveAddress(ctx, dealId, addr)
            Log.i(TAG, "详情页地址 deal=$dealId -> ${addr.ifEmpty { "（没抠到）" }.take(50)}")
            cb(addr)
        }
    }

    /**
     * 调试（vc278）：打开**订单详情页**并落盘——旁听响应 + DOM 文本。
     * vc279：改走 [openDetailPage]（与生产路径同一套机制），并多打一行解析出的地址。
     */
    fun debugFetchDetail(ctx: Context, dealId: String, probeWaybill: Boolean = false) {
        openDetailPage(ctx, dealId, probeWaybill = probeWaybill) { caps, dom ->
            if (probeWaybill) dumpWaybill(ctx, dealId, caps, dom)
            else dumpDetail(ctx, dealId, caps, dom)
            Log.i(
                TAG,
                "debug_jd_detail：解析出的收货地址 = ${extractAddress(caps).ifEmpty { "（没抠到）" }}"
            )
        }
    }

    /** 运单号形态（京东自营 JD+数字；第三方各家前缀） */
    private val WAYBILL_PATTERNS: List<Pair<String, Regex>> = listOf(
        "京东自营" to Regex("\\bJD\\d{10,16}\\b"),
        "京东(VJ)" to Regex("\\bVJ[A-Z0-9]{6,14}\\b"),
        "圆通" to Regex("\\bYT\\d{12,16}\\b"),
        "中通" to Regex("\\bZTO\\d{10,15}\\b"),
        "申通" to Regex("\\bSTO\\d{10,15}\\b"),
        "极兔" to Regex("\\bJT\\d{12,16}\\b"),
        "顺丰" to Regex("\\bSF\\d{12,16}\\b"),
        "韵达" to Regex("\\b4300\\d{10,14}\\b")
    )

    /**
     * ★ vc360：运单号探针落盘 —— 扫一遍全部旁听响应 + DOM 文本，
     *   看有没有各家运单号形态的串。有 ⇒ 京东轨迹/取件码这两条路都能接上。
     */
    private fun dumpWaybill(ctx: Context, dealId: String, caps: List<Pair<String, String>>, domText: String) {
        try {
            val all = StringBuilder()
            for ((_, b) in caps) all.append(b).append('\n')
            all.append(domText)
            val s = all.toString()
            val found = StringBuilder()
            for ((name, re) in WAYBILL_PATTERNS) {
                val hits = re.findAll(s).map { it.value }.distinct().toList()
                if (hits.isNotEmpty()) {
                    found.append(name).append(": ").append(hits.joinToString(", ")).append('\n')
                    Log.i(TAG, "运单号探针命中 $name → ${hits.joinToString(", ")}")
                }
            }
            if (found.isEmpty()) Log.w(TAG, "运单号探针：全程无运单号命中")
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val out = StringBuilder()
            out.append("===== jd wuliu probe deal_id=$dealId @ ${System.currentTimeMillis()} =====\n")
            out.append(found.toString())
            out.append("---- captures (${caps.size}) ----\n")
            for ((u, b) in caps) {
                out.append("--- URL: ").append(u.ifBlank { "(jsonp)" }).append('\n')
                out.append(b.take(20_000)).append('\n')
            }
            out.append("---- DOM text ----\n").append(domText.take(30_000)).append('\n')
            java.io.File(dir, "jd_wuliu.txt").writeText(out.toString())
            Log.i(TAG, "运单号探针 dump -> ${dir}/jd_wuliu.txt")
        } catch (e: Throwable) {
            Log.w(TAG, "运单号探针 dump failed: ${e.message}")
        }
    }

    private fun dumpDetail(ctx: Context, dealId: String, caps: List<Pair<String, String>>, domText: String) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val sb = StringBuilder()
            sb.append("===== jd detail deal_id=$dealId @ ${System.currentTimeMillis()} =====\n")
            sb.append("---- captures (${caps.size}) ----\n")
            for ((u, b) in caps) {
                sb.append("--- URL: ").append(u.ifBlank { "(jsonp)" }).append('\n')
                sb.append(b.take(20_000)).append('\n')
            }
            sb.append("---- DOM text ----\n")
            sb.append(domText.take(30_000)).append('\n')
            java.io.File(dir, "jd_detail.txt").writeText(sb.toString())
            Log.i(TAG, "debug_jd_detail dump -> ${dir}/jd_detail.txt (caps=${caps.size})")
        } catch (e: Throwable) {
            Log.w(TAG, "debug_jd_detail dump failed: ${e.message}")
        }
    }
}
