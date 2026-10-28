package com.dimonchik.kvas.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MarkdownPart {
    data class TextPart(val value: String, val level: Int = 0) : MarkdownPart
    data class CodePart(val language: String, val value: String) : MarkdownPart
}

private fun parseMarkdown(source: String): List<MarkdownPart> {
    val result = mutableListOf<MarkdownPart>()
    val code = mutableListOf<String>()
    var inCode = false
    var language = ""
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        val value = paragraph.joinToString("\n").trim()
        if (value.isNotEmpty()) result += MarkdownPart.TextPart(value)
        paragraph.clear()
    }
    source.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (!inCode) {
                flushParagraph()
                language = line.trim().removePrefix("```").trim()
                code.clear()
                inCode = true
            } else {
                result += MarkdownPart.CodePart(language, code.joinToString("\n"))
                inCode = false
            }
        } else if (inCode) {
            code += line
        } else {
            val heading = Regex("^(#{1,4})\\s+(.+)$").matchEntire(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    result += MarkdownPart.TextPart(heading.groupValues[2], heading.groupValues[1].length)
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    paragraph += "•  ${line.trimStart().drop(2)}"
                Regex("^\\d+[.)]\\s+").containsMatchIn(line.trimStart()) ->
                    paragraph += line.trimStart()
                line.trimStart().startsWith(">") ->
                    paragraph += line.trimStart().removePrefix(">").trim().let { "│  $it" }
                else -> paragraph += line
            }
        }
    }
    if (inCode) result += MarkdownPart.CodePart(language, code.joinToString("\n"))
    flushParagraph()
    return result
}

@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = KvasText
) {
    Column(modifier) {
        parseMarkdown(markdown).forEach { part ->
            when (part) {
                is MarkdownPart.TextPart -> {
                    val size = when (part.level) {
                        1 -> 22
                        2 -> 19
                        3 -> 17
                        4 -> 16
                        else -> 15
                    }
                    Text(
                        text = inlineMarkdown(part.value, textColor),
                        color = textColor,
                        fontSize = size.sp,
                        fontWeight = if (part.level > 0) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = (size + 8).sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (part.level > 0) 7.dp else 3.dp)
                    )
                }
                is MarkdownPart.CodePart -> {
                    Surface(
                        color = KvasSurface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                    ) {
                        Text(
                            text = syntaxHighlight(part.value, part.language),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

private fun inlineMarkdown(text: String, color: Color) = buildAnnotatedString {
    val pattern = Regex("(\\*\\*.+?\\*\\*)|(`[^`]+`)|(\\*[^*]+\\*)|(_[^_]+_)")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removePrefix("**").removeSuffix("**"))
            }
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    color = KvasAccent,
                    fontFamily = FontFamily.Monospace,
                    background = KvasSurface
                )
            ) {
                append(token.removePrefix("`").removeSuffix("`"))
            }
            token.startsWith("*") || token.startsWith("_") -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(token.substring(1, token.length - 1))
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
    addStyle(SpanStyle(color = color), 0, length)
}

private fun syntaxHighlight(code: String, language: String) = buildAnnotatedString {
    append(code)
    addStyle(SpanStyle(color = KvasText), 0, length)
    val keywords = when (language.lowercase()) {
        "kotlin", "kt", "java" -> Regex("\\b(fun|val|var|class|object|return|if|else|when|private|public|suspend|import|package|new|void|static)\\b")
        "python", "py" -> Regex("\\b(def|class|return|if|elif|else|for|while|import|from|as|with|try|except|True|False|None)\\b")
        "javascript", "js", "typescript", "ts", "json" -> Regex("\\b(const|let|var|function|return|async|await|class|new|if|else|true|false|null)\\b")
        "bash", "sh", "shell" -> Regex("\\b(if|then|else|fi|for|do|done|function|export|sudo|cd|mkdir|echo)\\b")
        else -> Regex("(?!)")
    }
    keywords.findAll(code).forEach { match ->
        addStyle(
            SpanStyle(color = Color(0xFFC792EA), fontWeight = FontWeight.Medium),
            match.range.first,
            match.range.last + 1
        )
    }
    Regex("\"[^\"]*\"|'[^']*'").findAll(code).forEach { match ->
        addStyle(SpanStyle(color = Color(0xFF9CCC65)), match.range.first, match.range.last + 1)
    }
}