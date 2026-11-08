package com.agent.idcode

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ★ vc500：**文件日志系统**（替代"只读 logcat 缓冲区"）。
 *
 * ## 为什么要重做
 * 旧做法导出时读 logcat 环形缓冲区 —— 缓冲区是全设备共享的、会被别的 App 冲掉，
 * 而且按当前进程过滤（App 重启后旧进程日志就没了）。用户导出的日志因此常常只有 1~9KB。
 *
 * ## 这版的思路
 * 1) **持续把本进程 logcat 落盘**：每 4 秒 `logcat -d --pid=<我们的 pid>` 增量追加，
 *    这样 500+ 处既有的 `Log.i/w/e` 一行都不用改，也全部进文件；
 * 2) **按天切片**：`files/logs/app-YYYYMMDD.log`，单文件超 8MB 切 `-2.log`；
 * 3) **保留 7 天**：启动时清理超期文件；
 * 4) **崩溃单独落盘**：`crash-时间.log`（堆栈 + 最近日志尾部）；
 * 5) **按时间范围导出 zip**：1 小时 / 24 小时 / 3 天 / 7 天四档。
 */
object LogStore {

    private const val TAG = "LogStore"
    private const val KEEP_DAYS = 7
    private const val MAX_FILE = 8L * 1024 * 1024

    private val dfDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    private val dfTime = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val dfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Volatile private var started = false
    private var ctxRef: Context? = null
    private var worker: Thread? = null
    private val queue = ArrayBlockingQueue<String>(4000)
    @Volatile private var dropped = 0

