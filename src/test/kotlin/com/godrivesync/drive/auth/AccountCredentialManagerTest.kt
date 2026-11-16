package com.godrivesync.drive.auth

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.data.security.TokenProtectionException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Integra Credential oficial, HTTP loopback, cifra e H2 temporários; nenhum teste acessa o Google. */
class AccountCredentialManagerTest : RepositoryTestSupport() {
    /** Relógio fixo separa a expiração dos tokens da duração de execução dos testes. */
    private val now = Instant.parse("2026-11-16T12:00:00Z")

    /** Serviço real de armazenamento; autorização e e-mail são simulados com valores sem validade externa. */
    private fun service(repository: AccountRepository, email: String = "teste@example.invalid",
                        access: String = "acesso-antigo", refresh: String = "refresh-antigo",
                        expiry: Instant = now.minusSeconds(1)) =
        AccountAuthorizationService(repository, LocalTokenKeyStore(temporaryFolder.root.toPath().resolve("private/key")),
            { OAuthCredentials(access, refresh, expiry) }, { email })

    /** Injeta apenas configuração, endpoints e relógio; mantém transporte e biblioteca OAuth reais. */
    private fun manager(service: AccountAuthorizationService, server: RefreshServer) = AccountCredentialManager(
        service, { OAuthConfiguration("cliente-ficticio", "segredo-ficticio") }, NetHttpTransport(),
        "${server.origin}/token", server.origin, Clock.fixed(now, ZoneOffset.UTC),
    )

    /** Consome e fecha a resposta dentro do escopo autenticado, como deverá fazer o cliente Drive. */
    private suspend fun get(manager: AccountCredentialManager, id: Int, server: RefreshServer): String =
        manager.execute(id) { initializer ->
            val response = NetHttpTransport().createRequestFactory(initializer)
                .buildGetRequest(GenericUrl("${server.origin}/api")).execute()
            try { response.parseAsString() } finally { response.disconnect() }
        }

