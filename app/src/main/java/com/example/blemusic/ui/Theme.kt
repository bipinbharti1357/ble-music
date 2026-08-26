package com.example.blemusic.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Ink = Color(0xFF16202B)
private val Signal = Color(0xFF2F81F7)
private val SignalDark = Color(0xFF8AB8FF)

private val LightColors = lightColorScheme(
    primary = Signal,
    onPrimary = Color.White,
    secondary = Ink,
    background = Color(0xFFF7F9FC),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = SignalDark,
    onPrimary = Ink,
    secondary = Color(0xFFB6C4D4),
    background = Ink,
    surface = Color(0xFF1E2A38)
)

@Composable
fun BleMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
