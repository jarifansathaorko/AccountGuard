package dev.accountguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
// ACCOUNTGUARD DESIGN SYSTEM
// Deep navy + electric cyan + vibrant accent palette
// Glassmorphism-inspired dark theme
// ─────────────────────────────────────────────────────────────

object GuardColors {
    // Navy & deep backgrounds
    val DeepNavy     = Color(0xFF050D1A)
    val NavyDark     = Color(0xFF0A1628)
    val NavyMid      = Color(0xFF0F2040)
    val NavySurface  = Color(0xFF132848)

    // Glass surfaces
    val GlassCard    = Color(0xFF1A3050)
    val GlassCardAlt = Color(0xFF152B45)
    val GlassBorder  = Color(0x33FFFFFF)

    // Electric cyan / primary
    val CyanVibrant  = Color(0xFF00D4FF)
    val CyanMid      = Color(0xFF00AACF)
    val CyanDim      = Color(0x3300D4FF)

    // Accent / secondary
    val PurpleAccent = Color(0xFF7B61FF)
    val PurpleDim    = Color(0x337B61FF)

    // Status colors
    val GreenSuccess = Color(0xFF00E676)
    val GreenDim     = Color(0x3300E676)
    val RedError     = Color(0xFFFF4B4B)
    val RedDim       = Color(0x33FF4B4B)
    val AmberWarning = Color(0xFFFFB300)
    val AmberDim     = Color(0x33FFB300)
    val GrayDisabled = Color(0xFF4A5568)

    // Text
    val TextPrimary   = Color(0xFFEDF2FF)
    val TextSecondary = Color(0xFF90A4B7)
    val TextMuted     = Color(0xFF4A6080)

    // VISIBLE / HIDDEN indicators
    val StateVisible  = Color(0xFF00E676)
    val StateHidden   = Color(0xFFFF4B4B)
    val StatePartial  = Color(0xFFFFB300)
    val StateExperimental = Color(0xFFFF8C42)
}

private val darkColorScheme = darkColorScheme(
    primary            = GuardColors.CyanVibrant,
    onPrimary          = GuardColors.DeepNavy,
    primaryContainer   = GuardColors.NavyMid,
    onPrimaryContainer = GuardColors.CyanVibrant,

    secondary          = GuardColors.PurpleAccent,
    onSecondary        = Color.White,
    secondaryContainer = GuardColors.PurpleDim,
    onSecondaryContainer = GuardColors.PurpleAccent,

    background         = GuardColors.DeepNavy,
    onBackground       = GuardColors.TextPrimary,

    surface            = GuardColors.NavyDark,
    onSurface          = GuardColors.TextPrimary,
    surfaceVariant     = GuardColors.GlassCard,
    onSurfaceVariant   = GuardColors.TextSecondary,

    error              = GuardColors.RedError,
    onError            = Color.White,
    errorContainer     = GuardColors.RedDim,

    outline            = GuardColors.GlassBorder,
    outlineVariant     = GuardColors.NavySurface,

    inverseSurface     = GuardColors.TextPrimary,
    inverseOnSurface   = GuardColors.DeepNavy,
    inversePrimary     = GuardColors.CyanMid,

    scrim              = Color(0xAA000000),
    surfaceTint        = GuardColors.CyanVibrant,
)

@Composable
fun AccountGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = GuardTypography,
        content = content
    )
}
