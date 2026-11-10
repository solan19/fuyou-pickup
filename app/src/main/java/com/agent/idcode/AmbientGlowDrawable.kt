package com.agent.idcode

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * ★ v2 马卡龙「环境光」—— 页面背后的四角柔光（D-凝光-v2.html 的 `.frame::before`）。
 *
 * 为什么需要它：白卡不透明，马卡龙彩色进不了卡面 ⇒ 彩色只能从**卡背后**透出来。
 * 这层光铺在页面最底，卡片浮在它上面（软阴影把卡托起来），磨砂面（dock/设置键）采样时
 * 采到的就是带色的光 —— 凝光的"光"就来自这里。
 *
 * 昼：四角极淡（rose 右上 / lilac 左上 / lemon 右中 / mint 左下）；
 * 夜：更强一档（纯黑底才显色）。四组都是椭圆径向渐变（拉伸 canvas 近似椭圆）。
 */
class AmbientGlowDrawable(private val dark: Boolean) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint()

    // (cxRatio, cyRatio, rxRatio, ryRatio, color) —— 照 v2 .frame::before 的位置与尺寸
    private val glows: Array<FloatArray> = if (dark) arrayOf(
        floatArrayOf(0.78f, -0.04f, 0.70f, 0.40f, 0x57FF9EC4.toFloat()),
        floatArrayOf(0.06f, 0.16f, 0.52f, 0.34f, 0x4DBAA6FF.toFloat()),
        floatArrayOf(0.50f, 1.04f, 0.70f, 0.34f, 0x2992E6C6.toFloat()),
        floatArrayOf(1.00f, 0.58f, 0.44f, 0.24f, 0x1FFFDC92.toFloat()),
    ) else arrayOf(
        floatArrayOf(0.82f, -0.02f, 0.58f, 0.34f, 0x45FFA8C7.toFloat()),
        floatArrayOf(0.04f, 0.04f, 0.46f, 0.28f, 0x3DBAA6FF.toFloat()),
        floatArrayOf(0.98f, 0.52f, 0.44f, 0.26f, 0x38FFDC92.toFloat()),
        floatArrayOf(-0.04f, 0.74f, 0.48f, 0.30f, 0x3D92E6C6.toFloat()),
    )

    override fun onBoundsChange(bounds: Rect) {
        // 无状态渐变在 draw 里按 bounds 现算，不需要缓存
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        basePaint.color = UiTok.pageBg(dark)
        canvas.drawRect(b, basePaint)

        val w = b.width().toFloat()
        val h = b.height().toFloat()
        for (g in glows) {
            val cx = g[0] * w
            val cy = g[1] * h
            val rx = g[2] * w
            val ry = g[3] * h
            val color = g[4].toInt()
            if (Color.alpha(color) == 0 || rx <= 0f || ry <= 0f) continue
            val r = maxOf(rx, ry)
            paint.shader = RadialGradient(
                0f, 0f, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(rx / r, ry / r)
            canvas.drawCircle(0f, 0f, r, paint)
            canvas.restore()
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
