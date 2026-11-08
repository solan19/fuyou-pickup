package com.agent.idcode

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray

/**
 * 「在页面里旁听」的通用基建 —— 京东 / 拼多多通道共用。
 *
 * ## 为什么需要它
 * 京东订单接口带 `h5st`、拼多多带 `anti_content`，都是**页面 JS 现算的签名**。
 * 自己复刻既难、又跟着对方升级天天坏。正确做法是**别碰签名**：
 * 让官方页面自己去发请求，我们只在页面上下文里把**响应原文**抄一份出来。
 *
 * ## 怎么抄
 * 钩子把响应压进 `window.name`：
 *   · 它**跨页面导航存活**（localStorage 会按域隔离、还可能被清）；
 *   · 而且**不需要 addJavascriptInterface 注册 JS 桥** —— 少一个被页面检测到的攻击面。
 * Kotlin 侧 `evaluateJavascript` 就能把它读出来。
 *
 * ## 注入时机是成败关键
 * 必须**在页面 JS 执行之前**注入（document-start）。否则首屏那几个请求
 * （京东的订单列表 JSONP 就在首屏）会在钩子装好之前发出去，永远抓不到。
 * 优先用 [WebViewCompat.addDocumentStartJavaScript]；老 WebView 不支持时退到
 * [attachFallback]，并靠"主动触发新请求"（滚动 / 点加载更多）把数据补齐。
 */
object WebHook {

    private const val TAG = "WebHook"

    /**
     * 生成钩子脚本。
     *
     * @param urlPatternJs 形如 `/proxy\/api|yangkeduo|pinduoduo/i` 的正则字面量（JS 语法）
     * @param hijackJsonp 是否额外改写 JSONP 回调（京东必须开：它的订单列表走 JSONP）
     */
    fun hookJs(urlPatternJs: String, hijackJsonp: Boolean = false): String {
        val jsonpPart = if (!hijackJsonp) "" else """
          /* 京东订单列表走 JSONP：把 callback=<随机名> 全改成我们自己的全局函数。
             注意两条路都要堵：createElement('script') 的 setAttribute，以及直接赋 .src。 */
          window.__hookcb = function(data){ try { pushCap(null, JSON.stringify(data)); } catch(e){} };
          function rewrite(v){
            if (v && /callback=/.test(String(v))) {
              return String(v).replace(/callback=[a-zA-Z0-9_]+/, 'callback=__hookcb');
            }
            return v;
          }
          var __oc = document.createElement.bind(document);
          document.createElement = function(tag){
            var el = __oc(tag);
            if (String(tag).toLowerCase() === 'script') {
              var __os = el.setAttribute.bind(el);
              el.setAttribute = function(k, v){ if (k === 'src') { v = rewrite(v); } return __os(k, v); };
            }
            return el;
          };
          try {
            var __desc = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, 'src');
            Object.defineProperty(HTMLScriptElement.prototype, 'src', {
              get: function(){ return __desc.get.call(this); },
              set: function(v){ return __desc.set.call(this, rewrite(v)); },
              configurable: true
            });
          } catch(e) {}
        """

        return """
        (function(){
          if (window.__hookInstalled) { return; }
          window.__hookInstalled = true;
          var PAT = $urlPatternJs;
          function pushCap(u, s, q){
            try {
              var arr = [];
              if (window.name && window.name.charAt(0) === '[') {
                try { arr = JSON.parse(window.name); } catch(e) { arr = []; }
              }
              // q = 请求体（有些接口光看响应反推不出参数，必须记下它自己发的 body）
              arr.push({ u: String(u||'').slice(0,300), b: String(s||''), q: String(q||'').slice(0,4000) });
              if (arr.length > 400) { arr = arr.slice(arr.length - 400); }
              window.name = JSON.stringify(arr);
            } catch(e) {}
          }
          window.__hookPush = pushCap;
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(){
            var self = this;
            var body = arguments[0];
            if (self.__u && PAT.test(String(self.__u))) {
              this.addEventListener('load', function(){
                try { pushCap(self.__u, self.responseText, body); } catch(e) {}
              });
            }
            return os.apply(this, arguments);
          };
          var of = window.fetch;
          if (of) {
            window.fetch = function(input, init){
              var url = (typeof input === 'string') ? input : ((input && input.url) || '');
              var body = (init && init.body) ? String(init.body) : '';
              var p = of.apply(this, arguments);
              if (p && p.then && PAT.test(url)) {
                p.then(function(r){
                  try { r.clone().text().then(function(t){ pushCap(url, t, body); }); } catch(e) {}
                });
              }
              return p;
            };
          }
        $jsonpPart
        })();
        """.trimIndent()
    }

