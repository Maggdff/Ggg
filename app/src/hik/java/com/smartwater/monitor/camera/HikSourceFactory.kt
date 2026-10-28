package com.smartwater.monitor.camera

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceHolder
import com.hikvision.netsdk.ExceptionCallBack
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_DEVICEINFO_V30
import com.hikvision.netsdk.NET_DVR_PREVIEWINFO
import com.hikvision.netsdk.RealPlayCallBack
import org.MediaPlayer.PlayM4.Player
import org.MediaPlayer.PlayM4.PlayerCallBack

object HikSourceFactory {
    const val AVAILABLE = true

    fun create(
        ctx: Context, ip: String, user: String, pass: String,
        channel: Int, streamType: Int, realChannelOverride: Int, holder: SurfaceHolder?,
    ): FrameSource = HikvisionSource(ip, user, pass, channel, streamType, realChannelOverride, holder)
}

/**
 * اتصال Hikvision SDK (HCNetSDK) على المنفذ 8000 وبث القناة المطلوبة، وفك الترميز بـ PlayM4 مع
 * استدعاء العرض (YV12) لأخذ قناة Y.
 *
 * تنبيه: كُتب هذا الصنف وفق واجهة SDK (HCNetSDK/PlayM4 للأندرويد) كما في العيّنة الرسمية، لكنه لم يُجرَّب
 * مع كاميرا حقيقية ولا مع الـ SDK في بيئة التطوير. راجع أي اختلاف في أسماء الدوال بحسب إصدار SDK لديك.
 */
class HikvisionSource(
    private val ip: String,
    private val user: String,
    private val pass: String,
    private val channel: Int,
    private val streamType: Int,
    private val realChannelOverride: Int,
    private val holder: SurfaceHolder?,
) : FrameSource {
    private val sdk get() = HCNetSDK.getInstance()
    private val player get() = Player.getInstance()

    @Volatile private var running = false
    private var userId = -1
    private var playId = -1
    private var port = -1
    private var listener: FrameSource.Listener? = null
    @Volatile private var lastFrameMs = 0L
    private var watchdog: Thread? = null
    private var yBuf = ByteArray(0)

    override fun start(listener: FrameSource.Listener) {
        this.listener = listener
        running = true
        Thread({ connect(listener) }, "hik-connect").start()
    }

    private fun connect(l: FrameSource.Listener) {
        if (!sdk.NET_DVR_Init()) { l.onStatus("فشل NET_DVR_Init"); l.onDisconnected(); return }
        sdk.NET_DVR_SetConnectTime(5000, 3)
        sdk.NET_DVR_SetReconnect(10000, true)
        val info = NET_DVR_DEVICEINFO_V30()
        userId = sdk.NET_DVR_Login_V30(ip, 8000, user, pass, info)
        if (userId < 0) {
            l.onStatus("فشل تسجيل الدخول إلى $ip:8000 — رمز الخطأ ${sdk.NET_DVR_GetLastError()}")
            l.onDisconnected(); return
        }
        // رقم القناة: تناظرية = byStartChan + n - 1، وإلا قنوات IP = byStartDChan + n - 1
        val real = when {
            realChannelOverride > 0 -> realChannelOverride
            info.byChanNum.toInt() >= channel -> info.byStartChan.toInt() + channel - 1
            else -> info.byStartDChan.toInt() + channel - 1
        }
        l.onStatus("تم الدخول (قنوات تناظرية=${info.byChanNum}, IP=${info.byIPChanNum}). فتح القناة الفعلية $real ...")
        sdk.NET_DVR_SetExceptionCallBack(ExceptionCallBack { type, _, _ ->
            l.onStatus("استثناء SDK رقم $type")
            l.onDisconnected()
        })

        val pi = NET_DVR_PREVIEWINFO()
        pi.lChannel = real
        pi.dwStreamType = streamType   // 0 رئيسي، 1 فرعي
        pi.dwLinkMode = 0              // TCP
        pi.bBlocked = 1
        val cb = RealPlayCallBack { _, dataType, data, size -> onRealData(dataType, data, size) }
        playId = sdk.NET_DVR_RealPlay_V40(userId, pi, cb)
        if (playId < 0) {
            l.onStatus("فشل بدء البث — رمز الخطأ ${sdk.NET_DVR_GetLastError()}")
            l.onDisconnected(); return
        }
        lastFrameMs = SystemClock.elapsedRealtime()
        watchdog = Thread({
            var alerted = false
            while (running) {
                Thread.sleep(1000)
                val silent = SystemClock.elapsedRealtime() - lastFrameMs > 3000
                if (silent && !alerted) { alerted = true; l.onStatus("لا إطارات منذ 3 ث"); l.onDisconnected() }
                if (!silent) alerted = false
            }
        }, "hik-watchdog").also { it.start() }
    }

    private fun onRealData(dataType: Int, data: ByteArray, size: Int) {
        when (dataType) {
            1 -> { // رأس النظام (NET_DVR_SYSHEAD)
                if (port != -1) return
                port = player.getPort()
                if (port == -1) { listener?.onStatus("PlayM4: لا منفذ متاح"); return }
                player.setStreamOpenMode(port, Player.STREAM_REALTIME)
                if (!player.openStream(port, data, size, 4 * 1024 * 1024)) {
                    listener?.onStatus("PlayM4.openStream فشل: ${player.getLastError(port)}"); return
                }
                player.setDisplayCB(port, PlayerCallBack.PlayerDisplayCB { _, buf, _, w, h, _, _, _ ->
                    // YV12: أول w*h بايت هي قناة Y (سطوع)
                    val n = w * h
                    if (yBuf.size != n) yBuf = ByteArray(n)
                    System.arraycopy(buf, 0, yBuf, 0, n)
                    lastFrameMs = SystemClock.elapsedRealtime()
                    listener?.onFrame(yBuf, w, h, SystemClock.elapsedRealtimeNanos() / 1e9)
                })
                if (!player.play(port, holder)) listener?.onStatus("PlayM4.play فشل: ${player.getLastError(port)}")
            }
            2 -> if (port != -1) player.inputData(port, data, size)
        }
    }

    override fun stop() {
        running = false
        try {
            if (playId >= 0) sdk.NET_DVR_StopRealPlay(playId)
            if (port != -1) { player.stop(port); player.closeStream(port); player.freePort(port) }
            if (userId >= 0) sdk.NET_DVR_Logout_V30(userId)
            sdk.NET_DVR_Cleanup()
        } catch (_: Throwable) {}
        playId = -1; port = -1; userId = -1
    }
}
