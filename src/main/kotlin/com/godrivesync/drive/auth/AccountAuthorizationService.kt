package com.godrivesync.drive.auth

import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.models.SavedAccount
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
    suspend fun authorizeAndSave(): SavedAccount {
        // Verificar a chave antes do navegador evita pedir consentimento quando o armazenamento está quebrado.
        storage { keyStore.load(createIfMissing = repository.listSavedAccounts().isEmpty()) }
        val credentials = authorize()
        val email = identify(credentials.accessToken)
        return storage {
            // Relê a chave: removê-la durante a autorização não pode gerar uma chave substituta.
            val cipher = TokenCipher(keyStore.load())
            val access = cipher.encrypt(credentials.accessToken, context(email, "access"))
            val refresh = cipher.encrypt(credentials.refreshToken, context(email, "refresh"))
            val saved = repository.saveAuthorizedTokens(email, access, refresh,
                LocalDateTime.ofInstant(credentials.expiresAt, ZoneOffset.UTC))
            SavedAccount(checkNotNull(saved.id), saved.email, saved.tokenExpiry.toInstant(ZoneOffset.UTC), saved.isDefault)
        }
    }

    /** Lista contas mesmo quando a chave está indisponível; a UI só recebe metadados seguros. */
    suspend fun listAccounts(): List<SavedAccount> = storage { repository.listSavedAccounts() }

    /**
     * Recupera tokens apenas em memória para construir um cliente da API; null significa ID inexistente.
     * Retorna a expiração original, inclusive se vencida: o chamador deverá renovar antes de usar nesse caso.
     * Não renova automaticamente, não escreve cifras novamente e nunca aceita tokens legados em texto puro.
     */
    suspend fun loadCredentials(accountId: Int): OAuthCredentials? = storage {
        val account = repository.findById(accountId) ?: return@storage null
        val cipher = TokenCipher(keyStore.load())
        OAuthCredentials(
            cipher.decrypt(account.accessToken, context(account.email, "access")),
            cipher.decrypt(account.refreshToken, context(account.email, "refresh")),
            account.tokenExpiry.toInstant(ZoneOffset.UTC),
        )
    }

    /** Contexto autenticado distingue finalidades e contas, com separador impossível em um e-mail válido. */
    private fun context(email: String, purpose: String): String = "go-drive-sync:v1:$purpose\u0000$email"

    /** Retira JDBC/arquivos da thread gráfica e impede que exceções SQL revelem parâmetros de persistência. */
    private suspend fun <T> storage(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TokenProtectionException) {
            throw failure
        } catch (_: Exception) {
            throw AccountAuthorizationException("Não foi possível acessar as contas salvas. Verifique o banco de dados.")
        }
    }
}
