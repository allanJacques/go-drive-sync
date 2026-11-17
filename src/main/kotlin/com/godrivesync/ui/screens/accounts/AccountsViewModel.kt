package com.godrivesync.ui.screens.accounts

import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.ui.screens.login.LoginViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Porta de gerenciamento local; nunca devolve tokens e pode ser substituída por um fake nos testes. */
interface AccountManagement {
    /** Persiste uma única preferência visual; conta ausente causa falha, sem escolher outra. */
    suspend fun setDefault(id: Int)
    /** Remove a conta e seus registros associados; conta já ausente é um sucesso idempotente. */
    suspend fun remove(id: Int)
}

/** Adaptador JDBC fora da EDT, semelhante ao trabalho de banco dentro de um SwingWorker. */
class AccountManagementService(private val repository: AccountRepository) : AccountManagement {
    /** Atualiza apenas preferência, preservando credenciais e datas. */
    override suspend fun setDefault(id: Int) = withContext(Dispatchers.IO) {
        check(repository.setDefault(id)) { "Conta ausente." }
    }

    /** Usa a transação de limpeza explícita; não chama revogação remota nem toca em arquivos. */
    override suspend fun remove(id: Int) = withContext(Dispatchers.IO) {
        repository.deleteWithRelatedData(id)
        Unit
    }
}

/**
 * Estado exclusivo da tela; a lista, OAuth e perfis continuam pertencendo ao LoginViewModel.
 * @property removal Identidade exibida na confirmação; null mantém o diálogo fechado.
 * @property busy Bloqueia cliques e fechamento durante gravação, sem bloquear a EDT.
 * @property message Texto local sanitizado, apresentado no diálogo ou acima da lista.
 */
data class AccountsState(
    val removal: SavedAccount? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Coordena confirmação, preferência e erros sanitizados sem duplicar a lista de contas.
 * Eventos são chamados na thread gráfica; o scope filho acompanha a janela, não cada recomposição.
 * O LoginViewModel serializa as gravações com OAuth/leitura e publica uma fotografia única para o App.
 */
class AccountsViewModel(
    private val login: LoginViewModel,
    private val management: AccountManagement,
    parentScope: CoroutineScope,
) {
    /** SupervisorJob isola esta tela; fechar a janela cancela os trabalhos ainda não iniciados. */
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    /** Fonte mutável privada; callbacks publicam novas fotografias em vez de alterar widgets. */
    private val mutableState = MutableStateFlow(AccountsState())
    /** Compose observa este fluxo; somente eventos podem alterar confirmação e mensagens. */
    val state = mutableState.asStateFlow()

    /** Abre a confirmação com a identidade salva; não remove nada ao primeiro clique. */
    fun requestRemoval(id: Int) {
        if (!canManage()) return
        val account = login.state.value.accounts.find { it.account.id == id }?.account ?: return
        mutableState.value = AccountsState(removal = account)
    }

    /** Fechar/Cancelar o diálogo preserva todos os dados; durante o commit o fechamento fica bloqueado. */
    fun cancelRemoval() {
        if (!state.value.busy) mutableState.update { it.copy(removal = null, message = null) }
    }

    /** Confirma exclusivamente a conta apresentada no diálogo; duplo clique não repete a transação. */
    fun confirmRemoval() {
        val id = state.value.removal?.id ?: return
        change(id, remove = true)
    }

    /** Escolhe a conta padrão por ID, sem alterar quais contas estão conectadas. */
    fun setDefault(id: Int) {
        if (state.value.removal != null) return
        change(id, remove = false)
    }

    /** Recusa eventos enquanto a restauração, OAuth ou outra alteração estiverem em andamento. */
    private fun canManage(): Boolean {
        val current = login.state.value
        return current.loaded && !current.loading && !current.authorizing && !current.managing && !state.value.busy
    }

    /** Publica progresso antes de suspender e mantém confirmação disponível para retry em caso de falha. */
    private fun change(id: Int, remove: Boolean) {
        if (!canManage()) return
        mutableState.update { it.copy(busy = true, message = null) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val changed = login.manageAccount(id, remove) {
                    if (remove) management.remove(id) else management.setDefault(id)
                }
                mutableState.update { it.copy(
                    removal = if (changed) null else it.removal,
                    message = if (changed) null else "A conta mudou. Recarregue a lista e tente novamente.",
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // SQL/HTTP podem conter dados privados: nunca apresentar a mensagem bruta da exceção.
                mutableState.update { it.copy(message = if (remove)
                    "Não foi possível remover a conta. Tente novamente."
                    else "Não foi possível definir a conta padrão. Tente novamente.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Cancela o scope filho; transação local já iniciada termina para reconciliar o estado com o banco. */
    fun close() { scope.cancel() }
}
