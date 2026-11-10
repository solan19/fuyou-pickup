package com.agent.idcode

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference

/**
 * ★ v1.4.20「凝光」管线 —— **背景采样 → 模糊 → 材质混色 → 轮廓光** 四段端到端。
 *
 * 规格口径对齐 COUI-Kit（ColorOS 17 复刻库）文档，实现为自绘，**不引用其 MPL 源码**：
 *  · 材质（blur-material.md）：「先执行 luminosity，再以 blend alpha 混合第二层」——
 *    两色输入 [BackdropBlurDrawable.matFirst] / [BackdropBlurDrawable.matSecond]，
 *    第二层混合模式默认 Overlay（可选 ColorDodge）；
 *  · 轮廓光（CouiContourPresets）：近边线（顶，亮 [UiTok.CONTOUR_NEAR]）+
 *    远边线（底，暗 [UiTok.CONTOUR_FAR]）双线结构；
 *  · 模糊：RenderEffect 高斯。**不能走 Paint.setRenderEffect —— 它不存在**（SDK 实测），
 *    只能 RenderNode.setRenderEffect 后 drawRenderNode；采样图 1/[UiTok.SAMPLE_SCALE] 降采样，
 *    GPU 成本可控，一加 13（8 Elite）上滚动跟手无压力。
 *
 * 用法（一条线走通）：
 * ```
 * NingGuang.glass(target = dockGlass, source = { pickupScroll }, dark = isDark())
 * ```
 * source 返回 null ⇒ 无内容可采（纯色面），管线照走，只是模糊段落在均匀底色上不可见。
 */
object NingGuang {
    private const val TAG = "NingGuang"

    /**
     * 给 [target] 挂一块凝光玻璃面。
     *
     * @param source 惰性取"玻璃背后的内容"。**必须惰性**：首页/取件码页会整体重建，
     *   直接持旧 View 引用会采到已 detach 的尸体（采样不报错但永远是旧画面）。
     */
    fun glass(
        target: View,
        source: () -> View?,
        dark: Boolean,
        cornerDp: Int = UiTok.R28,
        radiusDp: Int = UiTok.BLUR_GLASS,
        strokeDp: Float = 0.6f,
    ): BackdropBlurDrawable {
        val density = target.resources.displayMetrics.density
        val d = BackdropBlurDrawable(
            radiusPx = radiusDp * density,
            cornerPx = cornerDp * density,
            sampleScale = UiTok.SAMPLE_SCALE,
            matFirst = UiTok.matFirst(dark),
            matSecond = UiTok.matSecond(dark),
            matBlend = BlendMode.OVERLAY,
            matBlendAlpha = UiTok.MAT_BLEND_ALPHA,
            fallbackColor = UiTok.cardBg(dark),
            strokeColor = UiTok.cardStroke(dark),
            strokePx = strokeDp * density,
            contour = true,
        )
        d.targetRef = WeakReference(target)
        d.sourceProvider = source
        target.background = d

        val refresh = Runnable { capture(d, target) }
        // ViewTreeObserver 是窗口级的：任何一处置顶滚动都会回调，dock/设置键共用同一招
        target.viewTreeObserver.addOnScrollChangedListener { target.post(refresh) }
        target.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> target.post(refresh) }
        target.post(refresh)
        return d
    }

    /** 采样：把 source 的内容降采样画进小图（玻璃自身临时 INVISIBLE，避免把自己糊进自己） */
    private fun capture(d: BackdropBlurDrawable, target: View) {
        try {
            if (target.getWindowToken() == null) return
            val now = System.currentTimeMillis()
            if (now - d.lastCaptureMs < 16) return    // 一帧一次，别叠
            d.lastCaptureMs = now
            val src = d.sourceProvider?.invoke() ?: run {
                d.logOnce("采样=均匀底面（无源）")
                return
            }
            if (src.width <= 0 || src.height <= 0) return
            val scale = d.sampleScale
            val w = src.width / scale
            val h = src.height / scale
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(1f / scale, 1f / scale)
            val oldVis = target.visibility
            target.visibility = View.INVISIBLE
            src.draw(c)
            target.visibility = oldVis
            val loc = IntArray(2)
            src.getLocationInWindow(loc)
            d.updateSample(bmp, loc[0].toFloat(), loc[1].toFloat())
            d.logOnce("采样=${w}x${h}@1/$scale（源 ${src.width}x${src.height}）")
        } catch (t: Throwable) {
            Log.w(TAG, "采样失败，降级纯色: ${t.message}")
        }
    }
}

/**
 * 凝光玻璃面 Drawable：① 采样图（[updateSample] 喂）→ ② RenderNode 高斯模糊 →
 * ③ 材质双层（luminosity 先行，第二层 blendAlpha 混合）→ ④ 轮廓光近/远双线。
 * 无采样时退回 [fallbackColor] 纯色，但 ③④ 照走 —— 管线永远完整。
 */
