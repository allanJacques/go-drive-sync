package com.godrivesync.drive.auth

import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.data.security.LocalTokenKeyStore
import com.godrivesync.data.security.TokenProtectionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

/** Integra serviço, cifra, chave local e H2 real; somente OAuth/identidade são simulados. */
class AccountAuthorizationServiceTest : RepositoryTestSupport() {
    /** Instante com nanossegundos verifica o contrato UTC e precisão TIMESTAMP(9). */
    private val expires = Instant.parse("2026-11-15T12:30:15.123456789Z")

    /** Chave de cada teste fica ao lado do banco temporário, nunca no perfil real. */
    private fun keyPath(): Path = temporaryFolder.root.toPath().resolve("private/token-key-v1")

    /** Cria dependências previsíveis sem consentimento ou conexão com servidores Google. */
    private fun service(repository: AccountRepository, email: String = "teste@example.invalid", suffix: String = "") =
        AccountAuthorizationService(repository, LocalTokenKeyStore(keyPath()),
            { OAuthCredentials("acesso-ficticio$suffix", "refresh-ficticio$suffix", expires) }, { email })

    /** Confere conteúdo cifrado, recuperação após reabrir, metadados sem tokens e vencimento com fuso diferente. */
    @Test
    fun `persiste apenas cifras e recupera apos reabrir banco`() {
        var id = 0
        withDatabase { database -> runBlocking {
            val repository = AccountRepository(database)
            val service = service(repository)
            assertTrue(service.listAccounts().isEmpty())
            assertNull(service.loadCredentials(123))
            assertFalse(Files.exists(keyPath()))
            val saved = service.authorizeAndSave()
            id = saved.id
            val row = assertNotNull(repository.findById(id))
            assertTrue(row.accessToken.startsWith("v1:"))
            assertTrue(row.refreshToken.startsWith("v1:"))
            assertFalse(row.accessToken.contains("acesso-ficticio"))
            assertFalse(row.refreshToken.contains("refresh-ficticio"))
            assertEquals(expires, row.tokenExpiry.toInstant(ZoneOffset.UTC))
            assertEquals(listOf(saved), service.listAccounts())
            assertFalse(saved.toString().contains(row.accessToken))
            assertFalse(saved.isExpired(Clock.fixed(expires.minusNanos(1), ZoneId.of("America/Cuiaba"))))
            assertTrue(saved.isExpired(Clock.fixed(expires, ZoneId.of("Asia/Tokyo"))))
            assertTrue(saved.isExpired(Clock.fixed(expires.plusSeconds(1), ZoneOffset.UTC)))
        } }
        withDatabase { database -> runBlocking {
            val recovered = assertNotNull(service(AccountRepository(database)).loadCredentials(id))
            assertEquals("acesso-ficticio", recovered.accessToken)
            assertEquals("refresh-ficticio", recovered.refreshToken)
            assertEquals(expires, recovered.expiresAt)
            assertFalse(recovered.toString().contains("ficticio"))
        } }
    }

