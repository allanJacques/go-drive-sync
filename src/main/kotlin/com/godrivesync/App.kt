package com.godrivesync

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.components.AppPanel
import com.godrivesync.ui.components.Sidebar
import com.godrivesync.ui.components.AppIcons
import com.godrivesync.ui.navigation.AppDestination
import com.godrivesync.ui.screens.DestinationScreen
import com.godrivesync.ui.theme.AppStyle
import com.godrivesync.ui.theme.GoDriveSyncTheme
import com.godrivesync.ui.theme.LocalAppPalette

@Composable
fun App(
    style: AppStyle,
    onStyleChange: (AppStyle) -> Unit,
    icon: Painter? = null,
    themeSaveFailed: Boolean = false,
    onRetryThemeSave: () -> Unit = {},
) {
    var destination by remember { mutableStateOf(AppDestination.DASHBOARD) }

    GoDriveSyncTheme(style) {
        val palette = LocalAppPalette.current
        Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(
                modifier = Modifier.fillMaxSize().drawWithCache {
                    // Cache the brush until the palette or window size changes; no animated blur.
                    val background = Brush.linearGradient(
                        colors = listOf(palette.backgroundStart, palette.backgroundEnd),
                        start = Offset.Zero, end = Offset(size.width * .85f, size.height),
                    )
                    onDrawBehind { drawRect(background) }
                }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(52.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Go Drive Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Seu mundo, em sincronia.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { destination = AppDestination.SETTINGS }) {
                        Icon(AppIcons.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
                        Text("Tema: ${style.label}", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Sidebar(destination, { destination = it })
                    AppPanel(Modifier.weight(1f).fillMaxSize(), padding = PaddingValues(20.dp)) {
                        Text(destination.label, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Text(destination.subtitle, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        key(destination) {
                            val scrollState = rememberScrollState()
                            Box(Modifier.weight(1f)) {
                                Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 12.dp, bottom = 4.dp)) {
                                    DestinationScreen(destination, style, onStyleChange, themeSaveFailed, onRetryThemeSave) {
                                        destination = it
                                    }
                                }
                                if (scrollState.maxValue > 0) {
                                    VerticalScrollbar(
                                        adapter = rememberScrollbarAdapter(scrollState),
                                        modifier = Modifier.align(Alignment.CenterEnd),
                                        style = LocalScrollbarStyle.current.copy(
                                            thickness = 4.dp,
                                            unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f),
                                            hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = .7f),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    Text("Nenhuma sincronização em andamento", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("Configuração inicial", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
