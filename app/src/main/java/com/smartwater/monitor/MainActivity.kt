package com.smartwater.monitor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.smartwater.monitor.camera.VideoFileSource
import com.smartwater.monitor.core.MeterConfig
import com.smartwater.monitor.core.SwingParams
import com.smartwater.monitor.engine.MonitorEngine
import com.smartwater.monitor.engine.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/** ثلاث شاشات: المراقبة، المعايرة والتحقق، السجل. الواجهة مبنية برمجيًا لتقليل أخطاء الموارد. */
class MainActivity : AppCompatActivity(), MonitorEngine.UiListener {
    private lateinit var engine: MonitorEngine
    private lateinit var content: FrameLayout
    private lateinit var tabViews: List<View>
    private lateinit var rtspHost: FrameLayout
    private lateinit var etPort: EditText
    private lateinit var etRtsp: EditText

    // --- المراقبة
    private lateinit var tvStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etChannel: EditText
    private lateinit var etStream: EditText
    private lateinit var monView: FrameView
    private lateinit var tvM1: TextView
    private lateinit var tvM2: TextView
    private lateinit var tvSwing: TextView
    private lateinit var tvDay: TextView

    // --- المعايرة
    private lateinit var calView: FrameView
    private lateinit var tvCal: TextView
    private lateinit var tvReport: TextView
    private lateinit var seek: SeekBar
    private var draft: MutableList<MeterConfig> = mutableListOf()
    private var selected = 0
    private var tapMode = 0          // 0 محور، 1 حافة القرص
    private var zoom = true
    private var videoUri: Uri? = null
    private var videoDur = 0L

