package com.godrivesync.ui.screens.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.components.AppPanel

/**
 * Apresentação e conexão de todas as contas. Recebe estado imutável e callbacks como listeners Swing;
 * não executa rede/banco durante composição. A rolagem pertence ao painel pai, evitando scroll aninhado.
 * [blocked] bloqueia eventos durante gerenciamento; [accountDetails] acrescenta conteúdo ao mesmo cartão.
 * O botão de mapeamentos muda apenas a visualização; não existe seleção exclusiva de conta conectada.
 */
@Composable
fun LoginScreen(
    state: LoginState,
    onAdd: () -> Unit,
    onReconnect: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onReload: () -> Unit,
    onCancel: () -> Unit,
    onViewMappings: (Int) -> Unit,
    blocked: Boolean = false,
    accountDetails: @Composable (LoginAccount) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (state.accounts.isEmpty()) "Conecte seu Google Drive" else "Suas contas Google",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("As contas ficam salvas e são recuperadas automaticamente ao abrir o aplicativo.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("A sincronização automática de pastas estará disponível em uma próxima etapa.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Carregando contas salvas…")
        }
        state.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            if (!blocked && !state.authorizing && !state.loading) TextButton(onClick = onReload) { Text("Recarregar contas") }
        }
        state.accounts.forEach { account ->
            AccountCard(account, blocked || state.managing || state.loading || state.authorizing,
                onReconnect, onRetry, onViewMappings, accountDetails)
        }
        if (state.authorizing) {
            AppPanel(Modifier.fillMaxWidth(), inset = true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.reconnectingId == null) "Conclua a autorização no navegador."
                    else "No navegador, autorize novamente a mesma conta.")
                TextButton(onClick = onCancel) { Text("Cancelar autorização") }
            }
        }
        Button(onClick = onAdd, enabled = state.loaded && !state.loading && !state.authorizing && !state.managing && !blocked) {
            Text("+ Adicionar conta Google")
        }
    }
}

/** Cartão com avatar opcional, e-mail completo e ações abaixo do texto para caber na janela mínima. */
@Composable
private fun AccountCard(
    item: LoginAccount,
    blocked: Boolean,
    onReconnect: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onViewMappings: (Int) -> Unit,
    accountDetails: @Composable (LoginAccount) -> Unit,
) {
    AppPanel(Modifier.fillMaxWidth(), inset = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center) {
                val photo = item.photo
                if (photo != null) Image(photo, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
                else Text(item.account.email.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.account.email, style = MaterialTheme.typography.titleMedium)
                Text(when (item.access) {
                    AccountAccess.CHECKING -> "Verificando acesso…"
                    AccountAccess.AVAILABLE -> "Acesso confirmado"
                    AccountAccess.RECONNECT -> "Reconexão necessária"
                    AccountAccess.UNAVAILABLE -> "Não foi possível verificar o acesso"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        accountDetails(item)
        item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        // Ações em coluna preservam legibilidade para e-mails longos e fontes maiores.
        TextButton(onClick = { onViewMappings(item.account.id) }, enabled = !blocked) { Text("Ver mapeamentos") }
        when (item.access) {
            AccountAccess.RECONNECT -> TextButton(onClick = { onReconnect(item.account.id) }, enabled = !blocked) {
                Text("Reconectar")
            }
            AccountAccess.UNAVAILABLE -> TextButton(onClick = { onRetry(item.account.id) }, enabled = !blocked) {
                Text("Tentar novamente")
            }
            else -> Unit
        }
    }
}
