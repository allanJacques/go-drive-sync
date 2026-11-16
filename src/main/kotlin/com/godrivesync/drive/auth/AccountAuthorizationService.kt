package com.godrivesync.drive.auth

import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.data.models.Account
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.data.security.TokenCipher
import com.godrivesync.data.security.TokenProtectionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Une OAuth, identificação, criptografia e banco, sem dependência de Compose ou estado global de sessão.
 * O repositório recebe somente cifras; a listagem pública não contém tokens. I/O bloqueante usa IO,
 * semelhante a executar JDBC num SwingWorker em vez de um ActionListener na EDT.
 * Dependências de autorização/identificação são funções suspensas injetáveis para testes sem Google.
 */
class AccountAuthorizationService internal constructor(
    private val repository: AccountRepository,
    private val keyStore: LocalTokenKeyStore,
    private val authorize: suspend () -> OAuthCredentials,
    private val identify: suspend (String) -> String,
) {
    /** Monta o fluxo real, reutilizando o OAuthManager independente e o armazenamento local da chave. */
    constructor(
        repository: AccountRepository,
        keyStore: LocalTokenKeyStore = LocalTokenKeyStore(),
        oauthManager: OAuthManager = OAuthManager(),
    ) : this(repository, keyStore, oauthManager::authorize, GoogleAccountClient()::email)

    /**
     * Autoriza e salva somente após identificar o e-mail e criptografar ambos os tokens com sucesso.
     * Reautorizar atualiza a mesma conta. Falhas anteriores à transação não alteram registros existentes.
     * Cancelamento é propagado; depois do commit, cancelar a corrotina não desfaz o registro já salvo.
     */
    suspend fun authorizeAndSave(): SavedAccount = authorizeAndSave(expected = null)

    /**
     * Reabre OAuth para uma conta específica e recusa seleção de outro e-mail antes de salvar.
     * Um login/exclusão concorrente impede a substituição: não recria conta removida durante o navegador.
     * A futura UI deve chamar por ação explícita após REAUTHORIZATION_REQUIRED.
     */
    suspend fun reauthorize(accountId: Int): SavedAccount {
        val expected = storage { loadSnapshot(accountId) }
            ?: throw AccountAuthorizationException("A conta não está mais salva neste aplicativo.")
        return authorizeAndSave(expected)
    }

    /** Coordena autorização nova ou recuperação vinculada à fotografia anterior da conta. */
    private suspend fun authorizeAndSave(expected: CredentialSnapshot?): SavedAccount {
        // Verificar a chave antes do navegador evita pedir consentimento quando o armazenamento está quebrado.
        storage { keyStore.load(createIfMissing = repository.listSavedAccounts().isEmpty()) }
        val credentials = authorize()
        val email = identify(credentials.accessToken)
        if (expected != null && email != expected.account.email) {
            throw AccountAuthorizationException("Selecione a mesma conta Google que precisa ser autorizada novamente. Nenhuma credencial foi salva.")
        }
        return storage {
            // Relê a chave: removê-la durante a autorização não pode gerar uma chave substituta.
            val cipher = TokenCipher(keyStore.load())
            val access = cipher.encrypt(credentials.accessToken, context(email, "access"))
            val refresh = cipher.encrypt(credentials.refreshToken, context(email, "refresh"))
            val expiry = LocalDateTime.ofInstant(credentials.expiresAt, ZoneOffset.UTC)
            val saved = if (expected == null) repository.saveAuthorizedTokens(email, access, refresh, expiry)
            else repository.updateTokensIfUnchanged(expected.account, access, refresh, expiry)
                ?: throw AccountAuthorizationException("A conta mudou durante a autorização. Tente novamente.")
            SavedAccount(checkNotNull(saved.id), saved.email, saved.tokenExpiry.toInstant(ZoneOffset.UTC), saved.isDefault)
        }
    }

    /** Lista contas mesmo quando a chave está indisponível; a UI só recebe metadados seguros. */
    suspend fun listAccounts(): List<SavedAccount> = storage { repository.listSavedAccounts() }

    /**
     * Recupera tokens apenas em memória para construir um cliente da API; null significa ID inexistente.
     * Retorna a expiração original, inclusive se vencida. Chamadas autenticadas devem usar
     * AccountCredentialManager, que coordena renovação e persistência. Esta leitura não grava nada.
     */
    suspend fun loadCredentials(accountId: Int): OAuthCredentials? = storage { loadSnapshot(accountId)?.credentials }

    /** Lê uma fotografia para comparação otimista; bloqueante, exclusiva da camada auth em Dispatchers.IO. */
    internal fun loadSnapshot(accountId: Int): CredentialSnapshot? = protectedStorage {
        val account = repository.findById(accountId) ?: return@protectedStorage null
        val cipher = TokenCipher(keyStore.load())
        CredentialSnapshot(account, OAuthCredentials(
            cipher.decrypt(account.accessToken, context(account.email, "access")),
            cipher.decrypt(account.refreshToken, context(account.email, "refresh")),
            account.tokenExpiry.toInstant(ZoneOffset.UTC),
        ))
    }

    /**
     * Cifra a resposta e persiste antes de liberar o token para a chamada HTTP.
     * Bloqueante para uso no callback da biblioteca oficial, sempre dentro de Dispatchers.IO.
     * Retorna null em conflito; erro de disco/cifra/banco aborta a operação sem expor parâmetros.
     */
    internal fun saveRefreshed(snapshot: CredentialSnapshot, credentials: OAuthCredentials): CredentialSnapshot? = protectedStorage {
        val cipher = TokenCipher(keyStore.load())
        val email = snapshot.account.email
        val saved = repository.updateTokensIfUnchanged(snapshot.account,
            cipher.encrypt(credentials.accessToken, context(email, "access")),
            cipher.encrypt(credentials.refreshToken, context(email, "refresh")),
            LocalDateTime.ofInstant(credentials.expiresAt, ZoneOffset.UTC)) ?: return@protectedStorage null
        CredentialSnapshot(saved, credentials)
    }

    /** Contexto autenticado distingue finalidades e contas, com separador impossível em um e-mail válido. */
    private fun context(email: String, purpose: String): String = "go-drive-sync:v1:$purpose\u0000$email"

    /** Retira JDBC/arquivos da thread gráfica e impede que exceções SQL revelem parâmetros de persistência. */
    private suspend fun <T> storage(block: () -> T): T = withContext(Dispatchers.IO) {
        protectedStorage(block)
    }

    /** Sanitiza falhas também nos callbacks síncronos do SDK, sem mudar a thread por conta própria. */
    private fun <T> protectedStorage(block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TokenProtectionException) {
            throw failure
        } catch (failure: AccountAuthorizationException) {
            throw failure
        } catch (_: Exception) {
            throw AccountAuthorizationException("Não foi possível acessar as contas salvas. Verifique o banco de dados.")
        }
}

/** Fotografia interna com cifras e tokens em memória; não gera toString contendo campos sensíveis. */
internal class CredentialSnapshot(val account: Account, val credentials: OAuthCredentials)
