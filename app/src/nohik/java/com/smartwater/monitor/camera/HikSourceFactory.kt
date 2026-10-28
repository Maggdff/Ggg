package com.smartwater.monitor.camera

import android.content.Context
import android.view.SurfaceHolder

/** نسخة بدون Hikvision SDK: تُبنى حين لا توجد app/libs/HCNetSDK.jar. تعلن صراحةً أن الاتصال غير متاح. */
object HikSourceFactory {
    const val AVAILABLE = false

    fun create(
        ctx: Context, ip: String, user: String, pass: String,
        channel: Int, streamType: Int, realChannelOverride: Int, holder: SurfaceHolder?,
    ): FrameSource = object : FrameSource {
        override fun start(listener: FrameSource.Listener) {
            listener.onStatus("حزمة Hikvision SDK غير مضمّنة في هذا البناء. ضع HCNetSDK.jar و PlayerSDK.jar و ملفات .so ثم أعد البناء (انظر README).")
            listener.onDisconnected()
        }
        override fun stop() {}
    }
}
