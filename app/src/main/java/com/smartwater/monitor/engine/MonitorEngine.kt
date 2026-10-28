package com.smartwater.monitor.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceHolder
import com.smartwater.monitor.camera.FrameSource
import com.smartwater.monitor.camera.HikSourceFactory
import com.smartwater.monitor.camera.VideoFileSource
import com.smartwater.monitor.core.GrayFrame
import com.smartwater.monitor.core.SwingResult
import com.smartwater.monitor.core.SwingType
import com.smartwater.monitor.data.AppConfig
import com.smartwater.monitor.data.Store
import com.smartwater.monitor.data.SwingDb
import com.smartwater.monitor.data.SwingRecord
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MeterState(
    val name: String, val valid: Boolean, val active: Boolean,
    val totalDeg: Double, val rawAngle: Double, val currentDeg: Double,
    val contrast: Double, val shiftX: Double, val shiftY: Double,
)

class UiState(val connected: Boolean, val running: Boolean, val frameW: Int, val frameH: Int, val meters: List<MeterState>, val fps: Double)

/** المحرك المشترك بين الواجهة والخدمة: مصدر إطارات -> Pipeline -> سجل الزفات. */
class MonitorEngine(private val ctx: Context) : FrameSource.Listener {
    interface UiListener {
        fun onState(s: UiState)
        fun onSwing(text: String)
        fun onStatus(msg: String)
    }

    val store = Store(ctx)
    val db = SwingDb(ctx)
    @Volatile var cfg: AppConfig = store.load()
    var uiListener: UiListener? = null

    private val ui = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("monitor-proc").also { it.start() }
    private val wh = Handler(worker.looper)
    private val busy = AtomicBoolean(false)
    private var inBuf = ByteArray(0)

    private var source: FrameSource? = null
    @Volatile private var pipeline: Pipeline? = null
    @Volatile private var persist = false
    @Volatile var running = false
        private set
    @Volatile private var connected = false
    private var lastT = 0.0
    private var lastUi = 0L
    private var fpsCount = 0
    private var fpsT = 0L
    private var fps = 0.0

    // آخر إطار كامل (للمعايرة) ولقطة المعايرة
    private val lock = Any()
    private var latestMat: Mat? = null
    private var stillMat: Mat? = null
    val stillWidth: Int get() = synchronized(lock) { stillMat?.cols() ?: 0 }
    val stillHeight: Int get() = synchronized(lock) { stillMat?.rows() ?: 0 }

    // ---------- التشغيل ----------
    private fun newPipeline(): Pipeline {
        cfg = store.load()
        val refs = List(cfg.meters.size) { store.loadReference(it) }
        return Pipeline(cfg.meters, cfg.swing, refs) { onSwingResult(it) }
    }

    fun startCamera(holder: SurfaceHolder?) {
        stop()
        pipeline = newPipeline()
        persist = true; running = true; connected = false
        val c = cfg
        status("جارٍ الاتصال بـ ${c.ip}:8000 ، القناة ${c.channel} ...")
        source = HikSourceFactory.create(ctx, c.ip, c.user, c.pass, c.channel, c.streamType, c.realChannel, holder)
            .also { it.start(this) }
    }

    /** فيديو محلي كمصدر: لا يُكتب في السجل الحقيقي (للتجربة فقط). */
    fun startVideo(uri: Uri) {
        stop()
        pipeline = newPipeline()
        persist = false; running = true; connected = true
        status("تشغيل فيديو اختبار (لا يُسجَّل في السجل)")
        source = VideoFileSource(ctx, uri, 100, realtime = true).also { it.start(this) }
    }

    fun stop() {
        val s = source ?: return
        source = null
        s.stop()
        wh.post {
            pipeline?.swing?.flush()
            running = false; connected = false
            ui.post { uiListener?.onStatus("متوقف") }
        }
    }

