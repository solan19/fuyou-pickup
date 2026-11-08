package com.agent.idcode

import android.graphics.Color
import android.view.animation.PathInterpolator

/**
 * ★ ColorOS 17「流体设计」设计令牌 —— **唯一真源**。
 *
 * 规矩（来自 UI 重做要求，改动时别破坏）：
 *  · 间距只有 8 档：4 / 8 / 12 / 16 / 20 / 24 / 32 / 40（禁 6/10/14/18/22/30）
 *  · 圆角只有 6 档：6 / 10 / 14 / 20 / 28 / 999
 *  · 字号只有 8 级：11 / 12 / 13 / 15 / 17 / 20 / 24 / 28（禁 14/16/18/22 与半像素）
 *  · 字重只用 Regular(400) / Medium(500)，禁 Bold
 *  · 动画只有 5 档：100 / 190 / 240 / 320 / 380（硬上限 450）
 *  · 曲线只有 4 条：standard / decelerate / accelerate / spring
 *  · 强调色只有 cAccent；品牌色只许出现在 36dp 小方块里（底色品牌色 10%/15%）
 *  · 禁止任何渐变
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

    // ---------- 圆角（6 档） ----------
    const val R6 = 6
    const val R10 = 10
    const val R14 = 14
    const val R20 = 20
    const val R28 = 28
    const val R999 = 999

    // ---------- 字号（8 级） ----------
    const val T11 = 11f
    const val T12 = 12f
    const val T13 = 13f
    const val T15 = 15f
    const val T17 = 17f
    const val T20 = 20f
    const val T24 = 24f
    const val T28 = 28f

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

    /** 分割线色：亮色 #08000000 / 暗色 #0AFFFFFF */
    fun dividerColor(dark: Boolean): Int = if (dark) 0x0AFFFFFF else 0x08000000
}
