import com.smartwater.monitor.core.*
import kotlin.math.abs
import kotlin.random.Random

/** اختبارات محرك الزفات بمسارات زاوية اصطناعية معروفة الإجابة (سرعة 55°/ث ≈ ما قيس في الفيديو). */
private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  نجح  " else "  فشل  ") + name + (if (detail.isNotEmpty()) "  -> $detail" else ""))
    if (!ok) failures++
}

/** مقطع حركة: [from,to) بسرعة v درجة/ث. outages: فترات لا قياس فيها. */
private class Run(val moves: List<Triple<Double, Double, Double>>, val outages: List<Pair<Double, Double>> = emptyList(), val noise: Double = 0.3)

private fun simulate(runs: List<Run>, until: Double, params: SwingParams = SwingParams()): List<SwingResult> {
    val out = ArrayList<SwingResult>()
    val eng = SwingEngine(runs.size, params) { out.add(it) }
    val rnd = Random(1)
    val trk = runs.map { MeterTracker0() }
    var t = 0.0
    val dt = 0.1
    val cum = DoubleArray(runs.size)
    while (t <= until) {
        val samples = runs.mapIndexed { i, r ->
            // التقدّم الحقيقي يستمر حتى أثناء الانقطاع
            for ((a, b, v) in r.moves) if (t >= a && t < b) cum[i] += v * dt
            val out2 = r.outages.any { t >= it.first && t < it.second }
            // يحاكي مخرج MeterTracker: زاوية مثبّتة لا تتراجع
            val meas = cum[i] + rnd.nextDouble(-r.noise, r.noise)
            trk[i].committed = maxOf(trk[i].committed, if (meas - trk[i].committed > 1.5) meas else trk[i].committed)
            MeterSample(t, !out2, trk[i].committed, 0.0, 0.1, 0.0, 0.0, false)
        }
        eng.update(t, samples)
        t += dt
    }
    eng.flush()
    return out
}
private class MeterTracker0 { var committed = 0.0 }

fun main() {
    val V = 55.0
    println("1) لا حركة حقيقية (ضجيج ±1° فقط) لمدة 60 ث -> لا زفة")
    var r = simulate(listOf(Run(emptyList(), noise = 1.0), Run(emptyList(), noise = 1.0)), 60.0)
    check("لا زفات", r.isEmpty(), "عدد=${r.size}")

    println("2) عدّاد واحد يتحرك 20 ث ثم يتوقف -> زفة عداد")
    r = simulate(listOf(Run(listOf(Triple(5.0, 25.0, V))), Run(emptyList())), 40.0)
    check("زفة واحدة من نوع عداد", r.size == 1 && r[0].type == SwingType.METER)
    check("الكمية ≈ 3.056 برميل (1100°)", r.size == 1 && abs(r[0].totalBarrels - 1100.0 / 360) < 0.02, "قيس=%.3f".format(r.firstOrNull()?.totalBarrels ?: -1.0))

    println("3) العدّادان معًا ويتوقفان بفارق 0.5 ث -> زفة كاملة")
    r = simulate(listOf(Run(listOf(Triple(5.0, 25.0, V))), Run(listOf(Triple(5.5, 24.5, V * 1.1)))), 40.0)
    check("زفة كاملة واحدة", r.size == 1 && r[0].type == SwingType.FULL)
    check("الإجمالي = مجموع العدّادين", r.size == 1 && abs(r[0].totalBarrels - r[0].parts.sumOf { it.barrels }) < 1e-9)
    check("كل عدّاد محسوب مستقلًا", r.size == 1 && r[0].parts.size == 2 && abs(r[0].parts[0].barrels - r[0].parts[1].barrels) > 0.01,
        r.firstOrNull()?.parts?.joinToString { "%.3f".format(it.barrels) } ?: "")

    println("4) توقف أحدهما قبل الآخر بـ 15 ث -> لا زفة كاملة")
    r = simulate(listOf(Run(listOf(Triple(5.0, 15.0, V))), Run(listOf(Triple(5.0, 30.0, V)))), 50.0)
    check("لا زفة كاملة", r.none { it.type == SwingType.FULL })
    check("زفتا عداد منفصلتان", r.size == 2 && r.all { it.type == SwingType.METER }, "عدد=${r.size}")

    println("5) انقطاع البث 12..16 ث أثناء الحركة -> ليس توقفًا (زفة واحدة، والحركة أثناء الانقطاع محسوبة)")
    r = simulate(listOf(Run(listOf(Triple(5.0, 30.0, V)), listOf(12.0 to 16.0)), Run(emptyList())), 50.0)
    check("زفة واحدة فقط", r.size == 1, "عدد=${r.size}")
    check("الكمية ≈ 1375° = 3.82 برميل", r.size == 1 && abs(r[0].totalBarrels - 1375.0 / 360) < 0.03, "قيس=%.3f".format(r.firstOrNull()?.totalBarrels ?: -1.0))

    println("6) انقطاع طويل (20 ث) والعدّاد ساكن -> لا يُغلق الزفة بسبب الانقطاع وحده")
    r = simulate(listOf(Run(listOf(Triple(5.0, 10.0, V)), listOf(10.5 to 30.0)), Run(emptyList())), 36.0)
    check("الزفة لم تُغلق قبل مرور 3 ث مرصودة بعد عودة البث", r.size == 1 && r[0].endT >= 29.0, "endT=%.1f".format(r.firstOrNull()?.endT ?: -1.0))

    println("7) توقف قصير 2 ث (< نافذة التوقف 3 ث) -> زفة واحدة")
    r = simulate(listOf(Run(listOf(Triple(5.0, 10.0, V), Triple(12.0, 20.0, V))), Run(emptyList())), 40.0)
    check("زفة واحدة", r.size == 1, "عدد=${r.size}")

    println("8) اهتزاز 10° فقط (< الحد الأدنى للزفة) -> يُهمل")
    r = simulate(listOf(Run(listOf(Triple(5.0, 5.2, V))), Run(emptyList())), 30.0)
    check("لا زفات", r.isEmpty(), "عدد=${r.size}")

    println("9) عدّادان بزفتين متتاليتين منفصلتين زمنيًا -> زفتا عداد")
    r = simulate(listOf(Run(listOf(Triple(5.0, 20.0, V))), Run(listOf(Triple(40.0, 55.0, V)))), 80.0)
    check("زفتا عداد", r.size == 2 && r.all { it.type == SwingType.METER }, "عدد=${r.size}")

    println("10) انتهاء الفيديو أثناء الحركة -> تُسجَّل بملاحظة")
    r = simulate(listOf(Run(listOf(Triple(5.0, 100.0, V))), Run(emptyList())), 20.0)
    check("زفة بملاحظة 'انتهى البث'", r.size == 1 && r[0].notes.isNotEmpty())

    println(if (failures == 0) "كل الاختبارات نجحت" else "فشل $failures اختبار")
    if (failures > 0) System.exit(1)
}
