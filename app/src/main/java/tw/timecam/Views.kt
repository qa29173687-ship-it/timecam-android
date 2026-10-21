package tw.timecam

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class WatermarkView(ctx: Context, a: AttributeSet?) : View(ctx, a) {
    @Volatile var data: WmData = WmData.load(ctx)
    var bottomInset = 0f
    var topInset = 0f
    var imageRect: RectF? = null
    fun reload() { data = WmData.load(context); invalidate() }
    private val tick = object : Runnable { override fun run() { reload(); postDelayed(this, 500) } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val dp = resources.displayMetrics.density
        val r = imageRect ?: return
        val m = minOf(r.width(), r.height())
        Watermark.render(c, data, Names.now(), m * 0.03f, r, m * 0.03f)  // 與存檔相同比例
    }
}

class ShutterView(ctx: Context, a: AttributeSet?) : View(ctx, a) {
    var video = false; set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; anim = 0f; post(step) }
    private var anim = 1f
    private val step = object : Runnable { override fun run() { anim = minOf(1f, anim + 0.12f); invalidate(); if (anim < 1f) postOnAnimation(this) } }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF1A1A1A.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true }
    override fun onDraw(c: Canvas) {
        val dp = resources.displayMetrics.density; val cx = width / 2f; val cy = height / 2f; val R = minOf(cx, cy)
        ring.strokeWidth = 4 * dp; c.drawCircle(cx, cy, R - 2 * dp, ring)
        fill.color = if (video) 0xFFE5342B.toInt() else 0xFF1A1A1A.toInt()
        val press = if (isPressed) 0.9f else 1f
        val full = (R - 8 * dp) * press
        val k = if (recording) anim else 1 - anim
        val half = full + (R * 0.38f - full) * (if (recording) anim else 0f).coerceIn(0f, 1f)
        val rad = full + (8 * dp - full) * (if (recording) anim else 0f)
        if (!recording && k >= 0f) c.drawCircle(cx, cy, full, fill)
        else c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, rad, rad, fill)
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}
