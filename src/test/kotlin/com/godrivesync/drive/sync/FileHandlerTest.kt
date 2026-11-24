package com.godrivesync.drive.sync

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.drive.api.DriveApiClient
import com.godrivesync.drive.auth.AccountAuthorizationService
import com.godrivesync.drive.auth.AccountCredentialManager
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import com.godrivesync.drive.auth.OAuthConfiguration
import com.godrivesync.drive.auth.OAuthCredentials
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import kotlin.test.*

/** Exercita o FileHandler com o SDK, o manager da conta e o sistema de arquivos, usando dados descartáveis. */
class FileHandlerTest : RepositoryTestSupport() {
    /** Transporte local configurável. Conta os GETs de metadados e mídia e registra o backoff solicitado. */
    private class DownloadServer : HttpTransport() {
        /** Origem aceita pelo manager de teste e contadores das requisições recebidas pelo transporte. */
        val origin = URI("https://drive.example.invalid")
        var metadataCalls = 0
        var mediaCalls = 0
        /** As esperas injetadas permitem verificar os intervalos sem acrescentar segundos aos testes. */
        val waits = mutableListOf<Long>()
        /** Cada chamada produz uma nova resposta, inclusive quando o download precisa ser repetido. */
        var metadata: () -> MockLowLevelHttpResponse = { metadataResponse(4) }
        var media: () -> MockLowLevelHttpResponse = { MockLowLevelHttpResponse().setContent("1234") }

