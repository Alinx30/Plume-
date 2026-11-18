package com.alinx.plume.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF2F4B8C)
val Amber = Color(0xFFF2C14E)
val Desk = Color(0xFFE9E6DF)
val DeskDark = Color(0xFF2A2B30)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3F7),
    onPrimaryContainer = Color(0xFF14254F),
    secondary = Color(0xFF8A6A1E),
    secondaryContainer = Color(0xFFFBE7B5),
    background = Color(0xFFF6F4EF),
    surface = Color(0xFFFBFAF7),
    surfaceVariant = Color(0xFFEDEAE3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC2F5),
    onPrimary = Color(0xFF14254F),
    primaryContainer = Color(0xFF2F4B8C),
    secondary = Amber,
    background = Color(0xFF1C1D21),
    surface = Color(0xFF232429),
)

@Composable
fun PlumeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
