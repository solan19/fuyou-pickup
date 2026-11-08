package com.agent.idcode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 淘宝 / 菜鸟（驿站）取件码通道。
 *
 * ## 机理（与主界面那套完全一致，只是搬出来独立成通道）
 * 隐形 WebView 加载官方驿站首页 → 页面自带 `lib-mtop` + 登录 cookie →
 * 在**页面上下文**里调 `mtop.cainiao.pickup.search.queryMultiStationPackages`
 * → 签名由页面的 lib-mtop 现算（我们不算签名）→ JSON 经 JS 桥回原生 → 递归收包裹。
 *
 * ## ★ 为什么要有这个文件（2026-09-20 抽出来）
 * 这段逻辑原来长在 `MainActivity` 里（`dataWebView` / `runQueryJs` / `QUERY_JS` /
 * `handlePackagesResult` / `collectPackages`），**只有 Activity 能跑** ——
 * [TrackWorker] 在 WorkManager 起的进程里没有 Activity，后台轮询就接不上这一路。
 * 抽成 object 后：主界面与后台**共用同一份实现**（不是复制一份，避免两边走叉）。
 *
 * ## ★ 后台可用的两个关键点
 * 1. **`WebView(context)` 用 Context 建，没有 Activity 时就不挂窗口** ——
 *    参考项目 `Halo0sama/ExpressAssistant` 已验证：不挂窗口照样能跑 JS（无窗口 WebView）。
 * 2. **必须在主线程建/操作 WebView**，所以对外入口统一 `main.post { }`。
 *
 * ## 与主界面的复用方式
 * 主界面**继续用它自己那个常驻隐形页**（预热过、省一次页面加载），行为不变；
 * 本通道是给后台用的独立实例。两者共用同一份 `QUERY_JS` 与解析逻辑（本文件即唯一来源）。
 */
object CaiNiaoChannel {

    private const val TAG = "CaiNiaoChannel"

    /** 隐形宿主页：自带 lib-mtop + 登录 cookie。签名由它现算，我们不算。 */
    private const val HOME_URL =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/home-ssr-index"

    /** 判定宿主页就绪的域前缀（必须等它，否则页面里的 lib-mtop 还没初始化） */
    private const val READY_PREFIX = "https://pages-fast.m.taobao.com"

    /**
     * mtop 查询脚本。**必须压成单行**再注入 evaluateJavascript —— 带换行时
     * 某些 WebView 版本会对返回串做转义处理，导致 JSON 解析失败。
     *
     * ★★ vc667：**两级查询**（同志 2026-11-01：外部用户报"淘宝取件码抓不到"）
     * 旧写法只带 `{source:'ST_SCAN', traceId}`，服务端没有"这是谁的件"这个上下文，
     * 永远回 `allPkgSize:0`（真机两次取证：16:06 / 16:17 都是 0）。
     * 而校园驿站那条链能查到，是因为它带了**手机号**（`searchTypes:["01"] + searchValue`）。
     * ⇒ 先按老参数查一次；空就拿手机号再查一次（跨驿站）。
     *   手机号来源：页面 localStorage / 全局对象，拿不到就调 `pickup.user.get`。
     */
    internal val QUERY_JS = """
        (function(){
          function post(s){ try{ AndroidData.onPackages(s); }catch(e){} }
          if(!(window.lib && window.lib.mtop)){ post(JSON.stringify({ok:false,error:['NO_LIB']})); return; }
          var lib = window.lib.mtop;
          function getPhone(cb){
            var ph = '';
            try{ var u = JSON.parse(localStorage.getItem('__PICK_UP_USER_INFO__')||'{}'); ph = u.mobile||u.phone||''; }catch(e){}
            if(!ph){ try{ ph = (window.__PICK_UP_USER_INFO__ && window.__PICK_UP_USER_INFO__.mobile)||''; }catch(e){} }
            if(ph){ cb(ph); return; }
            lib.request({api:'mtop.com.cainiao.pickup.user.get', v:'1.0', data:{}, ecode:1,
              dataType:'json', type:'GET', timeout:6000}).then(function(r){
                var d = (r&&r.data!==undefined)?r.data:r; var u2 = (d&&d.data)?d.data:d;
                cb((u2&&u2.mobile)||'');
              }, function(){ cb(''); });
          }
          function q(d, cb){
            lib.request({api:'mtop.cainiao.pickup.search.queryMultiStationPackages', v:'1.0',
              data:d, ecode:1, dataType:'json', type:'GET', timeout:8000})
              .then(function(r){ cb(null, r); }, function(e){ cb(e, null); });
          }
          function sizeOf(r){
            if(!r){ return 0; }
            var d = (r.data!==undefined)?r.data:r; d = (d&&d.data)?d.data:d; if(!d){ return 0; }
            var n = d.allPkgSize || 0;
            if(!n && d.stationPackageAssembleList){ n = d.stationPackageAssembleList.length; }
            return n;
          }
          function body(r){ return (r.data!==undefined)?r.data:r; }
          q({source:'ST_SCAN', traceId:String(Date.now())+'-app'}, function(e, r){
            if(!e && sizeOf(r) > 0){
              post(JSON.stringify({ok:true, via:'base', ret:(r&&r.ret)||[], data:body(r)})); return;
            }
            getPhone(function(ph){
              if(!ph){
                if(e){ post(JSON.stringify({ok:false, error:(e&&e.ret)||['UNKNOWN'],
                  msg:(e&&e.errorInfo&&e.errorInfo.errorMsg)||''})); return; }
                post(JSON.stringify({ok:true, via:'base-empty', ret:(r&&r.ret)||[], data:body(r)})); return;
              }
              q({searchTypes:['01'], searchValue:ph, source:'ST_SCAN',
                 traceId:String(Date.now())+'-ph', pageSize:50, pageNum:1}, function(e2, r2){
                if(!e2 && sizeOf(r2) > 0){
                  post(JSON.stringify({ok:true, via:'phone', ret:(r2&&r2.ret)||[], data:body(r2)})); return;
                }
                if(!e && r){
                  post(JSON.stringify({ok:true, via:'base-empty', ret:(r&&r.ret)||[], data:body(r)})); return;
                }
                post(JSON.stringify({ok:false, error:(e&&e.ret)||['UNKNOWN'],
                  msg:(e&&e.errorInfo&&e.errorInfo.errorMsg)||''}));
              });
            });
          });
        })()
    """.trimIndent().replace("\n", "")

