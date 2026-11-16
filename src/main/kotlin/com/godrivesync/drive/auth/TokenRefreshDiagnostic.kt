package com.godrivesync.drive.auth

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.godrivesync.data.db.initializeDatabase
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.security.TokenProtectionException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Diagnóstico explícito: sem argumentos lista IDs; com ID faz about.get autenticado e renova se preciso.
 * --reauthorize permite abrir o navegador somente se o Google exigir nova autorização.
 * Aceita caminho opcional do JSON após o ID. Não imprime tokens, corpos HTTP nem stack traces.
 * runBlocking é exclusivo desta entrada de console; nunca deve ser usado em callbacks Compose/Swing.
 */
fun main(args: Array<String>) {
    try {
        val positional = args.filterNot { it == "--reauthorize" }
        require(positional.size <= 2)
        val accountId = positional.firstOrNull()?.toInt()?.also { require(it > 0) }
        val path = positional.getOrNull(1)?.let(Path::of) ?: Path.of("credentials.json")
        val database = initializeDatabase()
        try {
            val accounts = AccountAuthorizationService(AccountRepository(database), oauthManager = OAuthManager(path))
            runBlocking {
                if (accountId == null) {
                    accounts.listAccounts().forEach { println("Conta ${it.id}: ${it.email}; expiração UTC: ${it.expiresAt}.") }
                    println("Use oauthRefreshCheck --args='ID [caminho-do-JSON] [--reauthorize]' para verificar uma conta.")
                } else {
                    val manager = AccountCredentialManager(accounts, path)
                    try {
                        verifyAccountAccess(manager, accountId)
                    } catch (failure: CredentialException) {
                        if (failure.reason != CredentialFailure.REAUTHORIZATION_REQUIRED) throw failure
                        if ("--reauthorize" !in args) {
                            println("Esta conta precisa de nova autorização. Execute novamente com --reauthorize para abrir o navegador.")
                            throw failure
                        }
                        println("Abrindo OAuth. Selecione a mesma conta salva para recuperar o acesso.")
                        accounts.reauthorize(accountId)
                        verifyAccountAccess(manager, accountId)
                    }
                    val saved = accounts.listAccounts().first { it.id == accountId }
                    println("Acesso confirmado para ${saved.email}. Expiração UTC persistida: ${saved.expiresAt}.")
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    } catch (failure: Exception) {
        val message = when (failure) {
            is OAuthException, is TokenProtectionException, is AccountAuthorizationException, is CredentialException -> failure.message
            else -> "Não foi possível verificar a conta. Confira o ID, o JSON Desktop e o banco local."
        }
        System.err.println(message)
        exitProcess(1)
    }
}

/** Faz uma consulta mínima de leitura e consome/fecha a resposta ainda no escopo autenticado em IO. */
private suspend fun verifyAccountAccess(manager: AccountCredentialManager, accountId: Int) {
    manager.execute(accountId) { initializer ->
        val response = NetHttpTransport().createRequestFactory(initializer).buildGetRequest(
            GenericUrl("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)")
        ).execute()
        try { response.ignore() } finally { response.disconnect() }
    }
}
