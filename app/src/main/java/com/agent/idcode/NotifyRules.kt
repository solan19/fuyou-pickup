package com.agent.idcode

import android.content.Context

/**
 * ★★ v1.4.24：**系统通知抓码的平台规则表 —— 唯一真源**。
 *
 * ## 为什么要有这张表
 * 改之前 [IdNotifyListener] 对**任何 App** 的通知都跑同一套关键词 + 正则：
 *   · 营销push / 短信预览里只要出现「快递」二字就可能被抠出一串数字当取件码；
 *   · 抖音 / 小红书 / 团购自提常用「提货码 / 自提码 / 货架号 / 取件凭证」，
 *     旧词表只认「取件码」，这些平台的通知基本漏光；
 *   · 写进缓存的条目统一 `src = "ntf"`，取件码页只显示「通知」，看不出来自哪个平台。
 *
 * 现在：包名 → 平台 → 该平台的关键词/码词 → 抠码 → 平台标签，全部收敛到这里。
 * 白名单之外的一律不看（隐私最小面 + 误抓最少）。
 *
 * ## 规矩
 * 1. **只解析白名单平台的通知**；用户可在设置里逐个关，但不在表里的包永远不看。
 * 2. 写入码必须过 [hasPickupContext]（强信号），弱词表 [looksLikeExpress] 只用来触发即时抓取。
 * 3. 码必须过 [plausible]：不是手机号 / 不是日期 / 不是订单号 / 长度合理。
 * 4. 只提取码、驿站、运单号；**不上传、不落通知全文**。
 * 5. 所有规则与样例都在这里，自检页直接调 [replay]，不再另写一份判据。
 */
object NotifyRules {

    /** 一个可抓通知的平台 */
    data class Platform(
        val id: String,
        val label: String,
        val packages: Set<String>,
        /** 该平台常见的额外触发词（弱信号，用来触发即时抓取） */
        val extraHints: List<String> = emptyList(),
    )

    // ---------------- 平台白名单 ----------------
    // 包名取各 App 的正式发布包；同一平台多个包名（主包 / 极速版 / 商城）都列上。
    val PLATFORMS: List<Platform> = listOf(
        Platform(
            "pdd", "拼多多",
            setOf("com.xunmeng.pinduoduo"),
            listOf("多多买菜", "自提点", "团长", "待取")
        ),
        Platform(
            "dy", "抖音",
            setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"),
            listOf("团购", "自提", "提货", "抖音电商", "货架")
        ),
        Platform(
            "tb", "淘宝",
            setOf("com.taobao.taobao", "com.taobao.taobao4android", "com.tmall.wireless"),
            listOf("菜鸟", "待取", "入库", "天猫")
        ),
        Platform(
            "jd", "京东",
            setOf("com.jingdong.app.mall"),
            listOf("京东物流", "自提", "柜", "待取")
        ),
        Platform(
            "xhs", "小红书",
            setOf("com.xingin.xhs"),
            listOf("自提", "提货", "薯店", "待取")
        ),
        Platform(
            "cainiao", "菜鸟",
            setOf("com.cainiao.wireless", "com.taobao.cainiao"),
            listOf("驿站", "入库", "取件通知")
        ),
        Platform(
            "hive", "丰巢",
            setOf("com.fcbox.hive", "com.fcbox.hiveconsumer", "cn.com.fcbox.hive"),
            listOf("柜机", "格口", "滞留")
        ),
        Platform(
            "wx", "微信",
            setOf("com.tencent.mm"),
            listOf("团购", "接龙", "自提点", "团长")
        ),
        Platform(
            "ks", "快手",
            setOf("com.smile.gifmaker", "com.kuaishou.nebula"),
            listOf("小店", "自提", "提货")
        ),
        Platform(
            "mt", "美团",
            setOf("com.sankuai.meituan", "com.sankuai.meituan.waimai"),
            listOf("优选", "团", "自提点", "团长")
        ),
    )

    private val BY_PACKAGE: Map<String, Platform> = buildMap {
        for (p in PLATFORMS) for (pkg in p.packages) put(pkg, p)
    }
    private val BY_ID: Map<String, Platform> = PLATFORMS.associateBy { it.id }

    fun platformOf(pkg: String): Platform? = BY_PACKAGE[pkg]
    fun labelOf(id: String): String = BY_ID[id]?.label ?: ""
    fun allIds(): Set<String> = PLATFORMS.map { it.id }.toSet()

    // ---------------- 词表 ----------------

    /** 弱信号：像快递消息，但不足以据此写码（只用于触发即时抓取） */
    private val HINTS = listOf(
        "取件", "取货", "提货", "快递", "速递", "包裹", "驿站", "快递柜",
        "丰巢", "速递易", "菜鸟", "派件", "签收", "代收点", "快件", "送达", "柜",
        "自提", "团购", "入库", "货架", "待取", "到站"
    )

    /**
     * 强信号：文本里**必须**出现其一，才允许写入取件码。
     * 这一层专门挡「您的订单 2026111012345678 已发货」这类只有单号、没有取件语义的通知。
     */
    private val PICKUP_CONTEXT = listOf(
        "取件", "取货", "提货", "自提", "驿站", "快递柜", "丰巢", "菜鸟",
        "货架", "格口", "代收点", "入库", "派件", "待取", "取件凭证", "柜机", "柜"
    )

