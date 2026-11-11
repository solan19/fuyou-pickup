package com.agent.idcode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ★★ v1.4.24：**「关于应用 → 检查更新」的能力层**（UI 在设置页 aboutCard 里）。
 *
 * ## 三条硬规矩（同志 22:43 定）
 * 1. **版本由用户主动确认才分发**：仓库根 `update.json` 由 `tools/publish_update.py --confirm` 生成，
 *    平时 `published=false`。代码提交 ≠ 版本分发。
 * 2. **不进行弹窗推送**：这里不发通知、不弹对话框、不自动下载、不自动安装、不阻塞页面；
 *    所有结果都由关于页那一行显示，用户点「检查更新 / 下载更新」才动作。
 * 3. **force 只影响展示**：清单里 `force=true` 时关于页该行常驻红色「必须更新」，
 *    仍然不弹窗、不自动下载。
 *
 * ## 关于私有仓库
 * 默认清单地址指向本仓库 main 分支；但仓库是私有的，App 没有 token 读不到。
 * 所以清单 URL 做成可配置（[UpdatePrefs]）：分发时把清单与 APK 放到已安装用户可访问的位置，
 * 或在设置里改 URL。读不到只返回 [CheckResult.Failed] 显示在关于页，不打扰用户。
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    val force: Boolean,
    val minSupport: Long,
    val publishedAt: String,
) {
    companion object {
        fun from(o: JSONObject): UpdateInfo = UpdateInfo(
            versionCode = o.optLong("versionCode", 0L),
            versionName = o.optString("versionName", ""),
            apkUrl = o.optString("apkUrl", ""),
            sha256 = o.optString("sha256", ""),
            notes = o.optString("notes", ""),
            force = o.optBoolean("force", false),
            minSupport = o.optLong("minSupport", 0L),
            publishedAt = o.optString("publishedAt", ""),
        )
    }
}

sealed class CheckResult {
    data class UpToDate(val current: Long) : CheckResult()
    data class Available(val info: UpdateInfo) : CheckResult()
    data class NotPublished(val message: String) : CheckResult()
    data class Failed(val message: String) : CheckResult()
}

object UpdatePrefs {
    private const val PREF = "idcode_prefs"
    private const val KEY_URL = "update_manifest_url"
    // ★★ v1.4.26：默认地址回到**公开仓库**的 raw 清单 —— 全 App 只认这一个正式源。
    //   v1.4.25 把默认值指向了临时发布页，而 update.json 的正式源是仓库 raw ⇒
    //   两套地址各读各的，镜像页一旦忘了同步就显示「清单读取失败」。
    //   镜像页今后只作说明/兜底，不再出现在默认值里。
    const val DEFAULT_URL = "https://raw.githubusercontent.com/solan19/fuyou-pickup/main/update.json"

    /**
     * v1.4.25 及更早版本**内置过**的默认地址。
     *
     * 老用户的 prefs 里可能原样存着它（那是旧默认值，不是他自己改的）。照旧跟着它走，
     * 升级后仍然读临时发布页 ⇒ 与正式源不一致。识别出来直接清掉、归位到 [DEFAULT_URL]。
     */
    private val LEGACY_DEFAULT_URLS = listOf(
        "https://fuyou-update.app.workbuddy.host/update.json?sharecode=2QYLZIN5LX",
        "https://fuyou-update.app.workbuddy.host/update.json"
    )

    /**
     * ★★ v1.4.27：**备用清单源**（同一份内容的不同镜像）。
     *
     * 为什么必须有：`raw.githubusercontent.com` 在境内经常**被链路直接重置**，
     * 不是 404、不是超时，而是 `Connection reset` —— 2026-11-11 本机直连实测：
     *   raw.githubusercontent.com       → http=000（reset）
     *   cdn.jsdelivr.net                → http=000（reset）
     *   gcore.jsdelivr.net              → 200
     *   ghproxy.net                     → 200
     *   自建镜像页 app.workbuddy.host    → 200
     * 所以「只写一个 raw 地址」在国内等于赌运气。
     */
    val FALLBACK_MANIFESTS = listOf(
        "https://fuyou-update.app.workbuddy.host/update.json?sharecode=2QYLZIN5LX",
        "https://gcore.jsdelivr.net/gh/solan19/fuyou-pickup@main/update.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/solan19/fuyou-pickup/main/update.json"
    )