    /** Token ainda válido segue ao recurso sem POST, sem regravar cifras e sem renovar por conta ausente. */
    @Test
    fun `token valido nao renova nem altera banco`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository, expiry = now.plusSeconds(3600))
            val id = service.authorizeAndSave().id
            val before = repository.findAll()
            val manager = manager(service, server)
            assertEquals("ok", get(manager, id, server))
            assertEquals(0, server.tokenCalls.get())
            assertEquals(listOf("Bearer acesso-antigo"), server.authorizations)
            assertEquals(before, repository.findAll())
            assertEquals(CredentialFailure.ACCOUNT_NOT_FOUND,
                assertFailsWith<CredentialException> { get(manager, 999, server) }.reason)
        }
    } }

    /** Renovação conserva refresh omitido, metadados e FK; novo processo lógico lê a cifra e validade UTC. */
    @Test
    fun `renova vencido e recupera persistencia apos reabrir`() {
        var id = 0
        withDatabase { database -> runBlocking {
            RefreshServer().use { server ->
                val repository = AccountRepository(database)
                val service = service(repository)
                id = service.authorizeAndSave().id
                val before = repository.save(assertNotNull(repository.findById(id)).copy(isDefault = true, lastFullSync = date))
                val mapping = MappingRepository(database).save(mapping(id))
                assertEquals("ok", get(manager(service, server), id, server))
                assertEquals(1, server.tokenCalls.get())
                assertEquals(listOf("Bearer acesso-novo"), server.authorizations)
                assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-antigo",
                    "client_id" to "cliente-ficticio", "client_secret" to "segredo-ficticio"), server.forms.single())
                val row = assertNotNull(repository.findById(id))
                assertTrue(row.accessToken.startsWith("v1:"))
                assertFalse(row.accessToken.contains("acesso-novo"))
                assertTrue(row.refreshToken.startsWith("v1:"))
                assertEquals(before.createdAt, row.createdAt)
                assertEquals(before.lastFullSync, row.lastFullSync)
                assertTrue(row.isDefault)
                assertEquals(listOf(mapping), MappingRepository(database).findByAccountId(id))
            }
        } }
        withDatabase { database -> runBlocking {
            val tokens = assertNotNull(service(AccountRepository(database)).loadCredentials(id))
            assertEquals("acesso-novo", tokens.accessToken)
            assertEquals("refresh-antigo", tokens.refreshToken)
            assertEquals(now.plusSeconds(3600), tokens.expiresAt)
        } }
    }

    /** A resposta pode rotacionar refresh; margem de 60 s também renova token prestes a vencer. */
    @Test
    fun `renova preventivamente e aceita rotacao de refresh`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            server.tokenHandler = { respond(it, 200,
                """{"access_token":"acesso-novo","refresh_token":"refresh-novo","token_type":"Bearer","expires_in":30}""") }
            val service = service(AccountRepository(database), expiry = now.plusSeconds(60))
            val id = service.authorizeAndSave().id
            get(manager(service, server), id, server)
            val tokens = assertNotNull(service.loadCredentials(id))
            assertEquals("refresh-novo", tokens.refreshToken)
            assertEquals(now.plusSeconds(30), tokens.expiresAt)
            assertEquals(1, server.tokenCalls.get())
            assertEquals(1, server.apiCalls.get())
        }
    } }

    /** Primeiro 401 renova e repete; segundo 401 termina sem loop, sem renovar novamente. */
    @Test
    fun `401 renova e repete no maximo uma vez`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val service = service(AccountRepository(database), expiry = now.plusSeconds(3600))
            val id = service.authorizeAndSave().id
            server.apiHandler = { respond(it, if (server.apiCalls.get() == 1) 401 else 200, "ok") }
            val manager = manager(service, server)
            assertEquals("ok", get(manager, id, server))
            assertEquals(listOf("Bearer acesso-antigo", "Bearer acesso-novo"), server.authorizations)
            assertEquals(1, server.tokenCalls.get())
            server.apiHandler = { respond(it, 401, "corpo-sensivel-ficticio") }
            val failure = assertFailsWith<CredentialException> { get(manager, id, server) }
            assertEquals(CredentialFailure.API_REJECTED, failure.reason)
            assertEquals(401, failure.statusCode)
            assertEquals(4, server.apiCalls.get())
            assertEquals(2, server.tokenCalls.get())
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("sensivel"))
        }
    } }

    /** Re-login só decorre de invalid_grant; configuração, 429 e 5xx não apagam as credenciais. */
    @Test
    fun `categoriza recusa de refresh sem expor resposta nem alterar banco`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository)
            val id = service.authorizeAndSave().id
            val before = repository.findAll()
            val manager = manager(service, server)
            val cases = listOf(Triple(400, "invalid_grant", CredentialFailure.REAUTHORIZATION_REQUIRED),
                Triple(400, "invalid_client", CredentialFailure.CONFIGURATION),
                Triple(503, "temporarily_unavailable", CredentialFailure.TEMPORARY),
                Triple(429, "slow_down", CredentialFailure.TEMPORARY))
            for ((status, error, reason) in cases) {
                server.tokenHandler = { respond(it, status, """{"error":"$error","error_description":"segredo-remoto"}""") }
                val failure = assertFailsWith<CredentialException> { get(manager, id, server) }
                assertEquals(reason, failure.reason)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("segredo-remoto"))
                assertEquals(before, repository.findAll())
            }
            assertEquals(cases.size, server.tokenCalls.get())
            assertEquals(0, server.apiCalls.get())
        }
    } }

    /** Respostas incompletas ou com escopo reduzido nunca substituem credenciais válidas no armazenamento. */
    @Test
    fun `resposta de token invalida nao persiste`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository)
            val id = service.authorizeAndSave().id
            val before = repository.findAll()
            val bodies = listOf("""{"token_type":"Bearer","expires_in":3600}""",
                """{"access_token":"novo","token_type":"Basic","expires_in":3600}""",
                """{"access_token":"novo","token_type":"Bearer","expires_in":0}""",
                """{"access_token":"novo","token_type":"Bearer","expires_in":3600,"refresh_token":""}""",
                """{"access_token":"novo","token_type":"Bearer","expires_in":3600,"scope":"openid"}""")
            for (body in bodies) {
                server.tokenHandler = { respond(it, 200, body) }
                assertEquals(CredentialFailure.INVALID_RESPONSE,
                    assertFailsWith<CredentialException> { get(manager(service, server), id, server) }.reason)
                assertEquals(before, repository.findAll())
            }
            assertEquals(0, server.apiCalls.get())
        }
    } }

    /** O lock da instância agrupa renovações concorrentes; a segunda conta mantém tokens próprios. */
    @Test
    fun `concorrencia renova uma vez e isola contas`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository)
            val first = service.authorizeAndSave().id
            val second = service(repository, "outra@example.invalid", "acesso-outra", "refresh-outra", now.plusSeconds(3600))
                .authorizeAndSave().id
            val manager = manager(service, server)
            val values = (1..8).map { async { get(manager, first, server) } }.awaitAll()
            assertEquals(List(8) { "ok" }, values)
            assertEquals(1, server.tokenCalls.get())
            get(manager, second, server)
            assertEquals("Bearer acesso-outra", server.authorizations.last())
            assertEquals("refresh-outra", assertNotNull(service.loadCredentials(second)).refreshToken)
        }
    } }

    /** Login durante o POST vence a corrida; a resposta antiga é descartada antes de chamar o recurso. */
    @Test
    fun `refresh atrasado nao sobrescreve novo login`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository)
            val id = service.authorizeAndSave().id
            server.tokenHandler = {
                runBlocking { service(repository, access = "acesso-login", refresh = "refresh-login").authorizeAndSave() }
                respond(it, 200, SUCCESS_TOKEN)
            }
            assertEquals(CredentialFailure.CREDENTIALS_CHANGED,
                assertFailsWith<CredentialException> { get(manager(service, server), id, server) }.reason)
            val tokens = assertNotNull(service.loadCredentials(id))
            assertEquals("acesso-login", tokens.accessToken)
            assertEquals("refresh-login", tokens.refreshToken)
            assertEquals(0, server.apiCalls.get())
        }
    } }

    /** Remoção durante o POST continua removida; refresh não usa upsert por e-mail. */
    @Test
    fun `refresh atrasado nao recria conta excluida`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository)
            val id = service.authorizeAndSave().id
            server.tokenHandler = { repository.delete(id); respond(it, 200, SUCCESS_TOKEN) }
            assertEquals(CredentialFailure.CREDENTIALS_CHANGED,
                assertFailsWith<CredentialException> { get(manager(service, server), id, server) }.reason)
            assertTrue(repository.findAll().isEmpty())
            assertEquals(0, server.apiCalls.get())
        }
    } }

    /** Cancelar durante HTTP síncrono impede persistência após a resposta e não vira erro de re-login. */
    @Test
    fun `cancelamento antes do commit preserva banco`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val repository = AccountRepository(database)
            val service = service(repository)
            val id = service.authorizeAndSave().id
            val before = repository.findAll()
            server.tokenHandler = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                respond(it, 200, SUCCESS_TOKEN)
            }
            val pending = async { get(manager(service, server), id, server) }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                pending.cancel()
            } finally { release.countDown() }
            withTimeout(5000) { pending.cancelAndJoin() }
            assertEquals(before, repository.findAll())
            assertEquals(0, server.apiCalls.get())
        }
    } }

    /** Erro de conexão é transitório; 403 e redirects não disparam refresh nem encaminham Bearer. */
    @Test
    fun `falhas de recurso e rede nao exigem novo login`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val service = service(repository, expiry = now.plusSeconds(3600))
        val id = service.authorizeAndSave().id
        val server = RefreshServer()
        val manager = manager(service, server)
        server.use {
            for (status in listOf(403, 302)) {
                server.apiHandler = { exchange ->
                    exchange.responseHeaders.add("Location", "${server.origin}/destination")
                    respond(exchange, status, "resposta-ficticia")
                }
                assertEquals(status, assertFailsWith<CredentialException> { get(manager, id, server) }.statusCode)
            }
            assertEquals(2, server.apiCalls.get())
            assertEquals(0, server.tokenCalls.get())
        }
        assertEquals(CredentialFailure.TEMPORARY,
            assertFailsWith<CredentialException> { get(manager, id, server) }.reason)
    } }

    /** Credencial rejeitada por 401 também entrega invalid_grant ao fluxo de recuperação, sem repetir a API. */
    @Test
    fun `401 com refresh revogado exige reautorizacao`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository, expiry = now.plusSeconds(3600))
            val id = service.authorizeAndSave().id
            val before = repository.findAll()
            server.apiHandler = { respond(it, 401, "recusado") }
            server.tokenHandler = { respond(it, 400, """{"error":"invalid_grant"}""") }
            assertEquals(CredentialFailure.REAUTHORIZATION_REQUIRED,
                assertFailsWith<CredentialException> { get(manager(service, server), id, server) }.reason)
            assertEquals(1, server.apiCalls.get())
            assertEquals(1, server.tokenCalls.get())
            assertEquals(before, repository.findAll())
        }
    } }

    /** Falha ao proteger o novo token aborta antes da API; não mantém token renovado apenas em memória. */
    @Test
    fun `perda de chave durante refresh impede uso e preserva banco`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val repository = AccountRepository(database)
            val service = service(repository)
            val id = service.authorizeAndSave().id
            val before = repository.findAll()
            server.tokenHandler = {
                Files.delete(temporaryFolder.root.toPath().resolve("private/key"))
                respond(it, 200, SUCCESS_TOKEN)
            }
            assertFailsWith<TokenProtectionException> { get(manager(service, server), id, server) }
            assertEquals(0, server.apiCalls.get())
            assertEquals(before, repository.findAll())
        }
    } }

    /** O inicializador rejeita destino externo antes de enviar Bearer ou fazer refresh. */
    @Test
    fun `nao envia credenciais a outra origem`() = withDatabase { database -> runBlocking {
        RefreshServer().use { server ->
            val service = service(AccountRepository(database))
            val id = service.authorizeAndSave().id
            val failure = assertFailsWith<CredentialException> {
                manager(service, server).execute(id) { initializer ->
                    NetHttpTransport().createRequestFactory(initializer)
                        .buildGetRequest(GenericUrl("http://localhost:${server.origin.port}/api")).execute()
                }
            }
            assertEquals(CredentialFailure.CONFIGURATION, failure.reason)
            assertEquals(0, server.apiCalls.get())
            assertEquals(0, server.tokenCalls.get())
        }
    } }
}

