package com.agent.idcode

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 拼多多取件码 —— 纯原生通道，不依赖任何网页。
 *
 * 依据（2026-09-17 真机 + PC 双向实测，详见 pdd_rev 逆向报告）：
 *  ① PDD H5 的业务接口服务端【不校验 anti-content / anti-token】。实测：给了登录态之后，
 *     请求头里塞垃圾 anti-content、或 body 里塞垃圾 anti_content，返回码完全不变。
 *     ⇒ 签名不是门槛。
 *  ② 纯浏览器上下文（非 PDD 容器）的接口基址是
 *     https://mobile.yangkeduo.com/proxy/api/api/   —— 注意 "api" 出现两次。
 *  ③ 订单列表：POST aristotle/order_list_v4
 *       · front_env 是【数字枚举】不是字符串！H5_MOBILE_BROWSER = 163。
 *         实测发字符串 "H5_MOBILE_BROWSER" → HTTP 400 + error_code 3000000；
 *         发 163（或干脆不发）→ HTTP 200 正常返回 50 条订单。这是之前"接口报错"的真凶。
 *       · type 取 tab 语义：all / unpaidV2 / grouping / unshipping / unreceived / unrated
 *         （H5 里 PATH_TYPE:{0:"all",1:"unpaidV2",5:"grouping",2:"unshipping",3:"unreceived",4:"unrated"}）
 *       · 翻页靠 body.offset，取上一页最后一条的 offset 字段。
 *       · 商品字段是 order_goods（不是 goods_list）。
 *  ④ 取件码：  GET  express/cabinet/{order_sn}  →  result.* 里的 code 字段
 *       （H5 侧 expressHoneyInfo / getCabinetData 就是这个）
 *
 * 登录态：cookie jar 是权威来源（pdd_user_id / PDDAccessToken / pdd_user_uin 都是 cookie），
 * 实测只带 Cookie、不带 AccessToken 头也能正常拉单，所以 Cookie 足矣。
 *
 * 零外部依赖（与工程既有风格一致）：只用 JDK + org.json。
 */
object PddNative {

    const val H5 = "https://mobile.yangkeduo.com/proxy/api/api/"
    const val ORIGIN = "https://mobile.yangkeduo.com"

    /**
     * ★ 与登录页/旁听页**同一个设备身份**（见 [PddChannel.BROWSER_UA]）。
     * 以前这里是另一台机型（Android 14; PKX110）—— 服务端会当成"另一台设备"在访问，
     * 重新绑定会话 ⇒ 反复顶掉拼多多 App。
     * 只要是"移动浏览器"UA，[FRONT_ENV_H5_MOBILE_BROWSER] 那条分支就不受影响。
     */
    const val UA = PddChannel.BROWSER_UA

    /** PDD 错误码：登录已过期（见 H5 bundle 内置错误码字典） */
    const val ERR_NEED_LOGIN = 40001

    /**
     * front_env 数字枚举（H5 的 lF 表）。
     * 注意：这是数字！字符串形式会被服务端判为非法参数（3000000）。
     */
    const val FRONT_ENV_H5_MOBILE_BROWSER = 163

    /** 订单 tab → 接口 type（H5 的 PATH_TYPE 表） */
    const val TAB_ALL = "all"
    const val TAB_UNRECEIVED = "unreceived"

    /**
     * 登录态。cookie 是关键 —— WebView 的 HttpOnly cookie 读不到，只能由
     * CookieManager 原生取出后整条带上。
     */
    data class Session(
        val token: String,
        val uid: String,
        val cookie: String
    ) {
        val usable: Boolean get() = token.isNotEmpty() || cookie.isNotEmpty()
    }

    data class Pickup(
        val orderSn: String,
        val code: String,
        val tip: String,
        val place: String,
        val goods: String,
        val status: String,
        /** ★ vc659：真·运单号（订单列表里的 tracking_number）。菜鸟单的"后五位"就是它的尾号，
         *  也是回淘宝校园驿站反查真实格口码的唯一钥匙 ⇒ 必须带出来。 */
        val waybill: String = ""
    )

    data class Report(
        val needLogin: Boolean,
        val message: String,
        val items: List<Pickup>,
        val scanned: Int,
        val totalOrders: Int,
        /** 命中"可能待取件"判据的订单数（含还在路上、尚未入站的） */
        val waitingCandidates: Int = 0,
        /** 列表接口结构有效；空列表也可能是成功结果 */
        val ok: Boolean = true,
        /** 至少一页有效，但后续页/详情请求有失败 */
        val partial: Boolean = false,
        /** Candidate order numbers that passed pickup filtering, even when cabinet returned no code. */
        val candidateOrderSns: List<String> = emptyList(),
        /** Direct logistics URLs from the same order-list response, keyed by order number. */
        val candidateExpressUrls: Map<String, String> = emptyMap()
    )

    /** ★ vc212：第 5 栏「在途快递」的原生在途单（与 PddChannel.parseInTransit 的产出语义同构） */
    data class TransitItem(
        val orderSn: String,
        val goods: String,
        val status: String,
        val latest: String,
        val phone: String,
        /** ★ vc252：真·运单号（订单列表接口直接给的；空 = 这单还没发货/接口没给） */
        val waybill: String = "",
        /** ★ vc252：「查看物流」直链（相对 URL，形如 goods_express.html?tracking_number=…&order_sn=…） */
        val expressUrl: String = "",
        /**
         * ★ vc696：订单列表自带的「预计X送达」提示（`extra_info.order_hint.message` 里的 ETA 段）。
         * 实测 2026-11-09：11 个在途单**全部**带这条（形如「运输中 预计最晚后天送达 | 中通快递：…」），
         * 而物流轨迹文本只有部分承运商会写「预计【X月X日】到达」⇒ 只从轨迹抠 ETA 必然缺一大半。
         */
        val etaHint: String = ""
    )

    /**
     * ★ vc212：[inTransitScan] 的返回。
     * `ok=true` 表示列表接口完整成功（即使过滤后为空），partial=true 表示有有效页但未完整。
     */
    data class TransitReport(
        val needLogin: Boolean,
        val ok: Boolean,
        val message: String,
        val items: List<TransitItem>,
        /** ★ vc349：歧义单 —— 订单状态"已签收"但物流文案为空，无法判断真假签收；
         *  由调用方用 cabinet 接口裁决（有码→取件码栏；无码→不显示） */
        val ambiguous: List<TransitItem> = emptyList(),
        val partial: Boolean = false
    )

    /**
     * ★★ vc657：**`express/cabinet/{order_sn}` 的 `result.code` 真的是取件码**。
     *
     * 事故（2026-11-01 21:04 真机日志原文）：
     *   `"companyName":"抚州幼儿师专学校快递驿站","code":"27-5-5009"`
     * 而 [walk] 的 CODE_KEYS 里**故意没有 `code`**（怕把状态枚举当码）⇒ 这一单被判"无码"，
     * 只能靠 WebView 去物流页抠文本，9 单里只成功 4 单，用户看到"拼多多有 8 个码，App 只有 4 个"。
     *
     * ⇒ 不再把 `code` 加进全局 CODE_KEYS（那会重犯状态码的错），而是在**这个接口自己的
     *   `result` 层**单独读它，并且必须过形态校验（`27-5-5009` 这种分段码 / 字母数字混排）。
     */
    private fun looksLikePickupCode(s: String): Boolean {
        if (s.length !in 3..24) return false
        if (Regex("\\d{1,3}-\\d{1,4}-\\d{2,6}").matches(s)) return true
        return s.length >= 4 && s.any { it.isLetter() } && s.any { it.isDigit() }
    }

