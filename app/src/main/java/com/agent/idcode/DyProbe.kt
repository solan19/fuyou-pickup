package com.agent.idcode

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ★★ vc711：**抖音接口调研页**（只给调研版用）。
 *
 * 进入方式（界面上没有入口，避免污染正式版）：
 *   adb shell am start -n com.agent.idcode.probe/com.agent.idcode.MainActivity --ez debug_dy_probe true
 *
 * 它做什么：
 *  1. 一个**可见** WebView，让用户在应用内登录抖音、点进订单/物流页；
 *  2. 注入 JS 钩住 fetch 与 XMLHttpRequest，凡 URL 命中订单/物流关键词的响应，
 *     把 URL + 响应正文回给原生（[DyProbe] 日志 + 页面底部实时清单）；
 *  3. 只记订单/物流相关路径，其余请求一律不记。
 *
 * 为什么用 JS 钩而不是自己构造请求：抖音电商接口带 X-Bogus/X-Gorgon 签名，
 * 自造请求既不可能也不合规矩（本软件的原则是"只旁听用户自己的登录态"）。
 */
object DyProbe {

    private const val TAG = "DyProbe"

    /** 只记这些路径；其余请求不采样 */
    private const val KEYWORDS =
        "order/list|order/detail|logistics|express|trace|package|station|取件|logistic|delivery|order_list|orderlist|/order/|logisticsInfo"

    private const val HOOK_JS = """
(function(){
  if (window.__dyHooked) return; window.__dyHooked = 1;
  var K = /$KEYWORDS/i;
  function send(u, b) {
    try { window.dyProbe.onHit(String(u).slice(0,300), String(b).slice(0,4000)); } catch (e) {}
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function() {
      var a = arguments;
      var u = (a[0] && a[0].url) ? a[0].url : a[0];
      return of.apply(this, a).then(function(r) {
        try { if (K.test(u)) r.clone().text().then(function(t){ send(u, t); }); } catch (e) {}
        return r;
      });
    };
  }
  var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u) { this.__dyU = u; return oo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function() {
    var self = this;
    self.addEventListener('load', function() {
      try { if (self.__dyU && K.test(self.__dyU)) send(self.__dyU, self.responseText); } catch (e) {}
    });
    return os.apply(this, arguments);
  };
})();
"""

    fun open(act: Activity, root: ViewGroup) {
        val hits = ArrayList<String>()
        val layer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            isClickable = true
        }
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(act).apply {
            text = "抖音接口调研（命中会写到日志 tag DyProbe）"
            textSize = 14f
            setPadding(24, 24, 24, 8)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = Button(act).apply { text = "关闭" }
        header.addView(close)
        layer.addView(header)

        val hitTv = TextView(act).apply {
            textSize = 11f
            setPadding(24, 0, 24, 8)
            text = "还没有命中订单/物流接口…"
        }
        layer.addView(hitTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val urlIn = EditText(act).apply {
            hint = "地址（可贴抖音订单/物流链接）"
            setSingleLine(true)
            setText("https://haohuo.jinritemai.com/")
        }
        val openBtn = Button(act).apply { text = "打开" }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(urlIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(openBtn)
        layer.addView(row)

        val web = WebView(act).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onHit(url: String, body: String) {
                    Log.i(TAG, "命中 $url｜${body.take(1500)}")
                    act.runOnUiThread {
                        hits.add(url + "\n  " + body.take(160))
                        while (hits.size > 12) hits.removeAt(0)
                        hitTv.text = hits.joinToString("\n")
                    }
                }
            }, "dyProbe")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(HOOK_JS, null)
                }
            }
            loadUrl(urlIn.text.toString())
        }
        layer.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        openBtn.setOnClickListener { web.loadUrl(urlIn.text.toString()) }
        close.setOnClickListener { root.removeView(layer) }
        root.addView(layer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        Log.i(TAG, "调研页已打开，起始地址=${urlIn.text}")
    }
}
