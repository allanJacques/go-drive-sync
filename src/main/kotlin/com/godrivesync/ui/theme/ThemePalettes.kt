package com.godrivesync.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class AppTheme(val colors: ColorScheme, val palette: AppPalette)

// All themes share the same components. These small, immutable definitions are created once.
private val VioletDark = scheme(
    dark = true, accent = Color(0xFFBDA0FF), ink = Color(0xFFF2EDF8), muted = Color(0xFFBAB3C8),
    base = Color(0xFF101014), surface = Color(0xFF211D2B), raised = Color(0xFF302A3D),
    outline = Color(0xFF81768F), button = Color(0xFF7040D4), buttonText = Color(0xFFF9F3FF),
)
private val VioletLight = scheme(
    dark = false, accent = Color(0xFF6533B5), ink = Color(0xFF2D263B), muted = Color(0xFF5B4E6A),
    base = Color(0xFFECE9F1), surface = Color(0xFFF9F7FC), raised = Color(0xFFE9E3F2),
    outline = Color(0xFF81758F), button = Color(0xFF7040C5), buttonText = Color(0xFFFFF7FF),
)

private val Themes = mapOf(
    AppStyle.OBSIDIAN to AppTheme(VioletDark, AppPalette(
        backgroundStart = Color(0xFF211B30), backgroundEnd = Color(0xFF101114),
        panel = Color(0xFF202126), inset = Color(0xFF27282F), border = Color(0xFF393940),
        selected = Color(0xFF39304F), cornerRadius = 12.dp,
    )),
    AppStyle.AURORA to AppTheme(VioletDark, AppPalette(
        backgroundStart = Color(0xFF38205D), backgroundEnd = Color(0xFF08090E),
        panel = Color(0xB31C182B), inset = Color(0x6628243D), border = Color(0x33C3A6FF),
        selected = Color(0x554F3384), cornerRadius = 20.dp,
    )),
    AppStyle.TIDE to AppTheme(scheme(
        dark = true, accent = Color(0xFF74D8CD), ink = Color(0xFFE0F3EF), muted = Color(0xFFA3C5C2),
        base = Color(0xFF081413), surface = Color(0xFF122B2C), raised = Color(0xFF1A393A),
        outline = Color(0xFF6A9996), button = Color(0xFF126F6B), buttonText = Color(0xFFE7FFFA),
    ), AppPalette(
        backgroundStart = Color(0xFF124344), backgroundEnd = Color(0xFF071314),
        panel = Color(0xD912292B), inset = Color(0x99213E40), border = Color(0x405CB3AC),
        selected = Color(0xFF205451), cornerRadius = 20.dp,
    )),
    AppStyle.OBSIDIAN_LIGHT to AppTheme(VioletLight, AppPalette(
        backgroundStart = Color(0xFFE1DCEA), backgroundEnd = Color(0xFFEEF0F4),
        panel = Color(0xFFF6F6F9), inset = Color(0xFFECECF2), border = Color(0xFFD4CFDE),
        selected = Color(0xFFE2D7F5), cornerRadius = 12.dp,
    )),
    AppStyle.AURORA_LIGHT to AppTheme(VioletLight, AppPalette(
        backgroundStart = Color(0xFFD7C2F3), backgroundEnd = Color(0xFFF1EAF8),
        panel = Color(0xB3FCF6FF), inset = Color(0x80E8DAF7), border = Color(0x66937AB1),
        selected = Color(0xFFDCC7F2), cornerRadius = 20.dp,
    )),
    AppStyle.TIDE_LIGHT to AppTheme(scheme(
        dark = false, accent = Color(0xFF16675F), ink = Color(0xFF173C3A), muted = Color(0xFF355B56),
        base = Color(0xFFDCEFEA), surface = Color(0xFFF0F9F5), raised = Color(0xFFD6EAE3),
        outline = Color(0xFF628E87), button = Color(0xFF146B64), buttonText = Color(0xFFEDFFF8),
    ), AppPalette(
        backgroundStart = Color(0xFFB2DED5), backgroundEnd = Color(0xFFE5F2EC),
        panel = Color(0xD9F0FAF6), inset = Color(0x99D0E8E0), border = Color(0x8064998D),
        selected = Color(0xFFBBDED2), cornerRadius = 20.dp,
    )),
    AppStyle.TANGERINE to AppTheme(scheme(
        dark = false, accent = Color(0xFF70290F), ink = Color(0xFF542443), muted = Color(0xFF62223F),
        base = Color(0xFFFFAD68), surface = Color(0xFFF6BC83), raised = Color(0xFFF5A36E),
        outline = Color(0xFFA34C46), button = Color(0xFFA44213), buttonText = Color(0xFFFFE0A8),
        secondary = Color(0xFF742F74), tertiary = Color(0xFF17695D),
    ), AppPalette(
        backgroundStart = Color(0xFFFFA34F), backgroundEnd = Color(0xFFE19BB5),
        panel = Color(0xFFF6BC83), inset = Color(0xFFFFD299), border = Color(0xFFCC7653),
        selected = Color(0xFFF29868), cornerRadius = 20.dp,
    )),
)

fun appTheme(style: AppStyle): AppTheme = Themes.getValue(style)

private fun scheme(
    dark: Boolean,
    accent: Color,
    ink: Color,
    muted: Color,
    base: Color,
    surface: Color,
    raised: Color,
    outline: Color,
    button: Color,
    buttonText: Color,
    secondary: Color = accent,
    tertiary: Color = accent,
): ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent, onPrimary = base, primaryContainer = button, onPrimaryContainer = buttonText,
    secondary = secondary, onSecondary = base, secondaryContainer = raised, onSecondaryContainer = ink,
    tertiary = tertiary, onTertiary = base, tertiaryContainer = raised, onTertiaryContainer = ink,
    background = base, onBackground = ink,
    surface = surface, onSurface = ink, surfaceVariant = raised, onSurfaceVariant = muted,
    surfaceTint = accent, surfaceBright = raised, surfaceDim = base,
    surfaceContainer = surface, surfaceContainerLow = surface, surfaceContainerLowest = base,
    surfaceContainerHigh = raised, surfaceContainerHighest = raised,
    outline = outline, outlineVariant = outline.copy(alpha = .5f), scrim = ink,
    inverseSurface = ink, inverseOnSurface = base, inversePrimary = button,
    error = if (dark) Color(0xFFFFB4AB) else Color(0xFF922C44), onError = base,
    errorContainer = if (dark) Color(0xFF5B2431) else Color(0xFFF4C2BF),
    onErrorContainer = if (dark) Color(0xFFFFD9DF) else Color(0xFF662039),
    primaryFixed = raised, primaryFixedDim = raised, onPrimaryFixed = ink, onPrimaryFixedVariant = muted,
    secondaryFixed = raised, secondaryFixedDim = raised, onSecondaryFixed = ink, onSecondaryFixedVariant = muted,
    tertiaryFixed = raised, tertiaryFixedDim = raised, onTertiaryFixed = ink, onTertiaryFixedVariant = muted,
)
