package com.agent.idcode

import android.webkit.CookieManager

/**
 * 淘宝 / 菜鸟的登录态。
 *
 * ## ★ 跟拼多多 / 京东最大的不同：**不需要 cookie 存档**
 * 第 1/3 栏是官方 H5 页面、第 4 栏是隐形 WebView 借页面调 mtop ——
 * **两者都直接吃 WebView 的 cookie jar**，登录态本来就活在 jar 里，
 * 所以这里只做两件事：
 *   ① 判断 jar 里"登没登录"（看 `unb`）；
 *   ② 汇总 jar cookie，供诊断/调试看。
 *
 * （曾经为多账号做过 cookie 快照的导出/写回，v1.13.0 随多账号功能一起去掉了。）
 */
object TbChannel {

    /**
     * 登录页。★ 用完整路径 `/login.htm`：不少站点根路径是 404 或重定向页
     * （京东就栽过一次：`plogin.m.jd.com/` 是 404，必须 `/login/login`）。
     */
    const val LOGIN_URL = "https://login.m.taobao.com/login.htm"

    /** 淘宝系域：登录态几乎都在 `.taobao.com` 上，多读几个 host 兜底 */
    private val HOSTS = listOf(
        "https://www.taobao.com",
        "https://h5api.m.taobao.com",
        "https://h5.m.taobao.com",
        "https://pages-fast.m.taobao.com",
        "https://m.taobao.com",
        "https://login.taobao.com"
    )

    /** 汇总 jar 里能看到的淘宝 cookie（同名后者覆盖前者） */
    fun jarCookie(): String {
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

    /** 淘宝数字用户 id（`unb` 就是）；返回空串 = 未登录 */
    fun uid(): String {
        val m = Regex("(?:^|;\\s*)unb=([^;]+)").find(jarCookie())
        val v = m?.groupValues?.get(1)?.trim().orEmpty()
        return if (v.isNotEmpty() && v.all { it.isDigit() }) v else ""
    }

    fun isLoggedIn(): Boolean = uid().isNotEmpty()

    /** 退出淘宝登录，只删除淘宝/菜鸟域 Cookie，不动其他 WebView 会话。 */
    fun clearLogin() {
        try {
            val cm = CookieManager.getInstance()
            val urls = listOf(
                "https://taobao.com", "https://m.taobao.com",
                "https://pages-fast.m.taobao.com", "https://h5api.m.taobao.com",
                "https://cainiao.com", "https://m.cainiao.com",
                "https://tmall.com", "https://m.tb.cn", "https://tb.cn"
            )
            val domains = listOf(".taobao.com", ".m.taobao.com", ".cainiao.com", ".tmall.com", ".tb.cn")
            val names = LinkedHashSet<String>()
            for (url in urls) {
                for (part in (cm.getCookie(url).orEmpty()).split(';')) {
                    val name = part.substringBefore('=').trim()
                    if (name.isNotEmpty()) names.add(name)
                }
            }
            for (url in urls) {
                for (name in names) {
                    for (domain in domains) {
                        cm.setCookie(url, "$name=; Max-Age=0; Domain=$domain; Path=/")
                    }
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
            cm.flush()
            android.util.Log.i("TbChannel", "清理淘宝/菜鸟 Cookie：${names.size} 个名称")
        } catch (t: Throwable) { SafeRun.w("TbChannel", t) }
    }

    /**
     * 诊断：把各 host 能看到的 cookie 名打进日志。
     * 用于排查「明明登录了却判未登录」—— 是域没覆盖？是 unb 不在？还是压根没登录态？
     */
    fun diag() {
        try {
            val cm = CookieManager.getInstance()
            val sb = StringBuilder()
            val all = HOSTS + listOf(
                "https://login.m.taobao.com", "https://pages-g.m.taobao.com",
                "https://main.m.taobao.com", "https://h5.m.taobao.com"
            )
            for (h in all) {
                val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
                val names = ck.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
                sb.append("\n[").append(h).append("] n=").append(names.size)
                sb.append("  ").append(names.joinToString(","))
            }
            android.util.Log.i("TbChannel", "cookie diag:$sb")
        } catch (t: Throwable) { SafeRun.w("TbChannel", t) }
    }
}
