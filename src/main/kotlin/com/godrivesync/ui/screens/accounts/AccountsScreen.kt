package com.godrivesync.ui.screens.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.screens.login.LoginScreen
import com.godrivesync.ui.screens.login.LoginState
import java.time.format.DateTimeFormatter

/** Formato local legível; lastFullSync mantém o contrato LocalDateTime do banco, sem conversão UTC. */
private val syncDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * Gerenciamento de contas com estado elevado: callbacks lembram ActionListeners e não fazem I/O
 * durante composição. Reutiliza cartões/OAuth do login e acrescenta metadados e ações locais.
 * A rolagem é do App; o diálogo tem rolagem própria para textos longos na menor janela.
 */
@Composable
fun AccountsScreen(
    login: LoginState,
    state: AccountsState,
    onAdd: () -> Unit,
    onReconnect: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onReload: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onViewMappings: (Int) -> Unit,
    onSetDefault: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onConfirmRemoval: () -> Unit,
    onCancelRemoval: () -> Unit,
) {
    val blocked = state.busy || state.removal != null || login.managing
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.removal == null) state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy && state.removal == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LoginScreen(login, onAdd, onReconnect, onRetry, onReload, onCancelAuthorization, onViewMappings,
            blocked = blocked) { item ->
            Text(if (item.account.isDefault) "Conta padrão" else "Conta adicional",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.account.lastFullSync?.let { "Última sincronização: ${it.format(syncDateFormat)}" }
                ?: "Ainda não sincronizada", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onSetDefault(item.account.id) },
                enabled = !blocked && !login.loading && !login.authorizing && !item.account.isDefault) {
                Text("Definir como padrão")
            }
            TextButton(onClick = { onRemove(item.account.id) },
                enabled = !blocked && !login.loading && !login.authorizing) { Text("Sair da conta") }
        }
    }
    state.removal?.let { account ->
        // AlertDialog é modal, como JOptionPane; o estado controla sua presença na árvore Compose.
        AlertDialog(
            onDismissRequest = onCancelRemoval,
            title = { Text("Sair da conta?") },
            text = {
                Column(Modifier.heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(account.email)
                    Text("A conta, os tokens salvos, os mapeamentos e o histórico associados serão removidos deste aplicativo.")
                    Text("Os arquivos no computador e no Google Drive serão preservados. O acesso concedido no Google não será revogado.")
                    if (account.isDefault) Text("Nenhuma outra conta será definida como padrão automaticamente.")
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Removendo conta…") }
                }
            },
            confirmButton = { TextButton(onClick = onConfirmRemoval, enabled = !state.busy) { Text("Confirmar saída") } },
            dismissButton = { TextButton(onClick = onCancelRemoval, enabled = !state.busy) { Text("Cancelar") } },
        )
    }
}