    // --- السجل
    private lateinit var logBox: LinearLayout
    private lateinit var tvLogTotal: TextView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            videoUri = uri
            videoDur = VideoFileSource.durationMs(this, uri)
            toast("تم اختيار الفيديو (${videoDur / 1000} ث)")
            loadVideoFrame(0)
        }
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { engine.db.exportCsv(it) }
                toast("تم تصدير السجل")
            } catch (e: Exception) { toast("فشل التصدير: ${e.message}") }
        }
    }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as App
        engine = app.engine
        draft = engine.cfg.meters.toMutableList()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        // معاينة البث (تبقى ظاهرة أثناء التشغيل لأن الإطارات تُلتقط منها)؛ مخفية عند الإيقاف
        rtspHost = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); clipChildren = true; visibility = View.GONE }
        root.addView(rtspHost, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * 150).toInt()))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("المراقبة", "المعايرة والتحقق", "السجل")
        tabViews = listOf(buildMonitor(), buildCalibration(), buildLog())
        names.forEachIndexed { i, n ->
            tabs.addView(Button(this).apply { text = n; setOnClickListener { showTab(i) } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabs)
        tabViews.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        showTab(0)
        if (!app.openCvReady) tvStatus.text = "تحذير: فشل تحميل OpenCV"
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() { super.onStart(); engine.uiListener = this; refreshLog() }
    override fun onStop() { super.onStop(); if (engine.uiListener === this) engine.uiListener = null }

    private fun showTab(i: Int) {
        tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        if (i == 1) { draft = engine.cfg.meters.toMutableList(); refreshCal() }
        if (i == 2) refreshLog()
    }

    // ================= المراقبة =================
    private fun buildMonitor(): View {
        val c = col()
        val cfg = engine.cfg
        tvStatus = text("غير متصل")
        c.addView(tvStatus)
        c.addView(text("الاتصال عبر RTSP (المنفذ 554). القناة 4 رئيسي = المسار /Streaming/Channels/401"))
        etIp = edit("IP الكاميرا/الجهاز", cfg.ip)
        etUser = edit("اسم المستخدم", cfg.user)
        etPass = edit("كلمة المرور", cfg.pass).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        etChannel = edit("رقم القناة", cfg.channel.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        etStream = edit("نوع البث (0 رئيسي ، 1 فرعي)", cfg.streamType.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        etPort = edit("منفذ RTSP", cfg.rtspPort.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        etRtsp = edit("عنوان RTSP كامل (اختياري، يتجاوز الحقول أعلاه)", cfg.rtspUrl)
        listOf(etIp, etUser, etPass, etChannel, etStream, etPort, etRtsp).forEach { c.addView(it) }
        c.addView(row(
            btn("اتصال بالكاميرا") { connectCamera() },
            btn("فيديو اختبار") { playTestVideo() },
            btn("إيقاف") { stopMonitoring() },
        ))
        monView = FrameView(this)
        c.addView(monView, LinearLayout.LayoutParams(-1, -2))
        tvM1 = text("—"); tvM2 = text("—"); tvSwing = text("لا زفات بعد").apply { textSize = 18f }
        tvDay = text("")
        listOf(tvM1, tvM2, tvSwing, tvDay).forEach { c.addView(it) }
        return scroll(c)
    }

    private fun readConfigFields() = engine.cfg.copy(
        ip = etIp.text.toString().trim(), user = etUser.text.toString().trim(), pass = etPass.text.toString(),
        channel = etChannel.text.toString().toIntOrNull() ?: 4, streamType = etStream.text.toString().toIntOrNull() ?: 0,
        rtspPort = etPort.text.toString().toIntOrNull() ?: 554, rtspUrl = etRtsp.text.toString().trim(),
    )

    private fun connectCamera() {
        engine.store.save(readConfigFields()); engine.cfg = engine.store.load()
        MonitorService.start(this)
        rtspHost.visibility = View.VISIBLE
        engine.startCamera(rtspHost)
    }

    private fun playTestVideo() {
        val u = videoUri
        if (u == null) { pickVideo.launch(arrayOf("video/*")); toast("اختر الفيديو ثم اضغط الزر مجددًا"); return }
        rtspHost.visibility = View.GONE
        engine.startVideo(u)
    }

    private fun stopMonitoring() { engine.stop(); MonitorService.stop(this); rtspHost.visibility = View.GONE }

    override fun onStatus(msg: String) { tvStatus.text = msg }

    override fun onSwing(text: String) {
        tvSwing.text = text
        refreshLog()
    }

    override fun onState(s: UiState) {
        val fmt = { m: com.smartwater.monitor.engine.MeterState ->
            "${m.name}: " + (if (!m.valid) "لا قياس (ليس توقفًا) — " else "") +
                (if (m.active) "يتحرك — الزفة الجارية %.2f برميل".format(Locale.US, m.currentDeg / 360.0) else "ساكن") +
                "\n   الزاوية %.0f° ، المجموع %.2f برميل".format(Locale.US, m.rawAngle, m.totalDeg / 360.0)
        }
        if (s.meters.size >= 2) { tvM1.text = fmt(s.meters[0]); tvM2.text = fmt(s.meters[1]) }
        val bmp = engine.latestBitmap(1000)
        if (bmp != null && s.frameW > 0) {
            monView.bitmap = bmp
            monView.region = floatArrayOf(0f, 0f, s.frameW.toFloat(), s.frameH.toFloat())
            monView.meters = engine.cfg.meters
            monView.rawAngles = DoubleArray(s.meters.size) { s.meters[it].rawAngle }
            monView.shifts = s.meters.map { Pair(it.shiftX, it.shiftY) }
            monView.requestLayout(); monView.invalidate()
        }
        if (s.running || s.connected) tvStatus.text =
            (if (s.connected) "متصل" else "انقطع البث — لا يُحتسب توقفًا") + " — %.1f إطار/ث".format(Locale.US, s.fps)
        // شاشة المعايرة: خطوط العقرب الحية فوق اللقطة
        calView.rawAngles = DoubleArray(s.meters.size) { s.meters[it].rawAngle }
        tvCal.text = s.meters.joinToString("\n") {
            "%s: زاوية %.1f° | مجموع %.1f° = %.3f برميل | تباين %.3f | إزاحة (%.1f, %.1f)".format(
                Locale.US, it.name, it.rawAngle, it.totalDeg, it.totalDeg / 360.0, it.contrast, it.shiftX, it.shiftY)
        }
    }

    // ================= المعايرة والتحقق =================
    private fun buildCalibration(): View {
        val c = col()
        c.addView(text("1) التقط لقطة من البث الحي أو اختر فيديو.  2) اختر العدّاد، ثم المس منتصف محور العقرب الكبير السفلي، ثم حافة القرص الصغير الذي يدور فيه." +
            "  3) احفظ.  الدائرتان الصفراوان تحددان حلقة البحث عن العقرب، ويجب أن تقعا فوق جسم العقرب لا فوق أرقام القرص."))
        c.addView(row(
            btn("التقاط من البث") {
                if (engine.setStillFromLatest()) refreshCal() else toast("لا يوجد بث حاليًا")
            },
            btn("اختيار فيديو") { pickVideo.launch(arrayOf("video/*")) },
        ))
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { loadVideoFrame(videoDur * (sb?.progress ?: 0) / 1000) }
            })
        }
        c.addView(seek)
        c.addView(row(
            btn("العداد 1") { selected = 0; refreshCal() },
            btn("العداد 2") { selected = 1; refreshCal() },
            btn("تكبير/تصغير") { zoom = !zoom; refreshCal() },
        ))
        c.addView(row(
            btn("لمس = المحور") { tapMode = 0; toast("المس منتصف محور العقرب") },
            btn("لمس = الحافة") { tapMode = 1; toast("المس حافة القرص الصغير") },
            btn("−") { adjustRadius(-1.0) },
            btn("+") { adjustRadius(1.0) },
        ))
        calView = FrameView(this)
        calView.onTap = { fx, fy -> onCalTap(fx, fy) }
        c.addView(calView, LinearLayout.LayoutParams(-1, -2))
        tvCal = text("")
        c.addView(tvCal)
        c.addView(text("التحقق: شغّل الفيديو/البث وراقب «المجموع». كل دورة كاملة للعقرب يجب أن تزيد المجموع 360° (±3°) = برميل واحد."))
        c.addView(row(
            btn("حفظ المعايرة") { saveCalibration() },
            btn("إعدادات الزفات") { swingDialog() },
            btn("فحص الفيديو") { analyzeVideo() },
        ))
        tvReport = text("")
        c.addView(tvReport)
        return scroll(c)
    }

    private fun adjustRadius(d: Double) {
        val m = draft[selected]; draft[selected] = m.copy(radius = (m.radius + d).coerceAtLeast(10.0)); refreshCal()
    }

    private fun onCalTap(fx: Double, fy: Double) {
        val m = draft[selected]
        draft[selected] = if (tapMode == 0) m.copy(cx = fx, cy = fy)
        else m.copy(radius = hypot(fx - m.cx, fy - m.cy).coerceAtLeast(10.0))
        calView.meters = draft
        calView.invalidate()
    }

    private fun refreshCal() {
        calView.selected = selected
        calView.meters = draft
        if (engine.stillWidth == 0) { calView.bitmap = null; calView.invalidate(); return }
        val m = draft[selected]
        val (x0, y0, x1, y1) = if (zoom) {
            val h = m.radius * 2.4
            listOf(m.cx - h, m.cy - h, m.cx + h, m.cy + h)
        } else listOf(0.0, 0.0, engine.stillWidth.toDouble(), engine.stillHeight.toDouble())
        val r = engine.stillBitmap(x0, y0, x1, y1, 1400) ?: return
        calView.bitmap = r.first
        calView.region = r.second
        calView.requestLayout(); calView.invalidate()
    }

    private fun loadVideoFrame(ms: Long) {
        val u = videoUri ?: return
        Thread {
            val f = VideoFileSource.frameAt(this, u, ms)
            runOnUiThread {
                if (f == null) toast("تعذّر قراءة الإطار") else { engine.setStill(f.first, f.second, f.third); refreshCal() }
            }
        }.start()
    }

    private fun saveCalibration() {
        val ok = engine.saveCalibration(engine.cfg.copy(meters = draft.toList()))
        toast(if (ok) "حُفظت المعايرة (مع مرجع تعويض الاهتزاز)" else "التقط لقطة أولًا")
    }

    private fun analyzeVideo() {
        val u = videoUri
        if (u == null) { toast("اختر فيديو أولًا"); return }
        if (engine.store.loadReference(0) == null) toast("تنبيه: لم تُحفظ معايرة بعد؛ سيُستخدم أول إطار كمرجع")
        tvReport.text = "جارٍ التحليل ..."
        engine.analyzeVideo(u, { p -> runOnUiThread { tvReport.text = "جارٍ التحليل $p%" } }) { tvReport.text = it }
    }

    private fun swingDialog() {
        val s = engine.cfg.swing
        val fields = listOf(
            "حركة حقيقية: أقل تقدّم لبدء الزفة (°)" to s.startDeg,
            "نافذة البدء (ث)" to s.startWindowSec,
            "التوقف: تقدّم أقل من (°)" to s.stopDeg,
            "التوقف: خلال (ث)" to s.stopWindowSec,
            "أقل زفة تُسجَّل (°)" to s.minSwingDeg,
            "فارق نهاية العدّادين للزفة الكاملة (ث)" to s.endToleranceSec,
        )
        val box = col()
        val edits = fields.map { (label, v) ->
            box.addView(text(label))
            edit(label, v.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }.also { box.addView(it) }
        }
        AlertDialog.Builder(this).setTitle("إعدادات الزفات").setView(scroll(box))
            .setPositiveButton("حفظ") { _, _ ->
                fun v(i: Int, d: Double) = edits[i].text.toString().toDoubleOrNull() ?: d
                val n = SwingParams(
                    startDeg = v(0, s.startDeg), startWindowSec = v(1, s.startWindowSec),
                    stopDeg = v(2, s.stopDeg), stopWindowSec = v(3, s.stopWindowSec),
                    minSwingDeg = v(4, s.minSwingDeg), endToleranceSec = v(5, s.endToleranceSec),
                    minOverlapSec = s.minOverlapSec, gapSec = s.gapSec,
                )
                engine.store.save(engine.cfg.copy(swing = n)); engine.cfg = engine.store.load()
            }.setNegativeButton("إلغاء", null).show()
    }

    // ================= السجل =================
    private fun buildLog(): View {
        val c = col()
        tvLogTotal = text("").apply { textSize = 20f }
        c.addView(tvLogTotal)
        c.addView(row(
            btn("تصدير CSV") {
                exportCsv.launch("water_log_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv")
            },
            btn("تحديث") { refreshLog() },
            btn("حذف السجل") {
                AlertDialog.Builder(this).setMessage("حذف كل الزفات المسجلة؟")
                    .setPositiveButton("حذف") { _, _ -> engine.db.clear(); refreshLog() }
                    .setNegativeButton("إلغاء", null).show()
            },
        ))
        logBox = col()
        c.addView(logBox)
        return scroll(c)
    }

    private fun refreshLog() {
        if (!::logBox.isInitialized) return
        val today = engine.db.today()
        val total = engine.db.dayTotal(today)
        val txt = "إجمالي براميل اليوم ($today): %.2f".format(Locale.US, total)
        tvLogTotal.text = txt
        tvDay.text = txt
        logBox.removeAllViews()
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (r in engine.db.query(null).take(300)) {
            logBox.addView(text(
                "#${r.seq} (${r.day}) — ${r.typeAr}\n" +
                    "${tf.format(Date(r.startMs))}  →  ${tf.format(Date(r.endMs))}\n" +
                    "العداد 1: %.2f | العداد 2: %.2f | الإجمالي: %.2f برميل".format(Locale.US, r.m1Barrels, r.m2Barrels, r.total) +
                    (if (r.notes.isNotEmpty()) "\n⚠ ${r.notes}" else "")
            ).apply { setPadding(0, 12, 0, 12) })
        }
    }

    // ================= أدوات واجهة =================
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
    private fun scroll(v: View) = ScrollView(this).apply { addView(v, ViewGroup.LayoutParams(-1, -2)) }
    private fun text(s: String) = TextView(this).apply { text = s; textSize = 15f; setPadding(0, 6, 0, 6) }
    private fun edit(hint: String, v: String) = EditText(this).apply { this.hint = hint; setText(v); setSingleLine() }
    private fun btn(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
}
