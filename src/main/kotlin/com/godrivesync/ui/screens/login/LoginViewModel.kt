package com.godrivesync.ui.screens.login

import androidx.compose.ui.graphics.ImageBitmap
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.data.security.TokenProtectionException
import com.godrivesync.drive.auth.AccountAuthorizationException
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import com.godrivesync.drive.auth.OAuthException
import com.godrivesync.ui.navigation.AppDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Estado do acesso remoto, independente de estar selecionada na interface ou de sincronizar arquivos. */
enum class AccountAccess { CHECKING, AVAILABLE, RECONNECT, UNAVAILABLE }

/** Fotografia visual sem tokens; bitmap opcional fica em memória e erro pertence somente a esta conta. */
data class LoginAccount(
    val account: SavedAccount,
    val access: AccountAccess = AccountAccess.CHECKING,
    val message: String? = null,
    val photo: ImageBitmap? = null,
)

/** Pedido consumível de navegação; accountId é filtro visual, nunca exclusividade de sincronização. */
data class LoginNavigation(val destination: AppDestination, val accountId: Int? = null)

/**
 * Estado observável da tela; carregamento local e autorização não dependem das verificações remotas.
 * @property accounts Todas as contas persistidas, inclusive as que estão offline ou precisam de reconexão.
 * @property loaded Informa que houve ao menos uma leitura local bem-sucedida nesta janela.
 * @property loading Indica leitura local em andamento; não representa consultas remotas de perfil.
 * @property authorizing Mantém ações OAuth desabilitadas enquanto há uma autorização pendente.
 * @property reconnectingId Identidade exigida na reconexão; null representa adicionar conta.
 * @property message Falha geral sanitizada; erros remotos individuais ficam nos cartões.
 * @property navigation Pedido transitório consumido pelo App, independente de credenciais ou sincronização.
 */
data class LoginState(
    val accounts: List<LoginAccount> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val authorizing: Boolean = false,
    val reconnectingId: Int? = null,
    val message: String? = null,
    val navigation: LoginNavigation? = null,
)

/**
 * Coordena eventos de login sem conta ativa global. Todas as contas são restauradas antes da rede.
 * Métodos de evento e o scope pai pertencem à thread gráfica (EDT/Main); serviços suspendem I/O.
 * StateFlow lembra um modelo de tela observado por listeners em Swing, mas Compose coleta os valores
 * e recompõe apenas os consumidores. O filho SupervisorJob permite fechar a tela sem cancelar o pai.
 */
