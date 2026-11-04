package com.godrivesync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.components.AppIcons
import com.godrivesync.ui.components.AppPanel
import com.godrivesync.ui.navigation.AppDestination
import com.godrivesync.ui.theme.AppStyle

@Composable
fun DestinationScreen(
    destination: AppDestination,
    style: AppStyle,
    onStyleChange: (AppStyle) -> Unit,
    themeSaveFailed: Boolean,
    onRetryThemeSave: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    when (destination) {
        AppDestination.DASHBOARD -> DashboardScreen(onNavigate)
        AppDestination.SYNC -> EmptyScreen(
            destination, "Tudo tranquilo por aqui",
            "Quando houver uma sincronização, você poderá acompanhar o progresso e os arquivos nesta tela.",
            "Ver mapeamentos", { onNavigate(AppDestination.MAPPINGS) },
        )
        AppDestination.HISTORY -> EmptyScreen(
            destination, "Seu histórico começa aqui",
            "Os downloads e uploads realizados aparecerão aqui, com horário e resultado de cada operação.",
        )
        AppDestination.MAPPINGS -> EmptyScreen(
            destination, "Um caminho entre a nuvem e o computador",
            "Seus mapeamentos aparecerão aqui. A criação de conexões entre pastas estará disponível em breve.",
            "Ver contas", { onNavigate(AppDestination.ACCOUNTS) },
        )
        AppDestination.ACCOUNTS -> EmptyScreen(
            destination, "Todas as suas contas, juntas",
            "Nenhuma conta conectada. A conexão segura com o Google estará disponível em breve.",
        )
        AppDestination.SETTINGS -> SettingsScreen(style, onStyleChange, themeSaveFailed, onRetryThemeSave)
    }
}

@Composable
private fun EmptyScreen(
    destination: AppDestination,
    title: String,
    description: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    AppPanel(Modifier.fillMaxWidth(), inset = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f),
                    RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(destination.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                TextButton(onClick = onAction) {
                    Text(action)
                    Icon(AppIcons.ArrowRight, null, Modifier.padding(start = 12.dp).size(18.dp))
                }
            }
        }
    }
}
