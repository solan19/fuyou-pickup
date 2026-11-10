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
    const val DEFAULT_URL = "https://raw.githubusercontent.com/solan19/fuyou-pickup/main/update.json"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun url(ctx: Context): String =
        sp(ctx).getString(KEY_URL, DEFAULT_URL).orEmpty().ifBlank { DEFAULT_URL }

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

    /** 同步检查 —— 调用方必须在后台线程调（或用 [checkAsync]）。 */
    fun check(ctx: Context): CheckResult {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(UpdatePrefs.url(ctx)).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code != 200) return CheckResult.Failed("清单读取失败（HTTP $code）")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (!o.optBoolean("published", false)) {
                return CheckResult.NotPublished("暂未发布更新")
            }
            val info = UpdateInfo.from(o)
            if (info.versionCode <= 0L || info.apkUrl.isBlank() ||
                !info.apkUrl.startsWith("https://")
            ) {
                return CheckResult.Failed("清单内容不完整（版本号或 APK 地址）")
            }
            val cur = currentCode(ctx)
            return if (info.versionCode > cur) {
                Log.i(TAG, "发现新版本 ${info.versionName}(${info.versionCode})，当前 $cur")
                CheckResult.Available(info)
            } else {
                CheckResult.UpToDate(cur)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "检查更新失败：${t.message}")
            return CheckResult.Failed("检查失败：${t.message ?: "网络不可用"}")
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

    /** @return 是否成功下载并通过校验 */
    private fun doDownload(
        activity: Activity,
        info: UpdateInfo,
        post: (String) -> Unit,
    ): Boolean {
        post("正在下载更新…")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) {
                post("下载失败（HTTP $code）")
                return false
            }
            val f = targetFile(activity, info)
            conn.inputStream.use { input ->
                FileOutputStream(f).use { out -> input.copyTo(out) }
            }
            if (info.sha256.isNotBlank()) {
                post("正在校验安装包…")
                val actual = sha256(f)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    f.delete()
                    post("校验失败（SHA-256 不一致），已删除下载文件")
                    return false
                }
            }
            post("下载完成，等待系统安装器")
            return true
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
