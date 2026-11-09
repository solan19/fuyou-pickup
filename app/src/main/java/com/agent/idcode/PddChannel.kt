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
 * 拼多多通道：应用内登录 + 抓包裹/取件码。
 *
 * ## 为什么要自己登录（代价）
 * 拼多多的取件码只能在「已登录的页面上下文」里拿到。而**在第三方客户端登录**会让
 * 拼多多 App 掉线一次（服务端新建会话顶掉旧的）—— 这是一次性代价，之后反复查不会顶
 * （我们带着已建立的 cookie 请求，服务端视为同一会话，不新建）。
 *
 * ## 怎么拿数据：不碰签名，只在页面里旁听
 * 拼多多 H5 的接口要 `anti_content` 等动态签名，由页面 JS 现算 ⇒ 我们**不自算**，
 * 而是让官方页面自己去发请求，用 [WebHook] 把响应原文抄进 `window.name`。
 *
 * ## 数据源优先级
 * 1. **`m.pinduoduo.net/mdkd/package`** —— 拼多多驿站端的「取快递」聚合页
 *    （按手机尾号聚合驿站包裹，本身就是取件码页面）。若能直接用，就省掉
 *    "订单页 → 点物流 → 解析 DOM" 那条长链路。
 * 2. `mobile.yangkeduo.com/orders.html` —— 订单列表兜底。
 *
 * 抓到的原始响应会落盘到 `pdd_capture.txt`（受 [DEBUG_EXPORT] 控制），用于现场核对字段。
 */
object PddChannel {

    private const val TAG = "PddChannel"

    /** 调试：把抓到的原始响应写文件（发布前设 false） */
    var DEBUG_EXPORT = true

    const val KEY_COOKIES = "pdd_cookies"
    const val KEY_UID = "pdd_uid"

    /** 「最近一次业务调用有没有被服务端否掉」。缺省 true —— 刚登录时不该被误判 */
    private const val KEY_LOGIN_OK = "pdd_login_ok"

    /**
     * ★★ 拼多多全链路**统一设备身份**（UA）—— 单一出处，别在别处再写死第二份。
     *
     * 为什么必须统一（2026-09-24 排查"互顶反复复现"）：
     * 本文件开头写着"登录那一下顶一次、之后反复查不会顶"，它的成立前提是
     * **同一设备身份 + 带已建立的 cookie ⇒ 服务端视为同一会话，不新建**。
     * 而实测链路上同时存在四种身份：登录页(25102RKBEC) / 本文件的旁听 WebView
     * （原来不设 UA ⇒ 落到 WebView 默认"真机机型 + `; wv`"）/ [PddNative](Android 14; PKX110) /
     * mdkd（默认 UA + 原生标识）—— 每次抓取都可能被服务端当成"**另一台设备**"在访问，
     * 重新绑定会话 ⇒ 反复顶掉手机上的拼多多 App。
     * ⇒ 登录、旁听、原生接口、mdkd 转发一律用这一个；mdkd 再叠加它必须的原生标识。
     */
    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    const val LOGIN_URL = "https://mobile.yangkeduo.com/login.html"
    /** 驿站端身份码页（App 内可直接打开的那张 dd 前缀大条形码页） */
    const val IDENTITY_URL = "https://m.pinduoduo.net/mdkd/identificationCode"
    const val STATION_URL = "https://m.pinduoduo.net/mdkd/package"
    const val ORDERS_URL = "https://mobile.yangkeduo.com/orders.html"

    /** 拼多多相关域（cookie 注入 / 清理）。mdkd 域也要，驿站页在 m.pinduoduo.net */
    private val HOSTS = listOf(
        "https://mobile.yangkeduo.com",
        "https://yangkeduo.com",
        "https://pinduoduo.com",
        "https://mobile.pinduoduo.com",
        "https://m.pinduoduo.net"
    )

    /** 钩子匹配的接口范围：拼多多的 proxy/api、订单页、驿站 mdkd 都在内 */
    private const val URL_PATTERN = "/proxy\\/api|yangkeduo|pinduoduo|mdkd/i"

    /** 抓取用的隐藏 WebView 与轮询句柄（同一时刻只允许一次抓取） */
    private var worker: WebView? = null
    private var poller: Runnable? = null
    private var running = false

    /** 本次加载的页面 URL（探取件码载体时用来对账） */
    @Volatile
    var fetchedUrl: String = ORDERS_URL
        private set
    private val main = Handler(Looper.getMainLooper())

    /** 最近一次抓取的原始响应（途中快递栏用它做宽口径解析） */
    @Volatile
    var lastCaptures: List<Pair<String, String>> = emptyList()
        private set

    /** 落盘调试文件要用（getExternalFilesDir 需要 Context） */
    @Volatile
    private var appCtx: Context? = null

    // ---------- 登录态 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)

    fun cookies(ctx: Context): String = prefs(ctx).getString(KEY_COOKIES, "").orEmpty()

    fun uid(ctx: Context): String = prefs(ctx).getString(KEY_UID, "").orEmpty()

    /**
     * ★★ 登录判据：有 `PDDAccessToken` **且最近一次业务调用没被服务端否掉**。
     *
     * 为什么不能只看 cookie 名（2026-09-20 踩的坑）：
     * 拼多多的 token 会过期，但 cookie 一直躺在 jar 里 ⇒ 只看名字会永远显示"已登录"，
     * 用户点身份码进去却处处 `40001` + 弹登录，观感就是"我明明登录着却让我登录"。
     * （跟顺丰那次翻车同一个机理 —— 判登录态要问业务接口，不能猜 cookie 名。）
     *
     * [markLoginDead] 由业务调用侧（mdkd 桥转发发现 40001、PddNative 探活）回调。
     */
    fun loginOk(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOGIN_OK, true)

    fun isLoggedIn(ctx: Context): Boolean =
        cookies(ctx).contains("PDDAccessToken") && loginOk(ctx)

    /** 服务端明确不认（40001）时调它 —— 立刻反映到 UI，别再让用户白点一次 */
    fun markLoginDead(ctx: Context) {
        if (!loginOk(ctx)) return
        prefs(ctx).edit().putBoolean(KEY_LOGIN_OK, false).apply()
        markDeadAt(ctx)   // ★ 顺手起算"静默期"，6 小时内后台不再空跑（见 shouldSkipBackground）
        Log.w(TAG, "服务端 40001 ⇒ 标记拼多多登录态失效")
    }

