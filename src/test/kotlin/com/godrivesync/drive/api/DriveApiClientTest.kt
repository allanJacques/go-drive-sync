package com.godrivesync.drive.api

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.drive.auth.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Integra Drive SDK, renovação e H2/cifra temporários; transporte HTTP simulado sem sockets ou Google real. */
class DriveApiClientTest : RepositoryTestSupport() {
    /** Relógio determinístico evita esperar expiração real e mantém os testes independentes da data. */
    private val now = Instant.parse("2026-11-23T12:00:00Z")

    /** Serviço real de persistência com identidade e autorização simuladas, sem arquivos do usuário. */
    private fun service(repository: AccountRepository, email: String = "um@example.invalid",
                        access: String = "acesso-um", expiry: Instant = now.plusSeconds(3600)) =
        AccountAuthorizationService(repository,
            LocalTokenKeyStore(temporaryFolder.root.toPath().resolve("private/key")),
            { OAuthCredentials(access, "refresh-ficticio", expiry) }, { email })

    /** Injeta origem e relógio de testes; mantém SDK, banco e criptografia de produção. */
    private fun manager(accounts: AccountAuthorizationService, server: DriveServer) = AccountCredentialManager(
        accounts, { OAuthConfiguration("cliente-ficticio", "segredo-ficticio") }, server.transport,
        "${server.origin}/token", server.origin, Clock.fixed(now, ZoneOffset.UTC),
    )

    /** Cliente usa a mesma raiz permitida pelo manager para enviar Bearer exclusivamente à origem simulada. */
    private fun client(id: Int, manager: AccountCredentialManager, server: DriveServer) =
        DriveApiClient(id, manager, server.transport, "${server.origin}/")