    /** 镜像页静态资源的 sharecode（该站点不带就 403） */
    const val MIRROR_SHARECODE = "2QYLZIN5LX"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前生效的清单地址（旧内置默认值自动归位，见 [LEGACY_DEFAULT_URLS]） */
    fun url(ctx: Context): String {
        val saved = sp(ctx).getString(KEY_URL, null)?.trim().orEmpty()
        if (saved.isEmpty()) return DEFAULT_URL
        if (LEGACY_DEFAULT_URLS.any { it.equals(saved, ignoreCase = true) }) {
            sp(ctx).edit().remove(KEY_URL).apply()
            return DEFAULT_URL
        }
        return saved
    }

    /**
     * ★ v1.4.27：用户**自己改过**的清单地址；没改过返回 null。
     *
     * 只有它才排候选列表第一 —— 内置默认值（raw）在国内大概率被重置，
     * 若把它也排前面，每次「检查更新」都要白等一次 reset 才轮到镜像。
     */
    fun customUrl(ctx: Context): String? {
        val saved = sp(ctx).getString(KEY_URL, null)?.trim().orEmpty()
        if (saved.isEmpty() || saved == DEFAULT_URL) return null
        if (LEGACY_DEFAULT_URLS.any { it.equals(saved, ignoreCase = true) }) return null
        return saved
    }

