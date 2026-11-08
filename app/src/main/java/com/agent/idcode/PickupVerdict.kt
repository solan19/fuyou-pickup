package com.agent.idcode

/**
 * ★ vc614：**单一裁决器** —— 一个件该进取件码栏、途中栏、还是两栏都不显示，只在这里判一次。
 *
 * ## 为什么要它（同志 2026-10-31 反馈"规则非常乱"）
 * 之前同一件事分散在三处判：
 *  · `renderMerged`（取件码栏）自己一套终态词；
 *  · `renderInTransit`（途中栏）自己一套；
 *  · `PddNative.inTransitScan` / `PddChannel.parseInTransit` 又各一套（枚举 + 文案）。
 * 结果是同名不同义、各判一次、口径靠注释对齐，历史 bug 基本都是这么来的：
 *  「6 个在途只显示 5 个」「两栏都看不见」「同一码两张卡」。
 *
 * ## 设计原则
 * 1. **纯函数**：`decide(Input) → Verdict`，不碰 Context / SP / 网络，可以单测；
 * 2. **只说结论 + 原因**：`Verdict.sink` 三选一，`reason` 直接进日志；
 * 3. **优先级固定**：黑名单 > 已取件标记 > 三无 > 时效 > 枚举 > 物流文案 > 订单状态 > 默认；
 * 4. **保守**：规则照抄现有行为，只把散落的判断收口，不趁机改口径。
 */
object PickupVerdict {

    /** 这个件最终落哪一栏 */
    enum class Sink { PICKUP, TRANSIT, HIDDEN }

    /**
     * 裁决输入。字段名刻意与 `JSONObject` 里的键对齐，方便调用方直接喂。
     * 不适用的字段给空串 / 0L。
     */
    data class Input(
        val src: String,
        /** 枚举状态：拼多多 `IN_CABINET/GOT/OUT_CABINET/SIGN/DEPARTURE`；淘宝 `STA_INBOUND/STA_SIGN/TRANSPORT…` */
        val enumState: String? = null,
        /** 订单状态文案（拼多多 order_status_prompt / 淘宝 statusDesc / 京东 status） */
        val orderStatus: String = "",
        /** 物流文案 / 最新轨迹（拼多多 shipping_message、各家 latest） */
        val shippingMsg: String = "",
        /** 取件码，空 = 没有 */
        val code: String = "",
        /** 运单号，空 = 没有 */
        val waybill: String = "",
        /** 下单时间（毫秒），0 = 未知；只有拼多多用得上（15 天闸） */
        val bornAtMs: Long = 0L,
        /** 缓存写入时间（毫秒），0 = 非缓存卡；全部源用 48 小时闸 */
        val cacheTs: Long = 0L,
        /** 已取件灰标时间，非 0 = 用户已取（或枚举判定已取） */
        val pickedAt: Long = 0L,
        /** 是否在用户黑名单里（长按删过） */
        val muted: Boolean = false,
        val nowMs: Long = System.currentTimeMillis()
    )

    data class Verdict(val sink: Sink, val reason: String) {
        val hidden: Boolean get() = sink == Sink.HIDDEN
        val pickup: Boolean get() = sink == Sink.PICKUP
        val transit: Boolean get() = sink == Sink.TRANSIT
    }

    // ---------------- 词表（唯一来源） ----------------

    /** 硬终态：不管物流怎么说都不该显示（退款/取消/交易完成…） */
    val HARD_TERMINAL = listOf(
        "已取消", "交易关闭", "已关闭", "退款", "售后", "待付款", "未支付",
        "交易成功", "已评价", "待评价", "待发货", "待分享", "补寄成功", "已失效"
    )

    /** 软终态：可能被物流文案翻案（驿站代签「已签收待确认」就是这一类） */
    val SOFT_TERMINAL = listOf("已签收", "已收货", "已完成", "完成", "已取走", "已取件", "已取货")

