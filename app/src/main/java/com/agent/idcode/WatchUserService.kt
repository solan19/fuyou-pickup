package com.agent.idcode

import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ★ 独立特权进程（Stellar 原生形态，vc264i 改造）
 *
 * ## 它为什么能救主进程
 * 由 **Stellar 服务**以独立进程拉起（进程名后缀 `stellar_bridge`），
 * 以 **UID 2000（shell）或 UID 0（root）** 运行 ⇒
 *   ① 主进程被系统回收了，它还活着
 *   ② 它有资格发出被 `android.permission.DUMP` 保护的广播（普通 App 发不出）
 *
 * ## ★★★ 形态切换的由来（2026-10-15 深夜实测）
 * 原先按 Shizuku 的规矩写成 `Service` 子类（靠 onBind 返回 binder）。
 * 但实测发现 Stellar 的"Shizuku 兼容层"**不接管 Shizuku API**
 * （`Shizuku.pingBinder()` 恒为 false，主进程永远绑不上）。
 * 改走 **Stellar 原生通道**（`StellarUserService.bindUserService`）后，
 * 类的形态按 INTEGRATION_GUIDE 示例：**直接继承 AIDL 的 Stub**，
 * 由 Stellar 服务在目标进程里**反射实例化并持有**（不是四大组件，不需要 onBind）。
 *
 * ## 实例化即开工
 * 服务端 new 出本类实例后，构造块立刻启动 45 秒调度器 ——
 * 实例被服务端持有 = 调度器持续存活。
 *
 * ## 它每 45 秒做什么
 * 1. 息屏 ⇒ **跳过**（手册自限制②，夜里不折腾、省电）
 * 2. 主进程还活着 ⇒ 什么都不做
 * 3. 主进程没了 ⇒ 用 `/system/bin/am broadcast` 发一条**带令牌**的广播把它叫回来
 *
 * ## 令牌从哪来
 * 保护进程进程读不到主进程的 SharedPreferences，
 * 令牌由主进程绑定成功后经 [setToken] 传进来，只存在本进程内存里 ——
 * 主进程真死了也还握着，且不落任何外部存储（不外泄）。
 */
class WatchUserService : IWatchDog.Stub() {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null

    /** 只存内存，不落盘 */
    @Volatile
    private var token: String = ""

    init {
        // 服务端实例化即开工（见类头注"实例化即开工"）
        Log.i(TAG, "保护已启动（uid=${Process.myUid()}，pid=${Process.myPid()}）")
        try {
            future = scheduler.scheduleAtFixedRate(::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "调度器启动失败: ${t.message}")
        }
    }

    @Throws(RemoteException::class)
    override fun setToken(t: String?) {
        token = t ?: ""
        Log.i(TAG, "令牌已收到（长度 ${token.length}）")
    }

    @Throws(RemoteException::class)
    override fun ping() = Unit

    @Throws(RemoteException::class)
    override fun recover(pkg: String?): Int = doRecover(pkg ?: "")

    /**
     * ★ vc303：以 shell 权限注入一次输入事件（input text / tap / keyevent）。
     *   由主进程经 Binder 调用 —— 用于驱动拼多多「我的快递」查询页拿格口码
     *   （页面对 JS 合成事件免疫，只认真实输入，而本进程是 shell ⇒ 有 INJECT_EVENTS）。
     */
    @Throws(RemoteException::class)
    override fun injectInput(args: Array<String>?): Int {
        val a = args?.filter { it.isNotEmpty() } ?: return -1
        if (a.isEmpty()) return -1
        return try {
            val cmd = arrayOf("/system/bin/input") + a.toTypedArray()
            val p = Runtime.getRuntime().exec(cmd)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            Log.i(TAG, "injectInput ${a.joinToString(" ").take(60)} exit=$code out=${out.take(60)} err=${err.take(80)}")
            if (code == 0) 0 else -2
        } catch (t: Throwable) {
            Log.w(TAG, "injectInput 失败: ${t.message}")
            -3
        }
    }

    @Throws(RemoteException::class)
    override fun selfUid(): Int = Process.myUid()

    private fun tick() {
        try {
            if (!isScreenAwake()) return                 // 自限制② 息屏不折腾
            if (isProcessAlive(WATCH_PKG)) return        // 主进程还在，不用管
            Log.w(TAG, "主进程不在了 ⇒ 尝试复活")
            doRecover(WATCH_PKG)
        } catch (t: Throwable) {
            Log.w(TAG, "tick 异常: ${t.message}")
        }
    }

    /**
     * ★ 核心：用 shell 权限给自己发复活广播。
     * 主进程自己执行会被 DUMP 权限挡住 —— 这正是"为什么要独立特权进程"。
     */
    private fun doRecover(pkg: String): Int {
        if (token.isEmpty()) {
            Log.w(TAG, "还没有令牌，跳过复活")
            return -1
        }
        val cmd = arrayOf(
            "/system/bin/am", "broadcast",
            "--user", "0",
            "-a", "$pkg${KeepAliveRecoveryReceiver.ACTION_SUFFIX}",
            "-n", "$pkg/$pkg.KeepAliveRecoveryReceiver",
            "--es", "recovery_token", token
        )
        return try {
            val p = Runtime.getRuntime().exec(cmd)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            Log.i(TAG, "am broadcast exit=$code out=${out.take(120)}")
            0
        } catch (t: Throwable) {
            Log.w(TAG, "am broadcast 失败: ${t.message}")
            -2
        }
    }

    /** 亮屏判断：用 shell 权限读 dumpsys power（本进程拿不到常规 Context 服务） */
    private fun isScreenAwake(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", "power"))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        out.contains("mWakefulness=Awake") || out.contains("Wakefulness=Awake")
    } catch (_: Throwable) {
        true    // 读不到就当作亮屏，宁可多检查一次也别漏
    }

    /** 主进程是否还活着：shell 权限直接用 pidof */
    private fun isProcessAlive(pkg: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/pidof", pkg))
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        val code = p.waitFor()
        code == 0 && out.isNotEmpty()
    } catch (_: Throwable) {
        true    // 查不到就当作活着，避免误判导致疯狂复活
    }

    companion object {
        private const val TAG = "WatchUserService"
        private const val TICK_MS = 45_000L
        private const val WATCH_PKG = "com.agent.idcode"
    }
}
