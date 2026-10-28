package com.smartwater.monitor.core

import kotlin.math.floor

/**
 * إطار رمادي (8 بت). يمكن أن يكون الإطار كاملاً أو نافذة ROI؛ الإحداثيات المطلقة
 * (في إطار الكاميرا الكامل) تُحوَّل داخليًا بطرح (originX, originY).
 * النواة كلها بلا اعتماد على أندرويد كي تُختبر على JVM.
 */
class GrayFrame(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val originX: Int = 0,
    val originY: Int = 0,
) {
    init { require(data.size >= width * height) { "حجم البيانات لا يطابق الأبعاد" } }

    /** قيمة بكسل صحيح بإحداثيات مطلقة، أو -1 خارج الإطار. */
    fun pixel(x: Int, y: Int): Int {
        val lx = x - originX
        val ly = y - originY
        if (lx < 0 || ly < 0 || lx >= width || ly >= height) return -1
        return data[ly * width + lx].toInt() and 0xFF
    }

    /** عيّنة ثنائية الخطية بإحداثيات مطلقة، NaN خارج الإطار. */
    fun sample(x: Double, y: Double): Double {
        val lx = x - originX
        val ly = y - originY
        val x0 = floor(lx).toInt()
        val y0 = floor(ly).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= width || y0 + 1 >= height) return Double.NaN
        val fx = lx - x0
        val fy = ly - y0
        val i = y0 * width + x0
        val a = data[i].toInt() and 0xFF
        val b = data[i + 1].toInt() and 0xFF
        val c = data[i + width].toInt() and 0xFF
        val d = data[i + width + 1].toInt() and 0xFF
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}
