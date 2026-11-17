package com.godrivesync.ui.screens.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import com.godrivesync.ui.components.AppIcons
import com.godrivesync.ui.screens.login.AccountAvatar
import com.godrivesync.ui.screens.login.AccountOutlineButton
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.screens.login.LoginScreen
import com.godrivesync.ui.screens.login.LoginState

/**
 * Gerenciamento de contas com estado elevado: callbacks lembram ActionListeners e não fazem I/O
 * durante composição. Reutiliza cartões/OAuth do login e acrescenta ações locais à linha adaptável de botões.
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
            if (!item.account.isDefault) AccountOutlineButton("Definir como padrão", AppIcons.Star,
                { onSetDefault(item.account.id) }, enabled = !blocked && !login.loading && !login.authorizing)
            AccountOutlineButton("Sair da conta", AppIcons.SignOut, { onRemove(item.account.id) },
                enabled = !blocked && !login.loading && !login.authorizing, destructive = true)
        }
    }
    state.removal?.let { account ->
        // AlertDialog é modal, como JOptionPane; o estado controla sua presença na árvore Compose.
        AlertDialog(
            onDismissRequest = onCancelRemoval,
            // A largura padrão do popup desktop é estreita; 480 dp acomoda a identidade e os botões.
            // O limite da janela ainda é respeitado pelo Dialog, e o corpo pode rolar em alturas menores.
            modifier = Modifier.width(480.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    Icon(AppIcons.SignOut, contentDescription = null, modifier = Modifier.padding(12.dp).size(26.dp))
                }
            },
            title = { Text("Sair da conta?") },
            text = {
                Column(Modifier.heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Removendo conta…") }
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccountAvatar(login.accounts.find { it.account.id == account.id }?.photo, account.email)
                            Text(account.email, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Esta conta será removida do aplicativo, junto com suas configurações de pastas e seu histórico.")
                    Text("Seus arquivos continuarão no computador e no Google Drive.", fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = {
                Button(onClick = onConfirmRemoval, enabled = !state.busy,
                    modifier = Modifier.testTag("confirm-account-removal"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError)) {
                    Icon(AppIcons.SignOut, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sair da conta")
                }
            },
            dismissButton = { OutlinedButton(onClick = onCancelRemoval, enabled = !state.busy) { Text("Cancelar") } },
        )
    }
}
