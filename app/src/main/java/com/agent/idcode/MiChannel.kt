package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * 小米通道：账号登录 + 换 serviceToken。
 *
 * ## 流程（sid=assistant，即小米"个人助理/快递助手"服务）
 * ① GET `account.xiaomi.com/pass/serviceLogin?sid=assistant&_json=true`
 *    → 响应（可能带 `&startService;` 前缀）是 JSON，里面的 `location` 才是真正的登录页地址
 * ② WebView 打开 location，用户完成登录（账号密码 / 小米 App 扫码）
 *    → cookie 里出现 `passToken` / `userId` / `deviceId`
 * ③ 带这三个 cookie 再调 serviceLogin → 拿 `ssecurity` + `nonce` + 新 `location`
 * ④ `clientSign = Base64(SHA1("nonce=$nonce&$ssecurity"))`
 *    → GET `location?clientSign=…&_userIdNeedEncrypt=true`（**必须禁用重定向**）
 *    → 响应头 Set-Cookie 里的 `serviceToken`（或 `assistant_serviceToken`）就是最终通行证
 *
 * 之后调快递接口只需 `Cookie: serviceToken=…; cUserId=…`
 * （接口路径/请求体的 AES-ECB 加密在抓取阶段做，key 是固定值，自己算即可）。
 *
 * 注意：这里所有网络调用都是**阻塞式**的，必须在后台线程调（MainActivity 用线程池包着调）。
 */
object MiChannel {

    private const val TAG = "MiChannel"

    private const val KEY_TOKEN = "mi_service_token"
    private const val KEY_CUSER = "mi_cuser_id"
    private const val KEY_ACCOUNT = "mi_account_id"

