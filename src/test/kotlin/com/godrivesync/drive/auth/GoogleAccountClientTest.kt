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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Testa o contrato HTTP real da identificação com servidor loopback e tokens exclusivamente fictícios. */
class GoogleAccountClientTest {
    /** O token vai apenas no cabeçalho e a consulta solicita somente o e-mail. */
    @Test
    fun `identifica email com Bearer sem token na URL`() = runBlocking {
        withServer({ exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("fields=user(emailAddress)", exchange.requestURI.rawQuery)
            assertEquals("Bearer acesso-ficticio", exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, """{"user":{"emailAddress":"teste@example.invalid"}}""")
        }) { endpoint ->
            assertEquals("teste@example.invalid", GoogleAccountClient(endpoint).email("acesso-ficticio"))
        }
    }

    /** Redirect nunca recebe o Bearer; erros HTTP/JSON não vazam corpo remoto nem causas. */
    @Test
    fun `recusa redirects erros e identidade invalida`() = runBlocking {
        val invalid = listOf(302 to "segredo-remoto", 401 to "segredo-remoto", 500 to "segredo-remoto",
            200 to "segredo-remoto", 200 to "{}", 200 to """{"user":{"emailAddress":123}}""",
            200 to """{"user":{"emailAddress":""}}""", 200 to """{"user":{"emailAddress":"nao-email"}}""")
        for ((status, body) in invalid) {
            var requests = 0
            withServer({ exchange ->
                requests++
                exchange.responseHeaders.set("Location", "/outro")
                respond(exchange, status, body)
            }) { endpoint ->
                val failure = assertFailsWith<AccountAuthorizationException> { GoogleAccountClient(endpoint).email("acesso-ficticio") }
                assertEquals(1, requests)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("segredo-remoto"))
                assertFalse(failure.toString().contains("acesso-ficticio"))
            }
        }
    }

    /** Cancelar o serviço abandona a consulta pendente; não espera a resposta para liberar a corrotina. */
    @Test
    fun `cancelamento aborta identificacao pendente`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            withServer({ exchange ->
                started.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            }) { endpoint ->
                val pending = async { GoogleAccountClient(endpoint).email("acesso-ficticio") }
                withTimeout(3000) { started.await() }
                withTimeout(2000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                release.countDown()
            }
        } finally {
            release.countDown()
        }
    }

    /** Porta encerrada simula rede indisponível sem depender de endereço externo ou internet. */
    @Test
    fun `falha de conexao retorna erro local`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI("http://127.0.0.1:${server.address.port}/about")
        server.start()
        server.stop(0)
        val failure = assertFailsWith<AccountAuthorizationException> { GoogleAccountClient(endpoint).email("acesso-ficticio") }
        assertNull(failure.cause)
    }

    /** Mantém o servidor vivo durante a chamada suspensa e fecha-o também quando uma asserção falha. */
    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        try {
            block(URI("http://127.0.0.1:${server.address.port}/about?fields=user(emailAddress)"))
        } finally {
            server.stop(0)
        }
    }

    /** Responde com JSON de teste e fecha corpo/conexão sem registrar tokens. */
    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.use {
            val bytes = body.toByteArray(Charsets.UTF_8)
            it.sendResponseHeaders(status, bytes.size.toLong())
            it.responseBody.write(bytes)
        }
    }
}