class LoginViewModel(
    private val accounts: LoginAccounts,
    parentScope: CoroutineScope,
    private val loadPhoto: suspend (String) -> ImageBitmap = AccountPhotoLoader()::load,
) {
    /** Ciclo de vida da janela; SupervisorJob isola falhas de trabalhos de contas diferentes. */
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(LoginState())
    /** Fluxo somente leitura: composables enviam eventos, não alteram a lista diretamente. */
    val state = mutableState.asStateFlow()
    /** Uma autorização por vez evita abrir vários navegadores; consultas de contas têm jobs separados. */
    private var authorizationJob: Job? = null
    private val profileJobs = mutableMapOf<Int, Job>()
    private val profilePermits = Semaphore(3)
    private var started = false

    /** Inicialização idempotente: recomposição não repete leitura nem dispara OAuth. */
    fun start() {
        if (started) return
        started = true
        reload()
    }

    /** Recarrega apenas o armazenamento local; abre Dashboard automaticamente quando já existem contas. */
    fun reload() {
        if (state.value.loading || state.value.authorizing) return
        mutableState.update { it.copy(loading = true, message = null) }
        scope.launch {
            try {
                val saved = accounts.list()
                val firstLoad = !state.value.loaded
                // Mantemos imagens/status já conhecidos; nunca filtramos por isDefault ou escolha visual.
                mutableState.update { old -> old.copy(
                    accounts = saved.map { account -> old.accounts.find { it.account.id == account.id }
                        ?.copy(account = account) ?: LoginAccount(account) },
                    loaded = true,
                    navigation = if (firstLoad) LoginNavigation(
                        if (saved.isEmpty()) AppDestination.ACCOUNTS else AppDestination.DASHBOARD) else old.navigation,
                ) }
                profileJobs.keys.filter { id -> saved.none { it.id == id } }.toList()
                    .forEach { profileJobs.remove(it)?.cancel() }
                saved.forEach { refresh(it.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "Não foi possível carregar as contas salvas. Tente novamente.") }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    /** Atualiza uma conta em segundo plano; atraso ou falha não impede a restauração das outras. */
    fun refresh(accountId: Int) {
        val account = state.value.accounts.find { it.account.id == accountId }?.account ?: return
        profileJobs.remove(accountId)?.cancel()
        updateAccount(accountId) { it.copy(access = AccountAccess.CHECKING, message = null) }
        profileJobs[accountId] = scope.launch {
            profilePermits.withPermit {
                try {
                    val profile = accounts.profile(account)
                    ensureActive() // Uma reconexão pode ter invalidado esta resposta enquanto o HTTP terminava.
                    updateAccount(accountId) { it.copy(access = AccountAccess.AVAILABLE) }
                    val photo = profile.photoLink?.let { link ->
                        try { loadPhoto(link) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null } // Foto indisponível não transforma acesso válido em erro.
                    }
                    ensureActive()
                    updateAccount(accountId) { it.copy(photo = photo) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    ensureActive()
                    val reconnect = failure is CredentialException &&
                        failure.reason == CredentialFailure.REAUTHORIZATION_REQUIRED
                    updateAccount(accountId) { it.copy(
                        access = if (reconnect) AccountAccess.RECONNECT else AccountAccess.UNAVAILABLE,
                        message = safeMessage(failure),
                    ) }
                }
            }
        }
    }

    /** Abre OAuth somente por clique; ID é usado exclusivamente para reconectar a mesma identidade. */
    fun authorize(accountId: Int? = null) {
        if (state.value.authorizing || state.value.loading || !state.value.loaded) return
        if (accountId != null && state.value.accounts.none { it.account.id == accountId }) return
        mutableState.update { it.copy(authorizing = true, reconnectingId = accountId, message = null) }
        // UNDISPATCHED entra no try/finally antes de devolver o clique. Mesmo um cancelamento
        // imediato libera authorizing; o primeiro I/O suspenso continua fora da thread gráfica.
        authorizationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var cancelled = false
            try {
                val saved = accounts.authorize(accountId)
                ensureActive()
                // Publica imediatamente a conta salva, mesmo se a consulta de mapeamentos falhar depois.
                profileJobs.remove(saved.id)?.cancel()
                mutableState.update { old -> old.copy(accounts =
                    old.accounts.filterNot { it.account.id == saved.id } + LoginAccount(saved)) }
                refresh(saved.id)
                val destination = if (accounts.hasMappings(saved.id)) AppDestination.DASHBOARD else AppDestination.MAPPINGS
                ensureActive()
                mutableState.update { it.copy(navigation = LoginNavigation(destination, saved.id)) }
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            } catch (failure: Exception) {
                mutableState.update { it.copy(message = safeMessage(failure)) }
            } finally {
                mutableState.update { it.copy(authorizing = false, reconnectingId = null) }
                authorizationJob = null
                // Cancelar depois do commit não desfaz a conta. Releitura reconcilia o banco sem novo OAuth.
                // Ao fechar a janela o scope já está cancelado, portanto não se inicia outro trabalho.
                if (cancelled && scope.isActive) reload()
            }
        }
    }

    /** Cancela callback/troca OAuth; o finally reconcilia eventual persistência já concluída. */
    fun cancelAuthorization() { authorizationJob?.cancel() }

    /** Seleciona somente o contexto de configuração, inclusive quando a conta está temporariamente offline. */
    fun viewMappings(accountId: Int) {
        if (state.value.accounts.any { it.account.id == accountId }) {
            mutableState.update { it.copy(navigation = LoginNavigation(AppDestination.MAPPINGS, accountId)) }
        }
    }

    /** Limpa o pedido já aplicado pelo App para evitar navegação duplicada ao recompor/trocar tema. */
    fun consumeNavigation(navigation: LoginNavigation) {
        mutableState.update { if (it.navigation == navigation) it.copy(navigation = null) else it }
    }

    /** Fecha todos os trabalhos da janela, inclusive listener OAuth; não remove contas persistidas. */
    fun close() { scope.cancel() }

    /** Substitui apenas o cartão correspondente, preservando respostas independentes das outras contas. */
    private fun updateAccount(id: Int, transform: (LoginAccount) -> LoginAccount) {
        mutableState.update { state -> state.copy(accounts = state.accounts.map {
            if (it.account.id == id) transform(it) else it
        }) }
    }

    /** Somente exceções sanitizadas das camadas próprias podem fornecer texto à tela. */
    private fun safeMessage(failure: Exception): String = when (failure) {
        is CredentialException, is OAuthException, is AccountAuthorizationException, is TokenProtectionException ->
            failure.message ?: "Não foi possível concluir a operação. Tente novamente."
        else -> "Não foi possível concluir a operação. Verifique a conexão e tente novamente."
    }
}
