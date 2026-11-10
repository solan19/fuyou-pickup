package com.agent.idcode

/**
 * ★★ v1.4.13：「在途快递」的**唯一裁决器**（重写版）。
 *
 * ## 为什么要重写（同志 2026-11-10 13:26：「重新编写在途快递的抓取和过滤规则，可以参考 1.2.5」）
 *
 * 1.2.5 那版的在途是**一条直线**：只扫「待收货」→ 8 道判据依次筛 → 剩下的就是在途，
 * 每道丢弃都写日志（vc261 的教训：静默丢弃等于没法排查）。后来每修一个 bug 就在
 * `renderInTransit` 末尾再挂一段 `removeAll`，到 1.4.12 已经叠了 6 层：
 *
 * ```
 * dedupPkgs → 终态文案 → 已有取件码（TransitMove）→ 黑名单（TransitMuted）
 *          → isTransitClearlyTerminal → 小米陈旧单 → 渲染时再判一次黑名单
 * ```
 *
 * 层与层之间靠注释互相约束（"运输中优先"、"待确认豁免"、"宁多显示"），
 * 词表却有三套（[PickupVerdict]、`renderInTransit` 里的局部 list、`PddChannel` 常量）。
 * 结果就是每次修一处、另一处又反过来咬一口 —— 「在途少件 / 已取的还在」反复出现。
 *
 * ## 现在的结构
 * **一张卡只判一次**，判据顺序固定，按 1.2.5 的宽容度来（宁可多显示，不静默丢）：
 *
 * | 序 | 判据 | 结论 |
 * |---|---|---|
 * | ① | 命中屏蔽名单，且不是运输中/待确认 | 不显示（用户删过的件） |
 * | ② | 已标记取件 | 不显示（卡在取件码页留 24h） |
 * | ③ | 已有取件码 | 不显示（归取件码栏，12:54 口径） |
 * | ④ | 无运单号 + 无物流直链 + 无文案 | 不显示（多多买菜/虚拟商品） |
 * | ⑤ | 下单超 15 天 | 不显示 |
 * | ⑥ | 订单硬终态（退款/取消/交易成功） | 不显示 |
 * | ⑦ | 已取走（已领取/取件成功…），但待收货/运输中豁免 | 不显示 |
 * | ⑧ | 到站待取，但运输中豁免（"已发往XX驿站"不是到站） | 不显示 |
 * | ⑨ | 订单「已签收」且无在途/到站/待确认信号 | 不显示 |
 * | ⑩ | 小米陈旧单 | 不显示 |
 * | ⑪ | 其余 | **留在在途** |
 *
 * 两条最容易被改坏的宽容度，写在这里当护栏：
 * · **运输中优先**：只要文案/状态里有「运输中/派送/发往/中转/揽收/预计…」，
 *   ⑦⑧⑨ 一律不生效 —— 皇帝柑就是这么被误杀的（轨迹写着"已发往XX驿站"）。
 * · **待确认收货优先**：带「待收货/待确认」的件，⑨ 不生效 —— 平台还没确认收货，
 *   说明件其实还在驿站（代签），显示出来才对。
 */
object TransitRules {

    /** 一张卡进入判定时需要的**全部**信息（不传 JSONObject，方便单测） */
    data class Card(
        val src: String = "",
        /** 订单状态文案（拼多多 order_status_prompt / 淘宝 statusDesc / 京东 status） */
        val orderStatus: String = "",
        /** 最新物流文案（shipping_message / latest） */
        val latest: String = "",
        /** 轨迹全文（可选，能补上 latest 里没有的"已签收"等） */
        val trace: String = "",
        /** **持久缓存里**这单的取件码（不是卡面上那个，卡面的可能是上次遗留） */
        val code: String = "",
        /** 真运单号 */
        val waybill: String = "",
        /** 有没有「查看物流」直链 —— 有就说明这是快递单，不是虚拟商品 */
        val hasLogisticsUrl: Boolean = false,
        /** 已取件标记时刻 */
        val pickedAt: Long = 0L,
        /** 是否命中屏蔽名单 */
        val muted: Boolean = false,
        /**
         * ★★ v1.4.14：命中的那条名单记录是**「已取件」型**（id 前缀 `picked:`）的吗？
         *
         * 这类记录只有一个来源：用户亲手点过「标记已取件」/「清已取」。
         * 它跟「长按删除」写进来的记录**不能同等对待** —— 长按删可能是误删（件还在路上），
         * 所以要留"运输中/待确认"的豁免；而"我取走了"是明确的事实陈述，
         * 平台的订单状态却常年停在「已签收，待确认收货」，一豁免就等于没标。
         */
        val mutedPicked: Boolean = false,
        /** 下单时刻（拼多多按 order_sn 前 6 位算；其它源给 0） */
        val bornAtMs: Long = 0L,
        /** 小米商城的陈旧单（有自己的时效口径） */
        val staleMi: Boolean = false
    )

