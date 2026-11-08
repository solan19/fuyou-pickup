package roro.stellar.userservice

/**
 * 来源：roro2239/Stellar-API @ 1.0.3（userservice 模块, MPL-2.0）
 *
 * ★ 为什么把这几个文件拷进本项目自编译（2026-10-15）：
 *   JitPack 对 1.0.3 的发布命令（jitpack.yml）**漏列了 userservice 模块**
 *   （只发布 aidl/shared/api/provider）⇒ Maven 上取不到这个 artifact。
 *   在装了 Stellar 的设备上，保护进程必须走 Stellar 原生通道
 *   （`Stellar.getService().startUserService`）——"伪装成 Shizuku 的兼容层"实测不接管
 *   Shizuku API（`Shizuku.pingBinder()` 恒 false）。
 *   ⇒ 把模块源码拷进来（3 个必需文件），依赖同版本(1.0.3)的 api/aidl，自编译。
 */
enum class ServiceMode(val value: Int) {
    ONE_TIME(0),

    DAEMON(1);

    companion object {
        @JvmStatic
        fun fromValue(value: Int): ServiceMode = entries.find { it.value == value } ?: ONE_TIME
    }
}
