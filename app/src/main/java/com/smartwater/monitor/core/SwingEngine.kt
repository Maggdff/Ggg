package com.smartwater.monitor.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SwingType { METER, FULL }

class MeterPart(
    val meter: Int,
    val degrees: Double,
    val startT: Double,
    val endT: Double,
) {
    /** كل 360° = برميل واحد (كل 180° = نصف برميل). */
    val barrels: Double get() = degrees / 360.0
}

class SwingResult(
    val type: SwingType,
    val parts: List<MeterPart>,
    val notes: String,
) {
    val startT: Double get() = parts.minOf { it.startT }
    val endT: Double get() = parts.maxOf { it.endT }
    val totalBarrels: Double get() = parts.sumOf { it.barrels }
}

data class SwingParams(
    val startDeg: Double = 6.0,        // حركة حقيقية: تقدّم ≥ هذا خلال startWindowSec
    val startWindowSec: Double = 2.0,
    val stopDeg: Double = 3.0,         // التوقف: تقدّم < هذا خلال stopWindowSec
    val stopWindowSec: Double = 3.0,
    val minSwingDeg: Double = 20.0,    // أقل من هذا يُعدّ اهتزازًا لا زفة
    val endToleranceSec: Double = 3.0, // أقصى فارق بين نهايتي العدّادين لتُعدّ "كاملة"
    val minOverlapSec: Double = 1.0,
    val gapSec: Double = 1.5,          // فاصل بين عيّنتين أطول من هذا = انقطاع (ليس توقفًا)
)

/**
 * آلة حالات الزفات.
 *  - لا تبدأ حركة عدّاد إلا بعد تقدّم ≥ startDeg (حركة حقيقية)، وتُحسب من نقطة السكون السابقة.
 *  - يتوقف العدّاد فقط بعد stopWindowSec من العيّنات *المرصودة* بلا تقدّم؛
 *    انقطاع البث أو فقدان التتبع يوقف الساعة ولا يُحسب توقفًا.
 *  - الحلقة (episode) تنتهي حين يكون كل العدّادين ساكنين: عدّاد واحد وحده = "زفة عداد"،
 *    عدّادان متداخلان وينتهيان معًا (ضمن endToleranceSec) = "زفة كاملة"، وإلا فلكلٍّ زفة عداد.
 */
class SwingEngine(
    private val meters: Int,
    private val p: SwingParams = SwingParams(),
    private val onResult: (SwingResult) -> Unit,
) {
    private class MS {
        var active = false
        var sa = Double.NaN; var st = 0.0          // مرساة البداية (وضع الخمول)
        var startA = 0.0; var startT = 0.0
        var xa = 0.0; var xt = 0.0                 // مرساة التوقف (وضع الحركة)
        var lastA = Double.NaN; var lastT = 0.0
        var pendingGap = false
        var uncertain = false
    }

    private class Seg(val meter: Int, val startT: Double, val endT: Double, val deg: Double, val uncertain: Boolean, val truncated: Boolean)

    private val ms = Array(meters) { MS() }
    private val finished = ArrayList<Seg>()
    var ignoredBlips = 0
        private set

    fun isActive(i: Int) = ms[i].active

    /** درجات الزفة الجارية لهذا العدّاد (0 إن كان ساكنًا). */
    fun currentDegrees(i: Int): Double = if (ms[i].active) ms[i].lastA - ms[i].startA else 0.0

    /** تُستدعى لكل إطار؛ العنصر null أو غير صالح = لا قياس (لا يُعدّ توقفًا). */
    fun update(t: Double, samples: List<MeterSample?>) {
        for (i in 0 until meters) step(i, t, samples.getOrNull(i))
        if (ms.none { it.active } && finished.isNotEmpty()) resolve()
    }

    /** إشعار صريح بانقطاع البث. */
    fun markGap() { ms.forEach { it.pendingGap = true } }

    /** نهاية الجلسة/الفيديو: تُغلق الحركات الجارية وتُسجَّل مع ملاحظة. */
    fun flush() {
        for (i in 0 until meters) {
            val m = ms[i]
            if (m.active) close(i, m, m.lastA, m.xt.coerceAtLeast(m.lastT), "انتهى البث أثناء الحركة")
        }
        if (finished.isNotEmpty()) resolve()
    }

    private fun step(i: Int, t: Double, s: MeterSample?) {
        val m = ms[i]
        if (s == null || !s.valid) { m.pendingGap = true; return }
        val a = s.angleDeg
        if (m.lastA.isNaN()) { m.sa = a; m.st = t; m.lastA = a; m.lastT = t; return }
        val gap = m.pendingGap || (t - m.lastT) > p.gapSec
        if (s.uncertainJump) m.uncertain = true
        m.pendingGap = false

        if (!m.active) {
            if (a - m.sa >= p.startDeg) {
                m.active = true
                m.startA = m.sa; m.startT = m.st
                m.xa = a; m.xt = t
                m.uncertain = s.uncertainJump || gap
            } else if (t - m.st >= p.startWindowSec || gap) {
                m.sa = a; m.st = t
            }
        } else {
            if (a - m.xa >= p.stopDeg) {
                m.xa = a; m.xt = t
            } else if (gap) {
                // لا نحتسب الغياب سكونًا: نعيد تشغيل ساعة السكون من أول عيّنة بعد الانقطاع
                m.xa = a; m.xt = t
            } else if (t - m.xt >= p.stopWindowSec) {
                close(i, m, a, m.xt, "")
            }
        }
        m.lastA = a; m.lastT = t
    }

    private fun close(i: Int, m: MS, endA: Double, endT: Double, note: String) {
        val deg = endA - m.startA
        if (deg < p.minSwingDeg) ignoredBlips++
        else finished.add(Seg(i, m.startT, endT, deg, m.uncertain, note.isNotEmpty()))
        m.active = false
        m.sa = m.lastA; m.st = m.lastT
        m.uncertain = false
    }

    private fun resolve() {
        val segs = finished.sortedBy { it.startT }
        finished.clear()
        if (segs.size == 2 && segs[0].meter != segs[1].meter) {
            val a = segs[0]; val b = segs[1]
            val overlap = min(a.endT, b.endT) - max(a.startT, b.startT)
            if (overlap >= p.minOverlapSec && abs(a.endT - b.endT) <= p.endToleranceSec) {
                onResult(SwingResult(SwingType.FULL,
                    segs.sortedBy { it.meter }.map { MeterPart(it.meter, it.deg, it.startT, it.endT) },
                    noteOf(segs)))
                return
            }
        }
        for (s in segs) onResult(SwingResult(SwingType.METER,
            listOf(MeterPart(s.meter, s.deg, s.startT, s.endT)), noteOf(listOf(s))))
    }

    private fun noteOf(l: List<Seg>): String {
        val n = ArrayList<String>()
        if (l.any { it.truncated }) n.add("انتهى البث أثناء الحركة (زفة غير مكتملة)")
        if (l.any { it.uncertain }) n.add("تحتوي فجوة/فقدان تتبع — الكمية تقريبية")
        return n.joinToString("؛ ")
    }
}
