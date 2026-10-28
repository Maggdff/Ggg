import com.smartwater.monitor.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** يقرأ إطارات ROI المقصوصة من الفيديو الحقيقي ويشغّل نواة التتبع + محرك الزفات بنفس كود التطبيق. */
fun readFrames(path: String): List<GrayFrame> {
    val b = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val out = ArrayList<GrayFrame>()
    while (b.remaining() > 16) {
        val w = b.int; val h = b.int; val ox = b.int; val oy = b.int
        val d = ByteArray(w * h); b.get(d)
        out.add(GrayFrame(w, h, d, ox, oy))
    }
    return out
}

fun main(args: Array<String>) {
    val dir = args[0]
    val tag = args.getOrElse(1) { "run" }
    val cfgs = listOf(MeterConfig("L", 505.0, 1232.0, 60.0), MeterConfig("R", 1887.0, 1337.0, 62.0))
    val fr = listOf(readFrames("$dir/frames_L.bin"), readFrames("$dir/frames_R.bin"))
    val times = File("$dir/times.txt").readLines().map { it.split(" ")[0].toDouble() }
    val trackers = cfgs.mapIndexed { i, c -> MeterTracker(c, TrackerParams(), readFrames("$dir/ref_${c.name}.bin")[0]) }
    val results = ArrayList<SwingResult>()
    val engine = SwingEngine(2) { results.add(it) }
    val csv = StringBuilder("t,L_cum,L_raw,L_contrast,L_valid,L_sx,L_sy,R_cum,R_raw,R_contrast,R_valid,R_sx,R_sy\n")
    var invalidFrames = intArrayOf(0, 0)
    val dropFrom = args.getOrNull(2)?.toDoubleOrNull() ?: -1.0   // محاكاة انقطاع بث: [dropFrom, dropTo)
    val dropTo = args.getOrNull(3)?.toDoubleOrNull() ?: -1.0
    var wideFrames = 0
    val t0 = System.nanoTime()
    for (k in times.indices) {
        if (times[k] >= dropFrom && times[k] < dropTo) { engine.markGap(); continue }
        val s = (0..1).map { trackers[it].update(fr[it][k], times[k]) }
        for (x in s) if (x.uncertainJump) wideFrames++
        for (i in 0..1) if (!s[i].valid) invalidFrames[i]++
        engine.update(times[k], s)
        csv.append("%.3f".format(times[k]))
        for (x in s) csv.append(",%.2f,%.1f,%.3f,%d,%.2f,%.2f".format(x.angleDeg, x.rawAngleDeg, x.contrast, if (x.valid) 1 else 0, x.shiftX, x.shiftY))
        csv.append('\n')
    }
    val ms = (System.nanoTime() - t0) / 1e6
    engine.flush()
    File("$dir/trace_$tag.csv").writeText(csv.toString())
    println("إطارات=${times.size} زمن المعالجة=%.0f ms (%.1f ms/إطار لعدّادين)".format(ms, ms / times.size))
    for (i in 0..1) println("عدّاد ${cfgs[i].name}: درجات=%.1f  براميل=%.3f  إطارات غير صالحة=${invalidFrames[i]}".format(trackers[i].totalDegrees, trackers[i].totalDegrees / 360.0))
    println("إطارات بحث واسع (قفزة غير مؤكدة)=$wideFrames")
    println("زفات مسجلة=${results.size}  اهتزازات مهملة=${engine.ignoredBlips}")
    for (r in results) println("  [%s] t=%.1f..%.1f  %s  إجمالي=%.3f برميل  %s".format(
        if (r.type == SwingType.FULL) "زفة كاملة" else "زفة عداد", r.startT, r.endT,
        r.parts.joinToString(" | ") { "${cfgs[it.meter].name}: %.1f° = %.3f".format(it.degrees, it.barrels) }, r.totalBarrels, r.notes))
}
