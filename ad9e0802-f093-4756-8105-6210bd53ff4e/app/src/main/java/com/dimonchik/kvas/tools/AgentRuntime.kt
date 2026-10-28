package com.dimonchik.kvas.tools

import android.content.Context
import android.util.Base64
import com.dimonchik.kvas.data.AgentFile
import com.dimonchik.kvas.data.AgentCompletion
import com.dimonchik.kvas.data.AgentQuestion
import com.dimonchik.kvas.data.AgentQuestionBundle
import com.dimonchik.kvas.data.AgentTurn
import com.dimonchik.kvas.data.ModelInfo
import com.dimonchik.kvas.data.MemoryStore
import com.dimonchik.kvas.data.ProviderApi
import com.dimonchik.kvas.data.ProviderConfig
import com.dimonchik.kvas.data.ToolCall
import com.dimonchik.kvas.onboarding.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ApprovalChoice { ALLOW_ONCE, ALWAYS_ALLOW, DENY }

data class ToolApprovalRequest(
    val toolName: String,
    val title: String,
    val description: String
)

data class AgentTask(val title: String, val completed: Boolean)
data class FileChange(val path: String, val before: String?, val after: String)
data class BackgroundTask(
    val id: String,
    val description: String,
    val command: String,
    val startedAt: Long
)

data class AgentAction(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val title: String,
    val detail: String = "",
    val before: String? = null,
    val after: String? = null,
    val running: Boolean = true,
    val denied: Boolean = false
)

data class AgentRunResult(
    val text: String,
    val turns: List<AgentTurn>,
    val inputTokens: Long?,
    val outputTokens: Long?
)

/**
 * Bounded tool-calling loop for the coding chat. The model can only call tools declared in
 * ProviderApi; each call is permission-gated and every filesystem operation is rooted inside
 * the selected Termux workspace. It intentionally does not expose generic network scanning,
 * exploit tooling, credential access, or unrestricted shell pipelines.
 */
