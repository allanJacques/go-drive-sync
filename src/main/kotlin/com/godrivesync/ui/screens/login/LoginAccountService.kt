package com.godrivesync.ui.screens.login

import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.AccountAuthorizationService
import com.godrivesync.drive.auth.GoogleAccountProfile
import com.godrivesync.drive.auth.GoogleAccountProfileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fronteira suspensa da tela: testes substituem rede/banco; nenhum método devolve tokens. */
interface LoginAccounts {
    /** Recupera todas as contas persistidas, independentemente da preferência padrão. */
    suspend fun list(): List<SavedAccount>
    /** Abre OAuth por ação explícita; ID presente restringe a reconexão à mesma identidade. */
    suspend fun authorize(accountId: Int?): SavedAccount
    /** Verifica acesso e consulta perfil; falhas são isoladas pelo ViewModel por conta. */
    suspend fun profile(account: SavedAccount): GoogleAccountProfile
    /** Consulta os mapeamentos para escolher o destino depois da autorização. */
    suspend fun hasMappings(accountId: Int): Boolean
}

/** Adaptador entre os serviços existentes e a UI; seleção visual nunca ativa/desativa contas. */
class LoginAccountService(
    private val authorization: AccountAuthorizationService,
    private val profiles: GoogleAccountProfileClient,
    private val mappings: MappingRepository,
) : LoginAccounts {
    /** O serviço já transfere leitura do H2 para IO. */
    override suspend fun list() = authorization.listAccounts()

    /** Adicionar autoriza uma conta nova ou atualiza a existente sem duplicar seu e-mail. */
    override suspend fun authorize(accountId: Int?) =
        if (accountId == null) authorization.authorizeAndSave() else authorization.reauthorize(accountId)

    /** Reutiliza renovação automática e tratamento de HTTP 401 da issue #9. */
    override suspend fun profile(account: SavedAccount) = profiles.load(account)

    /** Consulta JDBC fora da EDT, como um SwingWorker; não altera nem inicia mapeamentos. */
    override suspend fun hasMappings(accountId: Int) = withContext(Dispatchers.IO) {
        mappings.findByAccountId(accountId).isNotEmpty()
    }
}
