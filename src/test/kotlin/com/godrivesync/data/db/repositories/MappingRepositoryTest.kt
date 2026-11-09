package com.godrivesync.data.db.repositories

import com.godrivesync.data.models.SyncDirection
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Testes de integração dos vínculos, incluindo alterações, pausa e isolamento entre contas. */
class MappingRepositoryTest : RepositoryTestSupport() {
    /** Confere todos os campos, defaults, filtros, atualização, pausa e exclusão sem dependentes. */
    @Test
    fun createsUpdatesPausesAndDeletesMappings() = withDatabase { database ->
        val accounts = AccountRepository(database)
        val firstId = assertNotNull(accounts.save(account()).id)
        val secondId = assertNotNull(accounts.save(account("outra@example.invalid")).id)
        val repository = MappingRepository(database)
        assertTrue(repository.findByAccountId(firstId).isEmpty())
        val first = repository.save(mapping(firstId))
        val sibling = repository.save(mapping(firstId).copy(driveFolderId = "pasta-2"))
        val other = repository.save(mapping(secondId))
        val id = assertNotNull(first.id)
        assertTrue(id > 0)
        assertEquals(SyncDirection.BIDIRECTIONAL, first.syncDirection)
        assertTrue(first.enabled)
        assertNull(first.lastSync)
        assertEquals(listOf(first, sibling), repository.findByAccountId(firstId))
        assertEquals(listOf(other), repository.findByAccountId(secondId))

        val changed = first.copy(
            driveFolderId = "renomeada-id",
            driveFolderName = "Fotos",
            localFolderPath = "/tmp/fotos",
            syncDirection = SyncDirection.DOWNLOAD_ONLY,
            lastSync = date,
            enabled = false,
        )
        assertTrue(repository.update(changed))
        assertEquals(changed, repository.findByAccountId(firstId).first())
        assertTrue(repository.setEnabled(id, true))
        assertEquals(changed.copy(enabled = true), repository.findByAccountId(firstId).first())
        assertTrue(repository.setEnabled(id, false))
        assertEquals(changed, repository.findByAccountId(firstId).first())
        assertTrue(repository.update(changed.copy(lastSync = null)))
        assertNull(repository.findByAccountId(firstId).first().lastSync)
        assertTrue(repository.delete(id))
        assertFalse(repository.delete(id))
        assertEquals(listOf(sibling), repository.findByAccountId(firstId))
        assertFalse(repository.setEnabled(id, true))
        assertFalse(repository.update(changed))
    }

    /** A API diferencia criação de edição e impede trocar o dono de um vínculo já usado no histórico. */
    @Test
    fun rejectsInvalidIdentityAndOwnershipWithoutChangingData() = withDatabase { database ->
        val accounts = AccountRepository(database)
        val firstId = assertNotNull(accounts.save(account()).id)
        val secondId = assertNotNull(accounts.save(account("outra@example.invalid")).id)
        val repository = MappingRepository(database)
        val saved = repository.save(mapping(firstId))
        val history = HistoryRepository(database)
        val record = history.insert(entry(firstId, saved.id))
        assertFailsWith<IllegalArgumentException> { repository.save(saved) }
        assertFailsWith<IllegalArgumentException> { repository.update(mapping(firstId)) }
        assertFailsWith<IllegalArgumentException> { repository.update(saved.copy(accountId = secondId)) }
        assertFailsWith<ExposedSQLException> { repository.save(mapping(Int.MAX_VALUE)) }
        assertEquals(listOf(saved), repository.findByAccountId(firstId))
        assertTrue(repository.findByAccountId(secondId).isEmpty())
        assertEquals(listOf(record), history.findByAccountId(firstId))
    }
}
