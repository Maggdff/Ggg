package com.smartwater.monitor.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * الملف الزاوي للإضاءة حول المحور: لكل زاوية (0°=أعلى، باتجاه عقارب الساعة)
 * يُحسب متوسط السطوع على حلقة نصف قطرية [inner, outer]×R ثم يُنعَّم بنافذة زاوية.
 * العقرب أغمق من وجه العدّاد، فيظهر كقاع عميق في الملف.
 */
class HandProfile(private val smoothDeg: Int = 15, private val radialSteps: Int = 8) {
    private val sinT = DoubleArray(N) { sin(Math.toRadians(it.toDouble())) }
    private val cosT = DoubleArray(N) { cos(Math.toRadians(it.toDouble())) }
    private val raw = DoubleArray(N)

    /** يكتب التباين النسبي ((سطوع منعَّم − متوسط)/متوسط) في out. false إذا تعذّر القياس. */
    fun compute(f: GrayFrame, cx: Double, cy: Double, radius: Double, inner: Double, outer: Double, out: DoubleArray): Boolean {
        for (a in 0 until N) {
            var s = 0.0
            var n = 0
            for (k in 0 until radialSteps) {
                val r = radius * (inner + (outer - inner) * k / (radialSteps - 1))
                val v = f.sample(cx + r * sinT[a], cy - r * cosT[a])
                if (!v.isNaN()) { s += v; n++ }
            }
            if (n < radialSteps * 0.6) return false
            raw[a] = s / n
        }
        var mean = 0.0
        for (v in raw) mean += v
        mean /= N
        if (mean < 8.0) return false // مظلم جدًا لقياس موثوق
        val half = smoothDeg / 2
        val w = 2 * half + 1
        for (a in 0 until N) {
            var s = 0.0
            for (d in -half..half) s += raw[(a + d + N) % N]
            out[a] = (s / w - mean) / mean
        }
        return true
    }

    companion object { const val N = 360 }
}
