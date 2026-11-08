package com.agent.idcode

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 拼多多「身份码」——**在应用内打开**。
 *
 * ## 为什么以前打不开（2026-09-18 的失败结论）
 * `m.pinduoduo.net/mdkd/identificationCode` 在我们 WebView 里会循环跳 `login.html`，
 * 当时判定"必须靠拼多多 App 容器注入登录态，纯 H5 无解"。
 *
 * ## 真相：不是登录态问题，是**缺原生桥**
 * 把 mdkd 的 4.5 MB bundle 全下来读完（见工作区 `mdkd_probe/`），机理是：
 *
 * 1. **请求根本不走 XHR/fetch**，而是走原生桥：
 *    `JSNetwork:request`（在 `_app` 的请求层 `83101` 模块里）。
 *    离开 App 容器时桥不存在 → 请求**压根没发出去** → 页面永远拿不到数据 →
 *    看起来就像"登录态不认"。
 * 2. **"我在不在 App 里"由 UA 判定**：`phh_android_version` 出现在 UA 里 ⇒ `isNativePlatform`。
 * 3. **桥有两代**：新的是 `window._fastJsN.callNative(...)`（同步返回 JSON），
 *    老的退化成 `iframe src="pinbridge:///request?..."`。而且老路里有一句
 *    `if (UA 含 phh_android_version) return alert(t)` ——
 *    ★ 所以**只伪装 UA、不给 `_fastJsN`，会变成满屏 alert，比不改还糟**。
 * 4. 身份码本身来自 `POST /api/express_trackbox/generate_identification_code`
 *    → `result.identification_code` + `expire_mills`（600000ms，对应页面上"每10分钟自动更新"）。
 *    ★ 实测（`_mdkd_api.py`）：不带任何凭据 → `40001 登录已过期`；假路径 → `40003`。
 *    ⇒ **这道坎只有登录态，不校验 anti-token**，这才是能自己实现的根据。
 *
 * ## 我们怎么做的
 * 冒充拼多多原生容器，只实现页面真正用到的那几个原生方法（36 个调用点里核心就 2 个）：
 *   · `JSNetwork:request` → 我们自己在原生侧发 HTTP，把响应原文交回页面；
 *   · `AMUser:info`       → 把我们在应用内登录拿到的 uid / uin 交回页面；
 *   · 其余导航/UI/埋点类 → 一律回成功占位（页面对它们是"有则更好"）。
 *
 * 于是官方身份码页在**我们自己的 WebView 里原样渲染**：大条形码、"每10分钟自动更新"、
 * "切换灯条身份码"、绑定手机号 —— 和拼多多 App 里看到的一模一样，且**不用跳出去**。
 *
 * ## 一条重要的设计取舍
 * 桥回包一律是**异步**的：`callByNative` 立刻返回 null，稍后由原生调
 * `window.pinbridge.callback(callId, code, data)`。原因是没让 JS 线程等网络。
 */
object PddMdkd {

    private const val TAG = "PddMdkd"

    /** 注入到 window 上的桥对象名（Kotlin 侧 addJavascriptInterface） */
    const val BRIDGE = "__idcodeMdkd"

    const val IDENTITY_URL = "https://m.pinduoduo.net/mdkd/identificationCode"
    const val LOGIN_URL = "https://m.pinduoduo.net/login.html?from=%2Fmdkd%2FidentificationCode"
    const val ORIGIN = "https://m.pinduoduo.net"

    /** vc270j：驿站包裹探针开关 —— 开着时桥上经过的请求/响应原样落盘（MainActivity 控制） */
    @JvmField
    var probeActive = false

    /** 冒充的拼多多 App 版本（页面用 `phh_android_version/<ver>` 判定"在 App 容器里"） */
    private const val FAKE_APP_VER = "8.19.0"

    /** 调试：桥调用 / HTTP 转发 / 页面 console 落盘到 mdkd_bridge.txt */
    var DEBUG = true

    /**
     * 给 WebView 的 UA 追加拼多多原生标识。
     * ★ 只加这一段，不加 `app_type/tiny` —— 否则会被判成"拼多多小程序容器"。
     */
    fun nativeUa(base: String): String =
        if (base.contains("phh_android_version")) base else "$base phh_android_version/$FAKE_APP_VER "

