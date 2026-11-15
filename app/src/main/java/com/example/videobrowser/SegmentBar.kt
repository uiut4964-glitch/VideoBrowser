package com.example.videobrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** شريط تقدم مقسّم: كل مقطع يمثل جزءًا من التحميل (مثل برامج إدارة التحميل) */
class SegmentBar(ctx: Context) : View(ctx) {
    private var fr = floatArrayOf(0f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0E0.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt() }
    private val rect = RectF()

    fun set(f: FloatArray) {
        if (!f.contentEquals(fr)) {
            fr = f
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val n = fr.size
        val d = resources.displayMetrics.density
        val gap = if (n > 1) (if (n > 16) d else 2 * d) else 0f
        val w = (width - gap * (n - 1)) / n
        val h = height.toFloat()
        val r = h / 2
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until n) {
            val l0 = i * (w + gap)
            val l = if (rtl) width - l0 - w else l0
            rect.set(l, 0f, l + w, h)
            c.drawRoundRect(rect, r, r, bg)
            val f = fr[i].coerceIn(0f, 1f)
            if (f > 0f) {
                if (rtl) rect.set(l + w * (1 - f), 0f, l + w, h) else rect.set(l, 0f, l + w * f, h)
                c.drawRoundRect(rect, r, r, fg)
            }
        }
    }
}
