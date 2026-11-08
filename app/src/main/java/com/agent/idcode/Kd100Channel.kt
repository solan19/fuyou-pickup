package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * 快递100 通道：**轨迹 / ETA 增强**（不是包裹列表）。
 *
 * ## 定位（照抄自参考项目的 KuaiDi100.kt，实测可用的两个免费接口）
 * 它不能按账号列出包裹 —— 给它 [运单号 + 快递公司码 + 手机号]，它返回：
 *   · 完整轨迹链（context/time/ftime 的数组）→ 第 5 栏展开区的"精确物流"
 *   · `expTime` 预计送达时间 → 第 5 栏折叠行的"预计剩余几天"
 * 所以设置页里它是一个**开关**：开 = 启用这条增强，关 = 回到只显示平台自带的最新轨迹。
 *
 * 两个接口（手机版免登录，官方版要付费授权 key，我们只用手机版）：
 *   ① 运单号 → 快递公司码：`www.kuaidi100.com/autonumber/auto?num=`
 *   ② 轨迹查询：POST `p.kuaidi100.com/mobile/mobileapi.do?method=query`
 *      body 里 json = 一串设备参数+phone+num+com，hash = MD5(SECRET + json).uppercase
 */
object Kd100Channel {

    private const val TAG = "Kd100Channel"

    /** 快递100 手机版接口的固定签名盐（逆向自其官方 App） */
    private const val MOBILE_SECRET = "L0Z1yKqPXseWi4ERAUFnxQmgHwhafITG"

    private const val KEY_ENABLED = "kd100_enabled"

    // ★ 快递100 **官方接口**的凭据（照参考项目 ExpressAssistant 的做法：用户在设置里自己填）。
    //   官方接口 `poll.kuaidi100.com/poll/query.do` 的 param **支持 phone** ——
    //   而"韵达等快递查前要手机尾号"正好卡在这：免费通道没有这个能力。
    private const val KEY_KD_KEY = "kd_key"
    private const val KEY_KD_CUSTOMER = "kd_customer"

    fun kdKey(ctx: Context): String = prefs(ctx).getString(KEY_KD_KEY, "").orEmpty()

    fun kdCustomer(ctx: Context): String = prefs(ctx).getString(KEY_KD_CUSTOMER, "").orEmpty()

    fun saveOfficial(ctx: Context, key: String, customer: String) {
        prefs(ctx).edit().putString(KEY_KD_KEY, key.trim()).putString(KEY_KD_CUSTOMER, customer.trim()).apply()
    }

    /** 配了官方凭据没？配了优先走官方接口（能带 phone 校验） */
    fun hasOfficial(ctx: Context): Boolean = kdKey(ctx).isNotBlank() && kdCustomer(ctx).isNotBlank()