class AgentRuntime(
    private val context: Context,
    private val api: ProviderApi,
    private val permissions: ToolAccessStore,
    private val memoryStore: MemoryStore,
    private val askPermission: suspend (ToolApprovalRequest) -> ApprovalChoice,
    private val askUser: suspend (AgentQuestionBundle) -> String,
    private val onAction: (AgentAction) -> Unit,
    private val onPlan: (List<AgentTask>) -> Unit = {},
    private val onBackground: (List<BackgroundTask>) -> Unit = {},
    private val onFileChange: (FileChange) -> Unit = {},
    private val onFile: (AgentFile) -> Unit = {}
) {
    private val webClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun run(
        provider: ProviderConfig,
        model: ModelInfo,
        initialTurns: List<AgentTurn>,
        effort: String,
        workspace: String,
        webToolsEnabled: Boolean,
        memoryToolsEnabled: Boolean = true
    ): AgentRunResult {
        val turns = initialTurns.toMutableList()
        var lastText = ""
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        repeat(MAX_ROUNDS) {
            val response: AgentCompletion = api.completeAgent(
                provider, model, turns, effort, webToolsEnabled, memoryToolsEnabled
            )
            lastText = response.text
            inputTokens = response.inputTokens ?: inputTokens
            outputTokens = response.outputTokens ?: outputTokens
            if (response.toolCalls.isEmpty()) {
                if (response.text.isNotBlank()) turns += AgentTurn("assistant", response.text)
                return AgentRunResult(response.text, turns, inputTokens, outputTokens)
            }
            turns += AgentTurn("assistant", response.text, toolCalls = response.toolCalls)
            for (call in response.toolCalls) {
                val action = actionFor(call)
                onAction(action)
                if (call.name == "update_plan") {
                    val tasks = runCatching {
                        val rows = JSONObject(call.arguments).optJSONArray("tasks") ?: error("Список задач отсутствует.")
                        buildList {
                            for (index in 0 until rows.length()) {
                                val task = rows.optJSONObject(index) ?: continue
                                val title = task.optString("title").trim()
                                if (title.isNotBlank()) add(AgentTask(title, task.optBoolean("completed")))
                            }
                        }.take(20)
                    }.getOrElse { emptyList() }
                    onPlan(tasks)
                    onAction(action.copy(running = false, detail = "План обновлён: ${tasks.size} задач"))
                    turns += AgentTurn("tool", "План обновлён. Отмечай задачи выполненными по мере реального завершения.", toolCallId = call.id)
                    continue
                }
                if (call.name == "ask_user") {
                    val parsedBundle = runCatching {
                        val rows = JSONObject(call.arguments).optJSONArray("questions") ?: error("Список вопросов отсутствует.")
                        require(rows.length() in 1..3) { "Можно задать от 1 до 3 вопросов за раз." }
                        AgentQuestionBundle(buildList {
                            for (index in 0 until rows.length()) {
                                val row = rows.optJSONObject(index) ?: continue
                                val multiple = row.optBoolean("multiple")
                                val optionsJson = row.optJSONArray("options")
                                val limit = if (multiple) 4 else 3
                                val options = buildList {
                                    if (optionsJson != null) for (i in 0 until optionsJson.length()) {
                                        val value = optionsJson.optString(i).trim()
                                        if (value.isNotBlank()) add(value)
                                    }
                                }.take(limit)
                                val question = row.optString("question").trim()
                                if (question.isNotBlank()) add(AgentQuestion(question, options, multiple))
                            }
                        }.also { require(it.isNotEmpty()) { "Вопрос не содержит текста." } })
                    }
                    if (parsedBundle.isFailure) {
                        onAction(action.copy(running = false, denied = true, detail = parsedBundle.exceptionOrNull()?.message.orEmpty()))
                        turns += AgentTurn("tool", "Некорректная структура вопроса; задай уточнение обычным текстом.", toolCallId = call.id)
                        continue
                    }
                    val bundle = parsedBundle.getOrThrow()
                    val answer = askUser(bundle)
                    onAction(action.copy(running = false, detail = "Получен ответ пользователя"))
                    turns += AgentTurn("tool", answer, toolCallId = call.id)
                    continue
                }
                val access = permissions.get(call.name)
                val choice = when (access) {
                    ToolAccess.NEVER -> ApprovalChoice.DENY
                    ToolAccess.ALWAYS_ALLOW -> ApprovalChoice.ALWAYS_ALLOW
                    ToolAccess.ASK -> askPermission(
                        ToolApprovalRequest(
                            call.name,
                            action.title,
                            action.detail.ifBlank { "Квас хочет использовать инструмент ${call.name}." }
                        )
                    )
                }
                if (choice == ApprovalChoice.ALWAYS_ALLOW) permissions.set(call.name, ToolAccess.ALWAYS_ALLOW)
                val result = if (choice == ApprovalChoice.DENY) {
                    onAction(action.copy(running = false, denied = true, detail = "Действие отклонено."))
                    "Действие отклонено пользователем; не повторяй тот же вызов."
                } else {
                    runCatching { execute(call, workspace) }.fold(
                        onSuccess = { output ->
                            onAction(action.copy(running = false, detail = output.summary, before = output.before, after = output.after))
                            output.text
                        },
                        onFailure = { error ->
                            val message = error.message ?: "Не удалось выполнить инструмент."
                            onAction(action.copy(running = false, detail = message, denied = true))
                            "Ошибка инструмента: $message"
                        }
                    )
                }
                turns += AgentTurn("tool", result.take(MAX_TOOL_RESULT), toolCallId = call.id)
            }
        }
        return AgentRunResult(
            lastText.ifBlank { "Остановил цепочку инструментов: достигнут безопасный лимит вызовов. Продолжить можно следующим сообщением." },
            turns,
            inputTokens,
            outputTokens
        )
    }

    private data class ToolOutput(
        val text: String,
        val summary: String,
        val before: String? = null,
        val after: String? = null
    )

    private suspend fun execute(call: ToolCall, workspace: String): ToolOutput {
        val args = JSONObject(call.arguments)
        return when (call.name) {
            "list_directory" -> {
                val path = args.optString("path", ".")
                val result = termuxOperation(workspace, "list_directory", JSONObject().put("path", path))
                ToolOutput(result, "Просмотрена папка $path")
            }
            "read_file" -> {
                val path = args.getString("path")
                val json = JSONObject(termuxOperation(workspace, "read_file", JSONObject().put("path", path)))
                ToolOutput(json.optString("content"), "Прочитан $path")
            }
            "send_file" -> {
                val path = args.getString("path")
                val json = JSONObject(termuxOperation(workspace, "read_file_binary", JSONObject().put("path", path)))
                val bytes = Base64.decode(json.getString("contentBase64"), Base64.DEFAULT)
                val name = json.optString("name", path.substringAfterLast('/'))
                onFile(AgentFile(name, path, mimeForFileName(name), bytes))
                ToolOutput("Файл $name отправлен пользователю.", "Передан $name")
            }
            "write_file" -> {
                val path = args.getString("path")
                val content = args.getString("content")
                val result = termuxOperation(workspace, "write_file", JSONObject().put("path", path).put("content", content))
                onFileChange(FileChange(path, null, content))
                ToolOutput(result, "Создан $path", after = content)
            }
            "create_directory" -> {
                val path = args.getString("path")
                val result = termuxOperation(workspace, "create_directory", JSONObject().put("path", path))
                ToolOutput(result, "Создана папка $path")
            }
            "edit_file" -> {
                val path = args.getString("path")
                val json = JSONObject(termuxOperation(workspace, "edit_file", JSONObject()
                    .put("path", path)
                    .put("old_text", args.getString("old_text"))
                    .put("new_text", args.getString("new_text"))))
                onFileChange(FileChange(path, json.optString("before"), json.optString("after")))
                ToolOutput("Изменён файл $path.", "Отредактирован $path", json.optString("before"), json.optString("after"))
            }
            "search_files" -> {
                val query = args.getString("query")
                val result = termuxOperation(workspace, "search_files", JSONObject().put("query", query))
                ToolOutput(result, "Поиск по файлам: «$query»")
            }
            "bash" -> {
                val command = args.getString("command")
                SafeCommandPolicy.requireAllowed(command)
                val seconds = args.optInt("timeout_seconds", 30).coerceIn(1, 120)
                val result = termuxOperation(workspace, "bash", JSONObject().put("command", command).put("timeout_seconds", seconds))
                val json = JSONObject(result)
                val exit = json.optInt("exitCode", -1)
                val output = buildString {
                    append("exit_code=$exit\n")
                    if (json.optBoolean("timedOut")) append("Команда остановлена по таймауту.\n")
                    append(json.optString("stdout"))
                    if (json.optString("stderr").isNotBlank()) append("\nSTDERR:\n").append(json.optString("stderr"))
                }
                ToolOutput(output, "Команда завершена с кодом $exit: $command")
            }
            "start_background" -> {
                val description = args.getString("description")
                val command = args.getString("command")
                SafeCommandPolicy.requireBackgroundAllowed(command)
                val json = JSONObject(termuxOperation(
                    workspace,
                    "start_background",
                    JSONObject().put("description", description).put("command", command)
                ))
                onBackground(listBackgroundTasks(workspace))
                ToolOutput(json.toString(), "Фоновая задача запущена: $description")
            }
            "remember_fact" -> {
                val fact = args.getString("fact")
                val category = args.optString("category", "notes")
                val safeFact = fact.takeIf { !Regex("(?i)(api[_ -]?key|password|пароль|токен|secret|секрет|private key)").containsMatchIn(it) }
                    ?: error("Секреты и учётные данные не сохраняются в памяти.")
                memoryStore.add(safeFact, category)
                ToolOutput("Факт сохранён в локальную память устройства.", "Запомнил факт")
            }
            "web_search" -> {
                val query = args.getString("query")
                val result = webSearch(query)
                ToolOutput(result, "Поиск в интернете: $query")
            }
            "web_fetch" -> {
                val url = args.getString("url")
                val result = webFetch(url)
                ToolOutput(result, "Загружена общедоступная страница")
            }
            else -> error("Неизвестный инструмент: ${call.name}")
        }
    }

    private suspend fun termuxOperation(workspace: String, operation: String, args: JSONObject): String {
        check(TermuxBridge.hasPermission(context)) { "Нет разрешения Termux RUN_COMMAND. Разрешите его в настройках Android." }
        val requestId = TermuxBridge.runWorkspaceOperation(context, workspace, operation, args)
            ?: error("Рабочая папка находится вне домашней директории Termux.")
        val prefs = context.getSharedPreferences(TermuxBridge.RPC_PREFS, Context.MODE_PRIVATE)
        withTimeout(130_000) {
            while (!prefs.getBoolean("$requestId.finished", false)) delay(200)
        }
        val exit = prefs.getInt("$requestId.exit", -1)
        val stdout = prefs.getString("$requestId.stdout", "").orEmpty()
        val stderr = prefs.getString("$requestId.stderr", "").orEmpty()
        prefs.edit().remove("$requestId.finished").remove("$requestId.exit")
            .remove("$requestId.stdout").remove("$requestId.stderr").apply()
        if (exit != 0) {
            val structuredError = runCatching { JSONObject(stderr).optString("error") }.getOrNull()
            error(structuredError?.takeIf { it.isNotBlank() } ?: stderr.take(300).ifBlank { "Termux завершил операцию с кодом $exit." })
        }
        return stdout.trim().lineSequence().lastOrNull { it.startsWith("{") }.orEmpty()
            .ifBlank { error("Termux вернул пустой результат.") }
            .let { line ->
                val obj = JSONObject(line)
                if (!obj.optBoolean("ok")) error(obj.optString("error", "Операция не выполнена."))
                val result = obj.opt("result")
                if (result is JSONObject) result.toString() else result?.toString().orEmpty()
            }
    }

    suspend fun listBackgroundTasks(workspace: String): List<BackgroundTask> {
        val raw = termuxOperation(workspace, "list_background", JSONObject())
        val rows = org.json.JSONArray(raw)
        return buildList {
            for (index in 0 until rows.length()) {
                val task = rows.optJSONObject(index) ?: continue
                add(BackgroundTask(
                    id = task.optString("id"),
                    description = task.optString("description"),
                    command = task.optString("command"),
                    startedAt = task.optLong("startedAt")
                ))
            }
        }
    }

    suspend fun stopBackgroundTask(workspace: String, id: String): Boolean {
        val result = JSONObject(termuxOperation(workspace, "stop_background", JSONObject().put("id", id)))
        onBackground(listBackgroundTasks(workspace))
        return result.optBoolean("stopped")
    }

    suspend fun undoFileChange(workspace: String, change: FileChange) {
        if (change.before == null) {
            termuxOperation(workspace, "delete_file", JSONObject()
                .put("path", change.path)
                .put("expected_content", change.after))
        } else {
            termuxOperation(workspace, "restore_file", JSONObject()
                .put("path", change.path)
                .put("expected_content", change.after)
                .put("content", change.before))
        }
    }

    private suspend fun webSearch(query: String): String {
        require(query.length in 2..300) { "Некорректный поисковый запрос." }
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded")
            .header("User-Agent", "KvasAndroid/1.0")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            webClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Поиск вернул HTTP ${response.code}." }
                val html = response.body?.string().orEmpty().take(250_000)
                val anchors = Regex("""class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
                val snippets = Regex("""class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
                val results = anchors.findAll(html).take(8).mapIndexed { index, match ->
                    val url = decodeHtml(match.groupValues[1])
                    val title = stripHtml(match.groupValues[2])
                    val snippet = snippets.getOrNull(index).orEmpty()
                    "${index + 1}. $title\n$url${if (snippet.isNotBlank()) "\n$snippet" else ""}"
                }.toList()
                results.joinToString("\n\n").ifBlank { "По запросу не найдено результатов." }
            }
        }
    }

    private suspend fun webFetch(url: String): String {
        val uri = URI(url)
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "Разрешены только публичные HTTPS-адреса."
        }
        return withContext(Dispatchers.IO) {
            requirePublicHost(uri.host)
            webClient.newCall(Request.Builder().url(uri.toASCIIString()).header("User-Agent", "KvasAndroid/1.0").build()).execute().use {
                check(it.isSuccessful) { "HTTP ${it.code}; перенаправления не выполняются." }
                val type = it.header("Content-Type").orEmpty()
                require(type.contains("text/html", true) || type.contains("text/plain", true) || type.contains("application/json", true)) {
                    "Поддерживаются только текстовые страницы."
                }
                it.body?.string().orEmpty().take(100_000)
            }
        }
    }

    private fun requirePublicHost(host: String) {
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all {
            !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress
        }) { "Локальные и приватные адреса запрещены." }
    }

    private fun stripHtml(value: String): String = decodeHtml(
        value.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private fun mimeForFileName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "txt", "log" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun actionFor(call: ToolCall): AgentAction {
        val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
        return when (call.name) {
            "read_file" -> AgentAction(toolName = call.name, title = "Читаю ${args.optString("path")}", detail = "Чтение файла в выбранной папке")
            "send_file" -> AgentAction(toolName = call.name, title = "Передаю ${args.optString("path")}", detail = "Файл будет прикреплён к сообщению")
            "write_file" -> AgentAction(toolName = call.name, title = "Создаю ${args.optString("path")}", detail = "Запись файла в выбранной папке")
            "create_directory" -> AgentAction(toolName = call.name, title = "Создаю папку ${args.optString("path")}", detail = "Создание каталога в выбранной папке")
            "edit_file" -> AgentAction(toolName = call.name, title = "Редактирую ${args.optString("path")}", detail = "Точечная правка файла в выбранной папке")
            "list_directory" -> AgentAction(toolName = call.name, title = "Изучаю папку ${args.optString("path")}", detail = "Просмотр списка файлов")
            "search_files" -> AgentAction(toolName = call.name, title = "Ищу файлы", detail = args.optString("query"))
            "update_plan" -> AgentAction(toolName = call.name, title = "Обновляю план задач", detail = "Список задач")
            "ask_user" -> AgentAction(toolName = call.name, title = "Задаю уточняющий вопрос", detail = "Ожидаю ответа")
            "bash" -> AgentAction(toolName = call.name, title = "Выполняю команду", detail = args.optString("command"))
            "start_background" -> AgentAction(toolName = call.name, title = "Запускаю фоновую задачу", detail = args.optString("command"))
            "remember_fact" -> AgentAction(toolName = call.name, title = "Сохраняю факт в память", detail = args.optString("fact"))
            "web_search" -> AgentAction(toolName = call.name, title = "Ищу в интернете", detail = args.optString("query"))
            "web_fetch" -> AgentAction(toolName = call.name, title = "Читаю веб-страницу", detail = args.optString("url"))
            else -> AgentAction(toolName = call.name, title = "Инструмент ${call.name}")
        }
    }

    companion object {
        private const val MAX_ROUNDS = 12
        private const val MAX_TOOL_RESULT = 50_000
    }
}

