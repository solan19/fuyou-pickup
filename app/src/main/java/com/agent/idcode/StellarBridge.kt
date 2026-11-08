package com.agent.idcode

import android.content.Context
import android.os.IBinder
import android.util.Log
import roro.stellar.Stellar
import roro.stellar.userservice.ServiceMode
import roro.stellar.userservice.StellarUserService
import roro.stellar.userservice.UserServiceArgs

/**
 * Stellar 桥（同志 2026-10-15 要求增加）
 *
 * ## Stellar 是什么
 * Shizuku 的深度定制分支（roro2239/Stellar），机制同源：ADB/Root 启动特权服务后，
 * 普通应用经 Binder 间接获得特权。同志手机上**只装了 Stellar**（没装原版 Shizuku）。
 *
 * ## ★★★ 版本史（2026-10-15 深夜，一天内两次修正，都是实测逼出来的）
 * - v1（已废弃）：以为"Stellar 的 Shizuku 兼容层会自动接管 Shizuku API"，
 *   于是本类只做检测、把绑定**委托给 [ShizukuBridge]**。
 *   **实测证伪**：`Shizuku.pingBinder()` 恒为 false（兼容层并不接管这套调用），
 *   主进程永远绑不上保护进程。
 * - v2（当前）：改走 **Stellar 原生通道** —— 直接用
 *   `StellarUserService.bindUserService(args, callback)`（其内部是
 *   `Stellar.getService().startUserService`，见拷入项目的源码）。
 *   注意 `roro.stellar.userservice.*` 那几个类**上游没发布到 Maven**
 *   （jitpack.yml 漏列了模块），所以源码已拷进本项目自编译（3 个文件，见包内头注）。
 *
 * ## ★★ 同样必须全程可降级
 * Stellar 不在 ⇒ 静默返回，绝不崩溃。常驻/自启/系统绑定三层不依赖任何特权。
 */
object StellarBridge {

    private const val TAG = "StellarBridge"
    private const val REQ_PERM = 2000
    private const val SUFFIX = "stellar_bridge"

    /** 用户是否开启了 Stellar 保护进程（设置页开关同一个键） */
    const val KEY_ENABLED = "stellar_enabled"

    @Volatile
    private var watchdog: IWatchDog? = null
    private var lastError = ""
    private var boundArgs: UserServiceArgs? = null

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 能不能开：Stellar 服务在运行 **且** 已给我们授权（用 Stellar 原生 API 判，最准） */
    fun isAvailable(): Boolean = try {
        Stellar.pingBinder() && Stellar.checkSelfPermission()
    } catch (_: Throwable) {
        false
    }

    fun blockReason(): String = try {
        when {
            !Stellar.pingBinder() -> "没检测到 Stellar 在运行"
            !Stellar.checkSelfPermission() -> "Stellar 还没授权给蜉蝣"
            else -> ""
        }
    } catch (_: Throwable) {
        "Stellar 状态读取失败"
    }

