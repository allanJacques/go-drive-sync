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

/**
 * Cores específicas do layout, além dos papéis de cor do Material 3.
 * backgroundStart/End: gradiente; panel/inset: painéis; border: borda; selected: seleção.
 * cornerRadius usa Dp, unidade independente da densidade da tela.
 * @Immutable declara um contrato ao Compose: estas propriedades não mudam após a criação.
 */
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

// CompositionLocal distribui a paleta aos descendentes sem passá-la em cada chamada.
// O valor padrão é Aurora; GoDriveSyncTheme fornece o valor escolhido para sua subárvore.
val LocalAppPalette = staticCompositionLocalOf { appTheme(AppStyle.AURORA).palette }

// Tipografia compartilhada: sp respeita escala de fonte; dp é usado em espaçamentos.
// copy cria novos estilos a partir dos padrões Material, preservando os campos não alterados.
private val BaseTypography = Typography()
private val AppTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontSize = 27.sp, lineHeight = 34.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 13.sp, lineHeight = 20.sp),
)

/**
 * Fornece paleta, tipografia e formatos a todo content, como um tema aplicado a uma subárvore.
 * MaterialTheme é lido pelos componentes descendentes; mudar style atualiza seus valores.
 * content é uma lambda composable que descreve a interface envolvida por este tema.
 */
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
