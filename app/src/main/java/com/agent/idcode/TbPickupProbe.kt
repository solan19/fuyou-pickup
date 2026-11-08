package com.agent.idcode

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * ★ vc308：淘宝侧取件码 —— 侦察探针（先探路，再生产化）。
 *
 * 同志 2026-10-18 指路：淘宝 App「我的快递 · 校园」页能查看**真实取件码**，
 * 两个入口：① 手机号查询 ② 输入快递单号查询。
 * 门牌号（从 `dumpsys activity activities` 的 intent 抄到，真机实证）：
 *   pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home
 * 注意它和菜鸟源（CaiNiaoChannel 宿主页 end-collect-platform）是**同一套 last-mile-fe 前端**，
 * 但「m-end-school-tab」是校园场景 tab —— 数据源可能覆盖学校自建驿站（菜鸟接口查不到的件）。
 *
 * 本探针：自家隐形 WebView 直开该页 + document-start 钩 XHR（WebHook 现成基建），
 * 落盘 innerText + 捕获的接口响应，先回答三个问题：
 *   ① 页面在第三方 WebView 里会不会降级/要登录？
 *   ② 它自己调哪个接口拿包裹（接口名 + 参数）？
 *   ③ 返回里有没有 pickupCode（格口码）？
 */
object TbPickupProbe {

    private const val TAG = "TbPickupProbe"
    private val main = Handler(Looper.getMainLooper())

    /** 同志手机上抄到的校园页门牌号（03:40 实证，保留关键参数照抄） */
    const val SCHOOL_URL =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home" +
                "?x-ssr=true&ssr=true&forceThemis=true&skeleton=true" +
                "&miniappSourceChannel=homepage&source=ST_HOME_ICON&subSource=HOME_ICON"

