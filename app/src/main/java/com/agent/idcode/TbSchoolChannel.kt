package com.agent.idcode

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * ★ vc308：淘宝侧查码（校园「我的快递」页）—— 生产通道。
 *
 * ## 为什么要有它
 * 拼多多那条链（assistant_pickup 取件页）只能覆盖**拼多多自己的订单**；淘宝单靠它碰运气。
 * 淘宝这边（2026-10-18 同志指路）在「我的快递 · 校园」页能按**手机号**或**快递单号**
 * 查真实取件码 —— 那才是淘宝件的本源。
 *
 * ## 机理（全部来自 bundle 静态分析 + 真机实测，不是猜的）
 * 隐形 WebView 直开校园页（`pages-fast.../last-mile-fe/m-end-school-tab/home`）——
 * 页面自带 `lib-mtop` 与登录 cookie，签名由页面现算（我们不算签名），然后在页面上下文里调：
 *
 *   `mtop.cainiao.pickup.xiaoyuan.queryMultisitePkgs`
 *     data = { searchTypes:'["01"]', searchValue:<手机号>, pageSize:50, pageNum:1,
 *              source:'ST', stationType:'XY', stVersion:'1.1' }
 *   searchTypes: "01" = 按手机号, "03" = 按快递单号（bundle 里 `"01":VALUE_7, "03":VALUE_6`）
 *   返回：`stationMessageAndPackages[].waybillPickUpResp.certainPickupWaybills[]`
 *
 * ## 取件码怎么抠
 * 包裹 DTO（`CertainPackageDTO`）里 **`shelfNo` 才是货架/格口号**（实证值形如 `"24-2"`、`"1-1"`），
 * `desensitizedPickupCode` 是脱敏码（带 `*`，没法用）。
 * 所以：**递归遍历整棵返回 JSON**，凡是「带 waybillNo / mailNo 的对象」就把它的
 * shelfNo（优先）或干净的 pickupCode 记下来 —— 不写死路径，对方改字段层级也能活。
 *
 * ## 省电与并发
 * 用完就 `destroy()`（照 vc267 的规矩：两次查码之间零占用）；
 * `running` 互斥，避免同时开两个隐形页。
 */
object TbSchoolChannel {

    private const val TAG = "TbSchoolChannel"

    /** 校园页门牌号：与探针共用一份，避免两处走叉 */
    private val SCHOOL_URL: String get() = TbPickupProbe.SCHOOL_URL

    private val main = Handler(Looper.getMainLooper())
    private var running = false

    /** 一次最多补查这么多单号（同志 2026-10-23 定：淘宝侧一次最多 4 个运单号） */
    private const val MAX_WAYBILL_CALLS = 4

    /** 整体预算：超过就空收口（不能让主链一直等） */
    private const val BUDGET_MS = 22_000L

    private var web: WebView? = null

    /** ★ vc313：调试开关 —— 把桥回来的**原始 JSON**（含每次调用的完整 body）落盘。
     *  用途：查「已取走的件在这个接口里到底还有没有记录、状态字段是什么」。默认关。 */
    @Volatile
    var dumpRaw = false

    fun isRunning(): Boolean = running

    /** JS 桥：页面把结果串回来。名字必须与 buildJs 里的 `TbData` 一致 */
    private class DataBridge(private val post: (String?) -> Unit) {
        @JavascriptInterface
        fun onResult(json: String?) = post(json)
    }

    /** 只保留「本次要查的单号」的命中（大写比对），顺带把结果裁掉空值 */
    @Volatile
    private var wanted: Set<String> = emptySet()

    /**
     * ★ vc663：最近一次查询里带回来的**站点名**（waybill 大写 → 站点文案）。
     * 拼多多对菜鸟单只给「菜鸟驿站」这种公司名，具体站点在淘宝校园驿站这条响应里 ⇒ 借它补地址。
     * 调用方在同一次 [queryBatch] 回调里读，不要跨轮次用。
     */
    @Volatile
    var lastStations: Map<String, String> = emptyMap()
        private set

