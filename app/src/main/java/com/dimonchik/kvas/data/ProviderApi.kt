package com.dimonchik.kvas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** OpenAI-compatible provider metadata. A null contextLimit means the provider did not expose it. */
data class ModelInfo(val id: String, val contextLimit: Long? = null)
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<ModelInfo>
)
data class ChatTurn(val role: String, val content: String)
data class Completion(val text: String, val inputTokens: Long?, val outputTokens: Long?)

class ProviderApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val endpoint = endpoint(baseUrl, "models")
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { providerError(response.code, body) }
            val rows = JSONObject(body).optJSONArray("data") ?: JSONArray()
            buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isNotEmpty()) {
                        val context = sequenceOf("context_length", "context_window", "max_input_tokens")
                            .map { item.optLong(it, 0L) }.firstOrNull { it > 0L }
                        add(ModelInfo(id, context))
                    }
                }
            }.also { check(it.isNotEmpty()) { "Провайдер ответил, но список моделей пуст." } }
        }
    }

    suspend fun complete(
        provider: ProviderConfig,
        model: ModelInfo,
        turns: List<ChatTurn>,
        effort: String
    ): Completion = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val requestBody = JSONObject()
            .put("model", model.id)
            .put("messages", messages)
            .put("reasoning_effort", effort.lowercase())
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(endpoint(provider.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { providerError(response.code, raw) }
            val json = JSONObject(raw)
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val usage = json.optJSONObject("usage")
            Completion(text, usage?.optLong("prompt_tokens"), usage?.optLong("completion_tokens"))
        }
    }

    suspend fun completeAgent(
        provider: ProviderConfig,
        model: ModelInfo,
        turns: List<AgentTurn>,
        effort: String,
        webToolsEnabled: Boolean = false,
        memoryToolsEnabled: Boolean = true
    ): AgentCompletion = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            turns.forEach { turn ->
                val message = JSONObject().put("role", turn.role)
                if (turn.imageDataUris.isNotEmpty() && turn.role == "user") {
                    message.put("content", JSONArray().apply {
                        if (turn.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", turn.content))
                        turn.imageDataUris.forEach { uri ->
                            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", uri)))
                        }
                    })
                } else if (turn.content.isNotEmpty() || turn.toolCalls.isEmpty()) message.put("content", turn.content)
                if (turn.toolCallId != null) message.put("tool_call_id", turn.toolCallId)
                if (turn.toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray().apply {
                        turn.toolCalls.forEach { call ->
                            put(JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
                        }
                    })
                }
                put(message)
            }
        }
        val payload = JSONObject()
            .put("model", model.id)
            .put("messages", messages)
            .put("tools", agentToolSchemas(webToolsEnabled, memoryToolsEnabled))
            .put("tool_choice", "auto")
            .put("reasoning_effort", effort.lowercase())
        val request = Request.Builder().url(endpoint(provider.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { providerError(response.code, raw) }
            val message = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: error("Провайдер вернул пустой ответ.")
            val calls = message.optJSONArray("tool_calls") ?: JSONArray()
            val parsedCalls = buildList {
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name")
                    if (name.isNotBlank()) add(ToolCall(
                        id = call.optString("id"),
                        name = name,
                        arguments = function.optString("arguments", "{}")
                    ))
                }
            }
            val usage = JSONObject(raw).optJSONObject("usage")
            AgentCompletion(
                text = message.optString("content"),
                toolCalls = parsedCalls,
                inputTokens = usage?.optLong("prompt_tokens"),
                outputTokens = usage?.optLong("completion_tokens")
            )
        }
    }

    private fun agentToolSchemas(webToolsEnabled: Boolean, memoryToolsEnabled: Boolean): JSONArray {
        fun schema(name: String, description: String, properties: JSONObject, required: List<String>) =
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required)))
            )
        fun stringProperty(description: String) = JSONObject().put("type", "string").put("description", description)
        return JSONArray()
            .put(schema("list_directory", "List entries in the current workspace folder.", JSONObject().put("path", stringProperty("Relative directory; use . for workspace root.")), listOf("path")))
            .put(schema("read_file", "Read a UTF-8 file inside the current workspace.", JSONObject().put("path", stringProperty("Relative file path.")), listOf("path")))
            .put(schema("send_file", "Send a workspace file to the user as an attachment.", JSONObject().put("path", stringProperty("Relative file path.")), listOf("path")))
            .put(schema("write_file", "Create a new UTF-8 file inside the current workspace. Use edit_file for existing files.", JSONObject().put("path", stringProperty("Relative file path.")).put("content", stringProperty("Complete file contents.")), listOf("path", "content")))
            .put(schema("create_directory", "Create one directory inside the current workspace; its parent must already exist.", JSONObject().put("path", stringProperty("Relative directory path.")), listOf("path")))
            .put(schema("edit_file", "Replace an exact text fragment in a workspace file.", JSONObject().put("path", stringProperty("Relative file path.")).put("old_text", stringProperty("Exact existing text.")).put("new_text", stringProperty("Replacement text.")), listOf("path", "old_text", "new_text")))
            .put(schema("search_files", "Search file names and UTF-8 file contents inside the workspace.", JSONObject().put("query", stringProperty("Search phrase.")), listOf("query")))
            .put(schema("update_plan", "Create or update the user's visible task checklist for this request.", JSONObject().put("tasks", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("title", stringProperty("Short task title."))
                        .put("completed", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray(listOf("title", "completed"))))), listOf("tasks")))
            .put(schema("ask_user", "Ask the user up to three clarifying questions and wait for their answer before continuing.", JSONObject()
                .put("questions", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 3)
                    .put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("question", stringProperty("The question text."))
                            .put("multiple", JSONObject().put("type", "boolean"))
                            .put("options", JSONObject().put("type", "array").put("items", stringProperty("A short answer option."))))
                        .put("required", JSONArray(listOf("question", "multiple", "options"))))), listOf("questions")))
            .put(schema("start_background", "Start a local development server in the selected workspace. The user must approve it.", JSONObject()
                .put("description", stringProperty("Short explanation shown in the background task list."))
                .put("command", stringProperty("A supported local development server command.")), listOf("description", "command")))
            .put(schema("bash", "Run an allowed local development command inside the selected workspace. Commands are filtered and permission-gated.", JSONObject().put("command", stringProperty("A local command for reading, testing, or building this project.")).put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 120)), listOf("command")))
            .apply {
                if (memoryToolsEnabled) {
                    put(schema("remember_fact", "Save one durable, useful fact about the user's preferences or an ongoing project for future chats. Do not save secrets, credentials, or sensitive personal information.", JSONObject()
                        .put("fact", stringProperty("One concise durable fact."))
                        .put("category", stringProperty("profile, preference, work, people, or notes.")), listOf("fact", "category")))
                }
                if (webToolsEnabled) {
                    put(schema("web_search", "Search public web pages for general information.", JSONObject().put("query", stringProperty("Search query.")), listOf("query")))
                    put(schema("web_fetch", "Fetch a public HTTPS page.", JSONObject().put("url", stringProperty("Public HTTPS URL.")), listOf("url")))
                }
            }
    }

    private fun endpoint(baseUrl: String, suffix: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "Укажите Base URL." }
        val uri = URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для защиты API-ключа используйте HTTPS."
        }
        require(uri.host != null && uri.userInfo == null && uri.fragment == null) { "Некорректный Base URL." }
        val path = uri.path.orEmpty().trimEnd('/')
        val endpointPath = if (path.endsWith("/v1")) "$path/$suffix" else "$path/v1/$suffix"
        return URI(uri.scheme, uri.userInfo, uri.host, uri.port, endpointPath, null, null).toASCIIString()
    }

    private fun providerError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        return "Ошибка провайдера ($code)${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}"
    }
}