    /** 判定结论：keep=true 才留在在途栏 */
    data class Decision(val keep: Boolean, val kind: Kind, val reason: String)

    enum class Kind {
        /** 留在在途栏 */
        TRANSIT,
        /** 已到站 / 已有码 —— 归取件码栏 */
        AT_STATION,
        /** 已被取走 */
        TAKEN,
        /** 退款/取消/已签收且无信号/超期 */
        TERMINAL,
        /** 不是快递（多多买菜、虚拟商品） */
        NOT_EXPRESS,
        /** 用户删过（名单） */
        MUTED,
        /** 用户手动标记已取件 */
        PICKED
    }

    // ---------------- 词表 ----------------
    // 全部取自 [PickupVerdict]（全局唯一来源），这里不再另抄一套 —— 抄一份就多一处会走偏的口径。

    /** 运输中信号（放行力度最大的那一类） */
    private val RUNNING = PickupVerdict.MSG_IN_TRANSIT

    /** 到站待取信号 */
    private val AT_STATION = PickupVerdict.MSG_ARRIVED

    /**
     * ★ v1.4.13：**「已签收，待确认」不算到站待取**。
     *
     * 它是"驿站代签、用户还没去拿"的中间态：有码的件在判据③就进取件码栏了，
     * 没码的件只能留在在途栏 —— 按"到站"剔掉就是同志 12:40 报的
     * 「既不显示在取件码页面也不显示在在途快递中」。
     */
    private val AT_STATION_STRICT = AT_STATION.filterNot { it == "已签收，待确认" }

    /**
     * ★ v1.4.13：给**抓取层**（[PddNative.inTransitScan]）用的到站词表 —— 必须是严格版。
     *
     * 13:41 真机实证踩过的坑：抓取层原来硬编码 4 个词，换成 [PickupVerdict.MSG_ARRIVED] 后
     * 把「已签收，待确认」也当成了到站，3 个还在驿站的件（洗面奶/沐浴露/柿子）当场被剔出
     * 在途候选，而它们在取件码栏又各有各的码 ⇒ 用户看到的在途从 7 件变 5 件。
     */
    val arrivalWords: List<String> get() = AT_STATION_STRICT

    /** 待确认收货，用于"这单还没完结"的豁免 */
    private val WAITING = PickupVerdict.PENDING_CONFIRM

    /**
     * ★ v1.4.13：**名单豁免只能用窄表**。
     *
     * [PickupVerdict.PENDING_CONFIRM] 里含「待收货」，而拼多多在途件的订单状态**几乎全是**
     * 「待收货」—— 名单判定要是用它豁免，名单在拼多多上等于完全失效（删了也会回来）。
     * 所以名单只用"待确认/待取/待领取"这类真正的未完结信号。
     */
    private val WAITING_NARROW = listOf("待确认", "待取", "待领取")

    /** 订单软终态（可能被物流文案翻案） */
    private val SOFT = PickupVerdict.SOFT_TERMINAL

    /** 订单硬终态（物流怎么说不救） */
    private val HARD = PickupVerdict.HARD_TERMINAL

    /**
     * 「件已经被拿走」的强词。
     *
     * 与 [PickupVerdict.SOFT_TERMINAL] 的区别：这几个词**只在轨迹里**出现时才算数，
     * 而且必须没有"待收货/运输中"打掩护 —— 代收点扫描后立刻回「已取件」，
     * 但订单还是「待收货」的情况很常见（用户还没点确认）。
     */
    val TAKEN = listOf(
        "已领取", "已取走", "已取件", "取件成功", "本人已取", "已自提",
        "代收点取件", "代收点自提", "已核销", "已签收，感谢使用"
    )

    private const val DAY_MS = 86_400_000L