class BackdropBlurDrawable(
    private val radiusPx: Float,
    private val cornerPx: Float,
    val sampleScale: Int,
    val matFirst: Int,
    val matSecond: Int,
    private val matBlend: BlendMode,
    val matBlendAlpha: Float,
    private val fallbackColor: Int,
    private val strokeColor: Int,
    private val strokePx: Float,
    private val contour: Boolean,
) : Drawable() {

    internal var targetRef: WeakReference<View>? = null
    internal var sourceProvider: (() -> View?)? = null
    internal var lastCaptureMs = 0L

    private val path = Path()
    private var builtW = -1f
    private var builtH = -1f

    private var sample: Bitmap? = null
    private var sampleOriginX = 0f
    private var sampleOriginY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fallbackPaint = Paint()
    private val matLumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // ③-1 先 luminosity：把背景明度压到材质本色的明度上，玻璃才"雾"得住
        color = matFirst
        blendMode = BlendMode.LUMINOSITY
    }
    private val matBlendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // ③-2 再以 blend alpha 混第二层（CouiMaterial 口径）
        color = matSecond
        blendMode = matBlend
        alpha = (Color.alpha(matSecond) * matBlendAlpha).toInt().coerceIn(0, 255)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var blurNode: RenderNode? = null
    private var logged = false

    init {
        if (strokePx > 0f) {
            strokePaint.strokeWidth = strokePx
            strokePaint.color = strokeColor
        }
        if (contour) contourPaint.strokeWidth = maxOf(1f, strokePx)
    }

    fun updateSample(bmp: Bitmap, originX: Float, originY: Float) {
        val old = sample
        sample = bmp
        sampleOriginX = originX
        sampleOriginY = originY
        if (old != null && old !== bmp && !old.isRecycled) old.recycle()
        invalidateSelf()
    }

    internal fun logOnce(stage: String) {
        if (logged) return
        logged = true
        Log.i(
            "NingGuang",
            "管线[完整] $stage → 模糊 r=${radiusPx}px(RenderNode) → " +
                "材质 LUMINOSITY(${String.format("#%08X", matFirst)}) + " +
                "$matBlend(${String.format("#%08X", matSecond)})α$matBlendAlpha → " +
                "轮廓光 近${UiTok.CONTOUR_NEAR}/远${UiTok.CONTOUR_FAR}"
        )
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w == builtW && h == builtH) return
        builtW = w
        builtH = h
        path.reset()
        if (w > 0f && h > 0f) SquirclePath.build(path, w, h, cornerPx)
        if (contour && h > 0f) {
            // 近边线（顶）亮 → 中段隐 → 远边线（底）暗 —— CouiContourPresets 双线口径
            contourPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    (UiTok.CONTOUR_NEAR shl 24 or 0xFFFFFF),
                    0x00FFFFFF,
                    (UiTok.CONTOUR_FAR shl 24 or 0xFFFFFF),
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.clipPath(path)

        // ① 采样 → ② 模糊
        val bmp = sample
        var painted = false
        if (bmp != null && !bmp.isRecycled && bmp.width > 0) {
            val tLoc = IntArray(2)
            targetRef?.get()?.getLocationInWindow(tLoc)
            val s = sampleScale.toFloat()
            val srcR = Rect(
                ((tLoc[0] - sampleOriginX) / s).toInt(),
                ((tLoc[1] - sampleOriginY) / s).toInt(),
                ((tLoc[0] - sampleOriginX) / s + w / s).toInt(),
                ((tLoc[1] - sampleOriginY) / s + h / s).toInt(),
            )
            val dstR = RectF(0f, 0f, w, h)
            val node = obtainNode(w.toInt(), h.toInt())
            if (node != null) {
                try {
                    val rc = node.beginRecording()
                    rc.drawBitmap(bmp, srcR, dstR, bitmapPaint)
                    node.endRecording()
                    canvas.drawRenderNode(node)
                    painted = true
                } catch (t: Throwable) {
                    // 软件画布等极端情况：退回直接画（降采样图本身已软）
                }
            }
            if (!painted) {
                canvas.drawBitmap(bmp, srcR, dstR, bitmapPaint)
                painted = true
            }
        }
        if (!painted) {
            fallbackPaint.color = fallbackColor
            canvas.drawRect(0f, 0f, w, h, fallbackPaint)
        }

        // ③ 材质混色：先 luminosity，再 blendAlpha 混第二层
        canvas.drawPath(path, matLumPaint)
        canvas.drawPath(path, matBlendPaint)

        if (strokePx > 0f) canvas.drawPath(path, strokePaint)
        if (contour) canvas.drawPath(path, contourPaint)
        canvas.restore()
        logOnce(if (painted) "首绘" else "首绘(纯色兜底)")
    }

    private fun obtainNode(w: Int, h: Int): RenderNode? = try {
        var n = blurNode
        if (n == null) {
            n = RenderNode("ngBlur")
            n.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
            blurNode = n
        }
        n.setPosition(0, 0, w, h)
        n
    } catch (t: Throwable) {
        null
    }

    override fun setAlpha(alpha: Int) {
        matLumPaint.alpha = alpha
        matBlendPaint.alpha = alpha
        strokePaint.alpha = alpha
        contourPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
        fallbackPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
