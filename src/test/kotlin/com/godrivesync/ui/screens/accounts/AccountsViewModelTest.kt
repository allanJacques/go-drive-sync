package com.godrivesync.ui.screens.accounts

import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.GoogleAccountProfile
import com.godrivesync.ui.navigation.AppDestination
import com.godrivesync.ui.screens.login.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.time.Instant
import kotlin.test.*

/** Exercita confirmação e coordenação de operações usando barreiras, sem sleeps, Google ou banco real. */
class AccountsViewModelTest {
    /** Backend comum permite conferir tanto persistência simulada quanto estado compartilhado. */
    private class Backend : LoginAccounts, AccountManagement {
        var saved = (1..2).map { SavedAccount(it, "conta$it@example.invalid", Instant.EPOCH, it == 1) }
        var removed = 0
        var defaults = 0
        var fail = false
        var beforeWrite: suspend () -> Unit = {}
        var profileAction: suspend (SavedAccount) -> GoogleAccountProfile = { GoogleAccountProfile(null) }
        /** Retorna metadados locais, independentemente da rede. */
        override suspend fun list() = saved
        /** OAuth permanece pendente até cancelamento explícito. */
        override suspend fun authorize(accountId: Int?): SavedAccount = awaitCancellation()
        /** Permite simular resposta não cancelável que chega após a remoção. */
        override suspend fun profile(account: SavedAccount) = profileAction(account)
        /** Os cenários não possuem pastas, nem precisam do motor de sincronização. */
        override suspend fun hasMappings(accountId: Int) = false
        /** Registra a preferência somente após a barreira e simula falha sanitizável. */
        override suspend fun setDefault(id: Int) {
            beforeWrite()
            if (fail) error("segredo SQL")
            defaults++
            saved = saved.map { it.copy(isDefault = it.id == id) }
        }
        /** Simula exclusão idempotente com a mesma possibilidade de falha do banco. */
        override suspend fun remove(id: Int) {
            beforeWrite()
            if (fail) error("segredo SQL")
            removed++
            saved = saved.filterNot { it.id == id }
        }
    }

    /** Aguarda uma fotografia publicada e limita falhas de sincronização a cinco segundos. */
    private suspend fun LoginViewModel.ready() = withTimeout(5_000) { state.first { it.loaded && !it.loading } }

    /** Espera o fim da alteração local, incluindo publicação dos erros ou sucesso. */
    private suspend fun AccountsViewModel.idle() = withTimeout(5_000) { state.first { !it.busy } }

    /** Cancelar não grava; trocar padrão não perde outra conta; remover a última retorna à apresentação. */
    @Test
    fun `confirmacao preferencia e ultima conta atualizam fonte compartilhada`() = runBlocking {
        val backend = Backend()
        val login = LoginViewModel(backend, this)
        val vm = AccountsViewModel(login, backend, this)
        try {
            login.start(); login.ready()
            vm.requestRemoval(1)
            assertEquals(1, vm.state.value.removal?.id)
            assertEquals(0, backend.removed)
            vm.cancelRemoval(); vm.confirmRemoval()
            assertEquals(0, backend.removed)
            vm.setDefault(2); vm.idle()
            assertEquals(1, backend.defaults)
            assertEquals(listOf(2), login.state.value.accounts.filter { it.account.isDefault }.map { it.account.id })
            login.viewMappings(2)
            vm.requestRemoval(2); vm.confirmRemoval(); vm.idle()
            assertNull(login.state.value.navigation)
            assertTrue(login.state.value.accounts.none { it.account.isDefault })
            assertEquals(1, login.state.value.accounts.size)
            vm.requestRemoval(1); vm.confirmRemoval(); vm.idle()
            assertTrue(login.state.value.accounts.isEmpty())
            assertEquals(AppDestination.ACCOUNTS, login.state.value.navigation?.destination)
            assertNull(vm.state.value.removal)
            assertEquals(2, backend.removed)
        } finally { vm.close(); login.close() }
    }

    /** Falha conserva diálogo/lista e permite retry; não expõe exceção nem aplica preferência otimista. */
    @Test
    fun `falhas preservam estado e permitem tentar novamente`() = runBlocking {
        val backend = Backend().apply { fail = true }
        val login = LoginViewModel(backend, this)
        val vm = AccountsViewModel(login, backend, this)
        try {
            login.start(); login.ready()
            vm.setDefault(2); vm.idle()
            assertTrue(login.state.value.accounts.first().account.isDefault)
            assertFalse(assertNotNull(vm.state.value.message).contains("segredo"))
            vm.requestRemoval(1); vm.confirmRemoval(); vm.idle()
            assertEquals(1, vm.state.value.removal?.id)
            assertEquals(2, login.state.value.accounts.size)
            assertFalse(assertNotNull(vm.state.value.message).contains("segredo"))
            backend.fail = false
            vm.confirmRemoval(); vm.idle()
            assertEquals(listOf(2), login.state.value.accounts.map { it.account.id })
            assertNull(vm.state.value.message)
        } finally { vm.close(); login.close() }
    }

    /** OAuth e gravação local não se sobrepõem; fechamento durante commit não deixa estado divergente. */
    @Test
    fun `operacoes concorrentes sao recusadas e commit sobrevive ao fechamento`() = runBlocking {
        val backend = Backend()
        val login = LoginViewModel(backend, this)
        val vm = AccountsViewModel(login, backend, this)
        val barrier = CompletableDeferred<Unit>()
        try {
            login.start(); login.ready()
            login.authorize()
            vm.requestRemoval(1); vm.setDefault(2)
            assertNull(vm.state.value.removal)
            assertEquals(0, backend.defaults)
            login.cancelAuthorization()
            withTimeout(5_000) { login.state.first { !it.authorizing && !it.loading } }
            backend.beforeWrite = { barrier.await() }
            vm.requestRemoval(1); vm.confirmRemoval(); vm.confirmRemoval()
            assertTrue(login.state.value.managing)
            login.authorize(); login.reload()
            assertFalse(login.state.value.authorizing)
            assertFalse(login.state.value.loading)
            vm.cancelRemoval()
            assertNotNull(vm.state.value.removal)
            vm.close()
            barrier.complete(Unit)
            vm.idle()
            assertEquals(1, backend.removed)
            assertEquals(backend.saved, login.state.value.accounts.map { it.account })
        } finally { barrier.complete(Unit); vm.close(); login.close() }
    }

    /** Perfil antigo pode terminar em IO depois da saída, mas não pode recolocar a conta removida. */
    @Test
    fun `perfil atrasado nao restaura conta removida`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val backend = Backend().apply {
            profileAction = {
                if (it.id == 1) withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); finished.complete(Unit)
                }
                GoogleAccountProfile(null)
            }
        }
        val login = LoginViewModel(backend, this)
        val vm = AccountsViewModel(login, backend, this)
        try {
            login.start(); login.ready()
            withTimeout(5_000) { entered.await() }
            vm.requestRemoval(1); vm.confirmRemoval(); vm.idle()
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }
            yield()
            assertEquals(listOf(2), login.state.value.accounts.map { it.account.id })
        } finally { release.complete(Unit); vm.close(); login.close() }
    }
}
