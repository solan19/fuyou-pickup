package com.agent.idcode

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * vc269：淘宝 H5 链路隐形探测器（debug 专用，不进生产路径）。
 *
 * 背景（2026-10-15 实测）：
 * · H5 订单列表 h5.m.taobao.com/mlapp/olist.html 在本 App WebView 里登录态直接可用；
 * · 「查看物流」跳 pages-g.m.taobao.com/.../h5-detail?x-ssr=true&bizOrderId=<订单号>，
 *   SSR 渲染，本 App WebView 完整显示（地图/运单号/全轨迹）。
 * 本探测器摸清两件事，给「第5栏淘宝源」铺路：
 * ① olist 页背后调的 mtop 接口名 + 参数 + 响应原文（拿在途订单 bizOrderId/运单号/状态）；
 * ② h5-detail 页轨迹数据藏哪（SSR blob 还是 DOM 文本），决定抽取写法。
 *
 * ★ 旁听用项目统一的 WebHook（document-start 注入 + window.name 收集）——
 *   第一版用 onPageFinished 才挂钩子 + 自带 JS 桥，结果：钩晚了啥都抓不到
 *   （订单数据在 onPageFinished 之前就拉完了）、详情页脚本里 // 注释在单行化后被吃。
 *   两个坑都在第一版里踩过，别再犯。
 *
 * 产物落 getExternalFilesDir(null)/tb_olist_dump.txt、tb_detail_dump.txt（adb 可拉）。
 */
object TbTransitProbe {
    private const val TAG = "TbProbe"
    private val main = Handler(Looper.getMainLooper())

    private fun dump(ctx: Context, name: String, s: String) {
        try {
            val f = File(ctx.getExternalFilesDir(null), name)
            f.appendText(s + "\n----\n")
            Log.i(TAG, "$name +${s.length} 字符")
        } catch (t: Throwable) {
            Log.w(TAG, "落盘失败：${t.message}")
        }
    }

