package com.godrivesync.data.db.repositories

import com.godrivesync.data.models.Account
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifica contratos das contas e atomicidade da preferência padrão usando H2 real. */
class AccountRepositoryTest : RepositoryTestSupport() {
    /** Exercita os campos, update por ID, data original, busca exata e DTOs utilizáveis fora da transação. */
    @Test
    fun savesReadsAndUpdatesAccountWithoutChangingCreationDate() = withDatabase { database ->
        val repository = AccountRepository(database)
        assertTrue(repository.findAll().isEmpty())
        val original = account().copy(createdAt = date.minusDays(1), lastFullSync = date.minusHours(1))
        val saved = repository.save(original)
        assertTrue(assertNotNull(saved.id) > 0)
        assertEquals(original.copy(id = saved.id), saved)
        assertEquals(saved, repository.findByEmail(original.email))
        assertNull(repository.findByEmail(original.email.uppercase()))
        assertFalse(saved.toString().contains(saved.accessToken))
        assertFalse(saved.toString().contains(saved.refreshToken))

        val changed = saved.copy(
            email = "renomeado@example.invalid",
            accessToken = "nova-cifra-ficticia",
            refreshToken = "nova-renovacao-ficticia",
            tokenExpiry = date.plusHours(1),
            lastFullSync = null,
            createdAt = date.plusYears(1),
        )
        val updated = repository.save(changed)
        assertEquals(changed.copy(createdAt = saved.createdAt), updated)
        assertNull(repository.findByEmail(original.email))
        assertEquals(listOf(updated), repository.findAll())
        assertTrue(repository.delete(saved.id))
        assertFalse(repository.delete(saved.id))
        assertTrue(repository.findAll().isEmpty())
    }

    /** Confere defaults SQL, persistência ao reabrir e ordenação estável por ID. */
    @Test
    fun preservesAccountsWhenReopeningDatabase() {
        lateinit var expected: List<Account>
        withDatabase { database ->
            val repository = AccountRepository(database)
            val first = repository.save(account("z@example.invalid"))
            val second = repository.save(account("a@example.invalid"))
            assertNotNull(first.createdAt)
            assertNull(first.lastFullSync)
            assertFalse(first.isDefault)
            expected = listOf(first, second)
        }
        withDatabase { database -> assertEquals(expected, AccountRepository(database).findAll()) }
    }

    /** Salvar, escolher, desmarcar e remover a padrão mantêm no máximo uma preferência, sem eleger substituta. */
    @Test
    fun maintainsSingleDefaultAcrossSaveSetAndDelete() = withDatabase { database ->
        val repository = AccountRepository(database)
        val first = repository.save(account().copy(isDefault = true))
        val second = repository.save(account("segunda@example.invalid").copy(isDefault = true))
        assertEquals(listOf(second.id), repository.findAll().filter { it.isDefault }.map { it.id })
        assertTrue(repository.setDefault(assertNotNull(first.id)))
        assertTrue(repository.setDefault(first.id))
        assertEquals(listOf(first.id), repository.findAll().filter { it.isDefault }.map { it.id })
        repository.save(first.copy(isDefault = false))
        assertTrue(repository.findAll().none { it.isDefault })
        repository.save(second.copy(isDefault = true))
        assertTrue(repository.delete(assertNotNull(second.id)))
        assertTrue(repository.findAll().none { it.isDefault })
    }

    /** Uma falha após desmarcar a padrão desfaz a transação inteira tanto no insert quanto no update. */
    @Test
    fun rollsBackDefaultChangeWhenEmailIsDuplicate() = withDatabase { database ->
        val repository = AccountRepository(database)
        val first = repository.save(account().copy(isDefault = true))
        val second = repository.save(account("segunda@example.invalid"))
        val expected = listOf(first, second)
        assertFailsWith<ExposedSQLException> { repository.save(account().copy(isDefault = true)) }
        assertEquals(expected, repository.findAll())
        assertFailsWith<ExposedSQLException> {
            repository.save(second.copy(email = first.email, isDefault = true))
        }
        assertEquals(expected, repository.findAll())
    }

    /** IDs ausentes não criam registros acidentalmente nem apagam a preferência anterior. */
    @Test
    fun leavesDataUnchangedForMissingIds() = withDatabase { database ->
        val repository = AccountRepository(database)
        val saved = repository.save(account().copy(isDefault = true))
        assertFalse(repository.setDefault(Int.MAX_VALUE))
        assertFalse(repository.delete(Int.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> {
            repository.save(account("nova@example.invalid").copy(id = Int.MAX_VALUE, isDefault = true))
        }
        assertEquals(listOf(saved), repository.findAll())
    }

    /** Dois bancos registrados simultaneamente não desviam consultas para o último Database conectado. */
    @Test
    fun usesExplicitDatabaseInsteadOfGlobalDefault() = withDatabase("first") { firstDatabase ->
        val firstRepository = AccountRepository(firstDatabase)
        val first = firstRepository.save(account())
        withDatabase("second") { secondDatabase ->
            val secondRepository = AccountRepository(secondDatabase)
            assertTrue(secondRepository.findAll().isEmpty())
            val second = secondRepository.save(account("outra@example.invalid"))
            assertEquals(listOf(first), firstRepository.findAll())
            assertEquals(listOf(second), secondRepository.findAll())
        }
    }

    /** Impede que uma transação externa estenda o commit para além da proteção compartilhada. */
    @Test
    fun rejectsAccountWritesInsideExternalTransaction() = withDatabase { database ->
        val repository = AccountRepository(database)
        transaction(database) {
            assertFailsWith<IllegalStateException> { repository.save(account()) }
            assertFailsWith<IllegalStateException> { repository.setDefault(1) }
            assertFailsWith<IllegalStateException> { repository.delete(1) }
        }
        assertTrue(repository.findAll().isEmpty())
    }

    /** Exercita a corrida da primeira inserção e trocas simultâneas por instâncias diferentes do repositório. */
    @Test
    fun serializesConcurrentDefaultWritesAcrossRepositoryInstances() = withDatabase { database ->
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val inserts = (1..2).map { index ->
                executor.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    AccountRepository(database).save(account("conta$index@example.invalid").copy(isDefault = true))
                })
            }
            start.countDown()
            val accounts = inserts.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(2, AccountRepository(database).findAll().size)
            assertEquals(1, AccountRepository(database).findAll().count { it.isDefault })

            val changes = accounts.map { saved ->
                executor.submit(Callable {
                    val repository = AccountRepository(database)
                    repeat(5) { assertTrue(repository.setDefault(assertNotNull(saved.id))) }
                })
            }
            changes.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, AccountRepository(database).findAll().count { it.isDefault })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
