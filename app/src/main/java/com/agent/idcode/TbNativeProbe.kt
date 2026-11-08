package com.agent.idcode

import android.util.Log
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * ★ vc496：淘宝 **纯 HTTP mtop** 探针（B 路线可行性实验）。
 *
 * ## 背景
 * 淘宝现有的取件码/在途全部依赖 WebView（页面里的 `lib.mtop` 现算签名），
 * 而 WebView 的 cookie 仓库全 App 只有一个 ⇒ 多账号只能串行。
 * 拼多多之所以能并行，是因为它的接口可以裸传 cookie。
 *
 * ## 这个类要验证的事
 * mtop 的签名其实是可复算的：
 * ```
 * token = cookie 里 _m_h5_tk 的下划线前半段
 * t     = 当前毫秒
 * sign  = md5(token + "&" + t + "&" + appKey + "&" + data)
 * ```
 * 如果服务端认这个 sign（返回 `SUCCESS::调用成功`），
 * 淘宝就能像拼多多一样**多账号并行**（各带各的 cookie，互不干扰）。
 *
 * ## 用法（调试）
 * ```
 * adb shell am start -n com.agent.idcode/.MainActivity \
 *   --es tb_probe_api mtop.cainiao.cnts.lightapp.agreement.query \
 *   --es tb_probe_data '{}'
 * ```
 */
object TbNativeProbe {

    private const val TAG = "TbProbe"
    private const val APP_KEY = "12574478"
    private const val HOST = "https://h5api.m.taobao.com/h5/"

    /** 取主号 cookie（WebView jar 里的淘宝域） */
    private fun cookies(): String = try {
        val cm = CookieManager.getInstance()
        (cm.getCookie("https://h5api.m.taobao.com").orEmpty() + ";" +
                cm.getCookie("https://.taobao.com").orEmpty())
    } catch (_: Throwable) {
        ""
    }

    /** `_m_h5_tk=abcdef_1698123456789` → `abcdef` */
    private fun token(ck: String): String {
        val m = Regex("(?:^|;)\\s*_m_h5_tk=([^;_]+)_").find(ck) ?: return ""
        return m.groupValues[1]
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 直接调一个 mtop 接口。
     * @param api  形如 `mtop.cainiao.cnts.lightapp.agreement.query`
     * @param data JSON 字符串（保持调用方给的原文，签名用的就是它）
     * @return 原始响应体；失败返回 null
     */
    fun call(ctx: android.content.Context, api: String, data: String = "{}", v: String = "1.0"): String? {
        val ck = cookies()
        val tk = token(ck)
        if (tk.isEmpty()) {
            Log.w(TAG, "没有 _m_h5_tk，无法签名（先登录淘宝）")
            FileLog.log(ctx, TAG, "没有 _m_h5_tk")
            return null
        }
        val t = System.currentTimeMillis().toString()
        val sign = md5("$tk&$t&$APP_KEY&$data")
        val url = HOST + api + "/" + v + "/?" +
                "jsv=2.7.0&appKey=$APP_KEY&t=$t&sign=$sign" +
                "&api=" + URLEncoder.encode(api, "UTF-8") +
                "&v=$v&type=originaljson&dataType=json&timeout=20000" +
                "&data=" + URLEncoder.encode(data, "UTF-8")
        Log.i(TAG, "→ $api  token=${tk.take(8)}…  data=$data")
        FileLog.log(ctx, TAG, "→ $api data=$data")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Cookie", ck)
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                            "Chrome/122.0.6261.120 Mobile Safari/537.36"
                )
                setRequestProperty("Referer", "https://pages-fast.m.taobao.com/")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            Log.i(TAG, "← HTTP $code len=${body.length}")
            Log.i(TAG, "← ${body.take(600)}")
            FileLog.log(ctx, TAG, "← HTTP $code ${body.take(400)}")
            body
        } catch (t2: Throwable) {
            Log.w(TAG, "调用异常: ${t2.message}")
            FileLog.log(ctx, TAG, "调用异常: ${t2.message}")
            null
        }
    }
}