/**
 * Restricts shell calls to common local development/read commands. Pipelines, redirects,
 * command chaining, networking utilities, privilege changes, and traversal paths are refused.
 * A user confirmation is still required unless the user explicitly changed the tool setting.
 */
object SafeCommandPolicy {
    private val forbiddenChars = Regex("[;|&<>`$\\\\\\n\\r]")
    private val forbiddenWords = Regex("(?i)\\b(sudo|su|curl|wget|ssh|scp|nc|ncat|netcat|nmap|masscan|hydra|sqlmap|msfconsole|aircrack-ng|termux-clipboard-get|termux-location|termux-sms-list|chmod|chown|rm|dd|mkfs|reboot|shutdown)\\b")

    fun requireAllowed(command: String) {
        val normalized = command.trim()
        require(normalized.isNotEmpty() && normalized.length <= 500) { "Команда пустая или слишком длинная." }
        require(!forbiddenChars.containsMatchIn(normalized)) { "Цепочки, перенаправления и shell-операторы запрещены." }
        require(!forbiddenWords.containsMatchIn(normalized)) { "Команда заблокирована политикой безопасного выполнения." }
        val arguments = normalized.split(Regex("\\s+"))
        require(arguments.none { token ->
            val value = token.substringAfter('=', token)
            value.startsWith("/") || value.split('/').any { it == ".." }
        }) { "Абсолютные пути и выход из рабочей папки запрещены." }
        val allowed = listOf(
            Regex("^pwd$"),
            Regex("^(ls|find|cat|head|tail|grep|rg|wc|file)(\\s+[A-Za-z0-9_./:@=,+ -]+)?$"),
            Regex("^git\\s+(status|diff|log|show)(\\s+[A-Za-z0-9_./:@=,+ -]+)?$"),
            Regex("^npm\\s+(test|run\\s+(test|build|lint|typecheck))(\\s+[A-Za-z0-9_./:@=,+ -]+)?$"),
            Regex("^(gradle|\\./gradlew)\\s+(tasks|test|check|lint|build|assembleDebug)(\\s+[A-Za-z0-9_./:@=,+ -]+)?$"),
            Regex("^(pytest|python\\s+-m\\s+pytest)(\\s+[A-Za-z0-9_./:@=,+ -]+)?$"),
            Regex("^node\\s+--test(\\s+[A-Za-z0-9_./:@=,+ -]+)?$")
        )
        require(allowed.any { it.matches(normalized) }) {
            "Разрешены только локальные команды чтения, тестирования и сборки проекта."
        }
        require(!Regex("(?i)\\b(find\\s+.*-(exec|execdir|delete|ok)|git\\s+.*--upload-pack)\\b").containsMatchIn(normalized)) {
            "Этот вариант команды не поддерживается."
        }
    }

    fun requireBackgroundAllowed(command: String) {
        val normalized = command.trim()
        require(!forbiddenChars.containsMatchIn(normalized)) { "Shell-операторы запрещены." }
        val allowed = Regex("^npm run dev -- --host 127\\.0\\.0\\.1$").matches(normalized) ||
            Regex("^npm run dev -- --port [0-9]{2,5} --host 127\\.0\\.0\\.1$").matches(normalized) ||
            Regex("^python -m http\\.server [0-9]{2,5} --bind 127\\.0\\.0\\.1$").matches(normalized)
        require(allowed) { "Разрешены только локальные dev-серверы на 127.0.0.1." }
    }
}