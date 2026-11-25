package com.godrivesync.drive.sync

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.drive.api.DriveApiClient
import com.godrivesync.drive.api.DriveExportCatalog
import com.godrivesync.drive.api.DriveExportException
import com.godrivesync.drive.api.DriveExportFailure
import com.godrivesync.drive.api.DriveExportStage
import com.godrivesync.drive.api.GoogleDocumentType
import com.godrivesync.drive.auth.AccountAuthorizationService
import com.godrivesync.drive.auth.AccountCredentialManager
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import com.godrivesync.drive.auth.OAuthConfiguration
import com.godrivesync.drive.auth.OAuthCredentials
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    /** Transporte local que simula mídia, metadados e sessões de upload da conta de teste. */
    private class FileServer : HttpTransport() {
        /** Origem aceita pelo manager de teste e contadores das requisições recebidas pelo transporte. */
        val origin = URI("https://drive.example.invalid")
        var metadataCalls = 0
        var mediaCalls = 0
        /** As esperas injetadas permitem verificar os intervalos sem acrescentar segundos aos testes. */
        val waits = mutableListOf<Long>()
        /** Cada chamada produz uma nova resposta, inclusive quando o download precisa ser repetido. */
        var metadata: () -> MockLowLevelHttpResponse = { metadataResponse(4) }
        var media: () -> MockLowLevelHttpResponse = { MockLowLevelHttpResponse().setContent("1234") }

        /** Contagem do catálogo e sequência de MIME pedidos permitem conferir a descoberta e as recusas. */
        var catalogCalls = 0
        val exportRequests = mutableListOf<String>()
        /** Respostas do catálogo e da conversão, geradas novamente a cada requisição simulada. */
        var catalog: () -> MockLowLevelHttpResponse = { catalogResponse() }
        var export: (String) -> MockLowLevelHttpResponse = { MockLowLevelHttpResponse().setContent(it) }

        /** Responde ao POST de criação ou ao PUT da sessão conforme o cenário do teste. */
        var upload: MockLowLevelHttpRequest.(String, URI) -> MockLowLevelHttpResponse = { _, _ ->
            error("Upload inesperado neste cenário.")
        }

        /** Confere o contrato HTTP e o token fictício que o SDK enviaria, antes de fornecer a resposta. */
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                /** Encaminha cada requisição autenticada ao cenário configurado, sem rede ou credenciais reais. */
                override fun execute(): MockLowLevelHttpResponse {
                    val uri = URI(url)
                    assertEquals(origin.host, uri.host)
                    assertEquals("Bearer teste", getFirstHeaderValue("Authorization"))
                    if (method == "POST" || method == "PUT") return upload(method, uri)
                    assertEquals("GET", method)
                    val query = URLDecoder.decode(uri.rawQuery, Charsets.UTF_8)
                    return when (uri.path) {
                        "/drive/v3/about" -> {
                            assertEquals("fields=exportFormats", query)
                            catalogCalls++
                            catalog()
                        }
                        "/download/drive/v3/files/item/export" -> {
                            val params = uri.rawQuery.split('&').associate {
                                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                            }
                            assertEquals("media", params["alt"])
                            val requested = params.getValue("mimeType")
                            exportRequests.add(requested)
                            export(requested)
                        }
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

    /** Atalho para os testes que usam somente o manipulador de arquivos. */
    private fun scenario(block: suspend FileServer.(FileHandler, Path) -> Unit) =
        scenarioWithClient { _, handler, path -> block(handler, path) }

    /** Monta conta, SDK e pasta isolados. O mesmo cliente permite testar a reutilização do catálogo. */
    private fun scenarioWithClient(block: suspend FileServer.(DriveApiClient, FileHandler, Path) -> Unit) = withDatabase { db ->
        runBlocking {
            val now = Instant.parse("2026-11-24T12:00:00Z")
            val server = FileServer()
            val accounts = AccountAuthorizationService(AccountRepository(db),
                LocalTokenKeyStore(temporaryFolder.root.toPath().resolve("private/key")),
                { OAuthCredentials("teste", "refresh", now.plusSeconds(3600)) }, { "teste@example.invalid" })
            val manager = AccountCredentialManager(accounts, { OAuthConfiguration("cliente", "segredo") },
                server, "${server.origin}/token", server.origin, Clock.fixed(now, ZoneOffset.UTC))
            val client = DriveApiClient(accounts.authorizeAndSave().id, manager, server, "${server.origin}/")
            val destination = temporaryFolder.newFolder().toPath().resolve("arquivo.bin")
            server.block(client, FileHandler(client) { server.waits.add(it) }, destination)
            // Depois de sucesso, falha ou cancelamento nenhum arquivo parcial deve ficar ao lado do destino.
            Files.walk(destination.parent).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".part") })
            }
        }
    }

    /** Confere metadados, bytes e progresso do multipart, incluindo arquivo vazio e o limite decimal exato. */
    @Test
    fun `envia arquivo vazio e limite de cinco MB diretamente`() = scenario { handler, target ->
        for (size in listOf(0, 5_000_000)) {
            val original = ByteArray(size) { it.toByte() }
            Files.write(target, original)
            var posts = 0
            upload = { method, uri ->
                posts++
                assertEquals("POST", method)
                assertEquals("/upload/drive/v3/files", uri.path)
                assertTrue(uri.query.contains("uploadType=multipart"))
                assertTrue(uri.query.contains("fields=id"))
                val body = ByteArrayOutputStream().also { streamingContent.writeTo(it) }.toByteArray()
                // ISO-8859-1 conserva a posição de cada byte ao localizar as duas partes do multipart.
                val wire = body.toString(Charsets.ISO_8859_1)
                assertContains(wire, """"name":"arquivo.bin"""")
                assertContains(wire, """"parents":["pasta"]""")
                val boundary = wire.substringBefore("\r\n")
                val firstByte = wire.indexOf("\r\n\r\n", wire.indexOf(boundary, boundary.length)) + 4
                assertContentEquals(original, body.copyOfRange(firstByte, firstByte + size))
                assertEquals("\r\n$boundary--\r\n", wire.substring(firstByte + size))
                createdFileResponse()
            }
            val progress = mutableListOf<Long>()
            val id = handler.uploadFile(target, "pasta") { sent, total ->
                assertEquals(size.toLong(), total)
                progress.add(sent)
            }
            assertEquals("remoto", id)
            assertEquals(1, posts)
            assertEquals(if (size == 0) listOf(0L) else listOf(0L, size.toLong()), progress)
        }
    }

    /** O servidor perde a resposta do primeiro bloco. A consulta de posição confirma só parte dos bytes. */
    @Test
    fun `retoma na mesma sessao a partir do ultimo byte confirmado`() = scenario { handler, target ->
        val bytes = ByteArray(5_000_001) { (it % 251).toByte() }
        Files.write(target, bytes)
        val received = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        var posts = 0
        var probes = 0
        upload = { method, uri ->
            when {
                method == "POST" -> {
                    posts++
                    assertEquals("/upload/drive/v3/files", uri.path)
                    assertContains(uri.query, "uploadType=resumable")
                    assertEquals(bytes.size.toString(), getFirstHeaderValue("X-Upload-Content-Length"))
                    assertContains(contentAsString, """"parents":["root"]""")
                    MockLowLevelHttpResponse().addHeader("Location", "$origin/session")
                }
                getFirstHeaderValue("Content-Range") == "bytes */5000001" -> {
                    probes++
                    assertEquals("/session", uri.path)
                    MockLowLevelHttpResponse().setStatusCode(308).addHeader("Range", "bytes=0-262143")
                }
                else -> {
                    assertEquals("PUT", method)
                    assertEquals("/session", uri.path)
                    val chunk = ByteArrayOutputStream().also { streamingContent.writeTo(it) }.toByteArray()
                    val start = received.size()
                    assertEquals("bytes $start-${start + chunk.size - 1}/5000001", getFirstHeaderValue("Content-Range"))
                    if (start == 0) {
                        received.write(chunk, 0, 262_144)
                        throw IOException("Resposta perdida no transporte de teste.")
                    }
                    received.write(chunk)
                    if (received.size() == bytes.size) createdFileResponse()
                    else MockLowLevelHttpResponse().setStatusCode(308)
                        .addHeader("Range", "bytes=0-${received.size() - 1}")
                }
            }
        }
        assertEquals("remoto", handler.uploadFile(target, "root") { sent, total ->
            assertEquals(bytes.size.toLong(), total)
            progress.add(sent)
        })
        assertEquals(1, posts)
        assertEquals(1, probes)
        assertContentEquals(bytes, received.toByteArray())
        assertEquals(listOf(0L, 262_144L), progress.take(2))
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
    }

    /** Se o último PUT receber 503, a consulta à sessão pode devolver o ID do item já criado. */
    @Test
    fun `recupera confirmacao final sem reenviar o arquivo`() = scenario { handler, target ->
        val total = 5_000_001
        Files.write(target, ByteArray(total))
        var accepted = 0
        var posts = 0
        var probes = 0
        upload = { method, _ ->
            when {
                method == "POST" -> {
                    posts++
                    MockLowLevelHttpResponse().addHeader("Location", "$origin/session")
                }
                getFirstHeaderValue("Content-Range") == "bytes */$total" -> {
                    probes++
                    assertEquals(total, accepted)
                    createdFileResponse()
                }
                else -> {
                    val chunk = ByteArrayOutputStream().also { streamingContent.writeTo(it) }
                    accepted += chunk.size()
                    if (accepted == total) errorResponse(503)
                    else MockLowLevelHttpResponse().setStatusCode(308).addHeader("Range", "bytes=0-${accepted - 1}")
                }
            }
        }
        assertEquals("remoto", handler.uploadFile(target, "pasta") { _, _ -> })
        assertEquals(1, posts)
        assertEquals(1, probes)
    }

    /** Uma recusa, perda da resposta final ou resposta sem ID jamais inicia outra criação ou informa sucesso. */
    @Test
    fun `falha de criacao nao repete o post`() = scenario { handler, target ->
        Files.writeString(target, "dados")
        var calls = 0
        for (reason in listOf(CredentialFailure.API_REJECTED, CredentialFailure.TEMPORARY, CredentialFailure.INVALID_RESPONSE)) {
            upload = { _, _ ->
                calls++
                when (reason) {
                    CredentialFailure.API_REJECTED -> errorResponse(403)
                    CredentialFailure.TEMPORARY -> throw IOException("Sem resposta.")
                    else -> MockLowLevelHttpResponse().setContentType("application/json").setContent("{}")
                }
            }
            val progress = mutableListOf<Long>()
            val failure = assertFailsWith<CredentialException> {
                handler.uploadFile(target, "pasta") { sent, _ -> progress.add(sent) }
            }
            assertEquals(reason, failure.reason)
            assertEquals(listOf(0L), progress)
        }
        assertEquals(3, calls)
    }

    /** Entradas inválidas falham antes do HTTP. Cancelar entre blocos impede o PUT seguinte. */
    @Test
    fun `valida origem e cancela entre blocos`() = scenario { handler, target ->
        for (invalid in listOf(target.parent, target)) {
            assertFailsWith<IllegalArgumentException> { handler.uploadFile(invalid, "pasta") { _, _ -> } }
        }
        Files.write(target, ByteArray(5_000_001))
        assertFailsWith<IllegalArgumentException> { handler.uploadFile(target, " ") { _, _ -> } }
        var chunks = 0
        upload = { method, _ ->
            if (method == "POST") MockLowLevelHttpResponse().addHeader("Location", "$origin/session")
            else {
                chunks++
                streamingContent.writeTo(java.io.OutputStream.nullOutputStream())
                MockLowLevelHttpResponse().setStatusCode(308).addHeader("Range", "bytes=0-1048575")
            }
        }
        coroutineScope {
            val job = launch { handler.uploadFile(target, "pasta") { sent, _ -> if (sent > 0) cancel() } }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(1, chunks)
    }

    /** Confere os padrões Office e várias saídas do mesmo original usando uma única consulta por conta. */
    @Test
    fun `exporta os formatos marcados e os tres padroes Office`() = scenarioWithClient { client, handler, target ->
        val available = client.listExportFormats()
        val base = target.resolveSibling("Revisão.v2")
        val examples = mapOf(
            GoogleDocumentType.DOCS to mapOf("application/pdf" to "pdf", "application/epub+zip" to "epub",
                "application/vnd.oasis.opendocument.text" to "odt"),
            GoogleDocumentType.SHEETS to mapOf("application/vnd.oasis.opendocument.spreadsheet" to "ods", "text/csv" to "csv"),
            GoogleDocumentType.SLIDES to emptyMap(),
        )
        for ((type, choices) in examples) {
            val defaultExtension = mapOf(GoogleDocumentType.DOCS to "docx", GoogleDocumentType.SHEETS to "xlsx",
                GoogleDocumentType.SLIDES to "pptx").getValue(type)
            val office = handler.exportFile("item", type.mimeType, base, catalog = available)
            assertEquals(base.resolveSibling("Revisão.v2.$defaultExtension"), office)
            assertEquals(type.officeMimeType, Files.readString(office))
            for ((mime, extension) in choices) {
                val output = handler.exportFile("item", type.mimeType, base, mime, available)
                assertEquals(base.resolveSibling("Revisão.v2.$extension"), output)
                assertEquals(mime, Files.readString(output))
            }
        }
        val pdf = base.resolveSibling("Revisão.v2.PDF")
        assertEquals(pdf, handler.exportFile("item", GoogleDocumentType.DOCS.mimeType, pdf, "application/pdf", available))
        assertEquals(1, catalogCalls)
        assertEquals(9, exportRequests.size)
        assertEquals(0, metadataCalls)
    }

    /** Um MIME inédito continua selecionável e exportável. Cada um recebe um caminho próprio e repetível. */
    @Test
    fun `catalogo dinamico preserva formatos novos`() = scenarioWithClient { client, handler, base ->
        val types = listOf("application/x-inedito+zip", "application/x-outro+zip")
        catalog = { catalogResponse(types) }
        val available = client.listExportFormats()
        val newFormats = available.formatsFor(GoogleDocumentType.DOCS).filter { it.mimeType in types }
        assertEquals(types, newFormats.map { it.displayName })
        assertTrue(newFormats.all { it.extension == null })
        assertTrue(available.formatsFor(GoogleDocumentType.SHEETS).none { it.mimeType in types })
        val paths = newFormats.map { format ->
            val output = handler.exportFile("item", GoogleDocumentType.DOCS.mimeType, base, format.mimeType, available)
            assertEquals(base.parent, output.parent)
            assertEquals(format.mimeType, Files.readString(output))
            assertEquals(output, handler.exportFile("item", GoogleDocumentType.DOCS.mimeType, output, format.mimeType, available))
            output
        }
        assertEquals(2, paths.distinct().size)
    }

    /** A ausência confirmada dispensa mídia e preserva as duas identidades MIME para o histórico. */
    @Test
    fun `formato retirado do catalogo preserva copia e demais formatos`() = scenario { handler, base ->
        val csv = base.resolveSibling("Dados.csv")
        Files.writeString(csv, "última versão")
        catalog = { catalogResponse(csv = false) }
        val failure = assertFailsWith<DriveExportException> {
            handler.exportFile("item", GoogleDocumentType.SHEETS.mimeType, csv, "text/csv")
        }
        assertEquals(DriveExportFailure.EXPORT_FORMAT_UNSUPPORTED, failure.reason)
        assertEquals(DriveExportStage.CATALOG, failure.stage)
        assertEquals(GoogleDocumentType.SHEETS.mimeType, failure.sourceMimeType)
        assertEquals("text/csv", failure.targetMimeType)
        assertNull(failure.statusCode)
        assertTrue(exportRequests.isEmpty())
        assertEquals("última versão", Files.readString(csv))
        val ods = handler.exportFile("item", GoogleDocumentType.SHEETS.mimeType, base,
            "application/vnd.oasis.opendocument.spreadsheet")
        assertTrue(Files.exists(ods))
    }

    /** Revalida após recusa explícita e distingue saída retirada, recusa de um item e catálogo indisponível. */
    @Test
    fun `recusa durante a exportacao reconsulta sem mascarar o motivo`() = scenarioWithClient { client, handler, base ->
        val type = GoogleDocumentType.SHEETS
        val previous = client.listExportFormats()
        val csv = base.resolveSibling("Dados.csv")
        Files.writeString(csv, "versão anterior")
        export = { errorResponse(400, "badRequest", "The requested conversion is not supported.") }
        for (offered in listOf(true, false, null)) {
            catalog = { if (offered == null) errorResponse(503) else catalogResponse(csv = offered) }
            val failure = assertFailsWith<DriveExportException> {
                handler.exportFile("item", type.mimeType, csv, "text/csv", previous)
            }
            assertEquals(DriveExportFailure.EXPORT_FORMAT_UNSUPPORTED, failure.reason)
            assertEquals(DriveExportStage.EXPORT, failure.stage)
            assertEquals(400, failure.statusCode)
            assertEquals("text/csv", failure.targetMimeType)
            assertEquals(offered, failure.refreshedCatalog?.formatsFor(type)?.any { it.mimeType == "text/csv" })
            assertEquals("versão anterior", Files.readString(csv))
        }
        assertEquals(4, catalogCalls)
        assertEquals(3, exportRequests.size)
        assertTrue(waits.isEmpty())
    }

    /** Uma falha de permissão, tamanho ou do próprio arquivo não altera o catálogo para a conta. */
    @Test
    fun `distingue recusas de arquivo de formato incompativel`() = scenarioWithClient { client, handler, base ->
        val available = client.listExportFormats()
        for ((reason, expected) in mapOf("insufficientFilePermissions" to DriveExportFailure.PERMISSION_DENIED,
            "fileNotExportable" to DriveExportFailure.FILE_NOT_EXPORTABLE,
            "exportSizeLimitExceeded" to DriveExportFailure.EXPORT_SIZE_LIMIT_EXCEEDED)) {
            export = { errorResponse(403, reason) }
            val failure = assertFailsWith<DriveExportException> {
                handler.exportFile("item", GoogleDocumentType.DOCS.mimeType, base, "application/pdf", available)
            }
            assertEquals(expected, failure.reason)
            assertNull(failure.refreshedCatalog)
            assertFalse(failure.toString().contains("detalhe remoto"))
        }
        export = { errorResponse(400, "badRequest") }
        val generic = assertFailsWith<CredentialException> {
            handler.exportFile("item", GoogleDocumentType.DOCS.mimeType, base, "application/pdf", available)
        }
        assertEquals(CredentialFailure.API_REJECTED, generic.reason)
        assertEquals(1, catalogCalls)
        assertTrue(waits.isEmpty())
    }

    /** Exercita repetição transitória e cancelamento na mesma saída de exportação, com um destino anterior. */
    @Test
    fun `exportacao repete erro temporario e preserva copia ao cancelar`() = scenarioWithClient { client, handler, base ->
        val available = client.listExportFormats()
        val docs = GoogleDocumentType.DOCS.mimeType
        export = { if (exportRequests.size == 1) errorResponse(503) else MockLowLevelHttpResponse().setContent("inteiro") }
        val pdf = handler.exportFile("item", docs, base, "application/pdf", available)
        assertEquals(listOf(1_000L), waits)
        coroutineScope {
            val job = launch(start = CoroutineStart.LAZY) {
                handler.exportFile("item", docs, base, "application/pdf", available)
            }
            export = {
                job.cancel()
                MockLowLevelHttpResponse().setContent("parcial")
            }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals("inteiro", Files.readString(pdf))
        assertEquals(3, exportRequests.size)
        assertEquals(1, catalogCalls)
    }

    /** Uma consulta falha, incompleta ou de outra conta não autoriza concluir que uma conversão foi retirada. */
    @Test
    fun `exige catalogo valido da conta correta`() = scenarioWithClient { client, handler, base ->
        val docs = GoogleDocumentType.DOCS.mimeType
        assertFailsWith<IllegalArgumentException> {
            handler.exportFile("item", docs, base, "application/pdf", DriveExportCatalog(client.accountId + 1, emptyMap()))
        }
        catalog = { errorResponse(503) }
        assertEquals(503, assertFailsWith<CredentialException> { handler.exportFile("item", docs, base) }.statusCode)
        catalog = { MockLowLevelHttpResponse().setContentType("application/json").setContent("{}") }
        assertEquals(CredentialFailure.INVALID_RESPONSE,
            assertFailsWith<CredentialException> { handler.exportFile("item", docs, base) }.reason)
        assertTrue(exportRequests.isEmpty())
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

/** Resposta final da criação com o ID remoto solicitado ao SDK. */
private fun createdFileResponse(): MockLowLevelHttpResponse =
    MockLowLevelHttpResponse().setContentType("application/json").setContent("""{"id":"remoto"}""")

/** Cria a projeção de metadados do Drive e permite omitir o tamanho como numa resposta real. */
private fun metadataResponse(size: Long?, mime: String = "application/octet-stream", allowed: Boolean = true):
    MockLowLevelHttpResponse {
    val sizeField = size?.let { ""","size":"$it"""" }.orEmpty()
    return MockLowLevelHttpResponse().setContentType("application/json")
        .setContent("""{"mimeType":"$mime","capabilities":{"canDownload":$allowed}$sizeField}""")
}

/** Gera HTTP recusado com motivo estruturado para verificar a distinção entre cota e permissão. */
private fun errorResponse(status: Int, reason: String = "backendError", message: String = "detalhe remoto"): MockLowLevelHttpResponse =
    MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json")
        .setContent("""{"error":{"code":$status,"message":"$message","errors":[{"reason":"$reason"}]}}""")

/** Constrói uma resposta about.exportFormats com padrões, saídas múltiplas e novos MIME opcionais. */
private fun catalogResponse(extraDocs: List<String> = emptyList(), csv: Boolean = true): MockLowLevelHttpResponse {
    val formats = mapOf(
        GoogleDocumentType.DOCS to (listOf("application/pdf", "application/epub+zip", "application/vnd.oasis.opendocument.text") + extraDocs),
        GoogleDocumentType.SHEETS to listOfNotNull("application/vnd.oasis.opendocument.spreadsheet", "text/csv".takeIf { csv }),
        GoogleDocumentType.SLIDES to emptyList(),
    )
    val json = buildJsonObject {
        put("exportFormats", buildJsonObject {
            formats.forEach { (type, targets) ->
                put(type.mimeType, JsonArray((targets + type.officeMimeType).map(::JsonPrimitive)))
            }
        })
    }
    return MockLowLevelHttpResponse().setContentType("application/json").setContent(json.toString())
}
