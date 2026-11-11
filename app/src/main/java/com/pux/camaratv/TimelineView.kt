package com.pux.camaratv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * A DVR-style 24-hour timeline: recorded clips are drawn as blocks, with a movable playhead the
 * remote scrubs. Seconds-of-day (0..86400) are the unit throughout.
 */
class TimelineView(context: Context) : View(context) {

    /** A recorded span within the day, in seconds-of-day. */
    class Span(val startSec: Int, val endSec: Int, val motion: Boolean)

    private var spans: List<Span> = emptyList()
    private var cursorSec = 0
    private var playing = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt() }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5252.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun setSpans(spans: List<Span>) {
        this.spans = spans
        invalidate()
    }

    fun setCursor(sec: Int) {
        cursorSec = sec.coerceIn(0, DAY)
        invalidate()
    }

    fun getCursor() = cursorSec

    fun setPlaying(value: Boolean) {
        playing = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val padX = dp(24f)
        val w = width - 2 * padX
        if (w <= 0) return
        val barTop = dp(34f)
        val barH = dp(22f)

        fun x(sec: Int) = padX + w * sec / DAY

        // The 24 h track
        rect.set(padX, barTop, padX + w, barTop + barH)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), barPaint)

        // Recorded clips
        for (s in spans) {
            val x1 = x(s.startSec)
            val x2 = x(s.endSec).coerceAtLeast(x1 + dp(1f)) // keep very short clips visible
            rect.set(x1, barTop, x2, barTop + barH)
            canvas.drawRect(rect, if (s.motion) motionPaint else recPaint)
        }

        // Hour ticks + labels every 3 h
        for (h in 0..24 step 3) {
            val hx = x(h * 3600)
            canvas.drawRect(hx - dp(0.5f), barTop + barH + dp(2f), hx + dp(0.5f), barTop + barH + dp(7f), tickPaint)
            val lbl = if (h < 10) "0$h" else "$h"
            canvas.drawText(lbl, hx - labelPaint.measureText(lbl) / 2, barTop + barH + dp(22f), labelPaint)
        }

        // Playhead + time label
        val hx = x(cursorSec)
        canvas.drawRect(hx - dp(1.5f), barTop - dp(6f), hx + dp(1.5f), barTop + barH + dp(6f), headPaint)
        val t = timeLabel(cursorSec)
        var tx = hx - timePaint.measureText(t) / 2
        tx = tx.coerceIn(padX, padX + w - timePaint.measureText(t))
        canvas.drawText(t, tx, barTop - dp(12f), timePaint)
    }

    private fun timeLabel(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    companion object {
        const val DAY = 86400
    }
}
