package com.agent.idcode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * ★★ v1.4.31：**不顶内容的刷新条**。
 *
 * ## 为什么换掉 SwipeRefreshLayout
 *
 * 取件码页与在途页原来都用 `SwipeRefreshLayout`。它的交互模型是"整块内容跟着手指往下走"：
 * 下拉时子 View 被 `translationY` 推下去、松手再弹回来，刷新期间还压着一个圆形 spinner。
 * 同志 2026-11-11 14:56 的原话是「刷新时会给卡片向下顶，刷新结束再回来，观感不适」——
 * 那个上下位移就是它的固有行为，调参数（`setDistanceToTriggerSync`）只能减轻、消不掉。
 *
 * ## 现在的做法
 *
 * **内容一动不动**，只在页面顶部浮出一条 3dp 的圆角进度条（画在本容器自己的 Canvas 上，
 * 不占布局、不参与测量）：
 *  · 下拉：按拉动比例变长（0 → 满宽），过阈值后变实色；
 *  · 松手触发：变成一段来回扫动的"不确定进度"，位置固定；
 *  · 结束：淡出 180ms。
 *
 * 这样一来，「下拉刷新」这件事从"移动页面"变成了"点亮一条线"，卡片全程不动。
 *
 * ## 三个必须守住的手势判据（少一个就会抢滚动 / 误触）
 *
 * 1. 只有内容**已经到顶**（[canScrollUp] 返回 false）才允许接管下拉；
 * 2. 纵向位移超过 touchSlop，且朝下、且纵向明显大于横向（否则是侧滑返回 / 横滑）；
 * 3. 一旦本轮手势里出现过横向主导，直接放弃，把事件还给内容。
 *
 * 另外：`ACTION_DOWN` 永远先交给子 View（这样点击的按压态、长按都正常），
 * 只有"确认是下拉"之后才在 [onInterceptTouchEvent] 里截断 —— 子 View 会收到 `ACTION_CANCEL`，
 * 不会出现"手指在卡片上滑走了、卡片还亮着按下态"。
 */
class PullRefreshLayout(context: Context) : FrameLayout(context) {

    /** 内容是否还能向上滚（到顶才允许下拉）。默认问第一个子 View。 */
    var canScrollUp: (() -> Boolean)? = null

    /** 达到阈值、松手后调用 */
    var onRefresh: (() -> Unit)? = null

    /** 进度条颜色（一般给主题强调色） */
    var barColor: Int = Color.parseColor("#0080FF")
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    /** 进度条距容器顶部的位置（给状态栏 inset 用，避免压在状态栏文字上） */
    var barTopInset: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 刷新中。由业务侧在"这一轮真的结束"时置 false ——
     * 本类不猜何时结束（抓取可能是 0.3s 也可能是 40s）。
     */
    var isRefreshing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                pull = triggerDistance
                phase = 0f
                startSweep()
            } else {
                stopSweep()
                animatePullTo(0f)
            }
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    /** 触发刷新需要拉多少（像素） */
    private val triggerDistance = 72f * density

    /** 进度条高度 */
    private val barHeight = 3f * density

    /** 当前下拉量（像素） */
    private var pull = 0f

    /** 扫动相位 0..1 */
    private var phase = 0f

    private var downX = 0f
    private var downY = 0f
    private var downAtTop = false
    private var dragging = false
    private var horizontal = false

    private var backAnimator: android.animation.ValueAnimator? = null

    private val sweepRunnable = object : Runnable {
        override fun run() {
            if (!isRefreshing) return
            phase += 0.035f
            if (phase > 1f) phase -= 1f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        // 本容器不自己吃背景，页面底色由外层负责
        setWillNotDraw(false)
    }

    private fun atTop(): Boolean {
        canScrollUp?.let { return !it() }
        val child = getChildAt(0) ?: return true
        return !child.canScrollVertically(-1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downAtTop = atTop()
                dragging = false
                horizontal = false
                // 刷新中又按下：不拦（让用户正常点击/滚动）
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRefreshing) return false
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy)) {
                    horizontal = true      // 本轮是横滑（侧滑返回），彻底放弃
                }
                if (!dragging && !horizontal && downAtTop && dy > slop) {
                    dragging = true
                    return true            // 从这里开始接管，子 View 收到 CANCEL
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downAtTop = atTop()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRefreshing) return true
                val dy = ev.y - downY
                if (!horizontal && abs(ev.x - downX) > abs(dy) && abs(ev.x - downX) > slop) {
                    horizontal = true
                }
                if (horizontal) return false
                // 阻尼：手指走 2px，条只走 1px，超过阈值再减半 —— 手感"有底"
                val raw = (dy - slop).coerceAtLeast(0f)
                pull = if (raw <= triggerDistance) raw * 0.5f
                else triggerDistance * 0.5f + (raw - triggerDistance) * 0.15f
                pull = min(pull, triggerDistance * 1.15f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isRefreshing) return true
                // 松手时的 pull 是"阻尼后"的值，反推是否过阈值
                val triggered = pull >= triggerDistance * 0.5f
                if (triggered && !isRefreshing) {
                    isRefreshing = true
                    onRefresh?.invoke()
                } else {
                    animatePullTo(0f)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun animatePullTo(target: Float) {
        backAnimator?.cancel()
        if (pull == target) return
        backAnimator = android.animation.ValueAnimator.ofFloat(pull, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pull = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startSweep() {
        removeCallbacks(sweepRunnable)
        postOnAnimation(sweepRunnable)
    }

    private fun stopSweep() {
        removeCallbacks(sweepRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSweep()
        backAnimator?.cancel()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (pull <= 0.5f && !isRefreshing) return
        val w = width.toFloat()
        if (w <= 0f) return
        val top = barTopInset.toFloat()

        if (isRefreshing) {
            // 不确定进度：一段 32% 宽的条来回扫，位置固定不动
            val segW = w * 0.32f
            val travel = w - segW
            // 三角波：0→1→0，避免"跳回左边"
            val t = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            val left = travel * t
            rect.set(left, top, left + segW, top + barHeight)
            paint.alpha = 255
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
            return
        }

        // 下拉进度：条从左侧长出来，到阈值后加深
        val progress = (pull / (triggerDistance * 0.5f)).coerceIn(0f, 1f)
        val grow = w * progress
        rect.set(0f, top, grow, top + barHeight)
        paint.alpha = (120 + 135 * progress).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
    }

    /** 供业务侧主动触发（例如状态行的"重试"） */
    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        onRefresh?.invoke()
    }
}