    /**
     * 桌面 Chrome UA。
     * ★ 必须与登录 WebView 的 UA 一致（MainActivity 对 "mi" 模式设同一个）——
     *   登录用移动 UA、换票用桌面 UA 会被服务端判"登录验证失败"。
     */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)

    fun isLoggedIn(ctx: Context): Boolean = prefs(ctx).getString(KEY_TOKEN, "").orEmpty().isNotEmpty()

    fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "").orEmpty()

    fun cUserId(ctx: Context): String = prefs(ctx).getString(KEY_CUSER, "").orEmpty()

    fun accountId(ctx: Context): String = prefs(ctx).getString(KEY_ACCOUNT, "").orEmpty()

    fun saveLogin(ctx: Context, token: String, cUserId: String, accountId: String) {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_CUSER, cUserId)
            .putString(KEY_ACCOUNT, accountId)
            .apply()
    }

    fun clearLogin(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TOKEN).remove(KEY_CUSER).remove(KEY_ACCOUNT).remove(KEY_PHONES).apply()
    }

    /** 退出：清掉 SP 里的令牌 + jar 里的小米域 cookie（不清全局，别的数据源靠 jar 活着） */
    fun clearJar() {
        val cm = android.webkit.CookieManager.getInstance()
        for (h in listOf(
            "https://account.xiaomi.com", "https://passport.xiaomi.com",
            "https://api.assistant.miui.com", "https://mi.com", "https://www.mi.com",
            "https://xiaomi.com"
        )) {
            val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
            if (ck.isEmpty()) continue
            for (kv in ck.split(';')) {
                val name = kv.trim().substringBefore('=')
                if (name.isNotBlank()) cm.setCookie(h, "$name=; Max-Age=0; path=/")
            }
        }
        try { cm.flush() } catch (t: Throwable) { SafeRun.w("MiChannel", t) }
    }

    // ---------- 手机号：三级自动探测（照参考项目的做法，**不让用户手输**） ----------

    private const val KEY_PHONES = "mi_phones"

    fun phones(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_PHONES, "").orEmpty().split(',').filter { it.length == 11 }

    fun savePhone(ctx: Context, phone: String) {
        val m = Regex("^1[3-9]\\d{9}$")
        if (!m.matches(phone)) return
        val cur = phones(ctx).toMutableList()
        if (!cur.contains(phone)) {
            cur.add(phone)
            prefs(ctx).edit().putString(KEY_PHONES, cur.joinToString(",")).apply()
            Log.i(TAG, "phone saved: $phone (共 ${cur.size} 个)")
        }
    }

    /**
     * ③ 从小米账号资料接口探测绑定手机号（**不解析 JSON 结构，直接正则扫 11 位号码** —— 照参考项目，
     * 因为端点与字段名随版本变，扫文本最耐操）。阻塞式，后台线程调。
     */
    fun detectPhoneFromProfile(): String {
        val c = passCookies()
        val cookie = buildString {
            c["userId"]?.let { append("userId=").append(it).append("; ") }
            c["passToken"]?.let { append("passToken=").append(it).append("; ") }
            c["cUserId"]?.let { append("cUserId=").append(it) }
        }
        if (cookie.isBlank()) return ""
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        val urls = listOf(
            "https://account.xiaomi.com/fe/service/account/profile",
            "https://account.xiaomi.com/fe/api/account/profile",
            "https://account.xiaomi.com/user/profile",
            "https://api.account.xiaomi.com/user/profile"
        )
        val re = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
        for (u in urls) {
            try {
                val body = httpGetPlain(u, cookie, ua)
                if (body == null) {
                    Log.i(TAG, "profile probe $u -> 无响应体")
                    continue
                }
                Log.i(TAG, "profile probe $u -> ${body.length} 字符, 首80=${body.take(80).replace("\n", " ")}")
                re.find(body)?.let {
                    Log.i(TAG, "phone from profile: $u -> ${it.value.take(3)}****${it.value.takeLast(2)}")
                    return it.value
                }
            } catch (e: Throwable) {
                Log.i(TAG, "profile probe $u -> ERR ${e.message}")
            }
        }
        Log.i(TAG, "profile probe: 4 个端点都没扫到手机号")
        return ""
    }

    /** 从任意 URL 里抠手机号（登录页 URL 常带 `_user=1xxxxxxxxxx`） */
    fun phoneFromUrl(url: String): String {
        val m = Regex("[?&](?:_user|user)=1(\\d{10})").find(url) ?: return ""
        return "1" + m.groupValues[1]
    }

    private fun httpGetPlain(url: String, cookie: String, ua: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", ua)
        }
        val input: InputStream? = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        conn.disconnect()
        return text
    }

    // ============ getList：走小米智能助理的原生接口（路线①，不依赖 Shizuku） ============

    private const val API_HOST = "https://verca.xpa.assistant.miui.com"
    private const val AES_KEY = "d101b17c77ff93cs"
    private const val SIGN_SALT = "77eb2e8a5755abd016c0d69ba74b219c"
    private const val DEFAULT_PA_VERSION = 25000031

    /**
     * 反射 MSA（移动安全联盟）标准接口拿 OAID / VAID。
     * **多数国产 ROM（含 ColorOS）内置该实现，不需要 Shizuku** —— 参考项目把提权只当兜底。
     * 拿不到就返回空串：参考项目自己也说"空串也走兜底"，服务端未必校验。
     */
    private fun msaId(ctx: Context, method: String): String = try {
        val cls = Class.forName("com.android.id.impl.IdProviderImpl")
        val inst = cls.newInstance()
        val v = cls.getMethod(method, Context::class.java).invoke(inst, ctx) as? String
        Log.i(TAG, "msaId($method) = ${if (v.isNullOrEmpty()) "(空)" else v.take(6) + "…" + v.takeLast(4)}")
        v.orEmpty()
    } catch (e: Throwable) {
        Log.i(TAG, "msaId($method) 反射失败：${e.message}（继续用空串）")
        ""
    }

    private fun aesEncrypt(plain: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        )
        return android.util.Base64.encodeToString(
            c.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP
        )
    }

    private fun aesDecrypt(b64: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        )
        return String(
            c.doFinal(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)), StandardCharsets.UTF_8
        )
    }

    /**
     * ★★ 请求体加解密：**优先反射 MIUI 系统类 `miui.util.CoderUtils` 的静态方法**
     * （`base64AesEncode` / `base6AesDecode`，两个 String 参数），拿不到才退回本地 AES-ECB。
     *
     * 为什么必须这么做：只用本地 AES 时服务端直接回
     * `{"code":50000000,"message":"Decryption error..."}` ——
     * 而同志在 ColorOS 上实测参考项目的小米同步**成功**（"在途包裹已更新 0 件"）
     * ⇒ 那个系统类在 ColorOS 上**其实存在**，用它的实现服务端才认。
     */
    private fun coderEncode(plain: String): String = try {
        val cls = Class.forName("miui.util.CoderUtils")
        val m = cls.getMethod("base64AesEncode", String::class.java, String::class.java)
        val v = m.invoke(null, plain, AES_KEY) as? String
        if (v.isNullOrEmpty()) throw RuntimeException("CoderUtils 返回空")
        Log.i(TAG, "body 加密：CoderUtils.base64AesEncode 反射成功")
        v
    } catch (e: Throwable) {
        Log.i(TAG, "body 加密：CoderUtils 反射失败（${e.message}），退回本地 AES")
        aesEncrypt(plain)
    }

    private fun coderDecode(cipher: String): String = try {
        val cls = Class.forName("miui.util.CoderUtils")
        val m = cls.getMethod("base6AesDecode", String::class.java, String::class.java)
        val v = m.invoke(null, cipher, AES_KEY) as? String
        if (v.isNullOrEmpty()) throw RuntimeException("CoderUtils 返回空")
        v
    } catch (_: Throwable) {
        aesDecrypt(cipher)
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sysProp(key: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (_: Throwable) {
        null
    }

    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    data class ListResult(val ok: Boolean, val info: String, val dump: String)

    /**
     * 拉包裹列表（阻塞式，后台线程调）。
     *
     * 签名：路径 AES-ECB（固定 key）+ `SHA1("yellowpage_encparam"+enc+盐).uppercase`
     * 请求体：业务 JSON 包成 `{userSignal, environmentSignal, timeSignal, eventSignal}` 后**整体再 AES**
     * 响应：`{data:"<密文>"}` → 解密 data
     *
     * 返回里带**原始响应与解密结果**，由上层落盘 —— 先看真数据，再写解析。
     */
    fun getList(ctx: Context, phones: List<String>): ListResult {
        val token = token(ctx)
        val cUser = cUserId(ctx)
        if (token.isEmpty()) return ListResult(false, "未登录（没有 serviceToken）", "")

        val biz = buildString {
            append("{\"info\":{\"limit\":29,\"phones\":[")
            append(phones.joinToString(",") { "\"$it\"" })
            append("],\"deletedMailNos\":[],\"modifiedMailNos\":[]}}")
        }

        val encPath = aesEncrypt("/cpa/express/v2/getList")
        val sign = sha1Hex("yellowpage_encparam$encPath$SIGN_SALT").uppercase()
        val url = "$API_HOST/cpa/express/v2/getList?version=$DEFAULT_PA_VERSION&appkey=yellowpage" +
            "&yellowpage_encparam=${enc(encPath)}&sign=$sign&_encparam=${enc(encPath)}"

        return try {
            // ★ body 走 CoderUtils（反射优先），响应 data 用同源解密
            val raw = httpPostJson(
                url, coderEncode(buildBody(ctx, biz, cUser)), "serviceToken=$token; cUserId=$cUser"
            )
            val dec = try {
                val root = JSONObject(raw.substring(raw.indexOf('{')))
                val d = root.optString("data")
                if (d.isNotEmpty() && d != "null") coderDecode(d) else ""
            } catch (e: Throwable) {
                "解密失败：${e.message}"
            }
            Log.i(TAG, "getList raw=${raw.length} chars, dec=${dec.length} chars")
            ListResult(true, "rawLen=${raw.length} decLen=${dec.length}", "== RAW ==\n$raw\n\n== DECRYPTED ==\n$dec")
        } catch (e: Throwable) {
            Log.w(TAG, "getList failed: ${e.message}")
            ListResult(false, "请求异常：${e.message}", "")
        }
    }

    /** 请求体包装：业务 JSON 进 eventSignal，外面套 userSignal / environmentSignal / timeSignal */
    private fun buildBody(ctx: Context, biz: String, userId: String): String {
        val tz = java.util.TimeZone.getDefault().getDisplayName(false, 0)
        val user = JSONObject()
            .put("oaid", msaId(ctx, "getOAID"))
            .put("vaid", msaId(ctx, "getVAID"))
            .put("userId", userId)
        val env = JSONObject()
            .put("terminal", "phone")
            .put("assistantVersion", DEFAULT_PA_VERSION)
            .put("launchVersion", 0)
            .put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("androidSdkVersion", android.os.Build.VERSION.SDK_INT)
            .put("miuiType", "V8")
            .put("phoneModel", android.os.Build.MODEL)
            .put("phoneDevice", android.os.Build.DEVICE)
            .put("connectionType", "wifi")
            .put("miuiVersionName", sysProp("ro.miui.ui.version.name") ?: "V8")
            .put("miuiVersion", sysProp("ro.miui.ui.version.code") ?: "")
            .put("osVersionName", sysProp("ro.mi.os.version.name") ?: "-1.0")
            .put("osVersion", sysProp("ro.mi.os.version.code") ?: "")
            .put("miSettingsVersionName", "")
            .put("miSettingsVersionCode", 0)
            .put("timeZone", tz)
            .put("language", java.util.Locale.getDefault().language)
            .put("country", java.util.Locale.getDefault().country)
            .put("screen", 1)
            .put("coordinate", "")
            .put("reqId", java.util.UUID.randomUUID().toString().replace("-", ""))
            .put("ip", localIp())
            .put("cpuArchitecture", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            .put("marketVersion", 0)
            .put("os", android.os.Build.VERSION.INCREMENTAL)
            .put("clientFlag", 0)
            .put("hwVersion", sysProp("ro.boot.hwversion") ?: "")
        val time = JSONObject()
            .put("clientTs", System.currentTimeMillis().toString())
            .put("timeZone", tz)
        return JSONObject()
            .put("userSignal", user)
            .put("environmentSignal", env)
            .put("timeSignal", time)
            .put("eventSignal", JSONObject(biz))
            .toString()
    }

    private fun httpPostJson(url: String, encBody: String, cookie: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", UA)
        }
        conn.outputStream.use { it.write(encBody.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val input: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return "HTTP $code\n$text"
    }

    /**
     * 按需跑一次 getList 并把原始响应落盘（6 小时节流），供真机验证用。
     * 落盘位置：外部私有目录（`Android/data/<pkg>/files/mi_list.txt`），adb 可直接 pull。
     */
    fun tryGetListIfDue(ctx: Context) {
        val now = System.currentTimeMillis()
        // 调试期 30 秒节流（正式版改回 6 小时）；注意别误删 prefs 文件，那会连登录态一起丢
        if (now - prefs(ctx).getLong(KEY_LIST_TRY, 0L) < 30_000L) return
        val phones = phones(ctx)
        if (phones.isEmpty() || !isLoggedIn(ctx)) return
        prefs(ctx).edit().putLong(KEY_LIST_TRY, now).apply()
        try {
            val r = getList(ctx, phones)
            Log.i(TAG, "tryGetList: ok=${r.ok} ${r.info}")
            if (r.dump.isNotEmpty()) {
                val f = java.io.File(ctx.getExternalFilesDir(null), "mi_list.txt")
                f.writeText("${r.info}\n\n${r.dump}")
                Log.i(TAG, "getList dump -> ${f.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "tryGetList failed: ${e.message}")
        }
    }

    private const val KEY_LIST_TRY = "mi_list_try_at"

    // ---------- ① 登录页地址 ----------

    /**
     * 拿真正的登录页 URL（后台线程调）。
     * serviceLogin 的 `_json=true` 响应可能是 `&startService;{...}`，所以从第一个 `{` 开始截。
     */
    fun fetchLoginPageUrl(): String {
        val body = httpGet("https://account.xiaomi.com/pass/serviceLogin?sid=assistant&_json=true", null)
        val json = body.substring(body.indexOf('{'))
        val location = JSONObject(json).optString("location")
        if (location.isEmpty()) throw RuntimeException("小米未返回登录地址: ${json.take(200)}")
        return location
    }

    // ---------- ② WebView 登录后的 cookie 检查 ----------

    /**
     * jar 里 Xiaomi 账号域的 cookie。
     *
     * ★ 顺序敏感：**账号域（account/passport.xiaomi.com）必须优先且不被覆盖**。
     *   早先按 `xiaomi.com` / `mi.com` / `www.mi.com` 一起扫并让后者盖前者，
     *   结果换票时服务端回"登录验证失败" —— 很可能就是同名 cookie 被别的域盖掉了。
     */
    fun passCookies(): Map<String, String> {
        val cm = android.webkit.CookieManager.getInstance()
        val parts = LinkedHashMap<String, String>()
        // 先账号域（权威），后其他域（只补缺，不覆盖）
        for (h in listOf(
            "https://account.xiaomi.com",
            "https://passport.xiaomi.com",
            "https://xiaomi.com",
            "https://mi.com",
            "https://www.mi.com"
        )) {
            val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
            if (ck.isEmpty()) continue
            for (kv in ck.split(';')) {
                val t = kv.trim()
                if (!t.contains('=')) continue
                val k = t.substringBefore('=')
                val v = t.substringAfter('=')
                if (v.isNotEmpty() && !parts.containsKey(k)) parts[k] = v
            }
        }
        return parts
    }

    /** 诊断：看换票到底拿到了哪些 cookie（值只打长度与首尾，不落明文） */
    fun diagCookies(): String {
        val c = passCookies()
        val need = listOf("passToken", "userId", "deviceId", "cUserId", "passToken_ph")
        val sb = StringBuilder()
        for (k in need) {
            val v = c[k]
            sb.append("\n  ").append(k).append(" = ").append(
                if (v == null) "(缺失)"
                else "len=${v.length} head=${v.take(6)}…tail=…${v.takeLast(4)}"
            )
        }
        sb.append("\n  全部键: ").append(c.keys.joinToString(","))
        return sb.toString()
    }

    /** WebView 登录完成的判据：三个关键 cookie 齐 */
    fun hasPassCookies(): Boolean {
        val c = passCookies()
        return c.containsKey("passToken") && c.containsKey("userId") && c.containsKey("deviceId")
    }

    /**
     * ★★ 主路径：登录页完成登录时，**页面的 STS 跳转自己就把令牌写进 cookie jar 了**，
     * 位置在 `api.assistant.miui.com` 域（不是账号域）。参考项目就是优先读这里的，`mint()` 只是兜底。
     * 我们原先只走 mint，于是换来一句"登录验证失败"。
     */
    private const val STS_HOST = "https://api.assistant.miui.com"

    fun stsToken(): String {
        val ck = try {
            android.webkit.CookieManager.getInstance().getCookie(STS_HOST).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (ck.isEmpty()) return ""
        for (kv in ck.split(';')) {
            val t = kv.trim()
            if (t.startsWith("assistant_serviceToken=")) return t.substringAfter('=').trim()
            if (t.startsWith("serviceToken=")) return t.substringAfter('=').trim()
        }
        return ""
    }

    /**
     * 自动轮询的就绪判据：**只等 STS 令牌**。
     *
     * ★ 早先写成 `stsToken 非空 || hasPassCookies()`，用"或"导致账号域 cookie 一齐就立刻收尾，
     *   而那时页面还没走到 STS 跳转、令牌自然是空的 ⇒ 每次都掉进会被拒的兜底换票（code 70016）。
     *   参考项目的做法就是优先等 STS，这里照它来。
     */
    fun isLoginDone(): Boolean = stsToken().isNotEmpty()

    /** 诊断：STS 域（api.assistant.miui.com）上现在能看到哪些 cookie 键 */
    fun diagSts(): String {
        val ck = try {
            android.webkit.CookieManager.getInstance().getCookie(STS_HOST).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (ck.isEmpty()) return "($STS_HOST 域无 cookie)"
        val names = ck.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
        return "STS 域键: " + names.joinToString(",")
    }

    // ---------- ③④ 换 serviceToken ----------

    data class MintResult(val token: String, val cUserId: String)

    /**
     * 用 WebView 登录拿到的 cookie 换 serviceToken（后台线程调）。
     * 失败抛异常，调用方给用户提示。
     */
    fun mint(): MintResult {
        val c = passCookies()
        val passToken = c["passToken"] ?: throw RuntimeException("cookie 里没有 passToken，请重新登录")
        val userId = c["userId"] ?: throw RuntimeException("cookie 里没有 userId，请重新登录")
        val deviceId = c["deviceId"] ?: throw RuntimeException("cookie 里没有 deviceId，请重新登录")

        val loginUrl = "https://account.xiaomi.com/pass/serviceLogin" +
            "?sid=assistant&_json=true&_appName=com.miui.personalassistant&_locale=zh_CN"
        val cookie = "userId=${enc(userId)}; passToken=${enc(passToken)}; deviceId=${enc(deviceId)}"

        val body = httpGet(loginUrl, cookie)
        val json = body.substring(body.indexOf('{'))
        val obj = JSONObject(json)
        if (obj.optInt("code") != 0) {
            // 换票失败最常见的两种：cookie 不对（被别的域盖了 / 没拿到）或 UA 不一致被风控
            Log.w(TAG, "serviceLogin failed: desc=${obj.optString("desc")} cookies=${diagCookies()}")
            Log.w(TAG, "serviceLogin resp=${json.take(240)}")
            throw RuntimeException("小米登录失败：${obj.optString("desc")}")
        }
        val ssecurity = obj.getString("ssecurity")
        val nonce = obj.getLong("nonce")
        val location = obj.getString("location")
        val cUserId = obj.optString("cUserId")

        val clientSign = sha1Base64("nonce=$nonce&$ssecurity")
        val sep = if (location.contains('?')) "&" else "?"
        val stsUrl = "$location${sep}clientSign=${enc(clientSign)}&_userIdNeedEncrypt=true"

        // STS 必须禁用跟随重定向：serviceToken 在 302 响应的 Set-Cookie 里
        val (headers, stsBody) = httpGetWithHeaders(stsUrl, cookie)
        val token = extractCookie(headers, "serviceToken")
            ?: extractCookie(headers, "assistant_serviceToken")
        if (token.isNullOrEmpty()) {
            val stsJson = try { stsBody.substring(stsBody.indexOf('{')) } catch (_: Throwable) { "" }
            if (stsJson.contains("\"S\":\"OK\"")) {
                throw RuntimeException("小米返回了空令牌（可能账号风控或登录态过期），请退出后重新登录")
            }
            throw RuntimeException("没有取到小米令牌（STS 响应异常），请重新登录")
        }
        Log.i(TAG, "mint ok tokenLen=${token.length} cUser=$cUserId")
        return MintResult(token, cUserId)
    }

    // ---------- HTTP / 加密工具 ----------

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun sha1Base64(input: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
        )

    private fun httpGet(url: String, cookie: String?): String {
        val conn = open(url, cookie, followRedirects = true)
        val text = read(conn)
        conn.disconnect()
        return text
    }

    private fun httpGetWithHeaders(url: String, cookie: String?): Pair<Map<String, String>, String> {
        val conn = open(url, cookie, followRedirects = false)
        val headers = HashMap<String, String>()
        for ((k, v) in conn.headerFields) {
            if (k == null) continue
            headers[k] = v.joinToString("\n")
        }
        val text = read(conn)
        conn.disconnect()
        return headers to text
    }

    private fun open(url: String, cookie: String?, followRedirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", UA)
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val input: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        return "HTTP $code\n$text"
    }

    private fun extractCookie(headers: Map<String, String>, name: String): String? {
        for ((k, v) in headers) {
            if (!k.equals("Set-Cookie", ignoreCase = true)) continue
            for (line in v.split("\n")) {
                val t = line.trim()
                if (t.startsWith("$name=", ignoreCase = true)) {
                    return t.substring(name.length + 1).substringBefore(';')
                }
            }
        }
        return null
    }
}
