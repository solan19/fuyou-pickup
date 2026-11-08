package com.agent.idcode

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台轮询的排程器。设置的开关/间隔一变就调 [apply] 重排。
 *
 * ★ 为什么用 `enqueueUniquePeriodicWork` + `UPDATE` 而不是先 cancel 再 enqueue：
 *   UPDATE 会**保留原有的下次执行时间**，只在参数变化时替换请求 —— 用户改一下间隔不会把
 *   已经等了 14 分钟的计时清零重来。参考项目也是这个策略。
 *
 * ★ `PeriodicWorkRequest` 的周期下限是 15 分钟（低于它直接抛 IllegalArgumentException），
 *   所以这里用 `maxOf(设置值, 15)`；用户填 1~14 分钟也只会在 15 分钟粒度上跑，
 *   设置弹窗里要如实告诉用户这一点。
 */
object TrackScheduler {

    private const val TAG = "TrackScheduler"
    private const val WORK = "idcode_track_poll"

    /** 按当前设置重排。间隔 ≤ 0 表示关闭 → 撤销已排的任务 */
    fun apply(ctx: Context) {
        val wm = try {
            WorkManager.getInstance(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "WorkManager 拿不到（未初始化？）: ${e.message}")
            return
        }
        val min = ExpressStore.pollIntervalMin(ctx)
        if (min <= 0) {
            wm.cancelUniqueWork(WORK)
            Log.i(TAG, "轮询关闭，已撤销排程")
            return
        }
        val interval = maxOf(min, ExpressStore.MIN_INTERVAL_MIN).toLong()
        val req = PeriodicWorkRequestBuilder<TrackWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // ★ vc267（后台省电）：系统判定低电量时自动不跑（与 TrackWorker 里的
                    //   「≤15% 且未充电跳过」是两道互补的闸 —— 这道管周期任务，那道管常驻服务的 runNow）
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        Log.i(TAG, "已排程：每 $interval 分钟")
    }

    /** 立刻跑一次（调试/用户手动触发用，不影响周期排程） */
    fun runNow(ctx: Context) {
        try {
            WorkManager.getInstance(ctx)
                .enqueue(OneTimeWorkRequestBuilder<TrackWorker>().build())
            Log.i(TAG, "已投递一次性执行请求")
        } catch (e: Throwable) {
            Log.w(TAG, "一次性执行投递失败: ${e.message}")
        }
    }
}