    /** Reautorizar substitui os dois tokens sem perder preferência, sincronização, criação ou FK de pasta. */
    @Test
    fun `nova autorizacao preserva identidade preferencias e mapeamentos`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val first = service(repository).authorizeAndSave()
        val original = repository.save(assertNotNull(repository.findById(first.id)).copy(isDefault = true, lastFullSync = date))
        val mapping = MappingRepository(database).save(mapping(first.id))
        val updated = service(repository, suffix = "-novo").authorizeAndSave()
        val current = assertNotNull(repository.findById(first.id))
        assertEquals(first.id, updated.id)
        assertTrue(updated.isDefault)
        assertEquals(original.createdAt, current.createdAt)
        assertEquals(date, current.lastFullSync)
        assertEquals(listOf(mapping), MappingRepository(database).findByAccountId(first.id))
        assertEquals(1, repository.findAll().size)
        val credentials = assertNotNull(service(repository).loadCredentials(first.id))
        assertEquals("acesso-ficticio-novo", credentials.accessToken)
        assertEquals("refresh-ficticio-novo", credentials.refreshToken)
    } }

    /** Conta ausente retorna null; perda da chave bloqueia leitura e novo login, mas preserva listagem e H2. */
    @Test
    fun `perda da chave nao gera outra nem altera contas`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val service = service(repository)
        val saved = service.authorizeAndSave()
        val before = repository.findAll()
        Files.delete(keyPath())
        assertEquals(listOf(saved), service.listAccounts())
        assertFailsWith<TokenProtectionException> { service.loadCredentials(saved.id) }
        assertFailsWith<TokenProtectionException> { service.authorizeAndSave() }
        assertEquals(before, repository.findAll())
        assertFalse(Files.exists(keyPath()))
    } }

    /** Troca de cifras entre contas, chave errada e dados legados não recebem interpretação permissiva. */
    @Test
    fun `rejeita troca de tokens chave incorreta e plaintext`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val first = service(repository).authorizeAndSave()
        val second = service(repository, "outra@example.invalid").authorizeAndSave()
        val firstRow = assertNotNull(repository.findById(first.id))
        val secondRow = assertNotNull(repository.findById(second.id))
        repository.save(firstRow.copy(accessToken = secondRow.accessToken))
        assertFailsWith<TokenProtectionException> { service(repository).loadCredentials(first.id) }
        repository.save(firstRow.copy(accessToken = firstRow.refreshToken))
        assertFailsWith<TokenProtectionException> { service(repository).loadCredentials(first.id) }
        repository.save(firstRow.copy(accessToken = "plaintext-ficticio"))
        assertFailsWith<TokenProtectionException> { service(repository).loadCredentials(first.id) }
        repository.save(firstRow)
        Files.write(keyPath(), ByteArray(32) { 7 })
        assertFailsWith<TokenProtectionException> { service(repository).loadCredentials(first.id) }
    } }

    /** Falha de identificação, cancelamento e erro de cifra nunca substituem as credenciais anteriores. */
    @Test
    fun `falhas anteriores a gravacao preservam conta`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        service(repository).authorizeAndSave()
        val before = repository.findAll()
        val failureService = AccountAuthorizationService(repository, LocalTokenKeyStore(keyPath()),
            { OAuthCredentials("novo", "novo-refresh", expires) }, { throw AccountAuthorizationException("Falha simulada") })
        assertFailsWith<AccountAuthorizationException> { failureService.authorizeAndSave() }
        val cancelledService = AccountAuthorizationService(repository, LocalTokenKeyStore(keyPath()),
            { throw CancellationException("Cancelamento simulado") }, { "teste@example.invalid" })
        assertFailsWith<CancellationException> { cancelledService.authorizeAndSave() }
        val invalidService = AccountAuthorizationService(repository, LocalTokenKeyStore(keyPath()),
            { OAuthCredentials("novo", "", expires) }, { "teste@example.invalid" })
        assertFailsWith<TokenProtectionException> { invalidService.authorizeAndSave() }
        assertEquals(before, repository.findAll())
    } }

    /** Autorizações simultâneas da mesma conta produzem um registro e nunca misturam o par de tokens. */
    @Test
    fun `autorizações concorrentes mantem uma unica conta e par coerente`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val results = (1..8).map { suffix -> async { service(repository, suffix = "-$suffix").authorizeAndSave() } }.awaitAll()
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(1, repository.findAll().size)
        val tokens = assertNotNull(service(repository).loadCredentials(results.first().id))
        assertEquals(tokens.accessToken.removePrefix("acesso-ficticio"), tokens.refreshToken.removePrefix("refresh-ficticio"))
    } }

    /** Recuperação exige a mesma identidade e preserva a conta quando o navegador seleciona outro e-mail. */
    @Test
    fun `reautoriza somente a conta solicitada`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val saved = service(repository).authorizeAndSave()
        val before = repository.findAll()
        assertFailsWith<AccountAuthorizationException> {
            service(repository, "outra@example.invalid").reauthorize(saved.id)
        }
        assertEquals(before, repository.findAll())
        val recovered = service(repository, suffix = "-recuperado").reauthorize(saved.id)
        assertEquals(saved.id, recovered.id)
        assertEquals("acesso-ficticio-recuperado", assertNotNull(service(repository).loadCredentials(saved.id)).accessToken)
        assertFailsWith<AccountAuthorizationException> { service(repository).reauthorize(999) }
    } }

    /** Uma recuperação que termina após a remoção da conta não pode inseri-la novamente no H2. */
    @Test
    fun `reautorizacao atrasada nao recria conta removida`() = withDatabase { database -> runBlocking {
        val repository = AccountRepository(database)
        val saved = service(repository).authorizeAndSave()
        val recovery = AccountAuthorizationService(repository, LocalTokenKeyStore(keyPath()), {
            repository.delete(saved.id)
            OAuthCredentials("novo", "novo-refresh", expires)
        }, { saved.email })
        assertFailsWith<AccountAuthorizationException> { recovery.reauthorize(saved.id) }
        assertTrue(repository.findAll().isEmpty())
    } }
}