    fun isWatchdogAlive(): Boolean {
        val wd = watchdog ?: return false
        return try {
            wd.ping()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * @param interactive true = 用户在设置页**主动**点开关（未授权时弹申请框）；
     *                    false = 服务启动时的静默检测（未授权就安静跳过，防骚扰）。
     */
    fun bindWatchdog(context: Context, interactive: Boolean = false) {
        try {
            if (!Stellar.pingBinder()) {
                Log.i(TAG, "Stellar 未运行 ⇒ 降级（仅用无特权的三层）")
                return
            }
            if (!Stellar.checkSelfPermission()) {
                if (interactive) {
                    Log.i(TAG, "Stellar 未授权 ⇒ 用户主动开启，发申请")
                    Stellar.requestPermission(requestCode = REQ_PERM)
                } else {
                    Log.i(TAG, "Stellar 未授权 ⇒ 静默跳过（等用户主动开启）")
                }
                return
            }
            // ★ 幂等：保护已在位就不再绑（服务重启/两个入口重复触发时防抖）
            if (isWatchdogAlive()) {
                Log.i(TAG, "保护已在位 ⇒ 跳过重复绑定")
                return
            }
            doBind(context)
        } catch (t: Throwable) {
            // 任何异常都不许冒出去 —— Stellar 是可选增强
            lastError = t.message ?: "未知"
            Log.w(TAG, "绑定 Stellar 保护失败（已降级）: ${t.message}")
        }
    }

    /** ★ 走 Stellar 原生通道（`StellarUserService` 内部调 `IStellarService.startUserService`） */
    private fun doBind(context: Context) {
        val args = UserServiceArgs.Builder(WatchUserService::class.java)
            .processNameSuffix(SUFFIX)
            .versionCode(1L)
            .debug(false)
            .serviceMode(ServiceMode.ONE_TIME)
            .build()
        boundArgs = args

        // ★ 传**持久化**的校验令牌（与复活令牌共用同一个 SP 键）：
        //   上游默认每次新生成 UUID，而服务端"复用已存在的服务"时持有的是首次的令牌
        //   ⇒ 复用场景必然"验证令牌不匹配"（永久死锁）。传稳定令牌后首次=后续，恒等匹配。
        val stableToken = KeepAliveToken.ensure(context)
        StellarUserService.bindUserService(args, object : StellarUserService.ServiceCallback {
            override fun onServiceConnected(service: IBinder) {
                val wd = IWatchDog.Stub.asInterface(service)
                watchdog = wd
                lastError = ""
                try {
                    // 把复活令牌交给保护进程（只存在它进程的内存里）
                    wd.setToken(KeepAliveToken.ensure(context))
                } catch (t: Throwable) {
                    Log.w(TAG, "交令牌失败: ${t.message}")
                }
                Log.i(TAG, "Stellar 保护已绑定（原生通道）")
            }

            override fun onServiceDisconnected() {
                watchdog = null
                Log.w(TAG, "Stellar 保护断开")
            }

            override fun onServiceStartFailed(errorCode: Int, message: String) {
                watchdog = null
                lastError = "$errorCode $message"
                Log.w(TAG, "Stellar 保护启动失败: $errorCode $message")
            }
        }, verificationToken = stableToken)
    }

    /**
     * ★ vc303：用 shell 权限注入一次输入（input text/tap/keyevent）—— 给格口码查询用。
     *   页面对 JS 合成事件免疫、只认真实输入 ⇒ 借 Stellar 的 shell 特权进程发命令
     *   （不需要开无障碍，也不需要 adb 连电脑）。
     * @return 0 成功；-1 保护进程未绑定；其它 = 执行失败码
     */
    fun injectInput(args: Array<String>): Int {
        val wd = watchdog ?: return -1
        return try {
            wd.injectInput(args)
        } catch (t: Throwable) {
            Log.w(TAG, "injectInput 调用失败: ${t.message}")
            -9
        }
    }

    /** 查保护进程实际 UID（0=root / 2000=shell ⇒ 才有 INJECT_EVENTS） */
    fun selfUid(): Int {
        val wd = watchdog ?: return -1
        return try {
            wd.selfUid()
        } catch (t: Throwable) {
            -1
        }
    }

    /** 关掉开关时解绑（用户明确不要，就别留着独立进程） */
    fun unbindWatchdog(context: Context) {
        try {
            boundArgs?.let { StellarUserService.unbindUserService(it) }
            Log.i(TAG, "Stellar 保护已解绑")
        } catch (t: Throwable) {
            Log.w(TAG, "解绑 Stellar 保护失败（已忽略）: ${t.message}")
        }
        watchdog = null
        boundArgs = null
    }

    /** 给设置页的状态文案（说人话） */
    fun statusText(ctx: Context): String = when {
        !isEnabled(ctx) -> "未开启"
        !isAvailable() -> blockReason()
        isWatchdogAlive() -> "已就位（独立进程保护中）"
        lastError.isNotEmpty() -> "未连接：$lastError"
        else -> "正在连接…"
    }
}