    /** ① 旁听 olist 页：document-start 钩 mtop 网关（XHR+fetch+JSONP 全覆盖），20 秒后收 */
    @SuppressLint("SetJavaScriptEnabled")
    fun probeOlist(activity: MainActivity, root: android.view.ViewGroup) {
        val ctx = activity
        main.post {
            var wv: WebView? = null
            try {
                wv = WebView(ctx)
                val v = wv!!
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                // ★ 禁缓存：此前跑过几轮，页面/数据被 HTTP 缓存或 SW 缓存接管后
                //   一条网络请求都不发（20:20 实测：代理开着却零请求）。探测必须走真网络。
                v.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                // ★ mlapp 老页面的订单数据大概率走 JSONP（script 标签），必须开 hijackJsonp
                //   （京东订单列表同款坑，见 WebHook.kt 注释）
                val js = WebHook.hookJs("/h5\\/mtop\\.|h5api\\.m\\.taobao\\.com/i", hijackJsonp = true)
                WebHook.attachDocumentStart(
                    v, js, setOf("https://h5.m.taobao.com", "https://pages-g.m.taobao.com")
                )
                // ★★ WebView 必须挂到窗口上，且**要有真实尺寸**：1x1 时页面懒加载不触发
                //   （20:33 实测 25 万字符骨架里零订单）。600x900 + 透明垫底 ≈ 用户无感，
                //   但页面以为自己在正常渲染（19:39 可见调试层能出订单，同一机理）。
                try {
                    root.addView(v, 0, android.widget.FrameLayout.LayoutParams(600, 900))
                    v.alpha = 0.02f
                } catch (t: Throwable) {
                    Log.w(TAG, "挂窗失败（继续隐形跑）：${t.message}")
                }
                v.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(vv: WebView, url: String) {
                        Log.i(TAG, "olist onPageFinished: $url")
                    }
                }
                // 12 秒先收第一波（首屏订单），再滚动触发「加载更多」收第二波
                main.postDelayed({
                    WebHook.readCaptures(v) { list ->
                        list.forEach { (u, b) -> dump(ctx, "tb_olist_dump.txt", "URL: $u\nBODY: $b") }
                        WebHook.scrollToBottom(v)
                    }
                }, 12000)
                // 16 秒：直接翻渲染好的 DOM——「查看物流」能带订单号跳转 ⇒ 订单号多半在页面里
                main.postDelayed({
                    v.evaluateJavascript(DOM_SCRAPE_JS) { result ->
                        dump(ctx, "tb_olist_dom.txt", result ?: "<null>")
                    }
                }, 16000)
                main.postDelayed({
                    WebHook.readCaptures(v) { list ->
                        list.forEach { (u, b) -> dump(ctx, "tb_olist_dump.txt", "URL: $u\nBODY: $b") }
                        Log.i(TAG, "olist 探测结束，销毁")
                        try { (v.parent as? android.view.ViewGroup)?.removeView(v) } catch (t: Throwable) { SafeRun.w("TbTransitProbe", t) }
                        try { v.clearCache(true) } catch (t: Throwable) { SafeRun.w("TbTransitProbe", t) }
                        try { v.destroy() } catch (t: Throwable) { SafeRun.w("TbTransitProbe", t) }
                    }
                }, 24000)
                v.loadUrl("https://h5.m.taobao.com/mlapp/olist.html")
            } catch (t: Throwable) {
                Log.w(TAG, "probeOlist 启动失败：${t.message}")
                try { wv?.destroy() } catch (t: Throwable) { SafeRun.w("TbTransitProbe", t) }
            }
        }
    }

    /** ② 旁听 h5-detail 页：dump 可见文本 + window 大对象（SSR blob）。脚本内禁用 // 注释（会被单行化吃掉） */
    @SuppressLint("SetJavaScriptEnabled")
    fun probeDetail(ctx: Context, bizOrderId: String) {
        main.post {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.addJavascriptInterface(Bridge(ctx, "detail"), "TbProbeBridge")
                val url = "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail" +
                    "?x-ssr=true&bizOrderId=$bizOrderId&viewLogisticDetail=true"
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        Log.i(TAG, "detail onPageFinished: $url")
                        main.postDelayed({ v.evaluateJavascript(DETAIL_JS, null) }, 4000)
                    }
                }
                main.postDelayed({
                    Log.i(TAG, "detail 探测结束，销毁")
                    try { wv.destroy() } catch (t: Throwable) { SafeRun.w("TbTransitProbe", t) }
                }, 20000)
                wv.loadUrl(url)
            } catch (t: Throwable) {
                Log.w(TAG, "probeDetail 启动失败：${t.message}")
            }
        }
    }

    private class Bridge(private val ctx: Context, private val tag: String) {
        @JavascriptInterface
        fun post(s: String?) {
            if (s.isNullOrEmpty()) return
            dump(ctx, "tb_${tag}_dump.txt", s)
        }
    }

    /** 对外暴露：正式网页层（openWeb）刮取用——debug_tb_scrape 开关走它 */
    val DOM_SCRAPE_PUBLIC: String get() = DOM_SCRAPE_JS

    // ==================== vc270 生产用 JS（第5栏淘宝源） ====================
    // ⚠️ 这些脚本会被 .replace("\n","") 压成一行 ⇒ **禁止使用 // 注释**（会吃掉后续代码）。

    /**
     * 订单收割：扫 innerHTML + window 全部大对象，正则收 bizOrderId，
     * 并截每个订单号**首次命中处 ±前后文**（里面带着订单状态文案，Kotlin 侧做在途启发式过滤）。
     * vc270b 增配商品名：结构化遍历含 bizOrderId 的节点，取同节点/路径上的
     * itemName/itemTitle/goodsName/title 字段（详情页数据里没有商品名，只能在这里配对）。
     * 返回 {ok, href, ids:[...], ctx:{id:前后文}, goods:{id:商品名}}。
     */
    val HARVEST_ORDERS_JS: String = """
        (function(){
          var ids = {}; var ctx = {}; var goods = {}; var lastLineId = '';
          function harvest(s){
            try {
              var str = String(s); var re = /bizOrderId[^0-9]{0,8}([0-9]{12,25})/g; var m;
              while ((m = re.exec(str)) !== null) {
                var n = m[1];
                if (n.length > 10 && !ids[n]) {
                  ids[n] = 1;
                  ctx[n] = str.substring(Math.max(0, m.index - 2500), Math.min(str.length, m.index + 2500));
                }
              }
            } catch(e) {}
          }
          function pairWalk(o){
            if (!o || typeof o !== 'object') return;
            if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) pairWalk(o[i]); return; }
            var ol = o.orderLineId;
            if (typeof ol === 'string' && ol.length > 10) lastLineId = ol;
            var t = o.title;
            if (typeof t === 'string' && t.length >= 4 && t.length <= 80 &&
                lastLineId && !goods[lastLineId] && (o.skuText || o.pic || o.priceInfo)) {
              goods[lastLineId] = t;
            }
            var id = o.bizOrderId;
            if (typeof id === 'string' && id.length > 10 && !goods[id]) {
              var names = ['itemName', 'itemTitle', 'goodsName'];
              for (var j = 0; j < names.length; j++) {
                var v = o[names[j]];
                if (typeof v === 'string' && v.length >= 4 && v.length <= 60) { goods[id] = v; break; }
              }
            }
            for (var k in o) { try { if (o.hasOwnProperty(k)) pairWalk(o[k]); } catch(e) {} }
          }
          var href = location.href;
          harvest(document.documentElement.innerHTML);
          for (var k in window) {
            try {
              var v = window[k];
              if (v && typeof v === 'object' && k.indexOf('webkit') !== 0) {
                var s = JSON.stringify(v);
                if (s && s.length > 1000) {
                  harvest(s);
                  if (s.indexOf('bizOrderId') > -1) { try { pairWalk(JSON.parse(s)); } catch(e) {} }
                }
              }
            } catch(e) {}
          }
      return JSON.stringify({ ok: true, href: href, ids: Object.keys(ids), ctx: ctx, goods: goods });
    })()
    """.trimIndent().replace("\n", "")

    /**
     * vc275：**便宜快路**订单收割 —— 只刮页面数据 blob（`window.currentUltronData` /
     * `window.__ICE_SUSPENSE_LOADER__`，olist 页各 ~150KB，stringify+正则几十毫秒）。
     *
     * 为什么加它（10-16 真机实证）：olist 是 **SSR 页** —— 订单数据直出在页面里，
     * **根本不发可钩子的 XHR**（探针钩子只抓到 getUserSimple/百川配置，订单接口零命中；
     * 生产 tb_order_api.txt 从未生成过）⇒ WebHook 快路对这个页面结构性失效。
     * 旧代码白等 4/6/8/10/12s 五轮钩子，才跑一次全量刮（innerHTML 830KB + 所有 window
     * 对象）—— 02:23 真机日志实证整轮 **12.1s** 才拿到订单号。
     * 数据 blob 在页面 DOM 解析后就有（~1.5~3s）⇒ 1.5s 起每 1s 刮一次，整链 ~3s。
     *
     * 返回结构与 [HARVEST_ORDERS_JS] 完全一致（{ok, href, ids, ctx, goods}）——
     * 刮不到就返回空 ids，调用方还有全量刮兜底，行为不会比旧版差。
     */
    val HARVEST_ULTRON_JS: String = """
        (function(){
          var ids = {}; var ctx = {}; var goods = {}; var lastLineId = '';
          function harvest(s){
            try {
              var str = String(s); var re = /bizOrderId[^0-9]{0,8}([0-9]{12,25})/g; var m;
              while ((m = re.exec(str)) !== null) {
                var n = m[1];
                if (n.length > 10 && !ids[n]) {
                  ids[n] = 1;
                  ctx[n] = str.substring(Math.max(0, m.index - 2500), Math.min(str.length, m.index + 2500));
                }
              }
            } catch(e) {}
          }
          function pairWalk(o){
            if (!o || typeof o !== 'object') return;
            if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) pairWalk(o[i]); return; }
            var ol = o.orderLineId;
            if (typeof ol === 'string' && ol.length > 10) lastLineId = ol;
            var t = o.title;
            if (typeof t === 'string' && t.length >= 4 && t.length <= 80 &&
                lastLineId && !goods[lastLineId] && (o.skuText || o.pic || o.priceInfo)) {
              goods[lastLineId] = t;
            }
            var id = o.bizOrderId;
            if (typeof id === 'string' && id.length > 10 && !goods[id]) {
              var names = ['itemName', 'itemTitle', 'goodsName'];
              for (var j = 0; j < names.length; j++) {
                var v = o[names[j]];
                if (typeof v === 'string' && v.length >= 4 && v.length <= 60) { goods[id] = v; break; }
              }
            }
            for (var k in o) { try { if (o.hasOwnProperty(k)) pairWalk(o[k]); } catch(e) {} }
          }
          var keys = ['currentUltronData', '__ICE_SUSPENSE_LOADER__'];
          for (var i = 0; i < keys.length; i++) {
            try {
              var v = window[keys[i]];
              if (!v || typeof v !== 'object') continue;
              var s = JSON.stringify(v);
              if (!s || s.length < 100) continue;
              harvest(s);
              if (s.indexOf('bizOrderId') > -1) { try { pairWalk(JSON.parse(s)); } catch(e) {} }
            } catch(e) {}
          }
          return JSON.stringify({ ok: true, href: location.href, ids: Object.keys(ids), ctx: ctx, goods: goods });
        })()
        """.trimIndent().replace("\n", "")

    /**
     * 轨迹抽取（h5-detail 页）—— vc270b 改为**结构化遍历**（正则版被真实数据的
     * 不规则嵌套/键序打爆：4 节点只命中 2）：
     * 解析含轨迹的 window 大对象 → 递归找「同时带 subtitle+title」的节点
     * （labelDesc.text 是文案）。运单号/商品名另抠。终态过滤在 Kotlin 侧做。
     * 返回 {ok, traces:[{time,status,text}...], waybill, goods, pickCode, blobLen}
     * （vc301：pickCode 为详情页 SSR blob 里的取件码 —— 键名白名单
     *   pickupCode/pickCode/takeCode/mailPickCode/pickupMailCode 直抠；同一键可能出两种码：
     *   格口码（XX-X-XXXX，驿站货架标签/广播用）优先，官方短码（极兔=单号后5位）兜底，可空）。
     * ⚠️ 本串会被 `.replace("\n","")` 压成单行 ⇒ **JS 内严禁 `//` 行注释**
     *   （压行后注释会吞掉其后所有代码，2026-10-17 实测整段脚本报废 → 详情抽取全空）。
     */
    val DETAIL_EXTRACT_JS: String = """
        (function(){
          var traces = [];
          var seen = 0;
          function walk(o){
            if (!o || typeof o !== 'object' || seen > 20000) return;
            seen++;
            if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walk(o[i]); return; }
            var sub = o.subtitle, tit = o.title;
            if (typeof sub === 'string' && typeof tit === 'string' && /^\d{2}-\d{2} \d{2}:\d{2}/.test(sub)) {
              var txt = '';
              var ld = o.labelDesc;
              if (ld && typeof ld === 'object' && typeof ld.text === 'string') txt = ld.text;
              if (!txt && typeof o.text === 'string') txt = o.text;
              if (traces.length < 30) traces.push({ time: sub, status: tit, text: txt });
              return;
            }
            for (var k in o) {
              try { if (o.hasOwnProperty(k)) walk(o[k]); } catch(e) {}
            }
          }
          var blobLen = 0;
          try {
            if (window.__ICE_SUSPENSE_LOADER__) {
              var b = JSON.stringify(window.__ICE_SUSPENSE_LOADER__);
              blobLen = b.length;
              walk(window.__ICE_SUSPENSE_LOADER__);
            }
          } catch(e) {}
          if (!traces.length) {
            for (var k in window) {
              try {
                var v = window[k];
                if (v && typeof v === 'object') {
                  var s = JSON.stringify(v);
                  if (s && s.indexOf('labelDesc') > -1 && s.indexOf('subtitle') > -1) {
                    blobLen = s.length;
                    walk(JSON.parse(s));
                    if (traces.length) break;
                  }
                }
              } catch(e) {}
            }
          }
          var waybill = '';
          try {
            var all = JSON.stringify(window.__ICE_SUSPENSE_LOADER__ || '').match(/[A-Z]{2}[0-9]{12,16}/g) || [];
            var okRe = /^(JT|SF|YT|YD|ZT|STO|ST|JD|EMS|POST|DBL|DB)/;
            for (var i = 0; i < all.length; i++) { if (okRe.test(all[i])) { waybill = all[i]; break; } }
          } catch(e) {}
          var goods = '';
          try {
            var g = (JSON.stringify(window.__ICE_SUSPENSE_LOADER__ || '').match(/\x22itemName\x22:\x22([^\x22]{2,50})\x22/));
            if (g) goods = g[1];
          } catch(e) {}
          var pickCode = '';
          try {
            var b2 = JSON.stringify(window.__ICE_SUSPENSE_LOADER__ || '');
            var re2 = /\x22(?:pickupCode|pickCode|takeCode|mailPickCode|pickupMailCode)\x22:\x22([0-9A-Za-z][0-9A-Za-z\-]{1,15})\x22/g;
            var m2; var boxed = ''; var plain = '';
            while ((m2 = re2.exec(b2)) !== null) {
              var v2 = m2[1];
              if (!boxed && v2.indexOf('-') > 0) boxed = v2;
              if (!plain && v2.indexOf('-') < 0) plain = v2;
            }
            pickCode = boxed || plain;
          } catch(e) {}
          var stationName = '';
          try {
            var b3 = JSON.stringify(window.__ICE_SUSPENSE_LOADER__ || '');
            var m3 = b3.match(/\x22(?:stationName|pointName|siteName|stationAliasName|branchName)\x22:\x22([^\x22]{2,40})\x22/);
            if (m3) stationName = m3[1];
          } catch(e) {}
          var stsCode = '', stsDesc = '', stsSeq = '';
          try {
            var b4 = JSON.stringify(window.__ICE_SUSPENSE_LOADER__ || '');
            var m4 = b4.match(/\x22logisticsPackageStatus\x22:\{[^}]*\}/);
            if (m4) {
              var c4 = m4[0].match(/\x22statusCode\x22:\x22([^\x22]+)\x22/); if (c4) stsCode = c4[1];
              var d4 = m4[0].match(/\x22statusDesc\x22:\x22([^\x22]+)\x22/); if (d4) stsDesc = d4[1];
              var q4 = m4[0].match(/\x22statusSeq\x22:\x22?([0-9]+)\x22?/); if (q4) stsSeq = q4[1];
            }
          } catch(e) {}
          return JSON.stringify({ ok: true, traces: traces, waybill: waybill, goods: goods, pickCode: pickCode, stationName: stationName, blobLen: blobLen, stsCode: stsCode, stsDesc: stsDesc, stsSeq: stsSeq });
        })()
    """.trimIndent().replace("\n", "")
    private val DOM_SCRAPE_JS = """
        (function(){
          var html = document.documentElement.innerHTML;
          var ids = {};
          function harvest(s){
            try {
              var m = String(s).match(/bizOrderId[^0-9]{0,8}([0-9]{12,25})/g) || [];
              m.forEach(function(x){ var n = x.replace(/[^0-9]/g,''); if(n.length > 10) ids[n] = 1; });
            } catch(e) {}
          }
          harvest(html);
          var winKeys = [];
          for (var k in window) {
            try {
              var v = window[k];
              if (v && typeof v === 'object' && k.indexOf('webkit') !== 0) {
                var s = JSON.stringify(v);
                if (s && s.length > 1000) {
                  winKeys.push({k: k, len: s.length});
                  harvest(s);
                }
              }
            } catch(e) {}
          }
          var wb = html.match(/[A-Z]{2}[0-9]{12,16}/g) || [];
          var wbs = {};
          wb.forEach(function(x){ wbs[x] = 1; });
          var out = {kind:'domIds', htmlLen: html.length,
            bizOrderIds: Object.keys(ids), waybills: Object.keys(wbs).slice(0, 20),
            winKeys: winKeys};
          try {
            var btns = [];
            var all = document.querySelectorAll('div,span,a');
            for (var i = 0; i < all.length; i++) {
              var t = (all[i].innerText || '').trim();
              if (t === '查看物流' || t === '查询物流') { btns.push(all[i].outerHTML); if (btns.length >= 2) break; }
            }
            out.logisticsBtns = btns;
          } catch(e) {}
          return JSON.stringify(out);
        })()
    """.trimIndent().replace("\n", "")

    /** h5-detail 抽取探针：可见文本 + window 大对象名 + 命中的 SSR blob 原文 */
    private val DETAIL_JS = """
        (function(){
          function post(s){ try{ TbProbeBridge.post(s); }catch(e){} }
          var out = {kind:'detailText', href: location.href, title: document.title,
            text: document.body ? document.body.innerText.substring(0, 8000) : ''};
          post(JSON.stringify(out));
          var big = [];
          for (var k in window) {
            try {
              var v = window[k];
              if (v && typeof v === 'object' && k.indexOf('webkit') !== 0) {
                var s = JSON.stringify(v);
                if (s && s.length > 1500) big.push({k: k, len: s.length});
              }
            } catch(e){}
          }
          post(JSON.stringify({kind:'bigWinKeys', list: big}));
          big.forEach(function(b){
            try {
              var s = JSON.stringify(window[b.k]);
              post(JSON.stringify({kind:'winBlob', k: b.k, sample: s.substring(0, 30000)}));
            } catch(e){}
          });
          var ssrNames = ['__INITIAL_DATA__','__INITIAL_STATE__','__NUXT__','__NEXT_DATA__','__SSR_DATA__','g_config','__appData__'];
          ssrNames.forEach(function(k){
            try {
              if (window[k]) post(JSON.stringify({kind:'ssr', k: k,
                sample: JSON.stringify(window[k]).substring(0, 30000)}));
            } catch(e){}
          });
        })()
    """.trimIndent().replace("\n", "")
}
