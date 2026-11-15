package com.godrivesync.drive.auth

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlin.test.*

/** Testa a resposta HTTP real: estados visuais, recursos embutidos e ausência de conteúdo OAuth no HTML. */
class OAuthCallbackPageTest {
    /** Código só chega ao chamador Kotlin; HTML deve ser completo e não afirmar conclusão do login. */
    @Test
    fun `callback entrega pagina completa sem refletir codigo e state`() = runBlocking {
        val state = "state-ficticio-nao-exibir"
        val code = "codigo-ficticio-<script>alert('x')</script>"
        LoopbackReceiver(state).use { receiver ->
            val response = read(URI("${receiver.redirectUri}?" + encodeForm(mapOf("state" to state, "code" to code))))
            assertEquals(200, response.status)
            assertTrue(response.body.contains("<h1 id=\"page-title\">Resposta recebida</h1>"))
            assertTrue(response.body.contains("conferir a conclusão"))
            assertFalse(response.body.contains("Autorização concluída"))
            assertFalse(response.body.contains(code))
            assertFalse(response.body.contains(state))
            assertFalse(response.body.contains("<script"))
            assertFalse(response.body.contains("{{"))
            assertTrue(response.body.contains("<html lang=\"pt-BR\">"))
            assertTrue(response.body.contains("name=\"viewport\""))
            assertEquals("text/html; charset=utf-8", response.contentType)
            assertEquals("no-store", response.cacheControl)
            assertEquals("no-referrer", response.referrerPolicy)
            assertTrue(response.csp.contains("default-src 'none'"))
            assertTrue(response.csp.contains("img-src data:"))
            assertTrue(response.csp.contains("frame-ancestors 'none'"))
            // Confere que o PNG entregue é exatamente o logo do classpath, sem endpoint adicional.
            val embedded = Regex("<img src=\"data:image/png;base64,([^\"]+)\"").find(response.body)
            assertNotNull(embedded)
            val expected = javaClass.getResourceAsStream("/icons/app-icon.png")!!.use { it.readAllBytes() }
            assertContentEquals(expected, Base64.getDecoder().decode(embedded.groupValues[1]))
            assertEquals(code, receiver.awaitCode())
        }
    }

    /** Recusa usa texto próprio, sem expor a descrição arbitrária recebida do provedor. */
    @Test
    fun `recusa tem pagina propria sem descricao remota`() = runBlocking {
        LoopbackReceiver("state-ficticio").use { receiver ->
            val description = "descricao-remota-<img src=x onerror=alert(1)>"
            val response = read(URI("${receiver.redirectUri}?" + encodeForm(mapOf(
                "state" to "state-ficticio", "error" to "access_denied", "error_description" to description,
            ))))
            assertEquals(200, response.status)
            assertTrue(response.body.contains("Autorização não concedida"))
            assertFalse(response.body.contains("Resposta recebida"))
            assertFalse(response.body.contains(description))
            val failure = assertFailsWith<OAuthException> { receiver.awaitCode() }
            assertEquals(OAuthFailure.DENIED, failure.reason)
        }
    }

    /** Erros mantêm status HTTP e orientação específica; um inválido não consome o callback seguinte. */
    @Test
    fun `retornos invalido desconhecido e repetido preservam seus estados HTTP`() = runBlocking {
        LoopbackReceiver("state-ficticio").use { receiver ->
            val invalid = read(URI("${receiver.redirectUri}?state=errado&code=nao-exibir"))
            assertEquals(400, invalid.status)
            assertTrue(invalid.body.contains("Retorno inválido"))
            assertFalse(invalid.body.contains("nao-exibir"))
            val missing = read(receiver.redirectUri.resolve("/desconhecido"))
            assertEquals(404, missing.status)
            assertTrue(missing.body.contains("Página não encontrada"))
            val valid = URI("${receiver.redirectUri}?state=state-ficticio&code=codigo-ficticio")
            assertEquals(200, read(valid).status)
            val repeated = read(valid)
            assertEquals(409, repeated.status)
            assertTrue(repeated.body.contains("Resposta já recebida"))
            assertEquals("codigo-ficticio", receiver.awaitCode())
        }
    }

    /** Fotografia da resposta local para verificar corpo e cabeçalhos depois de fechar a conexão. */
    private data class Response(
        val status: Int,
        val body: String,
        val contentType: String,
        val cacheControl: String,
        val referrerPolicy: String,
        val csp: String,
    )

    /** Lê respostas de sucesso e erro em UTF-8 com timeout; não segue redirects nem acessa o Google. */
    private fun read(uri: URI): Response {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Response(
                status, stream.bufferedReader(Charsets.UTF_8).use { it.readText() },
                connection.contentType, connection.getHeaderField("Cache-Control"),
                connection.getHeaderField("Referrer-Policy"), connection.getHeaderField("Content-Security-Policy"),
            )
        } finally {
            connection.disconnect()
        }
    }
}
