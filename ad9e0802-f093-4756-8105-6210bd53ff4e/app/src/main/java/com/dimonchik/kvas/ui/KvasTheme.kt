package com.dimonchik.kvas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KvasBackground = Color(0xFF131312)
val KvasSurface = Color(0xFF1A1A19)
val KvasSurface2 = Color(0xFF232322)
val KvasBorder = Color(0xFF2A2A28)
val KvasText = Color(0xFFECECEA)
val KvasMuted = Color(0xFFA3A29C)
val KvasDim = Color(0xFF77766F)
val KvasAccent = Color(0xFFD97757) // Exact clay accent from the supplied HTML.

@Composable
fun KvasTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = KvasAccent,
        onPrimary = Color(0xFF18131F),
        background = KvasBackground,
        onBackground = KvasText,
        surface = KvasSurface,
        onSurface = KvasText,
        secondary = KvasMuted,
        outline = KvasBorder
    )
    MaterialTheme(colorScheme = colors, content = content)
}