    /**
     * document-start 注入：装上 `window._fastJsN` 这条**同步桥**。
     *
     * ★ 必须在页面任何 JS 之前跑：mdkd 的 `27533` 模块在初始化那一刻就把
     *   `window._fastJsN ? 同步桥 : iframe桥` 这条分支定死了。
     *   document-start 注入是唯一来得及的时机。
     */
    fun bridgeJs(): String = """
        (function(){
          if (window.__idcMdkdHooked) { return; }
          window.__idcMdkdHooked = true;
          function fire(mod, method, paramsJson, callId){
            try {
              window.$BRIDGE.call(String(mod), String(method),
                                  paramsJson == null ? '{}' : String(paramsJson), String(callId));
            } catch (e) {
              // 桥异常也要回一个错，否则页面那个 Promise 永远挂着
              try { window.pinbridge && window.pinbridge.callback(callId, -1, {}); } catch (_) {}
            }
          }
          window._fastJsN = {
            callNative: function(mod, method, paramsJson, callId){
              fire(mod, method, paramsJson, callId);
              // 回 null = "没有同步结果"，随后由原生调 window.pinbridge.callback 回包
              return null;
            }
          };
        })();
    """.trimIndent()

    // ---------- 调试落盘 ----------

    @Volatile
    private var debugCtx: Context? = null

    fun attachDebug(ctx: Context) {
        if (DEBUG) debugCtx = ctx.applicationContext
    }

