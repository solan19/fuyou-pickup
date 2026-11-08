package com.agent.idcode

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 主进程 ↔ 独立特权进程保护进程 的桥（手册 3.5）
 *
 * ## ★★ 最重要的设计：全程可降级
 * Shizuku 没装 / 没运行 / 没授权 ⇒ **静默返回，绝不崩溃**。
 * 蜉蝣的常驻（[KeepAliveService]）+ 开机自启 + 系统绑定型服务这三层**不依赖任何特权**，
 * 单独用也远好于裸奔。Shizuku 只是"主进程真被回收时 45 秒内爬起来"的增强。
 * 手册原话：代码里必须做这个判断，别在没有 Shizuku 时崩溃。
 *
 * ## 退避重试（自限制④）
 * 绑定失败按 5s → 30s → 120s 退避，不疯狂重试。
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val REQ_PERM = 1000
    private const val SUFFIX = "system_bridge"
    private const val VERSION = 1

    /** 用户是否开启了 Shizuku 保护进程（存 idcode_prefs，与设置页开关同一个键） */
    const val KEY_ENABLED = "shizuku_enabled"

    @Volatile
    private var watchdog: IWatchDog? = null
    private var attempt = 0
    private var lastError = ""
    private var conn: ServiceConnection? = null

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * ★ 能不能开：Shizuku 服务在运行 **且** 已给我们授权。
     * 设置页开关打开前就问它 —— 不满足就开不了（同志 10-15 要求）。
     */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 不能开的原因（给提示用，说人话） */
    fun blockReason(): String = try {
        when {
            !Shizuku.pingBinder() -> "没检测到 Shizuku 在运行"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku 还没授权给蜉蝣"
            else -> ""
        }
    } catch (_: Throwable) {
        "Shizuku 状态读取失败"
    }

    /** 保护进程是否可用（设置页要显示状态） */
    fun isWatchdogAlive(): Boolean {
        val wd = watchdog ?: return false
        return try {
            wd.ping()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Shizuku 侧状态文案（给设置页用，说人话） */
    fun statusText(ctx: Context): String = when {
        !Shizuku.pingBinder() -> "未检测到 Shizuku（可选，不影响基本功能）"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku 已运行，尚未授权"
        isWatchdogAlive() -> "已就位（独立进程保护中）"
        lastError.isNotEmpty() -> "未连接：$lastError"
        else -> "正在连接…"
    }

    /**
     * @param interactive true = 用户在设置页**主动**点开关（未授权时弹申请框）；
     *                    false = 服务启动时的静默检测（未授权就安静跳过，
     *                            否则每次服务重启都弹一次授权框 = 骚扰）。
     */
    fun bindWatchdog(context: Context, interactive: Boolean = false) {
        try {
            // ★ 幂等保护（2026-10-15 同志问"两个开关同时开会冲突吗"）：
            //   StellarBridge 是委托绑定到这里的 ⇒ 两个开关都开时本方法会被调两次。
            //   保护已在位就直接返回，别重复绑（同一 key 重复 bind 可能造成连接抖动）。
            if (isWatchdogAlive()) {
                Log.i(TAG, "保护已在位 ⇒ 跳过重复绑定")
                return
            }
            if (!Shizuku.pingBinder()) {
                Log.i(TAG, "Shizuku 未运行 ⇒ 降级（仅用无特权的三层）")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (interactive) {
                    Log.i(TAG, "Shizuku 未授权 ⇒ 用户主动开启，发申请")
                    Shizuku.requestPermission(REQ_PERM)
                } else {
                    Log.i(TAG, "Shizuku 未授权 ⇒ 静默跳过（等用户主动开启）")
                }
                return
            }
            doBind(context)
        } catch (t: Throwable) {
            // ★ 任何异常都不许冒出去 —— Shizuku 是可选增强
            lastError = t.message ?: "未知"
            Log.w(TAG, "绑定保护进程失败（已降级）: ${t.message}")
        }
    }

    private fun doBind(context: Context) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, WatchUserService::class.java)
        )
            .daemon(false)                 // 不常驻，由主进程按需拉起
            .processNameSuffix(SUFFIX)
            .version(VERSION)
            .debuggable(false)

        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    lastError = "binder 为空"
                    return
                }
                val wd = IWatchDog.Stub.asInterface(service)
                watchdog = wd
                attempt = 0
                lastError = ""
                try {
                    // ★ 把复活令牌交给保护进程（只存在它进程的内存里）
                    wd.setToken(KeepAliveToken.ensure(context))
                } catch (t: Throwable) {
                    Log.w(TAG, "交令牌失败: ${t.message}")
                }
                // ★ 互保：保护进程进程死了，主进程立刻知道并重连
                try {
                    service.linkToDeath({ rebindLater(context) }, 0)
                } catch (t: Throwable) { SafeRun.w("ShizukuBridge", t) }
                Log.i(TAG, "保护已绑定")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                watchdog = null
                Log.w(TAG, "保护断开 ⇒ 退避重连")
                rebindLater(context)
            }
        }
        conn = c
        Shizuku.bindUserService(args, c)
    }

    /** 关掉开关时解绑保护进程（用户明确不要，就别留着独立进程） */
    fun unbindWatchdog(context: Context) {
        val c = conn ?: return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, WatchUserService::class.java)
            )
                .daemon(false)
                .processNameSuffix(SUFFIX)
                .version(VERSION)
                .debuggable(false)
            Shizuku.unbindUserService(args, c, true)
            Log.i(TAG, "保护已解绑")
        } catch (t: Throwable) {
            Log.w(TAG, "解绑保护进程失败（已忽略）: ${t.message}")
        }
        watchdog = null
        conn = null
    }

    /** 自限制④：5s → 30s → 120s 退避 */
    private fun rebindLater(context: Context) {
        val delay = KeepAliveGuard.backoffMs(attempt++)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                bindWatchdog(context)
            } catch (t: Throwable) { SafeRun.w("ShizukuBridge", t) }
        }, delay)
    }
}
