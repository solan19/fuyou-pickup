package com.agent.idcode

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * ★ vc303：拼多多「我的快递」跨平台取件码查询（三级兜底链的一级）。
 *
 * ## 它解决什么
 * 拼多多按**收件手机号**聚合了全平台快递（淘宝单、京东单都查得到），页面上直接显示
 * **驿站格口码**（如 59-6-6012）和驿站名 —— 比快递商官方短码（单号后5位，43943）更实用。
 * 当某单的取件码疑似官方短码（= 单号后5位）时，来这里查真实码。
 *
 * ## 实现：页面文本读取（不抓接口）
 * 页面在纯 WebView 里渲染正常（手机 UA + 登录 cookie），搜索结果直接画在 DOM 里
 * （「取件码 59-6-6012」「XX驿站」）⇒ **读 innerText 用正则抠**，绕开接口逆向。
 * （隐形 XHR 旁听路线实测 4 轮全空 —— 1x1 WebView 里该页面行为不稳定，见当日 memory；
 *   文本读取不依赖接口捕获，页面渲染出来就能读。）
 *
 * ## 三个不能省的点（都是项目里验证过的成熟手法）
 * 1. **WebView 必须主线程建**，全尺寸挂 decorView 后 **translationX 移出屏幕** ——
 *    1x1 会踩「页面不初始化」的玄学坑；全尺寸移出屏 = 布局正常 + 用户看不见。
 * 2. **手机 UA**（不设桌面 UA）—— 页面按 UA 分流渲染，桌面 UA 出来的是 PC 个人中心。
 * 3. **CookieManager 全局 jar 直接用**（登录后的 cookie 已在 jar 里），只兜底注入 SP 存档。
 *
 * ## 查询动作
 * 注入 JS：找搜索输入框（placeholder 含 单号/物流）→ React 受控注入单号 →
 * input/change 事件 → 回车 + 点「搜索/查询」→ 等 8s/15s 两次读 innerText → 正则抠码。
 */
object PddExpressChannel {

    private const val TAG = "PddExpress"
    private val main = Handler(Looper.getMainLooper())

    /** 查询结果 */
    class Result(
        val code: String,      // 抠到的取件码（"" = 没拿到）
        val station: String,   // 驿站名（"" = 没拿到）
        val pageText: String,  // 页面文本（诊断用，落盘）
        val pageUrl: String    // 最终 URL（判断是否被跳登录/风控页）
    )

    // ================= 纯 HTTP 直连（生产路径，<1.5 秒）=================

    /**
     * 「我的快递」列表条目 —— **跨平台件**（拼多多按收件手机号聚合，淘宝/京东单都在）。
     * 数据源：POST api/express_trackbox/list（页面 H5 同款接口）。
     * ★ vc303 实测（真机 18:05）：**服务端不校验 anti-content**（PddNative 历史结论复用），
     *   带登录 cookie 直连 HTTP 200，一次请求 ~0.5s —— 满足同志 5 秒硬指标。
     */
    class ListEntry(
        val trackingNumber: String,   // 运单号（跨平台：JT/圆通/邮政…都有）
        val statusDesc: String,       // 待取件 / 已签收 / 派件中…
        val trackDesc: String,        // 完整物流文案（含驿站地址/电话）
        val station: String,          // 抠出的驿站全称（"取件地址:"段）
        val shippingId: Long,
        /** 文案里若出现格口码（形如 59-6-6012）也一并抠出 —— 搜索把件加入列表后可能带码 */
        val pickupCode: String = "",
        /** 拼多多订单号（自家单数字号 / 跨平台件 32 位映射号）—— cabinet 接口的入参 */
        val orderSn: String = "",
        /** 官方详情直链（相对路径）—— 直开详情页时优先用，比手拼 URL 参数全 */
        val jumpUrl: String = ""
    )

    /** 格口码形态：59-6-6012 / 31-5-7006 一类（含两段连字符） */
    private val BOX_CODE_RE = Regex("(\\d{1,4}-[A-Za-z0-9]{1,4}-\\d{2,6})")

    /** 最近一次列表查询时间（限频：10 分钟内不重复打） */
    @Volatile
    private var lastListAt = 0L