    /**
     * 在 cabinet 响应的 `result` 层补两样 [walk] 拿不到的东西：
     *  ① `code` —— 真正的货架取件码（`27-5-5009`）；
     *  ② 菜鸟驿站的 `cnInOutBound.display` —— 「取件出示快递单号后五位 58867」，
     *     那就是柜台要的凭证（这类单**没有传统取件码**，抓一百遍物流页也抓不到）。
     *
     * 只在含「单号后/后五位」字样时才取尾部数字，避免把电话号码当成码。
     */
    private fun fillFromCabinetResult(js: JSONObject, f: Found, sn: String) {
        val r0 = js.optJSONObject("result") ?: return
        if (f.code == null) {
            val raw = r0.optString("code").trim()
            if (looksLikePickupCode(raw)) {
                f.code = raw
            } else {
                val disp = r0.optJSONObject("cnInOutBound")?.optString("display").orEmpty()
                if (disp.contains("单号后") || disp.contains("后五位")) {
                    Regex("(\\d{4,6})\\s*$").find(disp)?.groupValues?.get(1)?.let {
                        f.code = it
                        if (f.tip.isNullOrBlank()) f.tip = disp
                        Log.i("PddNative", "菜鸟单凭单号后五位取件 order=$sn code=$it")
                    }
                }
            }
        }
        // 驿站名比"负一楼"这种地址更能让用户认出是哪个点
        r0.optString("companyName").trim().takeIf { it.isNotEmpty() }?.let { f.place = it }
    }

    /**
     * ★ vc659：从订单自带的物流文案里抽**具体站点名**（不打开详情页）。
     *
     * 实测文案（2026-11-01 真机）：
     *  · `快件已由抚州幼儿师范高等专科学校店菜鸟驿站代收，请及时取件…`
     *  · `快件已送达【抚州幼儿师范高等专科学校店】，取件地址：王安石大道…`
     *  · `您好，快件已…（抚州幼儿师范高等专科学校店)，请及时取件…`
     * cabinet 接口只给 `companyName=菜鸟驿站`，卡片上就只有"菜鸟驿站"这种泛称，
     * 用户看不出是哪个点 ⇒ 从这三处捞一个更具体的。
     */
    private fun stationFromMsg(msg: String): String {
        if (msg.isBlank()) return ""
        // ★ vc661：物流文案是**多行**的（"快件已由抚州\n范高等专科学校店菜鸟驿站代收"），
        //   换行会把"已由…代收"截断 ⇒ 先压成一行再匹配，否则永远提不到。
        val flat = msg.replace("\n", "").replace("\r", "").replace("\u3000", " ")
        val pats = listOf(
            Regex("已由([^，。；、]{2,40})代收"),
            Regex("[【\\[]([^】\\]]{2,40})[】\\]]"),
            Regex("[（(]([^）)]{2,40})[)）]"),
            Regex("取件地址[:：]\\s*([^，。；、]{2,60})")
        )
        for (p in pats) {
            val raw = p.find(flat)?.groupValues?.get(1)?.trim().orEmpty()
            // 文案里常混着电话/门牌（"XX店，电话：177…"）⇒ 只取第一个标点前那一段
            val v = raw.split('，', ',', '。', '；').firstOrNull()?.trim().orEmpty()
            if (v.length >= 2) return v.take(40)
        }
        return ""
    }

    /** 取件码字段候选（不同版本字段名有变，宁多勿漏） */
    // ★★ 刻意【不含】"code"（2026-09-20，与 PddChannel 同一教训）：
    //   拼多多响应里 `code` 是状态码/枚举（0、3、200001…），当取件码键会把一片对象认成包裹、
    //   取件码显示成孤零零的 "3"。并配 looksLikePickupCode() 形态校验兜底。
    private val CODE_KEYS = setOf(
        "pickup_code", "pickupCode", "pick_up_code",
        "take_code", "takeCode", "extract_code", "extractCode"
    )
    private val TIP_KEYS = setOf(
        "pickupCodeTip", "pickup_code_tip", "code_tip", "pickupTip", "express_tip"
    )
    private val PLACE_KEYS = setOf(
        "cabinetName", "cabinet_name", "stationName", "station_name",
        "expressName", "companyName", "deliveryName", "address"
    )
    /** 订单状态文案里表示"可能已入柜/入站"的关键字 */
    private val WAIT_HINTS = arrayOf(
        "待取件", "待收货", "待收件", "已到达", "派送", "派件", "待自提",
        "待领取", "已入柜", "待取", "运输中", "已发货", "待确认"
    )
    /** 退款 / 取消类的 status 值，出现这些就别去查取件码了 */
    private val DEAD_STATUS = intArrayOf(5, 6, 7, 8, 9, 10, 11, 12)

    // ---------------------------------------------------------------- HTTP

