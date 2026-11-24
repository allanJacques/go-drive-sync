package com.godrivesync.drive.api

import com.godrivesync.data.db.initializeDatabase
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.security.TokenProtectionException
import com.godrivesync.drive.auth.AccountAuthorizationException
import com.godrivesync.drive.auth.AccountAuthorizationService
import com.godrivesync.drive.auth.AccountCredentialManager
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.OAuthException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Diagnóstico explícito de files.list: sem argumentos mostra contas; com ID consulta até dez itens.
 * Usa o banco/chave locais e pode persistir renovação, mas não altera arquivos nem abre navegador.
 * Aceita JSON Desktop opcional após o ID. Não imprime nomes de arquivos, tokens ou respostas HTTP.
 * runBlocking pertence somente a esta entrada de console, nunca a callbacks Swing/Compose.
 */
fun main(args: Array<String>) {
    try {
        require(args.size <= 2)
        val accountId = args.firstOrNull()?.toInt()?.also { require(it > 0) }
        val configurationPath = args.getOrNull(1)?.let(Path::of) ?: Path.of("credentials.json")
        val database = initializeDatabase()
        try {
            val accounts = AccountAuthorizationService(AccountRepository(database))
            runBlocking {
                val saved = accounts.listAccounts()
                if (accountId == null) {
                    saved.forEach { println("Conta ${it.id}: ${it.email}") }
                    println("Use driveApiCheck --args='ID [caminho-do-JSON]' para consultar uma página.")
                } else {
                    val account = saved.firstOrNull { it.id == accountId }
                        ?: throw AccountAuthorizationException("A conta não está mais salva neste aplicativo.")
                    val manager = AccountCredentialManager(accounts, configurationPath)
                    val page = DriveApiClient.fromSavedAccount(account, manager).listFilesPage()
                    println("Listagem autenticada concluída: ${page.files.size} item(ns) nesta página.")
                    println("Há próxima página: ${page.nextPageToken != null}. Busca incompleta: ${page.incompleteSearch}.")
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    } catch (failure: Exception) {
        val message = when (failure) {
            is OAuthException, is TokenProtectionException, is AccountAuthorizationException,
            is CredentialException -> failure.message
            else -> "Não foi possível consultar o Drive. Confira o ID, o JSON Desktop e o banco local."
        }
        System.err.println(message)
        exitProcess(1)
    }
}
