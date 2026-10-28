package com.smartwater.monitor.camera

/** مصدر إطارات رمادية (قناة Y فقط تكفي لتتبع العقرب). */
interface FrameSource {
    interface Listener {
        /** gray: width*height بايت. قد يعاد استخدام المصفوفة بعد عودة الدالة، فانسخها إن احتجت. */
        fun onFrame(gray: ByteArray, width: Int, height: Int, tSec: Double)
        fun onStatus(message: String)
        /** انقطع البث. يُعامَل كفجوة وليس كتوقف عدّاد. */
        fun onDisconnected()
    }

    fun start(listener: Listener)
    fun stop()
}