    /**
     * ★★ 这次「失效」是不是**已经过期太久**了？—— 决定还要不要再去碰拼多多服务端。
     *
     * 为什么需要这个（2026-09-26 真机取证后加）：
     * 拼多多的 token 是**在第三方容器里换来的**，服务端会不定期清扫，我们在客户端
     * **无法续期**（实测：实发 token 与库存 token 逐字符一致，服务端照样回 40001）。
     * 而 [TrackWorker] 每 15 分钟就要跑一轮 —— 登录明明已经失效，它还会：
     *   ① 走 [PddChannel.fetch] 起隐藏 WebView 打 orders.html；
     *   ② 走 [PddTraceChannel] 为每个缺码的件开一次订单详情页。
     * 这些都是**注定失败**的访问，而且**每访问一次都在触碰拼多多的风控**
     * （2026-09-20 亲历：多次探针后 orders.html 都被重定向到登录页）。
     * ⇒ 明确失效超过一定时间，就干脆别碰了，等用户重新登录再说。
     *
     * ★ 阈值取 6 小时：既不会因为"刚失效几分钟、用户马上要重登"就过早放弃，
     *   也不会让它一整天空跑。用户任何一次重新登录都会把计时清零。
     */
    private const val KEY_DEAD_AT = "pdd_login_dead_at"
    private const val DEAD_QUIET_MS = TimeGates.PDD_DEAD_QUIET_MS

    /** 记下"第几次业务调用发现它不认了"（只记第一次，别被后续 40001 刷新） */
    private fun markDeadAt(ctx: Context) {
        val p = prefs(ctx)
        if (p.getLong(KEY_DEAD_AT, 0L) != 0L) return
        p.edit().putLong(KEY_DEAD_AT, System.currentTimeMillis()).apply()
    }

    /**
     * 后台是否应该**跳过**拼多多这条源。
     * true = 登录早已失效且无人重新登录 ⇒ 别再白跑，也别再触碰风控。
     * ★ 只影响**后台**：用户主动点第 2 栏/取件码，仍然正常尝试（他要的就是"去看看"）。
     */
    fun shouldSkipBackground(ctx: Context): Boolean {
        if (isLoggedIn(ctx)) return false
        val at = prefs(ctx).getLong(KEY_DEAD_AT, 0L)
        return at != 0L && System.currentTimeMillis() - at > DEAD_QUIET_MS
    }

