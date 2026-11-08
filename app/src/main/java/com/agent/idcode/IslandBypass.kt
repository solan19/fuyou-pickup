package com.agent.idcode

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import roro.stellar.Stellar

/**
 * ★ vc321：小米超级岛白名单绕过 —— 照 Maling-Island（github.com/SiberiaApp/Maling-Island，
 * AGPL-3.0）的「掐网法」一比一复刻，双引擎（Shizuku / Stellar 任一可用即可）。
 *
 * ## 原理（为什么掐网能绕白名单）
 * HyperOS 收到带 miui.focus.param 的通知后，要找小米服务器（经小米推送框架
 * com.xiaomi.xmsf）核对「这个 App 在不在焦点通知白名单里」；没审批过的包直接发会被拦。
 * Maling 的办法：
 *   发通知前的一瞬间，用特权（Shizuku/Stellar）把 XMSF 的网掐断 →
 *   系统验资格时联系不上服务器 → 放行显示 → 100ms 后恢复联网。神不知鬼不觉。
 *
 * ## 掐网手段（两步，与 Maling 相同）
 * ① binder 直调（首选）：把 connectivity 系统服务用 ShizukuBinderWrapper 包一层 →
 *    setFirewallChainEnabled(9/OEM_DENY, true) + setUidFirewallRule(9, xmsfUid, DENY=2)
 * ② cmd 兜底：`cmd connectivity set-chain3-enabled true` +
 *    `cmd connectivity set-package-networking-enabled <bool> com.xiaomi.xmsf`
 *
 * ★ 与 Maling 的差异：它只支持 Shizuku，这里 Stellar 也能当引擎（同志两套管理器都可能装）。
 * ★ 引擎不可用时不掐网、照常发通知 —— 与 Maling 同款降级：岛大概率不亮，但绝不报错。
 */
object IslandBypass {

    private const val TAG = "IslandBypass"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val FIREWALL_CHAIN_OEM_DENY = 9
    private const val RULE_ALLOW = 0
    private const val RULE_DENY = 2
    private val lock = Any()

    enum class Engine { NONE, SHIZUKU, STELLAR }

    /** Shizuku 在运行且已授权 */
    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Stellar 在运行且已授权（roro.stellar API：checkSelfPermission 直接返回 Boolean） */
    fun stellarReady(): Boolean = try {
        Stellar.pingBinder() && Stellar.checkSelfPermission()
    } catch (_: Throwable) {
        false
    }

    /** 当前可用的绕过引擎 */
    fun engine(): Engine = when {
        shizukuReady() -> Engine.SHIZUKU
        stellarReady() -> Engine.STELLAR
        else -> Engine.NONE
    }

    fun engineName(): String = when (engine()) {
        Engine.SHIZUKU -> "Shizuku"
        Engine.STELLAR -> "Stellar"
        Engine.NONE -> "无（未装或未授权）"
    }