        /** Confere o contrato HTTP e o token fictício que o SDK enviaria, antes de fornecer a resposta. */
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                /** Encaminha cada GET autenticado ao cenário configurado, sem rede ou credenciais reais. */
                override fun execute(): MockLowLevelHttpResponse {
                    val uri = URI(url)
                    assertEquals(origin.host, uri.host)
                    assertEquals("GET", method)
                    assertEquals("Bearer teste", getFirstHeaderValue("Authorization"))
                    val query = URLDecoder.decode(uri.rawQuery, Charsets.UTF_8)
                    return when (uri.path) {
                        "/drive/v3/files/item" -> {
                            assertEquals("fields=mimeType,size,capabilities/canDownload", query)
                            metadataCalls++
                            metadata()
                        }
                        "/download/drive/v3/files/item" -> {
                            assertEquals("alt=media", query)
                            mediaCalls++
                            media()
                        }
                        else -> error("Caminho inesperado no teste de download.")
                    }
                }
            }
    }

    /** Monta uma conta isolada com renovação real disponível e fornece uma pasta exclusiva para a saída. */
    private fun scenario(block: suspend DownloadServer.(FileHandler, Path) -> Unit) = withDatabase { db ->
        runBlocking {
            val now = Instant.parse("2026-11-24T12:00:00Z")
            val server = DownloadServer()
            val accounts = AccountAuthorizationService(AccountRepository(db),
                LocalTokenKeyStore(temporaryFolder.root.toPath().resolve("private/key")),
                { OAuthCredentials("teste", "refresh", now.plusSeconds(3600)) }, { "teste@example.invalid" })
            val manager = AccountCredentialManager(accounts, { OAuthConfiguration("cliente", "segredo") },
                server, "${server.origin}/token", server.origin, Clock.fixed(now, ZoneOffset.UTC))
            val client = DriveApiClient(accounts.authorizeAndSave().id, manager, server, "${server.origin}/")
            val destination = temporaryFolder.newFolder().toPath().resolve("arquivo.bin")
            server.block(FileHandler(client) { server.waits.add(it) }, destination)
            // Depois de sucesso, falha ou cancelamento nenhum arquivo parcial deve ficar ao lado do destino.
            Files.walk(destination.parent).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".part") })
            }
        }
    }

    /** Mostra o destino antigo durante toda a cópia e verifica progresso incremental e bytes binários ao final. */
    @Test
    fun `substitui somente apos gravar todos os blocos`() = scenario { handler, target ->
        val bytes = ByteArray(220_000) { it.toByte() }
        metadata = { metadataResponse(bytes.size.toLong()) }
        val response = MockLowLevelHttpResponse().setContent(bytes)
        media = { response }
        Files.writeString(target, "anterior")
        val progress = mutableListOf<Long>()
        handler.downloadFile("item", target) { written, total ->
            assertEquals("anterior", Files.readString(target))
            assertEquals(bytes.size.toLong(), total)
            progress.add(written)
        }
        assertEquals(0L, progress.first())
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.size > 2 && progress.zipWithNext().all { (a, b) -> a < b })
        assertContentEquals(bytes, Files.readAllBytes(target))
        assertTrue(response.isDisconnected)
        assertEquals(1, mediaCalls)
        assertTrue(waits.isEmpty())
    }

    /** Distingue os três totais possíveis e cria o diretório pai para o primeiro download. */
    @Test
    fun `trata arquivo vazio tamanho desconhecido e tamanho do HTTP`() = scenario { handler, target ->
        val nested = target.resolveSibling("subpasta").resolve("novo")
        metadata = { metadataResponse(0) }
        media = { MockLowLevelHttpResponse().setZeroContent() }
        val progress = mutableListOf<Pair<Long, Long?>>()
        handler.downloadFile("item", nested) { n, total -> progress.add(n to total) }
        assertEquals(listOf<Pair<Long, Long?>>(0L to 0L), progress)
        assertEquals(0L, Files.size(nested))

        metadata = { metadataResponse(null) }
        for (lengthHeader in listOf(false, true)) {
            progress.clear()
            media = { MockLowLevelHttpResponse().setContent("abc".byteInputStream()).apply {
                if (lengthHeader) addHeader("Content-Length", "3")
            } }
            handler.downloadFile("item", nested) { n, total -> progress.add(n to total) }
            val expected = if (lengthHeader) 3L else null
            assertEquals(listOf(0L to expected, 3L to expected), progress)
            assertEquals("abc", Files.readString(nested))
        }
    }

    /** Com gzip, o SDK entrega os bytes descomprimidos. O comprimento do corpo HTTP não pode limitar a cópia. */
    @Test
    fun `comprimento comprimido nao vira total de progresso`() = scenario { handler, target ->
        val plain = ByteArray(4_096) { 7 }
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(plain) }
        metadata = { metadataResponse(null) }
        media = {
            MockLowLevelHttpResponse().setContent(compressed.toByteArray()).setContentEncoding("gzip")
                .addHeader("Content-Length", compressed.size().toString())
        }
        handler.downloadFile("item", target) { _, total -> assertNull(total) }
        assertContentEquals(plain, Files.readAllBytes(target))
        assertEquals(1, mediaCalls)
    }

    /** Interrompe a leitura após dois bytes. A próxima tentativa reabre a mídia, zera o progresso e substitui o parcial. */
    @Test
    fun `reinicia apos desconexao no meio do stream`() = scenario { handler, target ->
        val partial = MockLowLevelHttpResponse().setContent(object : InputStream() {
            private var offset = 0
            /** Entrega dois bytes de uma versão anterior e depois simula uma desconexão. */
            override fun read(): Int = if (offset++ < 2) 'x'.code else throw IOException("detalhe remoto")
        })
        media = { if (mediaCalls == 1) partial else MockLowLevelHttpResponse().setContent("1234") }
        val progress = mutableListOf<Long>()
        handler.downloadFile("item", target) { n, _ -> progress.add(n) }
        assertEquals(listOf(0L, 2L, 0L, 4L), progress)
        assertEquals("1234", Files.readString(target))
        assertTrue(partial.isDisconnected)
        assertEquals(listOf(1_000L), waits)
        assertEquals(2, metadataCalls)
    }

    /** Confere as duas esperas, o limite de tentativas e a integridade do destino para falta ou excesso de bytes. */
    @Test
    fun `tamanho divergente nunca substitui o destino`() = scenario { handler, target ->
        Files.writeString(target, "anterior")
        media = { MockLowLevelHttpResponse().setContent(if (mediaCalls == 2) "longo" else "abc") }
        val failure = assertFailsWith<CredentialException> { handler.downloadFile("item", target) { _, _ -> } }
        assertEquals(CredentialFailure.TEMPORARY, failure.reason)
        assertEquals(listOf(1_000L, 2_000L), waits)
        assertEquals(3, mediaCalls)
        assertEquals("anterior", Files.readString(target))
    }

    /** Exercita cota em metadados e indisponibilidade da mídia dentro do mesmo orçamento de três tentativas. */
    @Test
    fun `repete limite de frequencia e indisponibilidade`() = scenario { handler, target ->
        metadata = { if (metadataCalls == 1) errorResponse(403, "userRateLimitExceeded") else metadataResponse(4) }
        media = { if (mediaCalls == 1) errorResponse(503) else MockLowLevelHttpResponse().setContent("1234") }
        handler.downloadFile("item", target) { _, _ -> }
        assertEquals(listOf(1_000L, 2_000L), waits)
        assertEquals(3, metadataCalls)
        assertEquals(2, mediaCalls)
        assertEquals("1234", Files.readString(target))
    }

    /** Pastas, documentos, restrições de download e recusas permanentes encerram a operação na primeira tentativa. */
    @Test
    fun `rejeita itens e erros permanentes sem repetir`() = scenario { handler, target ->
        val rejected = listOf(metadataResponse(null, "application/vnd.google-apps.folder"),
            metadataResponse(null, "application/vnd.google-apps.document"), metadataResponse(1, allowed = false),
            errorResponse(404), errorResponse(403, "insufficientFilePermissions"))
        for (response in rejected) {
            metadata = { response }
            val failure = assertFailsWith<CredentialException> { handler.downloadFile("item", target) { _, _ -> } }
            assertEquals(CredentialFailure.API_REJECTED, failure.reason)
            assertFalse(failure.toString().contains("detalhe remoto"))
        }
        assertEquals(rejected.size, metadataCalls)
        assertEquals(0, mediaCalls)
        assertTrue(waits.isEmpty())
        assertFalse(Files.exists(target))
    }

    /** Cancela de dentro do progresso, quando o temporário já contém dados e o destino continua intacto. */
    @Test
    fun `cancelamento durante a copia descarta o temporario`() = scenario { handler, target ->
        Files.writeString(target, "anterior")
        coroutineScope {
            val job = launch {
                handler.downloadFile("item", target) { n, _ -> if (n > 0) cancel() }
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(1, mediaCalls)
        assertTrue(waits.isEmpty())
        assertEquals("anterior", Files.readString(target))
    }

    /** Erros de caminho ou do observador preservam a causa local e nunca acionam a política de rede. */
    @Test
    fun `falha local e erro do callback encerram imediatamente`() = scenario { handler, target ->
        Files.writeString(target, "anterior")
        assertFailsWith<IOException> { handler.downloadFile("item", target.resolve("filho")) { _, _ -> } }
        assertEquals(0, metadataCalls)
        val failure = assertFailsWith<IOException> {
            handler.downloadFile("item", target) { n, _ -> if (n > 0) throw IOException("erro do observador") }
        }
        assertEquals("erro do observador", failure.message)
        assertEquals(1, mediaCalls)
        assertTrue(waits.isEmpty())
        assertEquals("anterior", Files.readString(target))
    }

    /** Uma resposta parcial inesperada falha mesmo quando os metadados não permitem conferir o tamanho. */
    @Test
    fun `resposta parcial nao publica arquivo`() = scenario { handler, target ->
        metadata = { metadataResponse(null) }
        media = { MockLowLevelHttpResponse().setStatusCode(206).setContent("parte") }
        val failure = assertFailsWith<CredentialException> { handler.downloadFile("item", target) { _, _ -> } }
        assertEquals(CredentialFailure.INVALID_RESPONSE, failure.reason)
        assertFalse(Files.exists(target))
        assertTrue(waits.isEmpty())
    }
}

/** Cria a projeção de metadados do Drive e permite omitir o tamanho como numa resposta real. */
private fun metadataResponse(size: Long?, mime: String = "application/octet-stream", allowed: Boolean = true):
    MockLowLevelHttpResponse {
    val sizeField = size?.let { ""","size":"$it"""" }.orEmpty()
    return MockLowLevelHttpResponse().setContentType("application/json")
        .setContent("""{"mimeType":"$mime","capabilities":{"canDownload":$allowed}$sizeField}""")
}

/** Gera HTTP recusado com motivo estruturado para verificar a distinção entre cota e permissão. */
private fun errorResponse(status: Int, reason: String = "backendError"): MockLowLevelHttpResponse =
    MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json")
        .setContent("""{"error":{"code":$status,"message":"detalhe remoto","errors":[{"reason":"$reason"}]}}""")
