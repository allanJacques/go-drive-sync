package com.godrivesync.drive.auth

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Recursos isolados: JSON fictício e cliente HTTP loopback, sem consultar arquivos/contas reais. */
abstract class OAuthTestSupport {
    /** Credenciais fictícias são removidas ao terminar cada teste. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Retorno padrão para testes de orquestração que não precisam de um endpoint de token. */
    internal val fakeCredentials = OAuthCredentials("acesso-ficticio", "renovacao-ficticia", Instant.EPOCH)

    /** Escreve configuração mínima no formato Desktop; [authUri] permite testar URLs inválidas. */
    protected fun configurationFile(authUri: String = AUTHORIZATION_ENDPOINT): Path =
        temporaryFolder.newFile().apply {
            writeText("""{"installed":{"client_id":"cliente-ficticio","client_secret":"segredo-ficticio","auth_uri":"$authUri","token_uri":"$TOKEN_ENDPOINT"}}""")
        }.toPath()

    /** Monta o manager com dependências falsas; a abertura do navegador vira um callback controlado. */
    internal fun manager(
        path: Path = configurationFile(),
        timeout: Duration = Duration.ofSeconds(5),
        exchange: OAuthTokenExchange = OAuthTokenExchange { _, _, _, _ -> fakeCredentials },
        browser: (URI) -> Unit,
    ): OAuthManager = OAuthManager(path, timeout, browser, exchange)

    /** Decodifica os parâmetros efetivamente enviados, para conferir o contrato HTTP. */
    protected fun parameters(encoded: String): Map<String, String> = encoded.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
    }

    /** Envia uma chamada local com limites curtos e sem seguir redirects. Sempre fecha a conexão. */
    protected fun request(uri: URI, method: String = "GET"): Int {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
            status
        } finally {
            connection.disconnect()
        }
    }

    /** Reproduz o redirect do navegador usando state e URI recebidos na autorização. */
    protected fun callback(authorization: URI, extra: Map<String, String> = mapOf("code" to "codigo-ficticio")): URI {
        val query = parameters(authorization.rawQuery)
        return URI(query.getValue("redirect_uri") + "?" + encodeForm(mapOf("state" to query.getValue("state")) + extra))
    }
}
