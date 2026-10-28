package com.dimonchik.kvas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dimonchik.kvas.data.AgentFile

@Composable
fun AgentFileCard(file: AgentFile, onClick: () -> Unit) {
    val extension = file.name.substringAfterLast('.', "FILE").uppercase().take(8)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = KvasSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Description, null, tint = KvasAccent, modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, color = KvasText, fontSize = 13.sp, maxLines = 1)
                Text("${file.path} · $extension · ${file.bytes.size} байт", color = KvasDim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun AgentFileViewer(
    file: AgentFile,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onSaveMarkdown: () -> Unit,
    onSavePdf: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(Modifier.fillMaxSize().background(KvasBackground)) {
            Row(Modifier.fillMaxWidth().background(KvasSurface).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть", tint = KvasText) }
                Text(file.name, Modifier.weight(1f), color = KvasText, maxLines = 1, fontWeight = FontWeight.Medium)
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Меню файла", tint = KvasText) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Копировать") }, onClick = { menuExpanded = false; onCopy() })
                        DropdownMenuItem(text = { Text("Скачать") }, onClick = { menuExpanded = false; onDownload() })
                        DropdownMenuItem(text = { Text("Сохранить как MD") }, onClick = { menuExpanded = false; onSaveMarkdown() })
                        DropdownMenuItem(text = { Text("Сохранить как PDF") }, onClick = { menuExpanded = false; onSavePdf() })
                    }
                }
            }
            val content = file.textContent
            if (content == null) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Description, null, tint = KvasAccent, modifier = Modifier.size(48.dp))
                    Text(file.name, Modifier.padding(top = 12.dp), color = KvasText)
                    Text("Предпросмотр этого типа файла недоступен. Его можно скачать.", Modifier.padding(top = 6.dp), color = KvasDim, fontSize = 13.sp)
                }
            } else if (file.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                    MarkdownContent(content)
                }
            } else {
                val language = file.name.substringAfterLast('.', "")
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                    MarkdownContent("```$language\n$content\n```")
                }
            }
        }
    }
}