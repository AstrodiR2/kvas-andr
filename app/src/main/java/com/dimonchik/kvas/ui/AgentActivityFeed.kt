package com.dimonchik.kvas.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dimonchik.kvas.tools.AgentAction

@Composable
fun AgentActivityFeed(actions: List<AgentAction>, expanded: Boolean, onToggle: () -> Unit) {
    if (actions.isEmpty()) return
    val expandedDiffs = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            "Шаги: ${actions.size}",
            Modifier.clickable(onClick = onToggle).padding(vertical = 5.dp),
            color = KvasMuted,
            fontSize = 13.sp
        )
        if (expanded) {
            actions.forEachIndexed { index, action ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (action.running) BouncingDots()
                        else Box(Modifier.padding(top = 4.dp).size(8.dp).background(KvasDim, CircleShape))
                        if (index < actions.lastIndex) {
                            Box(Modifier.padding(top = 3.dp).width(1.dp).height(32.dp).background(KvasBorder))
                        }
                    }
                    Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (!action.running) ActionIcon(action.toolName)
                            Text(action.title, color = if (action.running) KvasText else KvasMuted, fontSize = 13.sp)
                        }
                        if (action.detail.isNotBlank()) {
                            Text(action.detail.take(180), Modifier.padding(top = 3.dp), color = KvasDim, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                        if (action.before != null && action.after != null) {
                            val lines = remember(action.before, action.after) { simpleDiff(action.before, action.after) }
                            val isExpanded = expandedDiffs[action.id] == true
                            DiffLines(lines.take(if (isExpanded) lines.size else 10))
                            if (lines.size > 10) {
                                TextButton(onClick = { expandedDiffs[action.id] = !isExpanded }) {
                                    Text(if (isExpanded) "Показать меньше" else "Показать больше", color = KvasMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BouncingDots() {
    val transition = rememberInfiniteTransition(label = "agent-dots")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 5.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = .25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420, delayMillis = index * 110), RepeatMode.Reverse),
                label = "dot-$index"
            )
            Box(Modifier.size(5.dp).alpha(alpha).background(KvasAccent, CircleShape))
        }
    }
}

@Composable
private fun ActionIcon(toolName: String) {
    val icon: ImageVector = when (toolName) {
        "read_file", "list_directory", "search_files" -> Icons.Default.Book
        "edit_file", "write_file" -> Icons.Default.Edit
        "create_directory" -> Icons.Default.Folder
        "bash" -> Icons.Default.Terminal
        "web_search", "web_fetch" -> Icons.Default.Language
        else -> Icons.Default.Code
    }
    androidx.compose.material3.Icon(icon, null, Modifier.size(14.dp), tint = KvasDim)
}

@Composable
private fun DiffLines(lines: List<DiffLine>) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(KvasSurface).padding(8.dp)) {
        lines.forEach { line ->
            Text(
                line.text,
                color = when (line.kind) {
                    '+' -> Color(0xFFB8E6C0)
                    '-' -> Color(0xFFF1A7A7)
                    else -> KvasMuted
                },
                modifier = Modifier.background(
                    when (line.kind) {
                        '+' -> Color(0x332B8A42)
                        '-' -> Color(0x33B23B3B)
                        else -> Color.Transparent
                    }
                ),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

private data class DiffLine(val kind: Char, val text: String)

private fun simpleDiff(before: String, after: String): List<DiffLine> {
    val old = before.lines()
    val new = after.lines()
    var prefix = 0
    while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.size - prefix && suffix < new.size - prefix &&
        old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
    val result = mutableListOf<DiffLine>()
    old.take(prefix).takeLast(3).forEach { result += DiffLine(' ', "  $it") }
    old.subList(prefix, old.size - suffix).forEach { result += DiffLine('-', "- $it") }
    new.subList(prefix, new.size - suffix).forEach { result += DiffLine('+', "+ $it") }
    if (suffix > 0) new.takeLast(suffix).take(3).forEach { result += DiffLine(' ', "  $it") }
    return result.ifEmpty { listOf(DiffLine(' ', "Файл без изменений")) }
}