    private fun readAll(ins: InputStream, gzip: Boolean): String {
        val s = if (gzip) GZIPInputStream(ins) else ins
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { r ->
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    private fun request(
        url: String, method: String, ses: Session, body: String?
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("Origin", ORIGIN)
            conn.setRequestProperty("Referer", "$ORIGIN/orders.html")
            if (ses.token.isNotEmpty()) conn.setRequestProperty("AccessToken", ses.token)
            val ck = buildCookie(ses)
            if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val gz = (conn.contentEncoding ?: "").contains("gzip", ignoreCase = true)
            val text = if (stream != null) readAll(stream, gz) else ""
            code to text
        } catch (t: Throwable) {
            -1 to ""
        } finally {
            try { conn?.disconnect() } catch (t: Throwable) { SafeRun.w("PddNative", t) }
        }
    }

    /** 把页面给的 cookie 串补上 pdd_user_id（若缺），保证身份字段齐全 */
    private fun buildCookie(ses: Session): String {
        val base = ses.cookie.trim()
        if (ses.uid.isEmpty()) return base
        if (base.contains("pdd_user_id=")) return base
        return if (base.isEmpty()) "pdd_user_id=${ses.uid}" else "$base; pdd_user_id=${ses.uid}"
    }

    /** 身份查询后缀，H5 的 extendQuery 会给每个请求补上 pdduid / is_back */
    private fun qs(ses: Session): String =
        "?pdduid=" + ses.uid.ifEmpty { "0" } + "&is_back=" + "&ts=" + System.currentTimeMillis()

    // ---------------------------------------------------------------- 接口

    private fun orderBody(type: String, offset: String?, size: Int = 50): JSONObject {
        val body = JSONObject()
            .put("type", type)
            .put("key_word", "")
            .put("size", size)
            .put("page", 1)
            .put("pay_channel_list", JSONArray())
            .put("userAgent", UA)
            .put("origin_host_name", "mobile.yangkeduo.com")
            .put("scene", "order_list_h5")
            // ★ 必须是数字 163；字符串会让服务端返回 3000000
            .put("front_env", FRONT_ENV_H5_MOBILE_BROWSER)
        if (!offset.isNullOrEmpty()) body.put("offset", offset)
        return body
    }

    /** POST aristotle/order_list_v4 —— 订单列表（body 与 H5 完全一致） */
    private fun orderPage(
        ses: Session, type: String, offset: String?, size: Int = 50
    ): Pair<Int, JSONObject?> {
        val (code, text) = request(
            H5 + "aristotle/order_list_v4" + qs(ses), "POST", ses,
            orderBody(type, offset, size).toString()
        )
        val js = try { JSONObject(text) } catch (t: Throwable) { null }
        return code to js
    }

    /** GET express/cabinet/{order_sn} —— 取件码。Pair.first=false 表示请求/解析失败。 */
    private fun cabinet(ses: Session, orderSn: String): Pair<Boolean, JSONObject?> {
        val u = H5 + "express/cabinet/" + orderSn + qs(ses)
        val (httpCode, text) = request(u, "GET", ses, null)
        if (httpCode !in 200..299 || text.isBlank()) return false to null
        val js = try { JSONObject(text) } catch (t: Throwable) { null }
        if (js == null) return false to null
        // 留给调用方识别 40001 登录失效；其它业务错误作为该单的详情失败重试。
        if (js.optInt("error_code", 0) != 0 && js.optInt("error_code", 0) != ERR_NEED_LOGIN) {
            return false to js
        }
        return true to js
    }

    /**
     * ★ vc304：按订单号直查取件码（公共入口，直连查码链第②环）。
     * 传入「我的快递」列表接口给的 order_sn（拼多多自家单是数字号；跨平台聚合件
     * 如淘宝/极兔单是 32 位映射号 —— 是否被 cabinet 接口接受由真机侦察定）。
     * 返回 "" = 无码/未登录/失败；回调语义纯函数、无副作用。
     */
    fun cabinetCode(ctx: android.content.Context, orderSn: String): String =
        cabinetInfo(ctx, orderSn).first

    /**
     * ★ vc340：柜码 **和柜名/取件地址** 一起拿。
     *
     * ## 为什么要有这个（PME110 用户实证）
     * express/cabinet/{order_sn} 的响应里本来就有 cabinetName/stationName 一类地址字段
     * （PLACE_KEYS 一直都在收），但旧 cabinetCode 只把 code 返回、place 直接扔掉 ⇒
     * 快递柜单（丰巢/速递易）永远只有码没有取件地址，拼多多 App 里却看得到。
     *
     * @return Pair(取件码, 柜名/驿站名)；任一没拿到就是空串。回调语义纯函数、无副作用。
     */
    fun cabinetInfo(ctx: android.content.Context, orderSn: String): Pair<String, String> {
        if (orderSn.isEmpty()) return "" to ""
        return try {
            val ck = PddChannel.cookies(ctx)
            if (ck.isEmpty()) return "" to ""
            val uid = PddChannel.uid(ctx).ifEmpty { PddChannel.uidFromCookie(ck) }
            val token = Regex("PDDAccessToken=([^;]+)")
                .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
            val ses = Session(token = token, uid = uid, cookie = ck)
            if (!ses.usable) return "" to ""
            val (ok, js) = cabinet(ses, orderSn)
            if (!ok || js == null) return "" to ""
            val f = Found()
            walk(js, 0, f)
            fillFromCabinetResult(js, f, orderSn)   // ★ vc657：result.code / 菜鸟后五位
            Log.i("PddNative", "cabinet($orderSn) → code=${f.code ?: "无"} tip=${f.tip ?: "无"} place=${f.place ?: "无"}")
            f.code.orEmpty() to f.place.orEmpty()
        } catch (_: Throwable) {
            "" to ""
        }
    }

    /**
     * ★ vc351（真机验证用）：拼多多**自己的物流状态枚举**。
     *
     * `POST /proxy/api/api/express/track/status`
     * 请求 `{"order_sn_list":[{"order_sn":"..."}]}`
     * 响应 `{"result_list":[{"order_sn":"...","status":"IN_CABINET"}]}`
     *
     * 这是拼多多对每一单物流的**内部判定**，与页面文案无关 ——
     * 用来替代"看状态字/轨迹字猜"的扣字逻辑（同志 2026-10-23 提的方案）。
     * 本函数只做收数，怎么分栏由调用方决定。
     *
     * ------------------------------------------------------------------
     * ★ vc355：状态码的公开依据（不要再自己编语义）
     *
     * 拼多多**没有**公开消费者端的枚举文档（开放平台只面向商家：商品/订单/发货）。
     * 但这套码不是拼多多私有的，它是**快递行业标准状态码**，韵达开放平台有公开对照：
     *
     *   ACCEPT     收件扫描
     *   GOT        揽件扫描（拼多多实测语义 = 已取走）
     *   ARRIVAL    入中转
     *   DEPARTURE  出中转
     *   SENT       派件中
     *   INBOUND    第三方代收入库   ≈ 拼多多 IN_CABINET（已入柜/驿站）
     *   OUTBOUND   第三方代收取出   ≈ 拼多多 OUT_CABINET（已从柜/驿站取出）
     *   SIGNED     已签收
     *   SIGNFAIL   签收失败
     *   RETURN     退回件
     *   ISSUE      问题件
     *   REJECTION  拒收
     *
     * ⚠️ 拼多多对个别值的用法与行业表**不一致**（实测：SIGN 出现在"待发货/已取消"单上），
     *    所以只采用已实测证实的映射：
     *      OUT_CABINET / GOT → 已取走（两栏都不显示）
     *      DEPARTURE         → 在途（实测 2/2）
     *      SIGN / 未知值 / 3001 → 走第二层文案规则，不硬判
     *    ARRIVAL / IN_CABINET 目前无真实样本，仍走文案兜底；等拿到样本再放开。
     */
    data class TrackStatusResult(
        val ok: Boolean,
        val statuses: Map<String, String>,
        val missing: Set<String>,
        val error: String? = null
    )

    fun trackStatusesResult(ses: Session, orderSns: List<String>): TrackStatusResult {
        if (!ses.usable) return TrackStatusResult(false, emptyMap(), orderSns.toSet(), "session unavailable")
        // ★ vc700：状态枚举上限 10 → 30 —— 在途件超过 10 个时，
        // 排在前 10 之后的单拿不到物流状态，会出现「11 件只显示 8 件」的分栏失败
        val sns = orderSns.filter { it.isNotBlank() }.distinct().take(30)
        if (sns.isEmpty()) return TrackStatusResult(true, emptyMap(), emptySet())
        return try {
            val arr = JSONArray()
            for (sn in sns) arr.put(JSONObject().put("order_sn", sn))
            val body = JSONObject().put("order_sn_list", arr).toString()
            val (code, text) = request(H5 + "express/track/status" + qs(ses), "POST", ses, body)
            Log.i("PddNative", "track/status HTTP $code len=${text.length} body=${text.take(300)}")
            if (code !in 200..299 || text.isBlank()) {
                Log.w("PddNative", "track/status 请求失败 HTTP $code（${sns.size} 单）")
                return TrackStatusResult(false, emptyMap(), sns.toSet(), "HTTP $code")
            }
            val root = JSONObject(text)
            val list = root.optJSONArray("result_list")
                ?: return TrackStatusResult(false, emptyMap(), sns.toSet(), "缺少 result_list")
            val out = LinkedHashMap<String, String>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val sn = o.optString("order_sn")
                val st = o.optString("status")
                if (sn in sns && st.isNotBlank()) out[sn] = st
            }
            val missing = sns.filter { it !in out }.toSet()
            if (missing.isNotEmpty()) {
                Log.w("PddNative", "track/status 成功但未返回 ${missing.size}/${sns.size} 单：$missing")
            }
            TrackStatusResult(true, out, missing)
        } catch (t: Throwable) {
            Log.w("PddNative", "track/status 异常：${t.message}")
            TrackStatusResult(false, emptyMap(), sns.toSet(), t.message ?: "parse error")
        }
    }

    /** 兼容旧调用方；需要可靠性信息时使用 trackStatusesResult。 */
    fun trackStatuses(ses: Session, orderSns: List<String>): Map<String, String> =
        trackStatusesResult(ses, orderSns).statuses

    /** ★ vc352：枚举缓存（10 分钟）—— 页面刷新很频繁，同一单不反复问 */
    private val enumCache = HashMap<String, Pair<String, Long>>()

    private fun cachedEnum(sn: String): String? = synchronized(enumCache) {
        val e = enumCache[sn] ?: return null
        if (System.currentTimeMillis() - e.second > 10 * 60_000L) {
            enumCache.remove(sn)
            null
        } else e.first
    }

    private fun putEnum(sn: String, v: String) = synchronized(enumCache) {
        enumCache[sn] = v to System.currentTimeMillis()
    }

    /**
     * ★ vc352：第一层判定 —— 拿拼多多自己的枚举（带缓存 + 批量失败拆单）。
     * 只返回**拿到了枚举**的单；没拿到/查不到的由调用方走第二层（文案规则）。
     */
    fun trackStatusesForCtx(ctx: android.content.Context, sns: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val need = ArrayList<String>()
        for (sn in sns.distinct()) {
            if (sn.isBlank()) continue
            val c = cachedEnum(sn)
            if (c == null) need.add(sn) else if (c.isNotEmpty()) out[sn] = c
        }
        if (need.isEmpty()) return out
        return try {
            val ck = PddChannel.cookies(ctx)
            if (ck.isEmpty()) return out
            val uid = PddChannel.uid(ctx).ifEmpty { PddChannel.uidFromCookie(ck) }
            val token = Regex("PDDAccessToken=([^;]+)")
                .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
            val ses = Session(token = token, uid = uid, cookie = ck)
            if (!ses.usable) return out
            val fresh = trackStatusesWithFallbackResult(ses, need.take(25))
            for ((sn, v) in fresh.statuses) {
                putEnum(sn, v)
                out[sn] = v
            }
            if (!fresh.ok) {
                Log.w("PddNative", "枚举本轮失败，不缓存空状态：${fresh.error}")
            } else if (fresh.missing.isNotEmpty()) {
                Log.i("PddNative", "枚举未返回 ${fresh.missing.size} 单，本轮保留未知并允许下轮重查")
            }
            out
        } catch (t: Throwable) {
            Log.w("PddNative", "枚举上下文失败：${t.message}")
            out
        }
    }