    /** 上次读到的最后一行（用于去重，避免每次 -t 500 重复追加） */
    private var lastLineKey: String = ""

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }

    // ---------------- 启动 ----------------

    fun start(ctx: Context) {
        if (started) return
        started = true
        ctxRef = ctx.applicationContext
        cleanupOld(ctx)
        worker = Thread({ loop() }, "log-store").apply { isDaemon = true; start() }
        Log.i(TAG, "文件日志已启动 dir=${dir(ctx).absolutePath}")
    }

    /** 崩溃捕获：把异常写进 crash-*.log（系统 logcat 也会有一份，但重启后可能被冲） */
    fun installCrashHandler(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = File(dir(ctx), "crash-${dfDay.format(Date())}.log")
                f.appendText(
                    "\n================ CRASH ${dfFull.format(Date())} ================\n" +
                            "thread=${t.name}\n" + Log.getStackTraceString(e) + "\n"
                )
            } catch (t: Throwable) { SafeRun.w("LogStore", t) }
            try {
                prev?.uncaughtException(t, e)
            } catch (t: Throwable) { SafeRun.w("LogStore", t) }
        }
    }

    // ---------------- 采集循环 ----------------

    private fun loop() {
        var lastTs = ""
        while (true) {
            try {
                val pid = android.os.Process.myPid()
                val p = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-v", "threadtime", "-t", "500", "--pid=$pid")
                )
                var text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                // ★ vc501：部分机型（华为/红魔/nubia）对 `--pid=` 直接返回空 ⇒ 退回全量读 + 本地筛
                if (text.isBlank()) {
                    val p2 = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-v", "threadtime", "-t", "800")
                    )
                    text = p2.inputStream.bufferedReader().readText()
                    p2.waitFor()
                    text = text.split('\n')
                        .filter { it.contains(" $pid ") || it.contains("com.agent.idcode") }
                        .joinToString("\n")
                }
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    // 行首是 "10-28 15:00:00.123"，用整行哈希去重（同一行不会写两次）
                    val key = line.hashCode().toString() + line.take(18)
                    if (key == lastLineKey) continue
                    lastLineKey = key
                    if (!keep(line)) continue
                    append(line)
                }
                Thread.sleep(4000)
            } catch (t: Throwable) {
                try {
                    Thread.sleep(8000)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** 噪声标签（WebView 渲染层最吵，一律不要） */
    private val NOISE = arrayOf(
        "VideoCapabilities", "cr_VAUtil", "OpenGLRenderer", "HWUI", "AdrenoVK",
        "RenderInspector", "Choreographer", "libEGL", "gralloc", "SurfaceFlinger",
        "chatty", "Zygote", "ProfileInstaller", "nativeloader", "AppCompatDelegate"
    )

    /**
     * ★ vc504：日志过滤 —— 只留"有信息量"的行。
     * 不过滤的话 WebView 渲染日志能刷到 6.6MB/13 分钟（实测），既占空间也淹没真正有用的行。
     * 规则：① 我方标签（[LogTags]）全留；② 已知噪声丢；③ 其它只留 W/E 且与 chromium/ssl/网络相关。
     */
    private fun keep(line: String): Boolean {
        // ★ vc519：**先排噪声** —— 否则 pid 兜底会把 WebView 渲染日志全放进来
        //   （实测一天能涨到 40MB+，8MB 一个文件一天要切五六个）
        for (n in NOISE) if (line.contains(n)) return false
        if (LogTags.matches(line)) return true
        // ★ vc517：**本进程 pid 兜底** —— 只要这行是我们的进程打的，就留。
        //   以后新增 TAG 忘了加白名单，也不会像 vc487 那样"后台日志整段消失"。
        try {
            val myPid = android.os.Process.myPid().toString()
            if (line.length > 19 && line.startsWith("1", 0) || line.length > 19) {
                // threadtime: "MM-dd HH:mm:ss.SSS  pid  tid L Tag: msg"
                val head = line.take(32)
                if (head.contains(" $myPid ")) return true
            }
        } catch (t: Throwable) { SafeRun.w("LogStore", t) }
        val warn = line.contains(" E ") || line.contains(" W ")
        if (!warn) return false
        return line.contains("chromium") || line.contains("ssl", true) ||
                line.contains("net_error") || line.contains("WebView")
    }

    @Synchronized
    private fun append(line: String) {
        val ctx = ctxRef ?: return
        try {
            val day = dfDay.format(Date())
            var f = File(dir(ctx), "app-$day.log")
            if (f.length() > MAX_FILE) {
                f = File(dir(ctx), "app-$day-2.log")
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
            dropped++
        }
    }

    private fun cleanupOld(ctx: Context) {
        try {
            val cut = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            dir(ctx).listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cut) f.delete()
            }
            // ★ vc519：**总容量上限 60MB** —— 超了从最旧的删（防某天异常刷屏把手机塞满）
            val files = dir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                ?: return
            var total = files.sumOf { it.length() }
            val cap = 60L * 1024 * 1024
            for (f in files) {
                if (total <= cap) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (t: Throwable) { SafeRun.w("LogStore", t) }
    }

    // ---------------- 导出 ----------------

    /** 可选档位（毫秒） */
    val RANGES = listOf(
        3600_000L to "最近 1 小时",
        24 * 3600_000L to "最近 24 小时",
        3 * 86_400_000L to "最近 3 天",
        7 * 86_400_000L to "最近 7 天"
    )

    /**
     * 按时间范围打一个 zip 到 cacheDir。
     * @param snapshot 状态快照文本（由调用方生成）
     * @param device   设备信息文本
     */
    fun exportZip(
        ctx: Context, rangeMs: Long, snapshot: String, device: String,
        sanitize: Boolean = true
    ): File {
        val cut = System.currentTimeMillis() - rangeMs
        val out = File(ctx.cacheDir, "logs-${SimpleDateFormat("MMdd_HHmm", Locale.CHINA).format(Date())}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("meta.txt"))
            zos.write(
                ("导出时间：${dfFull.format(Date())}\n" +
                        "范围：${RANGES.firstOrNull { it.first == rangeMs }?.second ?: "${rangeMs / 3600_000}小时"}\n" +
                        "脱敏：" + (if (sanitize) "已开启（手机号/取件码/uid/token/门牌 部分打码）" else "已关闭（完整原文）") + "\n" +
                        "日志目录：${dir(ctx).absolutePath}\n" + device + "\n").toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("snapshot.txt"))
            zos.write(snapshot.toByteArray())
            zos.closeEntry()

            val files = dir(ctx).listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
            for (f in files.sortedBy { it.name }) {
                val text = try {
                    f.readText()
                } catch (_: Throwable) {
                    continue
                }
                // 按行过滤：只保留范围内的行（文件按天切，跨天文件也能裁）
                val kept = text.lineSequence().filter { line ->
                    val ms = parseLineTime(line)
                    ms == null || ms >= cut   // 解析不出来的（异常堆栈）保留
                }.joinToString("\n")
                if (kept.isBlank()) continue
                val body = if (sanitize) kept.lines().joinToString("\n") { LogSanitizer.line(it) } else kept
                zos.putNextEntry(ZipEntry(f.name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        Log.i(TAG, "导出 zip: ${out.name} ${out.length()}B")
        return out
    }

    /** 解析 logcat threadtime 行首时间（补当前年份） */
    private fun parseLineTime(line: String): Long? {
        if (line.length < 18) return null
        val head = line.take(18)
        return try {
            val d = dfTime.parse(head) ?: return null
            val cal = java.util.Calendar.getInstance()
            val c2 = java.util.Calendar.getInstance().apply { time = d }
            cal.set(java.util.Calendar.MONTH, c2.get(java.util.Calendar.MONTH))
            cal.set(java.util.Calendar.DAY_OF_MONTH, c2.get(java.util.Calendar.DAY_OF_MONTH))
            cal.set(java.util.Calendar.HOUR_OF_DAY, c2.get(java.util.Calendar.HOUR_OF_DAY))
            cal.set(java.util.Calendar.MINUTE, c2.get(java.util.Calendar.MINUTE))
            cal.set(java.util.Calendar.SECOND, c2.get(java.util.Calendar.SECOND))
            cal.set(java.util.Calendar.MILLISECOND, c2.get(java.util.Calendar.MILLISECOND))
            var ms = cal.timeInMillis
            if (ms > System.currentTimeMillis() + 86_400_000L) {   // 跨年
                cal.add(java.util.Calendar.YEAR, -1)
                ms = cal.timeInMillis
            }
            ms
        } catch (_: Throwable) {
            null
        }
    }

    /** 当前已落盘的日志总量/文件数（设置页可展示） */
    fun stat(ctx: Context): String {
        val fs = dir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()
        val kb = fs.sumOf { it.length() } / 1024
        return "${fs.size} 个文件 / ${kb}KB"
    }
}
