package com.godrivesync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godrivesync.ui.theme.LocalAppPalette

@Composable
fun AppPanel(
    modifier: Modifier = Modifier,
    inset: Boolean = false,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(palette.cornerRadius),
        color = if (inset) palette.inset else palette.panel,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
