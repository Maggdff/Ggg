package com.duoprog.app.data

import android.content.Context
import com.duoprog.app.data.model.CurriculumUnit
import com.google.gson.Gson

object CurriculumLoader {
    private var cache: List<CurriculumUnit>? = null
    private val fileNames = listOf(
        "unit_0.json", "unit_1.json", "unit_2.json",
        "unit_3.json", "unit_4.json", "unit_5.json"
    )

    fun load(context: Context): List<CurriculumUnit> {
        cache?.let { return it }
        val gson = Gson()
        val units = fileNames.mapNotNull { name ->
            try {
                val text = context.assets.open("curriculum/$name")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                gson.fromJson(text, CurriculumUnit::class.java)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.order }
        cache = units
        return units
    }
}
