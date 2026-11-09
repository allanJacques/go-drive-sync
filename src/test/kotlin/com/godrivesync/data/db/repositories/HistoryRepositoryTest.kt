package com.godrivesync.data.db.repositories

import com.godrivesync.data.models.TransferDirection
import com.godrivesync.data.models.TransferStatus
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Testa registro de operações, filtros, coerência entre contas e limpeza temporal com H2 real. */
class HistoryRepositoryTest : RepositoryTestSupport() {
    /** Verifica todos os campos, enums, tamanho Long, ordem por data/ID e combinação dos filtros. */
    @Test
    fun storesAndFiltersHistoryWithDeterministicOrdering() = withDatabase { database ->
        val accounts = AccountRepository(database)
        val firstId = assertNotNull(accounts.save(account()).id)
        val secondId = assertNotNull(accounts.save(account("outra@example.invalid")).id)
        val mappingId = MappingRepository(database).save(mapping(firstId)).id
        val repository = HistoryRepository(database)
        assertTrue(repository.findByAccountId(firstId).isEmpty())
        assertTrue(repository.findByDirection(TransferDirection.UPLOAD).isEmpty())
        val oldest = repository.insert(entry(firstId, mappingId).copy(timestamp = date.minusSeconds(1)))
        val newer = repository.insert(entry(firstId).copy(timestamp = date, status = TransferStatus.SKIPPED))
        val failureInput = entry(firstId, mappingId).copy(
            timestamp = date,
            direction = TransferDirection.UPLOAD,
            status = TransferStatus.FAILED,
            errorMessage = "Falha fictícia de rede",
        )
        val failure = repository.insert(failureInput)
        val other = repository.insert(entry(secondId).copy(timestamp = date.plusSeconds(1)))
        assertEquals(failureInput.copy(id = failure.id), failure)
        assertTrue(assertNotNull(failure.id) > 0)
        assertEquals(listOf(failure, newer, oldest), repository.findByAccountId(firstId))
        assertEquals(listOf(other), repository.findByAccountId(secondId))
        assertEquals(listOf(other, newer, oldest), repository.findByDirection(TransferDirection.DOWNLOAD))
        assertEquals(listOf(newer, oldest), repository.findByDirection(TransferDirection.DOWNLOAD, firstId))
        assertEquals(listOf(failure), repository.findByDirection(TransferDirection.UPLOAD, firstId))
        assertTrue(repository.findByDirection(TransferDirection.UPLOAD, secondId).isEmpty())
        assertTrue(repository.findByAccountId(Int.MAX_VALUE).isEmpty())
    }

    /** Datas omitidas vêm do banco; vínculos opcionais e valores permanecem após fechar e reabrir o arquivo. */
    @Test
    fun usesDatabaseTimestampAndPreservesOptionalValuesOnReopen() {
        var accountId = 0
        withDatabase { database ->
            accountId = assertNotNull(AccountRepository(database).save(account()).id)
            val saved = HistoryRepository(database).insert(entry(accountId).copy(fileSize = 0))
            assertNotNull(saved.timestamp)
            assertNull(saved.mappingId)
            assertNull(saved.errorMessage)
        }
        withDatabase { database ->
            val saved = HistoryRepository(database).findByAccountId(accountId).single()
            assertEquals(entry(accountId).copy(id = saved.id, timestamp = saved.timestamp, fileSize = 0), saved)
        }
    }

