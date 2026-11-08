package com.agent.idcode

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * ★ 控制中心快捷磁贴（同志 2026-10-15 提出的想法，非常妙）
 *
 * ## 它同时干三件事
 * 1. **防杀**：磁贴由 SystemUI 亲自绑定，属"系统绑定型服务"；
 *    Android 13+ 声明 `ACTIVE_TILE` 后系统会**保持绑定并在进程死后重绑** ——
 *    与手册第 3 层（NotificationListenerService）同理，是第二条系统绑定路径。
 * 2. **合法唤醒**：用户点一下磁贴 ⇒ 系统把进程拉起来干活。
 *    这是**用户主动触发**，不是偷偷保活，完全合规，也最不容易被系统惩罚。
 * 3. **真有用**：一键开关快递跟踪、点一下立刻查一次，不用进 App。
 *
 * ⚠️ 磁贴要用户自己在控制中心编辑栏里拖出来才会显示（系统限制，App 无权自动添加）。
 */
class IdQuickTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val wasRunning = KeepAliveService.isRunning(this)
        if (wasRunning) {
            // 关：停掉常驻服务（轮询照旧可以在 App 里改回来）
            try {
                stopService(Intent(this, KeepAliveService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止常驻服务失败: ${t.message}")
            }
            Log.i(TAG, "磁贴点击 ⇒ 已关闭常驻")
        } else {
            // 开：确保轮询间隔 > 0，拉起服务，并**立刻查一次**
            if (ExpressStore.pollIntervalMin(this) <= 0) {
                ExpressStore.savePollIntervalMin(this, ExpressStore.MIN_INTERVAL_MIN)
            }
            KeepAliveService.start(this)
            try {
                TrackScheduler.runNow(this)
            } catch (t: Throwable) {
                Log.w(TAG, "立即查询失败: ${t.message}")
            }
            Log.i(TAG, "磁贴点击 ⇒ 已开启常驻并立刻查一次")
        }
        updateTile()
    }

    private fun updateTile() {
        val t = qsTile ?: return
        val running = KeepAliveService.isRunning(this)
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (running) "快递跟踪 开" else "快递跟踪 关"
        // ★ 显式设置单色条码图标（与 App 图标同源）：Manifest 声明在部分系统版本
        //   不生效、会显示成方形 App 图标的缩略图（同志反馈难看的正是这个）
        try {
            t.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (t: Throwable) { SafeRun.w("IdQuickTile", t) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.subtitle = pendingText()
        }
        t.updateTile()
    }

    /** 副标题显示待取件数（取不到就给操作提示） */
    private fun pendingText(): String = try {
        val n = PddCodeStore.all(this).values.count { c -> c.pickedAt == 0L }
        if (n > 0) "$n 个待取" else "点一下刷新"
    } catch (_: Throwable) {
        "点一下刷新"
    }

    companion object {
        private const val TAG = "IdQuickTile"
    }
}
