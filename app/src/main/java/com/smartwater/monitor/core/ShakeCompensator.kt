package com.smartwater.monitor.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * تعويض اهتزاز الصورة: مطابقة NCC لحلقة الدرجات الخارجية الثابتة لوجه العدّاد
 * (r من 0.72R إلى 1.02R، بعيدة عن العقرب) مع إطار مرجعي، ثم استخدام الإزاحة
 * (بدقة تحت البكسل) لتحريك مركز العدّاد.
 */
class ShakeCompensator(
    private val search: Int = 6,
    private val innerR: Double = 0.72,
    private val outerR: Double = 1.02,
    private val stride: Int = 2,
    private val minNcc: Double = 0.45,
) {
    private var dx = IntArray(0)
    private var dy = IntArray(0)
    private var ref = DoubleArray(0)
    private var refSum = 0.0
    private var refSq = 0.0
    private var baseX = 0
    private var baseY = 0
    var hasRef = false
        private set

    fun setReference(f: GrayFrame, cx: Double, cy: Double, radius: Double) {
        baseX = cx.roundToInt(); baseY = cy.roundToInt()
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>(); val vs = ArrayList<Double>()
        val ro = outerR * radius; val ri = innerR * radius
        val lim = ro.toInt() + 1
        var y = -lim
        while (y <= lim) {
            var x = -lim
            while (x <= lim) {
                val r = sqrt((x * x + y * y).toDouble())
                if (r in ri..ro) {
                    val p = f.pixel(baseX + x, baseY + y)
                    if (p >= 0) { xs.add(x); ys.add(y); vs.add(p.toDouble()) }
                }
                x += stride
            }
            y += stride
        }
        dx = xs.toIntArray(); dy = ys.toIntArray(); ref = vs.toDoubleArray()
        refSum = ref.sum(); refSq = ref.sumOf { it * it }
        hasRef = ref.size > 100
    }

    class Shift(val x: Double, val y: Double, val ncc: Double)

    /** يبحث حول (lastX,lastY) بمدى ±search. null إذا كانت المطابقة ضعيفة. */
    fun estimate(f: GrayFrame, lastX: Double, lastY: Double): Shift? {
        if (!hasRef) return null
        val n = ref.size
        val cx0 = lastX.roundToInt(); val cy0 = lastY.roundToInt()
        val w = 2 * search + 1
        val surf = DoubleArray(w * w) { -2.0 }
        val denR = refSq - refSum * refSum / n
        if (denR <= 1e-6) return null
        var best = -2.0; var bi = -1
        for (j in 0 until w) for (i in 0 until w) {
            val sx = cx0 + i - search; val sy = cy0 + j - search
            var sv = 0.0; var svv = 0.0; var svr = 0.0; var ok = true
            for (k in 0 until n) {
                val p = f.pixel(baseX + dx[k] + sx, baseY + dy[k] + sy)
                if (p < 0) { ok = false; break }
                val v = p.toDouble()
                sv += v; svv += v * v; svr += v * ref[k]
            }
            if (!ok) continue
            val denV = svv - sv * sv / n
            if (denV <= 1e-6) continue
            val ncc = (svr - sv * refSum / n) / sqrt(denV * denR)
            surf[j * w + i] = ncc
            if (ncc > best) { best = ncc; bi = j * w + i }
        }
        if (bi < 0 || best < minNcc) return null
        val bj = bi / w; val bx = bi % w
        var rx = (cx0 + bx - search).toDouble()
        var ry = (cy0 + bj - search).toDouble()
        // تدقيق تحت البكسل: شبكة 0.25 بكسل حول أفضل نقطة صحيحة (عيّنة ثنائية الخطية)
        var fb = best
        val ix = rx; val iy = ry
        var ox = -0.5
        while (ox <= 0.5001) {
            var oy = -0.5
            while (oy <= 0.5001) {
                if (ox != 0.0 || oy != 0.0) {
                    val v = nccReal(f, ix + ox, iy + oy, denR)
                    if (v > fb) { fb = v; rx = ix + ox; ry = iy + oy }
                }
                oy += 0.25
            }
            ox += 0.25
        }
        if (abs(rx) > 60 || abs(ry) > 60) return null
        return Shift(rx, ry, fb)
    }

    private fun nccReal(f: GrayFrame, sx: Double, sy: Double, denR: Double): Double {
        val n = ref.size
        var sv = 0.0; var svv = 0.0; var svr = 0.0
        for (k in 0 until n) {
            val v = f.sample(baseX + dx[k] + sx, baseY + dy[k] + sy)
            if (v.isNaN()) return -2.0
            sv += v; svv += v * v; svr += v * ref[k]
        }
        val denV = svv - sv * sv / n
        if (denV <= 1e-6) return -2.0
        return (svr - sv * refSum / n) / sqrt(denV * denR)
    }
}
