package com.agent.idcode

import android.util.Log

/**
 * ★★ vc619：**让"被吞掉的异常"留下证据**。
 *
 * ## 背景
 * 2026-10-31 统计：全项目有 **308 处 `catch (t: Throwable) { SafeRun.w("SafeRun", t) }`**（MainActivity 127 处）。
 * 这些地方出错时**一行日志都没有** —— 今天排查"抓不到"的每一次，最后都卡在
 * "日志是干净的，但东西没出来"，只能靠猜。这是可维护性上最大的坑。
 *
 * ## 做法
 * 把空 catch 统一换成 `catch (t: Throwable) { SafeRun.w("标签", t) }`：
 *  · 只打 **W 级**，不中断流程（原本就是"吞掉继续"的语义，行为不变）；
 *  · 带异常类型 / message / 首个堆栈位置（文件:行）；
 *  · **同一条异常 60 秒内只打一次** —— 避免探测型调用（每轮都失败）把日志刷爆。
 *
 * ## 注意
 * 这不是"改成抛异常"，只是**记录**。任何 catch 的原有控制流都保持不变。
 */
object SafeRun {

    /** 同类异常的最小重复间隔：60 秒 */
    private const val DEDUP_MS = 60_000L

    private val lastAt = HashMap<String, Long>()

    /**
     * ★ vc621：取**本 App 的第一个栈帧**（`com.agent.idcode.*`）。
     * 直接用 `stackTrace[0]` 只会拿到 `JSONTokener.java:489` 这种第三方行号，
     * 对定位我们自己的代码毫无帮助 —— 第一次上线时就被这一点坑了一次。
     */
    private fun appFrame(t: Throwable): String {
        val frames = t.stackTrace
        val mine = frames.firstOrNull { it.className.startsWith("com.agent.idcode") }
            ?: frames.firstOrNull { it.className.startsWith("android.") }
        // ★ 混淆后 fileName 会变成 "SourceFile"、行号指向合成类 ⇒ **必须带类名+方法名**才能反查
        return mine?.let {
            val cls = it.className.substringAfterLast('.').substringAfterLast('$')
            val outer = it.className.substringAfterLast('.')
            "$outer#$cls#${it.methodName}:${it.lineNumber}"
        } ?: "?"
    }

    /**
     * 记录一次被吞掉的异常。
     * @param tag 位置标签（一般用文件名/源名，如 "MainActivity"、"PddExpressChannel"）
     */
    fun w(tag: String, t: Throwable) {
        try {
            val where = appFrame(t)
            val key = tag + "|" + t.javaClass.simpleName + "|" + (t.message ?: "") + "|" + where
            val now = System.currentTimeMillis()
            synchronized(lastAt) {
                val prev = lastAt[key] ?: 0L
                if (now - prev < DEDUP_MS) return
                lastAt[key] = now
                // 防止长跑后 map 无限增长
                if (lastAt.size > 400) {
                    val cut = now - DEDUP_MS * 10
                    lastAt.entries.removeAll { it.value < cut }
                }
            }
            // ★ vc622：把"读空缓存文件"这类**已知无害**的异常降为 I 级 ——
            //   `JSONObject("")` 会抛 "End of input at character 0"，本质是缓存文件还没写，
            //   程序本来就走空逻辑。留在 W 级会把真正的异常淹掉。
            val benign = t is org.json.JSONException &&
                    (t.message ?: "").contains("End of input at character 0")
            val msg = "捕获异常：${t.javaClass.simpleName}: ${t.message ?: "(无 message)"}｜$where" +
                    if (benign) "（空输入，属正常）" else ""
            if (benign) Log.i("SafeRun/$tag", msg) else Log.w("SafeRun/$tag", msg)
        } catch (_: Throwable) {
            // 记录失败也不能影响业务
        }
    }

    /** 带上下文说明的版本：`SafeRun.w("PddNative", "取件码解析", t)` */
    fun w(tag: String, what: String, t: Throwable) {
        try {
            val where = appFrame(t)
            val key = tag + "|" + what + "|" + t.javaClass.simpleName + "|" + (t.message ?: "")
            val now = System.currentTimeMillis()
            synchronized(lastAt) {
                val prev = lastAt[key] ?: 0L
                if (now - prev < DEDUP_MS) return
                lastAt[key] = now
            }
            Log.w("SafeRun/$tag", "$what 失败：${t.javaClass.simpleName}: ${t.message ?: ""}｜$where")
        } catch (t: Throwable) { SafeRun.w("SafeRun", t) }
    }
}