    /** 明写「XX码 / XX号」的前缀词 */
    private val CODE_WORDS = listOf(
        "取件码", "取货码", "提货码", "自提码", "提取码", "取件口令", "取件密码",
        "取件凭证", "取件编号", "取件序号", "货架号", "格口号", "柜号", "提货号",
        "自提编号", "凭码"
    )

    // ---------------- 抠码正则 ----------------

    /** ① 明写前缀：取件码：73-3-5888 / 货架号 B-12 / 取件码 8-2-3011
     *  首段允许 1 位（「8-2-3011」「B-12」「J-7788」都是真实格口码），词表直接驱动正则。 */
    private val P_LABELLED = Regex(
        """(?:${CODE_WORDS.joinToString("|")})[^0-9A-Za-z]{0,8}([0-9A-Za-z]{1,8}(?:[\s\-－—_][0-9A-Za-z]{1,8}){0,3})"""
    )

    /** ② 横杠格口码，且后面 10 字内出现取件语义（旧 P_NEAR 的扩展） */
    private val P_DASH = Regex(
        """(?<![0-9A-Za-z])([0-9A-Za-z]{1,6}(?:-[0-9A-Za-z]{1,6}){1,3})(?=[^0-9A-Za-z]{0,10}(?:取件|提货|自提|驿站|柜|代收点|货架|格口))"""
    )

    /** ③ 纯数字长码（快递柜常见 8 位），后面 12 字内必须有取件语义 */
    private val P_DIGITS = Regex(
        """(?<![0-9A-Za-z])(\d{6,12})(?![0-9A-Za-z])(?=[^\d]{0,12}(?:取件|提货|自提|驿站|快递柜|柜|货架|格口))"""
    )

    /** 数字前面出现这些词 ⇒ 几乎肯定是单号/电话/金额，不是取件码 */
    private val NUMBER_PREFIX_WORDS = listOf(
        "单号", "订单", "运单", "尾号", "电话", "手机", "金额", "￥", "¥", "编号:"
    )

    private val RE_MOBILE = Regex("""1[3-9]\d{9}""")
    private val RE_DATE8 = Regex("""(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])""")

    fun looksLikeExpress(text: String, p: Platform? = null): Boolean {
        if (text.isBlank()) return false
        if (HINTS.any { text.contains(it) }) return true
        return p != null && p.extraHints.any { text.contains(it) }
    }

    fun hasPickupContext(text: String): Boolean = PICKUP_CONTEXT.any { text.contains(it) }

    /** 码像不像真的：挡手机号、日期、纯 0、订单号、过长串 */
    fun plausible(code: String): Boolean {
        val c = code.trim().replace(" ", "")
        if (c.length !in 2..20) return false
        if (!c.any { it.isDigit() }) return false
        if (RE_MOBILE.matches(c)) return false
        if (c.length == 8 && RE_DATE8.matches(c)) return false
        if (c.all { it == '0' }) return false
        if (c.toSet().size == 1) return false
        // 纯 12 位以上且没有任何分隔符 ⇒ 更像订单号，交给带前缀的正则去认，这里不兜
        if (c.length >= 12 && c.all { it.isDigit() }) return false
        return true
    }

    /** 文本里在某位置之前 10 字内是否出现「单号/订单/电话」等（用于挡纯数字误抓） */
    private fun looksLikeOrderNumber(text: String, at: Int): Boolean {
        val from = (at - 10).coerceAtLeast(0)
        val before = text.substring(from, at)
        return NUMBER_PREFIX_WORDS.any { before.contains(it) }
    }

    /**
     * 抠取件码：按「明写前缀 → 横杠格口 → 纯数字长码」依次尝试，每个候选都过 [plausible]。
     * 返回空串表示没抠到（调用方应转去触发即时抓取，而不是硬写一个脏码）。
     */
    fun pickCode(text: String): String {
        if (text.isBlank()) return ""
        P_LABELLED.findAll(text).forEach { m ->
            val c = (m.groupValues.getOrNull(1) ?: "").trim()
            if (plausible(c)) return normalize(c)
        }
        P_DASH.findAll(text).forEach { m ->
            val c = (m.groupValues.getOrNull(1) ?: "").trim()
            if (plausible(c)) return normalize(c)
        }
        P_DIGITS.findAll(text).forEach { m ->
            val c = (m.groupValues.getOrNull(1) ?: "").trim()
            if (!plausible(c)) return@forEach
            if (looksLikeOrderNumber(text, m.range.first)) return@forEach
            return normalize(c)
        }
        return ""
    }

    /** 统一分隔符：全角横杠/下划线/空白 → 半角横杠，避免同码因写法不同被当成两个 */
    private fun normalize(code: String): String = code
        .replace("－", "-").replace("—", "-").replace("_", "-")
        .replace(Regex("""\s*-\s*"""), "-")
        .replace(" ", "")
        .trim()

