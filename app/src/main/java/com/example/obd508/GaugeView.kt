package com.example.obd508

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Круговий датчик з плавною анімацією стрілки. */
class GaugeView(
    context: Context,
    private val label: String,
    private val unit: String,
    private val minV: Float,
    private val maxV: Float,
    private val warnFrom: Float,
    private val decimals: Int = 0,
) : View(context) {

    private var target: Float? = null
    private var shown = minV

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF222B35.toInt()
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A97A6.toInt(); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setValue(v: Float?) {
        target = v
        postInvalidateOnAnimation()
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.09f
        bgPaint.strokeWidth = stroke
        fgPaint.strokeWidth = stroke
        val pad = stroke
        rect.set(pad, pad, size - pad, size - pad)

        val t = target
        if (t != null) shown += (t - shown) * 0.2f

        c.drawArc(rect, 135f, 270f, false, bgPaint)
        val frac = ((shown - minV) / (maxV - minV)).coerceIn(0f, 1f)
        fgPaint.color = when {
            t != null && t >= warnFrom -> 0xFFFF5252.toInt()
            frac > 0.75f -> 0xFFFFB300.toInt()
            else -> 0xFF00BCD4.toInt()
        }
        if (t != null && frac > 0f) c.drawArc(rect, 135f, 270f * frac, false, fgPaint)

        val text = if (t == null) "—" else "%.${decimals}f".format(shown)
        valuePaint.textSize = size * 0.22f
        c.drawText(text, size / 2, size / 2 + size * 0.05f, valuePaint)
        smallPaint.textSize = size * 0.075f
        c.drawText(unit, size / 2, size / 2 + size * 0.16f, smallPaint)
        smallPaint.textSize = size * 0.08f
        c.drawText(label, size / 2, size * 0.88f, smallPaint)

        if (t != null && abs(t - shown) > (maxV - minV) * 0.002f) postInvalidateOnAnimation()
    }
}