    /**
     * 纯 HTTP 拉「我的快递」全量列表（登录 cookie 鉴权，无签名要求）。
     * 回调在主线程。失败/超时回调空列表（不影响调用方其它逻辑）。
     */
    fun fetchList(act: Activity, force: Boolean = false, onDone: (List<ListEntry>) -> Unit) {
        val now = System.currentTimeMillis()
        if (!force && now - lastListAt < 10L * 60_000L) {
            Log.i(TAG, "列表查询 10 分钟内已做过，跳过（限频）")
            onDone(emptyList())
            return
        }
        Thread {
            val out = ArrayList<ListEntry>()
            var err = ""
            try {
                lastListAt = now
                val ck = PddChannel.cookies(act)
                val uid = PddChannel.uid(act)
                val url = "https://mobile.yangkeduo.com/proxy/api/api/express_trackbox/list?pdduid=$uid"
                val body = "{\"offset\":0,\"count\":20,\"channel\":\"express_box_my_express\"," +
                        "\"entry_source\":null,\"status_type\":0,\"query_type\":0,\"anti_content\":\"x\"}"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Cookie", ck)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Referer", "https://mobile.yangkeduo.com/psnl_goods_help.html")
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val arr = org.json.JSONObject(resp).optJSONObject("result")
                    ?.optJSONArray("track_info_list")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val desc = o.optString("track_desc")
                        // 地址字段本身到逗号/句号才结束；不能在第一个“驿站”处收口，
                        // 否则「菜鸟驿站-抚州幼儿高等专科学校店」会被截成「菜鸟驿站」。
                        val station = Regex("取件地址[:：]\\s*([^,，。\\n]{2,60})")
                            .find(desc)?.groupValues?.get(1)?.trim()
                            ?.takeIf { it.contains("驿站") || it.contains("代收点") ||
                                it.contains("快递柜") || it.contains("兔喜") || it.contains("超市") }
                            .orEmpty()
                        out.add(
                            ListEntry(
                                o.optString("tracking_number"),
                                o.optString("status_type_desc"),
                                desc,
                                station,
                                o.optLong("shipping_id"),
                                BOX_CODE_RE.find(desc)?.groupValues?.get(1).orEmpty(),
                                o.optString("order_sn"),
                                o.optString("jump_url")
                            )
                        )
                    }
                } else {
                    err = "resp 无 track_info_list（HTTP $code）"
                }
                Log.i(TAG, "列表直连：${out.size} 条（带驿站 ${out.count { it.station.isNotEmpty() }}）err=$err")
            } catch (t: Throwable) {
                err = t.message ?: "异常"
                Log.w(TAG, "fetchList 失败: $err")
            }
            val result = out.toList()
            main.post { onDone(result) }
        }.start()
    }

    /**
     * ★ vc315 调试：把「我的快递」全量列表落盘（`pdd_list.txt`，adb 可直接 pull）。
     * 用途：确认"取走的件在这张表里到底是什么状态文案"——判定规则就按它写，不靠猜。
     */
    fun dumpList(ctx: android.content.Context, list: List<ListEntry>) {
        try {
            val sb = StringBuilder()
            sb.append("now=").append(System.currentTimeMillis())
                .append(" size=").append(list.size).append('\n')
            for (e in list) {
                sb.append(e.trackingNumber).append(" | ").append(e.statusDesc)
                    .append(" | sn=").append(e.orderSn)
                    .append(" | code=").append(e.pickupCode)
                    .append(" | station=").append(e.station)
                    .append(" | ").append(e.trackDesc.replace("\n", " ").take(180))
                    .append('\n')
            }
            val f = java.io.File(ctx.getExternalFilesDir(null), "pdd_list.txt")
            f.writeText(sb.toString())
            Log.i(TAG, "列表已落盘 -> ${f.absolutePath}")
        } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
    }

    // ================= DOM 诊断（vc303：查输入框真实形态）=================

    /**
     * 打开「我的快递」页并枚举所有可输入元素 —— 定位「JS 合成事件填不进单号」的根因：
     * 输入框可能是 contenteditable div / 在跨域 iframe 里 / 被 shadow DOM 包着。
     * 诊断 JSON 直接 Log + 落盘 pdd_dom_probe.txt。
     */
    fun probeDom(act: Activity) {
        if (running) return
        running = true
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            try {
                val ck = PddChannel.cookies(act)
                if (ck.isNotEmpty()) {
                    CookieManager.getInstance().setCookie("https://mobile.yangkeduo.com/", ck)
                    CookieManager.getInstance().flush()
                }
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            var done = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "诊断页加载完成：${url?.take(80)}")
                    main.postDelayed({
                        if (done) return@postDelayed
                        done = true
                        // ⚠️ 严禁 // 行注释
                        val js = """
                            (function(){
                              var inputs=[], ifr=[], ce=[];
                              document.querySelectorAll('input,textarea').forEach(function(el,i){
                                inputs.push({i:i, type:el.type||'', ph:(el.placeholder||'').slice(0,30),
                                  vis: el.offsetWidth+'x'+el.offsetHeight,
                                  html: el.outerHTML.slice(0,120)});
                              });
                              document.querySelectorAll('iframe').forEach(function(f,i){
                                try{
                                  ifr.push({i:i, src:(f.src||'').slice(0,80),
                                    sameOrigin: !!f.contentDocument});
                                }catch(e){ ifr.push({i:i, src:(f.src||'').slice(0,80), sameOrigin:false}); }
                              });
                              document.querySelectorAll('[contenteditable=true]').forEach(function(el,i){
                                ce.push({i:i, html: el.outerHTML.slice(0,120)});
                              });
                              return JSON.stringify({inputs:inputs, iframes:ifr, contenteditable:ce,
                                url: location.href.slice(0,90), title: document.title.slice(0,40)});
                            })()
                        """.trimIndent()
                        view.evaluateJavascript(js) { r ->
                            val text = WebHook.unquoteJsString(r)
                            Log.i(TAG, "DOM 诊断：$text")
                            try {
                                java.io.File(
                                    act.getExternalFilesDir(null), "pdd_dom_probe.txt"
                                ).writeText(text)
                            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                            try {
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.destroy()
                            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                            running = false
                        }
                    }, 6000)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            wv.loadUrl(psnlUrl())
            main.postDelayed({
                if (!done) {
                    done = true
                    running = false
                    try {
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                }
            }, 30000)
        }
    }

    // ================= 直连侦察（路线 B：详情接口试探 + 详情页真实流量捕获）=================

    @Volatile
    private var probing = false

    /**
     * ★ 路线 B 侦察（同志拍板：一条路走到黑）。一次构建同时拿两类材料：
     * ① 纯 HTTP 试探 express_trackbox 族「详情接口」—— 上次 36 组全 403 有两个嫌疑：
     *    基座打错（真基座可能是 api.pinduoduo.com/api/express_trackbox/，mdkd 桥接日志实证）
     *    + 样例 shipping_id=384 可能早已失效。这次两个基座 × 真实列表单数据重试。
     * ② WebView 直开 assistant_waybill 详情页 + document-start 钩子 —— 抓页面**自己**发的
     *    接口（响应里若有格口码即锁定载体），同时抓 innerText/HTML 全文/数据池三路原文。
     * 落盘：pdd_direct_probe.txt（通道1） / pdd_direct_probe_web.txt（通道2）。
     */
    fun directProbe(act: Activity) {
        if (probing) return
        probing = true
        fetchList(act, force = true) { list ->
            val arrivedKw = listOf("代收", "驿站", "待取", "快递柜", "已到达", "已存放", "入柜")
            val arrived = list.filter { e -> arrivedKw.any { it in e.statusDesc } }
            val hit = arrived.firstOrNull() ?: list.firstOrNull()
            if (hit == null) {
                Log.w(TAG, "directProbe：列表空，无法侦察")
                probing = false
                return@fetchList
            }
            val sid = hit.shippingId
            val tn = hit.trackingNumber
            Log.i(TAG, "directProbe：选单 $tn (shipping_id=$sid, ${hit.statusDesc})")
            val detailUrl =
                "https://mobile.yangkeduo.com/psnl_goods_help.html?_t_module_name=assistant_waybill" +
                        "&shipping_id=$sid&tracking_number=$tn"
            // ── 通道 2 先启动：给详情页加载时间，通道 1 在子线程并行打接口 ──
            probeWeb(act, detailUrl)
            Thread {
                val ck = PddChannel.cookies(act)
                val uid = PddChannel.uid(act)
                // AccessToken 候选：cookie 值里 40~64 长的（mdkd 桥接日志实证该头存在，59 字符）
                val token = Regex("(?:^|;\\s*)[A-Za-z_][A-Za-z0-9_]*=([A-Za-z0-9]{40,64})(?:;|$)")
                    .find(ck)?.groupValues?.get(1).orEmpty()
                val sb = StringBuilder()
                sb.append("=== directProbe @ ").append(System.currentTimeMillis()).append("\n")
                sb.append("=== 选单: $tn sid=$sid status=${hit.statusDesc}（到站 ${arrived.size}/${list.size}）\n")
                sb.append("=== token候选: ").append(if (token.isEmpty()) "无" else token.take(13) + "…(len=${token.length})").append("\n\n")
                val bases = listOf(
                    "https://api.pinduoduo.com/api/express_trackbox/",
                    "https://mobile.yangkeduo.com/proxy/api/api/express_trackbox/"
                )
                val names = listOf(
                    "detail", "info", "query", "get", "track_detail", "waybill_detail",
                    "express_detail", "package_detail", "detail_v2", "get_express_info"
                )
                val bodies = listOf(
                    "{\"shipping_id\":$sid,\"tracking_number\":\"$tn\"}",
                    "{\"shipping_id\":$sid}",
                    "{\"shipping_id\":$sid,\"tracking_number\":\"$tn\",\"channel\":\"express_box_my_express\"}"
                )
                val boxRe = Regex("\\d{1,4}-[A-Za-z0-9]{1,4}-\\d{2,6}")
                var stars = 0
                for (base in bases) {
                    for (n in names) {
                        val url = base + n + "?pdduid=$uid"
                        val b = bodies.first()
                        val (code, resp) = httpPost(url, b, ck, token, detailUrl)
                        val mark = when {
                            boxRe.containsMatchIn(resp) && !Regex("20\\d\\d-").containsMatchIn(resp) -> " ★★疑似格口码!"
                            resp.contains("取件码") -> " ★含取件码"
                            resp.contains("\"success\":true") -> " 有数据"
                            else -> ""
                        }
                        if (mark.contains("★")) stars++
                        Log.i(TAG, "试探 ${base.substringAfter("//").substringBefore(".com")}/$n HTTP $code len=${resp.length}$mark")
                        sb.append("=== $n @ ").append(base.substringAfter("//")).append("\nbody: $b\nHTTP $code\n")
                            .append(resp.take(1000)).append("\n\n")
                        Thread.sleep(320)
                    }
                }
                try {
                    java.io.File(act.getExternalFilesDir(null), "pdd_direct_probe.txt").writeText(sb.toString())
                } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                Log.i(TAG, "directProbe 通道1完成 星级命中=$stars")
            }.start()
        }
    }

    private fun httpPost(url: String, body: String, ck: String, token: String, referer: String): Pair<Int, String> {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Cookie", ck)
            if (token.isNotEmpty()) conn.setRequestProperty("AccessToken", token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; PKX110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            conn.doOutput = true
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code to resp
        } catch (t: Throwable) {
            -1 to "异常: ${t.message}"
        }
    }

    /** 通道 2：直开详情页 + document-start 钩子；9s/18s 两轮收割捕获+页面三路原文，24s 收摊 */
    private fun probeWeb(act: Activity, url: String) {
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            try {
                val ck = PddChannel.cookies(act)
                if (ck.isNotEmpty()) {
                    android.webkit.CookieManager.getInstance()
                        .setCookie("https://mobile.yangkeduo.com/", ck)
                    android.webkit.CookieManager.getInstance().flush()
                }
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            WebHook.attachDocumentStart(
                wv, WebHook.hookJs("/pinduoduo|yangkeduo|express|trackbox|caterham/i"),
                setOf("https://mobile.yangkeduo.com", "https://m.pinduoduo.net", "https://api.pinduoduo.com")
            )
            var round = 0
            fun harvestWeb() {
                round++
                WebHook.readCaptures(wv) { got ->
                    wv.evaluateJavascript(
                        "(function(){var t=document.body?document.body.innerText:'';" +
                                "var h='';try{h=document.documentElement.innerHTML;}catch(e){}" +
                                "var b='';try{b=JSON.stringify(window.__ICE_SUSPENSE_LOADER__||'').slice(0,150000);}catch(e){}" +
                                "return t+'||HTML||'+h.slice(0,150000)+'||BLOB||'+b;})()"
                    ) { r ->
                        val text = WebHook.unquoteJsString(r)
                        val parts = text.split("||HTML||", "||BLOB||")
                        val out = StringBuilder()
                        out.append("\n\n======== 详情页 第").append(round).append("轮 ========\nurl=")
                            .append(url).append("\n")
                        out.append("---- innerText (len=").append(parts[0].length).append(") ----\n")
                            .append(parts[0].take(4000)).append("\n")
                        if (parts.size > 1) out.append("---- HTML (len=").append(parts[1].length)
                            .append(") ----\n").append(parts[1].take(60000)).append("\n")
                        if (parts.size > 2) out.append("---- 数据池 (len=").append(parts[2].length)
                            .append(") ----\n").append(parts[2].take(30000)).append("\n")
                        out.append("---- XHR捕获 ").append(got.size).append(" 条 ----\n")
                        got.forEach { (u, b) ->
                            out.append("--- ").append(u).append("\n    RESP: ").append(b.take(4000)).append("\n")
                        }
                        try {
                            java.io.File(act.getExternalFilesDir(null), "pdd_direct_probe_web.txt")
                                .appendText(out.toString())
                        } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                        Log.i(TAG, "probeWeb 第${round}轮: innerText=${parts[0].length} 捕获=${got.size}")
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url2: String?) {
                    Log.i(TAG, "详情页加载完成：${url2?.take(90)}")
                    main.postDelayed({ harvestWeb() }, 9000)
                    main.postDelayed({ harvestWeb() }, 18000)
                    main.postDelayed({
                        try {
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                        probing = false
                    }, 24000)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            wv.loadUrl(url)
        }
    }

    /**
     * ★ vc304 直开详情页抠真实格口码（路线 B 生产路径）。
     * assistant_waybill 直链打开（cookie 鉴权，无搜索、无坐标注入）——页面数据为
     * SSR 预置、不走 XHR（0.9.8 侦察实证），故轮询读 innerText+HTML 全文双路抠码：
     * 「取件码:」正则优先，格口码（59-6-6012）形态兜底。抠到即收，最多 5 轮（~15s），
     * 25s 强制收口。与 fetchCode 共用 running 互斥。回调主线程，必回调一次。
     */
    fun openDetailForCode(act: Activity, url: String, waybill: String, onDone: (String) -> Unit) {
        if (running) {
            onDone("")
            return
        }
        running = true
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            try {
                val ck = PddChannel.cookies(act)
                if (ck.isNotEmpty()) {
                    // ★ 两个域都要注入：assistant_pickup 取件页在 m.pinduoduo.net（真机取证），
                    //   详情页在 mobile.yangkeduo.com —— 缺一个就是游客态，页面没有码
                    android.webkit.CookieManager.getInstance()
                        .setCookie("https://mobile.yangkeduo.com/", ck)
                    android.webkit.CookieManager.getInstance()
                        .setCookie("https://m.pinduoduo.net/", ck)
                    android.webkit.CookieManager.getInstance().flush()
                }
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            var done = false
            var reads = 0
            fun deliver(code: String) {
                if (done) return
                done = true
                running = false
                Log.i(TAG, "直开详情收口：code=${code.ifBlank { "无" }} reads=$reads")
                try {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                main.post { onDone(code) }
            }
            fun grab() {
                if (done) return
                reads++
                // innerText + HTML 全文双路（预置数据可能躺在 script JSON 里）
                wv.evaluateJavascript(
                    "(function(){var t=document.body?document.body.innerText:'';" +
                            "var h='';try{h=document.documentElement.innerHTML;}catch(e){}" +
                            "return t+'||HTML||'+h.slice(0,200000);})()"
                ) { r ->
                    val text = WebHook.unquoteJsString(r)
                    // ★ 先抹掉日期串（2026-10-17）再抠 —— BOX_CODE_RE 三段式会误配时间戳
                    //   （真机 01:16 实证：len=159633 的 HTML 全文里轨迹时间多，首读即中日期）
                    val all = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
                        .replace(text.split("||HTML||").joinToString("\n"), "D")
                    // ★ vc304b：assistant_pickup 是「驿站待取件列表」（多件同屏）——
                    //   不能抓全局第一个码，要按运单号就近配对（真机取证：码与单号相邻两行）
                    val idx = all.indexOf(waybill)
                    val window = if (idx >= 0)
                        all.substring(maxOf(0, idx - 400), minOf(all.length, idx + 400))
                    else all
                    val code = Regex("取件码[:：\\s]*([0-9A-Za-z][0-9A-Za-z\\-]{3,11})")
                        .find(window)?.groupValues?.get(1).orEmpty()
                        .ifEmpty {
                            // ★ 严格格口码（0.9.11）：真码形态 59-6-6012 —— 中间段纯数字 1~2 位、
                            //   尾段 3~6 位、前后不得贴 [0-9A-Za-z-]。宽松版会误收 UUID 中段
                            //   （2254-7a57-428）与日期（2026-10-17），真机两轮实证。
                            Regex("(?<![0-9A-Za-z\\-])\\d{1,4}-\\d{1,2}-\\d{3,6}(?![0-9A-Za-z\\-])")
                                .find(window)?.value.orEmpty()
                        }
                    Log.i(TAG, "直开读取#$reads len=${text.length} win=${window.length} code=${code.ifBlank { "无" }}")
                    when {
                        code.isNotEmpty() && !PickupCodeResolver.isShortCode(waybill, code) -> deliver(code)
                        code.isNotEmpty() -> deliver("")   // 短码（单号后5位兜底值）不算战果
                        reads >= 5 -> deliver("")
                        else -> main.postDelayed({ grab() }, 3000)
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u2: String?) {
                    Log.i(TAG, "直开详情页加载完成：${u2?.take(80)}")
                    main.postDelayed({ grab() }, 3500)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            wv.loadUrl(url)
            // 兜底：页面迟迟不回（登录跳转/风控）也必须收口，防 autoQueryRunning 卡死
            main.postDelayed({ deliver("") }, 25000)
        }
    }

    /** 批量配对候选码（位置、码值、是否带「取件码」标签） */
    private class CodeCand(val pos: Int, val code: String, val labeled: Boolean)

    /**
     * ★ vc305 批量抓码：一次直开 assistant_pickup 取件页，把**驿站全部待取件**的
     * 「运单号 ↔ 格口码」配对一次抓回（页面一屏显示全部件 + 各自的码，真机取证）。
     *
     * ## 为什么能批量
     * 0.9.13 取证：该页是「驿站待取件列表」，每件 = 快递公司+单号 与 取件码 相邻两行。
     * 旧链每单各开一次页（串行 ~4s/单）；批量版开一次页读全文就近配对 ⇒
     * N 单无码也只要 ~4.5s（与单件同速）。
     *
     * ## 配对算法（贪心最近邻，一码一单）
     * ① 全文先抹日期（2026-10-17 会污染三段式正则，0.9.10 教训）；
     * ② 收集全部码出现位置：先「取件码:」前缀高置信，再严格格口码（0.9.11 版防 UUID/日期）；
     * ③ 运单号按页面位置从前到后，各取 ±600 窗口内**距离最近**且未被占用的码 ——
     *    页面天然「单号→码→单号→码」交替，最近邻即正确配对；
     * ④ 等于单号后 5 位的候选（官方短码）直接丢弃。
     *
     * ## 收口节奏
     * 配满（配对数 ≥ 单数）→ 立即收；配到一些但连续两轮结果相同 → 收（页面渲染稳定了）；
     * 5 轮 / 25s 兜底收。回调主线程，必回调一次；没配上的单不在 map 里。
     */
    fun openPickupPageBatch(
        act: Activity, waybills: List<String>, onDone: (Map<String, String>) -> Unit
    ) {
        if (waybills.isEmpty()) {
            onDone(emptyMap())
            return
        }
        if (running) {
            onDone(emptyMap())
            return
        }
        running = true
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            try {
                val ck = PddChannel.cookies(act)
                if (ck.isNotEmpty()) {
                    // ★ 双域注入：取件页在 m.pinduoduo.net、接口在 mobile.yangkeduo.com
                    CookieManager.getInstance().setCookie("https://mobile.yangkeduo.com/", ck)
                    CookieManager.getInstance().setCookie("https://m.pinduoduo.net/", ck)
                    CookieManager.getInstance().flush()
                }
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            var done = false
            var reads = 0
            var lastMap: Map<String, String> = emptyMap()
            fun deliver(result: Map<String, String>) {
                if (done) return
                done = true
                running = false
                Log.i(TAG, "批量抓码收口：${result.size}/${waybills.size} 单 reads=$reads → $result")
                try {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
                main.post { onDone(result) }
            }
            fun grab() {
                if (done) return
                reads++
                // innerText + HTML 全文双路（同 openDetailForCode，预置数据不走 XHR）
                wv.evaluateJavascript(
                    "(function(){var t=document.body?document.body.innerText:'';" +
                            "var h='';try{h=document.documentElement.innerHTML;}catch(e){}" +
                            "return t+'||HTML||'+h.slice(0,200000);})()"
                ) { r ->
                    if (done) return@evaluateJavascript
                    val text = WebHook.unquoteJsString(r)
                    // ★ 先抹日期再抠（0.9.10：日期串会被三段式正则误收）
                    val all = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
                        .replace(text.split("||HTML||").joinToString("\n"), "D")
                    val cands = ArrayList<CodeCand>()
                    // ① 高置信：明确「取件码」标签后的值（记码本身的位置）
                    for (m in Regex("取件码[:：\\s]*([0-9A-Za-z][0-9A-Za-z\\-]{3,11})").findAll(all)) {
                        val g = m.groups[1] ?: continue
                        cands.add(CodeCand(g.range.first, g.value, true))
                    }
                    // ② 严格格口码（0.9.11：中间段纯数字 + 前后边界，防 UUID 中段）
                    for (m in Regex("(?<![0-9A-Za-z\\-])\\d{1,4}-\\d{1,2}-\\d{3,6}(?![0-9A-Za-z\\-])")
                        .findAll(all)) {
                        cands.add(CodeCand(m.range.first, m.value, false))
                    }
                    // ③ 贪心最近邻：带标签的优先，一码只配一单
                    val usedPos = HashSet<Int>()
                    val result = LinkedHashMap<String, String>()
                    for (w in waybills.sortedBy { w -> all.indexOf(w).let { if (it < 0) Int.MAX_VALUE else it } }) {
                        val idx = all.indexOf(w)
                        if (idx < 0) continue
                        var best: CodeCand? = null
                        var bestScore = Int.MAX_VALUE
                        for (c in cands) {
                            if (PickupCodeResolver.isShortCode(w, c.code)) continue
                            if (usedPos.contains(c.pos)) continue
                            val dist = Math.abs(c.pos - idx)
                            if (dist > 600) continue
                            val score = dist - (if (c.labeled) 10_000 else 0)
                            if (score < bestScore) {
                                bestScore = score
                                best = c
                            }
                        }
                        if (best != null) {
                            usedPos.add(best.pos)
                            result[w] = best.code
                        }
                    }
                    Log.i(TAG, "批量读取#$reads len=${text.length} 配对=${result.size}/${waybills.size} $result")
                    when {
                        result.size >= waybills.size -> deliver(result)
                        result.isNotEmpty() && result == lastMap && reads >= 2 -> deliver(result)
                        reads >= 5 -> deliver(result)
                        else -> {
                            lastMap = result
                            main.postDelayed({ grab() }, 3000)
                        }
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u2: String?) {
                    Log.i(TAG, "批量取件页加载完成：${u2?.take(80)}")
                    main.postDelayed({ grab() }, 3500)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            wv.loadUrl("https://m.pinduoduo.net/psnl_goods_help.html?_t_module_name=assistant_pickup")
            // 兜底：页面迟迟不回（登录跳转/风控）也必须收口，防 autoQueryRunning 卡死
            main.postDelayed({ deliver(lastMap) }, 25000)
        }
    }

    /** 同一时刻只允许一次页面查询（WebView 全局 jar + decorView 只有一个） */
    @Volatile
    private var running = false
    private var worker: WebView? = null

    fun isRunning(): Boolean = running

    /**
     * 查一个运单号的真实取件码。**阻塞式回调（主线程）**，完成后必回调一次。
     * @param waybill 运单号（如 JT3180960043943）
     * @param customUrl 自定义页面 URL（如 assistant_waybill 详情页）—— null = 默认「我的快递」列表页
     * @param autoSearch 是否注入「填单号触发搜索」动作（详情页取件码直接显示，无需搜索）
     */
    fun fetchCode(
        act: Activity, waybill: String, onDone: (Result) -> Unit,
        customUrl: String? = null, autoSearch: Boolean = true
    ) {
        if (running) {
            onDone(Result("", "", "", "busy"))
            return
        }
        running = true
        main.post {
            val wv = buildWebView(act, waybill, onDone, autoSearch)
            worker = wv
            wv.loadUrl(customUrl ?: psnlUrl())
        }
    }

    /** 「我的快递」查询页 URL（自动查码流程要用，故公开） */
    fun psnlUrl(): String =
        "https://mobile.yangkeduo.com/psnl_goods_help.html?_wv=41729&_wvx=10&" +
                "_t_module_name=assistant_search&refer_scene=wechat_share" +
                "&refer_share_id=probe&refer_share_uin=probe&refer_share_channel=message"

    private fun buildWebView(
        act: Activity, waybill: String, onDone: (Result) -> Unit, autoSearch: Boolean
    ): WebView {
        val d = act.resources.displayMetrics
        val wv = WebView(act)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // ★ 手机 UA：这个页面按 UA 分流，桌面 UA 渲染成 PC 个人中心（没有搜索框）
        wv.setBackgroundColor(0x00000000)
        // 兜底注入 SP 存档 cookie（正常情况下 jar 里已有；重复 set 同值无害）
        try {
            val ck = PddChannel.cookies(act)
            if (ck.isNotEmpty()) {
                CookieManager.getInstance().setCookie("https://mobile.yangkeduo.com/", ck)
                CookieManager.getInstance().flush()
            }
        } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }

        // ★ vc303：装 XHR 旁听钩子 —— 渲染成功的那次，把「我的快递」列表接口
        //   （跨平台件+驿站地址的数据源）抓出来，供纯 HTTP 直连（5 秒指标）复现。
        val hookJs = WebHook.hookJs("/proxy\\/api|yangkeduo|pinduoduo/i")
        val docStart = WebHook.attachDocumentStart(
            wv, hookJs, setOf("https://mobile.yangkeduo.com", "https://m.pinduoduo.net")
        )
        val captures = ArrayList<Pair<String, String>>()
        var delivered = false
        fun deliver(res: Result) {
            if (delivered) return
            delivered = true
            running = false
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            worker = null
            Log.i(TAG, "查询收尾：code=${res.code} station=${res.station} url=${res.pageUrl.take(80)} 捕获=${captures.size}")
            // ★ 把捕获的接口响应也落盘（找列表接口用）
            try {
                val ctx = act.applicationContext
                val f = java.io.File(
                    act.getExternalFilesDir(null), "pdd_express_captures.txt"
                )
                f.writeText(captures.joinToString("\n\n") { (u, b) -> "--- URL: $u\n${b.take(8000)}" })
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
            onDone(res)
        }

        /**
         * 读页面文本并抠码。★ vc303 轮询式：3.5s 起每 3s 读一次、最多 12 次（~40s），
         * **抠到码立即收**；到 5 次仍无码但已有"取件地址"文案 ⇒ 也要收（列表摘要里的
         * 驿站地址已够用，格口码拿不到就回退单号后5位 —— 三级链的最终兜底）。
         */
        var reads = 0
        fun harvest() {
            reads++
            WebHook.readCaptures(wv) { got ->
                if (got.isNotEmpty()) captures.addAll(got)
                wv.evaluateJavascript("(function(){var t=document.body?document.body.innerText:'';var b='';try{b=JSON.stringify(window.__ICE_SUSPENSE_LOADER__||'').slice(0,120000);}catch(e){} return t+'||BLOB||'+b;})()") { r ->
                main.post {
                    if (!delivered) {
                        wv.evaluateJavascript("(function(){return window.__fillDebug||'no-mark'})()") { d ->
                            Log.i(TAG, "填值自检: ${WebHook.unquoteJsString(d).take(120)}")
                        }
                    }
                main.post {
                    if (delivered) return@post
                    val text = WebHook.unquoteJsString(r)
                    val code = Regex("取件码[:：\\s]*([0-9A-Za-z][0-9A-Za-z\\-]{3,11})").find(text)
                        ?.groupValues?.get(1).orEmpty()
                    // 驿站名：优先「取件地址:」后的整段（最准）；退回词表尾匹配
                    val station = Regex("取件地址[:：]([^,，。\\n]{4,50}(?:驿站|代收点|快递柜|兔喜|超市))")
                        .find(text)?.groupValues?.get(1)?.trim().orEmpty()
                        .ifEmpty {
                            Regex("([\\u4e00-\\u9fa5A-Za-z0-9\\+]{2,24}(?:驿站|快递柜|代收点|自提点|兔喜|超市|服务站|丰巢|快递超市|妈妈驿站))")
                                .find(text)?.groupValues?.get(1).orEmpty()
                        }
                    Log.i(TAG, "读取#$reads：len=${text.length} code=$code station=$station")
                    val hasAddr = text.contains("取件地址") || text.contains("驿站") || text.contains("代收点")
                    // 收口条件：拿到码 / 读满 12 次（vc303：hasAddr 提前收会错过慢渲染的取件码区）
                    if (code.isNotEmpty() || reads >= 12) {
                        deliver(Result(code, station, text.take(20000), wv.url ?: ""))
                    } else {
                        main.postDelayed({ if (!delivered) harvest() }, 3000)
                    }
                }
                }
            }
        }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "页面加载完成：${url?.take(90)}")
                // 首屏起来 → （需要时）注入搜索动作 → 轮询读文本（有码即收）
                if (autoSearch) main.postDelayed({ injectSearch(view, waybill) }, 2500)
                main.postDelayed({ if (!delivered) harvest() }, 3500)
            }
        }

        // ★ vc303：全尺寸挂 decorView **最底层（index=0）** —— 三重考量（缺一不可）：
        //   ① 必须在视口内且全尺寸 —— 拼多多页面主体是懒加载组件，1x1（innerWidth=0）
        //      或移出屏幕（IntersectionObserver 判不可见）都**永不渲染**（真机实测只有页脚）；
        //   ② z 序最底 —— 被 App 各层内容盖住，用户看不见；
        //   ③ 在最底 ⇒ 触摸事件先打到上层 App 内容，WebView 天然不拦截操作。
        try {
            (act.window?.decorView as? ViewGroup)?.addView(
                wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "挂 decorView 失败: ${e.message}")
        }
        return wv
    }

    /** 往页面里注入「填单号 → 触发搜索」的动作脚本（vc303 增强版：聚焦+完整事件序列） */
    private fun injectSearch(view: WebView, waybill: String) {
        // ⚠️ 本 JS 会被原样 evaluate —— 内部严禁 // 行注释（纪律与其它注入串一致）
        val js = """
            (function(){
              var CODE='$waybill';
              var tries=0;
              function fire(target,type){
                try{
                  var ev=new KeyboardEvent(type,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true});
                  target.dispatchEvent(ev);
                }catch(e){}
              }
              function fill(){
                tries++;
                var target=document.getElementById('assisSearchInput');
                if(!target){
                  var inputs=document.querySelectorAll('input');
                  for(var i=0;i<inputs.length;i++){
                    var ph=(inputs[i].placeholder||'')+'';
                    if(ph.indexOf('单号')>-1||ph.indexOf('物流')>-1){target=inputs[i];break;}
                  }
                }
                if(!target){ if(tries<60) setTimeout(fill,500); return; }
                try{ target.focus(); }catch(e){}
                try{
                  var desc=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(target),'value');
                  if(desc&&desc.set){ desc.set.call(target,CODE); } else { target.value=CODE; }
                  target.dispatchEvent(new Event('input',{bubbles:true}));
                  target.dispatchEvent(new Event('change',{bubbles:true}));
                  window.__fillDebug='filled tries='+tries+' value='+target.value;
                }catch(e){ window.__fillDebug='fill-err '+e.message; }
                setTimeout(function(){
                  try{
                    fire(target,'keydown'); fire(target,'keypress'); fire(target,'keyup');
                    var els=document.querySelectorAll('button,div,span,a,i,img');
                    var words=['搜索','查询','确定','查一下','go','GO','Go'];
                    for(var j=0;j<els.length;j++){
                      var t=((els[j].innerText||'')+' '+(els[j].getAttribute('aria-label')||'')).trim();
                      for(var w=0;w<words.length;w++){
                        if(t.indexOf(words[w])>-1){ els[j].click(); break; }
                      }
                    }
                    var el2=document.elementFromPoint(1116,370);
                    if(el2){ el2.click(); }
                    var el3=document.elementFromPoint(660,554);
                    if(el3){ el3.click(); }
                  }catch(e){}
                },500);
                setTimeout(function(){
                  try{
                    fire(target,'keydown'); fire(target,'keyup');
                    var el2=document.elementFromPoint(1116,370);
                    if(el2){ el2.click(); }
                  }catch(e){}
                },1500);
                setTimeout(function(){
                  try{
                    fire(target,'keydown'); fire(target,'keyup');
                    var el2=document.elementFromPoint(1116,370);
                    if(el2){ el2.click(); }
                  }catch(e){}
                },2500);
                var clicks=0;
                function autoCard(){
                  clicks++;
                  if(clicks>25) return;
                  try{
                    var el=document.elementFromPoint(660,554);
                    var p=el; var found=null;
                    for(var k=0;k<6&&p;k++){
                      var t=(p.innerText||'')+'';
                      if(t.indexOf('极兔')>-1&&t.indexOf('查询后')>-1){ found=p; break; }
                      p=p.parentElement;
                    }
                    if(found){ found.click(); return; }
                  }catch(e){}
                  setTimeout(autoCard,800);
                }
                setTimeout(autoCard,2000);
              }
              if(document.readyState==='complete'||document.readyState==='interactive'){ setTimeout(fill,600); }
              else{ document.addEventListener('DOMContentLoaded',function(){ setTimeout(fill,600); }); }
            })()
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /** 主动放弃（调试/超时管理用） */
    fun cancel() {
        worker?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            } catch (t: Throwable) { SafeRun.w("PddExpressChannel", t) }
        }
        worker = null
        running = false
    }
}
