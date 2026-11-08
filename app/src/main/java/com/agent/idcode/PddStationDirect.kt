package com.agent.idcode

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * vc270k：多多驿站**直连**取件码（不再逐单开物流页、不再开任何网页）。
 *
 * ## 接口来源（2026-10-16 真机取证）
 * 驿站 H5（`https://m.pinduoduo.net/mdkd/package`）的 JS 包里翻出来的接口家族
 * —— 全部 POST `api.pinduoduo.com/api/express_trackbox/<各接口>`，**免签名**：
 * 只要 `Referer` + `AccessToken`（= cookie 里的 PDDAccessToken）+ 浏览器 UA。
 * · `/add/package/v2/station/history/info`  body {} → 默认驿站（station_code）
 * · `/station/package`  body {station_code, entry_source, ...} → **该驿站下当前用户的
 *   所有包裹**（result.count = 包裹数，每个包裹各自带取件码）⇒ 一次请求全拿到。
 *
 * ## 解析为什么写成"容错结构遍历"
 * 本账号取证时 count=0（没包裹），拿不到真实列表字段结构 ⇒ 改成：对响应原文做
 * 结构无关的正则收割（形如 `架-层-号` 的码），再从码的上下文里抠运单号/商品/手机号。
 * 响应原文一律落盘（`pdd_station_dump.txt`），有包裹的账号取证后可据此精修字段名。
 */
object PddStationDirect {

    private const val TAG = "PddStation"
    private const val BASE = "https://api.pinduoduo.com/api/express_trackbox/"
    private const val REFERER = "https://m.pinduoduo.net/mdkd/package"

    /** 一个驿站包裹（取件码为核心） */
    data class StationPackage(
        val code: String,
        val waybill: String = "",
        val goods: String = "",
        val mobile: String = "",
        val sn: String = ""
    )

    /**
     * ★ vc315：上一次查询**是否真的问到了**（区别于"失败了返回空表"）。
     *
     * 用途：`station/package` 返回空 = "这个驿站此刻没有我的件" ——
     * 这是**有效信息**；而失败/未登录返回空是**无效信息**。
     * 两者分开了才能在日志里看出"真没件"还是"没问成"。
     */
    @Volatile
    var lastOk = false
        private set

