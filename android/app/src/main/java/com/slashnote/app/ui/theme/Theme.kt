package com.slashnote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    background = DarkBackground,
    surface = DarkSurfaceSecondary,
    surfaceVariant = DarkSurfaceCard,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextMuted,
    primary = DarkAccent,
    onPrimary = DarkSurfaceSecondary,
    outline = DarkBorder
)

private val LightColorScheme = lightColorScheme(
    background = LightBackground,
    surface = LightSurfaceSecondary,
    surfaceVariant = LightSurfaceCard,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextMuted,
    primary = LightAccent,
    onPrimary = LightSurfaceSecondary,
    outline = LightBorder
)

@Composable
fun SlashNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
