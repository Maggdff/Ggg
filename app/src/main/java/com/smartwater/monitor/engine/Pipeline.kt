package com.smartwater.monitor.engine

import com.smartwater.monitor.core.GrayFrame
import com.smartwater.monitor.core.MeterConfig
import com.smartwater.monitor.core.MeterSample
import com.smartwater.monitor.core.MeterTracker
import com.smartwater.monitor.core.SwingEngine
import com.smartwater.monitor.core.SwingParams
import com.smartwater.monitor.core.SwingResult
import com.smartwater.monitor.core.TrackerParams
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** قص نافذة ROI لكل عدّاد وتنعيم خفيف بـ OpenCV (نفس معالجة بيانات الاختبار على JVM: σ=1.2). */
object Roi {
    fun toMat(gray: ByteArray, w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_8UC1)
        m.put(0, 0, gray)
        return m
    }

    fun extract(full: Mat, m: MeterConfig): GrayFrame {
        // يغطي حلقة الدرجات (1.02R) + هامش لإزاحة الاهتزاز
        val half = ceil(1.1 * m.radius).toInt() + 30
        val x0 = max(0, (m.cx - half).roundToInt())
        val y0 = max(0, (m.cy - half).roundToInt())
        val x1 = min(full.cols(), (m.cx + half).roundToInt())
        val y1 = min(full.rows(), (m.cy + half).roundToInt())
        require(x1 - x0 >= 8 && y1 - y0 >= 8) { "${m.name} خارج حدود الإطار ${full.cols()}×${full.rows()}" }
        val roi = Mat(full, Rect(x0, y0, x1 - x0, y1 - y0))
        val blur = Mat()
        Imgproc.GaussianBlur(roi, blur, Size(0.0, 0.0), 1.2)
        val w = blur.cols(); val h = blur.rows()
        val bytes = ByteArray(w * h)
        blur.get(0, 0, bytes)
        roi.release(); blur.release()
        return GrayFrame(w, h, bytes, x0, y0)
    }
}

/** تتبّع العدّادين + محرك الزفات لسلسلة إطارات (حيّة أو من فيديو). */
class Pipeline(
    val meters: List<MeterConfig>,
    swingParams: SwingParams,
    refs: List<GrayFrame?>,
    onSwing: (SwingResult) -> Unit,
) {
    val trackers = meters.mapIndexed { i, m -> MeterTracker(m, TrackerParams(), refs.getOrNull(i)) }
    val swing = SwingEngine(meters.size, swingParams, onSwing)
    @Volatile var samples: List<MeterSample> = emptyList()
    var frames = 0
    val invalid = IntArray(meters.size)

    fun process(full: Mat, t: Double) {
        val s = meters.indices.map { trackers[it].update(Roi.extract(full, meters[it]), t) }
        for (i in s.indices) if (!s[i].valid) invalid[i]++
        samples = s
        frames++
        swing.update(t, s)
    }
}