    // ---------- استقبال الإطارات ----------
    override fun onFrame(gray: ByteArray, width: Int, height: Int, tSec: Double) {
        if (!busy.compareAndSet(false, true)) return          // المعالج مشغول: نُسقط الإطار (الزمن حقيقي فلا يتأثر الحساب)
        val n = width * height
        if (inBuf.size != n) inBuf = ByteArray(n)
        System.arraycopy(gray, 0, inBuf, 0, n)
        wh.post {
            try { process(inBuf, width, height, tSec) } catch (e: Exception) { status("خطأ معالجة: ${e.message}") }
            finally { busy.set(false) }
        }
    }

    override fun onStatus(message: String) = status(message)

    override fun onDisconnected() {
        connected = false
        wh.post { pipeline?.swing?.markGap() }   // انقطاع البث ليس توقف عدّاد
        status("انقطع البث — لا يُحتسب توقفًا للعدّادين")
        publish()
    }

    override fun onFinished() {
        wh.post {
            pipeline?.swing?.flush()
            running = false
            status("انتهى الفيديو")
            publish()
        }
    }

    private fun process(gray: ByteArray, w: Int, h: Int, t: Double) {
        val p = pipeline ?: return
        connected = true
        val full = Roi.toMat(gray, w, h)
        p.process(full, t)
        lastT = t
        val now = System.currentTimeMillis()
        if (now - lastUi > 200) {
            lastUi = now
            synchronized(lock) { latestMat?.release(); latestMat = full }
            publish(w, h)
        } else full.release()
        fpsCount++
        if (fpsT == 0L) fpsT = now
        if (now - fpsT >= 2000) { fps = fpsCount * 1000.0 / (now - fpsT); fpsCount = 0; fpsT = now }
    }

    private fun publish(w: Int = 0, h: Int = 0) {
        val p = pipeline ?: return
        val smp = p.samples
        val ms = p.meters.indices.map { i ->
            val s = smp.getOrNull(i)
            MeterState(
                p.meters[i].name, s?.valid ?: false, p.swing.isActive(i),
                p.trackers[i].totalDegrees, s?.rawAngleDeg ?: 0.0, p.swing.currentDegrees(i),
                s?.contrast ?: 0.0, s?.shiftX ?: 0.0, s?.shiftY ?: 0.0,
            )
        }
        val st = UiState(connected, running, w, h, ms, fps)
        ui.post { uiListener?.onState(st) }
    }

    private fun status(m: String) { ui.post { uiListener?.onStatus(m) } }

    // ---------- نتائج الزفات ----------
    private fun wallAt(t: Double) = System.currentTimeMillis() - ((lastT - t) * 1000).toLong()

    private fun onSwingResult(r: SwingResult) {
        val names = cfg.meters.map { it.name }
        val kind = if (r.type == SwingType.FULL) "زفة كاملة" else "زفة عداد"
        val parts = r.parts.joinToString("\n") { "• ${names[it.meter]}: %.2f برميل (%.0f°)".format(Locale.US, it.barrels, it.degrees) }
        var seqTxt = ""
        if (persist) {
            val rec: SwingRecord = db.insert(r, wallAt(r.startT), wallAt(r.endT))
            seqTxt = "زفة رقم ${rec.seq} — "
        }
        val text = "$seqTxt$kind\n$parts\nالإجمالي: %.2f برميل".format(Locale.US, r.totalBarrels) +
            (if (r.notes.isNotEmpty()) "\n⚠ ${r.notes}" else "")
        ui.post { uiListener?.onSwing(text) }
    }

    // ---------- المعايرة ----------
    fun setStillFromLatest(): Boolean = synchronized(lock) {
        val l = latestMat ?: return false
        stillMat?.release(); stillMat = l.clone(); true
    }

    /** معاينة آخر إطار حي بتصغير يناسب العرض. */
    fun latestBitmap(maxDim: Int): Bitmap? = synchronized(lock) {
        val m = latestMat ?: return null
        val sc = min(1.0, maxDim.toDouble() / max(m.cols(), m.rows()))
        val out = Mat()
        Imgproc.resize(m, out, Size(m.cols() * sc, m.rows() * sc), 0.0, 0.0, Imgproc.INTER_AREA)
        val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        out.release()
        bmp
    }