    /**
     * 判一次。
     *
     * 纯函数：不读 SP、不发请求、不看时间以外的外部状态 ⇒ 可以单测、可以离线回放日志。
     * @param now 只为了让单测能固定时间；生产不传
     */
    fun judge(c: Card, now: Long = System.currentTimeMillis()): Decision {
        val status = c.orderStatus
        val latest = c.latest
        val both = status + " " + latest + " " + c.trace

        val running = RUNNING.firstOrNull { both.contains(it) }
        val waiting = WAITING.firstOrNull { both.contains(it) }
        val waitingNarrow = WAITING_NARROW.firstOrNull { both.contains(it) }
        val atWord = AT_STATION_STRICT.firstOrNull { both.contains(it) }
        val takenWord = TAKEN.firstOrNull { both.contains(it) }

        // ① 屏蔽名单 —— 但运输中/待确认的件不受影响。
        //   同志 2026-11-09 实测：皇帝柑还在运输中，因为取件码被删（= 进名单）就连在途栏一起藏了。
        //   ⚠️ 这里用 [WAITING_NARROW]，不能用含「待收货」的宽表（见其注释）。
        //
        //   ★★ v1.4.14（同志 13:53 的正根）：**「已取件」型记录不给豁免**。
        //   14:04 真机实证：取件码页标记洗面奶 → 清已取删除 → 名单写对了（生效=立即），
        //   但它在拼多多侧仍是「已签收，待确认收货」⇒ 被 `waitingNarrow` 放行 ⇒ 在途从 7 变 8，
        //   而且它还会被下面「连续 3 轮仍运输中自动移出名单」顺手打掉 —— 用户看到的就是
        //   「标记并删除后，快递又回到在途快递中」。用户亲手标的"我拿走了"是事实陈述，
        //   平台那句"待确认收货"只是他没点确认，不能反压用户的明确意图。
        if (c.muted && (c.mutedPicked || (running == null && waitingNarrow == null))) {
            return Decision(
                false, Kind.MUTED,
                if (c.mutedPicked) "已标记取件（名单）" else "命中屏蔽名单"
            )
        }

        // ② 已标记取件 —— 卡留在取件码页 24h，在途栏不重复出现。
        if (c.pickedAt != 0L) {
            return Decision(false, Kind.PICKED, "已标记取件")
        }

        // ③ 已有取件码 ⇒ 已进入取件码那条链（同志 12:54 口径）。
        if (c.code.isNotBlank()) {
            return Decision(false, Kind.AT_STATION, "已有取件码 ${c.code}")
        }

        // ④ 三无（无运单号 + 无物流直链 + 无文案）⇒ 不是快递。
        //   1.2.5 原话：多多买菜/虚拟商品的特征，详情页只会写"待收货"。
        if (c.waybill.isBlank() && !c.hasLogisticsUrl && latest.isBlank() && status.isBlank()) {
            return Decision(false, Kind.NOT_EXPRESS, "无运单号/无直链/无文案（非快递）")
        }

        // ⑤ 时效闸：没有有效快递能超过 15 天（同志 2026-10-31 定）。
        if (c.bornAtMs > 0L && now - c.bornAtMs > PickupVerdict.MAX_ORDER_AGE_DAYS * DAY_MS) {
            return Decision(false, Kind.TERMINAL, "下单超 ${PickupVerdict.MAX_ORDER_AGE_DAYS} 天")
        }

        // ⑥ 订单硬终态：退款/取消/交易成功 —— 物流文案说什么都不救。
        HARD.firstOrNull { status.contains(it) }?.let {
            return Decision(false, Kind.TERMINAL, "硬终态「$it」")
        }

        // ⑦ 已经拿走 —— 只受"运输中"豁免。
        //   ⚠️ 这里**不能**再加"待收货豁免"：轨迹写着「已取件/您的快件已被领取」时，
        //   订单状态经常还是「待收货」（用户懒得点确认收货）—— 那是真拿走了，
        //   留着它会让在途栏一直挂着一件已经到手的货。
        if (takenWord != null && running == null) {
            return Decision(false, Kind.TAKEN, "已取走「$takenWord」")
        }

        // ⑧ 到站待取 —— 只有"运输中"豁免。
        //   · 运输中豁免：轨迹里的"已发往XX驿站/预计到达XX驿站"不是到站；
        //   · 「已签收，待确认」已从 [AT_STATION_STRICT] 里排除，走不到这一档。
        if (atWord != null && running == null) {
            return Decision(false, Kind.AT_STATION, "到站待取「$atWord」")
        }

        // ⑨ 订单「已签收」且没有任何在途/到站/待确认信号 ⇒ 当作结束。
        //   1.2.5 这一档是"交 cabinet 裁决"：有码进取件码栏、无码不显示 —— 结论都是不在途。
        val soft = SOFT.firstOrNull { status.contains(it) }
        if (soft != null && running == null && waiting == null && atWord == null) {
            return Decision(false, Kind.TERMINAL, "订单「$soft」且无在途/到站信号")
        }

        // ⑩ 小米商城自带的陈旧判定（历史单的 status 恒「待收货」，只能按时效剔）。
        if (c.staleMi) {
            return Decision(false, Kind.TERMINAL, "小米陈旧单")
        }

        // ⑪ 留在在途。
        return Decision(
            true,
            Kind.TRANSIT,
            when {
                running != null -> "运输中「$running」"
                waiting != null -> "待确认收货「$waiting」"
                c.waybill.isNotBlank() -> "有运单号、状态未知"
                else -> "信息不足但在途（保守保留）"
            }
        )
    }

