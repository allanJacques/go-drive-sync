package com.godrivesync.ui.screens.mappings

import com.godrivesync.data.db.repositories.*
import com.godrivesync.data.models.SyncDirection
import kotlinx.coroutines.*
import org.junit.Test
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Exercita as ações da tela com o mesmo repositório, transações e esquema usados na janela. */
class MappingsViewModelTest : RepositoryTestSupport() {
    /** Percorre cadastro, cancelamento, validação, edição e remoção confirmada com histórico. */
    @Test
    fun `edita somente as pastas e remove preservando historico e demais vinculos`() = withDatabase { db ->
        val accounts = AccountRepository(db)
        val owner = accounts.save(account()).id!!
        val other = accounts.save(account("outra@example.invalid")).id!!
        val repository = MappingRepository(db)
        val otherMapping = repository.save(mapping(other))
        val history = HistoryRepository(db)
        val otherEntry = history.insert(entry(other, otherMapping.id))
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val vm = MappingsViewModel(repository, scope, Dispatchers.Unconfined)
            val fields = MappingFields("pasta-123", "Projetos", temporaryFolder.root.absolutePath)
            vm.open(owner)
            vm.show(MappingDialog.Form(null))
            vm.save(fields.copy(path = "relativo"))
            assertTrue(vm.state.error!!.contains("absoluto"))
            assertNotNull(fields.copy(driveId = "https://drive.google.com/drive/folders/123").validationError())
            assertNotNull(fields.copy(name = " ").validationError())
            assertTrue(repository.findByAccountId(owner).isEmpty())
            vm.save(fields)
            val created = vm.state.items!!.single()
            assertEquals(SyncDirection.BIDIRECTIONAL, created.syncDirection)
            val record = history.insert(entry(owner, created.id))

            // Simula o motor atualizando campos depois de a tela ter carregado a linha.
            repository.update(created.copy(syncDirection = SyncDirection.DOWNLOAD_ONLY, enabled = false, lastSync = date))
            vm.show(MappingDialog.Form(created))
            val edited = fields.copy(driveId = "outra-pasta", name = "Arquivo", path = Path.of(fields.path, "arquivo").toString())
            vm.save(edited)
            val saved = vm.state.items!!.single()
            assertEquals(created.copy(driveFolderId = edited.driveId, driveFolderName = edited.name,
                localFolderPath = edited.path, syncDirection = SyncDirection.DOWNLOAD_ONLY, enabled = false, lastSync = date), saved)
            assertEquals(listOf(saved), repository.findByAccountId(owner))

            vm.show(MappingDialog.Remove(saved))
            vm.show(null)
            vm.confirmRemoval()
            assertEquals(listOf(record), history.findByAccountId(owner))
            assertFailsWith<IllegalStateException> { repository.removeKeepingHistory(other, saved.id!!) }
            assertEquals(listOf(saved), repository.findByAccountId(owner))
            vm.show(MappingDialog.Remove(saved))
            vm.confirmRemoval()
            assertEquals(emptyList(), vm.state.items)
            assertTrue(repository.findByAccountId(owner).isEmpty())
            assertEquals(listOf(record.copy(mappingId = null)), history.findByAccountId(owner))
            assertEquals(listOf(otherMapping), repository.findByAccountId(other))
            assertEquals(listOf(otherEntry), history.findByAccountId(other))
        } finally {
            scope.cancel()
        }
    }

    /** Controla o fim de cada consulta para reproduzir uma troca de conta durante a leitura anterior. */
    @Test
    fun `resultado atrasado nunca aparece na conta seguinte`() = withDatabase { db ->
        val queue = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            /** Adia o bloco até que o teste permita sua execução. */
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val accounts = AccountRepository(db)
            val first = accounts.save(account()).id!!
            val second = accounts.save(account("segunda@example.invalid")).id!!
            val repository = MappingRepository(db)
            repository.save(mapping(first))
            val expected = repository.save(mapping(second))
            val vm = MappingsViewModel(repository, scope, io)
            vm.open(first)
            vm.open(second)
            queue.removeFirst().run()
            assertEquals(second, vm.state.accountId)
            assertNull(vm.state.items)
            queue.removeFirst().run()
            assertEquals(listOf(expected), vm.state.items)
        } finally {
            scope.cancel()
        }
    }
}
