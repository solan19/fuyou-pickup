package com.agent.idcode

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log

/**
 * ★ 自限制（2026-10-15，手册第 4 节"灵魂"）
 *
 * 手册原话：**没有这四条，上面的代码就是流氓软件**。本项目的取向（不耍流氓、不惹麻烦、
 * 不要求用户装任何东西）与它完全一致，所以照做，并且抽成**单一判定点**——
 * 三个调用点（Shizuku 看门狗、闹钟降级自愈、设置页状态显示）共用一份逻辑，
 * 不在三处各写一遍（本项目铁律：同一判定只写一处，否则迟早走叉）。
 *
 * 1. **熔断**：10 分钟内复活 ≥ 3 次 ⇒ 停止复活（防死循环、防被系统判为恶意 App）
 * 2. **息屏跳过**：屏幕灭着 ⇒ 不检查不复活（省电，夜里不折腾）
 * 3. **尊重强制停止**：用户在设置里点了「强行停止」⇒ **永不复活**
 * 4. **退避重试**：绑定失败按 5s → 30s → 120s 退避，不疯狂重试
 */
object KeepAliveGuard {

    private const val TAG = "KeepAliveGuard"
    private const val MAX_RECOVERY_IN_WINDOW = 3
    private const val WINDOW_MS = 600_000L            // 10 分钟
    private const val PREF = "idcode_prefs"
    private const val KEY_EXITS = "keepalive_exits"    // 复活时间戳，逗号分隔

    /** ① 熔断：本次复活是否被允许。调用前先过 ②③。 */
    fun allowAnotherRecovery(ctx: Context): Boolean {
        val exits = loadExits(ctx).toMutableList()
        val now = System.currentTimeMillis()
        exits.removeAll { now - it > WINDOW_MS }
        if (exits.size >= MAX_RECOVERY_IN_WINDOW) {
            Log.w(TAG, "10 分钟内已复活 ${exits.size} 次 ⇒ 停止复活（熔断）")
            saveExits(ctx, exits)
            return false
        }
        exits.add(now)
        saveExits(ctx, exits)
        return true
    }

    /** ② 息屏跳过 */
    fun isScreenOn(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    /**
     * ③ 尊重强制停止：`ApplicationInfo.FLAG_STOPPED`（1 << 21 = 2097152）。
     * 用户在设置里点了「强行停止」或被 force-stop，系统会打这个标记。
     */
    fun isForceStopped(ctx: Context): Boolean = try {
        val ai = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getApplicationInfo(
                ctx.packageName, PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        }
        (ai.flags and (1 shl 21)) != 0
    } catch (_: Throwable) {
        false
    }

    /** ④ 退避：第 n 次失败（从 0 开始）该等多久 */
    fun backoffMs(attempt: Int): Long = when {
        attempt <= 0 -> 5_000L
        attempt == 1 -> 30_000L
        else -> 120_000L
    }

    /** 熔断状态清零（用户手动"重新开始"时用） */
    fun resetBreaker(ctx: Context) = saveExits(ctx, emptyList())

    fun currentBreakerCount(ctx: Context): Int {
        val now = System.currentTimeMillis()
        return loadExits(ctx).count { now - it <= WINDOW_MS }
    }

    private fun loadExits(ctx: Context): List<Long> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_EXITS, null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    private fun saveExits(ctx: Context, list: List<Long>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXITS, list.joinToString(",")).apply()
    }
}
