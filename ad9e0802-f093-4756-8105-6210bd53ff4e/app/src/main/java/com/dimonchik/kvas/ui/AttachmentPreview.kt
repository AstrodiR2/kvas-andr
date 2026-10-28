package com.dimonchik.kvas.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AttachmentPreviewDialog(uri: Uri, mimeType: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(24.dp), color = KvasSurface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), color = KvasText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть", tint = KvasMuted) }
                }
                when {
                    mimeType.startsWith("image/") -> ImagePreview(context, uri)
                    mimeType.startsWith("video/") -> AndroidView(
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                setVideoURI(uri)
                                setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)
                    )
                    mimeType.startsWith("audio/") -> AudioPreview(uri)
                    else -> Text("Предпросмотр этого типа файла недоступен.", color = KvasMuted)
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(context: Context, uri: Uri) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    if (image != null) Image(image!!, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp))
    else Box(Modifier.fillMaxWidth().height(180.dp).background(KvasSurface2, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = KvasAccent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun AudioPreview(uri: Uri) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    DisposableEffect(uri) {
        val created = MediaPlayer()
        try {
            created.setDataSource(context, uri)
            created.setOnPreparedListener { player = it; prepared = true }
            created.setOnCompletionListener { playing = false }
            created.prepareAsync()
        } catch (_: Exception) {
            created.release()
        }
        onDispose { runCatching { created.release() } }
    }
    Row(Modifier.fillMaxWidth().background(KvasSurface2, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = prepared, onClick = {
            val current = player ?: return@IconButton
            if (current.isPlaying) { current.pause(); playing = false } else { current.start(); playing = true }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Пауза" else "Воспроизвести", tint = KvasText) }
        Text(if (prepared) "Аудио готово к воспроизведению" else "Загрузка аудио…", color = KvasMuted)
    }
}
