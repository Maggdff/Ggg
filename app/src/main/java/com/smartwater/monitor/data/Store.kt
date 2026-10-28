package com.smartwater.monitor.data

import android.content.Context
import com.smartwater.monitor.core.GrayFrame
import com.smartwater.monitor.core.MeterConfig
import com.smartwater.monitor.core.SwingParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class AppConfig(
    val ip: String = "192.168.1.100",
    val user: String = "admin",
    val pass: String = "",
    val channel: Int = 4,
    val streamType: Int = 0,        // 0 رئيسي (دقة عالية، يلزم لقراءة عقرب صغير)، 1 فرعي
    val meters: List<MeterConfig> = listOf(
        // قيم افتراضية مأخوذة من فيديو الاختبار 2560×1944؛ عايرها لكاميرتك من شاشة المعايرة.
        MeterConfig("العداد 1", 505.0, 1232.0, 60.0),
        MeterConfig("العداد 2", 1887.0, 1337.0, 62.0),
    ),
    val swing: SwingParams = SwingParams(),
    val rtspPort: Int = 554,
    val rtspUrl: String = "",       // اختياري: عنوان RTSP كامل يتجاوز التركيب التلقائي
) {
    /** عنوان RTSP لكاميرا Hikvision: /Streaming/Channels/<القناة><0=رئيسي1/2=فرعي>، مثال القناة 4 رئيسي = 401. */
    fun buildRtspUrl(): String {
        if (rtspUrl.isNotBlank()) return rtspUrl.trim()
        val auth = if (user.isNotEmpty()) android.net.Uri.encode(user) + ":" + android.net.Uri.encode(pass) + "@" else ""
        return "rtsp://$auth$ip:$rtspPort/Streaming/Channels/${channel}0${streamType + 1}"
    }
}

/** إعدادات التطبيق في SharedPreferences (ملاحظة: كلمة المرور تُخزَّن محليًا بلا تشفير). */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("smart_water", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        val s = prefs.getString("config", null) ?: return AppConfig()
        return try {
            val j = JSONObject(s)
            val d = AppConfig()
            val ms = j.optJSONArray("meters")
            val meters = if (ms != null && ms.length() == 2) List(2) {
                val m = ms.getJSONObject(it)
                MeterConfig(m.getString("name"), m.getDouble("cx"), m.getDouble("cy"), m.getDouble("r"))
            } else d.meters
            val sw = j.optJSONObject("swing")
            val sd = SwingParams()
            val swing = if (sw == null) sd else SwingParams(
                startDeg = sw.optDouble("startDeg", sd.startDeg),
                startWindowSec = sw.optDouble("startWindowSec", sd.startWindowSec),
                stopDeg = sw.optDouble("stopDeg", sd.stopDeg),
                stopWindowSec = sw.optDouble("stopWindowSec", sd.stopWindowSec),
                minSwingDeg = sw.optDouble("minSwingDeg", sd.minSwingDeg),
                endToleranceSec = sw.optDouble("endToleranceSec", sd.endToleranceSec),
                minOverlapSec = sw.optDouble("minOverlapSec", sd.minOverlapSec),
                gapSec = sw.optDouble("gapSec", sd.gapSec),
            )
            AppConfig(
                j.optString("ip", d.ip), j.optString("user", d.user), j.optString("pass", d.pass),
                j.optInt("channel", d.channel), j.optInt("streamType", d.streamType),
                meters, swing,
                j.optInt("rtspPort", d.rtspPort), j.optString("rtspUrl", d.rtspUrl),
            )
        } catch (e: Exception) { AppConfig() }
    }

    fun save(c: AppConfig) {
        val j = JSONObject()
        j.put("ip", c.ip).put("user", c.user).put("pass", c.pass)
        j.put("channel", c.channel).put("streamType", c.streamType)
        j.put("rtspPort", c.rtspPort).put("rtspUrl", c.rtspUrl)
        val ms = JSONArray()
        c.meters.forEach { ms.put(JSONObject().put("name", it.name).put("cx", it.cx).put("cy", it.cy).put("r", it.radius)) }
        j.put("meters", ms)
        val s = c.swing
        j.put("swing", JSONObject()
            .put("startDeg", s.startDeg).put("startWindowSec", s.startWindowSec)
            .put("stopDeg", s.stopDeg).put("stopWindowSec", s.stopWindowSec)
            .put("minSwingDeg", s.minSwingDeg).put("endToleranceSec", s.endToleranceSec)
            .put("minOverlapSec", s.minOverlapSec).put("gapSec", s.gapSec))
        prefs.edit().putString("config", j.toString()).apply()
    }

    /** مرجع المعايرة: نافذة ROI رمادية من لحظة المعايرة، تُستخدم لتعويض الاهتزاز. */
    fun saveReference(i: Int, f: GrayFrame) {
        DataOutputStream(File(ctx.filesDir, "ref_$i.bin").outputStream().buffered()).use {
            it.writeInt(f.width); it.writeInt(f.height); it.writeInt(f.originX); it.writeInt(f.originY)
            it.write(f.data, 0, f.width * f.height)
        }
    }

    fun loadReference(i: Int): GrayFrame? {
        val file = File(ctx.filesDir, "ref_$i.bin")
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use {
                val w = it.readInt(); val h = it.readInt(); val ox = it.readInt(); val oy = it.readInt()
                val d = ByteArray(w * h); it.readFully(d)
                GrayFrame(w, h, d, ox, oy)
            }
        } catch (e: Exception) { null }
    }

    fun deleteReferences() { for (i in 0..1) File(ctx.filesDir, "ref_$i.bin").delete() }
}
