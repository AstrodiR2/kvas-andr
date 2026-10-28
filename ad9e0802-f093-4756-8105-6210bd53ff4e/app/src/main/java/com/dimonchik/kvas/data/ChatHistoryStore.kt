package com.dimonchik.kvas.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedMessage(
    val role: String,
    val text: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val attachmentName: String? = null,
    val attachmentUri: String? = null,
    val attachmentText: String? = null
)

data class SavedChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val workingDirectory: String = "",
    val contextSummary: String = "",
    val messages: List<SavedMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Lightweight local persistence. API keys remain in ProviderStore's encrypted storage;
 * chat content is kept in app-private preferences and is never sent to Termux by this store.
 */
class ChatHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("kvas_chats", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<SavedChat> = runCatching {
        val rows = JSONArray(prefs.getString("rows", "[]"))
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val messagesJson = row.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (j in 0 until messagesJson.length()) {
                        val msg = messagesJson.optJSONObject(j) ?: continue
                        add(
                            SavedMessage(
                                role = msg.optString("role"),
                                text = msg.optString("text"),
                                inputTokens = msg.optLong("input", -1).takeIf { it >= 0 },
                                outputTokens = msg.optLong("output", -1).takeIf { it >= 0 },
                                attachmentName = msg.optString("attachmentName").takeIf { it.isNotBlank() },
                                attachmentUri = msg.optString("attachmentUri").takeIf { it.isNotBlank() },
                                attachmentText = msg.optString("attachmentText").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                add(
                    SavedChat(
                        id = row.optString("id", UUID.randomUUID().toString()),
                        title = row.optString("title"),
                        workingDirectory = row.optString("directory"),
                        contextSummary = row.optString("contextSummary"),
                        messages = messages,
                        updatedAt = row.optLong("updatedAt", 0L)
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(chat: SavedChat) {
        val rows = (all().filterNot { it.id == chat.id } + chat.copy(updatedAt = System.currentTimeMillis()))
            .sortedByDescending { it.updatedAt }
            .take(100)
        val json = JSONArray()
        rows.forEach { row ->
            json.put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("directory", row.workingDirectory)
                    .put("contextSummary", row.contextSummary)
                    .put("updatedAt", row.updatedAt)
                    .put("messages", JSONArray().apply {
                        row.messages.forEach { message ->
                            put(
                                JSONObject()
                                    .put("role", message.role)
                                    .put("text", message.text)
                                    .put("input", message.inputTokens ?: -1)
                                    .put("output", message.outputTokens ?: -1)
                                    .put("attachmentName", message.attachmentName.orEmpty())
                                    .put("attachmentUri", message.attachmentUri.orEmpty())
                                    .put("attachmentText", message.attachmentText.orEmpty())
                            )
                        }
                    })
            )
        }
        prefs.edit().putString("rows", json.toString()).apply()
    }

    @Synchronized
    fun exportJson(): String = JSONArray(prefs.getString("rows", "[]")).toString()

    @Synchronized
    fun importJson(serialized: String): Boolean = runCatching {
        val json = JSONArray(serialized)
        require(json.length() <= 100) { "Archive contains too many chats." }
        prefs.edit().putString("rows", json.toString()).commit()
    }.getOrDefault(false)

    @Synchronized
    fun mergeJson(serialized: String): Boolean = runCatching {
        val incoming = JSONArray(serialized)
        val merged = mutableMapOf<String, JSONObject>()
        val local = JSONArray(prefs.getString("rows", "[]"))
        listOf(local, incoming).forEach { rows ->
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                val previous = merged[id]
                if (previous == null || row.optLong("updatedAt") >= previous.optLong("updatedAt")) merged[id] = row
            }
        }
        val result = JSONArray()
        merged.values.sortedByDescending { it.optLong("updatedAt") }.take(100).forEach { result.put(it) }
        prefs.edit().putString("rows", result.toString()).commit()
    }.getOrDefault(false)
}