    /** 自测用例 —— `--ez debug_transit_selftest true` 时跑，验证判据顺序没被改坏 */
    fun selfTest(): String {
        val now = 1_800_000_000_000L
        val cases = listOf(
            Triple("运输中", Card(orderStatus = "待收货", latest = "运输中，预计明天到达", waybill = "YT1"), true),
            Triple("派送中", Card(orderStatus = "待收货", latest = "派送中，王金颖正在派送", waybill = "YT2"), true),
            Triple("已发往XX驿站（运输中）", Card(orderStatus = "待收货", latest = "已发往【长沙岳麓】驿站", waybill = "YT3"), true),
            Triple("已签收待确认", Card(orderStatus = "已签收，待确认收货", latest = "驿站已代签", waybill = "YT4"), true),
            Triple("已签收无信号", Card(orderStatus = "已签收", latest = "", waybill = "YT5"), false),
            Triple("已取走在途", Card(orderStatus = "待收货", latest = "已取件 您的快件已被领取", waybill = "YT6"), false),
            Triple("到站待取", Card(orderStatus = "待收货", latest = "快件已到站，请及时取件", waybill = "YT7"), false),
            Triple("已有取件码", Card(orderStatus = "待收货", latest = "运输中", code = "114-4-9453", waybill = "YT8"), false),
            Triple("已标记取件", Card(orderStatus = "待收货", latest = "运输中", waybill = "YT9", pickedAt = 1L), false),
            Triple("三无", Card(orderStatus = "", latest = "", waybill = ""), false),
            Triple("退款", Card(orderStatus = "退款成功", latest = "已发货", waybill = "YT10"), false),
            Triple("超15天", Card(orderStatus = "待收货", latest = "运输中", waybill = "YT11", bornAtMs = now - 16 * DAY_MS), false),
            Triple("名单但运输中", Card(orderStatus = "待收货", latest = "运输中", waybill = "YT12", muted = true), true),
            Triple("名单且到站", Card(orderStatus = "待收货", latest = "已到站，请取件", waybill = "YT13", muted = true), false),
            // ★★ v1.4.14：清已取后最常见的组合 —— 名单是「已取件」型 + 平台状态「已签收，待确认收货」
            Triple(
                "已取件名单+待确认",
                Card(orderStatus = "已签收，待确认收货", latest = "驿站已代签", waybill = "YT14", muted = true, mutedPicked = true),
                false
            ),
            // 对照：普通（长按删除的）名单记录 + 待确认 ⇒ 仍按误删保护放行
            Triple(
                "普通名单+待确认",
                Card(orderStatus = "已签收，待确认收货", latest = "驿站已代签", waybill = "YT15", muted = true),
                true
            )
        )
        val sb = StringBuilder("TransitRules 自测：\n")
        var ok = 0
        for ((name, card, expect) in cases) {
            val d = judge(card, now)
            val pass = d.keep == expect
            if (pass) ok++
            sb.append(if (pass) "  PASS " else "  FAIL ")
                .append(name).append(" → ").append(if (d.keep) "在途" else "不显示")
                .append("（").append(d.reason).append("）")
            if (!pass) sb.append("  期望=").append(if (expect) "在途" else "不显示")
            sb.append('\n')
        }
        sb.append("结果：").append(ok).append('/').append(cases.size).append(" 通过")
        return sb.toString()
    }
}
