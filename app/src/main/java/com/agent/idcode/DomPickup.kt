package com.agent.idcode

/**
 * ★ vc613：**从页面本身抠取件数据**（DOM / SSR 兜底）。
 *
 * ## 为什么需要它
 * 2026-10-30 日志（Xiaomi 2509FPN0BC，v1.1）实证：
 * ```
 * CaiNiaoChannel: 旁听未命中（captures=0），交给自拼查询兜底
 * CaiNiaoChannel: 查询回包(224B): {"ok":true,"ret":["SUCCESS"],"data":{"data":{"allPkgSize":0,
 *                 "stationPackageAssembleList":[],…}}}
 * ```
 * 驿站首页（`end-collect-platform/home-ssr-index`）现在是 **SSR 直出**：
 * 数据在服务端渲染进 HTML，页面自己**不再发** `mtop.cainiao.pickup.*` 的 XHR
 * ⇒ 按 URL 旁听永远命中不了；而自拼查询只带 `{source,traceId}`，
 * 服务端按"空站点上下文"返回 `allPkgSize:0`（代码注释里早有这条真机结论）。
 *
 * ## 做法
 * 1. 先看几个常见的 SSR 全局对象（`__INITIAL_STATE__` / `__NEXT_DATA__` …），
 *    `JSON.stringify` 后找 `stationPackageAssembleList`；
 * 2. 拿不到就取 `document.documentElement.outerHTML`，从 HTML 里**反向配平 JSON 括号**，
 *    把包含该字段的对象整段抠出来。
 *
 * 抠出来的片段交给原有的 `parseRawResponse` / `handlePackagesResult` 解析，**不另写解析器**。
 */
object DomPickup {

    /** 取页面数据：优先 SSR 全局，退化为整页 HTML */
    const val GRAB_JS = """(function(){try{
var names=['__INITIAL_STATE__','__NEXT_DATA__','__NUXT__','__SSR_DATA__','__PAGE_DATA__'];
for(var i=0;i<names.length;i++){var v=window[names[i]];
if(v){try{var s=JSON.stringify(v);if(s&&s.indexOf('stationPackageAssembleList')>=0)return s;}catch(e){}}}
return document.documentElement.outerHTML||'';
}catch(e){return '';}})()"""

    /** HTML 实体 / JS 转义还原（内联 JSON 常见 `&quot;`、`\u002F`、`\"`） */
    fun unescape(s: String): String {
        var t = s
        t = t.replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // \u002F 这类（JSON 里被转义的斜杠/中文）
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { m ->
            try {
                m.groupValues[1].toInt(16).toChar().toString()
            } catch (_: Throwable) {
                m.value
            }
        }
        // \" → "
        t = t.replace("\\\"", "\"")
        return t
    }

    /**
     * 在任意文本里找出**包含 [key] 的那个 JSON 对象**。
     *
     * 做法：从 key 出现的位置往前找最近的 `{`，再从那里做括号配平（跳过字符串内的括号），
     * 配平成功就返回这一段。配平失败就继续找下一个 key 出现的位置。
     */
    fun extractEnclosingJson(text: String, key: String): String? {
        if (text.isEmpty()) return null
        var from = 0
        while (true) {
            val idx = text.indexOf(key, from)
            if (idx < 0) return null
            // 往前找最近的 '{'
            var start = -1
            var i = idx - 1
            while (i >= 0) {
                if (text[i] == '{') {
                    start = i
                    break
                }
                i--
            }
            if (start >= 0) {
                val end = matchBrace(text, start)
                if (end > start) {
                    val snippet = text.substring(start, end + 1)
                    // 至少得像个对象，且包含目标字段
                    if (snippet.length > 2 && snippet.contains(key)) return snippet
                }
            }
            from = idx + key.length
        }
    }

    /** 从 [start]（必须是 `{`）开始做括号配平，返回与之匹配的 `}` 下标；失败返回 -1 */
    private fun matchBrace(text: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }
}
