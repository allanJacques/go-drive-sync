package com.godrivesync.ui.screens.login

import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.*
import com.godrivesync.ui.navigation.AppDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.time.Instant
import kotlin.test.*

/** Exercita comportamento assíncrono com portas falsas e barreiras; não abre navegador nem usa rede real. */
class LoginViewModelTest {
    /** DTOs determinísticos; preferência padrão não pode excluir as outras duas contas. */
    private fun saved(id: Int) = SavedAccount(id, "conta$id@example.invalid", Instant.EPOCH, id == 2)

    /** Espera um estado relevante com limite de tempo, sem sleeps ou dependência de ordem de threads. */
    private suspend fun LoginViewModel.await(predicate: (LoginState) -> Boolean): LoginState =
        withTimeout(5_000) { state.first(predicate) }

    /** Modelo falso mutável permite simular banco persistido, falhas e OAuth que aguarda cancelamento. */
    private class FakeAccounts(var saved: List<SavedAccount> = emptyList()) : LoginAccounts {
        var authorizationCalls = 0
        var profileCalls = mutableListOf<Int>()
        var listFailure = false
        var mappings = false
        var authorizeAction: suspend (Int?) -> SavedAccount = { error("OAuth inesperado") }
        var profileAction: suspend (SavedAccount) -> GoogleAccountProfile = { GoogleAccountProfile(null) }
        /** Lista registros sem consultar a rede; falha opcional simula armazenamento inacessível. */
        override suspend fun list(): List<SavedAccount> {
            if (listFailure) error("detalhe interno secreto")
            return saved
        }
        /** Conta cliques efetivos e delega à barreira/cenário configurado pelo teste. */
        override suspend fun authorize(accountId: Int?): SavedAccount {
            authorizationCalls++
            return authorizeAction(accountId)
        }
        /** Registra todas as contas verificadas; cada uma pode ter resultado independente. */
        override suspend fun profile(account: SavedAccount): GoogleAccountProfile {
            profileCalls.add(account.id)
            return profileAction(account)
        }
        /** Simula somente a existência de mapeamento, sem modificar a lista de contas. */
        override suspend fun hasMappings(accountId: Int) = mappings
    }

    /** Reabrir com três contas não exige OAuth nem aguarda rede; uma falha não bloqueia as demais. */
    @Test
    fun `restaura todas as contas antes da rede e isola falhas`() = runBlocking {
        val slow = CompletableDeferred<GoogleAccountProfile>()
        val backend = FakeAccounts((1..3).map(::saved)).apply {
            profileAction = { when (it.id) {
                1 -> slow.await()
                2 -> throw CredentialException(CredentialFailure.REAUTHORIZATION_REQUIRED, "Reconecte esta conta.")
                else -> GoogleAccountProfile(null)
            } }
        }
        val vm = LoginViewModel(backend, this)
        try {
            vm.start()
            vm.start()
            val restored = vm.await { it.accounts.lastOrNull()?.access == AccountAccess.AVAILABLE }
            assertEquals(listOf(1, 2, 3), restored.accounts.map { it.account.id })
            assertEquals(AppDestination.DASHBOARD, restored.navigation?.destination)
            assertEquals(AccountAccess.CHECKING, restored.accounts[0].access)
            assertEquals(AccountAccess.RECONNECT, restored.accounts[1].access)
            assertEquals(0, backend.authorizationCalls)
            assertEquals(listOf(1, 2, 3), backend.profileCalls)
            vm.viewMappings(3)
            val navigation = assertNotNull(vm.state.value.navigation)
            assertEquals(LoginNavigation(AppDestination.MAPPINGS, 3), navigation)
            vm.consumeNavigation(navigation)
            slow.complete(GoogleAccountProfile(null))
            vm.await { it.accounts.first().access == AccountAccess.AVAILABLE }
            assertNull(vm.state.value.navigation)
            assertEquals(3, vm.state.value.accounts.size)
        } finally { vm.close() }
    }

    /** Banco vazio abre apresentação; autorização salva navega para mapeamentos ou Dashboard conforme dados. */
    @Test
    fun `adicionar navega sem criar selecao exclusiva nem duplicar conta`() = runBlocking {
        val backend = FakeAccounts()
        val vm = LoginViewModel(backend, this)
        try {
            vm.start()
            vm.await { it.loaded && !it.loading }
            assertEquals(AppDestination.ACCOUNTS, vm.state.value.navigation?.destination)
            val consent = CompletableDeferred<Unit>()
            backend.authorizeAction = { consent.await(); backend.saved = listOf(saved(1)); saved(1) }
            vm.authorize()
            vm.authorize() // Duplo clique enquanto o consentimento está aberto não inicia outro fluxo.
            consent.complete(Unit)
            vm.await { !it.authorizing && it.accounts.size == 1 }
            assertEquals(1, backend.authorizationCalls)
            assertEquals(LoginNavigation(AppDestination.MAPPINGS, 1), vm.state.value.navigation)
            backend.mappings = true
            vm.authorize()
            vm.await { !it.authorizing }
            assertEquals(LoginNavigation(AppDestination.DASHBOARD, 1), vm.state.value.navigation)
            assertEquals(1, vm.state.value.accounts.size)
        } finally { vm.close() }
    }

