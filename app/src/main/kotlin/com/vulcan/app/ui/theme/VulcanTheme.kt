package com.vulcan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── VULCAN COLOR PALETTE ─────────────────────────────────────────────────────
// Defined in Part I of the Masterplan. Every color has meaning.

object VulcanColors {
    // Core brand
    val ForgeOrange    = Color(0xFFFF6B35)   // Primary — forge fire
    val DeepForge      = Color(0xFF1A1A2E)   // Background — near-black
    val DarkSteel      = Color(0xFF16213E)   // Surface — dark steel
    val HotMetal       = Color(0xFFE94560)   // Error/Critical — hot metal
    val CoolingForge   = Color(0xFF0F9B58)   // Success — cooling green
    val Ash            = Color(0xFFA0A0B0)   // Neutral text

    // Derived
    val ForgeOrangeLight = Color(0xFFFF8C5C)
    val DeepForgeDark    = Color(0xFF0D0D1A)
    val SteelBright      = Color(0xFF2A2A4E)
    val SteelMid         = Color(0xFF1E1E3A)

    // Status colors
    val Running  = CoolingForge
    val Stopped  = Color(0xFF555568)
    val Starting = Color(0xFFFFB347)
    val Error    = HotMetal
}

// ─── MATERIAL 3 COLOR SCHEMES ────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary          = VulcanColors.ForgeOrange,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFF7A3010),
    onPrimaryContainer = Color(0xFFFFDBCA),

    secondary        = VulcanColors.Ash,
    onSecondary      = VulcanColors.DeepForge,

    background       = VulcanColors.DeepForge,
    onBackground     = Color(0xFFE4E1E6),

    surface          = VulcanColors.DarkSteel,
    onSurface        = Color(0xFFE4E1E6),
    surfaceVariant   = VulcanColors.SteelMid,
    onSurfaceVariant = VulcanColors.Ash,

    error            = VulcanColors.HotMetal,
    onError          = Color.White,

    outline          = VulcanColors.SteelBright,
    outlineVariant   = Color(0xFF3A3A5E),

    inverseSurface   = Color(0xFFE4E1E6),
    inverseOnSurface = VulcanColors.DeepForge
)

// AMOLED pure-black variant (saves battery on OLED screens)
private val AmoledColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface    = Color(0xFF0A0A0A)
)

private val LightColorScheme = lightColorScheme(
    primary          = VulcanColors.ForgeOrange,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF3A1200),

    background       = Color(0xFFF8F0ED),
    onBackground     = Color(0xFF1C1B1F),

    surface          = Color.White,
    onSurface        = Color(0xFF1C1B1F),
    surfaceVariant   = Color(0xFFF3ECE9),
    onSurfaceVariant = Color(0xFF52443E),

    error            = VulcanColors.HotMetal
)

// ─── TYPOGRAPHY ───────────────────────────────────────────────────────────────
// Inter Variable — clean, technical, readable at small sizes

val VulcanTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize   = 57.sp,
        lineHeight = 64.sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize   = 45.sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 24.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 16.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp
    )
)

// ─── THEME COMPOSABLE ─────────────────────────────────────────────────────────

enum class VulcanThemeMode { LIGHT, DARK, AMOLED, SYSTEM }

@Composable
fun VulcanTheme(
    themeMode: VulcanThemeMode = VulcanThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        VulcanThemeMode.LIGHT  -> LightColorScheme
        VulcanThemeMode.DARK   -> DarkColorScheme
        VulcanThemeMode.AMOLED -> AmoledColorScheme
        VulcanThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = VulcanTypography,
        content     = content
    )
}

// ─── DESIGN TOKENS ────────────────────────────────────────────────────────────

object VulcanDimens {
    val paddingXS   = androidx.compose.ui.unit.dp *  4
    val paddingS    = androidx.compose.ui.unit.dp *  8
    val paddingM    = androidx.compose.ui.unit.dp * 16
    val paddingL    = androidx.compose.ui.unit.dp * 24
    val paddingXL   = androidx.compose.ui.unit.dp * 32

    val radiusS     = androidx.compose.ui.unit.dp *  8
    val radiusM     = androidx.compose.ui.unit.dp * 12
    val radiusL     = androidx.compose.ui.unit.dp * 16
    val radiusXL    = androidx.compose.ui.unit.dp * 24

    val cardElevation = androidx.compose.ui.unit.dp * 2
    val appIconSize   = androidx.compose.ui.unit.dp * 56
    val miniIconSize  = androidx.compose.ui.unit.dp * 40
}
