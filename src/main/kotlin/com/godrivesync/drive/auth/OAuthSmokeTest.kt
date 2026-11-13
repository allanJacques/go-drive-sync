package com.godrivesync.drive.auth

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entrada de diagnóstico da tarefa oauthLogin: só confirma obtenção dos tokens e expiração.
 * runBlocking pertence a esta entrada de console, nunca a um evento da interface Compose/Swing.
 * O consentimento é feito pelo usuário no navegador; não persiste credenciais nem inicializa o H2.
 * @param args Caminho opcional do JSON Desktop externo.
 */
fun main(args: Array<String>) {
    require(args.size <= 1) { "Informe apenas o caminho opcional de credentials.json." }
    println("Abrindo o navegador para autorização. Conclua o consentimento em até cinco minutos.")
    try {
        val credentials = runBlocking {
            OAuthManager(args.firstOrNull()?.let(Path::of) ?: Path.of("credentials.json")).authorize()
        }
        println("Autorização concluída: access token e refresh token recebidos. Expiração: ${credentials.expiresAt}.")
        println("Os tokens permaneceram apenas na memória e não foram gravados nem exibidos.")
    } catch (failure: OAuthException) {
        System.err.println("Falha de autorização (${failure.reason}): ${failure.message}")
        exitProcess(1)
    }
}
