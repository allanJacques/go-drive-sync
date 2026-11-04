package com.godrivesync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.theme.AppStyle
import com.godrivesync.ui.theme.LocalAppPalette
import com.godrivesync.ui.theme.appTheme

@Composable
fun SettingsScreen(
    style: AppStyle,
    onStyleChange: (AppStyle) -> Unit,
    themeSaveFailed: Boolean,
    onRetryThemeSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aparência", style = MaterialTheme.typography.titleMedium)
        Text(
            "Escolha seu tema. A mudança é imediata e a preferência é salva neste computador.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (themeSaveFailed) {
            Text("O tema foi aplicado, mas não foi possível salvar a preferência.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetryThemeSave) { Text("Tentar salvar novamente") }
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStyle.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { option ->
                        ThemeOption(option, option == style, { onStyleChange(option) }, Modifier.weight(1f))
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(style: AppStyle, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalAppPalette.current
    Surface(
        selected = selected, onClick = onSelect,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.selected else palette.inset,
        contentColor = colors.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else palette.border),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeSwatch(style, Modifier.fillMaxWidth().height(54.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(style.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (selected) Box(Modifier.size(7.dp).background(colors.primary, CircleShape))
            }
            Text(style.description, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, minLines = 2)
        }
    }
}

/** Lightweight preview built from the very same tokens as the full interface. */
@Composable
private fun ThemeSwatch(style: AppStyle, modifier: Modifier) {
    val theme = appTheme(style)
    val brush = remember(style) { Brush.linearGradient(listOf(theme.palette.backgroundStart, theme.palette.backgroundEnd)) }
    Canvas(modifier) {
        val radius = CornerRadius(6.dp.toPx())
        val w = size.width
        val h = size.height
        drawRoundRect(brush, cornerRadius = radius)
        drawRoundRect(theme.palette.panel, Offset(w * .04f, h * .13f), Size(w * .22f, h * .74f), radius)
        drawRoundRect(theme.palette.panel, Offset(w * .30f, h * .13f), Size(w * .66f, h * .74f), radius)
        drawRoundRect(theme.palette.selected, Offset(w * .06f, h * .26f), Size(w * .18f, h * .14f), radius)
        drawRoundRect(theme.colors.onSurface.copy(alpha = .6f), Offset(w * .35f, h * .27f), Size(w * .30f, h * .06f), radius)
        drawRoundRect(theme.palette.inset, Offset(w * .35f, h * .43f), Size(w * .56f, h * .17f), radius)
        drawRoundRect(theme.colors.primaryContainer, Offset(w * .35f, h * .67f), Size(w * .20f, h * .09f), radius)
    }
}
