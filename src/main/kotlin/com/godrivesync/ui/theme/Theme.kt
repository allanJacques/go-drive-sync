package com.godrivesync.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class AppPalette(
    val backgroundStart: Color,
    val backgroundEnd: Color,
    val panel: Color,
    val inset: Color,
    val border: Color,
    val selected: Color,
    val cornerRadius: Dp,
)

val LocalAppPalette = staticCompositionLocalOf { appTheme(AppStyle.AURORA).palette }

private val BaseTypography = Typography()
private val AppTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontSize = 27.sp, lineHeight = 34.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 13.sp, lineHeight = 20.sp),
)

@Composable
fun GoDriveSyncTheme(style: AppStyle, content: @Composable () -> Unit) {
    val theme = appTheme(style)
    CompositionLocalProvider(LocalAppPalette provides theme.palette) {
        MaterialTheme(
            colorScheme = theme.colors,
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(theme.palette.cornerRadius),
            ),
            content = content,
        )
    }
}
