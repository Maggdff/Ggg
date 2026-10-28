package com.smartwater.monitor.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.view.SurfaceHolder
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * ISAPI snapshot polling source. Does not use RTSP or the licensed Hikvision SDK.
 * The camera must have HTTP/ISAPI enabled and allow the supplied account to read snapshots.
 */
object HikSourceFactory {
    const val AVAILABLE = true

    fun create(
        ctx: Context, ip: String, user: String, pass: String,
        channel: Int, streamType: Int, realChannelOverride: Int, holder: SurfaceHolder?,
    ): FrameSource = IsapiSnapshotSource(ip, user, pass, channel, streamType, realChannelOverride)
}

private class IsapiSnapshotSource(
    private val hostInput: String,
    private val username: String,
    private val password: String,
    private val channel: Int,
    private val streamType: Int,
    private val channelOverride: Int,
) : FrameSource {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var lastStatus = ""
    private var consecutiveErrors = 0
    private var disconnectedNotified = false

    override fun start(listener: FrameSource.Listener) {
        if (running) return
        running = true
        worker = Thread({
            try {
                val base = normalizeBase(hostInput)
                val ch = when {
                    channelOverride > 0 -> channelOverride
                    channel in 1..99 -> channel * 100 + if (streamType == 1) 2 else 1
                    else -> channel
                }
                val endpoint = "$base/ISAPI/Streaming/channels/$ch/picture"
                status(listener, "الاتصال عبر HTTP/ISAPI: $endpoint")
                var lastFrameAt = 0L
                while (running) {
                    val started = SystemClock.elapsedRealtime()
                    try {
                        val bytes = fetchDigest(endpoint)
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            ?: throw IllegalStateException("استجابة HTTP ليست صورة JPEG")
                        val gray = toGray(bitmap)
                        val w = bitmap.width
                        val h = bitmap.height
                        bitmap.recycle()
                        listener.onFrame(gray, w, h, SystemClock.elapsedRealtimeNanos() / 1e9)
                        lastFrameAt = SystemClock.elapsedRealtime()
                        consecutiveErrors = 0
                        disconnectedNotified = false
                        status(listener, "متصل عبر HTTP/ISAPI — القناة $ch — لقطات متتابعة")
                    } catch (e: Exception) {
                        consecutiveErrors++
                        val detail = e.message ?: e.javaClass.simpleName
                        val shouldShowError = consecutiveErrors == 1 || consecutiveErrors % 5 == 0
                        if (lastFrameAt == 0L || SystemClock.elapsedRealtime() - lastFrameAt > 3000) {
                            if (!disconnectedNotified) {
                                disconnectedNotified = true
                                listener.onDisconnected()
                            }
                            // أظهر السبب الحقيقي بعد رسالة الانقطاع العامة حتى لا تختفي تفاصيل الخطأ.
                            if (shouldShowError) {
                                status(listener, "فشل جلب لقطة ISAPI ($consecutiveErrors): $detail. تحقق من عنوان الجهاز والمنفذ 80 وصلاحيات HTTP/ISAPI.")
                            }
                        } else if (shouldShowError) {
                            status(listener, "تعثر مؤقت في جلب لقطة ISAPI ($consecutiveErrors): $detail")
                        }
                        Thread.sleep(if (consecutiveErrors < 3) 700L else 1500L)
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed < 350) Thread.sleep(350 - elapsed)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                status(listener, "تعذّر بدء اتصال ISAPI: ${e.message}")
                listener.onDisconnected()
            }
        }, "isapi-snapshot-poll").also { it.start() }
    }

    private fun normalizeBase(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isBlank()) throw IllegalArgumentException("أدخل عنوان IP الجهاز")
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        // Accept host:port; default to HTTP port 80. HTTPS can be entered explicitly.
        return s
    }

    private fun fetchDigest(endpoint: String): ByteArray {
        var conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2500
        conn.readTimeout = 2500
        conn.setRequestProperty("Accept", "image/jpeg,*/*")
        conn.connect()
        val code = conn.responseCode
        if (code in 200..299) {
            val data = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (data.isEmpty()) throw IllegalStateException("الصورة فارغة")
            return data
        }
        val challenge = conn.getHeaderField("WWW-Authenticate")
        val errorText = try { conn.errorStream?.bufferedReader()?.use { it.readText().take(180) } ?: "" } catch (_: Exception) { "" }
        conn.disconnect()
        if (code == 401 && !challenge.isNullOrBlank()) {
            // Hikvision firmware can advertise Basic, Digest, or both.
            val auth = if (hasDigestChallenge(challenge)) {
                digestAuthorization(challenge.substringAfter("Digest", ""), endpoint)
            } else if (challenge.contains("Basic", ignoreCase = true)) {
                val token = Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.ISO_8859_1),
                    Base64.NO_WRAP
                )
                "Basic $token"
            } else {
                throw IllegalStateException("نوع المصادقة غير مدعوم: ${challenge.take(180)}")
            }
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "image/jpeg,*/*")
            conn.setRequestProperty("Authorization", auth)
            conn.connect()
            val retryCode = conn.responseCode
            if (retryCode in 200..299) {
                val data = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (data.isEmpty()) throw IllegalStateException("الصورة فارغة")
                return data
            }
            val retryChallenge = conn.getHeaderField("WWW-Authenticate") ?: ""
            val retryText = try { conn.errorStream?.bufferedReader()?.use { it.readText().take(180) } ?: "" } catch (_: Exception) { "" }
            conn.disconnect()
            throw IllegalStateException("HTTP $retryCode بعد المصادقة ${if (auth.startsWith("Basic ")) "Basic" else "Digest"} — تحقق من الصلاحيات أو القناة. ${retryChallenge.take(100)} $retryText")
        }
        throw IllegalStateException("HTTP $code ${if (errorText.isNotBlank()) "— $errorText" else ""}")
    }

    private fun hasDigestChallenge(challenge: String): Boolean {
        return Regex("""(?i)Digest\\s+[^,]*realm\\s*=\\s*["'][^"']+["']""").containsMatchIn(challenge) &&
            Regex("""(?i)nonce\\s*=\\s*["'][^"']+["']""").containsMatchIn(challenge)
    }

    private fun digestAuthorization(challenge: String, endpoint: String): String {
        val params = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
            .findAll(challenge)
            .associate { it.groupValues[1].lowercase(Locale.US) to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        val realm = params["realm"] ?: throw IllegalStateException("تحدي Digest بلا realm")
        val nonce = params["nonce"] ?: throw IllegalStateException("تحدي Digest بلا nonce")
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
        val algorithm = params["algorithm"] ?: "MD5"
        if (!algorithm.equals("MD5", true)) throw IllegalStateException("خوارزمية Digest غير مدعومة: $algorithm")
        val uri = URL(endpoint).let { it.path.ifEmpty { "/" } + (it.query?.let { q -> "?$q" } ?: "") }
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val nc = "00000001"
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("GET:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"").append(username.replace("\"", "")).append("\", ")
            append("realm=\"").append(realm).append("\", nonce=\"").append(nonce).append("\", ")
            append("uri=\"").append(uri).append("\", response=\"").append(response).append("\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            params["opaque"]?.let { append(", opaque=\"").append(it).append("\"") }
        }
    }

    private fun md5(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

    private fun toGray(bitmap: Bitmap): ByteArray {
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            ByteArray(gray.rows() * gray.cols()).also { gray.get(0, 0, it) }
        } finally {
            rgba.release()
            gray.release()
        }
    }

    private fun status(listener: FrameSource.Listener, msg: String) {
        if (msg != lastStatus) {
            lastStatus = msg
            listener.onStatus(msg)
        }
    }

    override fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