    /** 一次抓取的结果。`needLogin` 只在**服务端明确说会话失效**时才为 true（NO_LIB 不算） */
    class Result(
        val pkgs: ArrayList<JSONObject>,
        val err: String?,
        val needLogin: Boolean
    )

    private var web: WebView? = null
    private var pageReady = false
    private var running = false
    private var waitingForPage = false
    private var delivered = false
    private var pendingDone: ((Result) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** 页面组件是否已就绪（宿主页加载完成且过 lib-mtop 探测） */
    val isPageReady: Boolean get() = pageReady

    /**
     * 抓一次取件码。**阻塞式回调（主线程）**，完成后必回调一次。
     *
     * @param ctx 任意 Context（后台传 applicationContext 即可，没有 Activity 也能跑）
     */
    fun fetch(ctx: Context, onDone: (Result) -> Unit) {
        val app = ctx.applicationContext
        main.post {
            if (running) {
                onDone(Result(ArrayList(), "正在抓取中", false))
                return@post
            }
            running = true
            delivered = false
            pendingDone = onDone
            ensureWebView(app)
            if (pageReady) {
                runQueryJs(0)
            } else {
                waitingForPage = true
            }
            // 兜底：宿主页 30 秒还不就绪/不回来，就别让调用方一直等
            main.postDelayed({
                if (!delivered) finish(Result(ArrayList(), "超时", false))
            }, 30_000)
        }
    }

    fun cancel() {
        main.post {
            try {
                web?.stopLoading()
            } catch (t: Throwable) { SafeRun.w("CaiNiaoChannel", t) }
            finish(Result(ArrayList(), "已取消", false))
        }
    }

    // ---------------- 内部 ----------------

    private fun finish(res: Result) {
        if (delivered) return
        delivered = true
        running = false
        waitingForPage = false
        val cb = pendingDone
        pendingDone = null
        cb?.invoke(res)
        // ★ vc267（后台省电）：**用完就关**。以前这个隐形 WebView 建一次就常驻到进程死，
        //   页面里的 JS 计时器/请求在两次轮询之间也在空转（常驻服务 24h 在 ⇒ 24h 空转）。
        //   抓一次只花几秒，关掉后下次重建（冷启动多 1~3 秒），换两次轮询之间**零占用**。
        //   只影响后台这条路 —— 主界面用它自己的常驻页（见类注释），行为不变。
        main.postDelayed({ destroyWeb() }, 300)
    }

    /** 销毁隐形 WebView（必须在主线程）。有挂过窗口要先摘下来再 destroy，否则泄漏 */
    private fun destroyWeb() {
        val wv = web ?: return
        web = null
        pageReady = false
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
        } catch (t: Throwable) { SafeRun.w("CaiNiaoChannel", t) }
        try {
            wv.stopLoading()
        } catch (t: Throwable) { SafeRun.w("CaiNiaoChannel", t) }
        // ★ vc283（缓存瘦身）：HTTP 缓存是全局罐，抓取页攒的缓存不清的话越滚越大。
        //   登录态在 CookieManager，不受 clearCache 影响。
        try {
            wv.destroy()
        } catch (t: Throwable) { SafeRun.w("CaiNiaoChannel", t) }
        Log.i(TAG, "隐形 WebView 已销毁（后台省电：两次轮询之间零占用）")
    }

