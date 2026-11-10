package com.agent.idcode

import android.graphics.Color
import android.view.animation.PathInterpolator

/**
 * ★ ColorOS 17「凝光」设计令牌 —— **唯一真源**。
 *
 * v1.4.19 起从 Linear 风格切到凝光（ColorOS 17 的系统语言）。规矩改了三条，其余沿用：
 *  · 间距只有 8 档：4 / 8 / 12 / 16 / 20 / 24 / 32 / 40（禁 6/10/14/18/22/30）
 *  · 圆角只有 6 档：**8 / 12 / 16 / 22 / 28 / 999** —— 全部按**超椭圆**渲染（见 [SquircleDrawable]），
 *    不是四分之一圆弧；22 是分组卡、28 是主卡，999 只给胶囊
 *  · 字号（v2 马卡龙稿口径）：11 / 11.5 / 12 / 12.5 / 13 / 13.5 / 14 / 15 / 16 / 17 / 19 / 20 / 24 / **26** / **36** / 41 / **68**
 *    （41 = 首页取件码、36 = 取件码卡数字、68 = 首页待取数字、26 = 页面标题 —— 这几个是"唯一的大字"）
 *  · 字重只用 Regular(400) / Medium(500)，禁 Bold
 *  · 动画只有 5 档：100 / 190 / 240 / 320 / 380（硬上限 450）
 *  · 曲线只有 4 条：standard / decelerate / accelerate / spring
 *  · 强调色只有 cAccent（昼 #0080FF / 夜 #1A8CFF）
 *  · **v2 马卡龙光色层（2026-11-10）**：樱/薰/薄荷/奶油/蜜桃只作**底色与光色**（chip、图标底、
 *    身份码格、状态胶囊、环境光），一个都不进正文文字；白卡保持纯白，彩色只从卡背后透出来
 *  · **允许渐变，只有两种用途**：主按钮的垂直凝光（#2E9BFF → #0080FF）、开关轨道（#8FC0FA → #0080FF）
 */
object UiTok {

    // ---------- 间距（4dp 栅格，只有 8 档） ----------
    const val S4 = 4
    const val S8 = 8
    const val S12 = 12
    const val S16 = 16
    const val S20 = 20
    const val S24 = 24
    const val S32 = 32
    const val S40 = 40

    // ---------- 圆角（6 档，均为超椭圆） ----------
    const val R6 = 8          // v1.4.19：8 起档（原 6，太尖，不像系统件）
    const val R10 = 12
    const val R14 = 16
    const val R20 = 22        // 分组卡 / 行卡
    const val R28 = 28        // 主卡 / 悬浮面板
    const val R999 = 999      // 胶囊（按钮 / chip）

    // ---------- 字号（v2 稿 1:1） ----------
    const val T11 = 11f
    /** 日期 / 单号行 / 行内说明 / 提示（v2: 11.5px） */
    const val T115 = 11.5f
    const val T12 = 12f
    /** 行卡描述 / 轨迹行（v2: 12.5px） */
    const val T125 = 12.5f
    const val T13 = 13f
    /** 顶部小胶囊（v2: 13.5px） */
    const val T135 = 13.5f
    /** 行卡右侧值（v2: 14px） */
    const val T14 = 14f
    const val T15 = 15f
    /** 行卡主标题（v2: 16px） */
    const val T16 = 16f
    const val T17 = 17f
    /** 行卡数字（v2: 19px） */
    const val T19 = 19f
    const val T20 = 20f
    const val T24 = 24f
    /** 页面标题（v2 .bar h2 / .set-h: 26px） */
    const val T26 = 26f
    const val T28 = 28f
    /** 取件码卡数字（v2 .ccard .num: 36px，等宽 + tabular） */
    const val T36 = 36f
    /** 首页主卡取件码（v2 .code: 41px，等宽 + tabular） */
    const val T41 = 41f
    /** 首页"待取 N"的 N（v2 .count .n: 68px） */
    const val T68 = 68f
    /** v2 起待取数字走 [T68]，此档仅旧调用点兼容 */
    const val T56 = 56f

    // ---------- 行高倍数 ----------
    const val LINE_BODY = 1.5f
    const val LINE_TITLE = 1.3f

    // ---------- 动画时长（5 档） ----------
    const val D100 = 100L
    const val D190 = 190L
    const val D240 = 240L
    const val D320 = 320L
    const val D380 = 380L
    const val D_MAX = 450L

    // ---------- 组件尺寸 ----------
    const val TOUCH_MIN = 48
    const val ICON = 24
    const val ICON_S = 20
    const val BRAND_TILE = 36
    const val ROW_H = 64
    const val HAIR = 1
    const val BTN_H = 44
    /** 主按钮高度档：44 / **48**（卡片底部大按钮用 48） */
    const val S48Dp = 48
    /** v2 .herofoot / .tbtn 胶囊按钮高度：40 */
    const val S40Dp = 40

