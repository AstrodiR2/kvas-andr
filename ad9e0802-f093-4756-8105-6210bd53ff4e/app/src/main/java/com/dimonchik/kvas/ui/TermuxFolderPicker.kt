package com.dimonchik.kvas.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dimonchik.kvas.onboarding.TermuxBridge
import kotlinx.coroutines.delay
import org.json.JSONArray

private data class TermuxEntry(val name: String, val directory: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxFolderPicker(
    onDismiss: () -> Unit,
    onUseFolder: (String) -> Unit
) {
    val context = LocalContext.current
    var path by remember { mutableStateOf(TermuxBridge.TERMUX_HOME) }
    var filter by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<TermuxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableLongStateOf(0L) }

    LaunchedEffect(path, refreshKey) {
        entries = emptyList()
        error = ""
        if (!TermuxBridge.hasPermission(context)) {
            error = "Нужно разрешить Квасу запуск команд Termux в настройках Android."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val requestId = TermuxBridge.listDirectory(context, path)
        if (requestId == null) {
            error = "Не удалось запросить список папок Termux."
            loading = false
            return@LaunchedEffect
        }
        val prefs = context.getSharedPreferences(TermuxBridge.RPC_PREFS, Context.MODE_PRIVATE)
        val deadline = System.currentTimeMillis() + 20_000
        while (!prefs.getBoolean("$requestId.finished", false) && System.currentTimeMillis() < deadline) {
            delay(250)
        }
        if (!prefs.getBoolean("$requestId.finished", false)) {
            error = "Termux не ответил. Проверьте, что он установлен и разрешён запуск внешних команд."
        } else {
            val exit = prefs.getInt("$requestId.exit", -1)
            val output = prefs.getString("$requestId.stdout", "").orEmpty()
            val stderr = prefs.getString("$requestId.stderr", "").orEmpty()
            if (exit != 0) {
                error = stderr.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "Не удалось прочитать папку (код $exit)."
            } else {
                entries = runCatching {
                    val json = JSONArray(output.trim().lineSequence().last { it.startsWith("[") })
                    buildList {
                        for (i in 0 until json.length()) {
                            val row = json.optJSONObject(i) ?: continue
                            val name = row.optString("name")
                            if (name.isNotBlank() && name != "." && name != "..") {
                                add(TermuxEntry(name, row.optBoolean("dir")))
                            }
                        }
                    }.sortedWith(compareByDescending<TermuxEntry> { it.directory }.thenBy { it.name.lowercase() })
                }.getOrElse { error = "Получен некорректный ответ Termux."; emptyList() }
            }
        }
        prefs.edit().remove("$requestId.finished").remove("$requestId.exit")
            .remove("$requestId.stdout").remove("$requestId.stderr").apply()
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KvasSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (path != TermuxBridge.TERMUX_HOME) {
                            path = path.substringBeforeLast('/').ifBlank { TermuxBridge.TERMUX_HOME }
                        }
                    },
                    enabled = path != TermuxBridge.TERMUX_HOME
                ) { Icon(Icons.Default.ArrowBack, "На уровень выше", tint = KvasText) }
                Column(Modifier.weight(1f)) {
                    Text("Папка для чата", color = KvasText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(path.removePrefix(TermuxBridge.TERMUX_HOME).ifEmpty { "~" }, color = KvasDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, "Обновить", tint = KvasMuted) }
            }
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .then(Modifier),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = KvasText, fontSize = 14.sp),
                decorationBox = { inner ->
                    Surface(color = KvasSurface2, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Text("⌕  ", color = KvasDim)
                            if (filter.isEmpty()) Text("Поиск папок и файлов", color = KvasDim)
                            inner()
                        }
                    }
                }
            )
            if (loading) {
                Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = KvasAccent, strokeWidth = 2.dp)
                    Text("  Загружаю содержимое Termux…", color = KvasMuted, fontSize = 13.sp)
                }
            } else if (error.isNotBlank()) {
                Text(error, Modifier.padding(vertical = 12.dp), color = KvasMuted, fontSize = 13.sp)
                if (!TermuxBridge.hasPermission(context)) {
                    TextButton(onClick = { TermuxBridge.openRunCommandSettings(context) }) {
                        Icon(Icons.Default.Settings, null)
                        Text("Открыть настройки Android")
                    }
                } else TextButton(onClick = { refreshKey++ }) { Text("Повторить") }
            } else {
                val visible = entries.filter { it.name.contains(filter, ignoreCase = true) }
                if (path != TermuxBridge.TERMUX_HOME) {
                    ListItem(
                        headlineContent = { Text("..", color = KvasText) },
                        leadingContent = { Icon(Icons.Default.Folder, null, tint = KvasAccent) },
                        modifier = Modifier.clickable { path = path.substringBeforeLast('/').ifBlank { TermuxBridge.TERMUX_HOME } },
                        colors = ListItemDefaults.colors(containerColor = KvasSurface)
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                    items(visible, key = { it.name }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, color = KvasText, maxLines = 1) },
                            leadingContent = {
                                Icon(
                                    if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    null,
                                    tint = if (entry.directory) KvasAccent else KvasDim
                                )
                            },
                            modifier = Modifier.clickable(enabled = entry.directory) {
                                path = "$path/${entry.name}".replace("//", "/")
                                filter = ""
                            },
                            colors = ListItemDefaults.colors(containerColor = KvasSurface)
                        )
                        HorizontalDivider(color = KvasBorder)
                    }
                }
            }
            Button(
                onClick = { onUseFolder(path) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
                enabled = !loading && error.isBlank()
            ) {
                Text("Использовать эту папку")
            }
        }
    }
}