    /** 从通知文本里尽力认出运单号（常写整号或「尾号XXXX」） */
    fun waybillOf(text: String): String {
        val m = Regex("""(?:运单号|快递单号|单号|尾号)[是为：:\s]*([0-9A-Za-z]{6,})""").find(text)
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 从通知文本里尽力认出驿站 / 快递柜名 */
    fun stationOf(text: String): String {
        val m = Regex(
            """([\u4e00-\u9fa5A-Za-z0-9]{2,18}(?:驿站|快递柜|代收点|自提点|服务点|便利店|柜机|丰巢|菜鸟))"""
        ).find(text)
        return m?.groupValues?.get(1)?.trim()?.take(60).orEmpty()
    }

    // ---------------- 自检回放（L3） ----------------

    data class Sample(val platform: String, val text: String, val expect: String)
    data class ReplayResult(
        val platform: String, val text: String, val expect: String,
        val actual: String, val pass: Boolean, val context: Boolean,
    )

    /** 各平台真实格式近似样例 + 必须抠不出来的反例；自检页与离线回放脚本共用这一份 */
    val SAMPLES: List<Sample> = listOf(
        Sample("pdd", "您的快递已到达【北门菜鸟驿站】，取件码 73-3-5888，请及时领取", "73-3-5888"),
        Sample("pdd", "多多买菜：您的订单已到自提点，提货码 A12-3456，凭码取货", "A12-3456"),
        Sample("dy", "【抖音电商】包裹已到 XX 自提点，提货码：D8-2201，请于今日取件", "D8-2201"),
        Sample("dy", "团购自提通知：货架号 B-12，请到 3 号自提点提货", "B-12"),
        Sample("tb", "菜鸟驿站：您的包裹已入库，取件码 8-2-3011，地址：南门驿站", "8-2-3011"),
        Sample("tb", "【淘宝】快递已到，取件码：TB8899，凭码到驿站取件", "TB8899"),
        Sample("jd", "京东物流：快件已放入丰巢快递柜，取件码 12345678，请及时取件", "12345678"),
        Sample("jd", "京东自提点通知：取件凭证 J-7788，请到店提货", "J-7788"),
        Sample("xhs", "【小红书】订单已到达自提点，取件码：XHS-8899，记得来取", "XHS-8899"),
        Sample("xhs", "薯店自提：提货码 66-1-2200，货架 A3", "66-1-2200"),
        Sample("cainiao", "取件码 12-3-4567，包裹已到 XX 菜鸟驿站，请尽快领取", "12-3-4567"),
        Sample("hive", "丰巢柜机取件码 88990011，格口 3 号，请取件", "88990011"),
        Sample("wx", "团长通知：团购到货，自提码 WX-3312，请到自提点取货", "WX-3312"),
        Sample("ks", "快手小店：提货码 55-8-9001，请到 XX 驿站取件", "55-8-9001"),
        Sample("mt", "美团优选：货架号 M-09，请到自提点提货", "M-09"),
        // ---- 反例：必须抠不出码 ----
        Sample("pdd", "您的订单 2026111012345678 已发货，请耐心等待", ""),
        Sample("tb", "客服电话 13800138000 为您服务", ""),
        Sample("jd", "【活动】满 200 减 30，点击领取优惠券", ""),
        Sample("dy", "您关注的商品价格已更新，快来看看", ""),
    )

    /** 逐条回放；自检页直接显示 PASS/FAIL，离线脚本也可用同一份数据 */
    fun replay(): List<ReplayResult> = SAMPLES.map { s ->
        val ctx = hasPickupContext(s.text)
        val got = if (ctx) pickCode(s.text) else ""
        ReplayResult(s.platform, s.text, s.expect, got, got == s.expect, ctx)
    }
}

/**
 * 通知抓取的开关偏好（与 [NotifyRules] 同文件，避免设置页再写一份 SP 键）。
 * 默认：总开关开（兼容 v1.4.23 之前已在跑的通知抓码）、白名单内平台全开、白名单外一律不解析。
 */
object NotifyPrefs {
    private const val PREF = "idcode_prefs"
    private const val KEY_ENABLED = "notify_pickup_enabled"
    private const val KEY_PLATFORMS = "notify_platforms"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 未配置过 = 白名单全部开启 */
    fun enabledPlatforms(ctx: Context): Set<String> {
        val raw = sp(ctx).getString(KEY_PLATFORMS, null) ?: return NotifyRules.allIds()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun platformEnabled(ctx: Context, id: String): Boolean = enabledPlatforms(ctx).contains(id)

    fun setPlatformEnabled(ctx: Context, id: String, on: Boolean) {
        val cur = enabledPlatforms(ctx).toMutableSet()
        if (on) cur.add(id) else cur.remove(id)
        sp(ctx).edit().putString(KEY_PLATFORMS, cur.sorted().joinToString(",")).apply()
    }

    /** 唯一准入闸：总开关 + 包名在白名单 + 该平台被用户勾选 */
    fun isAllowed(ctx: Context, pkg: String): Boolean {
        if (!enabled(ctx)) return false
        val p = NotifyRules.platformOf(pkg) ?: return false
        return platformEnabled(ctx, p.id)
    }
}
