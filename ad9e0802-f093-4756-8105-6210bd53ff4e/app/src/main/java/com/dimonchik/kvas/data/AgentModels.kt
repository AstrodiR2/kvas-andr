package com.dimonchik.kvas.data

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class AgentQuestion(val title: String, val options: List<String>, val multiple: Boolean)
data class AgentQuestionBundle(val questions: List<AgentQuestion>)
data class AgentFile(val name: String, val path: String, val mimeType: String, val bytes: ByteArray) {
    val textContent: String?
        get() = if (
            mimeType.startsWith("text/") ||
            name.substringAfterLast('.', "").lowercase() in setOf(
                "md", "txt", "kt", "kts", "java", "py", "js", "ts", "html", "css", "xml", "json", "yaml", "yml", "sh", "sql"
            )
        ) bytes.toString(Charsets.UTF_8) else null
}

data class AgentTurn(
    val role: String,
    val content: String = "",
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val imageDataUris: List<String> = emptyList()
)

data class AgentCompletion(
    val text: String,
    val toolCalls: List<ToolCall>,
    val inputTokens: Long?,
    val outputTokens: Long?
)