    /** Não permite registrar transferências com identidade pronta, tamanho inválido ou relações incoerentes. */
    @Test
    fun rejectsInvalidEntriesWithoutPartialWrites() = withDatabase { database ->
        val accounts = AccountRepository(database)
        val firstId = assertNotNull(accounts.save(account()).id)
        val secondId = assertNotNull(accounts.save(account("outra@example.invalid")).id)
        val mappingId = assertNotNull(MappingRepository(database).save(mapping(firstId)).id)
        val repository = HistoryRepository(database)
        assertFailsWith<IllegalArgumentException> { repository.insert(entry(firstId).copy(id = 1)) }
        assertFailsWith<IllegalArgumentException> { repository.insert(entry(firstId).copy(fileSize = -1)) }
        assertFailsWith<IllegalArgumentException> { repository.insert(entry(firstId, Int.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> { repository.insert(entry(secondId, mappingId)) }
        assertFailsWith<ExposedSQLException> { repository.insert(entry(Int.MAX_VALUE)) }
        assertTrue(repository.findByAccountId(firstId).isEmpty())
        assertTrue(repository.findByAccountId(secondId).isEmpty())
        assertTrue(repository.findByAccountId(Int.MAX_VALUE).isEmpty())
    }

    /** A limpeza é global, estritamente anterior ao limite e não remove contas ou mapeamentos. */
    @Test
    fun deletesOnlyEntriesStrictlyOlderThanCutoff() = withDatabase { database ->
        val accounts = AccountRepository(database)
        val first = accounts.save(account())
        val second = accounts.save(account("outra@example.invalid"))
        val firstId = assertNotNull(first.id)
        val secondId = assertNotNull(second.id)
        val mappings = MappingRepository(database)
        val savedMapping = mappings.save(mapping(firstId))
        val repository = HistoryRepository(database)
        repository.insert(entry(firstId, savedMapping.id).copy(timestamp = date.minusNanos(1)))
        repository.insert(entry(secondId).copy(timestamp = date.minusDays(1)))
        val boundary = repository.insert(entry(firstId).copy(timestamp = date))
        val newer = repository.insert(entry(secondId).copy(timestamp = date.plusNanos(1)))
        assertEquals(2, repository.deleteOlderThan(date))
        assertEquals(0, repository.deleteOlderThan(date))
        assertEquals(listOf(boundary), repository.findByAccountId(firstId))
        assertEquals(listOf(newer), repository.findByAccountId(secondId))
        assertEquals(listOf(first, second), accounts.findAll())
        assertEquals(listOf(savedMapping), mappings.findByAccountId(firstId))
    }

    /** Exclusões respeitam as FKs e só são liberadas depois de remover explicitamente os dependentes. */
    @Test
    fun preservesReferencedRowsUntilDependentsAreExplicitlyRemoved() = withDatabase { database ->
        val accounts = AccountRepository(database)
        val savedAccount = accounts.save(account().copy(isDefault = true))
        val accountId = assertNotNull(savedAccount.id)
        val mappings = MappingRepository(database)
        val savedMapping = mappings.save(mapping(accountId))
        val mappingId = assertNotNull(savedMapping.id)
        val history = HistoryRepository(database)
        val savedEntry = history.insert(entry(accountId, mappingId).copy(timestamp = date))
        val unlinked = history.insert(entry(accountId).copy(timestamp = date))
        assertFailsWith<ExposedSQLException> { accounts.delete(accountId) }
        assertFailsWith<ExposedSQLException> { mappings.delete(mappingId) }
        assertEquals(listOf(savedAccount), accounts.findAll())
        assertEquals(listOf(savedMapping), mappings.findByAccountId(accountId))
        assertEquals(listOf(unlinked, savedEntry), history.findByAccountId(accountId))
        assertEquals(2, history.deleteOlderThan(date.plusNanos(1)))
        assertFailsWith<ExposedSQLException> { accounts.delete(accountId) }
        assertTrue(mappings.delete(mappingId))
        // Mesmo sem mapeamentos, uma operação avulsa ainda precisa proteger a conta.
        history.insert(entry(accountId).copy(timestamp = date))
        assertFailsWith<ExposedSQLException> { accounts.delete(accountId) }
        assertEquals(1, history.deleteOlderThan(date.plusNanos(1)))
        assertTrue(accounts.delete(accountId))
    }
}
