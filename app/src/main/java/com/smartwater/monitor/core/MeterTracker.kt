package com.smartwater.monitor.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** معايرة عدّاد واحد (إحداثيات الإطار الكامل بالبكسل). */
data class MeterConfig(
    val name: String,
    val cx: Double,      // محور دوران العقرب الكبير السفلي
    val cy: Double,
    val radius: Double,  // نصف قطر قرص العدّاد الصغير الذي يدور فيه العقرب
)

data class TrackerParams(
    val bandInner: Double = 0.33,   // حلقة البحث عن العقرب (كسر من R) — خارج كتلة المحور
    val bandOuter: Double = 0.52,   // وقبل علامات الأرقام عند ~0.65R
    val smoothDeg: Int = 15,
    val minContrast: Double = 0.025, // أقل تباين مقبول للعقرب أثناء التتبع
    val lockContrast: Double = 0.05, // أقل تباين للالتقاط الأول/البحث الواسع بعد فقدان
    val maxDegPerSec: Double = 900.0,   // يُستخدم فقط للبحث الواسع بعد انقطاع طويل
    val baseFwdDeg: Double = 12.0,      // هامش أمامي ثابت لنافذة التنبؤ
    val accelFactor: Double = 3.0,      // هامش التسارع: نضرب السرعة المقدّرة × dt × هذا العامل
    val wideAfterSec: Double = 1.0,     // بعد هذا الفقدان تُفتح نافذة بحث واسعة (بتباين أشد)
    val backDeg: Double = 8.0,       // العقرب لا يرجع؛ نسمح فقط بتذبذب صغير للخلف
    val deadbandDeg: Double = 1.5,   // لا تتقدّم الزاوية المثبّتة إلا بعد تجاوز هذا الحد
    val shakeSearch: Int = 6,
)

class MeterSample(
    val t: Double,
    val valid: Boolean,        // false: لا قياس موثوق في هذا الإطار (ليس توقفًا!)
    val angleDeg: Double,      // الحركة المتراكمة المثبّتة بالدرجات (تتزايد فقط)
    val rawAngleDeg: Double,   // زاوية العقرب 0..360 (0=أعلى)
    val contrast: Double,
    val shiftX: Double,
    val shiftY: Double,
    val uncertainJump: Boolean, // قفزة بعد فقدان طويل (بحث واسع) — الدقة أقل
)

/**
 * يتتبّع العقرب الكبير لعدّاد واحد ويحسب الحركة المتراكمة.
 *  - تعويض الاهتزاز (ShakeCompensator)
 *  - ملف زاوي ثم اختيار القاع ضمن نافذة تنبؤ (للأمام فقط) لتفادي علامات الأرقام الثابتة
 *  - فكّ اللف (unwrap) فيعالج عبور الصفر بدقة
 *  - منطقة ميتة صغيرة تمنع تراكم ضجيج القياس
 */
class MeterTracker(val cfg: MeterConfig, val p: TrackerParams = TrackerParams(), reference: GrayFrame? = null) {
    private val profile = HandProfile(p.smoothDeg)
    private val shake = ShakeCompensator(search = p.shakeSearch)
    private val prof = DoubleArray(HandProfile.N)

    private var sx = 0.0
    private var sy = 0.0
    private var locked = false
    private var lastMod = 0.0
    private var lastValidT = 0.0
    private var rawCum = 0.0
    private var committed = 0.0

    private var vEst = 0.0   // سرعة مقدّرة (°/ث)

    init {
        // المرجع من لحظة المعايرة يضمن أن المحور المعاير يطابق إزاحة الاهتزاز المقدّرة
        if (reference != null) shake.setReference(reference, cfg.cx, cfg.cy, cfg.radius)
    }

    val totalDegrees: Double get() = committed

    fun reset() {
        locked = false; rawCum = 0.0; committed = 0.0; sx = 0.0; sy = 0.0; vEst = 0.0
    }

    fun update(f: GrayFrame, t: Double): MeterSample {
        if (!shake.hasRef) shake.setReference(f, cfg.cx, cfg.cy, cfg.radius)
        else shake.estimate(f, sx, sy)?.let { sx = it.x; sy = it.y }

        val ok = profile.compute(f, cfg.cx + sx, cfg.cy + sy, cfg.radius, p.bandInner, p.bandOuter, prof)
        if (!ok) return invalid(t)

        if (!locked) {
            var bi = 0
            for (a in 1 until HandProfile.N) if (prof[a] < prof[bi]) bi = a
            val c = -prof[bi]
            if (c < p.lockContrast) return invalid(t)
            locked = true
            lastMod = refine(bi)
            lastValidT = t
            return valid(t, c, false)
        }

        val lost = t - lastValidT
        val wide = lost > p.wideAfterSec
        val fwd = if (wide) min(359.0, p.maxDegPerSec * lost + 10.0)
                  else min(120.0, p.baseFwdDeg + p.accelFactor * vEst * lost)
        val center = lastMod.roundToInt()
        val lo = -p.backDeg.roundToInt()
        val hi = ceil(fwd).toInt()
        var best = -1
        var bv = Double.MAX_VALUE
        for (d in lo..hi) {
            val a = ((center + d) % 360 + 360) % 360
            if (prof[a] < bv) { bv = prof[a]; best = a }
        }
        val contrast = -bv
        if (contrast < (if (wide) p.lockContrast else p.minContrast)) return invalid(t)

        val ang = refine(best)
        var d = ang - lastMod
        d = (((d + p.backDeg) % 360.0) + 360.0) % 360.0 - p.backDeg
        if (!wide && lost > 1e-3) {
            val step = max(0.0, d) / lost
            vEst = max(0.75 * vEst + 0.25 * step, step)   // صعود سريع، هبوط تدريجي
        } else if (wide) vEst = 0.0
        rawCum += d
        lastMod = ((ang % 360.0) + 360.0) % 360.0
        lastValidT = t
        if (rawCum - committed > p.deadbandDeg) committed = rawCum
        return valid(t, contrast, wide)
    }

    /** مركز ثقل القاع (فوق نصف العمق) حول الفهرس: أثبت من أدنى نقطة لأن قاع الملف المنعَّم مسطّح. */
    private fun refine(i: Int): Double {
        val n = HandProfile.N
        var c = i.toDouble()
        repeat(2) {
            val ci = Math.round(c).toInt()
            val depth = -prof[((ci % n) + n) % n]
            val thr = 0.5 * depth
            var sw = 0.0; var sd = 0.0
            for (d in -CENTROID_HALF..CENTROID_HALF) {
                val w = -prof[(((ci + d) % n) + n) % n] - thr
                if (w > 0) { sw += w; sd += w * d }
            }
            if (sw > 0) c = ci + sd / sw
        }
        return c
    }

    private companion object { const val CENTROID_HALF = 22 }

    private fun valid(t: Double, c: Double, wide: Boolean) =
        MeterSample(t, true, committed, lastMod, c, sx, sy, wide)

    private fun invalid(t: Double) =
        MeterSample(t, false, committed, lastMod, 0.0, sx, sy, false)
}