    /** 文案里的「在途」信号 */
    val MSG_IN_TRANSIT = listOf(
        "运输中", "运送中", "派送", "派件", "发往", "已揽收", "已收件", "中转", "分拣",
        "转运中心", "分拨中心", "预计明天", "预计今天", "预计后天", "预计送达", "预计",
        "已发货", "已出库", "干线", "到达网点", "离开"
    )

    /**
     * 文案里的「已到站/待取」信号 —— 有它就该进取件码栏（哪怕还没码）。
     *
     * ★★ vc623：**必须只收"确定性到站词"**。原来把「驿站 / 快递柜 / 代收点 / 门卫」也算进来，
     * 而运输文案里到处是"已发往 XX 驿站 / 到达 XX 代收点"—— 结果 4 个还在路上的件
     * 全被判成"已到站"，途中栏直接少了 4 条（同志 2026-10-31 12:24 实测：
     * 5 个在途只显示 3 个，取件码多出 2 张占位卡）。
     */
    val MSG_ARRIVED = listOf(
        // ★★ vc632：**「已到达」也删掉** —— 运输文案常写「快件已到达【XX分拨中心】」，
        //   那是中转站，不是驿站。现在只认"到站/入柜/待取件"这类不可能出现在运输途中的词。
        "已到站", "已入柜", "已放入柜", "待取件", "待自提", "待领取",
        "已签收，待确认", "请及时取件"
    )

    /** 澄清词：跟软终态同时出现时，说明「件还在驿站，只是被代签了」 */
    val PENDING_CONFIRM = listOf("待确认", "待收货", "待取", "待领取")

    // ---------------- 枚举表 ----------------

    private val PDD_TAKEN = setOf("GOT")
    private val PDD_PICKUP = setOf("IN_CABINET")

    /**
     * ★★ vc655：**"枚举说这单已经取走了"的唯一真值 = GOT**。
     *
     * 事故（2026-11-01 20:13 真机日志实证）：副号刚补出 4 个码，5 毫秒后
     * `renderMerged` 的缓存清理把其中一个删了 —— 因为那张表把 `OUT_CABINET` 也当"非待取"。
     * 而 `OUT_CABINET` 的真实含义是"出了柜、驿站代签、件还在"（vc347 实证：
     * 「已签收，待确认收货」就是这个枚举）。
     *
     * ⇒ 缓存消除（"这码还要不要留"）必须与裁决器同一口径：**只有 GOT 算取走**。
     *   `OUT_CABINET` / `SIGN` 留在缓存里等文案翻案，`ARRIVAL` / `DEPARTURE` 是还没到站。
     *   两处再分叉一次，就又是一轮"补了又消失"。
     */
    fun enumSaysTaken(st: String?): Boolean =
        st != null && st.uppercase().trim() in PDD_TAKEN
    /** 出了柜 ≠ 已取走（vc347 实证：「已签收，待确认收货」就是它） */
    private val PDD_AMBIGUOUS = setOf("OUT_CABINET", "SIGN")
    private val PDD_TRANSIT = setOf("DEPARTURE", "ARRIVAL")

    private val TB_PICKUP = setOf("STA_INBOUND", "INBOUND")
    private val TB_TAKEN = setOf("STA_SIGN", "SIGN", "SIGNED", "AGENT_SIGN", "OUTBOUND", "RETURN_SIGN")
    private val TB_TRANSIT = setOf(
        "TRANSPORT", "ON_THE_WAY", "SEND_ON", "ARRIVE_CITY", "DELIVERING",
        "ACCEPT", "WAIT_ACCEPT", "RECEIVE", "DEPARTURE"
    )