    /**
     * 批量查；批量失败或只返回部分订单时，对所有未返回订单逐单补查。
     * 只有明确成功拿到状态才写入十分钟缓存，失败/缺失不会伪装成空状态。
     */
    private fun trackStatusesWithFallbackResult(ses: Session, sns: List<String>): TrackStatusResult {
        if (sns.isEmpty()) return TrackStatusResult(true, emptyMap(), emptySet())
        val wanted = sns.distinct().take(25)
        val out = LinkedHashMap<String, String>()
        val missing = LinkedHashSet<String>()
        var anyOk = false
        var firstError: String? = null
        for (chunk in wanted.chunked(10)) {
            val batch = trackStatusesResult(ses, chunk)
            if (batch.ok) anyOk = true else if (firstError == null) firstError = batch.error
            out.putAll(batch.statuses)
            val retry = if (batch.ok) batch.missing else chunk
            if (retry.isNotEmpty()) {
                Log.i("PddNative", "枚举补查 ${retry.size} 单（批量${if (batch.ok) "部分返回" else "失败"}）")
                for (sn in retry) {
                    val one = trackStatusesResult(ses, listOf(sn))
                    if (one.ok) {
                        anyOk = true
                        val st = one.statuses[sn]
                        if (!st.isNullOrBlank()) out[sn] = st else missing.add(sn)
                    } else {
                        if (firstError == null) firstError = one.error
                        missing.add(sn)
                    }
                }
            }
        }
        val allMissing = wanted.filter { it !in out }.toSet()
        return TrackStatusResult(
            ok = anyOk,
            statuses = out,
            missing = allMissing,
            error = if (anyOk) null else firstError ?: "track/status failed"
        )
    }

    private fun trackStatusesWithFallback(ses: Session, sns: List<String>): Map<String, String> =
        trackStatusesWithFallbackResult(ses, sns).statuses

    /**
     * 轻量探活：拉 1 条订单，判断这份登录态服务端认不认。
     * 成功判据是"没有 error_code"，而不是"有条数"—— 账号没订单时 orders 也是空的。
     * @return Triple(是否可用, error_code, 说明)
     */
    fun verify(ses: Session): Triple<Boolean, Int, String> {
        if (!ses.usable) return Triple(false, -1, "本地没有登录态")
        val (httpCode, js) = orderPage(ses, TAB_ALL, null, size = 1)
        if (js == null) return Triple(false, -1, "接口无响应（HTTP $httpCode）")
        val ec = js.optInt("error_code", 0)
        if (ec == ERR_NEED_LOGIN) return Triple(false, ec, "服务端说登录已过期（40001）")
        if (ec != 0) return Triple(false, ec, "接口返回 error_code=$ec")
        val n = js.optJSONArray("orders")?.length() ?: 0
        return Triple(true, 0, "登录态有效（本页 $n 条订单）")
    }

    // ---------------------------------------------------------------- 抽取

    private class Found {
        var code: String? = null
        var tip: String? = null
        var place: String? = null
    }