    // ---------- 曲线（全项目只有这 4 条） ----------
    val standard: PathInterpolator by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }
    val decelerate: PathInterpolator by lazy { PathInterpolator(0.05f, 0.7f, 0.1f, 1f) }
    val accelerate: PathInterpolator by lazy { PathInterpolator(0.3f, 0f, 0.8f, 0.15f) }

    /** 品牌色按透明度提亮（10% 亮色 / 15% 暗色），用于 36dp 方块底 */
    fun brandWithAlpha(c: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    /** 品牌方块透明度：亮色 26(≈10%) / 暗色 38(≈15%) */
    fun brandTileAlpha(dark: Boolean): Int = if (dark) 38 else 26

    /** 分割线色：亮色 12% 黑 / 暗色 20% 白（凝光的两档，比旧版更明确） */
    fun dividerColor(dark: Boolean): Int = if (dark) 0x29FFFFFF else 0x14000000

    // ---------- 凝光色板（取自 ColorOS 17 复刻库 COUI 的 CouiPresets） ----------
    /** 页面底色：昼 #F0F1F2 / 夜 **纯黑**（不是近黑 —— 凝光的黑是真空的，卡片才浮得起来） */
    fun pageBg(dark: Boolean): Int = if (dark) 0xFF000000.toInt() else 0xFFF0F1F2.toInt()

    /**
     * 卡片底色（v2「半融入背景」）：昼不再是死白一块，而是**半透明白的垂直渐变** ——
     * 顶部 92% 白（保文字对比度）、底部 84% 白（让四角马卡龙环境光从背后透出来）。
     * 夜仍是 10% 白半透（叠在纯黑上 = #1A1A1A，等于系统的半透明卡）。
     * 返回 [cardBgTop] / [cardBgBottom] 两个端点，交给 [SquircleDrawable] 铺渐变。
     */
    fun cardBgTop(dark: Boolean): Int = if (dark) 0x1AFFFFFF else 0xEBFFFFFF.toInt()
    fun cardBgBottom(dark: Boolean): Int = if (dark) 0x1AFFFFFF else 0xD6FFFFFF.toInt()
    /** 兼容旧调用点（纯色场景） */
    fun cardBg(dark: Boolean): Int = cardBgTop(dark)

    /** 面板层（顶栏 / 抽屉）：夜景比卡片再亮一档 #1E1E1E */
    fun panelBg(dark: Boolean): Int = if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()

    /** 卡片描边：昼 6% 黑 / 夜 10% 白 —— 只用来勾边，不要加深 */
    fun cardStroke(dark: Boolean): Int = if (dark) 0x1AFFFFFF else 0x0F000000

    /** 主按钮的凝光蓝：上浅下深，光从上面来 */
    fun accentTop(dark: Boolean): Int = if (dark) 0xFF3D9DFF.toInt() else 0xFF2E9BFF.toInt()
    fun accentBottom(dark: Boolean): Int = if (dark) 0xFF1478E0.toInt() else 0xFF0080FF.toInt()

    /** 强调色的 10~15% 淡底（chip / 选中态） */
    /** 强调色淡底（v2 天青：rgba(120,180,255,.20) —— 比旧版 .10 蓝更奶，亮度接近） */
    fun accentSoft(dark: Boolean): Int = if (dark) 0x331A8CFF else 0x3378B4FF

    // ---------- 凝光效果（管线：背景采样 → 模糊 → 材质混色 → 轮廓光） ----------
    /** 背景采样降采样分母（1/8）——采样图小、GPU 模糊便宜；一加 13 与低端机同参 */
    const val SAMPLE_SCALE = 8

    /** 玻璃面高斯模糊半径（dp）——只给悬浮面（dock / 悬浮按钮），实心卡不需要 */
    const val BLUR_GLASS = 22

    /**
     * 材质第一层（**先 luminosity**）：昼冷白 70% / 夜深灰 60%。
     * 作用是把背景明度压到材质本色的明度上 —— 玻璃才"雾"得住（CouiMaterial 口径）。
     */
    fun matFirst(dark: Boolean): Int = if (dark) 0x9912161C.toInt() else 0xB3EEF1F4.toInt()

    /** 材质第二层（以 blendAlpha 混合）：昼白 40% / 夜白 10% */
    fun matSecond(dark: Boolean): Int = if (dark) 0x1AFFFFFF else 0x66FFFFFF

    /** 第二层混合强度（COUI 的 blend alpha；模式固定 Overlay，文档默认值） */
    const val MAT_BLEND_ALPHA = 0.55f

    /** 轮廓光近边线（顶，亮）——CouiContourPresets 的 near 线（0.7α ≈ 55% 白） */
    const val CONTOUR_NEAR = 0x8C

    /** 轮廓光远边线（底，暗）——CouiContourPresets 的 far 线（0.6α ≈ 30% 白） */
    const val CONTOUR_FAR = 0x4D

    // ================= v2 马卡龙光色层（2026-11-10） =================
    // 规矩：低饱和 + 高明度，**只作底色/光色，一个都不进正文文字**；
    // 白卡保持纯白 —— 彩色只能从卡背后的环境光透出来（被磨砂面采样），
    // 或出现在本来就有底色的小面（chip / 图标底 / 身份码格 / 状态胶囊）。

    /** 樱粉 —— 平台胶囊 / 身份码格 1 */
    fun macRose(dark: Boolean): Int = if (dark) 0x24FFA8C7 else 0xFFFFE7EF.toInt()

    /** 薰衣草 —— 身份码格 2 / 分段控件底 */
    fun macLilac(dark: Boolean): Int = if (dark) 0x26BAA6FF else 0xFFEBE4FF.toInt()

    /** 薄荷 —— 身份码格 3 */
    fun macMint(dark: Boolean): Int = if (dark) 0x2192E6C6 else 0xFFDDF6EC.toInt()

    /** 奶油 —— 环境光用 */
    fun macLemon(dark: Boolean): Int = if (dark) 0x24FFDC92 else 0xFFFFF3D6.toInt()

    /** 蜜桃 —— warm 状态胶囊（派送中） */
    fun macPeach(dark: Boolean): Int = if (dark) 0x2EFFBE9B else 0xFFFFF1E8.toInt()

    /** 平台胶囊底（樱粉） */
    fun platBg(dark: Boolean): Int = if (dark) 0x29FFA8C7 else 0xFFFFE7EF.toInt()

    /** 平台胶囊字（深樱粉，白/黑底都够对比） */
    fun platText(dark: Boolean): Int = if (dark) 0xFFFFB3CC.toInt() else 0xFFB23A5E.toInt()

    /** 商品名胶囊底（中性灰） */
    fun gtagBg(dark: Boolean): Int = if (dark) 0x1FFFFFFF else 0x0D000000

    /** warm 状态胶囊字（派送中） */
    fun warmText(dark: Boolean): Int = if (dark) 0xFFF0B945.toInt() else 0xFFB07800.toInt()

    /** 分段控件容器底（薰衣草） */
    fun segBg(dark: Boolean): Int = if (dark) 0x1FFFFFFF else 0x26BAA6FF

    /** 分段控件选中项（浅色=白 + 投影；深色=22% 白） */
    fun segSelBg(dark: Boolean): Int = if (dark) 0x38FFFFFF else 0xFFFFFFFF.toInt()

    /** 开关轨道渐变（v2 .sw-t：#8FC0FA → #0080FF；夜间实色） */
    fun switchTrackTop(dark: Boolean): Int = if (dark) 0xFF1A8CFF.toInt() else 0xFF8FC0FA.toInt()
    fun switchTrackBottom(dark: Boolean): Int = if (dark) 0xFF1A8CFF.toInt() else 0xFF0080FF.toInt()

    /** 开关关闭轨道 */
    fun switchOff(dark: Boolean): Int = if (dark) 0x33FFFFFF else 0x1F000000

    /** 状态点绿（v2 --green） */
    fun greenDot(dark: Boolean): Int = if (dark) 0xFF4FD35B.toInt() else 0xFF00BD13.toInt()

    /** 图标底（蓝变体：天青底 + 强调字） */
    fun iconBlueBg(dark: Boolean): Int = accentSoft(dark)

    /** 图标底（绿变体：薄荷底 + 绿字） */
    fun iconGreenBg(dark: Boolean): Int = if (dark) 0x2992E6C6 else macMint(dark)
    fun iconGreenText(dark: Boolean): Int = if (dark) 0xFF4FD35B.toInt() else 0xFF00A011.toInt()

    // ---------- 环境光（页面背后四角的马卡龙径向光晕，v2 .frame::before） ----------
    /** 昼：四角柔光（rose 右上 / lilac 左上 / lemon 右中 / mint 左下） */
    val AMBIENT_LIGHT: IntArray = intArrayOf(
        0x45FFA8C7.toInt(), 0x3DBAA6FF.toInt(), 0x38FFDC92.toInt(), 0x3D92E6C6.toInt()
    )
    /** 夜：更强一档（rose 右上 / lilac 左上 / mint 底中 / lemon 右中） */
    val AMBIENT_DARK: IntArray = intArrayOf(
        0x57FF9EC4.toInt(), 0x4DBAA6FF.toInt(), 0x2992E6C6.toInt(), 0x1FFFDC92.toInt()
    )

    // ---------- 卡片软阴影（v2 box-shadow: 0 8px 26px -18px rgba(15,26,44,.42)） ----------
    /** 阴影色：昼偏蓝的墨色（不是纯黑），夜纯黑 */
    fun cardShadow(dark: Boolean): Int = if (dark) 0x66000000 else 0x6B0F1A2C
}