    /** 从同一份 JSON 里取站点名（★ vc665：按接口真实结构解析，不再猜字段名） */
    fun parseStations(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        try {
            val calls = JSONObject(json).optJSONArray("calls") ?: return emptyMap()
            for (i in 0 until calls.length()) {
                val body = calls.optJSONObject(i)?.optString("body").orEmpty()
                if (body.isBlank() || !body.contains("stationMessageAndPackages")) continue
                val b = try { JSONObject(body) } catch (_: Throwable) { continue }
                val arr = b.optJSONObject("data")?.optJSONArray("stationMessageAndPackages") ?: continue
                for (j in 0 until arr.length()) {
                    val g = arr.optJSONObject(j) ?: continue
                    // ★ 结构（2026-11-01 真机原始返回实证）：
                    //   stationMessageAndPackages[j].stationMessage.stationName  ← 站点名
                    //   stationMessageAndPackages[j].waybillPickUpResp.certainPickupWaybills[] ← 该站下的件
                    val st = g.optJSONObject("stationMessage")?.optString("stationName").orEmpty().trim()
                    if (st.isEmpty()) continue
                    val pk = g.optJSONObject("waybillPickUpResp")?.optJSONArray("certainPickupWaybills") ?: continue
                    for (k in 0 until pk.length()) {
                        val wb = pk.optJSONObject(k)?.optString("waybillNo").orEmpty().trim().uppercase()
                        if (wb.isNotEmpty() && !out.containsKey(wb)) out[wb] = st
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "parseStations 失败: $e")
        }
        return out
    }

    /**
     * 批量查码。**主线程回调，必回调一次**：waybill(大写) -> 取件码。
     *
     * @param waybills 要查的单号（可空 —— 空则只走「手机号查全部」那一路）
     */
    fun queryBatch(act: Activity, waybills: List<String>, onDone: (Map<String, String>) -> Unit) {
        main.post {
            if (running) {
                onDone(emptyMap())
                return@post
            }
            running = true
            wanted = waybills.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
            val done = booleanArrayOf(false)
            // ★ 计时：0 = 起点（建 WebView 前），1 = 页面完成时刻
            val clock = longArrayOf(0L, 0L)
            // 防重复注入：onPageFinished 可能多次触发（重定向/二次渲染）
            val injected = booleanArrayOf(false)

            fun deliver(map: Map<String, String>) {
                if (done[0]) return
                done[0] = true
                running = false
                try {
                    web?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                } catch (t: Throwable) { SafeRun.w("TbSchoolChannel", t) }
                web = null
                val total = if (clock[0] > 0) System.currentTimeMillis() - clock[0] else -1
                val page = if (clock[0] > 0 && clock[1] > 0) clock[1] - clock[0] else -1
                Log.i(TAG, "收口 命中=${map.size} 查${wanted.size}单 | 总耗时 ${total}ms（建页→页面完成 ${page}ms）")
                onDone(map)
            }

            val wv = WebView(act)
            web = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.setBackgroundColor(0)
            clock[0] = System.currentTimeMillis()
            wv.addJavascriptInterface(DataBridge { json ->
                main.post {
                    Log.i(TAG, "ret: ${retOf(json)}")
                    if (dumpRaw) {
                        try {
                            val f = java.io.File(act.getExternalFilesDir(null), "tb_school_raw.txt")
                            f.writeText(json.orEmpty())
                            Log.i(TAG, "raw -> ${f.absolutePath}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "raw 落盘失败: $e")
                        }
                    }
                    lastStations = parseStations(json)   // ★ vc663：同一次响应里的站点名
                    deliver(parsePairs(json))
                }
            }, "TbData")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    clock[1] = System.currentTimeMillis()
                    Log.i(TAG, "校园页完成：用时 ${clock[1] - clock[0]}ms  ${url?.take(90)}")
                    // ★ 以前固定等 2200ms（怕 lib-mtop 没初始化好）；改成 **250ms 轮询探测**，
                    //   就位就立刻注入 —— 实测能把这段从 2.2s 压到 0.3~0.8s。
                    var tries = 0
                    fun probeReady() {
                        if (done[0] || injected[0]) return
                        if (tries++ >= 40) {                       // 10 秒还没就位 ⇒ 放弃
                            Log.w(TAG, "等 lib-mtop 超时（${tries - 1} 次），放弃本轮")
                            return
                        }
                        try {
                            view.evaluateJavascript(
                                "(function(){return !!(window.lib&&window.lib.mtop);})()"
                            ) { v ->
                                if (done[0] || injected[0]) return@evaluateJavascript
                                if (v != null && v.contains("true")) {
                                    injected[0] = true
                                    Log.i(TAG, "lib-mtop 就位：页面完成后 ${System.currentTimeMillis() - clock[1]}ms")
                                    try {
                                        view.evaluateJavascript(buildJs(waybills), null)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "注入失败: $e")
                                    }
                                } else {
                                    main.postDelayed({ probeReady() }, 250)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "探测 lib-mtop 失败: $e")
                        }
                    }
                    main.postDelayed({ probeReady() }, 250)
                }
            }
            wv.loadUrl(SCHOOL_URL)
            main.postDelayed({ deliver(emptyMap()) }, BUDGET_MS)
        }
    }

    // ---------------- 内部 ----------------

    fun buildJs(waybills: List<String>): String {
        val wbArr = waybills
            .map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z0-9]{6,32}")) }
            .distinct()
            .take(MAX_WAYBILL_CALLS)
            .joinToString(",") { "'" + it.replace("'", "") + "'" }
        return "(function(){" +
                "function post(s){try{TbData.onResult(s);}catch(e){}}" +
                "if(!(window.lib&&window.lib.mtop)){post(JSON.stringify({ok:false,err:'NO_LIB'}));return;}" +
                "var lib=window.lib.mtop;var WBS=[" + wbArr + "];var calls=[];" +
                "function req(tag,types,value){" +
                "var d={searchTypes:types,searchValue:value,pageSize:50,pageNum:1," +
                "source:'ST',stationType:'XY',stVersion:'1.1',traceId:String(Date.now())+'-'+tag};" +
                "return lib.request({api:'mtop.cainiao.pickup.xiaoyuan.queryMultisitePkgs'," +
                "v:'1.0',data:d,ecode:1,dataType:'json',type:'GET',timeout:8000})" +
                ".then(function(r){calls.push({t:tag,ret:(r&&r.ret||[]).join('|')," +
                "body:JSON.stringify((r&&r.data!==undefined)?r.data:r)});}," +
                "function(e){calls.push({t:tag,ret:((e&&e.ret)||[]).join('|')});});}" +
                "function getPhone(){" +
                "var ph='';try{var u=JSON.parse(localStorage.getItem('__PICK_UP_USER_INFO__')||'{}');" +
                "ph=u.mobile||u.phone||'';}catch(e){}" +
                "if(!ph){try{ph=(window.__PICK_UP_USER_INFO__&&window.__PICK_UP_USER_INFO__.mobile)||'';}catch(e){}}" +
                "return ph;}" +
                "function fellPhone(cb){" +
                "var ph=getPhone();if(ph){cb(ph);return;}" +
                "lib.request({api:'mtop.com.cainiao.pickup.user.get',v:'1.0',data:{},ecode:1," +
                "dataType:'json',type:'GET',timeout:8000}).then(function(r){" +
                "var dd=(r&&r.data!==undefined)?r.data:r;" +
                "var u2=(dd&&dd.data)?dd.data:dd;cb((u2&&u2.mobile)||'');},function(){cb('');});}" +
                "function walk(o,out){" +
                "if(!o||typeof o!=='object'){return;}" +
                "if(Object.prototype.toString.call(o)==='[object Array]'){" +
                "for(var i=0;i<o.length;i++){walk(o[i],out);}return;}" +
                "var wb=o.waybillNo||o.mailNo||o.trackingNumber||o.waybillCode||'';" +
                "var code=o.shelfNo||o.gridNo||o.cabinetNo||o.pickupCode||'';" +
                "if(!code){var dp=o.desensitizedPickupCode||'';if(dp&&dp.indexOf('*')<0){code=dp;}}" +
                "if(wb&&code){var k=String(wb).toUpperCase();if(!out[k]){out[k]=String(code).trim();}}" +
                "for(var kk in o){if(Object.prototype.hasOwnProperty.call(o,kk)){walk(o[kk],out);}}" +
                "}" +
                "function flush(ph){" +
                "var out={};for(var j=0;j<calls.length;j++){try{walk(JSON.parse(calls[j].body||'{}'),out);}catch(e){}}" +
                "post(JSON.stringify({ok:true,phone:ph?1:0,pairs:out,calls:calls}));}" +
                "fellPhone(function(ph){" +
                "var jobs=[];" +
                "if(ph){jobs.push(req('p','[\"01\"]',ph));}" +
                "for(var i=0;i<WBS.length;i++){jobs.push(req('w'+i,'[\"03\"]',WBS[i]));}" +
                "Promise.all(jobs).then(function(){flush(ph);},function(){flush(ph);});" +
                "});" +
                "})()"
    }

    /** 从桥回来的 JSON 里取 `pairs`（waybill -> code），只留这次要查的单号 */
    fun parsePairs(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val pairs = o.optJSONObject("pairs") ?: return emptyMap()
            val names = ArrayList<String>()
            val kit = pairs.keys()
            while (kit.hasNext()) names.add(kit.next())
            val all = LinkedHashMap<String, String>()
            for (name in names) {
                val k = name.uppercase()
                val v = pairs.optString(name, "").trim()
                if (k.length >= 6 && v.isNotBlank()) all[k] = v
            }
            val allow = wanted
            if (allow.isEmpty()) return all
            val out = LinkedHashMap<String, String>()
            for ((k, v) in all) if (allow.contains(k)) out[k] = v
            out
        } catch (e: Throwable) {
            Log.w(TAG, "parsePairs 失败: $e")
            emptyMap()
        }
    }

    /** 把最近一次各调用的 ret 串成一行（排查 403/风控/未登录） */
    fun retOf(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            val arr = JSONObject(json).optJSONArray("calls") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                sb.append(o.optString("t")).append(':').append(o.optString("ret")).append(' ')
            }
            sb.toString().trim()
        } catch (_: Throwable) {
            ""
        }
    }
}