    private val UA = "Dalvik/2.1.0 (Linux; U; Android 5.1.1; PCT-AL10 Build/LYZ28N)"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)

    /** 数据源开关（同志要求：设置页可一键启停这条增强） */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    // ---------- ① 运单号 → 快递公司码 ----------

    data class Company(val comCode: String, val name: String)

    fun detectCompany(mailNo: String): Company? {
        return try {
            val text = httpGet("https://www.kuaidi100.com/autonumber/auto?num=${URLEncoder.encode(mailNo, "UTF-8")}")
            val arr = JSONArray(text.substring(text.indexOf('[')))
            if (arr.length() == 0) null else {
                val o = arr.optJSONObject(0) ?: return null
                Company(o.optString("comCode"), o.optString("name"))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "detectCompany: ${e.message}")
            null
        }
    }

    /**
     * ★ vc260（2026-10-14）：运单号**字母前缀 → 快递公司码**。
     *
     * 为什么要它 + 为什么删掉跨公司盲试（FALLBACK_CODES / TAIL_CODES 已删）：
     * · 10-14 真机实证：圆通件 `YT0714785611281`，autonumber **正确识别为 yuantong**，
     *   但免费通道没有这件（已取完）包裹的数据 ⇒ 旧代码"不死心"去试韵达/中通/申通，
     *   其中一家有一件**撞号**包裹（梅州丰顺的件），它的轨迹被当成这一单的显示在界面上
     *   ⇒ **比"查不到"更糟：显示假物流**。且手动单号没有下单日期，vc216 的时间硬校验拦不住。
     * · 旧结论"autonumber 会把公司认错所以要兜底"（vc223 注）在这单上被证伪：识别的就是对的，
     *   只是免费通道没数据 ⇒ 兜底的唯一实证效果就是返回别人的轨迹。
     * · ⇒ 新口径：**前缀优先，只查一家，不跨公司盲试**。认不出就老实说查不到
     *   （卡片照出——vC254"查不到也出卡"不受影响，只是文案更诚实）。
     * · 历史教训留着：09-20 的"识别失败静默跳过"和 vc223 的"无轨迹转兜底"都是为了覆盖率，
     *   但覆盖率不能靠猜别家公司换——宁可漏，不可错。
     */
    private val PREFIX_CODES = mapOf(
        "SF" to "shunfeng", "JT" to "jtexpress", "JD" to "jd", "YT" to "yuantong",
        "YD" to "yunda", "ZT" to "zhongtong", "ST" to "shentong",
        "DB" to "debangkewei", "EMS" to "ems"
    )

    /** 前缀认公司（认不出返回 null，交给 autonumber） */
    private fun prefixCompany(mailNo: String): Company? {
        val up = mailNo.trim().uppercase()
        for ((p, code) in PREFIX_CODES) {
            if (up.startsWith(p)) {
                val name = when (code) {
                    "shunfeng" -> "顺丰速运"
                    "jtexpress" -> "极兔速递"
                    "jd" -> "京东物流"
                    "yuantong" -> "圆通速递"
                    "yunda" -> "韵达速递"
                    "zhongtong" -> "中通快递"
                    "shentong" -> "申通快递"
                    "debangkewei" -> "德邦快递"
                    "ems" -> "邮政EMS"
                    else -> code
                }
                return Company(code, name)
            }
        }
        return null
    }

    /** 轨迹查询可能失败的原因（用于向用户如实说明，而不是静默跳过） */
    data class TraceResult(val trace: Trace?, val comCode: String, val why: String)

    /**
     * 自动识别公司码 + 查**一次**（vc260 起不跨公司盲试，见 [PREFIX_CODES] 注释）。
     *
     * @return `trace != null` 表示查到了；否则 `why` 说明原因（识别失败/查无结果/需要手机号…）
     */
    fun queryTraceAuto(ctx: Context, phone: String, mailNo: String): TraceResult {
        // ① 前缀优先（号段是快递公司自己编的，最硬）；认不出前缀才问 autonumber
        val prefix = prefixCompany(mailNo)
        val detected = if (prefix != null) null else detectCompany(mailNo)
        val com = prefix?.comCode ?: detected?.comCode.orEmpty()
        val name = prefix?.name ?: detected?.name.orEmpty()
        if (com.isEmpty()) {
            Log.i(TAG, "$mailNo：前缀和 autonumber 都认不出公司，不盲试（vc260）")
            return TraceResult(
                null, "",
                "快递100 识别不出该单号的快递公司（SF/JT/YT 等字母前缀的单号识别最快）"
            )
        }
        // ② 只查这一家。没轨迹点就如实说查不到 —— 跨公司盲试只会试出撞号的别人的件
        //    （vc260：10-14 实证，圆通 YT0714785611281 盲试韵达时返回了梅州一件撞号包裹的轨迹）
        val t = queryTrace(ctx, phone, mailNo, com)
        if (t.ok && t.points.isNotEmpty()) return TraceResult(t, com, "")
        val why = when {
            t.ok -> "快递100 暂无该单号的轨迹（已按${name}查询；可能刚发出还没上网，或该渠道暂无此件数据）"
            t.why.isNotEmpty() -> t.why
            else -> "查询失败（$name）"
        }
        Log.i(TAG, "$mailNo → $com（$name）：$why，不跨公司盲试（vc260）")
        return TraceResult(null, com, why)
    }

    // ---------- ② 轨迹查询 ----------

    data class Trace(
        val ok: Boolean,
        val isReceived: Boolean,
        /** 预计送达，如 "9月21日"（服务端给的 expTime，格式化后） */
        val eta: String,
        /** 完整轨迹点，"time 内容" 的形式，时间序 */
        val points: List<String>,
        /** ok=false 时说明原因（供上层如实告知用户，而不是静默跳过） */
        val why: String = ""
    )

    /**
     * 按运单号查完整轨迹（阻塞式网络调用，后台线程调）。
     * @param phone 收件人手机号（快递100 要求，用于校验）
     */
    fun queryTrace(ctx: Context, phone: String, mailNo: String, comCode: String): Trace {
        // ★ 配了官方 key/customer ⇒ **官方接口优先**（照参考项目 KuaiDi100.fetchDetail 的分发逻辑）。
        //   理由：只有官方接口的 param 支持 `phone`，而韵达这类快递必须带手机尾号才给数据。
        if (hasOfficial(ctx)) {
            val off = officialQuery(ctx, phone, mailNo, comCode)
            if (off.points.isNotEmpty()) return off
            Log.i(TAG, "官方接口没拿到（${off.why}），退回免费通道")
        }
        // ★★ 2026-09-21：优先走**网页版**接口。
        //   原因：原来用的手机版 `mobileapi.do` 现在对**任何**请求都回
        //   `{"status":502,"message":"操作失败"}`（实测：真单号/假单号/带不带 phone 全是 502）
        //   —— 那套 `appid=com.Kingdee.Express` + `versionCode=693` 已被快递100 下线
        //   ⇒ **手动单号功能对所有单号都失效了**，不只是某一个单号。
        //   网页版 `www.kuaidi100.com/query` 实测可用，且**不需要签名**（盐/设备参数全省了），
        //   生命周期也比从 App 逆向出来的手机版接口长得多。
        // ★★ 两个通道**都要试**（2026-09-21 实测结论）：
        //   手机版 `mobileapi.do` 对韵达这类单号**稳定返回"查无结果"**（它有独立的数据源限制），
        //   而网页版 `www.kuaidi100.com/query` 同一个单号**能拿到轨迹**
        //   ⇒ 只试一个就会漏数据。谁有轨迹点用谁。
        val mob = queryTraceMobile(phone, mailNo, comCode)
        if (mob.points.isNotEmpty()) return mob
        val web = queryTraceWeb(mailNo, comCode, phone)
        if (web.points.isNotEmpty()) return web
        // 两边都没轨迹点：优先用"接口正常"的那个（它给出的原因更可信）
        if (mob.ok) return mob
        if (web.ok) return web
        Log.i(TAG, "两通道都不通：手机版(${mob.why}) / 网页版(${web.why})")
        return if (mob.why.isNotEmpty()) mob else web
    }

    /**
     * 网页版查询（无签名）：`GET www.kuaidi100.com/query?type=&postid=`
     *
     * 响应：`{"message":"ok","ischeck":"1","com":"yunda","status":"200",
     *        "condition":"F00","state":"3","data":[{"ftime","context"}...]}`
     * ★ `condition == "F00"` / data 里只有"查无结果" ⇒ 接口正常但**这个单号没有轨迹**
     *   （要跟"接口挂了"区分开，否则用户以为是我们坏了）。
     */
    private fun queryTraceWeb(mailNo: String, comCode: String, phone: String = ""): Trace {
        if (comCode.isEmpty()) return Trace(false, false, "", emptyList(), "没有公司码")
        return try {
            // ★ `phone` 是**校验字段**（实测：填错就返回"查无结果"，韵达这类必须给对尾号）
            val url = "https://www.kuaidi100.com/query?type=${URLEncoder.encode(comCode, "UTF-8")}" +
                    "&postid=${URLEncoder.encode(mailNo, "UTF-8")}&temp=${Math.random()}" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}"
            val body = httpGet(url, UA_WEB)
            val i = body.indexOf('{')
            if (i < 0) return Trace(false, false, "", emptyList(), "网页版返回非 JSON")
            val root = JSONObject(body.substring(i))
            val msg = root.optString("message")
            if (msg.isNotEmpty() && msg != "ok") {
                return Trace(false, false, "", emptyList(), "网页版: $msg")
            }
            val isReceived = root.optString("ischeck") == "1"
            val eta = formatEta(root.optString("expTime"))
            val points = ArrayList<String>()
            val arr = root.optJSONArray("data") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val t = o.optString("ftime").ifEmpty { o.optString("time") }
                val c = o.optString("context")
                if (c.isEmpty()) continue
                // ★ 快递100 会把"查无结果"当作一条**占位轨迹**塞进 data 数组
                //   （实测：真实响应 `data:[{"context":"查无结果"}]`）——
                //   不过滤的话会把它当成"有轨迹"，界面显示一条"查无结果"的假动态。
                if (c.contains("查无结果")) continue
                points.add("${t.ifEmpty { "?" }} $c")
            }
            // ★ 拿到合法响应就算 ok —— **哪怕 points 为空**，这样调用方能区分
            //   "接口正常但这个单号没轨迹" 与 "接口挂了"
            if (points.isEmpty()) Log.i(TAG, "网页版无轨迹（占位/空）原响应: " + body.take(300))
            Trace(true, isReceived || looksSigned(points), eta, points, "")
        } catch (e: Throwable) {
            Log.w(TAG, "queryTraceWeb: ${e.message}")
            Trace(false, false, "", emptyList(), "网页版异常: ${e.message}")
        }
    }

    /**
     * ★ vc345：快递100 的 `ischeck` **会漏报**（轨迹里已经"已签收"，字段仍是 0）。
     * 同志 2026-10-20 报「手动加的单号状态应该是已签收」——就是被这个字段骗了。
     * 用**最后一条轨迹文案**兜底判定，与 `ischeck=1` 取或。
     */
    private fun looksSigned(points: List<String>): Boolean {
        // 签收是不可逆的终态：**任意一条**轨迹命中就算签收（不只看首尾 ——
        // 有的通道最后一条是"签收后如有疑问请联系…"这类尾巴）
        val words = listOf(
            "已签收", "签收人", "已妥投", "妥投", "已投递", "投递成功", "已领取", "已代收"
        )
        return points.any { line -> words.any { line.contains(it) } }
    }

    /**
     * 快递100 **官方接口**（`poll.kuaidi100.com/poll/query.do`）—— 照参考项目
     * `ExpressAssistant/api/KuaiDi100.kt` 的 `officialQuery` 实现。
     *
     * 与免费手机版最大的差别：**param 里可以带 `phone`**（收件人手机号 / 后 4 位），
     * 这正是韵达等"查前要手机尾号"的快递所必需的；免费通道给不了这个能力。
     * 签名：`sign = MD5(param + key + customer)`（大写）。
     */
    private fun officialQuery(ctx: Context, phone: String, mailNo: String, comCode: String): Trace {
        val key = kdKey(ctx)
        val customer = kdCustomer(ctx)
        if (key.isEmpty() || customer.isEmpty()) return Trace(false, false, "", emptyList(), "没配官方凭据")

        // ★★ vc230 重试外壳。为什么必须重试：快递100 第一次查某个单号时常常自己也没有数据，
        //   要立刻去该快递公司取一趟，这次就回「查询无结果，请隔段时间再查」——
        //   等它取回来（真机实测约 5~10 分钟）再查，轨迹就完整了。
        //   ⇒ 同志看到的现象就是：额度扣了一次(100→99)，页面上却还是只有 1 条。
        //   与其让用户手动再点一遍，不如我们替他等一会儿再问一次。
        //   ⚠️ **只在这一种语义下重试**：别的错误（签名错/公司码不对/单号不存在）
        //   重试也还是同一个结果，白白消耗有限的额度。
        var last = officialQueryOnce(key, customer, phone, mailNo, comCode)
        if (last.points.isNotEmpty()) return last
        if (last.why.contains("查询无结果") || last.why.contains("隔段时间")) {
            for (attempt in 1..2) {
                try { Thread.sleep(if (attempt == 1) 2_500L else 4_000L) } catch (t: Throwable) { SafeRun.w("Kd100Channel", t) }
                val again = officialQueryOnce(key, customer, phone, mailNo, comCode)
                Log.i(TAG, "第 $attempt 次重试：$mailNo → ${again.points.size} 条轨迹")
                if (again.points.isNotEmpty()) return again
                last = again
            }
        }
        return last
    }

    /** 官方接口**单次**请求（不含重试）。签名方式：MD5(param + key + customer) 大写。 */
    private fun officialQueryOnce(
        key: String, customer: String, phone: String, mailNo: String, comCode: String
    ): Trace {
        return try {
            val param = "{\"com\":\"$comCode\",\"num\":\"$mailNo\"" +
                    (if (phone.isNotEmpty()) ",\"phone\":\"$phone\"" else "") + "}"
            val sign = md5("$param$key$customer").uppercase()
            val form = "param=${URLEncoder.encode(param, "UTF-8")}" +
                    "&customer=${URLEncoder.encode(customer, "UTF-8")}&sign=$sign"
            val body = httpPost("https://poll.kuaidi100.com/poll/query.do", form)
            // ★ vc230：原来只有失败才打日志，且只有一句 message —— 分不清"包裹太新"和
            //   "我们接错了"。把原始响应整段打出来（截断防刷屏），日常也能据此定位。
            Log.i(TAG, "officialRaw: ${body.take(900)}")
            val i = body.indexOf('{')
            if (i < 0) return Trace(false, false, "", emptyList(), "官方返回非 JSON")
            val root = JSONObject(body.substring(i))
            val msg = root.optString("message")
            if (msg.isNotEmpty() && msg != "ok") {
                Log.w(TAG, "officialQuery: $msg")
                return Trace(false, false, "", emptyList(), "官方接口: $msg")
            }
            val isReceived = root.optString("ischeck") == "1"
            val eta = formatEta(root.optString("expTime"))
            val points = ArrayList<String>()
            val arr = root.optJSONArray("data") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val t = o.optString("ftime").ifEmpty { o.optString("time") }
                val c = o.optString("context")
                if (c.isEmpty() || c.contains("查无结果")) continue
                points.add("${t.ifEmpty { "?" }} $c")
            }
            Trace(true, isReceived || looksSigned(points), eta, points, "")
        } catch (e: Throwable) {
            Log.w(TAG, "officialQuery: ${e.message}")
            Trace(false, false, "", emptyList(), "官方异常: ${e.message}")
        }
    }

    private fun queryTraceMobile(phone: String, mailNo: String, comCode: String): Trace {
        val params = linkedMapOf<String, String?>(
            "type" to "detail",
            // ★ 旧值 com.Kingdee.Express / 693 已下线（全 502），换成实测能通的组合
            "appid" to "com.kuaidi100.app",
            "versionCode" to "999",
            "os_version" to "android5.1.1",
            "os_name" to "PCT-AL10",
            "t" to System.currentTimeMillis().toString(),
            "tra" to UUID.randomUUID().toString(),
            "uchannel" to "null",
            "nt" to "wifi",
            "deviceId" to UUID.randomUUID().hashCode().toString(),
            "apiversion" to "18",
            "phone" to phone,
            "num" to mailNo,
            "com" to comCode
        )
        val json = params.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            val value = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            "\"$k\":$value"
        }
        val hash = md5("$MOBILE_SECRET$json").uppercase()

        val form = "method=query&json=${URLEncoder.encode(json, "UTF-8")}&token=&hash=$hash&userid=0"
        val body = httpPost("https://p.kuaidi100.com/mobile/mobileapi.do?method=query", form)

        return try {
            val root = JSONObject(body.substring(body.indexOf('{')))
            val last = root.optJSONObject("lastResult") ?: root
            val isReceived = root.optString("ischeck") == "1" || last.optString("ischeck") == "1"
            val expTime = root.optString("expTime").ifEmpty { last.optString("expTime") }
            val eta = formatEta(expTime)
            val points = ArrayList<String>()
            val arr = last.optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val time = o.optString("ftime").ifEmpty { o.optString("time") }
                val ctx = o.optString("context")
                if (ctx.isEmpty()) continue
                // ★ 2026-10-12：和网页版对齐 —— 快递100 会把「查无结果」当成一条**占位轨迹**
                //   塞进 data（实测 data:[{"time":"2026-10-07 03:11:22","context":"查无结果"}]）。
                //   网页版早就过滤了，手机版漏了 ⇒ 会把这条假动态当成真轨迹显示到第 5 栏。
                if (ctx.contains("查无结果")) continue
                points.add("${time.ifEmpty { "?" }} $ctx")
            }
            // ★ 拿到响应却 0 条轨迹 ⇒ 把原始响应摘要落日志，方便一眼看出是不是接口降级了
            if (points.isEmpty()) Log.i(TAG, "手机版无轨迹（占位/空）原响应: " + body.take(300))
            Trace(true, isReceived || looksSigned(points), eta, points, "")
        } catch (e: Throwable) {
            Log.w(TAG, "queryTraceMobile: ${e.message}")
            Trace(false, false, "", emptyList(), "手机版异常: ${e.message}")
        }
    }

    /** "2026-09-21 18:00:00" → "9月21日"（与第 5 栏 ETA 展示风格一致） */
    private fun formatEta(expTime: String): String {
        if (expTime.isBlank()) return ""
        return try {
            val d = expTime.trim().split(" ")[0].split("-")
            if (d.size == 3) "${d[1].toInt()}月${d[2].toInt()}日" else ""
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * ★ vc230 诊断入口：拿**任意单号**跑一次官方请求，把请求与原始响应整段打到日志。
     *
     * 为什么必须有它：interfaces 只在意业务结果，失败时我们只能看到一句
     * 「查询无结果，请隔段时间再查」。这句话既可以表示"这单在快递100 侧真没数据"
     * （包裹太新，属于数据源天花板），也可以表示"公司码/参数/签名不对"。
     * 看不到原始响应就只能瞎猜。留个开关，一条命令就能把真相挑出来：
     *   adb shell am start -n com.agent.idcode/.MainActivity \
     *     --es debug_kd_num YT0714785611281 [--es debug_kd_com yuantong] [--es debug_kd_phone 13800138000]
     * 然后：adb logcat -d --pid=$(pidof com.agent.idcode) | grep Kd100Channel
     */
    /**
     * ★ 2026-10-12 诊断：同一个单号把**三条路**各查一遍，用来回答
     * "免费通道和官方通道到底差在哪"（不靠宣传，靠真机数据）。
     * adb: `--es debug_kd_free <单号> [--es debug_kd_com <公司码>] [--es debug_kd_phone <手机号>]`
     */
    fun selfTestFree(ctx: Context, num: String, com: String, phone: String) {
        Thread {
            try {
                val useCom = com.ifEmpty { detectCompany(num)?.comCode.orEmpty() }
                Log.i(TAG, "==== 三通道对比 num=$num com=${useCom.ifEmpty { "(识别失败)" }} " +
                    "hasOfficial=${hasOfficial(ctx)} ====")
                if (hasOfficial(ctx)) {
                    val off = officialQuery(ctx, phone, num, useCom)
                    Log.i(TAG, "【官方】ok=${off.ok} 条数=${off.points.size} why=${off.why}")
                    off.points.take(15).forEach { Log.i(TAG, "  官方| $it") }
                } else {
                    Log.i(TAG, "【官方】未配置凭据，跳过")
                }
                val mob = queryTraceMobile(phone, num, useCom)
                Log.i(TAG, "【免费·手机版】ok=${mob.ok} 条数=${mob.points.size} why=${mob.why}")
                mob.points.take(15).forEach { Log.i(TAG, "  手机版| $it") }
                val web = queryTraceWeb(num, useCom, phone)
                Log.i(TAG, "【免费·网页版】ok=${web.ok} 条数=${web.points.size} why=${web.why}")
                web.points.take(15).forEach { Log.i(TAG, "  网页版| $it") }
            } catch (e: Throwable) {
                Log.w(TAG, "三通道对比异常: ${e.message}")
            }
        }.start()
    }

    fun selfTest(ctx: Context, num: String, com: String, phone: String) {
        Thread {
            val key = kdKey(ctx)
            val customer = kdCustomer(ctx)
            Log.i(TAG, "==== 自检开始 num=$num com=$com phone=${phone.take(3)}**** ====")
            Log.i(TAG, "凭据: keyLen=${key.length} customerLen=${customer.length} hasOfficial=${hasOfficial(ctx)}")
            if (key.isEmpty() || customer.isEmpty()) {
                Log.w(TAG, "没填凭据，自检中止")
                return@Thread
            }
            try {
                val useCom = com.ifEmpty { detectCompany(num)?.comCode.orEmpty() }
                Log.i(TAG, "识别公司: ${useCom.ifEmpty { "(识别失败)" }}")
                if (useCom.isEmpty()) {
                    Log.w(TAG, "公司码为空 ⇒ 快递100 会当成「查不到」。这时不是接口故障，"
                            + "而是没能识别出快递公司，可 --es debug_kd_com 手动指定再试")
                }
                val param = "{\"com\":\"$useCom\",\"num\":\"$num\"" +
                        (if (phone.isNotEmpty()) ",\"phone\":\"$phone\"" else "") + "}"
                val sign = md5("$param$key$customer").uppercase()
                val form = "param=${URLEncoder.encode(param, "UTF-8")}" +
                        "&customer=${URLEncoder.encode(customer, "UTF-8")}&sign=$sign"
                Log.i(TAG, "请求 param: $param")
                Log.i(TAG, "请求 customer: $customer")
                Log.i(TAG, "请求 sign: $sign")
                val body = httpPost("https://poll.kuaidi100.com/poll/query.do", form)
                Log.i(TAG, "原始响应(${body.length}字): ${body.take(1500)}")
                Log.i(TAG, "==== 自检结束 ====")
            } catch (e: Throwable) {
                Log.w(TAG, "自检异常: ${e.message}")
            }
        }.start()
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---------- HTTP ----------

    /** 网页版接口要浏览器 UA（用 App 的 Dalvik UA 会被当成爬虫） */
    private const val UA_WEB =
        "Mozilla/5.0 (Linux; Android 16; 25102RKBEC) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun httpGet(url: String, ua: String = UA): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", ua)
        }
        val input: InputStream? = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return text
    }

    private fun httpPost(url: String, form: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val input: InputStream? = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        val text = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return text
    }
}
