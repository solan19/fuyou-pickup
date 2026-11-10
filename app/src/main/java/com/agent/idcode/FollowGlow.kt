package com.agent.idcode

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/**
 * ★ P2「跟手光」—— 手指按下的地方浮起一团柔光，跟着手指走，松手淡出。
 *
 * 为什么不是 AGSL/RuntimeShader：COUI 的跟手光要 API 33+ 的 Shader 回调，
 * 而本项目 minSdk 31，且同志的机器虽是 API 37、但整体是 View 体系（不是 Compose）。
 * 这里用 [RadialGradient] 在 Canvas 上做**等价的视觉结果**：
 *   · 光源位置 = 触点（局部坐标），半径 150dp，白色/主色 18% → 全透明；
 *   · 只在**按下期间**可见，MOVE 更新中心，UP/CANCEL 按 190ms 淡出后自摘；
 *   · 光被裁到 View 自己的**超椭圆路径**里，不会溢出成方形（这是"像系统"的关键）。
 *
 * 接入点统一放在 [com.agent.idcode.MainActivity.applyPress] 内（所有卡片/行都走它），
 * 不新挂 OnTouchListener，因此不会覆盖既有的按压缩放与点击逻辑。
 * 全 API 可用，低端机只是一层径向渐变，没有 shader 编译开销。
 */
class FollowGlowDrawable(
    private val radiusDp: Float,
    private val dark: Boolean,
    private val dp: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private var cx = 0f
    private var cy = 0f
    private var alphaF = 0f
    private var builtW = -1f
    private var builtH = -1f
    private val glowRadiusPx = 150f * dp

    /** 光色：日间偏冷白，夜间偏主蓝（纯黑上一团白光太硬） */
    private val coreColor: Int = if (dark) 0x4D5C9DFF else 0x38FFFFFF
    private val midColor: Int = if (dark) 0x1A5C9DFF else 0x14FFFFFF

    fun moveTo(x: Float, y: Float) {
        cx = x
        cy = y
        alphaF = 1f
        invalidateSelf()
    }

    fun setFade(a: Float) {
        alphaF = a
        invalidateSelf()
    }

    val faded: Boolean get() = alphaF <= 0.01f

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w == builtW && h == builtH) return
        builtW = w
        builtH = h
        clip.reset()
        if (w > 0f && h > 0f) SquirclePath.build(clip, w, h, radiusDp * dp)
    }

    override fun draw(canvas: Canvas) {
        if (alphaF <= 0.01f || builtW <= 0f) return
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        if (!clip.isEmpty) canvas.clipPath(clip)
        paint.shader = RadialGradient(
            cx, cy, glowRadiusPx,
            intArrayOf(coreColor, midColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.alpha = (255 * alphaF).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, builtW, builtH, paint)
        paint.shader = null
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * ★ P2「渐进遮罩」：滚动区顶部/底部的一层渐隐，模拟 COUI 的「渐进模糊」边界。
 *
 * 严格说 COUI 的渐进模糊是位置可变卷积（API 33+ RuntimeShader），View 体系里没有等价物；
 * 这里给出**观感等价且零开销**的实现：从页面底色不透明渐变到全透明，
 * 让滚动内容在顶栏底下"淡掉"而不是硬切，这正是渐进模糊想解决的视觉问题。
 * 真正的 AGSL 版留待真机验证阶段再替换（见更新记录 1.4.22 已知边界）。
 */
class ProgressiveFadeDrawable(
    private val baseColor: Int,
    private val fromTop: Boolean,
    /** 渐变只在这一段像素内发生，其余区域全透明 —— 避免整屏发灰、也避免顶栏下沿出现白条 */
    private val fadePx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var builtH = -1f

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val h = bounds.height().toFloat()
        if (h == builtH || h <= 0f) return
        builtH = h
        // ★ 只做「雾化」不做「遮挡」：起点 alpha 仅 0x3C（24%），底下内容始终可见。
        //   不能用 `baseColor or 0xB0...` —— cBg 自身 alpha 已是 0xFF，or 完还是全不透明，
        //   会在滚动区顶部形成一条实色白边（同志明确反对的"大白边"）。
        val rgb = baseColor and 0x00FFFFFF
        val opaque = (0x3C000000).toInt() or rgb
        val clear = rgb
        val stop = (fadePx / h).coerceIn(0.001f, 0.999f)
        paint.shader = if (fromTop) {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(opaque, clear, clear),
                floatArrayOf(0f, stop, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(clear, clear, opaque),
                floatArrayOf(0f, 1f - stop, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 跟手光的挂载/收尾：只在 [applyPress] 里调用 */
object FollowGlow {
    private const val KEY = "followGlow"

    /** 按下：把光挂到 overlay（不改 OnTouchListener） */
    fun down(v: View, x: Float, y: Float, radiusDp: Float, dark: Boolean) {
        try {
            var d = v.getTag(R.id.follow_glow) as? FollowGlowDrawable
            if (d == null) {
                d = FollowGlowDrawable(radiusDp, dark, v.resources.displayMetrics.density)
                v.setTag(R.id.follow_glow, d)
                v.overlay.add(d)
                v.addOnLayoutChangeListener { view, l, t, r, b, _, _, _, _ ->
                    (view.getTag(R.id.follow_glow) as? FollowGlowDrawable)
                        ?.setBounds(0, 0, r - l, b - t)
                }
            }
            d.setBounds(0, 0, v.width, v.height)
            d.moveTo(x, y)
        } catch (_: Throwable) { }
    }

    fun move(v: View, x: Float, y: Float) {
        (v.getTag(R.id.follow_glow) as? FollowGlowDrawable)?.moveTo(x, y)
    }

    /** 松手：190ms 淡出后自摘，避免 overlay 里堆 Drawable */
    fun up(v: View) {
        val d = v.getTag(R.id.follow_glow) as? FollowGlowDrawable ?: return
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val t = (System.currentTimeMillis() - start) / 190f
                if (t >= 1f || !v.isAttachedToWindow) {
                    try { v.overlay.remove(d) } catch (_: Throwable) { }
                    if (v.getTag(R.id.follow_glow) === d) v.setTag(R.id.follow_glow, null)
                    return
                }
                d.setFade(1f - t)
                v.postOnAnimation(this)
            }
        }
        v.postOnAnimation(tick)
    }

    /** 供 applyPress 的 MOVE 事件用：把屏幕坐标换成 View 局部坐标 */
    fun local(v: View, e: MotionEvent): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (e.rawX - loc[0]) to (e.rawY - loc[1])
    }

    const val TAG = KEY
}
