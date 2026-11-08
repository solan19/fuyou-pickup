package com.agent.idcode

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 复活令牌（2026-10-15，照同志提供的《Android常驻防杀机制-施工手册》3.3 实现）
 *
 * ## 为什么要有令牌
 * 复活接收器 [KeepAliveRecoveryReceiver] 是 exported 的（必须 exported，否则 shell 的
 * `am broadcast` 发不进来）。exported = 任何 App 都能给它发广播 ⇒ 必须有个"暗号"，
 * 否则别人可以随便把我们的服务拉起来（骚扰用户、耗电）。
 *
 * ## 三个不能省的点
 * ① **24 字节强随机**（SecureRandom，不是 Math.random）——猜不出来
 * ② **常量时间比对**（`MessageDigest.isEqual`，不是 `String.equals`）——
 *    `equals` 一旦某个字符不匹配就立刻返回，攻击者靠"返回快慢"能逐字节猜出令牌
 *    （时序侧信道）。手册里原话特别强调这一条。
 * ③ **服务正常停止时销毁令牌**——别让暗号长期躺在磁盘上
 */
object KeepAliveToken {

    private const val PREF = "keepalive_recovery"
    private const val KEY = "token"

    /** 取当前令牌；没有就现生成一个（强随机 24 字节 → Base64 URL 安全编码） */
    fun ensure(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val exist = prefs.getString(KEY, null)
        if (!exist.isNullOrEmpty()) return exist

        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = Base64.getEncoder()
            .encodeToString(bytes)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    fun current(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    /** ★ 常量时间比较，防时序侧信道 */
    fun matches(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }
}