    /**
     * 直连取件码。返回 (默认驿站名?, 包裹列表)。失败/未登录返回 (null, emptyList())。
     * 线程安全：内部同步 HTTP，调用方请放在后台线程。
     */
    fun query(ctx: Context): Pair<String?, List<StationPackage>> {
        lastOk = false
        val ck = try {
            CookieManager.getInstance().getCookie("https://mobile.yangkeduo.com/").orEmpty()
        } catch (_: Throwable) { "" }
        val tok = Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
        if (tok.isEmpty()) {
            Log.w(TAG, "没有 PDDAccessToken，跳过直连")
            return Pair(null, emptyList())
        }
        // ① 拿到**所有**驿站（默认 + 历史）—— 同志小米机实测：包裹在"抚州幼儿师专学校快递驿站"，
        //   而 default_station 是"中心批发超市" ⇒ 只查默认那个必然 count=0（vc270m 修）。
        val hRes = post("add/package/v2/station/history/info", "{}", ck, tok)
        if (hRes == null) return Pair(null, emptyList())
        dump(ctx, "history", hRes)
        val stations = ArrayList<Pair<String, String>>()   // (code, name)
        try {
            val res = JSONObject(hRes).optJSONObject("result")
            fun add(code: String?, name: String?) {
                val c = code.orEmpty()
                if (c.isNotEmpty() && stations.none { it.first == c }) {
                    stations.add(Pair(c, name.orEmpty()))
                }
            }
            val def = res?.optJSONObject("default_station")
            add(def?.optString("code"), def?.optString("name"))
            val arr = res?.optJSONArray("station_history_list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("code"), o.optString("name"))
                }
            }
        } catch (_: Throwable) {
            return Pair(null, emptyList())
        }
        if (stations.isEmpty()) {
            // ★ vc270n：解析不出驿站时必须把**真实字段名**露出来 —— 否则永远在猜
            //   （10-16 教训：那台小米机 default_station 解析成功但包裹在别的驿站，
            //    而 station_history_list 这个名字对不对没人知道，count=0 时更没有样本）。
            //   打 result 下的所有 key + 原文片段，下次一眼就能对上。
            val keys = try {
                JSONObject(hRes).optJSONObject("result")?.let { r ->
                    (0 until r.length()).map { r.names()?.getString(it) ?: "?" }
                }.orEmpty()
            } catch (_: Throwable) { emptyList() }
            Log.w(TAG, "拿不到任何驿站；result 的字段=[$keys] 原文=${hRes.take(600)}")
            return Pair(null, emptyList())
        }
        // ② 逐个驿站查包裹（各自带取件码），合并去重
        val merged = LinkedHashMap<String, StationPackage>()
        var hitStation: String? = null
        var allOk = true
        for ((code, name) in stations) {
            val body = JSONObject()
                .put("station_code", code)
                .put("entry_source", "APP")
                .put("dynamic_qrcode", false)
                .put("install_token", "")
                .put("dp_source", "")
                .put("redirect_app", false)
                .toString()
            val pRes = post("station/package", body, ck, tok)
            if (pRes == null) {
                allOk = false
                continue
            }
            dump(ctx, "packages@${name.ifEmpty { code.take(6) }}", pRes)
            val cnt = try {
                JSONObject(pRes).optJSONObject("result")?.optInt("count") ?: 0
            } catch (_: Throwable) { 0 }
            Log.i(TAG, "驿站 ${name.ifEmpty { code.take(6) }}：count=$cnt")
            // ★ vc270n：count=0 时也露一眼原文 —— 确认 `count` 这个字段名没猜错
            //   （猜错的后果是"其实有包裹但报 0"，静默且极难发现）
            if (cnt <= 0) Log.w(TAG, "  └ count=0 原文：${pRes.take(300)}")
            if (cnt > 0 && hitStation == null) hitStation = name.ifEmpty { null }
            for (p in parsePackages(pRes)) {
                if (!merged.containsKey(p.code)) merged[p.code] = p
            }
        }
        // ③ 兜底：驿站维度查不到 ⇒ 按绑定手机号查（另一个接口）
        if (merged.isEmpty()) {
            val bRes = post("bind/mobile/station/package", "{}", ck, tok)
            if (!bRes.isNullOrEmpty()) {
                dump(ctx, "byMobile", bRes)
                for (p in parsePackages(bRes)) {
                    if (!merged.containsKey(p.code)) merged[p.code] = p
                }
                Log.i(TAG, "按手机号兜底查询：${merged.size} 个码")
            }
        }
        val list = merged.values.toList()
        // ★ vc315：所有驿站都问到了 ⇒ 这份（哪怕是空表）就是"此刻在站的真名单"
        lastOk = allOk
        Log.i(TAG, "直连驿站汇总：${stations.size} 个驿站 → ${list.size} 个取件码" +
                (hitStation?.let { "（命中：$it）" } ?: "") + " ok=$lastOk")
        return Pair(hitStation, list)
    }

    /** 结构无关地收割取件码（形如 3-3-4013），并从上下文抠运单号/商品/手机号/订单号 */
    private fun parsePackages(raw: String): List<StationPackage> {
        val out = ArrayList<StationPackage>()
        // ★ vc270n：值可能是**字符串**也可能是**数字**（JSON int）—— 旧正则只认带引号的，
        //   万一服务端把码序列化成数字就一个都收割不到（而且不报错，静悄悄 0 个）。
        val codeRe = Regex(
            "\"[A-Za-z_]*code[A-Za-z_]*\"\\s*:\\s*(?:\"([0-9]{1,4}-[0-9]{1,4}-[0-9]{2,8})\"" +
                    "|([0-9]{1,4}-[0-9]{1,4}-[0-9]{2,8}))"
        )
        val seen = HashSet<String>()
        for (m in codeRe.findAll(raw)) {
            val code = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            if (code.isEmpty() || !seen.add(code)) continue
            val ctx = raw.substring(maxOf(0, m.range.first - 400), minOf(raw.length, m.range.last + 400))
            fun pick(keys: String): String {
                val r = Regex("\"(?:$keys)\"\\s*:\\s*\"?([^\",}]{2,60})\"?").find(ctx)
                return r?.groupValues?.get(1).orEmpty()
            }
            out.add(
                StationPackage(
                    code = code,
                    waybill = pick("waybill_no|mail_no|tracking_no|express_no|wp_code"),
                    goods = pick("goods_name|title|item_name|package_name"),
                    mobile = pick("mobile|phone|mobile_alias|receiver_mobile"),
                    sn = pick("order_sn|orderSn|order_id")
                )
            )
        }
        return out
    }

    /** 免签名 POST：Referer + AccessToken + Cookie + 浏览器 UA */
    private fun post(path: String, body: String, cookie: String, token: String): String? {
        return try {
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Referer", REFERER)
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                setRequestProperty("User-Agent", PddChannel.BROWSER_UA)
                setRequestProperty("AccessToken", token)
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val s = (if (code in 200..299) conn.inputStream else conn.errorStream)
            val text = s?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            conn.disconnect()
            if (text.isBlank()) null else text
        } catch (t: Throwable) {
            Log.w(TAG, "POST $path 失败：${t.message}")
            null
        }
    }

    /** 原始响应落盘（外部目录，adb/导出日志都能取）—— 校准解析用 */
    private fun dump(ctx: Context, tag: String, raw: String) {
        try {
            val f = java.io.File(ctx.getExternalFilesDir(null), "pdd_station_dump.txt")
            f.appendText("== $tag ${System.currentTimeMillis()}\n${raw.take(30000)}\n----\n")
        } catch (t: Throwable) { SafeRun.w("PddStationDirect", t) }
    }
}
