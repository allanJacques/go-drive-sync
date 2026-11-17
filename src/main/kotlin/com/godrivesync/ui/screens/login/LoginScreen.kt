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
import com.godrivesync.ui.components.AppIcons
import java.time.format.DateTimeFormatter

/** Formato da última sincronização local, preservando a convenção LocalDateTime do banco. */
private val syncDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * Apresentação e conexão de todas as contas. Recebe estado imutável e callbacks como listeners Swing;
 * não executa rede/banco durante composição. A rolagem pertence ao painel pai, evitando scroll aninhado.
 * [blocked] bloqueia eventos durante gerenciamento; [accountActions] acrescenta botões à mesma linha adaptável de ações.
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
    accountActions: @Composable (LoginAccount) -> Unit = {},
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
            if (!blocked && !state.authorizing && !state.loading) OutlinedButton(onClick = onReload) {
                Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recarregar contas")
            }
        }
        state.accounts.forEach { account ->
            AccountCard(account, blocked || state.managing || state.loading || state.authorizing,
                onReconnect, onRetry, onViewMappings, accountActions)
        }
        if (state.authorizing) {
            AppPanel(Modifier.fillMaxWidth(), inset = true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.reconnectingId == null) "Conclua a autorização no navegador."
                    else "No navegador, autorize novamente a mesma conta.")
                OutlinedButton(onClick = onCancel) { Text("Cancelar autorização") }
            }
        }
        Button(onClick = onAdd, enabled = state.loaded && !state.loading && !state.authorizing && !state.managing && !blocked) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Adicionar conta Google")
        }
    }
}

/**
 * Cartão compacto: identidade e selo no cabeçalho, metadados agrupados e ações com ícones.
 * FlowRow distribui os botões horizontalmente e cria novas linhas quando a largura/fontes exigirem;
 * diferente de uma Row fixa, preserva rótulos legíveis sem ultrapassar a janela mínima.
 * Os slots de ação não possuem estado próprio; cliques são encaminhados ao dono do estado.
 */
@Composable
private fun AccountCard(
    item: LoginAccount,
    blocked: Boolean,
    onReconnect: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onViewMappings: (Int) -> Unit,
    accountActions: @Composable (LoginAccount) -> Unit,
) {
    AppPanel(Modifier.fillMaxWidth(), inset = true, padding = PaddingValues(16.dp), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountAvatar(item.photo, item.account.email)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.account.email, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    // O selo ocupa apenas o espaço necessário e não repete um botão desabilitado.
                    if (item.account.isDefault) Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = CircleShape,
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(AppIcons.Star, contentDescription = null, modifier = Modifier.size(13.dp))
                            Text("Padrão", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when (item.access) {
                        AccountAccess.CHECKING -> "Verificando acesso…"
                        AccountAccess.AVAILABLE -> "Acesso confirmado"
                        AccountAccess.RECONNECT -> "Reconexão necessária"
                        AccountAccess.UNAVAILABLE -> "Não foi possível verificar o acesso"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.account.lastFullSync?.let { "Última sincronização: ${it.format(syncDateFormat)}" }
                        ?: "Ainda não sincronizada", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = { onViewMappings(item.account.id) }, enabled = !blocked,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ver mapeamentos")
            }
            when (item.access) {
                AccountAccess.RECONNECT -> AccountOutlineButton("Reconectar", AppIcons.Sync,
                    { onReconnect(item.account.id) }, enabled = !blocked)
                AccountAccess.UNAVAILABLE -> AccountOutlineButton("Tentar novamente", AppIcons.Sync,
                    { onRetry(item.account.id) }, enabled = !blocked)
                else -> Unit
            }
            accountActions(item)
        }
    }
}

/** Avatar compartilhado por cartão e confirmação; foto ausente usa a inicial, sem consulta durante composição. */
@Composable
internal fun AccountAvatar(photo: androidx.compose.ui.graphics.ImageBitmap?, email: String) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Text(email.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Ação secundária com contorno e ícone no estilo do menu. O texto fornece o nome acessível;
 * o ícone é decorativo. [destructive] destaca saída com a cor semântica do tema.
 */
@Composable
internal fun AccountOutlineButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor =
            if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