    /**
     * ★★ vc616：**取件码/在途的订单年龄上限 = 15 天**（同志 2026-10-31 定）。
     * 依据："没有一个有效快递能超过 15 天" —— 超过就一定不是待取件，属历史噪声。
     * 原来写死 45 天，散在 4 个文件里（拼多多缓存/在途扫描、小米途中、裁决器），
     * 现在统一成这一处，改天数只改这里。
     */
    const val MAX_ORDER_AGE_DAYS = TimeGates.ORDER_MAX_AGE_DAYS

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /**
     * 裁决。顺序即优先级，**不要调换** —— 调换会改变历史行为（例如把文案判在枚举前面）。
     */
    fun decide(i: Input): Verdict {
        val status = i.orderStatus
        val msg = i.shippingMsg
        val en = i.enumState?.uppercase()?.trim().orEmpty()

        // ① 黑名单：用户长按删过，任何情况都不显示
        if (i.muted) return Verdict(Sink.HIDDEN, "黑名单")

        // ② 已取件灰标：卡留在取件码栏（24h 后由清理链删除）
        if (i.pickedAt != 0L) return Verdict(Sink.PICKUP, "已取件标记（灰标保留）")

        // ③ 三无：没码、没运单、没物流文案 ⇒ 不是快递（多多买菜/虚拟商品）
        if (i.code.isBlank() && i.waybill.isBlank() && msg.isBlank() && status.isBlank()) {
            return Verdict(Sink.HIDDEN, "无码无运单无物流（不是快递）")
        }

        // ④ 时效闸
        if (i.bornAtMs > 0L && i.nowMs - i.bornAtMs > MAX_ORDER_AGE_DAYS * DAY_MS) {
            return Verdict(Sink.HIDDEN, "下单超 $MAX_ORDER_AGE_DAYS 天")
        }
        if (i.cacheTs > 0L && i.nowMs - i.cacheTs > 48 * HOUR_MS) {
            return Verdict(Sink.HIDDEN, "缓存超 48 小时未更新")
        }

        // ⑤ 枚举（最权威，但 OUT_CABINET/SIGN 要留给文案翻案）
        if (en.isNotEmpty()) {
            when (i.src) {
                "pdd" -> when {
                    en in PDD_TAKEN -> return Verdict(Sink.HIDDEN, "枚举 $en（已取走）")
                    en in PDD_PICKUP -> return Verdict(Sink.PICKUP, "枚举 $en（在驿站待取）")
                    en in PDD_AMBIGUOUS -> {
                        if (!hasPendingConfirm(status) && !hasPendingConfirm(msg)) {
                            return Verdict(Sink.HIDDEN, "枚举 $en 且文案无待确认（按已取走）")
                        }
                        // 否则往下走文案判定（驿站代签、件还在）
                    }
                    en in PDD_TRANSIT -> return Verdict(Sink.TRANSIT, "枚举 $en（在途）")
                }
                "tb", "cainiao" -> when {
                    en in TB_TAKEN -> return Verdict(Sink.HIDDEN, "枚举 $en（已签收/已取出）")
                    en in TB_PICKUP -> return Verdict(Sink.PICKUP, "枚举 $en（已入站）")
                    en in TB_TRANSIT -> return Verdict(Sink.TRANSIT, "枚举 $en（运输中）")
                }
            }
        }

        // ⑥ 硬终态（订单状态）—— 文案也救不回来
        HARD_TERMINAL.firstOrNull { status.contains(it) }?.let {
            return Verdict(Sink.HIDDEN, "硬终态「$it」")
        }

        // ⑦ 有取件码 ⇒ 取件码栏
        if (i.code.isNotBlank()) {
            // 码在手但状态是软终态、且文案没有「待确认」⇒ 这码是旧的（10-24 已取走的那个案子）
            val soft = SOFT_TERMINAL.firstOrNull { status.contains(it) }
            if (soft != null && !hasPendingConfirm(status) && !hasPendingConfirm(msg) &&
                MSG_IN_TRANSIT.none { msg.contains(it) }
            ) {
                return Verdict(Sink.HIDDEN, "有码但状态「$soft」且无待确认（旧码）")
            }
            return Verdict(Sink.PICKUP, "有取件码")
        }

        // ⑧ 没码：看文案
        val msgInTransit = MSG_IN_TRANSIT.any { msg.contains(it) }
        val msgArrived = MSG_ARRIVED.any { msg.contains(it) } || MSG_ARRIVED.any { status.contains(it) }
        val soft = SOFT_TERMINAL.firstOrNull { status.contains(it) }
        val pending = hasPendingConfirm(status) || hasPendingConfirm(msg)

        if (pending || msgArrived) return Verdict(Sink.PICKUP, "文案显示已到站/待取（无码占位）")
        if (msgInTransit) return Verdict(Sink.TRANSIT, "物流文案在途")
        if (soft != null) return Verdict(Sink.HIDDEN, "软终态「$soft」且无在途信号")

        // ⑨ 兜底：有运单号就当在途（没有码也没到站）
        if (i.waybill.isNotBlank()) return Verdict(Sink.TRANSIT, "有运单号、状态未知（默认在途）")
        return Verdict(Sink.HIDDEN, "信息不足，无法判定")
    }

