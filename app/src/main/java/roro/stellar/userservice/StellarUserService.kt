package roro.stellar.userservice

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.stellar.server.IUserServiceCallback
import roro.stellar.Stellar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "StellarUserService"

/**
 * 来源：roro2239/Stellar-API @ 1.0.3（userservice 模块, MPL-2.0）
 * 拷入本项目自编译的原因见 ServiceMode.kt 头注。
 *
 * 它是**主进程侧**的绑定封装：`Stellar.getService().startUserService(bundle, callback)`
 * —— 走 Stellar 原生通道（与"伪装成 Shizuku"的兼容层无关）。
 */
object StellarUserService {

    interface ServiceCallback {
        fun onServiceConnected(service: IBinder)
        fun onServiceDisconnected()
        fun onServiceStartFailed(errorCode: Int, message: String) {}
    }

    private val activeConnections = ConcurrentHashMap<String, ConnectionRecord>()

    private class ConnectionRecord(
        val args: UserServiceArgs,
        val callback: ServiceCallback,
        val handler: Handler?,
        var token: String? = null,
        var binder: IBinder? = null,
        var verificationToken: String = UUID.randomUUID().toString()
    )

    @JvmStatic
    @JvmOverloads
    fun bindUserService(
        args: UserServiceArgs,
        callback: ServiceCallback,
        handler: Handler? = Handler(Looper.getMainLooper()),
        // ★★★ 本地修复（2026-10-15，蜉蝣项目）：允许调用方提供**稳定的**校验令牌。
        //   上游原版每次 bind 都新生成 UUID —— 而服务端"**复用已存在的服务**"时
        //   持有的还是**首次创建**时传入的令牌 ⇒ 必然"验证令牌不匹配，拒绝连接"，
        //   且服务不会消散（ONE_TIME 只在客户端正常断开时才停）⇒ 永久死锁。
        //   调用方传入持久化令牌后：首次=后续 恒等 ⇒ 复用场景也能正确匹配。
        verificationToken: String? = null
    ) {
        Log.i(TAG, "class=${args.className}, suffix=${args.processNameSuffix}")

        val service = Stellar.getService()
        if (service == null) {
            Log.e(TAG, "Stellar service 不可用")
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "Stellar service 不可用")
            }
            return
        }

        val packageName = Stellar.getPackageName()
        if (packageName == null) {
            Log.e(TAG, "拿不到 packageName")
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "拿不到 packageName")
            }
            return
        }
        Log.d(TAG, "packageName=$packageName")

        val key = getConnectionKey(args)

        val existing = activeConnections[key]
        if (existing?.binder?.pingBinder() == true) {
            dispatchCallback(handler) {
                callback.onServiceConnected(existing.binder!!)
            }
            return
        }

        val record = ConnectionRecord(args, callback, handler)
        if (!verificationToken.isNullOrEmpty()) {
            record.verificationToken = verificationToken   // ★ 本地修复：用调用方给的稳定令牌
        }
        activeConnections[key] = record

        val aidlCallback = createAidlCallback(record, key, handler)

        try {
            val bundle = args.toBundle(packageName)
            bundle.putString(UserServiceArgs.ARG_VERIFICATION_TOKEN, record.verificationToken)
            val token = service.startUserService(bundle, aidlCallback)
            Log.i(TAG, "token=$token")
            record.token = token
        } catch (e: RemoteException) {
            Log.e(TAG, "startUserService 异常", e)
            activeConnections.remove(key)
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "远程异常: ${e.message}")
            }
        }
    }

    @JvmStatic
    fun unbindUserService(args: UserServiceArgs) {
        val key = getConnectionKey(args)
        Log.i(TAG, "unbind key=$key")

        val record = activeConnections[key]
        if (record == null) {
            Log.w(TAG, "没有正在进行的连接: key=$key")
            return
        }

        val token = record.token
        if (token == null) {
            activeConnections.remove(key)
            dispatchCallback(record.handler) {
                record.callback.onServiceDisconnected()
            }
            return
        }

        val service = Stellar.getService()
        if (service == null) {
            activeConnections.remove(key)
            dispatchCallback(record.handler) {
                record.callback.onServiceDisconnected()
            }
            return
        }

        activeConnections.remove(key)

        try {
            Log.i(TAG, "stop token=$token")
            service.stopUserService(token)
        } catch (e: RemoteException) {
            Log.e(TAG, "stopUserService 异常", e)
        }

        dispatchCallback(record.handler) {
            record.callback.onServiceDisconnected()
        }
    }

    @JvmStatic
    fun peekUserService(args: UserServiceArgs): IBinder? {
        val key = getConnectionKey(args)
        val record = activeConnections[key] ?: return null
        return if (record.binder?.pingBinder() == true) record.binder else null
    }

    @JvmStatic
    fun getUserServiceCount(): Int {
        val service = Stellar.getService() ?: return 0
        return try {
            service.userServiceCount
        } catch (e: RemoteException) {
            0
        }
    }

    private fun getConnectionKey(args: UserServiceArgs): String =
        "${args.className}:${args.processNameSuffix}"

    private fun dispatchCallback(handler: Handler?, action: () -> Unit) {
        handler?.post(action) ?: action()
    }

    private fun createAidlCallback(
        record: ConnectionRecord,
        key: String,
        handler: Handler?
    ): IUserServiceCallback.Stub {
        return object : IUserServiceCallback.Stub() {
            override fun onServiceConnected(service: IBinder, returnedToken: String?) {
                Log.i(TAG, "服务已连接, binder=$service token=${returnedToken?.take(8)}")
                if (returnedToken != record.verificationToken) {
                    Log.e(TAG, "验证令牌不匹配，拒绝连接（回=${returnedToken?.take(8)} 期=${record.verificationToken.take(8)}）")
                    activeConnections.remove(key)
                    dispatchCallback(handler) {
                        record.callback.onServiceStartFailed(-2, "验证令牌不匹配")
                    }
                    return
                }
                record.binder = service
                dispatchCallback(handler) {
                    record.callback.onServiceConnected(service)
                }
            }

            override fun onServiceDisconnected() {
                Log.i(TAG, "服务断开连接")
                record.binder = null
                record.verificationToken = UUID.randomUUID().toString()
                activeConnections.remove(key)
                dispatchCallback(handler) {
                    record.callback.onServiceDisconnected()
                }
            }

            override fun onServiceStartFailed(errorCode: Int, message: String) {
                Log.e(TAG, "服务启动失败, code=$errorCode, msg=$message")
                activeConnections.remove(key)
                dispatchCallback(handler) {
                    record.callback.onServiceStartFailed(errorCode, message)
                }
            }
        }
    }
}
