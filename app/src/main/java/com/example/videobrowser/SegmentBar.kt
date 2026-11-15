package com.example.videobrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * شريط تقدم بأسلوب برامج إدارة التحميل: الشريط يمثل الملف كاملًا،
 * وكل اتصال يملأ جزءه ابتداءً من موضع بدايته.
 * البيانات: أزواج (بداية، نهاية) بين 0 و 1.
 */
class SegmentBar(ctx: Context) : View(ctx) {
    private var rg = FloatArray(0)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCE3EC.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E6FE0.toInt() }
    private val rect = RectF()
    private val clip = Path()

    fun set(r: FloatArray) {
        if (!r.contentEquals(rg)) {
            rg = r
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, rad, rad, bg)
        if (rg.size < 2) return
        val minW = 2f * resources.displayMetrics.density
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        clip.reset()
        clip.addRoundRect(rect, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        var i = 0
        while (i + 1 < rg.size) {
            val a = rg[i].coerceIn(0f, 1f)
            val b = rg[i + 1].coerceIn(0f, 1f)
            if (b > a) {
                var x0 = a * w
                var x1 = maxOf(b * w, x0 + minW)
                if (rtl) { val t0 = w - x1; val t1 = w - x0; x0 = t0; x1 = t1 }
                rect.set(x0, 0f, x1, h)
                c.drawRect(rect, fg)
            }
            i += 2
        }
        c.restore()
    }
}
