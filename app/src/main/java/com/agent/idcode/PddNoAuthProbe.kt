package com.agent.idcode

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * ★★★ 拼多多「物流分享页」的免登录接口探针（2026-10-12）
 *
 * ## 背景
 * 同志复制了拼多多「分享物流」的链接，实测发现：
 *  - 用手机自带浏览器（几乎不带拼多多 cookie）打开 ⇒ **不需要登录**，直接渲染出完整物流详情；
 *  - 而且在 App 的隐形 WebView 里旁听，抓到了它唯一的数据接口：
 *
 *      GET /proxy/api/api/express/logistics/detail_**noauth**
 *          ?share_token=…&order_sn=…&query_source=2&query_type=1&scene_type=1
 *
 *  接口名里的 **noauth** 已经说明设计上就不需要登录。
 *
 * ## 但直接调会 40001
 *  1. App 的 WebView 带了**已失效的登录 cookie + uid** ⇒ 服务端按"登录态失效"处理 ⇒ 40001；
 *  2. 从电脑侧裸调也 40001 ⇒ 说明**还缺一个请求头**：
 *     页面上先请求 `/proxy/api/xg/pfb/a3`，它返回的 32 位串就是拼多多的
 *     **anti-content 风控令牌**，后续接口要带在 `Anti-Content` 头里。
 *
 * ## 本探针做什么
 * 一次把「请求头组合」全试一遍（裸 / UA / UA+Referer / 再加 Anti-Content …），
 * 每条都打日志 + 落盘，**用真机数据确定到底需要哪几个头**，而不是猜。
 * 成功后就能**绕过 WebView、直接 HTTP 拿完整轨迹**（比抠页面稳得多）。
 *
 * adb 用法：
 *   adb shell am force-stop com.agent.idcode
 *   adb shell am start -n com.agent.idcode/.MainActivity --es debug_pdd_noauth "<分享链接>"
 */
object PddNoAuthProbe {

    private const val TAG = "PddNoAuth"
    private const val ORIGIN = "https://mobile.yangkeduo.com"

    /** 从 URL 里取参数（解 URL 编码） */
    private fun param(url: String, key: String): String {
        val m = Regex("[?&]$key=([^&]*)").find(url) ?: return ""
        return try {
            URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (_: Throwable) {
            m.groupValues[1]
        }
    }

    private fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            // ★ 关键：**一个 Cookie 都不带**，模拟"外人"（浏览器就是这么成功的）
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val gz = (conn.contentEncoding ?: "").contains("gzip", ignoreCase = true)
            val text = if (stream != null) {
                val s = if (gz) GZIPInputStream(stream) else stream
                s.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else ""
            code to text
        } catch (t: Throwable) {
            Log.w(TAG, "请求异常 ${t.message}")
            -1 to ""
        } finally {
            try { conn?.disconnect() } catch (t: Throwable) { SafeRun.w("PddNoAuthProbe", t) }
        }
    }

    fun probe(ctx: Context, shareUrl: String) {
        Thread {
            val sb = StringBuilder()
            fun say(s: String) {
                Log.i(TAG, s)
                sb.append(s).append('\n')
            }
            try {
                val shareToken = param(shareUrl, "share_token")
                val orderSn = param(shareUrl, "order_sn")
                val goodsId = param(shareUrl, "goods_id")
                say("=== PddNoAuth 探针 ===")
                say("share_token=$shareToken")
                say("order_sn=$orderSn  goods_id=$goodsId")
                if (shareToken.isEmpty() || orderSn.isEmpty()) {
                    say("!! 链接里缺 share_token / order_sn，中止")
                } else {
                    // ① 先拿 anti-content 令牌
                    val (c0, antiBody) = get("$ORIGIN/proxy/api/xg/pfb/a3", emptyMap())
                    say("")
                    say("① /xg/pfb/a3 HTTP=$c0 → ${antiBody.take(200)}")
                    val ac = Regex("\"result\"\\s*:\\s*\"([^\"]+)\"").find(antiBody)
                        ?.groupValues?.get(1).orEmpty()
                    say("   解析出 antiContent=${ac.ifEmpty { "(空)" }}")

                    // ② 免登录详情接口，逐个组合试
                    val api = "$ORIGIN/proxy/api/api/express/logistics/detail_noauth" +
                            "?pdduid=0" +
                            "&share_token=" + URLEncoder.encode(shareToken, "UTF-8") +
                            "&query_source=2&query_type=1&scene_type=1" +
                            "&order_sn=" + URLEncoder.encode(orderSn, "UTF-8") +
                            "&is_back=&ts=" + System.currentTimeMillis()

                    val ua = PddChannel.BROWSER_UA
                    val variants = LinkedHashMap<String, Map<String, String>>()
                    variants["1_裸请求(只UA)"] = mapOf("User-Agent" to ua)
                    variants["2_+Referer"] = mapOf(
                        "User-Agent" to ua, "Referer" to shareUrl
                    )
                    variants["3_+Referer+AntiContent"] = mapOf(
                        "User-Agent" to ua, "Referer" to shareUrl,
                        "Anti-Content" to ac
                    )
                    variants["4_+Referer+AntiContent+Origin"] = mapOf(
                        "User-Agent" to ua, "Referer" to shareUrl,
                        "Anti-Content" to ac, "Origin" to ORIGIN
                    )
                    variants["5_anti-content小写头"] = mapOf(
                        "User-Agent" to ua, "Referer" to shareUrl,
                        "anti-content" to ac
                    )

                    for ((name, h) in variants) {
                        val (code, body) = get(api, h)
                        say("")
                        say("②【$name】HTTP=$code len=${body.length}")
                        say("    ${body.take(600)}")
                    }

                    // ③ 附：不带 share_token（只用 order_sn）看会不会给数据
                    val api2 = "$ORIGIN/proxy/api/api/express/logistics/detail_noauth" +
                            "?pdduid=0&query_source=2&query_type=1&scene_type=1" +
                            "&order_sn=" + URLEncoder.encode(orderSn, "UTF-8") +
                            "&is_back=&ts=" + System.currentTimeMillis()
                    val (c2, b2) = get(api2, mapOf("User-Agent" to ua, "Referer" to shareUrl))
                    say("")
                    say("③【只用 order_sn，不带 share_token】HTTP=$c2 → ${b2.take(300)}")
                }
            } catch (t: Throwable) {
                say("探针异常: ${t.message}")
            }
            try {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                java.io.File(dir, "pdd_noauth.txt").writeText(sb.toString())
                Log.i(TAG, "已落盘 pdd_noauth.txt")
            } catch (t: Throwable) { SafeRun.w("PddNoAuthProbe", t) }
        }.start()
    }
}