    private fun hasPendingConfirm(s: String): Boolean =
        s.isNotEmpty() && PENDING_CONFIRM.any { s.contains(it) }

    /**
     * 自测：`--ez debug_verdict_selftest true` 时跑一遍，把结果打到日志 + 落盘。
     * 只验证"规则按预期分流"，不依赖网络/账号。
     */
    fun selfTest(): String {
        val now = System.currentTimeMillis()
        val cases = listOf(
            // 名称, Input, 期望 sink
            Triple("PDD 在驿站待取", Input("pdd", "IN_CABINET", "待收货", "", "1-2-1", "YT1", nowMs = now), Sink.PICKUP),
            Triple("PDD 已取走", Input("pdd", "GOT", "待收货", "", "1-2-1", "YT1", nowMs = now), Sink.HIDDEN),
            Triple("PDD 出柜待确认", Input("pdd", "OUT_CABINET", "已签收，待确认收货", "", "", "YT2", nowMs = now), Sink.PICKUP),
            Triple("PDD 出柜无待确认", Input("pdd", "OUT_CABINET", "已签收", "", "", "YT3", nowMs = now), Sink.HIDDEN),
            Triple("PDD 运输中", Input("pdd", null, "运输中，预计明天到达", "运输中", "", "YT4", nowMs = now), Sink.TRANSIT),
            Triple("PDD 退款", Input("pdd", null, "退款成功", "已发货", "", "YT5", nowMs = now), Sink.HIDDEN),
            Triple("PDD 超15天", Input("pdd", null, "待收货", "运输中", "", "YT6", nowMs = now, bornAtMs = now - 16 * DAY_MS), Sink.HIDDEN),
            Triple("PDD 三无", Input("pdd", null, "", "", "", "", nowMs = now), Sink.HIDDEN),
            Triple("TB 入站", Input("tb", "STA_INBOUND", "已到驿站", "", "8-1-2", "TB1", nowMs = now), Sink.PICKUP),
            Triple("TB 运输中", Input("tb", "TRANSPORT", "运输中", "运输中", "", "TB2", nowMs = now), Sink.TRANSIT),
            Triple("TB 已签收", Input("tb", "STA_SIGN", "已签收", "", "", "TB3", nowMs = now), Sink.HIDDEN),
            Triple("京东 派送中", Input("jd", null, "派送中", "派送中", "", "JD1", nowMs = now), Sink.TRANSIT),
            Triple("旧码（已取走文案）", Input("pdd", null, "已取件 2026-10-24 您的快件已取走", "", "21-1-5009", "YT7", nowMs = now), Sink.HIDDEN)
        )
        val sb = StringBuilder("PickupVerdict 自测：\n")
        var ok = 0
        for ((name, input, expect) in cases) {
            val v = decide(input)
            val pass = v.sink == expect
            if (pass) ok++
            sb.append(if (pass) "  PASS " else "  FAIL ")
                .append(name).append(" → ").append(v.sink).append("（").append(v.reason).append("）")
                .append(if (pass) "" else "  期望=" + expect).append('\n')
        }
        sb.append("结果：$ok/${cases.size} 通过")
        return sb.toString()
    }
}