    /** 当前 WebView 支不支持 document-start 注入 */
    fun supportsDocumentStart(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (_: Throwable) {
            false
        }

    /**
     * 在页面 JS 之前注入钩子。
     * @return true = 用了 document-start；false = 需要调用方走 [attachFallback]
     */
    fun attachDocumentStart(wv: WebView, js: String, allowedOrigins: Set<String>): Boolean {
        if (!supportsDocumentStart()) return false
        return try {
            WebViewCompat.addDocumentStartJavaScript(wv, js, allowedOrigins)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "document-start inject failed: $e")
            false
        }
    }

    /** 退化注入：onPageStarted 时调。时机偏晚，可能漏掉最早几个请求，靠主动触发补齐 */
    fun attachFallback(wv: WebView, js: String) {
        try {
            wv.evaluateJavascript(js, null)
        } catch (e: Throwable) {
            Log.w(TAG, "fallback inject failed: $e")
        }
    }

    /**
     * 读出并清空 `window.name` 里的捕获（读一次清一次，避免重复解析）。
     * 返回 List<Pair<url, 响应原文>>，url 可能为空串（JSONP 那条路拿不到 url）。
     */
    fun readCaptures(wv: WebView, cb: (List<Pair<String, String>>) -> Unit) {
        try {
            wv.evaluateJavascript(
                "(function(){var s=window.name||'';if(s.charAt(0)!=='['){return '';}window.name='';return s;})()"
            ) { v ->
                val out = ArrayList<Pair<String, String>>()
                try {
                    val text = unquoteJsString(v)
                    if (text.isNotBlank()) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val b = o.optString("b")
                            val q = o.optString("q")
                            // 把请求体拼在 url 后面（保持返回类型不变，免得动所有调用方）
                            val uu = if (q.isBlank()) o.optString("u") else o.optString("u") + " | BODY: " + q
                            if (b.isNotBlank()) out.add(uu to b)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "parse captures failed: ${e.message}")
                }
                cb(out)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "readCaptures failed: $e")
            cb(emptyList())
        }
    }

    /** 只读不清（调试用） */
    fun peek(wv: WebView, cb: (String) -> Unit) {
        try {
            wv.evaluateJavascript("(function(){return window.name||'';})()") { v ->
                cb(unquoteJsString(v))
            }
        } catch (_: Throwable) {
            cb("")
        }
    }

    /** evaluateJavascript 回来的是「JSON 编码过的字符串」，要剥一层引号和转义 */
    fun unquoteJsString(v: String?): String {
        if (v.isNullOrBlank() || v == "null") return ""
        var s = v.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
            .replace("\\n", "\n").replace("\\/", "/")
    }

    /** 页面通用动作：滚到底（含内层滚动容器，React 页面往往内层滚） */
    fun scrollToBottom(wv: WebView) {
        val js = """
            (function(){
              try { window.scrollTo(0, document.body ? document.body.scrollHeight : 0); } catch(e) {}
              try {
                var els = document.querySelectorAll('*');
                for (var i = 0; i < els.length; i++) {
                  var e = els[i];
                  if (e.scrollHeight > e.clientHeight + 80) { e.scrollTop = e.scrollHeight; }
                }
              } catch(e) {}
              return true;
            })()
        """.trimIndent()
        safeEval(wv, js)
    }

    /** 页面通用动作：点一个文案匹配的元素（加载更多 / 查看全部 / 全部） */
    fun tapByText(wv: WebView, texts: List<String>) {
        val arr = texts.joinToString(",") { "'" + it.replace("'", "\\'") + "'" }
        val js = """
            (function(){
              try {
                var want = [$arr];
                var els = document.querySelectorAll('div,span,button,a,li');
                for (var i = 0; i < els.length; i++) {
                  var t = (els[i].innerText || '').trim();
                  for (var j = 0; j < want.length; j++) {
                    if (t === want[j] || t.indexOf(want[j]) >= 0) {
                      try { els[i].scrollIntoView(); els[i].click(); return true; } catch(e) {}
                    }
                  }
                }
              } catch(e) {}
              return false;
            })()
        """.trimIndent()
        safeEval(wv, js)
    }

    private fun safeEval(wv: WebView, js: String) {
        try {
            wv.evaluateJavascript(js, null)
        } catch (t: Throwable) { SafeRun.w("WebHook", t) }
    }
}
