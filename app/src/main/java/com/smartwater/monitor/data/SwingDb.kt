package com.smartwater.monitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartwater.monitor.core.SwingResult
import com.smartwater.monitor.core.SwingType
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SwingRecord(
    val id: Long, val day: String, val seq: Int, val full: Boolean, val meters: String,
    val startMs: Long, val endMs: Long, val m1Barrels: Double, val m2Barrels: Double,
    val total: Double, val notes: String,
) {
    val typeAr: String get() = if (full) "زفة كاملة" else "زفة عداد"
}

/** سجل الزفات: رقم الزفة (يومي) ونوعها ووقتها وكمية البراميل. */
class SwingDb(ctx: Context) : SQLiteOpenHelper(ctx, "swings.db", null, 1) {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE swings(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, seq INTEGER NOT NULL," +
                "full INTEGER NOT NULL, meters TEXT NOT NULL, start_ms INTEGER, end_ms INTEGER," +
                "m1 REAL, m2 REAL, total REAL, notes TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    @Synchronized
    fun insert(r: SwingResult, startMs: Long, endMs: Long): SwingRecord {
        val day = dayFmt.format(Date(endMs))
        val db = writableDatabase
        val seq = db.rawQuery("SELECT COUNT(*) FROM swings WHERE day=?", arrayOf(day)).use { it.moveToFirst(); it.getInt(0) } + 1
        val m1 = r.parts.firstOrNull { it.meter == 0 }?.barrels ?: 0.0
        val m2 = r.parts.firstOrNull { it.meter == 1 }?.barrels ?: 0.0
        val meters = r.parts.joinToString("+") { (it.meter + 1).toString() }
        val cv = ContentValues().apply {
            put("day", day); put("seq", seq); put("full", if (r.type == SwingType.FULL) 1 else 0)
            put("meters", meters); put("start_ms", startMs); put("end_ms", endMs)
            put("m1", m1); put("m2", m2); put("total", r.totalBarrels); put("notes", r.notes)
        }
        val id = db.insert("swings", null, cv)
        return SwingRecord(id, day, seq, r.type == SwingType.FULL, meters, startMs, endMs, m1, m2, r.totalBarrels, r.notes)
    }

    private fun read(c: android.database.Cursor) = SwingRecord(
        c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) == 1, c.getString(4),
        c.getLong(5), c.getLong(6), c.getDouble(7), c.getDouble(8), c.getDouble(9), c.getString(10) ?: "",
    )

    @Synchronized
    fun query(day: String?): List<SwingRecord> {
        val out = ArrayList<SwingRecord>()
        val cols = "id,day,seq,full,meters,start_ms,end_ms,m1,m2,total,notes"
        val c = if (day == null) readableDatabase.rawQuery("SELECT $cols FROM swings ORDER BY id DESC", null)
        else readableDatabase.rawQuery("SELECT $cols FROM swings WHERE day=? ORDER BY id DESC", arrayOf(day))
        c.use { while (it.moveToNext()) out.add(read(it)) }
        return out
    }

    fun today(): String = dayFmt.format(Date())

    @Synchronized
    fun dayTotal(day: String): Double =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(total),0) FROM swings WHERE day=?", arrayOf(day)).use { it.moveToFirst(); it.getDouble(0) }

    @Synchronized
    fun clear() { writableDatabase.execSQL("DELETE FROM swings") }

    /** CSV بترميز UTF-8 مع BOM ليفتح العربي صحيحًا في Excel. */
    fun exportCsv(out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("\uFEFF")
        w.write("رقم الزفة (يومي),التاريخ,نوع الزفة,العدّادات,وقت البدء,وقت الانتهاء,المدة (ث),براميل العداد 1,براميل العداد 2,إجمالي البراميل,ملاحظات\n")
        for (r in query(null).reversed()) {
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            w.write(listOf(
                r.seq.toString(), r.day, q(r.typeAr), r.meters,
                timeFmt.format(Date(r.startMs)), timeFmt.format(Date(r.endMs)),
                ((r.endMs - r.startMs) / 1000).toString(),
                "%.3f".format(Locale.US, r.m1Barrels), "%.3f".format(Locale.US, r.m2Barrels),
                "%.3f".format(Locale.US, r.total), q(r.notes),
            ).joinToString(",") + "\n")
        }
        w.flush()
    }
}
