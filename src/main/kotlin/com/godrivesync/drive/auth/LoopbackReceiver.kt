package com.godrivesync.drive.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Recebe um único resultado OAuth em IPv4 loopback, com porta escolhida pelo sistema operacional.
 * O servidor usa sua própria thread; CompletableDeferred entrega o código à corrotina sem bloquear a UI.
 * Callbacks inválidos não consomem a tentativa válida. O dono deve chamar close em todos os desfechos.
 * @param expectedState Valor imprevisível desta tentativa, validado antes de aceitar código ou recusa.
 */
internal class LoopbackReceiver(private val expectedState: String) : AutoCloseable {
    /** Carrega os recursos antes de abrir a porta: falha de empacotamento não deixa listener órfão. */
    private val page = OAuthCallbackPage

    /** Resultado de uma única tentativa; completar novamente não substitui o primeiro resultado. */
    private val result = CompletableDeferred<String>()

    /** Bind explícito evita expor o callback à rede; porta zero elimina a corrida de procurar porta livre. */
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Exatamente a mesma URI é usada no navegador e na troca do código. */
    val redirectUri: URI = URI("http://127.0.0.1:${server.address.port}/callback")

    init {
        try {
            server.createContext("/", ::handle)
            server.start()
        } catch (failure: Exception) {
            server.stop(0)
            throw failure
        }
    }

    /** Suspende até receber código/recusa; o cancelamento do chamador não fica preso em accept(). */
    suspend fun awaitCode(): String = result.await()

    /** Encerra listener e espera pendente; pode ser chamado pelo finally mesmo após sucesso. */
    override fun close() {
        server.stop(0)
        result.cancel()
    }

    /**
     * Valida método, caminho, parâmetros únicos e state. Nunca reflete parâmetros na página HTML.
     * A resposta é enviada antes de liberar o manager, para ele não fechar o socket prematuramente.
     */
    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestMethod != "GET" || exchange.requestURI.rawPath != "/callback") {
                respond(exchange, 404, OAuthCallbackState.NOT_FOUND)
                return
            }
            val parameters = try {
                parseQuery(exchange.requestURI.rawQuery ?: "")
            } catch (_: IllegalArgumentException) {
                respond(exchange, 400, OAuthCallbackState.INVALID)
                return
            }
            val state = parameters["state"]
            val code = parameters["code"]
            val error = parameters["error"]
            val validState = state != null && MessageDigest.isEqual(
                expectedState.toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8),
            )
            if (!validState || (code == null) == (error == null) || code?.isBlank() == true || error?.isBlank() == true) {
                respond(exchange, 400, OAuthCallbackState.INVALID)
                return
            }
            if (result.isCompleted) {
                respond(exchange, 409, OAuthCallbackState.ALREADY_RECEIVED)
                return
            }
            // Mesmo se o navegador fechar a conexão, o resultado validado deve chegar ao chamador.
            try {
                respond(exchange, 200, if (error != null) OAuthCallbackState.DENIED else OAuthCallbackState.RECEIVED)
            } finally {
                if (error != null) {
                    result.completeExceptionally(OAuthException(OAuthFailure.DENIED, "A autorização não foi concedida."))
                } else {
                    result.complete(requireNotNull(code))
                }
            }
        }
    }

    /** Decodifica query de tamanho limitado e rejeita duplicatas, evitando interpretações ambíguas. */
    private fun parseQuery(query: String): Map<String, String> {
        require(query.length <= 8192)
        val parameters = mutableMapOf<String, String>()
        query.split('&').forEach { part ->
            val pair = part.split('=', limit = 2)
            require(pair.size == 2)
            val key = URLDecoder.decode(pair[0], Charsets.UTF_8)
            val value = URLDecoder.decode(pair[1], Charsets.UTF_8)
            require(parameters.put(key, value) == null)
        }
        return parameters
    }

    /**
     * Entrega HTML completo em UTF-8, sem cache nem dados OAuth refletidos.
     * A política permite apenas CSS embutido e imagens data URI; bloqueia scripts, frames e conexões externas.
     * Referrer-Policy impede encaminhar a URL do callback com seu código como referência de navegação.
     */
    private fun respond(exchange: HttpExchange, status: Int, state: OAuthCallbackState) {
        val bytes = page.render(state).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