    fun setUrl(ctx: Context, u: String) {
        sp(ctx).edit().putString(KEY_URL, u.trim()).apply()
    }
}

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 8_000

    fun currentCode(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (t: Throwable) {
        0L
    }

    /** 上一个失败的源给出的原因，用于「所有源都不通」时的提示 */
    private var lastError: String = "网络不可用"

    /**
     * ★★ v1.4.27：清单候选地址 —— 手改地址 → 镜像/CDN → 正式源（raw 排最后）。
     * 只写一个源在国内不够用，见 [UpdatePrefs.FALLBACK_MANIFESTS]。
     */
    fun manifestCandidates(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        UpdatePrefs.customUrl(ctx)?.let { out.add(it) }
        out.addAll(UpdatePrefs.FALLBACK_MANIFESTS)
        out.add(UpdatePrefs.DEFAULT_URL)
        return out.toList()
    }

    /** 同步检查 —— 调用方必须在后台线程调（或用 [checkAsync]）。多源逐个试，第一个通的就用。 */
    fun check(ctx: Context): CheckResult {
        val list = manifestCandidates(ctx)
        for (u in list) {
            val r = checkOne(ctx, u)
            if (r != null) return r
        }
        return CheckResult.Failed("所有清单源都不可用（$lastError），可在设置里改清单地址")
    }

    /** @return null = 这个源不可用（换下一个）；非 null = 拿到了有效结论 */
    private fun checkOne(ctx: Context, url: String): CheckResult? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "IdCode-UpdateChecker")
            val code = conn.responseCode
            if (code != 200) {
                lastError = "HTTP $code"
                Log.w(TAG, "清单源不可用（HTTP $code）：$url")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (!o.optBoolean("published", false)) {
                return CheckResult.NotPublished("暂未发布更新")
            }
            val info = UpdateInfo.from(o)
            if (info.versionCode <= 0L || info.apkUrl.isBlank() ||
                !info.apkUrl.startsWith("https://")
            ) {
                lastError = "清单内容不完整"
                Log.w(TAG, "清单内容不完整：$url")
                return null
            }
            val cur = currentCode(ctx)
            Log.i(
                TAG,
                "清单源 ${runCatching { URL(url).host }.getOrDefault("?")} ⇒ " +
                        "${info.versionName}(${info.versionCode})，当前 $cur"
            )
            return if (info.versionCode > cur) CheckResult.Available(info) else CheckResult.UpToDate(cur)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "清单源不可用（$lastError）：$url")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 关于页用：后台检查，结果回主线程由 UI 显示（不弹窗） */
    fun checkAsync(activity: Activity, cb: (CheckResult) -> Unit) {
        Thread {
            val r = check(activity.applicationContext)
            if (activity.isFinishing || activity.isDestroyed) return@Thread
            activity.runOnUiThread { cb(r) }
        }.start()
    }

    /**
     * 用户点「下载更新」后才调用：下载 → SHA-256 校验 → 交给系统安装器。
     * 不自动安装（系统安装器仍会要求用户确认），不绕过签名校验。
     */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo, onProgress: (String) -> Unit) {
        Thread {
            val post: (String) -> Unit = { s ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.runOnUiThread { onProgress(s) }
                }
            }
            try {
                val ok = doDownload(activity, info, post)
                if (!ok) return@Thread
                val f = targetFile(activity, info)
                val uri = FileProvider.getUriForFile(
                    activity, activity.packageName + ".fileprovider", f
                )
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.runOnUiThread {
                    try {
                        activity.startActivity(i)
                    } catch (t: Throwable) {
                        SafeRun.w(TAG, t)
                        onProgress("打不开系统安装器：${t.message}")
                    }
                }
            } catch (t: Throwable) {
                SafeRun.w(TAG, t)
                post("下载失败：${t.message}")
            }
        }.start()
    }

    private fun targetFile(ctx: Context, info: UpdateInfo): File {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "fuyou-${info.versionName}.apk")
    }

    private val RAW_APK = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")

    /**
     * ★★ v1.4.27：安装包的候选地址。
     *
     * 清单里 `apkUrl` 写的是 raw（正式源），但境内大概率被重置 ⇒ 同一份文件再换 CDN / 镜像重试。
     * 每个候选都**独立校验 SHA-256**，第一个校验通过才算成功（镜像给错文件也不会装上）。
     */
    fun apkCandidates(apkUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        val m = RAW_APK.find(apkUrl)
        if (m != null) {
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            val ref = m.groupValues[3]
            val path = m.groupValues[4]
            // 顺序按**实测速度**排（2026-11-11，3.28MB 的包）：
            //   镜像页 6.3s 下完 ／ gcore 20s 只到 3.11MB ／ ghproxy 20s 只到 1.46MB ／ raw 直接 reset
            // ⇒ 镜像第一，CDN 其次，raw 垫底（能通最好，不通也不至于卡住）。
            out.add(
                "https://fuyou-update.app.workbuddy.host/" + path.substringAfterLast('/') +
                        "?sharecode=" + UpdatePrefs.MIRROR_SHARECODE
            )
            out.add("https://gcore.jsdelivr.net/gh/$owner/$repo@$ref/$path")
            out.add("https://ghproxy.net/$apkUrl")
            out.add(apkUrl)
        } else if (apkUrl.contains("app.workbuddy.host")) {
            // 清单里的 apkUrl 也可能直接是镜像页（旧版 App 只认这一个地址，必须这样写才行）
            val full = if (apkUrl.contains("sharecode=")) apkUrl
            else apkUrl + (if (apkUrl.contains('?')) "&" else "?") +
                    "sharecode=" + UpdatePrefs.MIRROR_SHARECODE
            out.add(full)
            // 再从文件名推两个 CDN 备源：镜像站挂了也不至于没得下
            val name = apkUrl.substringBefore('?').substringAfterLast('/')
            if (name.startsWith("fuyou-") && name.endsWith(".apk")) {
                out.add("https://gcore.jsdelivr.net/gh/solan19/fuyou-pickup@main/update/$name")
                out.add(
                    "https://ghproxy.net/https://raw.githubusercontent.com/" +
                            "solan19/fuyou-pickup/main/update/$name"
                )
            }
            out.add(apkUrl)
        } else {
            out.add(apkUrl)
        }
        return out.toList()
    }

    /** @return 是否成功下载并通过校验 */
    private fun doDownload(
        activity: Activity,
        info: UpdateInfo,
        post: (String) -> Unit,
    ): Boolean {
        val list = apkCandidates(info.apkUrl)
        var last = "未知错误"
        for ((i, u) in list.withIndex()) {
            post("正在下载更新…（源 ${i + 1}/${list.size}）")
            val r = downloadOne(activity, info, u)
            when (r) {
                1 -> {
                    post("下载完成，等待系统安装器")
                    Log.i(TAG, "下载成功：$u")
                    return true
                }
                2 -> last = "SHA-256 校验不一致"
                else -> last = "下载失败"
            }
        }
        post("下载失败：所有下载源都不可用（$last）")
        return false
    }

    /** @return 1=成功 2=校验不一致 0=这个源不可用 */
    private fun downloadOne(activity: Activity, info: UpdateInfo, url: String): Int {
        var conn: HttpURLConnection? = null
        val f = targetFile(activity, info)
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "IdCode-UpdateChecker")
            if (conn.responseCode != 200) {
                Log.w(TAG, "下载源不可用（HTTP ${conn.responseCode}）：$url")
                return 0
            }
            conn.inputStream.use { input ->
                FileOutputStream(f).use { out -> input.copyTo(out) }
            }
            if (info.sha256.isNotBlank()) {
                val actual = sha256(f)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    Log.w(TAG, "下载源校验不一致：$url（实际 $actual）")
                    f.delete()
                    return 2
                }
            }
            return 1
        } catch (t: Throwable) {
            Log.w(TAG, "下载源不可用（${t.message}）：$url")
            try {
                if (f.exists()) f.delete()
            } catch (_: Throwable) {
            }
            return 0
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 手工拼 hex，避开 String.format 的重载解析差异 */
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = "0123456789abcdef"
        return md.digest().joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "" + hex[v ushr 4] + hex[v and 0x0F]
        }
    }
}
