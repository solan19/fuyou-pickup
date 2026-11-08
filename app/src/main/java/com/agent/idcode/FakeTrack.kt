package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 伪造数据源（2026-10-14 端到端测试专用，vc262）。
 *
 * ## 解决什么问题
 * 要验证「唤醒式轮询 → 快照比对 → 通知/流体云」全链路，但不能：
 *  - 拿假单号问快递100 —— **查无结果照样收费**（项目铁律）
 *  - 真抓拼多多 —— 触碰风控 + 多端互斥把同志手机上的 PDD App 顶下线
 *  ⇒ 造一条"看起来像抓到的"数据，走完剩下的全部真实链路。
 *
 * ## 用法（调试参数只在 onCreate 读 ⇒ 必须先 am force-stop）
 * ```
 * adb shell am start -n com.agent.idcode/.MainActivity \
 *   --es debug_fake_track '{"no":"TEST262","company":"圆通速递","pickupCode":"3-1-2345","station":"测试菜鸟驿站"}'
 * ```
 * 写入伪造计划 + 往快照里种一条"运输中"旧状态（基线）。
 * 之后每一轮 [TrackFetcher] 消费一级，Worker 走真实的比对/通知/流体云逻辑：
 *  - 第 1 轮：派送中         ⇒ 与基线比 ⇒ 阶段跃迁 ⇒ **「派件中」通知**
 *  - 第 2 轮：已到驿站+取件码 ⇒ 与上一轮比 ⇒ **「已到达」通知 + 取件码上流体云**
 *  - 第 3 轮起：状态不变      ⇒ 阶段没动 ⇒ **不再通知**（顺带验收稳态不重复）
 *
 * 清除（恢复正常抓取）：`--ez debug_fake_clear true`
 *
 * ## 文案为什么这么写
 * [ExpressStore.stageOf] 的判定词是硬编码的，伪造文案必须精确命中：
 *  - "派送" → DELIVERING；"驿站"/"取件码" → ARRIVED
 *  - 种基线用"已到达XX转运中心"（含"转运"）确保判成 NORMAL 而不是 ARRIVED
 *    （W_TRANSIT_HUB 排在 W_ARRIVED 前面，这是 2026-09-20 修过的中转误报坑）
 */
object FakeTrack {

    private const val TAG = "FakeTrack"
    private const val KEY = "fake_track_plan"
    private const val KEY_LAST = "fake_track_last_ms"

    /** 两轮消费的最小间隔（毫秒）。挡掉积压 Job 批量补跑造成的连发 —— 否则一秒内
     *  状态就被推到底，等 15 分钟的那一轮反而不跃迁了。 */
    private const val MIN_GAP_MS = 60_000L

    data class Plan(
        val no: String,
        val company: String,
        val pickupCode: String,
        val station: String,
        var cursor: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("no", no)
            put("company", company)
            put("pickupCode", pickupCode)
            put("station", station)
            put("cursor", cursor)
        }
    }

    fun load(ctx: Context): Plan? {
        val raw = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val no = o.optString("no")
            if (no.isBlank()) null
            else Plan(
                no = no,
                company = o.optString("company"),
                pickupCode = o.optString("pickupCode"),
                station = o.optString("station"),
                cursor = o.optInt("cursor", 0)
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun save(ctx: Context, p: Plan) {
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, p.toJson().toString()).apply()
    }

    fun clear(ctx: Context) {
        val p = load(ctx)
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .edit().remove(KEY).remove(KEY_LAST).apply()
        // ★ 一并把快照里的同号记录删掉：否则第 5 栏会留一张"测试菜鸟驿站"的卡，
        //   流体云还挂着它的取件码（LiveUpdate 是全量语义，快照没了下次同步才撤）。
        if (p != null) {
            val old = ExpressStore.load(ctx)
            val rest = old.filter { it.no != p.no }
            if (rest.size != old.size) {
                ExpressStore.save(ctx, rest)
                Log.i(TAG, "已从快照移除测试单号 ${p.no}（剩 ${rest.size} 条）")
            }
        }
        Log.i(TAG, "伪造计划已清除，恢复真实抓取")
    }

    /**
     * 安装伪造计划 + 种基线。只动同号那条，快照里其他单原样保留。
     * @param cursor 起始游标；0 = 首轮返回基线级（见 [next] 的保护②）
     */
    fun install(ctx: Context, p: Plan, seedBaseline: Boolean = true) {
        save(ctx, p)
        // 把"上次消费"压到当前时刻：注入瞬间的积压 Job 连发全被最小间隔挡掉，
        // 第一跳留给 15 分钟后那一轮自然轮询
        ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        if (seedBaseline) seedSnapshot(ctx, p)
        Log.i(TAG, "伪造计划已安装：no=${p.no} company=${p.company} cursor=${p.cursor}")
    }

    /** 往快照种一条"运输中"旧状态（首次判定的 prev，没有它第一轮只会记基线不通知） */
    private fun seedSnapshot(ctx: Context, p: Plan) {
        val old = ExpressStore.load(ctx)
        val seeded = ExpressStore.Item(
            no = p.no,
            src = "fake",
            company = p.company,
            statusText = "运输中",
            latest = "快件已到达南昌转运中心",
            pickupCode = "",
            stationText = ""
        )
        val merged = old.filter { it.no != p.no } + seeded
        ExpressStore.save(ctx, merged)
        Log.i(TAG, "基线已种：${p.no} = 运输中（快照共 ${merged.size} 条）")
    }

    /**
     * 消费一级并返回对应 Item。到顶后保持最后一级（稳态，验收"不重复通知"）。
     * 返回 null = 没有伪造计划（调用方走真实抓取）。
     *
     * ★ 两个保护（2026-10-14 实测补）：
     *  ① **最小间隔 60 秒**：App 重启后 WorkManager 的积压 Job 会批量补跑，一秒内抓 4 次。
     *     不挡的话两跳状态瞬间消费完，15 分钟后那一轮反而不跃迁、收不到通知。
     *  ② **cursor=0 时返回基线级（运输中）**：与种下的快照相同 ⇒ 无跃迁 ⇒ 注入瞬间零通知，
     *     "划掉等 15 分钟"的测试时序才干净。
     */
    fun next(ctx: Context): ExpressStore.Item? {
        val p = load(ctx) ?: return null
        val sp = ctx.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST, 0L)
        val now = System.currentTimeMillis()
        if (now - last >= MIN_GAP_MS) {
            if (p.cursor < 2) p.cursor += 1
            sp.edit().putLong(KEY_LAST, now).apply()
            save(ctx, p)
        }
        Log.i(TAG, "伪造数据生效：cursor=${p.cursor} no=${p.no}（距上次 ${now - last}ms）")
        return when (p.cursor) {
            0 -> ExpressStore.Item(
                no = p.no, src = "fake", company = p.company,
                statusText = "运输中",
                latest = "快件已到达南昌转运中心",
                pickupCode = "", stationText = ""
            )
            1 -> ExpressStore.Item(
                no = p.no, src = "fake", company = p.company,
                statusText = "派送中",
                latest = "快递员张小四正在派送，电话 138****1234",
                pickupCode = "", stationText = ""
            )
            else -> ExpressStore.Item(
                no = p.no, src = "fake", company = p.company,
                statusText = "在驿站待取",
                latest = "已暂存至${p.station}，取件码 ${p.pickupCode}",
                pickupCode = p.pickupCode, stationText = p.station
            )
        }
    }
}