/** Resposta fictícia padrão, sem refresh para exercitar preservação do token anterior. */
private const val SUCCESS_TOKEN = """{"access_token":"acesso-novo","token_type":"Bearer","expires_in":3600}"""

/** Servidor local com handlers substituíveis; listas sincronizadas observam chamadas das threads HTTP. */
private class RefreshServer : AutoCloseable {
    /** Porta efêmera e executor próprios permitem testar requisições concorrentes sem infraestrutura externa. */
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    val origin = URI("http://127.0.0.1:${server.address.port}")
    val tokenCalls = AtomicInteger()
    val apiCalls = AtomicInteger()
    val authorizations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val forms: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    @Volatile var tokenHandler: (HttpExchange) -> Unit = { respond(it, 200, SUCCESS_TOKEN) }
    @Volatile var apiHandler: (HttpExchange) -> Unit = { respond(it, 200, "ok") }

    init {
        server.executor = executor
        server.createContext("/token") { exchange ->
            tokenCalls.incrementAndGet()
            forms.add(exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }.split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
            })
            tokenHandler(exchange)
        }
        server.createContext("/api") { exchange ->
            apiCalls.incrementAndGet()
            authorizations.add(exchange.requestHeaders.getFirst("Authorization") ?: "ausente")
            apiHandler(exchange)
        }
        server.start()
    }

    /** Encerra socket e executor mesmo quando uma asserção falha. */
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

/** Envia resposta UTF-8 e fecha o exchange para não deixar conexões presas ao final do teste. */
private fun respond(exchange: HttpExchange, status: Int, body: String) {
    exchange.responseHeaders.add("Content-Type", "application/json")
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
    exchange.close()
}