    /** JS 桥：页面把结果串回来。名字必须与 QUERY_JS 里的 `AndroidData` 一致 */
    private class DataBridge(private val post: (String?) -> Unit) {
        @JavascriptInterface
        fun onPackages(json: String?) = post(json)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(ctx: Context) {
        if (web != null) return
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.setBackgroundColor(0)
        wv.addJavascriptInterface(
            DataBridge { json -> main.post { handleResult(json) } }, "AndroidData"
        )
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = url.startsWith(READY_PREFIX)
                Log.i(TAG, "onPageFinished ready=$pageReady $url")
                if (pageReady && waitingForPage) {
                    waitingForPage = false
                    // 先给页面 1.5s 发自己的取件接口，读它的响应；读不到再回退自拼查询
                    main.postDelayed({ readPageCapture() }, 1500)
                    main.postDelayed({ if (!delivered) runQueryJs(0) }, 4000)
                }
            }
        }
        // ★ 有 Activity 就按老样子 1×1 挂上去（不可见）；**没有就不挂** ——
        //   后台（Worker）里拿不到 decorView，而不挂窗口照样能跑 JS。
        (ctx as? Activity)?.let { act ->
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, ViewGroup.LayoutParams(1, 1)
                )
            } catch (e: Throwable) {
                Log.w(TAG, "attach failed: $e")
            }
        }
        // ★★ vc522：**旁听页面自己发的取件接口**。
        //   我们自拼的查询只有 {source,traceId}，服务端按空站点上下文返回 allPkgSize=0
        //   （真机实证 224B 回包：allPkgSize=0 / stationPackageAssembleList=[]）。
        //   驿站首页自己会带着完整参数请求同名接口 —— 抄它的响应，比猜参数靠谱。
        try {
            val js = WebHook.hookJs("/h5api\\.m\\.taobao\\.com\\/h5\\/mtop\\.cainiao\\.pickup/i")
            val ok = WebHook.attachDocumentStart(wv, js, setOf("*"))
            if (!ok) WebHook.attachFallback(wv, js)
            Log.i(TAG, "旁听钩子注入=$ok")
        } catch (t: Throwable) {
            Log.w(TAG, "旁听钩子注入失败: ${t.message}")
        }
        web = wv
        wv.loadUrl(HOME_URL)
    }

    /**
     * ★ vc522：从旁听捕获里找**页面自己发的那条取件接口响应**并直接采用。
     * 命中就 finish，没命中就交给 4s 后的自拼查询兜底。
     */
    private fun readPageCapture() {
        val w = web ?: return
        if (delivered) return
        WebHook.readCaptures(w) { caps ->
            if (delivered) return@readCaptures
            var hit: String? = null
            var hitUrl = ""
            for ((u, body) in caps) {
                if (body.contains("stationPackageAssembleList")) {
                    hit = body
                    hitUrl = u
                    break
                }
            }
            if (hit == null) {
                Log.i(TAG, "旁听未命中（captures=${caps.size}），先试 DOM/SSR 兜底")
                // ★ vc613：页面是 SSR 直出（不发 XHR）⇒ 旁听永远空；从 DOM 里抠数据
                main.postDelayed({ grabFromDom() }, 300)
                return@readCaptures
            }
            Log.i(TAG, "旁听命中 $hitUrl")
            Log.i(TAG, "旁听回包: " + hit.take(600))
            finish(parseRawResponse(hit))
        }
    }

    /** 直接解析页面原始响应（形如 {"data":{"data":{...}}} 或 {"data":{...}}） */
    /** ★ vc615：DOM 兜底原始数据落盘（外部目录，adb 可拉；覆盖式，避免越滚越大） */
    private fun dumpDom(ctx: Context?, head: String, raw: String, json: String?) {
        try {
            val dir = ctx?.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "dom_pickup_dump.txt")
            f.writeText(
                "===== $head @ ${System.currentTimeMillis()} =====\n" +
                        "页面字节=" + raw.length + " | 抠出JSON=" + (json?.length ?: 0) + "\n" +
                        "-- JSON 前 4000 字 --\n" + (json ?: "").take(4000) + "\n" +
                        "-- 页面 前 4000 字 --\n" + raw.take(4000) + "\n"
            )
        } catch (t: Throwable) { SafeRun.w("CaiNiaoChannel", t) }
    }

    /**
     * ★ vc613：DOM / SSR 兜底 —— 页面把数据渲染进 HTML（不再发 XHR），
     * 按 URL 旁听抓不到时，直接向页面要数据。
     */
    private fun grabFromDom() {
        val w = web ?: return
        if (delivered) return
        try {
            w.evaluateJavascript(DomPickup.GRAB_JS) { v ->
                if (delivered) return@evaluateJavascript
                val raw = try {
                    WebHook.unquoteJsString(v ?: "")
                } catch (_: Throwable) {
                    v ?: ""
                }
                val json = DomPickup.extractEnclosingJson(DomPickup.unescape(raw), "stationPackageAssembleList")
                // ★ vc615：把这一屏的原始数据落盘（覆盖式，最大 8KB）——
                //   以后"抓不到"时直接看页面结构，不用再靠猜（今天已经因为看不到结构绕了好几轮）。
                dumpDom(ctx = web?.context, head = "CaiNiaoChannel", raw = raw, json = json)
                if (json != null) {
                    Log.i(TAG, "DOM 兜底命中（${json.length}B）")
                    finish(parseRawResponse(json))
                } else {
                    Log.i(TAG, "DOM 兜底未命中（页面 ${raw.length}B），交给自拼查询")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DOM 兜底异常: ${t.message}")
        }
    }

    private fun parseRawResponse(body: String): Result {
        return try {
            val o = JSONObject(body)
            val root = o.optJSONObject("data") ?: o
            val inner = root.optJSONObject("data") ?: root
            val pkgs = ArrayList<JSONObject>()
            collectPackages(inner, pkgs)
            for (p in pkgs) p.put("src", "cainiao")
            Log.i(TAG, "旁听解析出 ${pkgs.size} 个包裹")
            Result(pkgs, null, false)
        } catch (t: Throwable) {
            Result(ArrayList(), "旁听解析失败:${t.message}", false)
        }
    }

    /**
     * 跑查询前先探 lib-mtop 就绪 —— 页面刚 onPageFinished 时组件往往还没初始化好，
     * 直接查会拿到 NO_LIB，而 NO_LIB 容易被误当成"登录失效"。
     * （主界面多账号那轮就是这么踩出来的，这里保留同样的重试。）
     */
    private fun runQueryJs(attempt: Int = 0) {
        val w = web
        if (w == null) {
            finish(Result(ArrayList(), "宿主页未创建", false))
            return
        }
        w.evaluateJavascript("(window.lib&&window.lib.mtop)?1:0") { v ->
            if (v == "1" || attempt >= 14) {
                Log.i(TAG, "注入查询 JS（attempt=$attempt libReady=$v）")
                w.evaluateJavascript(QUERY_JS, null)
            } else {
                main.postDelayed({ runQueryJs(attempt + 1) }, 500)
            }
        }
    }

    private fun handleResult(json: String?) {
        // ★ vc520：以前这里什么都不打 —— 出现"0 条"时完全不知道是 NO_LIB、被拒、还是空列表
        Log.i(TAG, "查询回包(${(json ?: "").length}B): " + (json ?: "<null>").take(400))
        finish(parseResult(json))
    }

    /** 解析页面回包。逻辑与主界面那份保持一致（改这里要同步想清楚那边） */
    private fun parseResult(json: String?): Result {
        if (json.isNullOrEmpty()) return Result(ArrayList(), "空响应", false)
        return try {
            val obj = JSONObject(json)
            if (obj.optBoolean("ok")) {
                val pkgs = ArrayList<JSONObject>()
                collectPackages(obj.opt("data"), pkgs)
                // 打来源标记，渲染层靠它区分角标
                for (p in pkgs) p.put("src", "cainiao")
                Result(pkgs, null, false)
            } else {
                val err = obj.optJSONArray("error")?.join(",") ?: "UNKNOWN"
                when {
                    // NO_LIB 不算"未登录"：那是页面组件没就绪（上面已重试过），
                    // 报成未登录会把用户误导去重新登录一个其实好好的账号
                    err.contains("NO_LIB") -> Result(ArrayList(), "页面组件未就绪", false)
                    err.contains("SESSION") || err.contains("LOGIN") || err.contains("TOKEN") ->
                        Result(ArrayList(), null, true)
                    else -> Result(ArrayList(), err.substringAfter("::").ifEmpty { err }, false)
                }
            }
        } catch (e: Exception) {
            Result(ArrayList(), e.message ?: "解析异常", false)
        }
    }

    /** 递归收集所有带 pickupCode 的包裹对象 —— 不猜响应层级，结构改版也不怕 */
    private fun collectPackages(node: Any?, out: ArrayList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (!node.optString("pickupCode").isNullOrEmpty()) out.add(node)
                val keys = node.keys()
                while (keys.hasNext()) collectPackages(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectPackages(node.opt(i), out)
            }
        }
    }
}
