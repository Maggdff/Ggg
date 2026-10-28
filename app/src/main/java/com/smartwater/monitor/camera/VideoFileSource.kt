package com.smartwater.monitor.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * يقرأ فيديو محليًا إطارًا بإطار (للاختبار والتحقق من الدقة دون كاميرا).
 * الزمن = زمن عرض الإطار داخل الفيديو (PTS)، فيكون الحساب مستقلاً عن سرعة المعالجة.
 */
class VideoFileSource(
    private val ctx: Context,
    private val uri: Uri,
    private val stepMs: Long = 100,       // 10 إطار/ث كافٍ لعقرب بسرعة ~50°/ث
    private val realtime: Boolean = false,
) : FrameSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(listener: FrameSource.Listener) {
        running = true
        thread = Thread({
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durMs <= 0) { listener.onStatus("تعذّر قراءة مدة الفيديو"); return@Thread }
                val t0 = SystemClock.elapsedRealtime()
                var ms = 0L
                while (running && ms <= durMs) {
                    val bmp = mmr.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp != null) {
                        val (g, w, h) = toGray(bmp)
                        bmp.recycle()
                        listener.onFrame(g, w, h, ms / 1000.0)
                    }
                    ms += stepMs
                    if (realtime) {
                        val wait = ms - (SystemClock.elapsedRealtime() - t0)
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
            } catch (e: Exception) {
                listener.onStatus("خطأ قراءة الفيديو: ${e.message}")
            } finally {
                mmr.release()
                running = false
                listener.onFinished()
            }
        }, "video-source").also { it.start() }
    }

    override fun stop() { running = false }

    companion object {
        fun toGray(bmp: Bitmap): Triple<ByteArray, Int, Int> {
            val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            val rgba = Mat()
            val gray = Mat()
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val out = ByteArray(gray.rows() * gray.cols())
            gray.get(0, 0, out)
            val r = Triple(out, gray.cols(), gray.rows())
            rgba.release(); gray.release()
            return r
        }

        /** إطار واحد عند زمن محدد (للمعايرة). */
        fun frameAt(ctx: Context, uri: Uri, ms: Long): Triple<ByteArray, Int, Int>? {
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(ctx, uri)
                val bmp = mmr.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
                val r = toGray(bmp); bmp.recycle(); r
            } catch (e: Exception) { null } finally { mmr.release() }
        }

        fun durationMs(ctx: Context, uri: Uri): Long {
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(ctx, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) { 0L } finally { mmr.release() }
        }
    }
}
