package com.godrivesync.drive.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import kotlin.test.*

/** Testa o fluxo com navegador simulado e servidor loopback real, sem acessar o Google. */
class OAuthManagerTest : OAuthTestSupport() {
    /** Confere parâmetros OAuth/PKCE e a identidade do redirect usado na troca do código. */
    @Test
    fun `autoriza com state PKCE offline e retorna credenciais sem persistencia`() = runBlocking {
        lateinit var authorization: URI
        var exchanges = 0
        val path = configurationFile("https://accounts.google.com/o/oauth2/auth")
        val before = Files.readString(path)
        val manager = manager(path = path, exchange = OAuthTokenExchange { configuration, code, verifier, redirect ->
            exchanges++
            val query = parameters(authorization.rawQuery)
            assertEquals("cliente-ficticio", configuration.clientId)
            assertEquals("codigo +/&=ç", code)
            assertEquals(query["redirect_uri"], redirect.toString())
            assertEquals("127.0.0.1", redirect.host)
            assertTrue(redirect.port > 0)
            assertEquals("/callback", redirect.path)
            assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest), query["code_challenge"])
            assertEquals("S256", query["code_challenge_method"])
            assertEquals("offline", query["access_type"])
            assertEquals("consent select_account", query["prompt"])
            assertEquals(DRIVE_SCOPE, query["scope"])
            assertEquals("code", query["response_type"])
            assertFalse(query.containsKey("client_secret"))
            assertFalse(query.containsKey("code_verifier"))
            fakeCredentials
        }) { uri ->
            authorization = uri
            assertEquals(AUTHORIZATION_ENDPOINT, uri.toString().substringBefore('?'))
            assertEquals(200, request(callback(uri, mapOf("code" to "codigo +/&=ç"))))
        }
        assertSame(fakeCredentials, manager.authorize())
        assertEquals(1, exchanges)
        assertEquals(before, Files.readString(path))
        assertPortReleased(authorization)
    }

    /** Callbacks suspeitos não encerram a tentativa nem chegam ao token endpoint. */
    @Test
    fun `rejeita state caminho metodo e parametros invalidos antes de aceitar callback`() = runBlocking {
        manager { uri ->
            val valid = callback(uri)
            val base = parameters(uri.rawQuery).getValue("redirect_uri")
            assertEquals(400, request(URI("$base?code=falso")))
            assertEquals(400, request(URI("$base?state=errado&code=falso")))
            assertEquals(400, request(URI("$valid&state=duplicado")))
            assertEquals(400, request(URI("$valid&code=duplicado")))
            assertEquals(400, request(URI("$valid&error=access_denied")))
            assertEquals(400, request(callback(uri, mapOf("code" to ""))))
            assertEquals(400, request(callback(uri, emptyMap())))
            assertEquals(404, request(URI(base.replace("/callback", "/callback/outro") + "?state=x&code=y")))
            assertEquals(404, request(valid, "POST"))
            assertEquals(200, request(valid))
            assertEquals(409, request(valid))
        }.authorize()
        Unit
    }

    /** Recusa com state válido é terminal, sanitizada e não dispara troca de tokens. */
    @Test
    fun `recusa encerra servidor sem expor descricao remota`() = runBlocking {
        lateinit var authorization: URI
        val failure = assertFailsWith<OAuthException> {
            manager(exchange = OAuthTokenExchange { _, _, _, _ -> error("Não deve trocar tokens") }) { uri ->
                authorization = uri
                request(callback(uri, mapOf("error" to "access_denied", "error_description" to "segredo-remoto")))
            }.authorize()
        }
        assertEquals(OAuthFailure.DENIED, failure.reason)
        assertFalse(failure.toString().contains("segredo-remoto"))
        assertNull(failure.cause)
        assertPortReleased(authorization)
    }

    /** Fechar o navegador sem callback termina pelo timeout e libera o listener. */
    @Test
    fun `timeout libera porta`() = runBlocking {
        lateinit var authorization: URI
        val failure = assertFailsWith<OAuthException> {
            manager(timeout = Duration.ofMillis(300)) { authorization = it }.authorize()
        }
        assertEquals(OAuthFailure.TIMEOUT, failure.reason)
        assertPortReleased(authorization)
    }

    /** Cancelar a corrotina mantém o cancelamento e fecha a porta sem esperar o timeout de cinco minutos. */
    @Test
    fun `cancelamento durante espera libera porta`() = runBlocking {
        val opened = CompletableDeferred<URI>()
        val pending = async { manager { opened.complete(it) }.authorize() }
        val authorization = withTimeout(5000) { opened.await() }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertPortReleased(authorization)
    }

    /** O orçamento total também inclui a troca dos tokens, com fechamento em cancelamento interno. */
    @Test
    fun `timeout durante troca de tokens libera porta`() = runBlocking {
        lateinit var authorization: URI
        val failure = assertFailsWith<OAuthException> {
            manager(timeout = Duration.ofMillis(300), exchange = OAuthTokenExchange { _, _, _, _ -> awaitCancellation() }) {
                authorization = it
                request(callback(it))
            }.authorize()
        }
        assertEquals(OAuthFailure.TIMEOUT, failure.reason)
        assertPortReleased(authorization)
    }

    /** Falha na abertura não deixa um listener órfão nem vaza o texto de exceções AWT. */
    @Test
    fun `falha do navegador e sanitizada e fecha porta`() = runBlocking {
        lateinit var authorization: URI
        val failure = assertFailsWith<OAuthException> {
            manager { authorization = it; error("segredo-remoto") }.authorize()
        }
        assertEquals(OAuthFailure.BROWSER, failure.reason)
        assertFalse(failure.toString().contains("segredo-remoto"))
        assertNull(failure.cause)
        assertPortReleased(authorization)
    }

    /** Uma troca recusada pelo endpoint também fecha o servidor que já recebeu o callback. */
    @Test
    fun `falha na troca fecha porta`() = runBlocking {
        lateinit var authorization: URI
        val failure = assertFailsWith<OAuthException> {
            manager(exchange = OAuthTokenExchange { _, _, _, _ ->
                throw OAuthException(OAuthFailure.TOKEN_EXCHANGE, "Falha simulada")
            }) { authorization = it; request(callback(it)) }.authorize()
        }
        assertEquals(OAuthFailure.TOKEN_EXCHANGE, failure.reason)
        assertPortReleased(authorization)
    }

    /** Reutilizar o manager deve produzir tentativas independentes, não reutilizar state/PKCE. */
    @Test
    fun `cada tentativa usa state e challenge novos`() = runBlocking {
        val urls = mutableListOf<URI>()
        val manager = manager { urls.add(it); request(callback(it)) }
        repeat(2) { manager.authorize() }
        val first = parameters(urls[0].rawQuery)
        val second = parameters(urls[1].rawQuery)
        assertNotEquals(first["state"], second["state"])
        assertNotEquals(first["code_challenge"], second["code_challenge"])
        urls.forEach(::assertPortReleased)
    }

    /** Erros de configuração são detectados antes de abrir o navegador ou criar o listener. */
    @Test
    fun `configuracao ausente invalida web ou endpoint estranho e recusada`() = runBlocking {
        val paths = listOf(
            temporaryFolder.root.toPath().resolve("ausente.json"),
            temporaryFolder.newFile().apply { writeText("invalido segredo-ficticio") }.toPath(),
            temporaryFolder.newFile().apply { writeText("""{"web":{}}""") }.toPath(),
            temporaryFolder.newFile().apply { writeText("""{"installed":{"client_id":null}}""") }.toPath(),
            configurationFile("https://example.invalid/auth"),
            configurationFile().also { path ->
                Files.writeString(path, Files.readString(path).replace(TOKEN_ENDPOINT, "https://example.invalid/token"))
            },
        )
        paths.forEach { path ->
            val failure = assertFailsWith<OAuthException> { manager(path = path) { error("Não deve abrir") }.authorize() }
            assertEquals(OAuthFailure.CONFIGURATION, failure.reason)
            assertFalse(failure.toString().contains("segredo-ficticio"))
            assertNull(failure.cause)
        }
    }

    /** Tokens não devem surgir na representação textual do resultado usada em logs. */
    @Test
    fun `representacao das credenciais omite tokens`() {
        assertFalse(fakeCredentials.toString().contains(fakeCredentials.accessToken))
        assertFalse(fakeCredentials.toString().contains(fakeCredentials.refreshToken))
    }

    /** Bind no mesmo endereço prova a liberação efetiva do recurso, além do retorno da corrotina. */
    private fun assertPortReleased(authorization: URI) {
        val redirect = URI(parameters(authorization.rawQuery).getValue("redirect_uri"))
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", redirect.port))
        }
    }
}
