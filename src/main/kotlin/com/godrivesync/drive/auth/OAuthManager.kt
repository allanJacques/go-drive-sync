package com.godrivesync.drive.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Coordena OAuth Desktop com navegador do sistema, state e PKCE S256 por tentativa.
 * Não depende da UI, não identifica a conta nem grava tokens. Pode ser chamado por uma corrotina
 * da futura tela: I/O sai da thread Swing/Compose e a espera pelo callback apenas suspende.
 * Dependências internas permitem testes determinísticos sem abrir o navegador real.
 */
class OAuthManager internal constructor(
    private val credentialsPath: Path,
    private val timeout: Duration,
    private val openBrowser: (URI) -> Unit,
    private val tokenExchange: OAuthTokenExchange,
) {
    /**
     * Configura o uso real com JSON externo e limite total de cinco minutos por padrão.
     * @param credentialsPath Caminho relativo ao diretório de execução, nunca recurso empacotado.
     * @param timeout Limite da abertura do navegador, callback e troca de tokens; deve ser positivo.
     */
    constructor(
        credentialsPath: Path = Path.of("credentials.json"),
        timeout: Duration = Duration.ofMinutes(5),
    ) : this(credentialsPath, timeout, ::openSystemBrowser, OAuthTokenClient())

    init {
        require(timeout.toMillis() > 0) { "O timeout deve ser de pelo menos um milissegundo." }
    }

    /**
     * Autoriza uma conta e retorna tokens transitórios. Cada chamada usa porta, state e verifier próprios.
     * Recusa e falhas geram OAuthException; cancelamento externo mantém CancellationException.
     * O listener é fechado também em falhas, timeout e cancelamento; tokens não são escritos em disco.
     */
    suspend fun authorize(): OAuthCredentials {
        val configuration = withContext(Dispatchers.IO) { OAuthConfiguration.load(credentialsPath) }
        val state = randomUrlSafeValue()
        val verifier = randomUrlSafeValue()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        // Criar e usar o recurso dentro do mesmo contexto evita perdê-lo se houver cancelamento
        // na volta de withContext. use equivale a try-with-resources e sempre encerra o listener.
        return withContext(Dispatchers.IO) {
            val receiver = try {
                LoopbackReceiver(state)
            } catch (_: Exception) {
                throw OAuthException(OAuthFailure.CALLBACK_SERVER, "Não foi possível abrir o servidor local de autorização.")
            }
            receiver.use {
                withTimeoutOrNull(timeout.toMillis()) {
                    val authorization = URI(AUTHORIZATION_ENDPOINT + "?" + encodeForm(mapOf(
                        "client_id" to configuration.clientId,
                        "redirect_uri" to receiver.redirectUri.toString(),
                        "response_type" to "code",
                        "scope" to DRIVE_SCOPE,
                        "access_type" to "offline",
                        "prompt" to "consent select_account",
                        "state" to state,
                        "code_challenge" to challenge,
                        "code_challenge_method" to "S256",
                    )))
                    runInterruptible {
                        try {
                            openBrowser(authorization)
                        } catch (interrupted: InterruptedException) {
                            throw interrupted
                        } catch (_: Exception) {
                            throw OAuthException(OAuthFailure.BROWSER, "Não foi possível abrir o navegador padrão do sistema.")
                        }
                    }
                    val code = receiver.awaitCode()
                    tokenExchange.exchange(configuration, code, verifier, receiver.redirectUri)
                } ?: throw OAuthException(OAuthFailure.TIMEOUT, "O tempo para autorizar expirou. Inicie uma nova tentativa.")
            }
        }
    }
}

/** Gera 256 bits imprevisíveis; Base64URL sem padding resulta em 43 caracteres aceitos pelo PKCE. */
private fun randomUrlSafeValue(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Usa o navegador externo via AWT Desktop; ambiente sem suporte retorna erro tratado pelo manager. */
private fun openSystemBrowser(uri: URI) {
    check(Desktop.isDesktopSupported())
    val desktop = Desktop.getDesktop()
    check(desktop.isSupported(Desktop.Action.BROWSE))
    desktop.browse(uri)
}