    /**
     * Maling 同款三段流程：掐网 → 发布 → 恢复。
     * enabled=false 或引擎不可用 ⇒ 直接 block()（不掐网）。
     * 掐网失败 ⇒ 也直接 block()（降级发，岛不亮就当普通通知）。
     */
    fun runIfEnabled(context: Context, enabled: Boolean, block: () -> Unit) {
        if (!enabled) {
            block()
            return
        }
        val e = engine()
        if (e == Engine.NONE) {
            Log.i(TAG, "绕过开关已开，但 Shizuku/Stellar 都不可用 ⇒ 直接发通知（不掐网）")
            block()
            return
        }
        synchronized(lock) {
            Log.i(TAG, "绕过 1/3：准备断开 $XMSF_PACKAGE 联网（engine=$e）")
            val cut = setXmsfNetworking(context, e, false)
            if (!cut) {
                Log.w(TAG, "绕过中止：断网失败 ⇒ 直接发通知（降级）")
                block()
                return
            }
            try {
                Log.i(TAG, "绕过 2/3：XMSF 已断网，发布超级岛通知")
                block()
                Thread.sleep(100)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "绕过被中断：通知已发，准备恢复 XMSF 联网")
            } finally {
                Log.i(TAG, "绕过 3/3：恢复 $XMSF_PACKAGE 联网")
                if (setXmsfNetworking(context, e, true)) {
                    Log.i(TAG, "绕过完成：XMSF 联网已恢复")
                } else {
                    Log.e(TAG, "恢复 XMSF 联网失败！请检查 Shizuku/Stellar 状态或重启设备")
                }
            }
        }
    }

    private fun setXmsfNetworking(context: Context, engine: Engine, enabled: Boolean): Boolean {
        if (setByBinder(context, engine, enabled)) return true
        return setByCommand(context, engine, enabled)
    }

    /** 手段①：binder 直调（与 Maling 的 setXmsfNetworkingEnabledByConnectivityBinder 相同） */
    private fun setByBinder(context: Context, engine: Engine, enabled: Boolean): Boolean = try {
        val uid = context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
        val binder: IBinder = when (engine) {
            Engine.SHIZUKU -> SystemServiceHelper.getSystemService("connectivity")
            else -> Stellar.getSystemService("connectivity")
        } ?: return false
        val wrapped: IBinder = when (engine) {
            Engine.SHIZUKU -> ShizukuBinderWrapper(binder)
            // StellarBinderWrapper 构造签名不做编译期硬依赖（反射构造，失败自动落到 cmd 兜底）
            else -> Class.forName("roro.stellar.StellarBinderWrapper")
                .getConstructor(IBinder::class.java)
                .newInstance(binder) as IBinder
        }
        val svc = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)

        svc.javaClass.getMethod(
            "setFirewallChainEnabled",
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).invoke(svc, FIREWALL_CHAIN_OEM_DENY, true)

        val rule = if (enabled) RULE_ALLOW else RULE_DENY
        svc.javaClass.getMethod(
            "setUidFirewallRule",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(svc, FIREWALL_CHAIN_OEM_DENY, uid, rule)
        Log.i(TAG, "掐网·binder 直调成功 engine=$engine enabled=$enabled uid=$uid chain=$FIREWALL_CHAIN_OEM_DENY")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "掐网·binder 直调失败（转 cmd 兜底）: ${t.message}")
        false
    }

    /** 手段②：cmd 兜底（与 Maling 的 setXmsfNetworkingEnabledByConnectivityCommand 相同） */
    private fun setByCommand(context: Context, engine: Engine, enabled: Boolean): Boolean {
        val (chainCode, chainOut) = runCmd(
            engine, arrayOf("cmd", "connectivity", "set-chain3-enabled", "true")
        )
        if (chainCode != 0) {
            Log.w(TAG, "cmd 兜底失败：开不了 FIREWALL_CHAIN_OEM_DENY_3，exit=$chainCode out=$chainOut")
            return false
        }
        val (code, out) = runCmd(
            engine,
            arrayOf(
                "cmd", "connectivity", "set-package-networking-enabled",
                enabled.toString(), XMSF_PACKAGE
            )
        )
        if (code != 0) {
            Log.w(TAG, "cmd 兜底失败：设 XMSF 联网 exit=$code out=$out")
            return false
        }
        Log.i(TAG, "cmd 兜底成功 enabled=$enabled out=$out")
        return true
    }

    /**
     * 特权进程里远程执行命令。
     * Shizuku：反射调 newProcess（与 Maling 完全一致，避开编译期符号问题）；
     * Stellar：roro.stellar.Stellar.newProcess 直调。
     */
    private fun runCmd(engine: Engine, cmd: Array<String>): Pair<Int, String> = try {
        val p: Process = when (engine) {
            Engine.SHIZUKU -> {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                m.invoke(null, cmd, null, null) as Process
            }
            else -> {
                // Stellar 的 newProcess 形参是可空数组（Array<String?>）⇒ 原地转一次
                @Suppress("UNCHECKED_CAST")
                val args: Array<String?> = cmd as Array<String?>
                Stellar.newProcess(args, null, null) as Process
            }
        }
        val stdout = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.errorStream.bufferedReader().use { it.readText().trim() }
        Pair(p.waitFor(), stdout)
    } catch (t: Throwable) {
        Log.w(TAG, "runCmd 异常: ${t.message}")
        Pair(-1, t.message ?: "")
    }
}