    fun setStill(gray: ByteArray, w: Int, h: Int) = synchronized(lock) {
        stillMat?.release(); stillMat = Roi.toMat(gray, w, h)
    }

    /** بتصغير يناسب العرض؛ المنطقة بإحداثيات الإطار الكامل. تُرجع البتماب والمنطقة الفعلية المقصوصة. */
    fun stillBitmap(x0: Double, y0: Double, x1: Double, y1: Double, maxDim: Int): Pair<Bitmap, FloatArray>? = synchronized(lock) {
        val m = stillMat ?: return null
        val rx0 = max(0, x0.roundToInt()); val ry0 = max(0, y0.roundToInt())
        val rx1 = min(m.cols(), x1.roundToInt()); val ry1 = min(m.rows(), y1.roundToInt())
        if (rx1 - rx0 < 8 || ry1 - ry0 < 8) return null
        val sub = Mat(m, Rect(rx0, ry0, rx1 - rx0, ry1 - ry0))
        val sc = min(1.0, maxDim.toDouble() / max(sub.cols(), sub.rows()))
        val out = Mat()
        Imgproc.resize(sub, out, Size(sub.cols() * sc, sub.rows() * sc), 0.0, 0.0, Imgproc.INTER_AREA)
        val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        sub.release(); out.release()
        Pair(bmp, floatArrayOf(rx0.toFloat(), ry0.toFloat(), rx1.toFloat(), ry1.toFloat()))
    }

    /** يحفظ مرجع كل عدّاد (ROI) من لقطة المعايرة الحالية مع المعايرة الجديدة. */
    fun saveCalibration(newCfg: AppConfig): Boolean = synchronized(lock) {
        val m = stillMat ?: return false
        newCfg.meters.forEachIndexed { i, mc -> store.saveReference(i, Roi.extract(m, mc)) }
        store.save(newCfg); cfg = newCfg
        true
    }

    // ---------- فحص الدقة على فيديو ----------
    /** يشغّل المعايرة الحالية على كل إطارات فيديو (مستقل عن البث الحي) ويعيد تقريرًا نصيًا. */
    fun analyzeVideo(uri: Uri, progress: (Int) -> Unit, done: (String) -> Unit) {
        Thread({
            val c = store.load()
            val refs = List(c.meters.size) { store.loadReference(it) }
            val results = ArrayList<SwingResult>()
            val p = Pipeline(c.meters, c.swing, refs) { results.add(it) }
            val dur = VideoFileSource.durationMs(ctx, uri)
            val latch = CountDownLatch(1)
            val src = VideoFileSource(ctx, uri, 100, realtime = false)
            src.start(object : FrameSource.Listener {
                override fun onFrame(gray: ByteArray, width: Int, height: Int, tSec: Double) {
                    val full = Roi.toMat(gray, width, height)
                    p.process(full, tSec); full.release()
                    if (dur > 0) progress(min(100, (tSec * 1000 * 100 / dur).toInt()))
                }
                override fun onStatus(message: String) {}
                override fun onDisconnected() {}
                override fun onFinished() { latch.countDown() }
            })
            latch.await()
            p.swing.flush()
            val sb = StringBuilder()
            sb.append("إطارات معالجة: ${p.frames}\n")
            c.meters.forEachIndexed { i, m ->
                val d = p.trackers[i].totalDegrees
                sb.append("%s: %.1f° = %.3f برميل (إطارات بلا قياس: %d)\n".format(Locale.US, m.name, d, d / 360.0, p.invalid[i]))
            }
            sb.append("زفات مكتشفة: ${results.size}\n")
            results.forEach { r ->
                sb.append("• ${if (r.type == SwingType.FULL) "زفة كاملة" else "زفة عداد"}: ")
                sb.append(r.parts.joinToString(" | ") { "${c.meters[it.meter].name} %.3f".format(Locale.US, it.barrels) })
                sb.append(" = %.3f برميل".format(Locale.US, r.totalBarrels))
                if (r.notes.isNotEmpty()) sb.append("  (${r.notes})")
                sb.append('\n')
            }
            ui.post { done(sb.toString()) }
        }, "analyze-video").start()
    }
}
