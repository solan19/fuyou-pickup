package com.agent.idcode

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 后台快递轮询（WorkManager 周期任务）。
 *
 * ## 它解决什么
 * 不开 App 也能发现物流变化 → 发通知。App 是否在运行、屏幕是否亮着都不影响。
 *
 * ## 一次执行做什么
 * 1. 读设置：间隔 ≤ 0（关闭）或跟踪名单为空 → 直接返回，什么都不干。
 * 2. [TrackFetcher] 去抓「被跟踪单号所属的那些源」（只抓需要的源，不做全量四源同步）。
 * 3. 与上一份快照逐条比 `(statusText, latest)`：
 *    - **旧快照里没有这条** ⇒ 首次见到：运输中/已完成只记基线不通知；
 *      但**已在驿站/派件中的补报一条**（vc301 —— 首见常因旧版本漏收/新装 App，
 *      到站件不报用户就永远不知道，2026-10-17 极兔件实测）
 *    - 指纹变了 ⇒ 发通知
 * 4. 合并写回快照（**保留本次没抓到的旧条目** —— 某个源失败了不代表那个包裹不存在，
 *    整表覆盖会把基线清掉，下次真变化时反而漏通知）。
 *
 * ## 为什么用 `Result.retry()`
 * 只有**抓取抛异常**才 retry（网络抖动、登录态刚失效）；抓取成功但结果为空是正常情况
 * （那批包裹本来就没变化），retry 会造成没必要的重试风暴。这一点与参考项目的取舍一致。
 *
 * ★ 本类运行在**没有 Activity** 的进程环境里：不能碰 MainActivity 的任何成员，
 *   所有要读的东西都必须落在 SP（见 [ExpressStore]）。
 */
class TrackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val interval = ExpressStore.pollIntervalMin(ctx)
        if (interval <= 0) {
            Log.i(TAG, "轮询已关闭（间隔=0），跳过")
            return Result.success()
        }
        // ★ vc267（后台省电）：电量 ≤15% 且未充电 ⇒ 本轮不抓。
        //   快递数据晚一轮没关系，低电量时每一分电都该留给系统。充电时不受限。
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        if (bm != null) {
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 1..15 && !bm.isCharging) {
                Log.i(TAG, "电量 $level% 且未充电 ⇒ 本轮跳过（省电）")
                return Result.success()
            }
        }
        val mode = ExpressStore.trackMode(ctx)
        val tracked = ExpressStore.trackedNos(ctx)
        val muted = ExpressStore.mutedNos(ctx)
        // ★「指定」模式下没有任何标记就没什么可干的；「全部」模式永远值得跑一趟
        if (mode == ExpressStore.MODE_MANUAL && tracked.isEmpty()) {
            Log.i(TAG, "指定模式且跟踪名单为空，跳过")
            return Result.success()
        }

        val old = ExpressStore.load(ctx).associateBy { it.no }
        val fetchResult = try {
            TrackFetcher.fetchDetailed(ctx, tracked)
        } catch (e: Throwable) {
            Log.w(TAG, "抓取失败: ${e.message}")
            return Result.retry()
        }
        val fresh = fetchResult.items
        // ★ vc553：UI 黑名单（pickup_muted，键 "src:单号"）在后台同样生效 ——
        //   不再对它们发通知、不再写回快照/缓存（京东"已完成却反复回来"的件靠这个挡）。
        val mutedUi = ctx.getSharedPreferences("idcode", android.content.Context.MODE_PRIVATE)
            .getStringSet("pickup_muted", null)?.toSet() ?: emptySet()
        val freshFiltered = fresh.filter { it -> "${it.src}:${it.no}" !in mutedUi }
        if (freshFiltered.size != fresh.size) {
            Log.i(TAG, "黑名单剔除 ${fresh.size - freshFiltered.size} 条（不上报/不通知）")
        }
        val filteredSources = fetchResult.sources.mapValues { (_, source) ->
            val items = source.items.filter { "${it.src}:${it.no}" !in mutedUi }
            val hasMutedItems = items.size != source.items.size
            source.copy(
                items = items,
                transitOk = source.transitOk && !hasMutedItems
            )
        }
        if (freshFiltered.isEmpty()) {
            Log.i(TAG, "本次抓取无数据（mode=$mode tracked=${tracked.size}）")
            // 可信源的成功空结果可清除其旧途中卡；失败、未登录或未运行的源保持旧缓存。
            UiCacheSync.syncFromWorker(ctx, emptyList(), filteredSources)
            HomeWidgets.updateAll(ctx)
            return Result.success()
        }

        var notified = 0
        // ★ 流体云（Android 16 提升型通知 / ColorOS 实况位）：本次该上的取件码
        val live = ArrayList<LiveUpdate.Entry>()
        // ★ vc256：已取件的码不许再上流体云（PddCodeStore 里带 pickedAt 标记的）。
        //   vc229 起"取走了留 24h 才删"，这 24h 里码还在缓存里 —— 不滤就会一直挂着。
        //   （10-14 19:21 实测：19-2-7008 已取件仍被补推 4 次）
        val pickedCodes = try {
            PddCodeStore.all(ctx).values.filter { c -> c.pickedAt != 0L }
                .map { c -> c.code }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
        for (it in freshFiltered) {
            // ★ 流体云与「要不要发普通通知」是**两件事**：
            //   普通通知受 notifyReason 的阶段跃迁限制（只在派件中/已到达响一次），
            //   而实况位是**常驻显示**的 —— 只要"到了驿站且有取件码"就该挂着，
            //   哪怕这一轮没有阶段跃迁（比如用户手动清了通知、或 App 刚装上）。
            if (it.pickupCode.isNotBlank() && it.pickupCode !in pickedCodes &&
                    ExpressStore.stageOf(it) == ExpressStore.Stage.ARRIVED) {
                live.add(LiveUpdate.Entry(it.no, it.pickupCode, it.stationText, it.company))
            }
            // ★ 判定走 ExpressStore.notifyReason —— 与闭环自检用的是**同一份逻辑**
            val reason = ExpressStore.notifyReason(old[it.no], it, mode, tracked, muted)
            if (reason == null) continue
            Log.i(TAG, "通知 ${it.no}：$reason（「${old[it.no]?.statusText}」→「${it.statusText}」）")
            TrackNotifier.notifyUpdate(
                ctx, it.no, it.company,
                it.latest.ifBlank { it.statusText },
                it.stationText
            )
            notified++
        }
        // ★ 同步实况位（全量语义：本次没了的会被撤下 —— 已取出的件不该继续挂在流体云上）
        LiveUpdate.sync(ctx, live)
        // ★ vc321 超级岛：同一份列表喂给岛同步（非澎湃OS 设备内部自会跳过，不发一条）
        IslandHelper.sync(ctx, live.map { IslandHelper.Entry(it.no, it.code, it.station, it.company) })

        // 合并：新抓到的覆盖旧的，本次没抓到的旧条目原样保留
        val merged = old.toMutableMap()
        for (it in fresh) merged[it.no] = it
        ExpressStore.save(ctx, merged.values.toList())

        // ★ vc281：把本轮抓到的数据同步进第 4/5 栏的持久缓存（PddCodeStore / transit_cache.json）。
        //   以前后台轮询只发通知，UI 缓存完全不知道 —— 用户打开 App 看到的还是上一次
        //   手动刷新的结果，还常因"第 5 栏没等全部源回来就没落盘"而空白。
        //   同步之后：打开 App 秒显的就是这一轮的后台数据，"刚打开即最新，不用等刷新"。
        UiCacheSync.syncFromWorker(ctx, freshFiltered, filteredSources)

        // ★ vc289：途中快递小组件吃这份快照 —— 后台每轮抓完顺手推桌面
        HomeWidgets.updateAll(ctx)

        // ★ vc358：后台轮询也给「取件码卡」做已取判定 ——
        //   以前轮询只盯在途件，取件码卡要等用户打开 App 才刷新。
        //   现在开关「自动打已取标签」开着时：拿拼多多自己的枚举把仍待取的码查一遍，
        //   出库（OUT_CABINET/GOT）就打灰标；打标满 24h 的卡顺手清掉
        //   （以前只有打开取件码页才会触发清理）。
        try {
            val tagOn = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
                .getBoolean("auto_picked_tag", false)
            val nowMs = System.currentTimeMillis()
            val store = PddCodeStore.all(ctx)
            var cleaned = 0
            for ((k, c) in store) {
                if (PddCodeStore.pickedExpired(c, nowMs)) {
                    PddCodeStore.remove(ctx, k)
                    cleaned++
                }
            }
            if (tagOn) {
                val pend = store.filter { (k, c) ->
                    k.startsWith("pdd:") && c.code.isNotEmpty() && c.pickedAt == 0L
                }
                if (pend.isNotEmpty()) {
                    val sns = pend.keys.map { it.removePrefix("pdd:") }
                    val enums = PddNative.trackStatusesForCtx(ctx, sns)
                    var marked = 0
                    for ((k, c) in pend) {
                        val sn = k.removePrefix("pdd:")
                        val st = enums[sn] ?: continue
                        if (st == "OUT_CABINET" || st == "GOT") {
                            PddCodeStore.put(ctx, k, c.copy(pickedAt = System.currentTimeMillis()))
                            marked++
                        }
                    }
                    Log.i(TAG, "轮询打标：候选 ${sns.size} 单 → 已取 $marked")
                }
            }
            if (cleaned > 0) Log.i(TAG, "轮询清理过期灰标卡 $cleaned 张")
        } catch (t: Throwable) {
            Log.w(TAG, "轮询打标失败: ${t.message}")
        }

        Log.i(TAG, "完成：抓取=${freshFiltered.size} 跟踪=${tracked.size} 新通知=$notified 快照=${merged.size}")
        return Result.success()
    }

    companion object {
        private const val TAG = "TrackWorker"
    }
}
