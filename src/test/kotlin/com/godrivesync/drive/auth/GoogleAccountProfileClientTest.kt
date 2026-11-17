package com.godrivesync.drive.auth

import com.google.api.client.http.javanet.NetHttpTransport
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.ui.screens.login.LoginAccountService
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import kotlin.test.*

/** Integra perfil, credenciais cifradas e persistência com H2 temporário e servidor local. */
class GoogleAccountProfileClientTest : RepositoryTestSupport() {
    /** Autoriza identidades fictícias sem navegador e usa chave temporária, reaproveitada ao reabrir o H2. */
    private fun service(repository: AccountRepository, email: String = "teste@example.invalid") =
        AccountAuthorizationService(repository, LocalTokenKeyStore(temporaryFolder.root.toPath().resolve("private/key")),
            { OAuthCredentials("token-ficticio", "refresh-ficticio", Instant.now().plusSeconds(3600)) }, { email })

    /** Perfil valida identidade, tolera foto ausente e usa Bearer apenas na chamada autorizada. */
    @Test
    fun `consulta perfil validado e recusa identidade diferente`() = withDatabase { database -> runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = """{"user":{"emailAddress":"teste@example.invalid","photoLink":"https://lh3.googleusercontent.com/photo"}}"""
        val headers = mutableListOf<String?>()
        server.createContext("/profile") { exchange ->
            headers.add(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val origin = URI("http://127.0.0.1:${server.address.port}")
            val authorization = service(AccountRepository(database))
            val saved = authorization.authorizeAndSave()
            val manager = AccountCredentialManager(authorization,
                { OAuthConfiguration("cliente", "segredo") }, NetHttpTransport(), "$origin/token", origin, Clock.systemUTC())
            val profiles = GoogleAccountProfileClient(manager, "$origin/profile")
            assertEquals("https://lh3.googleusercontent.com/photo", profiles.load(saved).photoLink)
            body = """{"user":{"emailAddress":"teste@example.invalid"}}"""
            assertNull(profiles.load(saved).photoLink)
            body = """{"user":{"emailAddress":"outra@example.invalid"}}"""
            assertEquals(CredentialFailure.INVALID_RESPONSE,
                assertFailsWith<CredentialException> { profiles.load(saved) }.reason)
            body = "resposta-secreta-invalida"
            val failure = assertFailsWith<CredentialException> { profiles.load(saved) }
            assertFalse(failure.message!!.contains("secreta"))
            assertTrue(headers.all { it == "Bearer token-ficticio" })
        } finally { server.stop(0) }
    } }

    /** Fechar/reabrir preserva as três contas e seus vínculos; conta padrão não limita a recuperação. */
    @Test
    fun `servico de login recupera todas as contas e mapeamentos apos reabrir`() {
        var mappedId = 0
        withDatabase { database -> runBlocking {
            val repository = AccountRepository(database)
            (1..3).forEach { service(repository, "conta$it@example.invalid").authorizeAndSave() }
            mappedId = repository.listSavedAccounts()[1].id
            repository.setDefault(mappedId)
            MappingRepository(database).save(mapping(mappedId))
        } }
        withDatabase { database -> runBlocking {
            val authorization = service(AccountRepository(database))
            val login = LoginAccountService(authorization, GoogleAccountProfileClient(AccountCredentialManager(authorization)),
                MappingRepository(database))
            val restored = login.list()
            assertEquals(3, restored.size)
            assertEquals(setOf("conta1@example.invalid", "conta2@example.invalid", "conta3@example.invalid"),
                restored.map { it.email }.toSet())
            assertTrue(login.hasMappings(mappedId))
            assertFalse(login.hasMappings(restored.first { it.id != mappedId }.id))
            assertEquals(1, restored.count { it.isDefault })
        } }
    }
}
