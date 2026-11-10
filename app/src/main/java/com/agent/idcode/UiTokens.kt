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
 *  · 字号只有 10 级：11 / 12 / 13 / 15 / 17 / 20 / 24 / 28 / **41 / 56**
 *    （41 = 取件码、56 = 首页待取数字；这两个是"唯一的大字"，别的元素不许跟它们抢）
 *  · 字重只用 Regular(400) / Medium(500)，禁 Bold
 *  · 动画只有 5 档：100 / 190 / 240 / 320 / 380（硬上限 450）
 *  · 曲线只有 4 条：standard / decelerate / accelerate / spring
 *  · 强调色只有 cAccent（昼 #0080FF / 夜 #1A8CFF）；品牌色只许出现在 36dp 小方块里
 *  · **允许渐变，但只有一种用途**：主按钮的垂直凝光（#2E9BFF → #0080FF），其余一律纯色
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

    // ---------- 字号（10 级） ----------
    const val T11 = 11f
    const val T12 = 12f
    const val T13 = 13f
    const val T15 = 15f
    const val T17 = 17f
    const val T20 = 20f
    const val T24 = 24f
    const val T28 = 28f
    /** 取件码专用（等宽 + tabular，眼睛能逐位核对）—— 全 App 只有它和 T56 算大字 */
    const val T41 = 41f
    /** 首页"待取 N 件"的 N */
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
    fun dividerColor(dark: Boolean): Int = if (dark) 0x33FFFFFF else 0x1F000000

    // ---------- 凝光色板（取自 ColorOS 17 复刻库 COUI 的 CouiPresets） ----------
    /** 页面底色：昼 #F0F1F2 / 夜 **纯黑**（不是近黑 —— 凝光的黑是真空的，卡片才浮得起来） */
    fun pageBg(dark: Boolean): Int = if (dark) 0xFF000000.toInt() else 0xFFF0F1F2.toInt()

    /** 卡片底色：昼纯白 / 夜 10% 白（叠在纯黑上 = #1A1A1A，等于系统的半透明卡） */
    fun cardBg(dark: Boolean): Int = if (dark) 0x1AFFFFFF else 0xFFFFFFFF.toInt()

    /** 面板层（顶栏 / 抽屉）：夜景比卡片再亮一档 #1E1E1E */
    fun panelBg(dark: Boolean): Int = if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()

    /** 卡片描边：昼 6% 黑 / 夜 10% 白 —— 只用来勾边，不要加深 */
    fun cardStroke(dark: Boolean): Int = if (dark) 0x1AFFFFFF else 0x0F000000

    /** 主按钮的凝光蓝：上浅下深，光从上面来 */
    fun accentTop(dark: Boolean): Int = if (dark) 0xFF3D9DFF.toInt() else 0xFF2E9BFF.toInt()
    fun accentBottom(dark: Boolean): Int = if (dark) 0xFF1478E0.toInt() else 0xFF0080FF.toInt()

    /** 强调色的 10~15% 淡底（chip / 选中态） */
    fun accentSoft(dark: Boolean): Int = if (dark) 0x331A8CFF else 0x1A0080FF

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
}
