package com.smartwater.monitor.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * مصدر RTSP عبر libVLC (نفس المشغّل الذي اشتغل مع كاميرا Hikvision في مشروع RtspPersonTracker،
 * ويتحمّل H.265 بلا fmtp). الإطارات تُلتقط من TextureView بالدقة الأصلية للبث (فتبقى إحداثيات المعايرة صحيحة)
 * ثم تُحوَّل إلى رمادي وتُسلَّم للمحرك. يجب استدعاء start/stop من الخيط الرئيسي.
 *
 * [host]: حاوية تُضاف إليها شاشة العرض (معاينة مصغّرة). يجب أن تبقى ظاهرة أثناء التشغيل وإلا يتوقف تحديث الإطارات.
 */
class RtspSource(
    private val ctx: Context,
    private val url: String,
    private val forceTcp: Boolean,
    private val host: FrameLayout,
    private val grabIntervalMs: Long = 125,      // ~8 إطار/ث
) : FrameSource {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var listener: FrameSource.Listener? = null

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var tv: TextureView? = null

    @Volatile private var running = false
    private var grabbingStarted = false
    private var reconnectPending = false
    private var playing = false
    private var attempt = 0
    private var nativeW = 0
    private var nativeH = 0
    private var bmp: Bitmap? = null
    private val grabbing = AtomicBoolean(false)
    private var lastDisplayed = 0
    private var lastProgressMs = 0L
    private var statusSince = 0L
    private var gen = 0                              // يُبطل أحداث الجلسات القديمة

    private val layoutListener = IVLCVout.OnNewVideoLayoutListener { _, _, _, visW, visH, _, _ ->
        if (visW > 0 && visH > 0) main.post { onVideoSize(visW, visH) }
    }

    override fun start(listener: FrameSource.Listener) {
        this.listener = listener
        running = true
        val lib = LibVLC(ctx, arrayListOf("--no-audio"))
        libVlc = lib
        val p = MediaPlayer(lib)
        player = p
        val myGen = ++gen
        p.setEventListener(MediaPlayer.EventListener { ev ->
            val type = ev.type; val vout = ev.voutCount
            main.post { if (myGen == gen) onEvent(type, vout) }
        })
        val t = TextureView(ctx)
        tv = t
        host.removeAllViews()
        host.addView(t, FrameLayout.LayoutParams(1280, 720))
        p.vlcVout.setVideoView(t)
        p.vlcVout.attachViews(layoutListener)
        p.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
        p.vlcVout.setWindowSize(1280, 720)
        connect()
        main.postDelayed(tick, 1000)
    }

    private fun maskedUrl() = url.replace(Regex("(?<=://)[^@/]*@"), "***@")

    private fun connect() {
        val p = player ?: return
        playing = false
        lastDisplayed = 0
        lastProgressMs = SystemClock.elapsedRealtime()
        setStatusTime()
        // تدوير الاستراتيجية مع كل إخفاق: (النقل المختار + عتادي) ثم (برمجي) ثم النقل الآخر
        val idx = attempt % 4
        val tcp = if (idx < 2) forceTcp else !forceTcp
        val hw = idx % 2 == 0
        val mode = (if (tcp) "TCP" else "UDP") + (if (hw) " · عتادي" else " · برمجي")
        listener?.onStatus((if (attempt > 0) "إعادة المحاولة #$attempt ($mode) " else "جارٍ الاتصال ($mode) ") + maskedUrl())
        try { p.stop() } catch (_: Exception) {}
        val lib = libVlc ?: return
        val media = Media(lib, Uri.parse(url))
        media.setHWDecoderEnabled(hw, false)
        media.addOption(":network-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        media.addOption(":no-audio")
        if (tcp) media.addOption(":rtsp-tcp")
        p.media = media
        media.release()
        p.play()
    }

    private fun setStatusTime() { statusSince = SystemClock.elapsedRealtime() }

    private fun onEvent(type: Int, voutCount: Int) {
        when (type) {
            MediaPlayer.Event.Vout -> if (voutCount > 0 && !playing) {
                playing = true; attempt = 0
                if (!grabbingStarted) { grabbingStarted = true; main.post(grabber) }
                lastProgressMs = SystemClock.elapsedRealtime()
                listener?.onStatus("البث يعمل — جارٍ التقاط الإطارات")
            }
            MediaPlayer.Event.EncounteredError -> scheduleReconnect("تعذّر تشغيل البث — تحقق من العنوان وكلمة المرور والمنفذ 554")
            MediaPlayer.Event.EndReached -> scheduleReconnect("انتهى البث")
            else -> Unit
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!running || reconnectPending) return
        reconnectPending = true
        playing = false
        try { player?.stop() } catch (_: Exception) {}
        attempt++
        val delayMs = min(1000L shl min(attempt - 1, 4), 10_000L)
        listener?.onStatus("$reason — إعادة المحاولة خلال ${delayMs / 1000} ث (محاولة $attempt)")
        listener?.onDisconnected()
        val myGen = gen
        main.postDelayed({
            reconnectPending = false
            if (running && myGen == gen) connect()
        }, delayMs)
    }

    /** يضبط حجم شاشة العرض على الدقة الأصلية (للالتقاط) ثم يصغّرها بصريًا لتناسب الحاوية. */
    private fun onVideoSize(w: Int, h: Int) {
        val t = tv ?: return
        if (w == nativeW && h == nativeH) return
        nativeW = w; nativeH = h
        bmp?.recycle(); bmp = null
        t.layoutParams = FrameLayout.LayoutParams(w, h)
        t.pivotX = 0f; t.pivotY = 0f
        val hw = host.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
        val hh = host.height.takeIf { it > 0 } ?: (hw / 3)
        val s = min(hw.toFloat() / w, hh.toFloat() / h)
        t.scaleX = s; t.scaleY = s
        t.translationX = (hw - w * s) / 2f
        t.translationY = (hh - h * s) / 2f
        player?.vlcVout?.setWindowSize(w, h)
        listener?.onStatus("دقة البث ${w}×${h}")
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            watchdog()
            main.postDelayed(this, 1000)
        }
    }

    private val grabber: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            try { grab() } catch (e: Throwable) { listener?.onStatus("خطأ التقاط: ${e.message}") }
            main.postDelayed(this, grabIntervalMs)
        }
    }

    private fun grab() {
        val t = tv ?: return
        if (!playing || nativeW <= 0 || !t.isAvailable) return
        if (!grabbing.compareAndSet(false, true)) return    // المعالجة السابقة لم تنته: أسقط الإطار
        val b = bmp ?: Bitmap.createBitmap(nativeW, nativeH, Bitmap.Config.ARGB_8888).also { bmp = it }
        val tSec = SystemClock.elapsedRealtimeNanos() / 1e9
        if (t.getBitmap(b) == null) { grabbing.set(false); return }
        worker.execute {
            try {
                val rgba = Mat(); val gray = Mat()
                Utils.bitmapToMat(b, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                val out = ByteArray(gray.rows() * gray.cols())
                gray.get(0, 0, out)
                val w = gray.cols(); val h = gray.rows()
                rgba.release(); gray.release()
                grabbing.set(false)
                listener?.onFrame(out, w, h, tSec)
            } catch (e: Throwable) {
                grabbing.set(false)
                listener?.onStatus("خطأ تحويل الإطار: ${e.message}")
            }
        }
    }

    /** كل ثانية: يكشف التجمّد (لا صور معروضة جديدة) ومهلة الاتصال. */
    private fun watchdog() {
        val now = SystemClock.elapsedRealtime()
        val p = player ?: return
        val m = p.media
        if (m != null) {
            try {
                val d = m.stats?.displayedPictures ?: lastDisplayed
                if (d > lastDisplayed) { lastDisplayed = d; lastProgressMs = now
                    if (!grabbingStarted) { grabbingStarted = true; main.post(grabber) } }
            } finally { m.release() }
        }
        if (reconnectPending) return
        if (playing) { if (now - lastProgressMs > 6000) scheduleReconnect("توقّف وصول الإطارات") }
        else if (now - statusSince > 15000) scheduleReconnect("انتهت مهلة الاتصال")
    }

    override fun stop() {
        running = false
        gen++
        grabbingStarted = false
        main.removeCallbacks(tick); main.removeCallbacks(grabber)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.vlcVout?.let { if (it.areViewsAttached()) it.detachViews() } } catch (_: Exception) {}
        host.removeAllViews()
        try { player?.setEventListener(null); player?.release(); libVlc?.release() } catch (_: Exception) {}
        player = null; libVlc = null; tv = null
        worker.execute { bmp?.recycle(); bmp = null }
        worker.shutdown()
    }
}