    /** Confere a requisição gerada pelo SDK, seus metadados, continuação e ausência de refresh indevido. */
    @Test
    fun `lista pagina autenticada e continua mesmo depois de pagina vazia`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val repository = AccountRepository(database)
            val accounts = service(repository)
            val saved = accounts.authorizeAndSave()
            val before = repository.findAll()
            val manager = manager(accounts, server)
            val client = client(saved.id, manager, server)
            assertEquals(saved.id, DriveApiClient.fromSavedAccount(saved, manager).accountId)
            assertEquals(saved.id, DriveApiClient(assertNotNull(repository.findById(saved.id)), manager).accountId)
            val page = client.listFilesPage(7)
            assertEquals(listOf(DriveFileSummary("arquivo-1", "Relatório.pdf", "application/pdf")), page.files)
            assertEquals("proxima+/=", page.nextPageToken)
            assertFalse(page.incompleteSearch)
            assertEquals("/drive/v3/files", server.paths.single())
            val query = server.queries.single()
            assertEquals("7", query["pageSize"])
            assertEquals("trashed = false", query["q"])
            assertEquals("user", query["corpora"])
            assertEquals("drive", query["spaces"])
            assertEquals("false", query["includeItemsFromAllDrives"])
            assertEquals("nextPageToken,incompleteSearch,files(id,name,mimeType)", query["fields"])
            assertFalse(query.containsKey("access_token"))
            assertEquals(listOf("Bearer acesso-um"), server.authorizations)
            server.apiBody = { """{"files":[],"nextPageToken":"ultima","incompleteSearch":true}""" }
            val empty = client.listFilesPage(7, page.nextPageToken)
            assertTrue(empty.files.isEmpty())
            assertEquals("ultima", empty.nextPageToken)
            assertTrue(empty.incompleteSearch)
            assertEquals(page.nextPageToken, server.queries.last()["pageToken"])
            server.apiBody = { "{}" }
            val last = client.listFilesPage(7, empty.nextPageToken)
            assertTrue(last.files.isEmpty())
            assertNull(last.nextPageToken)
            assertEquals(0, server.tokenCalls.get())
            assertEquals(before, repository.findAll())
            assertFalse(page.toString().contains("proxima"))
        }
    } }

    /** Simula três páginas da mesma pasta e confere os campos, o literal do filtro e cada token enviado. */
    @Test
    fun `lista filhos diretos atravessando pagina vazia e converte os metadados`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            val folderId = """pai'\especial"""
            server.apiBody = { token ->
                when (token) {
                    null -> """{"nextPageToken":"p+/=","files":[
                        {"id":"a","name":"Dados.zip","mimeType":"application/zip","size":"5000000000",
                         "modifiedTime":"2026-11-23T08:00:00.123-04:00","parents":["pai'\\especial"]}]}"""
                    "p+/=" -> """{"nextPageToken":"fim","files":[]}"""
                    "fim" -> """{"files":[
                        {"id":"b","name":"Pasta","mimeType":"application/vnd.google-apps.folder"},
                        {"id":"c","name":"Texto","mimeType":"application/vnd.google-apps.document"},
                        {"id":"d","name":"Vazio.txt","mimeType":"text/plain","size":"0"}]}"""
                    else -> error("Token de página inesperado no teste.")
                }
            }
            val files = client.listFiles(folderId)

            assertEquals(listOf("a", "b", "c", "d"), files.map { it.id })
            assertEquals(DriveFileSummary("a", "Dados.zip", "application/zip", 5_000_000_000L,
                Instant.parse("2026-11-23T12:00:00.123Z"), listOf(folderId)), files.first())
            assertEquals(listOf(false, true, false, false), files.map { it.isFolder })
            assertEquals(listOf(false, true, true, false), files.map { it.isGoogleNative })
            assertNull(files[1].size)
            assertNull(files[1].modifiedTime)
            assertTrue(files[1].parents.isEmpty())
            assertEquals(0L, files.last().size)
            assertEquals(listOf(null, "p+/=", "fim"), server.queries.map { it["pageToken"] })
            for (query in server.queries) {
                assertEquals("""'pai\'\\especial' in parents and trashed = false""", query["q"])
                assertEquals("nextPageToken,incompleteSearch,files(id,name,mimeType,size,modifiedTime,parents)", query["fields"])
                assertEquals("drive", query["spaces"])
                assertEquals("user", query["corpora"])
                assertEquals("false", query["includeItemsFromAllDrives"])
            }
            assertEquals(List(3) { "Bearer acesso-um" }, server.authorizations)
        }
    } }

    /** Uma raiz sem filhos conclui na primeira página e o ID em branco é recusado antes de consultar o Drive. */
    @Test
    fun `aceita raiz vazia e rejeita identificador em branco`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            assertFailsWith<IllegalArgumentException> { client.listFiles(" ") }
            server.apiBody = { """{"files":[]}""" }
            assertTrue(client.listFiles("root").isEmpty())
            assertEquals("'root' in parents and trashed = false", server.queries.single()["q"])
        }
    } }

    /** Uma falha depois de receber itens interrompe a operação inteira e conserva a categoria de erro. */
    @Test
    fun `recusa busca incompleta ou erro HTTP na segunda pagina`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            server.apiBody = { token ->
                if (token == null) """{"files":[{"id":"a","name":"a","mimeType":"text/plain"}],"nextPageToken":"outra"}"""
                else """{"incompleteSearch":true,"nextPageToken":"fim"}"""
            }
            val incomplete = assertFailsWith<CredentialException> { client.listFiles("pai") }
            assertEquals(CredentialFailure.TEMPORARY, incomplete.reason)
            assertEquals(listOf(null, "outra"), server.queries.map { it["pageToken"] })

            server.status = { if (server.queries.last()["pageToken"] == "outra") 403 else 200 }
            val rejected = assertFailsWith<CredentialException> { client.listFiles("pai") }
            assertEquals(CredentialFailure.API_REJECTED, rejected.reason)
            assertEquals(403, rejected.statusCode)
            assertEquals(listOf(null, "outra", null, "outra"), server.queries.map { it["pageToken"] })
        }
    } }

    /** Detecta um ciclo com dois tokens distintos antes de reenviar uma continuação já consumida. */
    @Test
    fun `interrompe ciclo de tokens`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            // Uma quarta chamada falharia imediatamente mesmo se a proteção contra ciclos regredisse.
            server.status = { if (it > 3) 400 else 200 }
            server.apiBody = { token ->
                if (token == "A") """{"nextPageToken":"B"}""" else """{"nextPageToken":"A"}"""
            }
            val failure = assertFailsWith<CredentialException> { client.listFiles("pai") }
            assertEquals(CredentialFailure.INVALID_RESPONSE, failure.reason)
            assertEquals(listOf(null, "A", "B"), server.queries.map { it["pageToken"] })
        }
    } }

    /** Refresh é persistido antes de files.list; reutilizar o wrapper relê a nova cifra sem renovar de novo. */
    @Test
    fun `renova token vencido e persiste antes da listagem`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val repository = AccountRepository(database)
            val accounts = service(repository, expiry = now.minusSeconds(1))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            client.listFilesPage()
            client.listFilesPage()
            assertEquals(1, server.tokenCalls.get())
            assertEquals(listOf("Bearer acesso-renovado", "Bearer acesso-renovado"), server.authorizations)
            val restored = assertNotNull(accounts.loadCredentials(saved.id))
            assertEquals("acesso-renovado", restored.accessToken)
            assertEquals("refresh-ficticio", restored.refreshToken)
            assertEquals(now.plusSeconds(3600), restored.expiresAt)
            val row = assertNotNull(repository.findById(saved.id))
            assertTrue(row.accessToken.startsWith("v1:"))
            assertFalse(row.accessToken.contains("acesso-renovado"))
        }
    } }

    /** O SDK repete uma vez com token renovado; um novo 401 termina com falha sanitizada. */
    @Test
    fun `401 renova e repete uma unica vez pelo SDK Drive`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            server.status = { if (it == 1) 401 else 200 }
            assertEquals(1, client.listFilesPage().files.size)
            assertEquals(listOf("Bearer acesso-um", "Bearer acesso-renovado"), server.authorizations)
            assertEquals(1, server.tokenCalls.get())
            server.status = { 401 }
            val failure = assertFailsWith<CredentialException> { client.listFilesPage() }
            assertEquals(CredentialFailure.API_REJECTED, failure.reason)
            assertEquals(401, failure.statusCode)
            assertEquals(4, server.apiCalls.get())
            assertEquals(2, server.tokenCalls.get())
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("segredo-remoto"))
        }
    } }

    /** Clientes compartilham manager sem compartilhar tokens; um login posterior substitui a fotografia antiga. */
    @Test
    fun `isola contas e rele credenciais apos reconexao`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val repository = AccountRepository(database)
            val accounts = service(repository)
            val first = accounts.authorizeAndSave()
            val second = service(repository, "dois@example.invalid", "acesso-dois").authorizeAndSave()
            val manager = manager(accounts, server)
            val one = client(first.id, manager, server)
            val two = client(second.id, manager, server)
            one.listFilesPage(); two.listFilesPage()
            service(repository, access = "acesso-reconectado").authorizeAndSave()
            one.listFilesPage()
            assertEquals(listOf("Bearer acesso-um", "Bearer acesso-dois", "Bearer acesso-reconectado"), server.authorizations)
            assertEquals(0, server.tokenCalls.get())
        }
    } }

    /** Conta excluída entre construção e chamada não usa tokens antigos nem é recriada. */
    @Test
    fun `conta removida e parametros invalidos nao fazem HTTP`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val repository = AccountRepository(database)
            val accounts = service(repository)
            val saved = accounts.authorizeAndSave()
            val manager = manager(accounts, server)
            val client = client(saved.id, manager, server)
            assertFailsWith<IllegalArgumentException> { DriveApiClient(account(), manager) }
            assertFailsWith<IllegalArgumentException> { DriveApiClient.fromSavedAccount(saved.copy(id = 0), manager) }
            for (size in listOf(0, 1001)) assertFailsWith<IllegalArgumentException> { client.listFilesPage(size) }
            assertFailsWith<IllegalArgumentException> { client.listFilesPage(pageToken = " ") }
            repository.delete(saved.id)
            val failure = assertFailsWith<CredentialException> { client.listFilesPage() }
            assertEquals(CredentialFailure.ACCOUNT_NOT_FOUND, failure.reason)
            assertEquals(0, server.apiCalls.get())
            assertEquals(0, server.tokenCalls.get())
            assertTrue(repository.findAll().isEmpty())
        }
    } }

    /** Recusas comuns da API não desencadeiam refresh e nunca incluem seu corpo remoto na exceção pública. */
    @Test
    fun `recusas da API sao sanitizadas sem apagar conta`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val repository = AccountRepository(database)
            val accounts = service(repository)
            val saved = accounts.authorizeAndSave()
            val before = repository.findAll()
            val client = client(saved.id, manager(accounts, server), server)
            for (status in listOf(403, 429, 500)) {
                server.status = { status }
                val failure = assertFailsWith<CredentialException> { client.listFilesPage() }
                assertEquals(CredentialFailure.API_REJECTED, failure.reason)
                assertEquals(status, failure.statusCode)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("segredo-remoto"))
            }
            assertEquals(3, server.apiCalls.get())
            assertEquals(0, server.tokenCalls.get())
            assertEquals(before, repository.findAll())
        }
    } }

    /** Autorização revogada interrompe a listagem antes da API e preserva dados para reconexão explícita. */
    @Test
    fun `refresh recusado exige reconexao e nao lista arquivos`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val repository = AccountRepository(database)
            val accounts = service(repository, expiry = now.minusSeconds(1))
            val saved = accounts.authorizeAndSave()
            val before = repository.findAll()
            server.tokenStatus = 400
            val failure = assertFailsWith<CredentialException> {
                client(saved.id, manager(accounts, server), server).listFilesPage()
            }
            assertEquals(CredentialFailure.REAUTHORIZATION_REQUIRED, failure.reason)
            assertEquals(1, server.tokenCalls.get())
            assertEquals(0, server.apiCalls.get())
            assertEquals(before, repository.findAll())
        }
    } }

    /** Rejeita metadados ausentes e continuação vazia sem divulgar os dados inválidos do servidor. */
    @Test
    fun `metadados incompletos e token vazio sao recusados`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            for (body in listOf("""{"files":[{"name":"segredo-remoto"}]}""", """{"nextPageToken":" "}""")) {
                server.apiBody = { body }
                val failure = assertFailsWith<CredentialException> { client.listFilesPage() }
                assertEquals(CredentialFailure.INVALID_RESPONSE, failure.reason)
                assertFalse(failure.toString().contains("segredo-remoto"))
            }
        }
    } }

    /** Cliente mantém bloqueio de redirects e de origem externa antes de anexar credenciais. */
    @Test
    fun `redirect e origem externa nao recebem token`() = withDatabase { database -> runBlocking {
        DriveServer().use { server -> DriveServer("https://outra.invalid").use { other ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val manager = manager(accounts, server)
            val failure = assertFailsWith<CredentialException> { client(saved.id, manager, other).listFilesPage() }
            assertEquals(CredentialFailure.CONFIGURATION, failure.reason)
            server.status = { 302 }
            server.redirect = "${other.origin}/drive/v3/files"
            assertFailsWith<CredentialException> { client(saved.id, manager, server).listFilesPage() }
            assertEquals(0, other.apiCalls.get())
            assertEquals(1, server.apiCalls.get())
        } }
    } }

    /** Cancelamento é propagado, em vez de ser convertido em erro de rede ou iniciar uma consulta. */
    @Test
    fun `corrotina cancelada nao inicia listagem`() = withDatabase { database -> runBlocking {
        DriveServer().use { server ->
            val accounts = service(AccountRepository(database))
            val saved = accounts.authorizeAndSave()
            val client = client(saved.id, manager(accounts, server), server)
            launch {
                cancel()
                assertFailsWith<CancellationException> { client.listFilesPage() }
            }.join()
            assertEquals(0, server.apiCalls.get())
        }
    } }

    /** Simula respostas HTTP abaixo do SDK, preservando interceptores, parsing JSON e repetição de 401. */
    private class DriveServer(originUrl: String = "https://drive.example.invalid") : AutoCloseable {
        /** Origem fictícia: qualquer acesso efetivo à rede seria uma falha do isolamento do teste. */
        val origin: URI = URI(originUrl)
        /** Observações das chamadas executadas em IO são lidas após a conclusão da operação suspensa. */
        val apiCalls = AtomicInteger()
        val tokenCalls = AtomicInteger()
        val authorizations: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val queries: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** Respostas configuráveis são publicadas antes da chamada e lidas pelo dispatcher IO. */
        @Volatile var status: (Int) -> Int = { 200 }
        @Volatile var tokenStatus = 200
        @Volatile var redirect: String? = null
        /** Cada resposta de sucesso depende do token recebido, como nas consultas paginadas do Drive. */
        @Volatile var apiBody: (String?) -> String = {
            """{"files":[{"id":"arquivo-1","name":"Relatório.pdf","mimeType":"application/pdf"}],"nextPageToken":"proxima+/="}"""
        }

        /** Transporte de teste materializa requisições sem abrir conexão; o restante da pilha é real. */
        val transport: HttpTransport = object : HttpTransport() {
            /** Cada tentativa HTTP ganha request próprio e recebe os headers do interceptor real. */
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    /** Observa a chamada pronta e devolve JSON como um servidor, incluindo erros e redirects. */
                    override fun execute(): LowLevelHttpResponse {
                        val uri = URI(url)
                        check(uri.scheme == origin.scheme && uri.authority == origin.authority)
                        if (uri.path == "/token") {
                            check(method == "POST")
                            tokenCalls.incrementAndGet()
                            return response(tokenStatus, if (tokenStatus == 200)
                                """{"access_token":"acesso-renovado","token_type":"Bearer","expires_in":3600}"""
                            else """{"error":"invalid_grant","error_description":"segredo-remoto"}""")
                        }
                        check(method == "GET" && uri.path == "/drive/v3/files")
                        val count = apiCalls.incrementAndGet()
                        authorizations.add(getFirstHeaderValue("Authorization").orEmpty())
                        paths.add(uri.path)
                        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                            val pair = it.split('=', limit = 2)
                            URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
                        }
                        queries.add(query)
                        val code = status(count)
                        return response(code, if (code == 200) apiBody(query["pageToken"]) else
                            """{"error":{"message":"segredo-remoto","code":$code}}""").apply {
                            redirect?.let { addHeader("Location", it) }
                        }
                    }
                }
        }

        /** Resposta oficial de teste permite que o parser Gson e os handlers reais processem o conteúdo. */
        private fun response(code: Int, body: String): MockLowLevelHttpResponse = MockLowLevelHttpResponse()
            .setStatusCode(code).setContentType("application/json; charset=UTF-8").setContent(body)

        /** Mantém uso com try/finally e encerra o transporte, embora ele não abra sockets. */
        override fun close() = transport.shutdown()
    }
}
