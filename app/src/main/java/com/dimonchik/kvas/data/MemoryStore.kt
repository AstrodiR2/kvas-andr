package com.dimonchik.kvas.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MemoryFact(
    val id: String = UUID.randomUUID().toString(),
    val fact: String,
    val category: String = "user",
    val updatedAt: Long = System.currentTimeMillis()
)

/** Small local cross-chat memory. Stored in app-private preferences, never uploaded by itself. */
class MemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("kvas_long_term_memory", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<MemoryFact> = runCatching {
        val rows = JSONArray(prefs.getString("facts", "[]"))
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val fact = row.optString("fact").trim()
                if (fact.isNotBlank()) add(
                    MemoryFact(
                        id = row.optString("id", UUID.randomUUID().toString()),
                        fact = fact,
                        category = row.optString("category", "user"),
                        updatedAt = row.optLong("updatedAt", 0L)
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(fact: String, category: String = "user") {
        val value = fact.trim().take(500)
        require(value.isNotBlank()) { "Пустой факт не сохраняется." }
        val previous = all()
        val normalized = value.lowercase()
        val rows = listOf(MemoryFact(fact = value, category = category.take(32))) +
            previous.filterNot { it.fact.lowercase() == normalized }
        val json = JSONArray()
        rows.take(100).forEach { item ->
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("fact", item.fact)
                    .put("category", item.category)
                    .put("updatedAt", item.updatedAt)
            )
        }
        prefs.edit().putString("facts", json.toString()).apply()
    }

    @Synchronized
    fun remove(id: String) {
        val json = JSONArray()
        all().filterNot { it.id == id }.forEach { item ->
            json.put(JSONObject().put("id", item.id).put("fact", item.fact).put("category", item.category).put("updatedAt", item.updatedAt))
        }
        prefs.edit().putString("facts", json.toString()).apply()
    }
}