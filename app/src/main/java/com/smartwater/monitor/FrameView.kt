package com.smartwater.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.smartwater.monitor.core.MeterConfig
import kotlin.math.cos
import kotlin.math.sin

/** يعرض لقطة (أو جزءًا مكبّرًا منها) مع دائرة كل عدّاد ومحوره وخط العقرب المقدَّر. */
class FrameView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
    /** المنطقة التي يغطيها البتماب بإحداثيات الإطار الكامل: x0,y0,x1,y1 */
    var region = floatArrayOf(0f, 0f, 4f, 3f)
    var meters: List<MeterConfig> = emptyList()
    var rawAngles: DoubleArray? = null
    var shifts: List<Pair<Double, Double>>? = null
    var selected = 0
    var onTap: ((Double, Double) -> Unit)? = null

    private val dst = RectF()
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(160, 255, 200, 0) }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.GREEN }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 28f }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val rw = (region[2] - region[0]).coerceAtLeast(1f)
        val rh = (region[3] - region[1]).coerceAtLeast(1f)
        setMeasuredDimension(w, if (bitmap == null) 160 else (w * rh / rw).toInt().coerceIn(200, 1600))
    }

    private fun scale() = width / (region[2] - region[0]).coerceAtLeast(1f)

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = bitmap ?: run { c.drawText("لا توجد لقطة — اضغط «اتصال RTSP والتقاط»", 20f, 40f, txt); return }
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(b, null, dst, null)
        val s = scale()
        meters.forEachIndexed { i, m ->
            val sh = shifts?.getOrNull(i)
            val cx = ((m.cx + (sh?.first ?: 0.0)) - region[0]) * s
            val cy = ((m.cy + (sh?.second ?: 0.0)) - region[1]) * s
            val r = (m.radius * s).toFloat()
            circle.color = if (i == selected) Color.CYAN else Color.rgb(0, 200, 0)
            circle.strokeWidth = if (i == selected) 3f else 2f
            c.drawCircle(cx.toFloat(), cy.toFloat(), r, circle)
            c.drawCircle(cx.toFloat(), cy.toFloat(), r * 0.33f, band)
            c.drawCircle(cx.toFloat(), cy.toFloat(), r * 0.52f, band)
            c.drawLine(cx.toFloat() - 8, cy.toFloat(), cx.toFloat() + 8, cy.toFloat(), circle)
            c.drawLine(cx.toFloat(), cy.toFloat() - 8, cx.toFloat(), cy.toFloat() + 8, circle)
            c.drawText("${i + 1}", cx.toFloat() - r, cy.toFloat() - r - 6f, txt)
            rawAngles?.getOrNull(i)?.let { a ->
                val rad = Math.toRadians(a)
                c.drawLine(cx.toFloat(), cy.toFloat(),
                    (cx + 0.62 * r * sin(rad)).toFloat(), (cy - 0.62 * r * cos(rad)).toFloat(), hand)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onTap == null) return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)   // لا تدع ScrollView يبتلع اللمس/السحب
            val s = scale()
            onTap?.invoke(region[0] + e.x / s.toDouble(), region[1] + e.y / s.toDouble())
            return true
        }
        return true
    }
}
