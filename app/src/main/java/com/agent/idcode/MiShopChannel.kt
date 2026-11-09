package com.agent.idcode

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 小米商城（消费者 H5）通道 —— 小米源的**可用路线**。
 *
 * 为什么不用小米智能助理那套原生接口：它的请求体加密依赖 MIUI 系统类
 * `miui.util.CoderUtils`（非小米 ROM 里 **不存在**，实测 ClassNotFoundException），
 * 退化的本地 AES 服务端不认（回 `Decryption error`），且还要 OAID / Shizuku。
 * 而走小米商城的**网页端**，登录态与签名全由页面 JS 自己处理，我们只旁听响应
 * ⇒ **任何品牌的手机都能用**，符合"不能要求用户装 Shizuku / 用小米手机"的约束。
 *
 * 当前是**探测版**：只抓 + 落盘原始响应，解析等看清真实结构再写。
 */
object MiShopChannel {

    private const val TAG = "MiShopChannel"

    /** 小米商城订单列表（SPA） */
    private const val SHOP_URL = "https://m.mi.com/order/list"
    /** 商城订单接口（实测抓到，页面首屏只请求一次，所以由我们主动调） */
    private const val SHOP_API = "/v1/miorder/order_list"

    /**
     * 小米有品：**真实域名是 `m.xiaomiyoupin.com`**！
     * `youpin.mi.com` 会重定向到 `m.xiaomiyoupin.com/main`（实测诊断日志），
     * 所以钩子的 origins 必须包含这个域，否则注入不上、一个请求都抓不到。
     * 订单页路由 `/orderlist` 是从它主 JS bundle（1.2MB 的 mainV3_*.js）里搜出来的候选
     * —— 同批还搜到订单接口 `/mtop/mf/norder/homePage`（有品是 weex + mtop 架构）。
     */
    private const val YOUPIN_URL = "https://m.xiaomiyoupin.com/orderlist"
    private const val YOUPIN_ORIGIN = "https://m.xiaomiyoupin.com"

    /**
     * 钩子匹配的接口范围。
     * ★ 必须是 **JS 正则字面量**（含两侧斜杠与 flags）——传 `"."` 这种普通字符串会生成
     * `".".test(url)`，页面里直接抛错、钩子等于没装（踩过：captures 一直是 0）。
     */
    private const val URL_PATTERN = "/.*/i"

    private var worker: WebView? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastCaptures: List<Pair<String, String>> = emptyList()
        private set

    private fun attach(ctx: android.content.Context, wv: WebView) {
        try {
            // decorView 的实际类型不保证是 FrameLayout，按 ViewGroup 处理（跟拼多多通道一致）
            // ★ 后台（Worker）里没有 Activity ⇒ 拿不到 decorView 就不挂。
        //   挂上去只是为了 1x1 隐藏显示，而参考项目已验证：**不挂窗口照样能跑 JS**。
        val decor = (ctx as? Activity)?.window?.decorView as? ViewGroup ?: return
            decor.addView(wv, ViewGroup.LayoutParams(1, 1))
        } catch (e: Throwable) {
            Log.w(TAG, "attach failed: $e")
        }
    }

    private fun detach() {
        try {
            (worker?.parent as? ViewGroup)?.removeView(worker)
            worker?.destroy()
        } catch (t: Throwable) { SafeRun.w("MiShopChannel", t) }
        worker = null
    }