    fun debugLog(line: String) {
        if (!DEBUG) return
        val ctx = debugCtx ?: return
        Log.i(TAG, line)
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, "mdkd_bridge.txt").appendText(line + "\n")
        } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
    }

    /** 清一下上一轮的日志，方便对照 */
    fun resetDebug() {
        val ctx = debugCtx ?: return
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, "mdkd_bridge.txt").writeText("==== mdkd bridge @ ${System.currentTimeMillis()} ====\n")
        } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
    }

    // ---------- 原生桥 ----------

    /**
     * 冒充拼多多原生容器。
     *
     * JS 侧调用约定（来自 mdkd bundle 的 `27533` 模块）：
     *   `window._fastJsN.callNative(module, method, paramsJson, callId)` → 我们异步回
     *   `window.pinbridge.callback(callId, errorCode, data)`
     */
    class Bridge(
        private val ctx: Context,
        private val web: WebView,
        private val onTitle: (String) -> Unit
    ) {

        /** 拼多多的 uid / uin（从应用内登录存的 cookie 里取） */
        private val ck: String = PddChannel.cookies(ctx)
        private val uid: String = PddChannel.uidFromCookie(ck)
        private val uin: String = Regex("(?:^|;\\s*)pdd_user_uin=([^;]+)").find(ck)
            ?.groupValues?.get(1)?.trim().orEmpty()

        @SuppressLint("JavascriptInterface")
        @JavascriptInterface
        fun call(module: String?, method: String?, paramsJson: String?, callId: String?) {
            val mod = module.orEmpty()
            val m = method.orEmpty()
            val cid = callId.orEmpty()
            val p = try {
                JSONObject(paramsJson?.takeIf { it.isNotBlank() } ?: "{}")
            } catch (_: Throwable) {
                JSONObject()
            }
            debugLog("BRIDGE $mod:$m  cid=$cid  ${p.toString().take(500)}")

            when (mod to m) {
                // ★ 核心 1：所有接口请求都由我们原生转发
                "JSNetwork" to "request" -> forwardHttp(p, cid)

                // ★ 核心 2：页面要用户身份，从我们登录拿到的 cookie 里答
                "AMUser" to "info" -> reply(cid, 0, JSONObject().apply {
                    put("user_id", uid)
                    put("uin", uin)
                    put("type", "")
                    put("is_login", uid.isNotEmpty())
                }.toString())

                "AMBridgeAPI" to "check" -> reply(cid, 0, JSONObject().apply {
                    put("exist", false)
                }.toString())

                "AMBridgeAPI" to "callback" -> reply(cid, 0, "{}")

                "AMDevice" to "deviceInfo" -> reply(cid, 0, JSONObject().apply {
                    put("platform", "android")
                    put("os_version", Build.VERSION.RELEASE)
                    put("os_version_code", Build.VERSION.SDK_INT)
                    put("device_name", Build.MODEL)
                    put("device_model", Build.MODEL)
                    put("brand", Build.BRAND)
                    put("app_version", FAKE_APP_VER)
                    put("screen_width", ctx.resources.displayMetrics.widthPixels)
                    put("screen_height", ctx.resources.displayMetrics.heightPixels)
                    put("density", ctx.resources.displayMetrics.density.toDouble())
                    put("is_root", false)
                }.toString())

                "AMDevice" to "getFontInfo" -> reply(cid, 0, """{"font_scale":1.0,"font_size":16}""")

                "JSUIControl" to "getNavigationHeight" -> reply(
                    cid, 0, """{"height":0,"navigationHeight":0}"""
                )

                "JSUIControl" to "setTitle" -> {
                    val t = p.optString("title").ifEmpty { p.optString("text") }
                    if (t.isNotEmpty()) {
                        try {
                            web.post { onTitle(t) }
                        } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
                    }
                    reply(cid, 0, "{}")
                }

                "JSAlert" to "toast" -> {
                    debugLog("TOAST ${p.optString("message").ifEmpty { p.optString("content") }}")
                    reply(cid, 0, "{}")
                }

                "JSUserSetting" to "getElderMode" -> reply(cid, 0, """{"elder_mode":false}""")

                // 其余（导航/埋点/推送/弹层/日志…）一律回成功占位：
                // 页面对它们是"有则更好"，回错误反而会打断主流程。
                else -> reply(cid, 0, "{}")
            }
        }

        /** 把结果送回页面：`pinbridge.callback(callId, errorCode, data)` */
        private fun reply(cid: String, code: Int, dataJson: String) {
            if (cid.isEmpty()) return
            val js = "window.pinbridge && window.pinbridge.callback($cid, $code, $dataJson)"
            try {
                web.post {
                    try {
                        web.evaluateJavascript(js, null)
                    } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
                }
            } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
        }

        /**
         * `JSNetwork:request` 的原生实现。
         *
         * 入参（页面给的）：`method` / `url` / `headers` / `data` / `timeout`。
         * 回参（页面要的）：`{response: <响应原文>, response_encoded: false, status: <http码>}`
         * —— 跟 bundle 里那行 `JSON.parse(false!==E.response_encoded?decodeURIComponent(E.response):E.response)`
         * 严格对齐：所以 `response` 用**原文**、`response_encoded` 用 **false**。
         *
         * ★ 404/424 这类**业务错误也必须原样交回**：页面靠 `error_code` 决定是弹
         *   "当前账号未登录，请登录后重新扫码" 还是别的东西。我们吞掉它，页面就只会说"网络异常"。
         */
        private fun forwardHttp(p: JSONObject, cid: String) {
            val method = p.optString("method", "GET").uppercase()
            val url = p.optString("url")
            val body = if (p.has("data") && !p.isNull("data")) p.optString("data") else null
            val headers = p.optJSONObject("headers")
            val timeout = p.optInt("timeout", 10_000).coerceIn(3_000, 20_000)

            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = timeout
                        readTimeout = timeout
                        instanceFollowRedirects = true
                        doInput = true
                    }
                    // ① 页面自己带的头（Referer / AccessToken / ETag…）——原样透传
                    val sent = LinkedHashMap<String, String>()
                    if (headers != null) {
                        for (k in headers.keys()) {
                            val v = headers.optString(k)
                            if (k.isNotBlank() && v.isNotBlank()) {
                                conn.setRequestProperty(k, v)
                                sent[k] = v
                            }
                        }
                    }
                    // ② cookie：HttpURLConnection 不会读 WebView 的 jar，手工带上
                    val jarCk = try {
                        CookieManager.getInstance().getCookie(url).orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    if (jarCk.isNotEmpty() && !sent.containsKey("Cookie")) {
                        conn.setRequestProperty("Cookie", jarCk)
                    }
                    // ★ 统一设备身份：页面自己发 fetch 时 User-Agent 由浏览器自动带（JS 改不了），
                    //   但这里是我们用 HttpURLConnection 转发 —— 默认会发 Java/Dalvik 的 UA，
                    //   服务端会当成"另一台设备"。显式补成与登录页同一个（见 PddChannel.BROWSER_UA）。
                    if (!sent.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                        conn.setRequestProperty("User-Agent", PddChannel.BROWSER_UA)
                    }
                    // ③ ★ 兜底补 AccessToken：页面是从 `document.cookie` 里读它的，
                    //    如果那一跳 cookie 还没落 jar，页面就会发一个没有身份的请求（必 40001）。
                    //    ★ vc265：优先从 WebView cookie 库现取（活会话；HttpOnly 的 PDDAccessToken
                    //      页面 JS 读不到，但 CookieManager 原生层拿得到），取不到再退回登录存档。
                    if (!sent.containsKey("AccessToken") && uid.isNotEmpty()) {
                        val jarTok = try {
                            Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)")
                                .find(
                                    CookieManager.getInstance()
                                        .getCookie("https://mobile.yangkeduo.com/").orEmpty()
                                )?.groupValues?.get(1)
                        } catch (_: Throwable) {
                            null
                        }
                        val tok = jarTok
                            ?: Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)").find(ck)?.groupValues?.get(1)
                        tok?.let { conn.setRequestProperty("AccessToken", it.trim()) }
                    }
                    if (body != null) {
                        conn.doOutput = true
                        if (!sent.keys.any { it.equals("Content-Type", true) }) {
                            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                        }
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }

                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    var text = stream?.let { s ->
                        val ins = if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(s) else s
                        ins.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()

                    debugLog("HTTP  $method $status  ${url.take(160)}  ->  ${text.take(400)}")

                    // ★ vc270j：探针模式 —— 桥上经过的每个请求/响应原样落盘
                    //   （页面 API 全走原生桥转发，JS 钩子看不见；找批量取件码接口用）
                    if (probeActive) {
                        try {
                            java.io.File(ctx.getExternalFilesDir(null), "mdkd_pkg_capture.txt")
                                .appendText(
                                    "REQ $method $url\n" +
                                        "BODY: ${body ?: "(无)"}\n" +
                                        "HEADERS: " + sent.entries.joinToString("; ") {
                                            "${it.key}=${it.value.take(80)}"
                                        } + "\n" +
                                        "RESP: ${text.take(30000)}\n----\n"
                                )
                        } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
                    }

                    // ★ 40001 = 登录已过期。这是**判定登录态的唯一可信口径**：
                    //   cookie 里躺着 PDDAccessToken ≠ 服务端认它。发现就立刻打回去，
                    //   入口文案/后续引导才会跟着变成"需重新登录"，而不是继续骗人。
                    if (text.contains("\"error_code\":40001") || text.contains("\"error_code\": 40001")) {
                        PddChannel.markLoginDead(ctx)
                        // 顺手记一条：实际发出去的 token 和我们存的是不是同一个
                        // （不同 = 页面用的是它自己的游客 token —— 这条日志能直接看出来）
                        val sentTok = sent["AccessToken"].orEmpty()
                        val mine = Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)")
                            .find(PddChannel.cookies(ctx))?.groupValues?.get(1).orEmpty()
                        debugLog(
                            "40001  ${url.take(70)}\n" +
                                    "       实发 token=${sentTok.take(20)}…(len=${sentTok.length})" +
                                    "  库存 token=${mine.take(20)}…(len=${mine.length})" +
                                    "  一致=${sentTok.isNotEmpty() && sentTok == mine}"
                        )
                    }

                    val out = JSONObject().apply {
                        put("response", text)
                        put("response_encoded", false)   // 原文提交，别让页面再 decodeURIComponent
                        put("status", status)
                    }
                    reply(cid, 0, out.toString())
                } catch (e: Throwable) {
                    debugLog("HTTP  ERR $method $url : $e")
                    // 真·网络异常：回一个没有 response 的对象，页面会走它的"网络异常"分支
                    reply(cid, 0, "{}")
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (t: Throwable) { SafeRun.w("PddMdkd", t) }
                }
            }.start()
        }
    }
}
