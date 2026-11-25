package com.godrivesync.ui.screens.mappings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.godrivesync.data.models.FolderMapping
import com.godrivesync.data.models.SyncDirection
import com.godrivesync.ui.components.AppPanel

/**
 * Desenha os vínculos da conta indicada pelo cabeçalho do App. A rolagem da lista pertence ao painel pai.
 * Os callbacks comunicam intenções da interface. [onSync] recebe conta e vínculo quando há motor disponível.
 */
@Composable
fun MappingsScreen(
    state: MappingsState,
    onReload: () -> Unit,
    onDialog: (MappingDialog?) -> Unit,
    onSave: (MappingFields) -> Unit,
    onRemove: () -> Unit,
    onSync: ((Int, Int) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.items == null) {
            if (state.error == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Carregando mapeamentos…")
            } else {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onReload) { Text("Tentar novamente") }
            }
        } else {
            Button(onClick = { onDialog(MappingDialog.Form(null)) }) { Text("+ Novo mapeamento") }
            if (state.items.isEmpty()) Text("Nenhum mapeamento cadastrado para esta conta.")
            if (state.items.isNotEmpty() && onSync == null) {
                Text("A sincronização manual estará disponível quando o motor de sincronização estiver integrado.",
                    style = MaterialTheme.typography.bodySmall)
            }
            state.items.forEach { mapping ->
                key(mapping.id) { MappingCard(mapping, onDialog, onSync) }
            }
        }
    }

    // O diálogo existe enquanto o estado o identifica. Fechar remove também os campos lembrados por remember.
    when (val dialog = state.dialog) {
        is MappingDialog.Form -> MappingForm(dialog.original, state, { onDialog(null) }, onSave)
        is MappingDialog.Remove -> AlertDialog(
            onDismissRequest = { onDialog(null) },
            title = { Text("Remover mapeamento?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dialog.mapping.driveFolderName, style = MaterialTheme.typography.titleMedium)
                    Text(dialog.mapping.localFolderPath)
                    Text("Seus arquivos e o histórico de transferências serão preservados.")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = onRemove, enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(if (state.busy) "Removendo…" else "Remover")
                }
            },
            dismissButton = { OutlinedButton({ onDialog(null) }, enabled = !state.busy) { Text("Cancelar") } },
        )
        null -> Unit
    }
}

/** Agrupa origem, destino e ações do vínculo; FlowRow quebra a linha de botões nas janelas menores. */
@Composable
private fun MappingCard(mapping: FolderMapping, onDialog: (MappingDialog) -> Unit, onSync: ((Int, Int) -> Unit)?) {
    AppPanel(Modifier.fillMaxWidth(), inset = true, spacing = 6.dp) {
        Text(mapping.driveFolderName, style = MaterialTheme.typography.titleMedium)
        Text("→ " + mapping.localFolderPath)
        val direction = when (mapping.syncDirection) {
            SyncDirection.BIDIRECTIONAL -> "↕ Bidirecional"
            SyncDirection.DOWNLOAD_ONLY -> "↓ Somente download"
        }
        Text(if (mapping.enabled) direction else "$direction · Pausado", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ onDialog(MappingDialog.Form(mapping)) }) { Text("Editar") }
            TextButton({ onDialog(MappingDialog.Remove(mapping)) }) { Text("✕ Remover") }
            OutlinedButton(onClick = { onSync?.invoke(mapping.accountId, requireNotNull(mapping.id)) },
                enabled = onSync != null && mapping.id != null) { Text("Sincronizar agora") }
        }
    }
}

/** Edita um rascunho em memória. Falhas mantêm os campos preenchidos e Cancelar descarta a edição. */
@Composable
private fun MappingForm(original: FolderMapping?, state: MappingsState, onCancel: () -> Unit, onSave: (MappingFields) -> Unit) {
    // remember conserva o texto entre teclas e trocas de tema. Nenhuma digitação grava no H2.
    var driveId by remember(original) { mutableStateOf(original?.driveFolderId.orEmpty()) }
    var name by remember(original) { mutableStateOf(original?.driveFolderName.orEmpty()) }
    var path by remember(original) { mutableStateOf(original?.localFolderPath.orEmpty()) }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.width(480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(if (original == null) "Novo mapeamento" else "Editar mapeamento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(driveId, { driveId = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
                    singleLine = true, label = { Text("ID da pasta no Drive") },
                    supportingText = { Text("Na URL da pasta, copie o trecho depois de /folders/ até o próximo ? ou /.") })
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
                    singleLine = true, label = { Text("Nome da pasta no Drive") })
                OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
                    singleLine = true, label = { Text("Caminho absoluto da pasta local") })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({ onSave(MappingFields(driveId, name, path)) }, enabled = !state.busy) {
                Text(if (state.busy) "Salvando…" else "Salvar")
            }
        },
        dismissButton = { OutlinedButton(onCancel, enabled = !state.busy) { Text("Cancelar") } },
    )
}