    /**
     * 递归遍历响应，按字段名候选抓 code / tip / place。
     * 不猜响应层级 —— 服务端改版结构也不怕。code 额外要求含数字，避免误抓内部状态枚举。
     */
    private fun walk(node: Any?, depth: Int, out: Found) {
        if (node == null || depth > 6) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        walk(v, depth + 1, out)
                        continue
                    }
                    if (v == null || v == JSONObject.NULL) continue
                    val sv = v.toString().trim()
                    if (sv.isEmpty() || sv == "0" || sv == "false" || sv == "null") continue
                    if (out.code == null && CODE_KEYS.contains(k) &&
                        sv.length in 3..24 && sv.any { it.isDigit() } &&
                        // ★ 形态校验：分段货架码（31-5-7006）/ ≥4 位含字母 / ≥6 位纯数字；
                        //   纯 1~2 位小数字（状态码 0/3/7…）不算 —— 与 PddChannel 同一道闸
                        (Regex("\\d{1,3}-\\d{1,4}-\\d{2,6}").matches(sv) ||
                                (sv.length >= 4 && sv.any { it.isLetter() }) ||
                                (sv.length >= 6 && sv.all { it.isDigit() }))
                    ) {
                        out.code = sv
                    } else if (out.tip == null && TIP_KEYS.contains(k)) {
                        out.tip = sv
                    } else if (out.place == null && PLACE_KEYS.contains(k)) {
                        out.place = sv
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1, out)
        }
    }

    /** 商品名：字段是 order_goods（实测），旧的 goods_list 一并兼容 */
    private fun goodsOf(o: JSONObject): String {
        val gl = o.optJSONArray("order_goods")
            ?: o.optJSONArray("goods_list")
            ?: o.optJSONArray("goods")
            ?: return ""
        if (gl.length() == 0) return ""
        val g = gl.optJSONObject(0) ?: return ""
        var n = g.optString("goods_name").ifEmpty { g.optString("goods_title") }
        if (n.length > 26) n = n.substring(0, 26) + "…"
        return if (gl.length() > 1) "$n 等${gl.length()}件" else n
    }

    private fun statusOf(o: JSONObject): String {
        for (k in arrayOf(
            "order_status_prompt", "status_desc", "order_status_desc",
            "order_status_str", "prompt"
        )) {
            val v = o.optString(k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun isPickupTerminal(o: JSONObject): Boolean {
        val prompt = statusOf(o)
        return PickupVerdict.HARD_TERMINAL.any { prompt.contains(it) } ||
                DEAD_STATUS.contains(o.optInt("status", -1))
    }

    /** Regression check for terminal filtering in the PDD pickup scan. */
    fun pickupFilterSelfTest(): String {
        val cases = listOf(
            Triple(
                "待收货且操作按钮含申请退款",
                JSONObject("""{"status":3,"shipping_status":1,"order_status_prompt":"待收货","brief_prompt":"申请退款"}"""),
                true
            ),
            Triple(
                "订单状态明确退款",
                JSONObject("""{"status":3,"shipping_status":1,"order_status_prompt":"退款成功","brief_prompt":"申请退款"}"""),
                false
            ),
            Triple(
                "退款终态状态码",
                JSONObject("""{"status":5,"shipping_status":1,"order_status_prompt":"待收货","brief_prompt":"申请退款"}"""),
                false
            )
        )
        val results = cases.map { (name, order, expected) ->
            val actual = looksWaiting(order) && !isPickupTerminal(order)
            Triple(name, actual, expected)
        }
        val passed = results.count { it.second == it.third }
        return buildString {
            append("PddNative 取件码候选筛选自测：\n")
            for ((name, actual, expected) in results) {
                append(if (actual == expected) "  PASS " else "  FAIL ")
                    .append(name).append(" -> ").append(actual)
                    .append(if (actual == expected) "" else " expected=$expected")
                    .append('\n')
            }
            append("结果：$passed/${results.size} 通过")
        }
    }

    /**
     * 该订单是否可能已入柜/入站。
     * 先看状态文案关键字；再退回结构化判据：shipping_status==1（在途）且不是退款/取消类。
     */
    private fun looksWaiting(o: JSONObject): Boolean {
        val prompt = o.optString("order_status_prompt")
        for (h in WAIT_HINTS) if (prompt.contains(h)) return true
        if (o.optInt("shipping_status", -1) == 1) {
            val st = o.optInt("status", -1)
            if (!DEAD_STATUS.contains(st)) return true
        }
        // 兜底：整条订单 JSON 里出现关键字（不同版本字段位置可能变）
        val blob = o.toString()
        for (h in WAIT_HINTS) if (blob.contains(h)) return true
        return false
    }

    // ---------------------------------------------------------------- 主流程

    // ---------------------------------------------------------------- ★ vc342：订单页 60s 共享缓存

    private data class OrderPages(
        val at: Long,
        val list: List<JSONObject>,
        val err: String?,
        val needLogin: Boolean,
        val ok: Boolean,
        val partial: Boolean
    )

    private data class OrderPageResult(
        val list: List<JSONObject>,
        val err: String?,
        val needLogin: Boolean,
        val ok: Boolean,
        val partial: Boolean
    )

    private val orderPagesCache = HashMap<String, OrderPages>()

    /** Force a fresh order-list request for one account before secondary-account scans. */
    fun clearOrderPagesCache(uid: String) {
        if (uid.isBlank()) return
        synchronized(orderPagesCache) {
            val prefix = "$uid|"
            orderPagesCache.keys.removeAll { it.startsWith(prefix) }
        }
        Log.i("PddNative", "已清理账号订单页缓存 uid=${uid.takeLast(8)}")
    }

    /**
     * 取某 tab 的订单列表（最多 [maxPages] 页）。
     *
     * ★ vc342（结构性优化 P0-3）：第 4 栏 [scan] 与第 5 栏 [inTransitScan] 打的是**同一批**
     *   `order_list` 接口 —— 以前两栏刷新时各拉一遍（双倍耗时 + 双倍风控暴露）。
     *   现在 60 秒内只真请求一次，两栏共用结果。
     *
     * @return Triple(订单列表, 错误文案, 是否登录过期)
     */
    private fun loadOrderPages(
        ses: Session, tab: String, maxPages: Int, onProgress: ((String) -> Unit)? = null
    ): OrderPageResult {
        // ★★ vc493：缓存键**必须带账号**！
        //   原来是 "$tab|$maxPages" —— 多账号时副号会命中主号缓存的那页订单，
        //   结果就是"副号显示了主号的件"（串号）或副号永远拉不到自己的件。
        //   uid 为空时退回 cookie 哈希（同一账号的 cookie 稳定，够用）。
        val accKey = ses.uid.ifEmpty { ses.cookie.hashCode().toString() }
        val key = "$accKey|$tab|$maxPages"
        synchronized(orderPagesCache) {
            val c = orderPagesCache[key]
            if (c != null && System.currentTimeMillis() - c.at < 60_000L) {
                Log.i("PddNative", "订单页缓存命中 $key（${c.list.size} 单，" +
                        "${(System.currentTimeMillis() - c.at) / 1000}s 前）")
                return OrderPageResult(c.list, c.err, c.needLogin, c.ok, c.partial)
            }
        }
        val out = ArrayList<JSONObject>()
        var firstErr: String? = null
        var needLogin = false
        var sawValidPage = false
        var offset: String? = null
        for (p in 1..maxPages) {
            val (httpCode, js) = orderPage(ses, tab, offset)
            if (js == null) {
                if (firstErr == null) firstErr = "订单列表无响应（HTTP $httpCode）"
                break
            }
            val ec = js.optInt("error_code", 0)
            if (ec == ERR_NEED_LOGIN) {
                needLogin = true
                break
            }
            if (ec != 0) {
                if (firstErr == null) firstErr = "接口返回 error_code=$ec"
                break
            }
            val arr = js.optJSONArray("orders")
            if (arr == null) {
                // 字段缺失/风控壳页属于失败，不能伪装成账号没有订单。
                firstErr = firstErr ?: "订单列表缺少 orders 字段"
                runCatching {
                    Log.w("PddNative", "order_list 无 orders 字段｜keys=" +
                        js.keys().asSequence().toList() + "｜片段=" + js.toString().take(400))
                }
                break
            }
            // 空数组是有效的“成功但没有订单”，不能和异常响应混为一谈。
            sawValidPage = true
            if (arr.length() == 0) {
                Log.i("PddNative", "order_list orders 空数组：接口成功返回空页")
                break
            }
            var validRows = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("order_sn").isNotEmpty()) {
                    out.add(o)
                    validRows++
                }
            }
            if (arr.length() > 0 && validRows == 0) {
                firstErr = firstErr ?: "订单列表含数据但没有有效订单号"
                break
            }
            onProgress?.invoke("已取订单 ${out.size} 条")
            offset = arr.optJSONObject(arr.length() - 1)?.optString("offset").orEmpty()
            if (offset.isEmpty()) break
        }
        val ok = sawValidPage && firstErr == null && !needLogin
        val partial = sawValidPage && !ok
        // 正常空页也可缓存；失败/部分结果不能缓存成完整列表。
        if (ok) {
            synchronized(orderPagesCache) {
                orderPagesCache[key] = OrderPages(System.currentTimeMillis(), out, null, false, true, false)
                if (orderPagesCache.size > 8) orderPagesCache.clear()
            }
        }
        if (!ok) Log.w("PddNative", "订单页未完整成功 tab=$tab got=${out.size} partial=$partial needLogin=$needLogin err=$firstErr")
        return OrderPageResult(out, firstErr, needLogin, ok, partial)
    }

    /**
     * 拉一遍拼多多待取件的取件码。
     *
     * 先扫「待收货」tab（精准、便宜），再扫「全部」tab 兜底（有些已签收/驿站件不挂在待收货下），
     * 合并去重后筛候选，逐个查 cabinet。
     *
     * @param maxPages  每个 tab 最多翻几页（每页 50）
     * @param cap       最多对多少个候选订单发起 cabinet 查询，防止请求过密
     * @param onProgress 进度回调（工作线程内调用）
     */
    fun scan(
        ses: Session,
        maxPages: Int = 2,
        cap: Int = 40,
        onProgress: ((String) -> Unit)? = null
    ): Report {
        if (!ses.usable) {
            return Report(true, "未登录拼多多", emptyList(), 0, 0)
        }

        // 1) 订单列表：待收货优先，再全部兜底
        //    ★ vc342：走 60s 共享缓存 —— 第 5 栏的 inTransitScan 刚拉过的「待收货」页不再重拉
        val orders = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        var firstErr: String? = null
        var successfulTabs = 0
        var partial = false
        for (tab in arrayOf(TAB_UNRECEIVED, TAB_ALL)) {
            val page = loadOrderPages(ses, tab, maxPages, onProgress)
            if (page.needLogin) return Report(true, "登录已过期", emptyList(), 0, orders.size, ok = false, partial = partial)
            if (page.ok) successfulTabs++
            if (page.partial) partial = true
            if (page.err != null) {
                if (firstErr == null) firstErr = page.err
                partial = true
            }
            for (o in page.list) {
                val sn = o.optString("order_sn")
                if (sn.isNotEmpty() && seen.add(sn)) orders.add(o)
            }
            onProgress?.invoke("已取订单 ${orders.size} 条")
        }

        val total = orders.size
        if (total == 0 && (firstErr != null || successfulTabs == 0)) {
            // 没有任何订单且列表未完整成功：不能把它当成可信空结果。
            return Report(false, firstErr ?: "订单列表抓取失败", emptyList(), 0, 0, ok = false, partial = partial)
        }
        if (total == 0) {
            // 至少有一个 tab 明确成功为空；另一个 tab 失败时仍是部分结果，不清缓存。
            return Report(false, firstErr.orEmpty(), emptyList(), 0, 0, ok = true, partial = partial)
        }

        // 2) 逐个查取件码
        //    ★ vc342：串行 → **3 线程并行**。旧实现 20 个候选单 = 20 × ~0.4s ≈ 8s，
        //    而这条链现在是"一次刷新拿全码"的第①步，必须快；3 路既压住耗时又不至于招风控。
        //    ★ vc643：候选入口先拦明确终态与明显过期单，避免把已取/退款单送进 cabinet。
        //    但对超龄且服务端仍明确报待取的单保守放行，避免误伤真实滞留件。
        val nowForPickup = System.currentTimeMillis()
        val targets = orders.filter { o ->
            if (!looksWaiting(o)) {
                Log.i("PddNative", "取件码候选跳过：无待取信号 order=${o.optString("order_sn")}")
                false
            } else {
                val sn = o.optString("order_sn")
                val prompt = statusOf(o)
                // 只检查订单状态字段。整份订单 JSON 里还会包含「申请退款」按钮等操作文案，
                // 把它们当成订单终态会误伤真实待取件（实测 status=3、待收货、IN_CABINET）。
                val hard = PickupVerdict.HARD_TERMINAL.firstOrNull { prompt.contains(it) }
                val terminal = isPickupTerminal(o)
                if (terminal) {
                    Log.i("PddNative", "取件码候选跳过 $sn：明确终态「${hard ?: "status=" + o.optInt("status", -1)}」")
                    false
                } else {
                    val track = PddChannel.lastTrackStatus[sn]
                    if (track == "GOT") {
                        Log.i("PddNative", "取件码候选跳过 $sn：track=GOT（已取件）")
                        false
                    } else if (track == "OUT_CABINET" && !prompt.contains("待确认") &&
                        !prompt.contains("待收货") && !prompt.contains("待取")) {
                        Log.i("PddNative", "取件码候选跳过 $sn：track=OUT_CABINET 且无待取文案")
                        false
                    } else {
                        val born = snToDateMs(sn)
                        val old = born != null && nowForPickup - born > PickupVerdict.MAX_ORDER_AGE_DAYS * 86_400_000L
                        val stillWaiting = track != null && !PddChannel.isNonPickupState(track)
                        if (old && !stillWaiting) {
                            Log.i("PddNative", "取件码候选跳过 $sn：下单超 ${PickupVerdict.MAX_ORDER_AGE_DAYS} 天且无待取状态")
                            false
                        } else true
                    }
                }
            }
        }
        val bySn = LinkedHashMap<String, JSONObject>()
        for (o in targets) {
            val sn = o.optString("order_sn")
            if (sn.isNotEmpty() && !bySn.containsKey(sn)) bySn[sn] = o
        }
        val want = bySn.keys.take(cap)
        val items = java.util.Collections.synchronizedList(ArrayList<Pickup>())
        val needLogin = java.util.concurrent.atomic.AtomicBoolean(false)
        val detailFailures = java.util.concurrent.atomic.AtomicInteger(0)
        val idx = java.util.concurrent.atomic.AtomicInteger(0)
        val n = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = minOf(3, maxOf(1, want.size))
        val threads = ArrayList<Thread>()
        for (w in 0 until workers) {
            threads.add(Thread {
                while (!needLogin.get()) {
                    val i = idx.getAndIncrement()
                    if (i >= want.size) return@Thread
                    val sn = want[i]
                    n.incrementAndGet()
                    onProgress?.invoke("查询取件码 ${n.get()}/${want.size}")
                    val (cabinetOk, js) = cabinet(ses, sn)
                    if (!cabinetOk || js == null) {
                        detailFailures.incrementAndGet()
                        Log.w("PddNative", "取件码详情失败 order=$sn；保留现有缓存，下轮可重试")
                        continue
                    }
                    if (js.optInt("error_code", 0) == ERR_NEED_LOGIN) {
                        needLogin.set(true)
                        return@Thread
                    }
                    val f = Found()
                    walk(if (js.opt("result") != null) js.opt("result") else js, 0, f)
                    fillFromCabinetResult(js, f, sn)
                    // ★ vc655 诊断：cabinet 返回 200 但没有码 —— 把原始响应头打出来，
                    //   才能看出是"这单本来就没码"还是"码在别的字段里我们没认"。
                    val c = f.code ?: run {
                        Log.w("PddNative", "cabinet 无码 order=$sn json=${js.toString().take(1200)}")
                        null
                    } ?: continue
                    val o = bySn[sn]
                    // ★ vc659：PDD 对菜鸟单只给公司名「菜鸟驿站」，具体站点藏在订单自带的
                    //   物流文案里（"快件已由抚州…店菜鸟驿站代收…"）。只在当前站点名是空/泛称时替换。
                    val generic = f.place.isNullOrBlank() || f.place == "菜鸟驿站" ||
                            f.place == "驿站" || f.place == "快递柜" || f.place == "代收点"
                    if (generic) {
                        val msg0 = o?.optString("shipping_message").orEmpty()
                        val better = stationFromMsg(msg0)
                        if (better.isNotEmpty()) f.place = better
                        else if (msg0.isNotBlank()) Log.i("PddNative", "站点名提取失败 order=$sn msg=${msg0.take(90)}")
                        else Log.i("PddNative", "订单无物流文案 order=$sn")
                    }
                    items.add(
                        Pickup(
                            orderSn = sn,
                            code = c,
                            tip = f.tip.orEmpty(),
                            place = f.place.orEmpty(),
                            goods = o?.let { goodsOf(it) } ?: "",
                            status = o?.let { statusOf(it) } ?: "",
                            waybill = o?.let { waybillOf(it) }.orEmpty()
                        )
                    )
                }
            })
        }
        threads.forEach { it.start() }
        threads.forEach { try { it.join() } catch (t: Throwable) { SafeRun.w("PddNative", t) } }
        if (needLogin.get()) {
            return Report(
                true, "登录已过期", items.toList(), n.get(), total, targets.size,
                ok = false, partial = true, candidateOrderSns = want,
                candidateExpressUrls = want.mapNotNull { sn ->
                    val url = bySn[sn]?.let { expressUrlOf(it) }.orEmpty()
                    if (url.isNotBlank()) sn to url else null
                }.toMap()
            )
        }
        val detailPartial = detailFailures.get() > 0 || targets.size > want.size
        if (detailFailures.get() > 0) {
            Log.w("PddNative", "取件码详情部分失败 ${detailFailures.get()} 单；查询数=${n.get()} 成功有码=${items.size}")
        }
        val reportPartial = partial || detailPartial
        return Report(
            false, firstErr.orEmpty(), items.toList(), n.get(), total, targets.size,
            ok = successfulTabs > 0,
            partial = reportPartial,
            candidateOrderSns = want,
            candidateExpressUrls = want.mapNotNull { sn ->
                val url = bySn[sn]?.let { expressUrlOf(it) }.orEmpty()
                if (url.isNotBlank()) sn to url else null
            }.toMap()
        )
    }

    // ---------------------------------------------------------------- ★ vc258：调试导出

    /**
     * ★ vc258：把订单列表的**原始响应**原样落盘（外部目录，`adb pull` 直接拿）。
     *
     * 为什么需要它：[inTransitScan] 只回**过滤后**的在途单，日志也只打在途单 ——
     * 要回答"某个件为什么拉不到任何信息"，必须看服务端到底返没返回它、
     * `tracking_number` 是什么、它落在哪个 tab、`order_buttons` 里有没有直链。
     * 用法：
     *   adb shell am start -n com.agent.idcode/.MainActivity --ez debug_dump_orders true
     *   adb pull /sdcard/Android/data/com.agent.idcode/files/pdd_orders_dump.txt
     */
    fun debugDumpOrders(ctx: android.content.Context, ses: Session, maxPages: Int = 3) {
        Thread {
            val sb = StringBuilder()
            for (tab in arrayOf(TAB_UNRECEIVED, TAB_ALL)) {
                var offset: String? = null
                for (p in 1..maxPages) {
                    val (code, js) = orderPage(ses, tab, offset)
                    sb.append("\n===== tab=").append(tab).append(" page=").append(p)
                        .append(" http=").append(code).append(" =====\n")
                    sb.append(js?.toString() ?: "(null)")
                    val arr = js?.optJSONArray("orders")
                    val last = if (arr != null && arr.length() > 0) {
                        arr.optJSONObject(arr.length() - 1)
                    } else null
                    offset = last?.optString("offset").orEmpty()
                    if (offset.isEmpty()) break
                }
            }
            try {
                val f = java.io.File(ctx.getExternalFilesDir(null), "pdd_orders_dump.txt")
                f.writeText(sb.toString())
                Log.i("PddNative", "订单列表 dump 完成：${f.absolutePath}（${sb.length} 字符）")
            } catch (e: Throwable) {
                Log.w("PddNative", "订单列表 dump 失败: ${e.message}")
            }
        }.start()
    }

    // ---------------------------------------------------------------- ★ vc212：途中栏直取

    /**
     * ★★ 第 5 栏「在途快递」真首页直取（2026-09-27）。
     *
     * 背景（取证见 文档\在途快递_第5栏_pdd在途不显示_修复_2026-09-27.md §六）：
     * 订单 H5 页**首屏是 SSR 服务端直出、不发 XHR** —— WebHook 旁听 9 天 / 243 轮 / 350 次
     * order_list_v4 **全部带 offset**（0 次真首页），滚动 XHR 只能翻到 ~260720 之前的老单；
     * 最新单只出现在 track/status 里（只有 order_sn+status，无运单号/商品，喂不了途中栏）。
     * ⇒ 老路（WebView 旁听）对最新单**结构性失明**；本函数用原生 HTTP 拉「待收货」「全部」
     *   tab 的**真首页**（offset=null），第一条就是最新订单。
     *
     * 口径（vc213 真机反馈收紧：09-27 02:01 截图——老单涌入 / 凉席待取件混入）：
     *  · **只扫「待收货」tab**（不扫「全部」！全部 tab=时间倒序的历史订单总和，且
     *    "已取件未确认收货"的单文案也是「待收货」，终态词拦不住 ⇒ vc212 老单整屏涌入的根因）；
     *  · 终态词排除：共用 [PddChannel.TRANSIT_TERMINAL_WORDS]（含"补寄成功"）；
     *  · 已到站待取排除：文案含「待取/待领取/待自提/已入柜」—— 那是第 4 栏语义；
     *  · ★ 结构化判据：`shipping_status` 字段**存在**且 != 1（在途）⇒ 排除；字段缺失则放行；
     *  · ★ 时间兜底：order_sn 前 6 位 = YYMMDD 下单日期（09-27 取证实证，260510=5 月 10 日），
     *    **>45 天的单不可能是"途中"** —— 这层不赌服务端字段，自主可控；
     *  · status 5..12（退款/取消类）排除 —— 与 [scan] 的 DEAD_STATUS 同判据；
     *  · **不做** track/status 交叉（PddNative 不碰 PddChannel 的旁听状态）——
     *    由调用方（MainActivity）用 PddChannel.trackSaysNotInTransit 做。
     *
     * @param maxPages 最多翻几页（每页 50；首页即可覆盖最新单，2 页兜底）
     */
    fun inTransitScan(
        ses: Session,
        maxPages: Int = 2,
        onProgress: ((String) -> Unit)? = null
    ): TransitReport {
        if (!ses.usable) return TransitReport(true, false, "未登录拼多多", emptyList())

        val orders = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        var firstErr: String? = null
        // ★ vc213：只扫「待收货」——「全部」tab 会把历史订单全拉进来（vc212 老单涌入根因）
        // ★ vc342：与第 4 栏 scan 共享 60s 订单页缓存，同一批接口不重复请求
        var listOk = false
        var listPartial = false
        for (tab in arrayOf(TAB_UNRECEIVED)) {
            val page = loadOrderPages(ses, tab, maxPages, onProgress)
            if (page.needLogin) return TransitReport(true, false, "登录已过期", emptyList())
            listOk = page.ok
            listPartial = page.partial
            if (page.err != null && firstErr == null) firstErr = page.err
            for (o in page.list) {
                val sn = o.optString("order_sn")
                if (sn.isNotEmpty() && seen.add(sn)) orders.add(o)
            }
            onProgress?.invoke("已取订单 ${orders.size} 条")
        }

        if (orders.isEmpty()) {
            // 只有列表接口明确完整成功为空，才允许调用方清空该源旧数据。
            return if (!listOk || listPartial || firstErr != null) TransitReport(
                false, false, firstErr ?: "订单列表抓取失败", emptyList(), partial = listPartial
            ) else TransitReport(false, true, "", emptyList())
        }

        val out = ArrayList<TransitItem>()
        // ★ vc349：歧义单收集（见下面 tw 分支）
        val ambiguous = ArrayList<TransitItem>()
        val nowMs = System.currentTimeMillis()
        for (o in orders) {
            val sn = o.optString("order_sn")
            if (sn.isEmpty()) continue
            val status = o.optString("order_status_prompt")
            val msg0 = o.optString("shipping_message")
            val shipStatus = o.optInt("shipping_status", -1)
            // ★ vc348（2026-10-20 同志实证）：**订单状态字段会说谎**。
            //   两个件拼多多显示"运输中，预计明天到达"，`order_status_prompt` 却是
            //   「已签收，待确认收货」—— 旧逻辑按订单状态整条丢掉，途中栏就少了两个件。
            //   ⇒ 冲突时以**物流文案**为准：物流说在途，就放行。
            val msgInTransit = PddChannel.MSG_IN_TRANSIT.any { msg0.contains(it) }
            // 「已签收，待确认收货」= 站点代签、还没确认（可能已到站，也可能只是状态滞后）
            val pendingConfirm = status.contains("已签收") &&
                    (status.contains("待确认") || status.contains("待收货"))
            // ★ vc261：每一层过滤都记一条丢弃原因 —— 10-14 同志报"烤虾那单在途时第 5 栏
            //   也不显示"，而历史日志/缓存都已过期无法还原当时是哪一层拦的 ⇒ 这类
            //   "静默丢弃"必须当时就打出来（vc182 手动单号被误伤也是同一类问题）。
            // ★ vc348：退款/取消/交易成功这类硬终态，不管物流文案写什么都不进途中
            val hard = PddChannel.HARD_TERMINAL_WORDS.firstOrNull { status.contains(it) }
            if (hard != null) {
                Log.i("PddNative", "在途扫描丢弃 $sn：硬终态「$hard」（订单=「$status」）")
                continue
            }
            val tw = PddChannel.TRANSIT_TERMINAL_WORDS.firstOrNull { status.contains(it) }
            if (tw != null && !msgInTransit && !pendingConfirm) {
                // ★ vc349：软终态「已签收」+ 物流文案**为空** = 歧义单 ——
                //   可能真签收了，也可能只是订单状态滞后（同志两天两版口径相反）。
                //   不进途中，也不直接扔：收集起来交给调用方 cabinet 裁决
                //   （有码→进取件码栏；无码→保持不显示）。上限 5 单、仅 45 天内的单。
                if (status.contains("已签收") && msg0.isEmpty() && ambiguous.size < 5 &&
                    (snToDateMs(sn)?.let { nowMs - it <= PickupVerdict.MAX_ORDER_AGE_DAYS * 86_400_000L } ?: true)
                ) {
                    ambiguous.add(
                        TransitItem(
                            orderSn = sn, goods = goodsOf(o), status = status,
                            latest = msg0, phone = "",
                            waybill = waybillOf(o), expressUrl = expressUrlOf(o),
                            etaHint = etaHintOf(o)
                        )
                    )
                    Log.i("PddNative", "歧义单 $sn：订单=「$status」 物流为空 → 交 cabinet 裁决")
                    continue
                }
                Log.i(
                    "PddNative",
                    "在途扫描丢弃 $sn：终态词「$tw」（订单=「$status」 物流=「${msg0.take(40)}」）"
                )
                continue
            }
            if (tw != null && (msgInTransit || pendingConfirm)) {
                Log.i(
                    "PddNative",
                    "在途保留 $sn：订单=「$status」 物流=「${msg0.take(40)}」 " +
                            "shipping_status=$shipStatus（冲突时按物流）"
                )
            }
            // ★ vc213：已到站待取（凉席类）—— 第 4 栏语义，不进途中栏。
            //   刻意不排「已到达」：那会误伤「已到达XX转运中心」这种真在途单
            val waitWord = listOf("待取", "待领取", "待自提", "已入柜").firstOrNull { status.contains(it) }
            if (waitWord != null && !msgInTransit) {
                Log.i("PddNative", "在途扫描丢弃 $sn：到站待取文案「$waitWord」（文案=「$status」）")
                continue
            }
            // ★ vc217 到站件兜底（03:11 真机：竹凉席已到驿站仍混进第 5 栏）—— 凉席类订单
            //   status 恒「待收货」（PDD 订单状态不随物流更新），上面一层拦不住；但已入站的
            //   件其**物流描述**（shipping_message，第 4 栏取件码的同源文本）必有驿站/取件码。
            //   「驿站」不会误伤转运中心在途单（转运中心不叫驿站）—— 宁可第 5 栏少显示。
            // ★ vc347：「待确认」件即使文案里有"驿站"也不在这里丢 ——
            //   交给调用方的 cabinet 交叉确认：有码就进取件码栏，没码留途中。
            if (!msgInTransit && !pendingConfirm && (msg0.contains("驿站") ||
                        Regex("(取件码|取货码|提货码|自提码|驿站码|取件验证码)[:：\\s]*[A-Za-z0-9][A-Za-z0-9\\-]{1,12}")
                            .containsMatchIn(msg0))
            ) {
                Log.i("PddNative", "在途扫描丢弃 $sn：物流文案含驿站/取件码（msg=「${msg0.take(50)}」）")
                continue
            }
            if (DEAD_STATUS.contains(o.optInt("status", -1))) {
                Log.i("PddNative", "在途扫描丢弃 $sn：死状态 status=${o.optInt("status", -1)}")
                continue
            }
            // ★ vc213 结构化判据：shipping_status 字段存在且明确不是 1（在途）⇒ 排除；
            //   字段缺失（-1 默认但 has=false）放行 —— 宁多勿漏，别赌响应结构
            // ★ vc347：「待确认」件放行（驿站已代签，shipping_status 往往不是 1）
            // ★ vc348：物流文案说在途的一律放行（订单状态字段会滞后）
            if (!msgInTransit && !pendingConfirm && o.has("shipping_status") && shipStatus != 1) {
                Log.i("PddNative", "在途扫描丢弃 $sn：shipping_status=$shipStatus（非在途）")
                continue
            }
            // ★ vc616 时间兜底：order_sn 前 6 位=YYMMDD 下单日期，>15 天不可能是"途中"
            val bornMs = snToDateMs(sn)
            if (bornMs != null && nowMs - bornMs > PickupVerdict.MAX_ORDER_AGE_DAYS * 86_400_000L) {
                Log.i("PddNative", "在途扫描丢弃 $sn：下单超 ${PickupVerdict.MAX_ORDER_AGE_DAYS} 天")
                continue
            }
            // ★ vc252：运单号 + 物流页直链 —— 订单列表接口自带，零额外请求
            val wb = waybillOf(o)
            val eu = expressUrlOf(o)
            // ★ vc350（2026-10-22 同志截图实证）：**没有物流信号的单不是快递** ——
            //   多多买菜/虚拟商品/未发货类的特征：无运单号、无「查看物流」直链、物流文案为空，
            //   订单状态恒「待收货」。它不该被当成"在途快递"（详情页也只会写待收货）。
            //   判据三缺一不可：三个都空才丢，真在途的快递单至少有运单号或物流文案。
            if (wb.isEmpty() && eu.isEmpty() && msg0.isEmpty()) {
                Log.i("PddNative", "在途扫描丢弃 $sn：无物流信号（多多买菜/未发货类，订单=「$status」）")
                continue
            }
            Log.i("PddNative", "在途单 $sn 运单号=${wb.ifEmpty { "(无)" }} 直链=" +
                    (if (eu.isEmpty()) "(无)" else "有"))
            out.add(
                TransitItem(
                    orderSn = sn,
                    goods = goodsOf(o),
                    status = status,
                    latest = o.optString("shipping_message")
                        .ifEmpty { orderHintLatestOf(o) }.ifEmpty { status },
                    phone = PddChannel.findPhone(o),
                    waybill = wb,
                    expressUrl = eu,
                    etaHint = etaHintOf(o)
                )
            )
        }
        Log.i(
            "PddNative",
            "在途扫描汇总：待收货订单 ${orders.size} 条 ⇒ 入选 ${out.size} 条（歧义 ${ambiguous.size} 条）"
        )
        return TransitReport(false, listOk && !listPartial && firstErr == null, "", out, ambiguous, partial = listPartial || firstErr != null)
    }

    /**
     * ★★ vc252：订单列表**直接带运单号** —— 不用再开详情页去抠。
     *
     * 2026-10-14 抓包实证（384KB 订单列表响应 / 20 单）：
     *  · `orders[].tracking_number` 对已发货的订单**有值** ——
     *    `260822-157894612560590` → `SF5115986077685`（顺丰）、
     *    `260812-441303713360590` → `JT5515365007898`（极兔）、
     *    `260802-108726863440590` → `465552193769620`（京东）；
     *    只有退款/取消的单（status 5/6）才是空字符串。
     *  · 就算那个字段空，`orders[].order_buttons[].type_value.url` 里还有「查看物流」的直链
     *    `goods_express.html?tracking_number=XXX&shipping_id=..&order_sn=YYY&goods_id=ZZZ`，
     *    运单号就在 query 里 ⇒ 兜底从这条 URL 上抠。
     * ⇒ 以前「运单号只藏在详情页 DOM 里、每单要开一次页面（10~20s）」这个前提**不成立了**：
     *   批量拿运单号现在**零额外请求**。
     */
    /**
     * ★ vc696：订单列表的 ETA 提示 —— 拼多多 App 订单列表每行都写「运输中 预计后天送达 | 中通快递：…」，
     * 这条就来自 `extra_info.order_hint.message`，**每个在途单都有**。
     * 返回含「预计」的那一段（没有就空串），交给 UI 层 [MainActivity.etaForCard] 统一解析成「预计明天送达」。
     */
    fun etaHintOf(o: JSONObject): String =
        orderHintMessage(o).split("|").firstOrNull { it.contains("预计") }?.trim().orEmpty()

    /** ★ vc696：order_hint 里「|」后那半段（快递公司：最新物流文案）——
     *  实测当前订单列表接口**不返回** `shipping_message`（卡面原来只剩一句「待收货」），
     *  用它当最新轨迹的兜底。 */
    fun orderHintLatestOf(o: JSONObject): String =
        orderHintMessage(o).split("|").drop(1).joinToString("|").trim()

    private fun orderHintMessage(o: JSONObject): String =
        o.optJSONObject("extra_info")?.optJSONObject("order_hint")?.optString("message").orEmpty()

    fun waybillOf(o: JSONObject): String {
        val direct = o.optString("tracking_number").trim()
        if (direct.isNotEmpty() && direct != "0" && direct != "null") return direct
        val u = expressUrlOf(o)
        if (u.isEmpty()) return ""
        return Regex("[?&]tracking_number=([^&]+)").find(u)?.groupValues?.getOrNull(1).orEmpty()
    }

    /**
     * 「查看物流」直链（相对 URL）。拿不到返回空串 —— 调用方据此退回老路，不猜。
     * ★ 优先取 `order_sn` 与本单一致的直链（一条订单只有一个物流页，这里是双保险）。
     */
    fun expressUrlOf(o: JSONObject): String {
        val btns = o.optJSONArray("order_buttons") ?: return ""
        val sn = o.optString("order_sn")
        var best = ""
        for (i in 0 until btns.length()) {
            val b = btns.optJSONObject(i) ?: continue
            val u = b.optJSONObject("type_value")?.optString("url").orEmpty()
            if (!u.contains("goods_express") || !u.contains("tracking_number=")) continue
            val usn = Regex("[?&]order_sn=([^&]+)").find(u)?.groupValues?.getOrNull(1).orEmpty()
            if (usn == sn) return u
            if (best.isEmpty()) best = u
        }
        return best
    }
    /**
     * ★ vc213：order_sn 前 6 位 = YYMMDD 下单日期（09-27 取证实证：260510 那单就是 5 月 10 日）。
     * 解析失败（长度不够/非数字/月日非法）返回 null ⇒ 调用方放行（宁多勿漏，别赌订单号格式）。
     * vc216 起改 public：第 5 栏轨迹增强的时间硬校验（轨迹时间早于下单日 ⇒ 脏轨迹）也用它。
     * 返回的是下单日 12:00（正午）——调用方减 12h 得到当日 00:00 做下界。
     */
    fun snToDateMs(sn: String): Long? {
        if (sn.length < 6) return null
        val p = sn.substring(0, 6)
        if (!p.all { it.isDigit() }) return null
        val yy = p.substring(0, 2).toInt()
        val mm = p.substring(2, 4).toInt()
        val dd = p.substring(4, 6).toInt()
        if (mm !in 1..12 || dd !in 1..31) return null
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(2000 + yy, mm - 1, dd, 12, 0, 0)
        return cal.timeInMillis
    }
}
