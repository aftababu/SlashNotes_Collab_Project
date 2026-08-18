package com.slashnote.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark Mode Palette (Charcoal Zinc)
val DarkBackground = Color(0xFF141416)
val DarkSurface = Color(0xFF1C1C1F)
val DarkSurfaceElevated = Color(0xFF27272A)
val DarkBorder = Color(0xFF2E2E33)
val DarkTextPrimary = Color(0xFFEDEDED)
val DarkTextSecondary = Color(0xFFA1A1AA)

// Light Mode Palette (Warm Minimalist)
val LightBackground = Color(0xFFF7F7F8)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFECECEE)
val LightBorder = Color(0xFFE5E5E7)
val LightTextPrimary = Color(0xFF111827)
val LightTextSecondary = Color(0xFF6B7280)

// Aliases for backward compatibility
val DarkSurfaceSecondary = DarkBackground
val DarkSurfaceCard = DarkSurface
val DarkTextMuted = DarkTextSecondary
val DarkButtonBg = DarkSurfaceElevated
val DarkButtonBorder = Color(0xFF3F3F46)
val DarkButtonText = DarkTextPrimary
val DarkCodeHighlight = DarkTextPrimary
val DarkCodeBg = Color(0xFF242429)

val LightSurfaceSecondary = LightBackground
val LightSurfaceCard = LightSurface
val LightTextMuted = LightTextSecondary
val LightButtonBg = LightTextPrimary
val LightButtonBorder = LightBorder
val LightButtonText = Color(0xFFFFFFFF)
val LightCodeHighlight = LightTextPrimary
val LightCodeBg = Color(0x0F18181B)

val StitchBackground = DarkBackground
val StitchSurfaceSecondary = DarkBackground
val StitchCardBg = DarkSurface
val StitchCardSelected = Color(0xFF26262B)
val StitchTextPrimary = DarkTextPrimary
val StitchTextMuted = DarkTextSecondary
val StitchBorder = DarkBorder
val StitchAccentCoral = DarkTextPrimary
val StitchAccentCoralLight = Color(0xFFFAFAF9)
val StitchCodeHighlight = DarkCodeHighlight
val StitchCodeBg = DarkCodeBg
val DarkAccent = DarkSurfaceElevated
val LightAccent = LightTextPrimary
