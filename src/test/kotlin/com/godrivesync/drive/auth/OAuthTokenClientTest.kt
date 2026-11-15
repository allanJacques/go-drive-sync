package com.godrivesync.drive.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Exercita POST real em endpoint local, incluindo cancelamento, redirects e validação do JSON. */
class OAuthTokenClientTest : OAuthTestSupport() {
    /** Relógio fixo torna a expiração verificável sem tolerâncias dependentes da velocidade da máquina. */
    private val clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC)

    /** Resposta fictícia válida, nunca obtida de uma conta Google. */
    private val validResponse = """{"access_token":"acesso-ficticio","refresh_token":"renovacao-ficticia","expires_in":3600,"token_type":"Bearer","scope":"$DRIVE_SCOPE"}"""

    /** Executa manager e cliente juntos, conferindo os campos e a codificação do POST recebido. */
    @Test
    fun `fluxo completo envia POST e converte expiracao`() = runBlocking {
        lateinit var authorization: URI
        var body = ""
        withServer({ exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/x-www-form-urlencoded", exchange.requestHeaders.getFirst("Content-Type"))
            body = exchange.requestBody.bufferedReader().readText()
            respond(exchange, 200, validResponse)
        }) { endpoint ->
            val result = manager(exchange = OAuthTokenClient(endpoint, clock)) {
                authorization = it
                request(callback(it, mapOf("code" to "codigo+&=/ ç")))
            }.authorize()
            assertEquals("acesso-ficticio", result.accessToken)
            assertEquals("renovacao-ficticia", result.refreshToken)
            assertEquals(clock.instant().plusSeconds(3600), result.expiresAt)
            val posted = parameters(body)
            assertEquals("authorization_code", posted["grant_type"])
            assertEquals("cliente-ficticio", posted["client_id"])
            assertEquals("segredo-ficticio", posted["client_secret"])
            assertEquals("codigo+&=/ ç", posted["code"])
            assertEquals(parameters(authorization.rawQuery)["redirect_uri"], posted["redirect_uri"])
            assertEquals(43, posted.getValue("code_verifier").length)
        }
    }

    /** HTTP de erro e redirects não expõem o corpo nem reenviam o código a outra URL. */
    @Test
    fun `recusa erro HTTP e redirect sem propagar corpo`() = runBlocking {
        for (status in listOf(400, 500, 302)) {
            var requests = 0
            withServer({ exchange ->
                requests++
                exchange.responseHeaders.set("Location", "/redirecionado")
                respond(exchange, status, "segredo-remoto")
            }) { endpoint ->
                val failure = assertFailsWith<OAuthException> { exchange(endpoint) }
                assertEquals(OAuthFailure.TOKEN_EXCHANGE, failure.reason)
                assertFalse(failure.toString().contains("segredo-remoto"))
                assertNull(failure.cause)
                assertEquals(1, requests)
            }
        }
    }

    /** Sem refresh token, validade positiva, Bearer ou Drive, o resultado não pode alimentar a aplicação. */
    @Test
    fun `rejeita respostas de token incompletas e escopo insuficiente`() = runBlocking {
        val invalid = listOf(
            "invalido segredo-remoto", "{}",
            validResponse.replace("\"refresh_token\":\"renovacao-ficticia\",", ""),
            validResponse.replace("\"acesso-ficticio\"", "null"),
            validResponse.replace("renovacao-ficticia", ""),
            validResponse.replace("3600", "0"),
            validResponse.replace("3600", "-1"),
            validResponse.replace("3600", "9223372036854775807"),
            validResponse.replace("Bearer", "Outro"),
            validResponse.replace(DRIVE_SCOPE, "https://www.googleapis.com/auth/drive.file"),
        )
        for (body in invalid) {
            withServer({ respond(it, 200, body) }) { endpoint ->
                val failure = assertFailsWith<OAuthException> { exchange(endpoint) }
                assertEquals(OAuthFailure.INVALID_TOKEN_RESPONSE, failure.reason)
                assertFalse(failure.toString().contains("ficticio"))
                assertFalse(failure.toString().contains("segredo-remoto"))
                assertNull(failure.cause)
            }
        }
    }

    /** A omissão de scope preserva o escopo solicitado segundo o contrato OAuth. */
    @Test
    fun `aceita resposta que omite scope`() = runBlocking {
        withServer({ respond(it, 200, validResponse.replace(",\"scope\":\"$DRIVE_SCOPE\"", "")) }) { endpoint ->
            assertEquals("acesso-ficticio", exchange(endpoint).accessToken)
        }
    }

    /** Cancelamento abandona a requisição pendente antes de o servidor responder. */
    @Test
    fun `cancela requisicao HTTP pendente`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            withServer({ exchange ->
                started.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            }) { endpoint ->
                val pending = async { exchange(endpoint) }
                withTimeout(3000) { started.await() }
                withTimeout(2000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                release.countDown()
            }
        } finally {
            release.countDown()
        }
    }

    /** Conexão recusada é traduzida para erro de domínio sem causa remota. */
    @Test
    fun `falha de rede e sanitizada`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI("http://127.0.0.1:${server.address.port}/token")
        server.start()
        server.stop(0)
        val failure = assertFailsWith<OAuthException> { exchange(endpoint) }
        assertEquals(OAuthFailure.TOKEN_EXCHANGE, failure.reason)
        assertNull(failure.cause)
    }

    /** Chama o cliente real com valores fictícios e endpoint local. */
    private suspend fun exchange(endpoint: URI): OAuthCredentials = OAuthTokenClient(endpoint, clock).exchange(
        OAuthConfiguration("cliente", "segredo"), "codigo", "verificador", URI("http://127.0.0.1:12345/callback"),
    )

    /** Abre um servidor temporário e garante encerramento mesmo quando a asserção falha. */
    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        try {
            block(URI("http://127.0.0.1:${server.address.port}/token"))
        } finally {
            server.stop(0)
        }
    }

    /** Responde JSON fictício e fecha o exchange, evitando conexões abandonadas nos testes. */
    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.use {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }
}
