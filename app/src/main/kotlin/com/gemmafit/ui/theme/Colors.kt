package com.gemmafit.ui.theme

import androidx.compose.ui.graphics.Color

// ── Universal Semantic Colors (theme-agnostic) ──────────────────────
val Green = Color(0xFF00E676)
val Red = Color(0xFFFF1744)
val Orange = Color(0xFFFF9100)
val Blue = Color(0xFF448AFF)
val PurpleHighlight = Color(0xFFE040FB)
val PurpleSecondary = Color(0xFF7C4DFF)

// ── Dark Theme Palette ──────────────────────────────────────────────
val DarkBackground = Color(0xFF121212)
val DarkBackgroundGradientEnd = Color(0xFF0A0F0E)
val DarkSurface = Color(0xFF1E1E1E)
val DarkOverlayPanel = Color(0xD02C2C2C)
val DarkTextPrimary = Color(0xFFFFFFFF)
val DarkTextSecondary = Color(0xFFB0B0B0)
val DarkTextHint = Color(0xFF757575)

// ── Light Theme Palette ─────────────────────────────────────────────
val LightBackground = Color(0xFFF5F5F7)
val LightBackgroundGradientEnd = Color(0xFFE8ECEA)
val LightSurface = Color(0xFFFFFFFF)
val LightOverlayPanel = Color(0xF0F0F0F0)
val LightTextPrimary = Color(0xFF1A1A1A)
val LightTextSecondary = Color(0xFF666666)
val LightTextHint = Color(0xFF999999)

// ── Theme state (set by GemmaFitTheme) ──────────────────────────────
var IsDarkTheme = true

// ── Dynamic aliases (auto-switch based on IsDarkTheme) ──────────────
val Background: Color get() = if (IsDarkTheme) DarkBackground else LightBackground
val BackgroundGradientEnd: Color get() = if (IsDarkTheme) DarkBackgroundGradientEnd else LightBackgroundGradientEnd
val SurfaceColor: Color get() = if (IsDarkTheme) DarkSurface else LightSurface
val OverlayPanel: Color get() = if (IsDarkTheme) DarkOverlayPanel else LightOverlayPanel
val TextPrimary: Color get() = if (IsDarkTheme) DarkTextPrimary else LightTextPrimary
val TextSecondary: Color get() = if (IsDarkTheme) DarkTextSecondary else LightTextSecondary
val TextHint: Color get() = if (IsDarkTheme) DarkTextHint else LightTextHint

// ── Form Score ──────────────────────────────────────────────────────
val ScoreHigh = Green
val ScoreMid = Color(0xFFFFAB00)
val ScoreLow = Red

// ── Skeleton Overlay ────────────────────────────────────────────────
val SkeletonNormal = Color(0x9900E676)
val SkeletonViolation = Color(0xE6FF1744)
val SkeletonJoint = Color(0xCC00E676)
val CorrectionArrow = Color(0xD9448AFF)
val AngleArc = Color(0x80FFFFFF)