    /**
     * 抓一次（阻塞式回调，主线程里跑）。完成后把原始响应落盘到
     * `Android/data/<pkg>/files/mi_shop.txt`，adb 可直接 pull。
     */
    fun fetchDebug(
        ctx: android.content.Context,
        ordersUrl: String = SHOP_URL,
        apiPath: String = SHOP_API,
        onDone: (String) -> Unit
    ) {
        if (running) {
            onDone("正在抓取中")
            return
        }
        // ★★ vc559：**把 SP 里的 serviceToken 写回 WebView cookie**。
        //   事故（用户日志 2026-10-29）：小米"已登录"（token 有）但页面路一直跳
        //   account.xiaomi.com 登录页 ⇒ 界面报「需要登录」。原因是**两条路用的登录态不是一份**：
        //   · 接口路：直接拿 SP 的 serviceToken 塞 Cookie 头（所以判"已登录"）；
        //   · 页面路：依赖 WebView 的 cookie jar，jar 被清/被系统回收后就没了。
        //   这里在每次抓取前补种一次，jar 丢了也能自愈。
        try {
            val tok = MiChannel.token(ctx)
            val cu = MiChannel.cUserId(ctx)
            if (tok.isNotEmpty()) {
                val cm = android.webkit.CookieManager.getInstance()
                for (host in listOf("https://m.mi.com", "https://m.xiaomiyoupin.com",
                        "https://shopapi.io.mi.com", "https://api2.mi.com")) {
                    cm.setCookie(host, "serviceToken=$tok; path=/")
                    if (cu.isNotEmpty()) cm.setCookie(host, "cUserId=$cu; path=/")
                }
                cm.flush()
                Log.i(TAG, "已把 serviceToken 注入 WebView cookie（len=${tok.length}）")
            } else {
                Log.i(TAG, "没有 serviceToken，页面路大概率会跳登录页")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "注入 cookie 失败: ${t.message}")
        }
        running = true
        val captures = ArrayList<Pair<String, String>>()
        var delivered = false
        var finished = false
        var polling = false   // SPA 会多次触发 onPageFinished，必须保证只有一条轮询链
        val diagFlag = booleanArrayOf(false)   // poll 是独立函数，用数组做可变引用

        // ★ WebView 用 Context 建（不是 Activity）：后台没有 Activity 也要能跑，
        //   参考项目的无窗口抓取就是这么做的。
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.setBackgroundColor(Color.TRANSPARENT)

        val js = WebHook.hookJs(URL_PATTERN)
        val docStart = WebHook.attachDocumentStart(
            wv, js,
            // 顺丰也复用这个旁听器（抓它"我收的"页面自己发的真实请求体）
            setOf("https://m.mi.com", "https://youpin.mi.com", YOUPIN_ORIGIN, "https://www.sf-express.com")
        )

        fun deliver(err: String) {
            if (delivered) return
            delivered = true
            finished = true
            running = false
            lastCaptures = captures.toList()
            detach()
            Log.i(TAG, "done: captures=${captures.size} err=$err")
            onDone(err)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!docStart) WebHook.attachFallback(view, js)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "onPageFinished: $url")
                if (finished) return
                // 跳登录页 = jar 里没有商城登录态
                if (url != null && (url.contains("login") || url.contains("passport"))) {
                    Log.i(TAG, "页面跳到登录: $url")
                    // ★ vc558：错误文案里**不带 URL** —— 之前把整串登录地址塞进错误，
                //   界面会变成一屏大字报（真机截图实证）。URL 只进日志。
                Log.i(TAG, "需要登录，页面跳到了 $url")
                deliver("需要登录")
                    return
                }
                if (polling) return
                polling = true
                main.postDelayed({ poll(ctx, view, captures, apiPath, diagFlag, 0) { done -> if (done) deliver("") } }, 3000)
            }
        }
        attach(ctx, wv)
        worker = wv
        wv.loadUrl(ordersUrl)

        // 兜底：最多 45 秒
        main.postDelayed({ if (running && !delivered) deliver("超时") }, 45_000)
    }

    /** 轮询读捕获：每 3 秒一次，最多 12 轮；连续 3 轮没新增就收工 */
    private fun poll(
        ctx: android.content.Context, wv: WebView, captures: ArrayList<Pair<String, String>>,
        apiPath: String, diagFlag: BooleanArray, quiet: Int, cb: (Boolean) -> Unit
    ) {
        // 关键：订单接口 `/v1/miorder/order_list` 是**首屏一次性请求**，钩子常常赶不上
        // （埋点因为是持续上报才被抓到）。所以第一轮直接**用页面自己的登录态主动请求一次**。
        if (quiet == 0 && apiPath.isNotEmpty()) {
            val jsPath = apiPath.replace("'", "")
            wv.evaluateJavascript(
                "(function(){try{if(window.__miFetched)return 'skip';window.__miFetched=true;" +
                    "fetch('$jsPath',{credentials:'include'," +
                    "headers:{'Accept':'application/json, text/plain, */*'}})" +
                    ".then(function(r){return r.text()})" +
                    ".then(function(t){window.__hookPush('$jsPath',t)})" +
                    ".catch(function(e){window.__hookPush('$jsPath','ERR '+e)});" +
                    "return 'sent'}catch(e){return 'EX '+e}})()"
            ) { v -> Log.i(TAG, "active fetch: ${WebHook.unquoteJsString(v)}") }
        }
        // 第一轮先看清页面状态：标题 / 实际落在哪一页 / 发过的请求 / 页面里的订单相关链接
        // ★ performance 能列出页面发过的**所有**请求 —— 钩子若漏了某个请求，靠它也能揪出接口地址；
        //   链接列表用来找有品的"我的订单"真实路由（SPA 猜路由会渲染空页）
        if (quiet == 0 && !diagFlag[0]) {
            diagFlag[0] = true
            wv.evaluateJavascript(
                "(function(){var b=document.body?document.body.innerText:'';" +
                    "var rs=(performance.getEntriesByType?performance.getEntriesByType('resource'):[])" +
                    ".map(function(e){return e.name}).filter(function(u){return /order|list|api|json/i.test(u)}).slice(-14);" +
                    "var ls=[].slice.call(document.querySelectorAll('a'))" +
                    ".map(function(a){return String(a.getAttribute('href')||'')})" +
                    ".filter(function(h){return h && /order|user|my|mine|account/i.test(h)}).slice(0,12);" +
                    "return JSON.stringify({t:document.title,l:location.href,reqs:rs,links:ls," +
                    "txt:b.replace(/\\s+/g,' ').slice(0,140)});})()"
            ) { v -> Log.i(TAG, "page: ${WebHook.unquoteJsString(v)}") }
        }
        WebHook.readCaptures(wv) { got ->
            val before = captures.size
            captures.addAll(got)
            val grew = captures.size > before
            Log.i(TAG, "poll: +${captures.size - before} (total=${captures.size})")
            // 让它再滚一下，促发"加载更多"类接口（订单页通常首屏就够，滚动是保险）
            try {
                if (captures.isEmpty() || grew) wv.scrollBy(0, 400)
            } catch (t: Throwable) { SafeRun.w("MiShopChannel", t) }
            val nextQuiet = if (grew) 0 else quiet + 1
            // 判 4 轮（≈12 秒）：主动 fetch 的响应要留够时间回来，别急着收工
            if (nextQuiet >= 4 || captures.size >= 40) {
                cb(true)
            } else {
                main.postDelayed({ poll(ctx, wv, captures, apiPath, diagFlag, nextQuiet, cb) }, 3000)
            }
        }
    }

    /** 把抓到的原始响应落盘（每 6 小时最多跑一次，避免频繁请求） */
    fun tryDebugIfDue(ctx: android.content.Context, onDone: (() -> Unit)? = null) {
        val prefs = ctx.getSharedPreferences("idcode_prefs", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_TRIED, 0L) < 30_000L) {
            onDone?.invoke()
            return
        }
        prefs.edit().putLong(KEY_TRIED, now).apply()
        // ★ 以前这里要求 ctx 必须是 Activity（要拿 decorView 挂 WebView）。
        //   现在 attach() 在没有 Activity 时会跳过挂载（无窗口 WebView 照样能跑 JS），
        //   所以后台也可以走这条 —— 那道 Activity 门槛就撤了。

        // 依次抓两个站点：小米商城（已知接口）→ 小米有品（接口待探测，先不主动 fetch）
        fetchDebug(ctx, SHOP_URL, SHOP_API) { err1 ->
            val shopCaps = lastCaptures
            fetchDebug(ctx, YOUPIN_URL, "") { err2 ->
                val youpinCaps = lastCaptures
                try {
                    val f = java.io.File(ctx.getExternalFilesDir(null), "mi_shop.txt")
                    val sb = StringBuilder("shop=$err1 (${shopCaps.size})  youpin=$err2 (${youpinCaps.size})\n\n")
                    for ((tag, caps) in listOf("MI" to shopCaps, "YOUPIN" to youpinCaps)) {
                        sb.append("######## ").append(tag).append(" ########\n")
                        for ((u, b) in caps) {
                            sb.append("--- URL: ").append(u.ifBlank { "(jsonp)" }).append('\n')
                            sb.append(b.take(20_000)).append("\n\n")
                        }
                    }
                    f.writeText(sb.toString())
                    Log.i(TAG, "dump -> ${f.absolutePath}")
                } catch (e: Throwable) {
                    Log.w(TAG, "dump failed: ${e.message}")
                }
                // ★ 完成后再放行下一个抓取（顺丰的旁听复用同一个抓取器，必须串行，否则撞 running 标志）
                onDone?.invoke()
            }
        }
    }

    private const val KEY_TRIED = "mi_shop_tried_at"

    // ---------- 解析 ----------

    /**
     * 小米商城的终态：这些订单不用再去取。
     * ★ vc345（同志 2026-10-20 报「在途快递混进几年前的件」）：
     *   旧表漏了 **「已收货」** —— 小米商城 2020 年的老单文案正是「已收货」，
     *   于是 5 年前的电视、退款单全被当成在途刷出来。补齐签收/评价类终态词。
     */
    private val DROP_STATUS = listOf(
        "已取货", "已收货", "已签收", "已退款", "退款成功", "已完成", "已取消",
        "交易关闭", "已关闭", "待评价", "已评价", "交易成功", "已失效"
    )

    /** ★ vc616：订单年龄上限与拼多多/裁决器同一口径（15 天），改天数只改 PickupVerdict */
    private val MAX_AGE_MS = PickupVerdict.MAX_ORDER_AGE_DAYS * 24 * 3600 * 1000

    /** 终态：文案命中，或商城 order_status=50（实测 50 = 已取货/已完成） */
    private fun isTerminal(p: JSONObject): Boolean {
        val st = p.optString("statusText")
        if (DROP_STATUS.any { st.contains(it) }) return true
        return p.optInt("miStatus", -1) == 50
    }

    /** 太老：add_time（秒或毫秒）超过 15 天 */
    private fun isAncient(p: JSONObject): Boolean {
        val t = p.optLong("miAddTime", 0L)
        if (t <= 0L) return false
        val ms = if (t > 100_000_000_000L) t else t * 1000L
        return System.currentTimeMillis() - ms > MAX_AGE_MS
    }

    /**
     * ★ vc346：**渲染层兜底** —— 缓存（transit_cache.json）里可能还躺着旧小米单，
     * 不经过 parseInTransit。渲染前用它再筛一次，避免"抓取修好了、旧卡还挂几天"。
     */
    fun isStaleForTransit(pkg: JSONObject): Boolean = isTerminal(pkg) || isAncient(pkg)

    /** 取第一个非空数字字段（有品字段名不统一） */
    private fun pickLong(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            val v = o.optLong(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }

    /**
     * 两个站点的捕获一起解析（商城 + 有品），上层不用关心来源。
     * `data.list[]` → 包裹结构（宽口径：商品名/店铺/状态/自提码/订单号）。
     * 字段实测自 `/v1/miorder/order_list`（见 memory 里的抓包样本）。
     */
    fun parseOrders(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = parseShopOrders(captures)
        out.addAll(parseYoupinOrders(captures))
        return out
    }

    /** 小米商城：`/v1/miorder/order_list` */
    private fun parseShopOrders(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for ((_, body) in captures) {
            // 只认商城的响应（有品那条没有 order_status_info，别互相误解析）
            if (!body.contains("order_id") || !body.contains("order_status_info")) continue
            try {
                val root = JSONObject(body.substring(body.indexOf('{')))
                val list = root.optJSONObject("data")?.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val id = o.optString("order_id")
                    if (id.isEmpty() || !seen.add(id)) continue

                    val info = o.optJSONObject("order_status_info")?.optString("info").orEmpty()
                    val goods = o.optJSONArray("product")?.optJSONObject(0)
                        ?.optString("product_name").orEmpty()
                    val shop = o.optJSONObject("shop")?.optString("name").orEmpty()
                    val pick = o.optJSONObject("pickup_info")
                    val code = pick?.optString("pick_up_code").orEmpty()

                    val pkg = JSONObject()
                    pkg.put("src", "mi")
                    pkg.put("goodsName", goods)
                    pkg.put("shopName", shop)
                    pkg.put("statusText", info)
                    pkg.put("pickupCode", code)
                    pkg.put("isPickUp", pick?.optBoolean("is_pick_up", false) ?: false)
                    pkg.put("waybillNo", id)
                    pkg.put("latest", if (shop.isNotEmpty()) "$shop · $info" else info)
                    pkg.put("trackLink", "")
                    pkg.put("phone", "")
                    // 第 4/5 栏卡片会读这些字段，补齐免得显示空白
                    pkg.put("upCpName", "小米商城")
                    pkg.put("nickName", info)
                    pkg.put("tip", info)
                    pkg.put("shelfNo", "")
                    // ★ vc345：终态/年龄判据（旧单混进途中栏的修复）
                    pkg.put("miStatus", o.optInt("order_status", -1))
                    pkg.put("miAddTime", o.optLong("add_time", 0L))
                    out.add(pkg)
                }
            } catch (t: Throwable) { SafeRun.w("MiShopChannel", t) }
        }
        return out
    }

    /** 取第一个非空字段（mtop 各接口字段名不统一，容错用） */
    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotEmpty() && v != "null") return v
        }
        return ""
    }

    /** 从订单里掏出第一个商品名（列表字段名同样不统一） */
    private fun firstGoodsName(o: JSONObject): String {
        for (k in listOf("items", "goodsList", "products", "goods", "orderItems", "subOrders", "skuList")) {
            val a = o.optJSONArray(k) ?: continue
            val g = a.optJSONObject(0) ?: continue
            val n = pick(g, "goodsName", "productName", "name", "title", "goodsTitle")
            if (n.isNotEmpty()) return n
        }
        return pick(o, "goodsName", "productName", "orderName", "title")
    }

    /**
     * 小米有品：`/mtop/order/orderinfo/list` → 包裹。
     *
     * ★ **字段名是容错的**：同志有品里没有订单，拿到的响应是
     * `{"code":0,"data":{"totalPage":0,"totalCount":0,...}}` —— **没有样本**，
     * 所以这里把 mtop 常见的几种字段名都试一遍，等真有订单时再按实际校正。
     */
    fun parseYoupinOrders(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for ((u, body) in captures) {
            if (!u.contains("orderinfo/list") && !u.contains("myOrderAllOrder")) continue
            if (!body.contains("\"code\":0")) continue
            try {
                val root = JSONObject(body.substring(body.indexOf('{')))
                val data = root.optJSONObject("data") ?: continue
                val arr = listOf("list", "orderList", "result", "orders", "orderInfoList", "orderListV2")
                    .firstNotNullOfOrNull { data.optJSONArray(it) } ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = pick(o, "orderId", "order_id", "orderNo", "id")
                    if (id.isEmpty() || !seen.add(id)) continue

                    val status = pick(o, "statusName", "statusText", "statusDesc", "statusDescName", "status")
                    val goods = firstGoodsName(o)
                    val code = pick(o, "pickupCode", "pickUpCode", "pickCode", "pick_code", "selfPickCode")

                    val pkg = JSONObject()
                    pkg.put("src", "mi")
                    pkg.put("goodsName", goods)
                    pkg.put("shopName", "小米有品")
                    pkg.put("statusText", status)
                    pkg.put("pickupCode", code)
                    pkg.put("isPickUp", code.isNotEmpty())
                    pkg.put("waybillNo", id)
                    pkg.put("latest", if (status.isNotEmpty()) "小米有品 · $status" else "小米有品")
                    pkg.put("trackLink", "")
                    pkg.put("phone", "")
                    pkg.put("upCpName", "小米有品")
                    pkg.put("nickName", status)
                    pkg.put("tip", status)
                    pkg.put("shelfNo", "")
                    // ★ vc345：有品的字段名没样本，几种时间键都试一遍；拿不到就只按文案过滤
                    pkg.put("miAddTime", pickLong(
                        o, "addTime", "add_time", "createTime", "create_time",
                        "orderTime", "order_time", "timestamp"
                    ))
                    out.add(pkg)
                }
            } catch (t: Throwable) { SafeRun.w("MiShopChannel", t) }
        }
        return out
    }

    /** 取件码栏口径：有自提码 + 非终态 + 不太老（已取/已收货的老单不能再上码栏） */
    fun parsePickup(captures: List<Pair<String, String>>): ArrayList<JSONObject> =
        ArrayList(parseOrders(captures).filter {
            it.optString("pickupCode").isNotEmpty() && !isTerminal(it) && !isAncient(it)
        })

    /** 途中栏口径：非终态 + 不太老（2020 年的老单不许再算"在途"） */
    fun parseInTransit(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val all = parseOrders(captures)
        val out = ArrayList<JSONObject>()
        for (p in all) {
            val st = p.optString("statusText")
            val t = p.optLong("miAddTime", 0L)
            when {
                isTerminal(p) -> Log.i(
                    TAG,
                    "途中丢弃 ${p.optString("waybillNo")}：终态「$st」（status=${p.optInt("miStatus", -1)}）"
                )
                isAncient(p) -> Log.i(
                    TAG,
                    "途中丢弃 ${p.optString("waybillNo")}：下单 ${fmtDay(t)}，超 ${PickupVerdict.MAX_ORDER_AGE_DAYS} 天"
                )
                else -> out.add(p)
            }
        }
        Log.i(TAG, "途中解析：${all.size} 单 → ${out.size} 条（终态/老单已剔除）")
        return out
    }

    private fun fmtDay(t: Long): String {
        if (t <= 0L) return "未知"
        val ms = if (t > 100_000_000_000L) t else t * 1000L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
        } catch (_: Throwable) {
            "未知"
        }
    }

    /** 正式抓取：依次打开「小米商城」和「小米有品」订单页并旁听，回调给合并后的原始捕获 */
    fun fetch(ctx: android.content.Context, onDone: (List<Pair<String, String>>, String) -> Unit) {
        fetchDebug(ctx, SHOP_URL, SHOP_API) { e1 ->
            val shop = lastCaptures
            fetchDebug(ctx, YOUPIN_URL, "") { e2 ->
                val err = when {
                    e1.isEmpty() && e2.isEmpty() -> ""
                    e1.isNotEmpty() && e2.isNotEmpty() -> "商城 $e1 / 有品 $e2"
                    e1.isNotEmpty() -> "商城 $e1"
                    else -> "有品 $e2"
                }
                onDone(shop + lastCaptures, err)
            }
        }
    }
}
