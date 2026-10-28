package com.dimonchik.kvas.tools

import android.content.Context

enum class ToolAccess { ASK, ALWAYS_ALLOW, NEVER }
data class ToolSpec(
    val name: String,
    val description: String,
    val defaultAccess: ToolAccess = ToolAccess.ASK,
    val caution: String? = null
)

/** Safe defaults for the later agent runtime: each action is explicit and visible to the user. */
object ToolCatalog {
    val tools = listOf(
        ToolSpec("read_file", "Чтение файла внутри выбранной папки"),
        ToolSpec("send_file", "Передача файла пользователю из выбранной папки"),
        ToolSpec("write_file", "Создание файла внутри выбранной папки"),
        ToolSpec("create_directory", "Создание папки внутри выбранной папки"),
        ToolSpec("edit_file", "Изменение файла внутри выбранной папки"),
        ToolSpec("list_directory", "Просмотр содержимого выбранной папки"),
        ToolSpec("search_files", "Поиск по именам файлов в выбранной папке"),
        ToolSpec("bash", "Запуск показанной пользователю команды в Termux с подтверждением",
            caution = "Команды могут изменять файлы и состояние Termux. Проверяйте текст команды перед запуском."),
        ToolSpec("start_background", "Запуск локального dev-сервера на 127.0.0.1 с подтверждением",
            caution = "Разрешены только локальные серверы, привязанные к 127.0.0.1."),
        ToolSpec("remember_fact", "Сохранение факта в локальную память между чатами",
            caution = "Не сохраняйте API-ключи, пароли и другие секреты."),
        ToolSpec("web_search", "Поиск общедоступной информации в интернете"),
        ToolSpec("web_fetch", "Загрузка общедоступной страницы по URL",
            caution = "Не отправляйте секреты или API-ключи в URL.")
    )
}

/** Per-tool permission preferences; the runtime must still request approval for ASK. */
class ToolAccessStore(context: Context) {
    private val prefs = context.getSharedPreferences("kvas_tool_access", Context.MODE_PRIVATE)

    fun get(toolName: String): ToolAccess =
        runCatching { ToolAccess.valueOf(prefs.getString(toolName, ToolAccess.ASK.name)!!) }
            .getOrDefault(ToolAccess.ASK)

    fun set(toolName: String, access: ToolAccess) {
        prefs.edit().putString(toolName, access.name).apply()
    }

    fun setAll(access: ToolAccess) {
        prefs.edit().apply {
            ToolCatalog.tools.forEach { putString(it.name, access.name) }
        }.apply()
    }

    fun uniformAccess(): ToolAccess? {
        val values = ToolCatalog.tools.map { get(it.name) }.distinct()
        return values.singleOrNull()
    }
}