    /**
     * 探针 v2：直开校园页 → 5 秒后**模拟输入单号并触发查询**（v1 实证：页面在自家 WebView
     * 里正常渲染、lib-mtop 在、登录态有效；但列表页不主动查就没数据）→ 钩出查询接口。
     * 同时带回页面加载的全部 JS bundle（接口名若没钩到，下一个手段是扒 bundle 静态搜）。
     */
    fun probeSchoolQuery(act: Activity, waybill: String) {
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            val hook = WebHook.hookJs("/mtop|cainiao|last-mile|h5api|pickup|express|package/i", hijackJsonp = true)
            val ok = WebHook.attachDocumentStart(wv, hook, setOf("https://pages-fast.m.taobao.com"))
            Log.i(TAG, "校园查询探针启动 document-start=$ok waybill=$waybill")
            var reads = 0
            var done = false
            fun snapshot(final: Boolean) {
                if (done) return
                reads++
                wv.evaluateJavascript(
                    "(function(){var t=document.body?document.body.innerText:'';" +
                            "var c='';try{c=window.name||'';}catch(e){}" +
                            "return JSON.stringify({len:t.length,cap:c.length," +
                            "text:t.slice(0,6000),capData:c.slice(0,400000)});})()"
                ) { r ->
                    val s = WebHook.unquoteJsString(r)
                    Log.i(TAG, "校园查询读取#$reads len=${s.length}")
                    if (final && !done) {
                        done = true
                        try {
                            File(act.getExternalFilesDir(null), "tb_school_query.txt").writeText(s)
                            Log.i(TAG, "校园查询落盘 tb_school_query.txt len=${s.length}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "落盘失败: $e")
                        }
                        try {
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
                    }
                }
            }
            // 填单号 + 触发查询 + 带回 JS bundle 列表（JS 内零注释 —— 项目铁律）
            val wb = waybill.replace("'", "")
            val js = "(function(){var out={};try{out.res=performance.getEntriesByType('resource')" +
                    ".map(function(e){return e.name}).filter(function(n){return /\\.js(\\?|$)/.test(n)}).slice(0,40);}" +
                    "catch(e){out.res=[];}" +
                    "var inp=null;var all=document.querySelectorAll('input,textarea');" +
                    "for(var i=0;i<all.length;i++){var el=all[i];var ph=(el.placeholder||'')+' '+(el.getAttribute('aria-label')||'');" +
                    "if(/单号|快递|包裹/.test(ph)||el.type==='search'){inp=el;break;}}" +
                    "out.inputFound=!!inp;out.phs=[];" +
                    "for(var k=0;k<all.length;k++){out.phs.push((all[k].placeholder||'')+'|'+(all[k].type||''));}" +
                    "if(inp){try{inp.focus();}catch(e){}" +
                    "try{var st=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;st.call(inp,'" + wb + "');}" +
                    "catch(e){inp.value='" + wb + "';}" +
                    "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,which:13,bubbles:true}));" +
                    "inp.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',keyCode:13,which:13,bubbles:true}));" +
                    "out.filled=inp.value;" +
                    "var bs=document.querySelectorAll('button,a,div,span');" +
                    "for(var j=0;j<bs.length;j++){var tx=(bs[j].innerText||'').trim();" +
                    "if(tx==='查询'||tx==='搜索'||tx==='立即查询'){try{bs[j].click();out.btn=tx;}catch(e){}break;}}}" +
                    "return JSON.stringify(out);})()"
            var injected = false
            fun injectFill() {
                if (done || injected) return
                injected = true
                wv.evaluateJavascript(js) { r -> Log.i(TAG, "填单号结果: ${WebHook.unquoteJsString(r).take(600)}") }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "校园查询页加载完成：${url?.take(140)}")
                    main.postDelayed({ injectFill() }, 5000)
                    main.postDelayed({ snapshot(false) }, 9000)
                    main.postDelayed({ snapshot(false) }, 13000)
                    main.postDelayed({ snapshot(true) }, 20000)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
            wv.loadUrl(SCHOOL_URL)
            main.postDelayed({ snapshot(true) }, 30000)
        }
    }

    /**
     * ★ 探针 v4（决定性）：mtop 走 **JSONP**，参数在 script src 里 —— v2 的 WebHook 只能
     * 收到响应体、收不到请求参数（捕获里 `u` 全空）。所以这里换手法：
     *   **直接把 `lib.mtop.request` 包一层记录器**，把每次请求的 api + data 原文写进
     *   `sessionStorage.__reqs`（跨页面导航存活）。
     * 然后模拟输入单号 + 回车 + 点搜索，看它到底调哪个接口、参数叫什么名字。
     * 同时带回：输入框结构与父级 HTML（判断是真 input 还是假 div）、页面 JS 包地址（下一步扒 bundle）。
     */
    fun probeSchoolV4(act: Activity, waybill: String) {
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            val hook = WebHook.hookJs("/mtop|cainiao|h5api|pickup|school/i", hijackJsonp = true)
            val ok = WebHook.attachDocumentStart(wv, hook, setOf("https://pages-fast.m.taobao.com"))
            Log.i(TAG, "v4 启动 document-start=$ok waybill=$waybill")
            var done = false
            val wb = waybill.replace("\\", "").replace("'", "").replace("\"", "")
            // 记录器 + 填单号 + 触发（JS 零注释；不用模板字符串，避免 $ 冲突）
            val act1 = "(function(){" +
                    "if(window.__recInstalled){return 'ALREADY';}" +
                    "if(!(window.lib&&window.lib.mtop)){return 'NO_LIB';}" +
                    "window.__recInstalled=true;" +
                    "var o=window.lib.mtop.request;" +
                    "window.lib.mtop.request=function(a){" +
                    "try{var arr=[];try{arr=JSON.parse(sessionStorage.getItem('__reqs')||'[]');}catch(e){arr=[];}" +
                    "arr.push({api:(a&&a.api)||'',v:(a&&a.v)||'',t:(a&&a.type)||'',data:JSON.stringify((a&&a.data)||{})});" +
                    "if(arr.length>200){arr=arr.slice(arr.length-200);}" +
                    "sessionStorage.setItem('__reqs',JSON.stringify(arr));}catch(e){}" +
                    "return o.apply(this,arguments);};" +
                    "var out={rec:'OK',wb:''};" +
                    "var inp=null;var all=document.querySelectorAll('input,textarea');" +
                    "for(var i=0;i<all.length;i++){var ph=(all[i].placeholder||'')+' '+(all[i].getAttribute('aria-label')||'');" +
                    "if(/单号|快递|包裹/.test(ph)){inp=all[i];break;}}" +
                    "out.inputs=all.length;out.found=!!inp;" +
                    "if(inp){try{inp.click();inp.focus();}catch(e){}" +
                    "try{var p=Object.getPrototypeOf(inp);var st=Object.getOwnPropertyDescriptor(p,'value').set;st.call(inp,'" + wb + "');}" +
                    "catch(e){try{inp.value='" + wb + "';}catch(e2){}}" +
                    "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));" +
                    "inp.dispatchEvent(new KeyboardEvent('keypress',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));" +
                    "inp.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));" +
                    "try{out.wb=inp.value||'';}catch(e){}" +
                    "var hit='';var bs=document.querySelectorAll('button,a,div,span,i,svg');" +
                    "for(var j=0;j<bs.length;j++){var tx=(bs[j].innerText||'').trim();" +
                    "var ar=bs[j].getAttribute&&(bs[j].getAttribute('aria-label')||'');" +
                    "if(tx==='查询'||tx==='搜索'||tx==='立即查询'||/查询|搜索/.test(ar||'')){try{bs[j].click();hit=tx||ar;}catch(e){}if(hit){break;}}}}" +
                    "out.hit=hit||'';" +
                    "return JSON.stringify(out);})()"
            var injected = false
            var injectCount = 0
            fun inject() {
                if (done || injected) return
                if (injectCount >= 3) return
                injected = true
                injectCount++
                wv.evaluateJavascript(act1) { r ->
                    Log.i(TAG, "v4 记录器+填单号($injectCount): ${WebHook.unquoteJsString(r).take(500)}")
                }
            }
            fun snapshot(final: Boolean) {
                if (done) return
                val js = "(function(){var o={};" +
                        "try{o.reqs=JSON.parse(sessionStorage.getItem('__reqs')||'[]');}catch(e){o.reqs=[];}" +
                        "try{o.cap=(window.name||'').slice(0,300000);}catch(e){o.cap='';}" +
                        "try{o.text=document.body?document.body.innerText.slice(0,3000):'';}catch(e){o.text='';}" +
                        "try{o.url=location.href;}catch(e){o.url='';}" +
                        "try{o.inputs=[];var a=document.querySelectorAll('input,textarea');" +
                        "for(var i=0;i<a.length;i++){var pn=a[i].parentNode;var gp=pn&&pn.parentNode;" +
                        "o.inputs.push({ph:a[i].placeholder||'',tp:a[i].type||'',val:a[i].value||''," +
                        "html:(gp?gp.outerHTML:'').slice(0,1500)});}}catch(e){}" +
                        "try{o.res=performance.getEntriesByType('resource').map(function(e){return e.name})" +
                        ".filter(function(n){return n.indexOf('.js')>0}).slice(0,40);}catch(e){o.res=[];}" +
                        "return JSON.stringify(o);})()"
                wv.evaluateJavascript(js) { r ->
                    val s = WebHook.unquoteJsString(r)
                    Log.i(TAG, "v4 读取 final=$final len=${s.length}")
                    if (final && !done) {
                        done = true
                        try {
                            File(act.getExternalFilesDir(null), "tb_school_v4.txt").writeText(s)
                            Log.i(TAG, "v4 落盘 tb_school_v4.txt len=${s.length}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "v4 落盘失败: $e")
                        }
                        try {
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "v4 页面完成：${url?.take(120)}")
                    injected = false   // 换页了（如进了搜索结果页）就允许在新页再注入一次
                    main.postDelayed({ inject() }, 4000)
                    main.postDelayed({ snapshot(false) }, 12000)
                    main.postDelayed({ snapshot(true) }, 22000)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
            wv.loadUrl(SCHOOL_URL)
            main.postDelayed({ snapshot(true) }, 32000)
        }
    }

    /**
     * ★ 探针 v5（实测矩阵）：bundle 静态分析已把候选缩到 4 个接口 ——
     *   ① `mtop.cainiao.pickup.xiaoyuan.queryMultisitePkgs`（校园多站点包裹，返回
     *      `stationMessageAndPackages[].waybillPickUpResp.certainPickupWaybills`）
     *   ② `mtop.cainiao.pickup.search.linTuoSearch`（临托搜索：searchValue+unionCode+stVersion）
     *   ③ `mtop.cainiao.pickup.plus.querymultistapackages4xy`（校园首页列表）
     *   ④ `mtop.cainiao.pickup.search.queryMultiStationPackages`（现有菜鸟源，加单号过滤试试）
     * 这里一次性打出去，看谁真能返回包裹、以及返回里有没有取件码/格口号。
     */
    fun probeSchoolV5(act: Activity, waybill: String, phone: String) {
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            val wb = waybill.replace("'", "").replace("\"", "")
            val ph = phone.replace("'", "").replace("\"", "")
            var done = false
            val js = "(function(){" +
                    "if(!(window.lib&&window.lib.mtop)){window.name=JSON.stringify({err:'NO_LIB'});return;}" +
                    "var lib=window.lib.mtop;var rows=[];" +
                    "function call(tag,api,data){" +
                    "return lib.request({api:api,v:'1.0',data:data,ecode:1,dataType:'json',type:'GET',timeout:9000})" +
                    ".then(function(r){var dd=(r&&r.data!==undefined)?r.data:r;" +
                    "rows.push({tag:tag,api:api,ok:true,ret:(r&&r.ret||[]).join('|'),body:JSON.stringify(dd).slice(0,12000)});" +
                    "},function(e){rows.push({tag:tag,api:api,ok:false,ret:((e&&e.ret)||[]).join('|')," +
                    "msg:(e&&e.errorInfo&&e.errorInfo.errorMsg)||''});});}" +
                    "var P1={searchTypes:'[\"03\"]',searchValue:'" + wb + "',pageSize:50,pageNum:1,source:'ST',stationType:'XY',stVersion:'1.1'};" +
                    "var P2={searchTypes:'[\"01\"]',searchValue:'" + ph + "',pageSize:50,pageNum:1,source:'ST',stationType:'XY',stVersion:'1.1'};" +
                    "var P3={searchValue:'" + wb + "',unionCode:'',stVersion:1.1};" +
                    "var P4={searchValue:'" + wb + "',mailNo:'" + wb + "',source:'ST_SCAN',stationType:'XY',stVersion:'1.1'};" +
                    "var jobs=[" +
                    "call('xy3','mtop.cainiao.pickup.xiaoyuan.queryMultisitePkgs',P1)," +
                    "call('xy1','mtop.cainiao.pickup.xiaoyuan.queryMultisitePkgs',P2)," +
                    "call('lintuo','mtop.cainiao.pickup.search.linTuoSearch',P3)," +
                    "call('m4xy','mtop.cainiao.pickup.plus.querymultistapackages4xy',P4)," +
                    "call('multi','mtop.cainiao.pickup.search.queryMultiStationPackages',P4)" +
                    "];" +
                    "Promise.all(jobs).then(function(){window.name=JSON.stringify({rows:rows});},function(){window.name=JSON.stringify({rows:rows});});" +
                    "})()"
            fun snapshot(final: Boolean) {
                if (done) return
                wv.evaluateJavascript(
                    "(function(){var s=window.name||'';var o={url:location.href,lib:(window.lib&&window.lib.mtop)?1:0," +
                            "text:document.body?document.body.innerText.slice(0,2000):'',cap:s.slice(0,300000)};" +
                            "return JSON.stringify(o);})()"
                ) { r ->
                    val s = WebHook.unquoteJsString(r)
                    Log.i(TAG, "v5 读取 final=$final len=${s.length}")
                    if (final && !done) {
                        done = true
                        try {
                            File(act.getExternalFilesDir(null), "tb_school_v5.txt").writeText(s)
                            Log.i(TAG, "v5 落盘 tb_school_v5.txt len=${s.length}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "v5 落盘失败: $e")
                        }
                        try {
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
                    }
                }
            }
            var injected = false
            var injectCount = 0
            fun inject() {
                if (done || injected || injectCount >= 3) return
                injected = true
                injectCount++
                wv.evaluateJavascript(js) { r -> Log.i(TAG, "v5 矩阵发起($injectCount)") }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "v5 页面完成：${url?.take(120)}")
                    injected = false
                    main.postDelayed({ inject() }, 3500)
                    main.postDelayed({ snapshot(false) }, 9000)
                    main.postDelayed({ snapshot(true) }, 16000)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
            wv.loadUrl(SCHOOL_URL)
            main.postDelayed({ snapshot(true) }, 26000)
        }
    }

    /**
     * 直开校园页并钩页面自身请求，20 秒后落盘 tb_school_probe.txt（App 外部目录）。
     * 探针用（debug 开关触发），生产查码等侦察结论后再实现。
     */
    fun probeSchoolPage(act: Activity) {
        main.post {
            val d = act.resources.displayMetrics
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // 手机 UA（默认即是）；桌面 UA 会被分流到 PC 版，历史上踩过
            val hook = WebHook.hookJs("/mtop|cainiao|last-mile|h5api|pickup|express/i", hijackJsonp = true)
            val ok = WebHook.attachDocumentStart(wv, hook, setOf("https://pages-fast.m.taobao.com"))
            Log.i(TAG, "校园页探针启动 document-start=$ok")
            var reads = 0
            var done = false
            fun snapshot(final: Boolean) {
                if (done) return
                reads++
                wv.evaluateJavascript(
                    "(function(){var t=document.body?document.body.innerText:'';" +
                            "var c='';try{c=window.name||'';}catch(e){}" +
                            "var lib=(window.lib&&window.lib.mtop)?1:0;" +
                            "var mt=typeof window.mtop;" +
                            "return JSON.stringify({len:t.length,lib:lib,mtop:mt,cap:c.length," +
                            "text:t.slice(0,6000),capData:c.slice(0,300000)});})()"
                ) { r ->
                    val s = WebHook.unquoteJsString(r)
                    Log.i(TAG, "校园页读取#$reads len=${s.length}")
                    if (final && !done) {
                        done = true
                        try {
                            File(act.getExternalFilesDir(null), "tb_school_probe.txt").writeText(s)
                            Log.i(TAG, "校园页探针落盘 tb_school_probe.txt len=${s.length}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "落盘失败: $e")
                        }
                        try {
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "校园页加载完成：${url?.take(140)}")
                    main.postDelayed({ snapshot(false) }, 4000)
                    main.postDelayed({ snapshot(false) }, 9000)
                    main.postDelayed({ snapshot(true) }, 16000)
                }
            }
            try {
                (act.window?.decorView as? ViewGroup)?.addView(
                    wv, 0, ViewGroup.LayoutParams(d.widthPixels, d.heightPixels)
                )
            } catch (t: Throwable) { SafeRun.w("TbPickupProbe", t) }
            wv.loadUrl(SCHOOL_URL)
            // 兜底：页面迟迟不 finished（登录跳转/风控）也要落盘
            main.postDelayed({ snapshot(true) }, 26000)
        }
    }
}
