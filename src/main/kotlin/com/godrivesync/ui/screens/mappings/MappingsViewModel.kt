package com.godrivesync.ui.screens.mappings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.models.FolderMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Representa o diálogo aberto. O formulário sem vínculo original cria um cadastro. */
sealed interface MappingDialog {
    /** Guarda a linha original durante uma edição ou null durante a criação. */
    data class Form(val original: FolderMapping?) : MappingDialog
    /** Identifica as duas pastas que a confirmação de remoção deve apresentar. */
    data class Remove(val mapping: FolderMapping) : MappingDialog
}

/** Fotografia da tela de uma conta. Uma lista nula indica que a primeira leitura ainda não terminou com sucesso. */
data class MappingsState(
    val accountId: Int? = null,
    val items: List<FolderMapping>? = null,
    val dialog: MappingDialog? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Campos manuais do formulário. O caminho segue as regras do sistema operacional em execução. */
data class MappingFields(val driveId: String, val name: String, val path: String) {
    /** Devolve a orientação do primeiro campo inválido ou null quando o cadastro pode ser salvo. */
    fun validationError(): String? {
        if (!FOLDER_ID.matches(driveId.trim())) return "Copie o ID da pasta que aparece depois de /folders/ na URL."
        if (name.isBlank() || name.trim().length > 255) return "Informe o nome da pasta no Drive (até 255 caracteres)."
        val absolute = try {
            Path.of(path).isAbsolute
        } catch (_: InvalidPathException) {
            false
        }
        return if (absolute) null else "Informe o caminho absoluto da pasta neste computador."
    }

    companion object {
        /** Caracteres de um ID do Drive e limite da coluna no H2. */
        private val FOLDER_ID = Regex("[a-zA-Z0-9_-]{1,100}")
    }
}

/**
 * Mantém o estado observado pela janela. Eventos chegam na thread gráfica de [scope].
 * As transações usam [io], como o trabalho de fundo de um SwingWorker. A janela cancela o escopo ao fechar.
 */
class MappingsViewModel(
    private val repository: MappingRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A substituição desta fotografia invalida somente os composables que leram o estado. */
    var state by mutableStateOf(MappingsState())
        private set

    /** Ordena releituras após gravações aceitas, inclusive ao sair e voltar rapidamente à tela. */
    private val operations = Mutex()
    /** Cada abertura recebe outra geração, mesmo quando o usuário volta para a mesma conta. */
    private var generation = 0L

    /** Abre [accountId] com uma lista nova. Descarta diálogos e resultados do contexto anterior. */
    fun open(accountId: Int) {
        generation++
        state = MappingsState(accountId)
        runOperation("Não foi possível carregar os mapeamentos.") {
            MappingsState(accountId, items = repository.findByAccountId(accountId))
        }
    }

    /** Ao sair da tela, descarta o rascunho e impede que trabalho em andamento republique a lista antiga. */
    fun leave() {
        generation++
        state = MappingsState()
    }

    /** Apresenta um formulário ou confirmação da lista atual. Null fecha e descarta o rascunho. */
    fun show(dialog: MappingDialog?) {
        if (!state.busy && state.items != null) state = state.copy(dialog = dialog, error = null)
    }

    /** Valida o formulário atual e publica a linha persistida somente após o commit. */
    fun save(fields: MappingFields) {
        val current = state
        val form = current.dialog as? MappingDialog.Form ?: return
        val accountId = current.accountId ?: return
        val items = current.items ?: return
        if (current.busy) return
        fields.validationError()?.let {
            state = current.copy(error = it)
            return
        }
        runOperation("Não foi possível salvar o mapeamento. Tente novamente.") {
            val id = form.original?.id
            val driveId = fields.driveId.trim()
            val name = fields.name.trim()
            val updated = if (id == null) {
                items + repository.save(FolderMapping(accountId, driveId, name, fields.path))
            } else {
                val saved = repository.updateFolders(accountId, id, driveId, name, fields.path)
                items.map { if (it.id == id) saved else it }
            }
            MappingsState(accountId, updated)
        }
    }

    /** Remove apenas a linha identificada pelo diálogo de confirmação da conta atual. */
    fun confirmRemoval() {
        val current = state
        val dialog = current.dialog as? MappingDialog.Remove ?: return
        val accountId = current.accountId ?: return
        val id = dialog.mapping.id ?: return
        val items = current.items ?: return
        if (current.busy) return
        runOperation("Não foi possível remover o mapeamento. Tente novamente.") {
            repository.removeKeepingHistory(accountId, id)
            MappingsState(accountId, items.filterNot { it.id == id })
        }
    }

    /** Serializa o trabalho em disco. Uma resposta atrasada jamais substitui a conta aberta mais recentemente. */
    private fun runOperation(message: String, work: () -> MappingsState) {
        val request = generation
        state = state.copy(busy = true, error = null)
        scope.launch {
            try {
                val result = operations.withLock { withContext(io) { work() } }
                if (request == generation) state = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) state = state.copy(busy = false, error = message)
            }
        }
    }
}
