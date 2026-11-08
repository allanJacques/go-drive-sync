package com.godrivesync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.navigation.AppDestination
import com.godrivesync.ui.theme.LocalAppPalette

/**
 * Menu lateral sem estado próprio de navegação: recebe selected e comunica cliques.
 * @param selected destino ativo, usado para cor, peso do texto e indicador visual.
 * @param onNavigate callback equivalente à intenção de um ActionListener; App altera o estado.
 * @param modifier permite ao pai ajustar o layout sem modificar este componente.
 */
@Composable
fun Sidebar(
    selected: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    AppPanel(modifier.fillMaxHeight().width(212.dp), padding = PaddingValues(12.dp)) {
        Eyebrow("ESPAÇO DE TRABALHO", Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp))
        // Só a lista rola quando a altura diminui; o rodapé permanece separado.
        // selectableGroup e Role.Tab identificam o menu para acessibilidade e testes.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // O enum é a fonte única dos nomes, ícones e destinos do menu.
            AppDestination.entries.forEach { destination ->
                val active = selected == destination
                Surface(
                    selected = active,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).semantics { role = Role.Tab },
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) palette.selected else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Ícone decorativo: o texto ao lado já identifica o destino.
                        Icon(
                            destination.icon, contentDescription = null, modifier = Modifier.size(19.dp),
                            tint = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            destination.label, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = palette.border)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AppIcons.Cloud, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Seu Drive. Seu espaço.", style = MaterialTheme.typography.labelLarge)
            Text(
                "Suas pastas, sempre por perto.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
