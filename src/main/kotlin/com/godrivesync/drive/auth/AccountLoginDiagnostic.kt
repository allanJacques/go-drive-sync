package com.godrivesync.drive.auth

import com.godrivesync.data.db.initializeDatabase
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.security.TokenProtectionException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entrada explícita de oauthAccountLogin: persiste conta, relê suas credenciais e lista metadados seguros.
 * Diferente de oauthLogin, abre o H2 e cria a chave local no primeiro uso. Tokens nunca são impressos.
 * runBlocking só pertence a este console; a futura UI deve iniciar uma corrotina em resposta a um evento.
 * @param args Caminho opcional do JSON Desktop, como no diagnóstico original.
 */
fun main(args: Array<String>) {
    require(args.size <= 1) { "Informe apenas o caminho opcional de credentials.json." }
    try {
        val database = initializeDatabase()
        try {
            val service = AccountAuthorizationService(AccountRepository(database),
                oauthManager = OAuthManager(args.firstOrNull()?.let(Path::of) ?: Path.of("credentials.json")))
            runBlocking {
                println("Autorizando conta para salvar tokens criptografados no banco local.")
                val saved = service.authorizeAndSave()
                checkNotNull(service.loadCredentials(saved.id))
                println("Conta salva e credenciais recuperadas: ${saved.email}. Expiração UTC: ${saved.expiresAt}.")
                service.listAccounts().forEach {
                    println("Conta ${it.id}: ${it.email}; padrão=${it.isDefault}; token vencido=${it.isExpired()}.")
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    } catch (failure: Exception) {
        // Não imprimir stack/cause: bibliotecas de banco/HTTP podem incluir parâmetros em exceções.
        val message = when (failure) {
            is OAuthException, is TokenProtectionException, is AccountAuthorizationException -> failure.message
            else -> "Não foi possível concluir o diagnóstico. Verifique a configuração e o banco local."
        }
        System.err.println(message)
        exitProcess(1)
    }
}
