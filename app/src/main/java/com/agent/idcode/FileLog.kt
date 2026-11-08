package com.agent.idcode

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★ vc460：应用内日志落盘。
 *
 * 起因：2026-10-25 晚 logcat 抓不到本应用日志（缓冲区被系统刷屏，IdDiag/WebUrl 全为 0），
 * 排查邮政登录时成了瞎子。这里把关键日志同时写进 `Android/data/com.agent.idcode/files/app.log`，
 * 可以直接 `adb pull` 出来看，不再受 logcat 缓冲影响。
 */
object FileLog {

    private const val MAX = 1_500_000

    fun log(ctx: Context, tag: String, msg: String) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val f = File(dir, "app.log")
            if (f.length() > MAX) f.writeText("")
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
            f.appendText("$ts $tag: $msg\n")
        } catch (t: Throwable) { SafeRun.w("FileLog", t) }
    }
}