    /** 重新登录成功后（或探活成功）调它 */
    fun markLoginOk(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_LOGIN_OK, true).remove(KEY_DEAD_AT).apply()
    }

    fun saveCookies(ctx: Context, ck: String) {
        val uid = uidFromCookie(ck)
        // 换了新 cookie 就认为登录有效；真失效由服务端回码再打回去
        prefs(ctx).edit().putString(KEY_COOKIES, ck).putString(KEY_UID, uid)
            .putBoolean(KEY_LOGIN_OK, true).remove(KEY_DEAD_AT).apply()
    }

    fun clearLogin(ctx: Context) {
        // 一起复位 loginOk：清完登录态后它该回到"缺省有效"，
        // 由「有没有 cookie」和「服务端认不认」共同决定最终显示
        prefs(ctx).edit().remove(KEY_COOKIES).remove(KEY_UID)
            .putBoolean(KEY_LOGIN_OK, true).remove(KEY_DEAD_AT).apply()
        clearCookies()
    }

    /** 从 cookie 串里抠 uid（pdd_user_id / pdd_user_uin 都可能是） */
    fun uidFromCookie(ck: String): String {
        for (key in listOf("pdd_user_id", "pdd_user_uin", "pdduid", "PASS_ID")) {
            val m = Regex("(?:^|;\\s*)$key=([^;]+)").find(ck)
            val v = m?.groupValues?.get(1)?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /** 登录成功后从 CookieManager 汇总各域 cookie */
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

    /** 真正决定"登没登录"的几个关键字段（跨域补种时只补这些，别把无关 cookie 也塞过去） */
    private val CRITICAL = listOf("PDDAccessToken", "pdd_user_id", "pdd_user_uin", "api_uid", "pdduid")

    /**
     * 把存的 cookie 写进 WebView 的 CookieManager。
     *
     * ★ 两个层次：
     *   ① 逐 host 种一份 host-only（原样照抄登录时的样子）；
     *   ② **跨域补种**：登录发生在 `.yangkeduo.com`，而身份码页在 `m.pinduoduo.net` ——
     *      两个**不同顶级域**，浏览器绝不会自己跨过去。但原生 setCookie 可以手动往任意域种，
     *      所以把关键登录字段额外补一份到 `pinduoduo.net` / `pinduoduo.com`。
     *      （能不能被服务端认，只有真机知道；这是 H5 层面唯一能做的努力。）
     */
    fun injectCookies(ck: String) {
        if (ck.isBlank()) return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for (h in HOSTS) {
            for (part in ck.split(";")) {
                val kv = part.trim()
                if (kv.isEmpty() || !kv.contains('=')) continue
                try { cm.setCookie(h, kv) } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
            }
        }
        // 跨域补种（显式带 domain，覆盖该域下所有子域）
        for ((host, domain) in listOf(
            "https://m.pinduoduo.net" to ".pinduoduo.net",
            "https://pinduoduo.net" to ".pinduoduo.net",
            "https://m.pinduoduo.com" to ".pinduoduo.com"
        )) {
            for (part in ck.split(";")) {
                val kv = part.trim()
                if (kv.isEmpty() || !kv.contains('=')) continue
                if (kv.substringBefore('=') !in CRITICAL) continue
                try { cm.setCookie(host, "$kv; domain=$domain; path=/") } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
            }
        }
        cm.flush()
    }

    /**
     * 只清拼多多域（绝不碰淘宝/京东的会话）。
     *
     * ★★ 2026-09-20 修：光按 host 写 `Max-Age=0` **删不掉域级 cookie**。
     * 只按 host 写，等于给那个 host 建一个同名 host-only 空 cookie，
     * 而 `domain=.pinduoduo.com` 上那份原封不动。
     *
     * 后果很隐蔽（真机上表现为"重新登录了但 token 没换"）：
     *   ① [injectCookies] 往 `.pinduoduo.com` / `.pinduoduo.net` 显式补种过 PDDAccessToken；
     *   ② 重新登录后服务端下发的是 `mobile.yangkeduo.com` 的 **host-only** 新 token；
     *   ③ [captureCookiesFromJar] 按 cookie **名字**合并、后写胜出，
     *      而 `m.pinduoduo.net` 排在 HOSTS 末尾 —— 它看到的是那份**旧的域级** cookie
     *      ⇒ 旧 token 把新 token 盖掉 ⇒ `finishPddLogin` 以为登录成功，存的还是死 token。
     *
     * 所以这里必须**显式带 domain** 逐名过期一遍。
     */
    fun clearCookies() {
        val cm = CookieManager.getInstance()
        // ① 先把这个域下能看到的 cookie 名字收集齐（host-only + 域级都会列出来）
        val names = LinkedHashSet(CRITICAL)
        for (h in HOSTS) {
            val old = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
            for (part in old.split(';')) {
                val n = part.trim().substringBefore('=')
                if (n.isNotEmpty()) names.add(n)
            }
        }
        // ② host-only：按 host 逐名过期
        for (h in HOSTS) {
            for (n in names) {
                try { cm.setCookie(h, "$n=; Max-Age=0") } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
            }
        }
        // ③ ★ 域级：必须显式带 domain，否则删不掉（见上面注释）
        for (d in listOf(".pinduoduo.com", ".pinduoduo.net", ".yangkeduo.com")) {
            val host = "https://" + d.trimStart('.')
            for (n in names) {
                try { cm.setCookie(host, "$n=; domain=$d; path=/; Max-Age=0") } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
            }
        }
        cm.flush()
        Log.i(TAG, "clearCookies: 过期 ${names.size} 个 cookie 名（含 3 个域级）")
    }

    /**
     * 诊断：dump「各域 jar 里到底有哪些 cookie」+「页面自身的 URL/标题/document.cookie」。
     * 用来判断"在页面上登录了却仍提示未登录"到底是
     *   ① 域没对上（登录种在 yangkeduo，页面在 pinduoduo.net）
     *   ② 页面要求拼多多 App 容器注入的 JS token（纯 cookie 环境天然拿不到）
     * 落到外部私有目录的 `pdd_diag.txt`。
     */
    fun diagnose(ctx: Context, pageJson: String) {
        try {
            val cm = CookieManager.getInstance()
            val sb = StringBuilder()
            sb.append("\n===== pdd diag @ ").append(System.currentTimeMillis()).append(" =====\n")
            sb.append("page=").append(pageJson.take(700)).append('\n')
            val hosts = HOSTS + listOf(
                "https://m.pinduoduo.com", "https://passport.pinduoduo.com", "https://m.pinduoduo.net"
            )
            for (h in hosts) {
                val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
                val names = ck.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
                sb.append("[").append(h).append("] n=").append(names.size)
                sb.append("  ").append(names.joinToString(",")).append('\n')
                if (ck.contains("PDDAccessToken")) sb.append("      <-- 该域有 PDDAccessToken\n")
            }
            sb.append("archive.keys=")
                .append(
                    cookies(ctx).split(';').map { it.trim().substringBefore('=') }
                        .filter { it.isNotBlank() }.joinToString(",")
                ).append('\n')
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            java.io.File(dir, "pdd_diag.txt").appendText(sb.toString())
            Log.i(TAG, "diag written")
        } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
    }

    // ---------- 抓取 ----------

    /**
     * 抓一次包裹。
     * @param onDone (包裹列表, 失败原因或空串)
     */
    // ★ clean=true：用**独立 cookie 目录**加载（不注入 App 登录态）。
    //   拼多多「物流分享页」的 detail_noauth 接口本来就免登录，但主 cookie 罐里那份
    //   **已失效**的登录态会让服务端回 40001（2026-10-12 实测：带失效 cookie 的 WebView
    //   回 40001、手机浏览器却成功）。
    //   ⚠️ clean 必须放在 onDone **前面**：Kotlin 尾随 lambda 绑定到**最后一个参数**，
    //      放后面的话所有旧调用 `fetch(x){...}` 都会把 lambda 塞给 clean。
    //   ⚠️ suffix 是进程级、只能设一次 —— 所以只用于调试探针，跑完 force-stop。
    fun fetch(
        act: Activity,
        url: String = ORDERS_URL,
        clean: Boolean = false,
        autoJs: String? = null,
        useDefaultUa: Boolean = false,
        onDone: (ArrayList<JSONObject>, String) -> Unit
    ) {
        if (running) {
            onDone(ArrayList(), "正在抓取中")
            return
        }
        val ck = cookies(act)
        if (ck.isBlank()) {
            onDone(ArrayList(), "未登录")
            return
        }
        running = true
        appCtx = act.applicationContext
        if (clean) {
            // ★ 独立 cookie 目录：本进程后续创建的 WebView 都用它（与主 cookie 罐隔离）
            try {
                android.webkit.WebView.setDataDirectorySuffix("pddnoauth")
                Log.i(TAG, "clean 模式：已切换到独立 cookie 目录（不注入登录态）")
            } catch (e: Throwable) {
                Log.w(TAG, "setDataDirectorySuffix 失败: ${e.message}")
            }
        } else {
            injectCookies(ck)
        }
        // ★ 记录本次要加载的页面，收尾/落盘时能对上（探取件码载体时尤其有用）
        fetchedUrl = url

        val wv = WebView(act)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // ★ 与登录页同一个设备身份（见 [BROWSER_UA]）。
        //   以前这里不设 UA ⇒ 走 WebView 默认值（真机机型 + "; wv"，一眼是 WebView），
        //   与登录时的身份不一致 ⇒ 每次抓取都可能被当成另一台设备、重新绑定会话 ⇒ 反复顶。
        // ★ vc303：useDefaultUa=true 时不设桌面 UA —— 部分 H5 页面（如「我的快递」
        //   psnl_goods_help）按 UA 分流渲染：桌面 UA 渲染成 PC 版个人中心（订单流+广告，
        //   没有跨平台快递查询），手机 UA 才是带搜索框的「我的快递」。
        if (!useDefaultUa) wv.settings.userAgentString = BROWSER_UA
        wv.setBackgroundColor(Color.TRANSPARENT)
        val js = WebHook.hookJs(URL_PATTERN)
        // document-start 注入：拼多多的首屏请求也可能很快，能早注就早注
        val docStart = WebHook.attachDocumentStart(
            wv, js,
            setOf("https://m.pinduoduo.net", "https://mobile.yangkeduo.com")
        )
        var finished = false
        var delivered = false
        val captures = ArrayList<Pair<String, String>>()

        /** 收尾（只执行一次）：轮询收工传空串，兜底定时器传"超时" */
        fun deliver(err: String) {
            if (delivered) return
            delivered = true
            finished = true
            finish(act, captures, err, onDone)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // 退化路径：老 WebView 不支持 document-start 时，在页面开始加载时补注入
                if (!docStart) WebHook.attachFallback(view, js)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (finished) return
                // ★ vc303：页面级自动动作（如「我的快递」页自动填单号触发搜索）——
                //   传了 autoJs 就在首屏起来后注入执行；脚本自身带重试，失败无副作用。
                if (autoJs != null) {
                    main.postDelayed({
                        try {
                            view.evaluateJavascript(autoJs, null)
                            Log.i(TAG, "autoJs 已注入（页面自动动作）")
                        } catch (e: Throwable) {
                            Log.w(TAG, "autoJs 注入失败: ${e.message}")
                        }
                    }, 3000)
                }
                // 先给它一点时间把首屏接口跑完，再开始"读捕获 + 主动触发"
                main.postDelayed({
                    startPolling(
                        act, view, captures,
                        quietLimit = if (autoJs != null) 8 else 3,
                        markDone = { done -> if (done) deliver("") }
                    )
                }, 2500)
            }
        }
        attach(act, wv)
        worker = wv
        // ★ 数据源用【消费者订单页】，不是驿站端 mdkd。
        //   实测结论：mdkd 驿站端页面不认消费者 H5 登录拿的 token
        //   （诊断铁证：cookie 里明明有 PDDAccessToken，页面仍循环跳登录页）。
        //   而 orders.html 属于消费者端，我们登录拿的就是它的 token ⇒ 这条路才通。
        wv.loadUrl(url)

        // 兜底：最多 40 秒（正常情况下轮询会先收工并立即 deliver，走不到这里）
        main.postDelayed({
            if (running && !delivered) deliver("超时")
        }, 40_000)
    }

    /**
     * 轮询读 window.name，并主动触发新请求（滚动到底 / 点"加载更多"）——
     * 因为注入时机可能晚于首屏请求，得靠自己再激一次。
     */
    private fun startPolling(
        act: Activity, wv: WebView, captures: ArrayList<Pair<String, String>>, markDone: (Boolean) -> Unit,
        quietLimit: Int = 3, maxRounds: Int = 10
    ) {
        var rounds = 0
        var quiet = 0
        lateinit var step: Runnable
        step = Runnable {
            if (wv !== worker) return@Runnable
            WebHook.readCaptures(wv) { got ->
                val before = captures.size
                captures.addAll(got)
                rounds++
                if (captures.size == before) quiet++ else quiet = 0
                if (DEBUG_EXPORT && got.isNotEmpty()) dump(got, "round$rounds")
                // 连续 3 轮没有新数据、或已经跑了 10 轮 → 收工
                if (rounds >= maxRounds || quiet >= quietLimit) {
                    markDone(true)
                    return@readCaptures
                }
                // 主动再激一次：滚动到底 + 点"加载更多/全部"（注入若晚于首屏，只能靠自己补）
                WebHook.scrollToBottom(wv)
                WebHook.tapByText(wv, listOf("加载更多", "查看更多", "查看全部", "待取件", "待收货"))
                poller = step
                main.postDelayed(step, 2500)
            }
        }
        poller = step
        main.postDelayed(step, 1200)
    }

    private fun finish(
        act: Activity, captures: List<Pair<String, String>>, err: String,
        onDone: (ArrayList<JSONObject>, String) -> Unit
    ) {
        running = false
        cancelPolling()
        detach()
        lastCaptures = captures.toList()
        if (DEBUG_EXPORT) dump(captures, "final")
        val pkgs = parse(captures)
        // ★ 抓到响应但没解析出包裹 = 正常空态（用户没有待取件），不算失败。
        //   同志实测反馈过：没快递时这里报"失败"很莫名其妙。
        val reason = when {
            err.isNotEmpty() -> err
            captures.isEmpty() -> "没有抓到任何接口响应（可能页面未加载或未登录）"
            else -> ""
        }
        Log.i(TAG, "fetch done: captures=${captures.size} pkgs=${pkgs.size} reason=$reason")
        // ★ 把每张卡的「单号/状态/有没有码」摊开写一行：
        //   2026-09-26 就是靠它才看出「抓到 16 个却一个都不显示」是过滤口径的锅
        //   —— 光看 pkgs=16 根本判断不出它们是不是全被完成态黑名单吃掉了。
        if (pkgs.isNotEmpty()) Log.i(TAG, "pkgs: " + pkgs.joinToString(" | ") {
            "${it.optString("orderSn").take(22)}[${it.optString("statusText")}]" +
                "code=${it.optString("pickupCode").take(12)}"
        }.take(2000))
        if (lastTrackStatus.isNotEmpty()) Log.i(TAG, "track: " + lastTrackStatus.entries.joinToString(" | ") {
            "${it.key.take(22)}=${it.value}"
        }.take(1200))
        onDone(pkgs, reason)
    }

    private fun cancelPolling() {
        poller?.let { main.removeCallbacks(it) }
        poller = null
    }

    fun cancel() {
        running = false
        cancelPolling()
        detach()
    }

    private fun attach(act: Activity, wv: WebView) {
        try {
            val decor = act.window?.decorView as? ViewGroup ?: return
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
        } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
        worker = null
    }

    // ---------- 解析 ----------

    /**
     * 从捕获的响应里找包裹。
     * 拼多多字段名随版本变，所以用「候选键 + 递归扫描」，而不是写死路径。
     */
    fun parse(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = LinkedHashSet<String>()
        for ((url, body) in captures) {
            if (body.isBlank() || body.first() != '{' && body.first() != '[') continue
            try {
                val node = if (body.first() == '[') JSONArray(body) else JSONObject(body)
                scan(node, url, out, seen, 0)
            } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
        }
        // ★★ express/track/status：页面对可见订单查的物流状态 —— 这是「取件码页」的**唯一入口**，
        //   订单列表（order_list_v4）里根本没有这些近期的单（2026-09-20 实测：40 次出现全是
        //   track/status）⇒ 订单列表给不了的，这里给。seen 用 "track:单号" 避免与 scan 的 key 撞车。
        //   同时把**每个单的最新状态**记到 lastTrackStatus —— 取件码缓存靠它判断"已取出 ⇒ 消除"。
        //
        //   ★★ 口径（2026-09-26 重写，旧口径是错的）：
        //   旧写法 `if (st != "IN_CABINET") continue` —— 只认 IN_CABINET。而实测这天服务端
        //   返回的是 `260926=ARRIVAL`、`260923=SIGN`，**一条 IN_CABINET 都没有**，
        //   结果真正在驿站待取的那单被直接丢掉，第 4 栏只剩历史完成订单（被黑名单清空）
        //   ⇒ 观感就是"明明有取件码却刷不出来"。
        //   ⚠️ 更要命的是 `SIGN` 并不是"已签收"：同一单的物流页白纸黑字写着「待取件」。
        //   ⇒ 改成**排除"不该出卡"的状态**（已取走 + 还没到驿站的在途，见 NON_PICKUP_STATES），
        //     其余（含未知状态）一律收进来。该判定与缓存消除共用同一份 [isNonPickupState]
        //     —— 两处口径一旦分叉，就会出现"码刚写进缓存转头就被消除"的自相残杀。
        val trackStatus = LinkedHashMap<String, String>()
        for ((url, body) in captures) {
            if (!url.contains("express/track/status")) continue
            if (body.isBlank() || !body.contains("result_list")) continue
            try {
                val root = JSONObject(body.substring(body.indexOf('{')))
                val arr = root.optJSONArray("result_list") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sn = o.optString("order_sn")
                    val st = o.optString("status")
                    if (sn.isEmpty() || st.isEmpty()) continue
                    trackStatus[sn] = st          // ★ 记下状态（供缓存消除用，同一份口径）
                    // SEND 明确是已发货/在途；不能被下面的未知态兜底误当成驿站待取。
                    if (st == "SEND") {
                        Log.i(TAG, "轨道状态 $sn=SEND（已发货/在途），不生成取件码占位卡")
                        continue
                    }
                    // ★★ vc703：**白名单** —— 只有能确认"在驿站待取"的枚举才建卡；
                    //   其余（含 OTHER 这种未知值、SIGN 以外的一切）只记状态。
                    if (st !in PICKUP_CARD_STATES) {
                        Log.i(TAG, "轨道状态 $sn=$st 不在建卡白名单，只记状态不出卡")
                        continue
                    }
                    // 已由订单列表产出（同号）就不再重复
                    if (out.any { it.optString("waybillNo") == sn || it.optString("orderSn") == sn }) continue
                    val key = "track:$sn"
                    if (!seen.add(key)) continue
                    out.add(JSONObject().apply {
                        put("src", "pdd")
                        put("orderSn", sn)                 // ★ 详情页补码（PddTraceChannel）要用它
                        put("pickupCode", "")
                        put("waybillNo", "")
                        put("upCpName", "拼多多")
                        // ★★ vc678：**别再一律写「在驿站待取」**。
                        //   这条路只看得到 track/status 枚举、拿不到页面文案，而 `SIGN` 实测是
                        //   "已从驿站取走、只是没在拼多多点确认收货"（2026-11-03 桑葚干实证）。
                        //   写死成待取 ⇒ 已取走的件常年占着取件码栏、还每轮被当成补码候选白跑 26 秒。
                        //   这里按枚举给文案：SIGN=已取件（下游的完成态过滤会把它藏起来），
                        //   其余仍按待取处理（IN_CABINET/未知不动，避免误伤）。
                        put("statusText", if (st == "SIGN") "已取件" else "在驿站待取")
                        put("goodsName", "")
                        put("stationName", "")
                        put("fromUrl", url.take(120))
                    })
                }
            } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
        }
        // ★★ vc628：**累加，不覆盖**。原来是整体替换 ⇒ 旁听路一跑就把 native 路的枚举冲掉，
        //   渲染层随即查不到状态，退回文案判定（今天"在途少了 2 条"的最后一环）。
        lastTrackStatus = lastTrackStatus + trackStatus
        return out
    }

    /**
     * track/status 里**不该进第 4 栏**的状态（已取走 / 还没到驿站）—— [parse] 按它跳过，
     * 缓存消除（`syncPickupCodeCache`）也必须按同一份判定，两处共用，见 [isNonPickupState]。
     *
     * ★ 实测状态的真实语义（2026-09-26 真机，勿再想当然）：
     * · `IN_CABINET`  = 在驿站待取（老口径）
     * · `SIGN`        = **待取**！不是"已签收" —— 同一单物流页白纸黑字写着「待取件」
     * · `ARRIVAL`     = **在途**（物流页：`已到达 江西南昌分拨交付中心`），还没到驿站，不该出卡
     * · `DEPARTURE`   = **在途**（已离开分拨/运输中）—— vc222 补：2026-09-27 04:07 取证，
     *   在途单 260926 因漏列 DEPARTURE 被收进第 4 栏出裸卡，又赶上 enrich 顶单
     *   （请求 260926、物流页却是 260923，码写给了 260923）被 `pddEnriched` 烧掉额度，
     *   裸卡整会话停在零信息「在驿站待取」＝同志 03:47 报的「两张取件码卡一张没码」。
     * · `OUT_CABINET` / `GOT` = 已取走
     * 枚举还会变 ⇒ 已知在途/已取走全排除；防不住的未来新枚举由 MainActivity 渲染层兜底
     * （零识别信息的拼多多裸卡不上屏，见 renderMerged 的 vc222 注）。
     */
    private val NON_PICKUP_STATES = setOf("OUT_CABINET", "GOT", "ARRIVAL", "DEPARTURE")

    /**
     * ★★ vc703：**能建「在驿站待取」卡的枚举白名单**（同志 2026-11-09 拍板）。
     *
     *  · `IN_CABINET` = 在驿站待取（老口径，实测可信）
     *  · `SIGN`       = 实测两种含义都出现过（"待取件" / "已从驿站取走但没点确认收货"）——
     *                   建卡后由下游按文案打「已取件」灰标，所以留在白名单里。
     *
     * 为什么改成白名单（原来"未知枚举一律收进来"）：当天日志实测 `OTHER` 会给**在途单**
     * 建一张「在驿站待取」空卡（4 张，sn 与在途单完全同号）；渲染层"零信息裸卡不上屏"
     * 只是挡住了显示，它们仍每轮被当补码候选白跑（vc678 注释自己写过"每轮白跑 26 秒"）。
     * 真到站的件仍有另外两条路出卡：cabinet 接口给码、物流页详情给站名+码。
     */
    private val PICKUP_CARD_STATES = setOf("IN_CABINET", "SIGN")

    /**
     * 第 5 栏「途中快递」里**不算途中**的 track/status 状态（已取走 + 在驿站待取）——
     * 与第 4 栏的 [NON_PICKUP_STATES] **互补**：那边只收"待取"，这边排除"待取+已取走"；
     * `ARRIVAL`（在途）与页面没查过状态的单都进第 5 栏。两个口径别混用。
     */
    /**
     * ★ vc608：**只保留真正"不在途中"的两个**。
     *
     * 实测对照（vc347 / vc361 两次真机取证）：
     *  · `GOT`            = 已取走（真）
     *  · `IN_CABINET`     = 在驿站待取（归第 4 栏，不该在途中）
     *  · `OUT_CABINET`    = **出了柜 ≠ 已取走**：vc347 记录的「已签收，待确认收货」实测就是 OUT_CABINET
     *                       （件被驿站/门卫代签，用户还没去拿）⇒ 不能无条件剔
     *  · `SIGN`           = 同上，vc361 记录「不可当待取，也不当已取」
     * 这两个交给调用方的**文案判定**（含"待确认/待收货"就留途中），否则用户会看到"6 个在途只显示 5 个"。
     */
    private val NOT_IN_TRANSIT_STATES = setOf("GOT", "IN_CABINET")

    /** 第 4 栏只收"在驿站待取" —— **parse 收卡**与**缓存消除**必须用这同一份判定 */
    fun isNonPickupState(st: String?): Boolean = st != null && st in NON_PICKUP_STATES

    /**
     * ★ vc212：track/status 已判"在驿站待取/已取走/已签收/在柜"⇒ 第 5 栏不该再显示它。
     * PddNative 原生直取不走 WebView 旁听，拿不到 track 交叉 ⇒ 由调用方（MainActivity）
     * 在合并时用本函数统一过滤，与 [parseInTransit] 770 行的口径**完全一致**（宁可多显示）。
     */
    /**
     * ★★ vc627：把**native 枚举结果并进全局表**。
     *
     * 这是今天最大的一个洞：`PddNative.trackStatusesForCtx()` 的返回值只存在
     * `MainActivity` 的局部变量 `enumMap` 里，只用于抓取层自己的判断；
     * 而渲染层（`PickupVerdict`）查的是 `lastTrackStatus` —— 它只由 WebView 旁听路写入。
     * 结果：native 路跑的时候，**枚举对渲染完全不可见**，全部退回文案判定，
     * 于是"驿站"这种宽泛词就把在途件判成了到站（同志 12:24 实测：5 个在途只显示 3 个）。
     */
    fun mergeTrackStatus(map: Map<String, String>) {
        if (map.isEmpty()) return
        try {
            lastTrackStatus = lastTrackStatus + map
        } catch (t: Throwable) {
            SafeRun.w("PddChannel", t)
        }
    }

    fun trackSaysNotInTransit(sn: String): Boolean =
        lastTrackStatus[sn]?.let { it in NOT_IN_TRANSIT_STATES } == true

    /**
     * 本次 [parse] 看到的「订单号 → 物流状态」，取值如 `IN_CABINET` / `OUT_CABINET` /
     * `GOT` / `SIGN` / `ARRIVAL`。
     * ★ 只有 [PICKED_UP_STATES] 里的才表示"已经取走了" ⇒ [PddCodeStore] 用它把该单的
     *   取件码从缓存里消除（同志要求：发现已取出就自动消除）。其余状态一律按"还在路上/
     *   待取"处理，最终由物流页的「待取件」文案定夺。
     */
    @Volatile
    var lastTrackStatus: Map<String, String> = emptyMap()
        private set

    /** ★ vc257：track 状态落盘文件名（内部 filesDir，无 root 取不到，只能让 App 自己写） */
    private const val TRACK_STATUS_FILE = "track_status.json"

    /** ★ vc257：落盘的陈状态只信 7 天 —— 与第 4 栏的 PDD_CODE_KEEP_DAYS 同口径 */
    private const val TRACK_STATUS_KEEP_MS = TimeGates.ENUM_STATUS_KEEP_MS

    /**
     * ★ vc257：把本次旁听到的 track 状态落盘。
     *
     * 为什么必须落盘：[lastTrackStatus] 是进程内 Map，**App 一杀就丢**（ColorOS 上滑划掉 =
     * 全面清场）。而它是第 4 栏"这单到底取没取"的唯一可靠判据 —— 丢了就只剩
     * "下单超 7 天"这个笨兜底，于是同志看到的现象是：
     *   「前两天取件的快递，取件码栏里还显示在驿站待取」。
     * 拼多多登录态失效时一模一样：没有新数据 ⇒ 没有新状态 ⇒ 判据彻底消失。
     */
    fun persistTrackStatus(ctx: Context) {
        if (lastTrackStatus.isEmpty()) return
        try {
            val m = JSONObject()
            for ((k, v) in lastTrackStatus) m.put(k, v)
            java.io.File(ctx.filesDir, TRACK_STATUS_FILE).writeText(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("map", m)
                    .toString()
            )
            Log.i(TAG, "track 状态落盘 ${m.length()} 条")
        } catch (e: Throwable) {
            Log.w(TAG, "track 状态落盘失败: ${e.message}")
        }
    }

    /**
     * ★ vc257：读回上次的 track 状态。**只在进程内 map 为空时读**（抓取回来后的正常路径
     * 一律以新数据为准，不受影响），且只信 [TRACK_STATUS_KEEP_MS] 内的陈状态。
     */
    fun restoreTrackStatus(ctx: Context) {
        if (lastTrackStatus.isNotEmpty()) return
        try {
            val f = java.io.File(ctx.filesDir, TRACK_STATUS_FILE)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            if (System.currentTimeMillis() - root.optLong("ts") > TRACK_STATUS_KEEP_MS) return
            val m = root.optJSONObject("map") ?: return
            val out = LinkedHashMap<String, String>()
            for (k in m.keys()) out[k] = m.optString(k)
            if (out.isNotEmpty()) {
                lastTrackStatus = lastTrackStatus + out
                Log.i(TAG, "track 状态读回 ${out.size} 条（进程内本来没有）")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "track 状态读回失败: ${e.message}")
        }
    }

    private val CODE_KEYS = listOf(
        "pickupCode", "pickup_code", "pickupNo", "pickup_no", "checkCode", "check_code",
        "takeCode", "take_code", "pickCode", "pickupcode", "qrCode"
        // ★★ 刻意【不含】"code"（2026-09-20 同志实况："一排拼多多取件信息，只有一个带取件码且是 3"）：
        //   拼多多响应里 `code` 是**状态码/枚举**（0、3、200001…），把它当取件码键
        //   会把一大片对象都认成包裹，取件码还显示成孤零零的 "3"。
    )

    /**
     * 取件码形态校验 —— 挡住状态码这类误伤值。
     * 认这三种：①「数字-数字-数字」分段货架码（31-5-7006）②≥4 位且含字母的混合码
     * ③≥6 位纯数字。**纯 1~5 位的小数字直接否掉**（那是 status/code，不是取件码）。
     */
    private fun looksLikePickupCode(v: String): Boolean {
        val s = v.trim()
        if (s.isEmpty()) return false
        if (Regex("\\d{1,3}-\\d{1,4}-\\d{2,6}").matches(s)) return true
        if (s.length >= 4 && s.any { it.isLetter() }) return true
        if (s.length >= 6 && s.all { it.isDigit() }) return true
        return false
    }
    private val WAYBILL_KEYS = listOf(
        "tracking_number", "trackingNumber", "tracking_no", "mailNo", "mail_no",
        "express_no", "expressNo", "logistics_no", "waybillNo", "waybill_no", "delivery_no"
    )
    // ★ vc340：补 cabinetName/cabinet_name —— 快递柜单（丰巢/速递易）的地址在这类字段里，
    //   旧表只认 stationName 一族 ⇒ 柜单捕获到 blob 也抠不出取件地址。
    private val STATION_KEYS = listOf(
        "stationName", "station_name", "pickupPoint", "place", "storeName",
        "cabinetName", "cabinet_name"
    )
    private val GOODS_KEYS = listOf("goodsName", "goods_name", "goods_title", "product_name", "item_name")
    private val STATUS_KEYS = listOf("statusText", "status_desc", "tip", "status")

    private fun scan(node: Any?, url: String, out: ArrayList<JSONObject>, seen: MutableSet<String>, depth: Int) {
        if (depth > 8) return
        when (node) {
            is JSONObject -> {
                val rawCode = firstOf(node, CODE_KEYS, depth)
                // ★ 形态校验：把 "3"、0、200001 这类状态码挡在门外（详见 looksLikePickupCode 的注释）
                val code = if (looksLikePickupCode(rawCode)) rawCode.trim() else ""
                val way = firstOf(node, WAYBILL_KEYS, depth)
                // ★ 订单列表对象的可展示字段在特殊位置（2026-09-20 实测）：
                //   商品名在 order_goods[] **数组**里（firstOf 不递归数组，必须特判）；
                //   状态提示叫 order_status_prompt（参考项目 PDD_REAL：状态名优先取它）。
                var goods = firstOf(node, GOODS_KEYS, 3)
                var status = firstOf(node, STATUS_KEYS, 2)
                if (goods.isBlank()) {
                    val og = node.optJSONArray("order_goods") ?: node.optJSONArray("orderGoods")
                    if (og != null && og.length() > 0) {
                        val g0 = og.optJSONObject(0)
                        val gn = g0?.optString("goods_name").orEmpty()
                        if (gn.isNotBlank()) goods = gn
                    }
                }
                if (status.isBlank()) {
                    val osp = node.optString("order_status_prompt")
                    if (osp.isNotBlank()) status = osp
                }
                val station = firstOf(node, STATION_KEYS, 3)
                // ★★ 有没有"可展示的内容"（2026-09-20 同志实况：8 张只有「拼多多」角标的空卡）。
                //   物流接口的中间对象往往**只有运单号**（商品名/状态/站点都在别的层级），
                //   按"有运单号就当包裹"会把它们全渲染成空卡。⇒ 没有取件码、也没有任何
                //   能给用户看的信息，就不许上屏。
                val hasContent = goods.isNotBlank() || station.isNotBlank() || status.isNotBlank()
                // 认定为一个包裹：有取件码，或有运单号且带包裹语境的字段 + 可展示内容
                if (code.isNotBlank() || (way.isNotBlank() && looksLikeParcel(node) && hasContent)) {
                    val key = (code.ifBlank { way }).trim()
                    if (key.isNotEmpty() && seen.add(key)) {
                        val o = JSONObject()
                        o.put("src", "pdd")
                        // ★ vc352：把订单号也带上 —— 枚举接口按 order_sn 查，没有它就查不了
                        val osn = node.optString("order_sn")
                        if (osn.isNotEmpty()) o.put("orderSn", osn)
                        o.put("pickupCode", code)
                        o.put("waybillNo", way)
                        o.put("shelfNo", "")
                        o.put("upCpName", "拼多多")
                        o.put("nickName", station)
                        o.put("goodsName", goods)
                        o.put("stationName", station)
                        o.put("statusText", status)
                        o.put("fromUrl", url.take(120))
                        out.add(o)
                    }
                }
                for (k in node.keys()) scan(node.opt(k), url, out, seen, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i), url, out, seen, depth + 1)
        }
    }

    /** 判断这个对象像不像一个包裹（避免把商品推荐流、状态码表错认成包裹） */
    private fun looksLikeParcel(o: JSONObject): Boolean {
        val keys = o.keys().asSequence().toList()
        // 订单/包裹语境
        val parcelish = keys.any {
            it in listOf(
                "order_sn", "orderSn", "order_id", "orderId", "mall_order_sn",
                "express", "logistics", "package", "parcel", "cabinet"
            )
        }
        // 排除推荐流（有 goods_list / 纯商品字段但没有订单/物流字段）
        val promo = o.has("goods_list") && !parcelish
        return parcelish && !promo
    }

    private fun firstOf(o: JSONObject, keys: List<String>, depth: Int): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) {
                // 过滤明显不是取件码的（太长的 URL / 纯 JSON）
                val t = v.trim()
                if (t.length in 3..64 && !t.startsWith("http") && !t.startsWith("{") && !t.startsWith("[")) return t
            }
            if (v is Number && k.lowercase().contains("code")) return v.toString()
        }
        // 往下找一层（很多接口把数据包在 data/result 里）
        if (depth < 4) {
            for (k in listOf("data", "result", "item", "packageVo", "expressInfo", "pickupInfo")) {
                val sub = o.optJSONObject(k) ?: continue
                val got = firstOf(sub, keys, depth + 1)
                if (got.isNotBlank()) return got
            }
        }
        return ""
    }

    /** 把原始捕获落盘，便于现场核对字段（受 DEBUG_EXPORT 控制） */
    /**
     * 第 5 栏「途中快递」的终态/非途中词名单。
     * ★ vc212 补「补寄成功」：LED 日光灯管那单（260510）状态是"换货/补寄成功"= 终态，
     *   旧名单漏了它 ⇒ 误卡涌进途中栏（09-27 取证 §六）。
     * [parseInTransit] 与 [PddNative.inTransitScan] 共用这一份，防两处名单漂移。
     */
    /**
     * ★★ vc617：**词表只有一份**，定义在 [PickupVerdict]。这里保留同名字段给旧调用方，
     * 值直接引用裁决器 —— 以后改词只改 PickupVerdict 一处。
     * 语义：终态 = 软终态（可被物流文案翻案）+ 硬终态（无条件丢）。
     */
    val TRANSIT_TERMINAL_WORDS: List<String> =
        PickupVerdict.SOFT_TERMINAL + PickupVerdict.HARD_TERMINAL

    /**
     * ★ vc348：**硬终态** —— 这类不管物流文案写什么都不该进"途中"（退款/取消/交易成功…）。
     * 只有「已签收 / 完成」属于**软终态**：可能与物流文案冲突，冲突时以物流为准。
     */
    val HARD_TERMINAL_WORDS: List<String> = PickupVerdict.HARD_TERMINAL

    /**
     * ★ vc348：物流文案里的"在途"信号。
     * 拼多多的**订单状态字段会滞后/说谎**（同志 2026-10-20 实证：件明明"运输中，预计明天到达"，
     * `order_status_prompt` 却写「已签收，待确认收货」）⇒ 冲突时一律以物流文案为准。
     */
    val MSG_IN_TRANSIT: List<String> = PickupVerdict.MSG_IN_TRANSIT

    /**
     * ★ vc347：这份文案算不算"终态"。
     *
     * 拼多多的 **「已签收，待确认收货」** 是个特殊态：驿站/门卫已经代签，但用户还没确认收货
     * ⇒ 件其实**还在等他去拿**，不能按"已签收"整条丢掉（2026-10-20 同志报：
     * 两个拼多多途中件被这句文案吃掉，两栏都看不见）。
     * 规则：带「待确认 / 待收货」的一律放行，其余命中终态词才算终态。
     */
    fun isTransitTerminal(status: String): Boolean {
        if (status.contains("待确认") || status.contains("待收货")) return false
        return TRANSIT_TERMINAL_WORDS.any { status.contains(it) }
    }

    /** 途中快递栏的宽口径：一切**非终态、非"在驿站待取"、非"已取走"**的订单（在途/未知状态）。
     *  ★★ 09-26 取证：`order_list_v4` 响应的订单数组叫 **`orders`**（不是 order_list）——
     *  旧代码找 `order_list` 永远 null ⇒ **第 5 栏的拼多多在途件从未显示过**（338 条捕获 0 条命中）。
     *  另：捕获 body 里的 `| BODY:` 拼在 URL 字段（WebHook 169 行），body 本身是**纯响应**，
     *  之前的"请求+响应连体"误判是 dump 目测造成的，别再被它带偏。 */
    fun parseInTransit(captures: List<Pair<String, String>>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for ((_, body) in captures) {
            if (!body.contains("orderList") && !body.contains("order_status_prompt")
                && !body.contains("\"orders\"")
            ) continue
            try {
                val root = JSONObject(body)
                val arr = root.optJSONArray("order_list")
                    ?: root.optJSONArray("orders")
                    ?: root.optJSONObject("data")?.optJSONArray("order_list")
                    ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sn = o.optString("order_sn")
                    if (sn.isEmpty() || !seen.add(sn)) continue
                    val status = o.optString("order_status_prompt")
                    // 终态（完成/取消/退款…）与"还没发货"不进"途中"栏
                    // ★ 09-26 补：交易成功/已评价/待发货/待分享 —— 旧名单漏了它们，
                    //   16 个历史完成单会整屏涌进第 5 栏
                    // ★ vc347：改用 isTransitTerminal —— 「已签收，待确认收货」不算终态
                    // ★ vc348：订单状态与物流文案冲突时以物流为准（物流说在途 ⇒ 放行）
                    val msg = o.optString("shipping_message")
                    val msgInTransit = MSG_IN_TRANSIT.any { msg.contains(it) }
                    val hard = HARD_TERMINAL_WORDS.firstOrNull { status.contains(it) }
                    if (hard != null) {
                        Log.i("PddChannel", "途中丢弃 $sn：硬终态「$hard」（订单=「$status」）")
                        continue
                    }
                    if (isTransitTerminal(status) && !msgInTransit) {
                        Log.i(
                            "PddChannel",
                            "途中丢弃 $sn：终态「$status」（物流=「${msg.take(40)}」）"
                        )
                        continue
                    }
                    if (isTransitTerminal(status) && msgInTransit) {
                        Log.i(
                            "PddChannel",
                            "途中保留 $sn：订单「$status」与物流「${msg.take(40)}」冲突，按物流在途"
                        )
                    }
                    // ★ track/status 已给出权威状态的单按状态交叉判定：
                    //   已取走 / 在驿站待取 都不算"途中"（待取的第 4 栏有卡，别重复显示）。
                    //   ARRIVAL 与页面没查过状态的单（lastTrackStatus 没有它）都放行 —— 宁可多显示。
                    if (lastTrackStatus[sn]?.let { it in NOT_IN_TRANSIT_STATES } == true) continue
                    // ★ vc350：无物流信号的单（多多买菜/未发货类）不是快递，不进途中
                    //   （与 PddNative.inTransitScan 同一口径：无运单号 + 物流文案为空）
                    if (msg.isEmpty() && o.optString("tracking_number").isEmpty()) {
                        Log.i("PddChannel", "途中丢弃 $sn：无物流信号（订单=「$status」）")
                        continue
                    }
                    val goods = o.optJSONArray("order_goods")?.optJSONObject(0)
                        ?.optString("goods_name").orEmpty()
                    val pkg = JSONObject()
                    pkg.put("src", "pdd")
                    pkg.put("goodsName", goods)
                    pkg.put("statusText", status)
                    pkg.put("latest", o.optString("shipping_message").ifEmpty { status })
                    pkg.put("trackLink", "")
                    pkg.put("waybillNo", sn)
                    // 收件手机号：给快递100 用（拼多多常见 pickUpInfo.contactPhone / receiveMobile）
                    pkg.put("phone", findPhone(o))
                    out.add(pkg)
                }
            } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
        }
        return out
    }

    /** 手机号字段的候选名（拼多多各版本字段名不一） */
    private val PHONE_KEYS = listOf(
        "contactPhone", "receiverPhone", "receiveMobile", "mobile", "phone", "tel", "phoneNum"
    )

    /**
     * 从订单对象里挖收件手机号（给快递100 查询用，免得用户手填 —— 这点跟参考项目一致：
     * 它的 ExpressItem.phone 本来就是从各平台响应里带出来的）。
     * 候选名 + 递归找 11 位数字，字段名换了也不会整个失效。
     */
    fun findPhone(node: Any?): String {
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
                    for (i in 0 until o.length()) scan(o.opt(i), depth + 1)?.let { return it }
                }
            }
            return null
        }
        return scan(node, 0).orEmpty()
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
                sb.append(b.take(200_000)).append('\n')
            }
            java.io.File(dir, "pdd_capture.txt").appendText(sb.toString())
            Log.i(TAG, "dump $tag: ${captures.size} items")
        } catch (t: Throwable) { SafeRun.w("PddChannel", t) }
    }
}