    /** Cancelar depois de salvar reconcilia a conta; fechar a janela cancela a tentativa seguinte. */
    @Test
    fun `cancelamento apos commit recupera conta sem reabrir navegador`() = runBlocking {
        val persisted = CompletableDeferred<Unit>()
        val backend = FakeAccounts().apply { authorizeAction = {
            saved = listOf(this@LoginViewModelTest.saved(1))
            persisted.complete(Unit)
            awaitCancellation()
        } }
        val vm = LoginViewModel(backend, this)
        try {
            vm.start()
            vm.await { it.loaded && !it.loading }
            vm.authorize()
            withTimeout(5_000) { persisted.await() }
            vm.cancelAuthorization()
            vm.await { it.accounts.size == 1 && !it.authorizing && !it.loading }
            assertEquals(1, backend.authorizationCalls)
            val closed = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            backend.authorizeAction = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { closed.complete(Unit) }
            }
            vm.authorize()
            withTimeout(5_000) { entered.await() }
            vm.close()
            withTimeout(5_000) { closed.await() }
        } finally { vm.close() }
    }

    /** Erros transitórios usam retry, foto quebrada mantém acesso e reconexão recebe o ID correto. */
    @Test
    fun `reconexao explicita e falha de foto nao bloqueiam outras contas`() = runBlocking {
        val backend = FakeAccounts(listOf(saved(1), saved(2))).apply {
            profileAction = { if (it.id == 1) throw CredentialException(CredentialFailure.TEMPORARY, "Sem rede.")
                else GoogleAccountProfile("https://foto.invalid") }
        }
        val vm = LoginViewModel(backend, this) { error("imagem inválida") }
        try {
            vm.start()
            vm.await { it.accounts.firstOrNull()?.access == AccountAccess.UNAVAILABLE &&
                it.accounts.lastOrNull()?.access == AccountAccess.AVAILABLE }
            assertNull(vm.state.value.accounts.last().photo)
            backend.profileAction = { GoogleAccountProfile(null) }
            vm.refresh(1)
            vm.await { it.accounts.all { row -> row.access == AccountAccess.AVAILABLE } }
            backend.authorizeAction = { id -> assertEquals(1, id); saved(1) }
            vm.authorize(1)
            vm.await { !it.authorizing }
            assertEquals(setOf(1, 2), vm.state.value.accounts.map { it.account.id }.toSet())
        } finally { vm.close() }
    }

    /** Falha local tem retry e não vaza detalhes; OAuth negado/timeout retorna à tela com nova tentativa disponível. */
    @Test
    fun `erros de armazenamento e oauth sao recuperaveis`() = runBlocking {
        val backend = FakeAccounts().apply { listFailure = true }
        val vm = LoginViewModel(backend, this)
        try {
            vm.start()
            vm.await { !it.loading && it.message != null }
            assertFalse(vm.state.value.loaded)
            assertFalse(vm.state.value.message!!.contains("secreto"))
            backend.listFailure = false
            vm.reload()
            vm.await { it.loaded && !it.loading }
            for (reason in listOf(OAuthFailure.DENIED, OAuthFailure.TIMEOUT)) {
                backend.authorizeAction = { throw OAuthException(reason, "Autorização interrompida.") }
                vm.authorize()
                vm.await { !it.authorizing && it.message != null }
                assertEquals("Autorização interrompida.", vm.state.value.message)
                assertTrue(vm.state.value.accounts.isEmpty())
            }
        } finally { vm.close() }
    }

    /** Cancelar no mesmo ciclo do clique não pode deixar o botão preso no estado de autorização. */
    @Test
    fun `cancelamento imediato libera nova autorizacao`() = runBlocking {
        val backend = FakeAccounts().apply { authorizeAction = { awaitCancellation() } }
        val vm = LoginViewModel(backend, this)
        try {
            vm.start()
            vm.await { it.loaded && !it.loading }
            vm.authorize()
            vm.cancelAuthorization()
            vm.await { !it.authorizing && !it.loading }
            assertEquals(1, backend.authorizationCalls)
            vm.authorize()
            assertTrue(vm.state.value.authorizing)
            assertEquals(2, backend.authorizationCalls)
        } finally { vm.close() }
    }

    /** HTTP antigo pode terminar depois do cancelamento; seu resultado não substitui o novo perfil. */
    @Test
    fun `resposta atrasada nao sobrescreve verificacao atual`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val delayed = CompletableDeferred<Unit>()
        var oldFinished = false
        val backend = FakeAccounts(listOf(saved(1))).apply {
            profileAction = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    delayed.await()
                    oldFinished = true
                }
                throw CredentialException(CredentialFailure.REAUTHORIZATION_REQUIRED, "Resposta antiga")
            }
        }
        val vm = LoginViewModel(backend, this)
        try {
            vm.start()
            withTimeout(5_000) { entered.await() }
            backend.profileAction = { GoogleAccountProfile(null) }
            vm.refresh(1)
            vm.await { it.accounts.single().access == AccountAccess.AVAILABLE }
            delayed.complete(Unit)
            yield()
            assertTrue(oldFinished)
            assertEquals(AccountAccess.AVAILABLE, vm.state.value.accounts.single().access)
            assertNull(vm.state.value.accounts.single().message)
        } finally { delayed.complete(Unit); vm.close() }
    }
}
