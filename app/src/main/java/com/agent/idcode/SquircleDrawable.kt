package com.agent.idcode

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ★ ColorOS 17「凝光」超椭圆圆角 —— 圆角不是四分之一圆弧，而是曲率连续的超椭圆（Lp 范数 n=2.5）。
 *
 * 为什么不用 `GradientDrawable`：
 *  · `cornerRadius` 只能画正圆弧，四个角是一段"死弯"，和系统自带的卡片并排放一眼就不像一家人；
 *  · 凝光的卡片、胶囊、品牌方块全部是超椭圆，手机自己的设置页就是这个曲率。
 *
 * 几何照 COUI（ColorOS 17 的 Compose 复刻库）`CouiShape.kt` 的算法：
 * `|x|^n + |y|^n = 1` 半角采样，n = 2.5，线段相对原曲线的误差上限 0.05px。
 * 采样点数跟着半径走 —— 小方块算得少、大卡片算得多，不在 draw 里做无谓的三角函数。
 *
 * 一次画三样（这也是 GradientDrawable 组合不出来的）：
 *  1. **填充**：可上下渐变。主按钮的凝光蓝就是 `#2E9BFF → #0080FF`；
 *  2. **描边**：极细半透明，深色下用来勾出边界（白天几乎看不见）；
 *  3. **轮廓光**：顶部一条 1dp 亮线，往下 55% 高度渐隐 —— 光从上面来，这是"凝光"的签名。
 *
 * 性能：Path 只在**尺寸变化时**重建（卡片尺寸基本不变），每帧只是 `drawPath` 三次；
 * 低端机上比 `backdrop-filter` 那套真实模糊便宜太多，所以这里不做运行时降级。
 */
class SquircleDrawable(
    private val radiusPx: Float,
    private val fillTop: Int,
    private val fillBottom: Int = fillTop,
    private val strokeColor: Int = Color.TRANSPARENT,
    private val strokePx: Float = 0f,
    private val contour: Boolean = false,
) : Drawable() {

    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var builtW = -1f
    private var builtH = -1f

    private val gradient: Boolean get() = fillBottom != fillTop

    init {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = fillTop
        if (strokePx > 0f) {
            strokePaint.strokeWidth = strokePx
            strokePaint.color = strokeColor
        }
        contourPaint.strokeWidth = maxOf(1f, strokePx)
        contourPaint.color = 0x8CFFFFFF.toInt()
    }

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.width().toFloat() == builtW && bounds.height().toFloat() == builtH) return
        builtW = bounds.width().toFloat()
        builtH = bounds.height().toFloat()
        buildPath(builtW, builtH)
        // 渐变必须按真实高度重建（否则 1px 高的占位也按比例铺色，颜色会跳）
        if (gradient) {
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, builtH, fillTop, fillBottom, Shader.TileMode.CLAMP
            )
        }
        if (contour) {
            // 顶部亮线：从 0 处的白 55% 渐隐到 55% 高度处完全透明
            contourPaint.shader = LinearGradient(
                0f, 0f, 0f, builtH * 0.55f,
                intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /** 超椭圆路径：四角各采样 segments*2+1 个点，拼成闭合 Path */
    private fun buildPath(w: Float, h: Float) {
        path.reset()
        if (w <= 0f || h <= 0f) return
        val r = radiusPx.coerceIn(0f, minOf(w, h) / 2f)
        if (r <= 1f) {
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            return
        }
        val weight = 2.5
        val diagonal = 0.5.pow(1.0 / weight)
        val segments = ceil(sqrt(r * (weight - 1.0) * diagonal / (4.0 * 0.05)))
            .toInt().coerceAtLeast(1)
        val half = DoubleArray(segments + 1) { i ->
            (1.0 - (diagonal * i / segments).pow(weight)).pow(1.0 / weight)
        }
        path.moveTo(r, 0f)
        repeat(4) { corner ->
            for (index in 0..segments * 2) {
                val firstHalf = index <= segments
                val hi = if (firstHalf) index else segments * 2 - index
                val a = diagonal * hi / segments
                val b = half[hi]
                val x = (r * if (firstHalf) a else b).toFloat()
                val y = (r * if (firstHalf) b else a).toFloat()
                when (corner) {
                    0 -> path.lineTo(w - r + x, r - y)          // 右上角
                    1 -> path.lineTo(w - r + y, h - r + x)      // 右下角
                    2 -> path.lineTo(r - x, h - r + y)          // 左下角
                    else -> path.lineTo(r - y, r - x)           // 左上角
                }
            }
        }
        path.close()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawPath(path, fillPaint)
        if (strokePx > 0f) canvas.drawPath(path, strokePaint)
        if (contour) canvas.drawPath(path, contourPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        contourPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        contourPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
