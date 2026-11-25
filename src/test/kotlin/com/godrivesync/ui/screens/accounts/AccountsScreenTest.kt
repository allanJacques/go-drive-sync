package com.godrivesync.ui.screens.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.ui.screens.mappings.MappingsViewModel
import com.godrivesync.App
import com.godrivesync.drive.auth.GoogleAccountProfile
import kotlinx.coroutines.*
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.ui.screens.login.*
import com.godrivesync.ui.theme.AppStyle
import com.godrivesync.ui.theme.GoDriveSyncTheme
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import javax.swing.SwingUtilities
import kotlin.test.*

/** Renderiza cartões e confirmação em Compose real, nos sete temas e duas dimensões de conteúdo. */
class AccountsScreenTest : RepositoryTestSupport() {
    /** Dados fictícios cobrem e-mail longo, preferência e data persistida, sem tokens. */
    private val account = SavedAccount(1,
        "uma.conta.com.endereco.longo.para.validar.layout@example.invalid", Instant.EPOCH, true,
        LocalDateTime.of(2026, 11, 17, 10, 25))
    /** Relógio de quadros crescente permite compor popups e animações sem depender de sleeps. */
    private var frame = 0L

    /** Estados visuais e ações semânticas incluem confirmação, falha, progresso, lista vazia e OAuth. */
    @Test
    fun `gerenciamento renderiza e respeita bloqueios em todos os temas`() {
        SwingUtilities.invokeAndWait {
            for (style in AppStyle.entries) for ((width, height) in listOf(604 to 456, 824 to 616)) {
                for (scenario in listOf("accounts", "empty", "loading", "oauth", "error", "dialog", "failure", "busy", "saving")) {
                    var removed: Int? = null
                    var preferred: Int? = null
                    var confirms = 0
                    var cancels = 0
                    val login = LoginState(loaded = scenario != "loading", loading = scenario == "loading",
                        authorizing = scenario == "oauth", accounts = if (scenario == "empty") emptyList() else listOf(
                            LoginAccount(account, AccountAccess.AVAILABLE),
                            LoginAccount(account.copy(id = 2, isDefault = false, lastFullSync = null), AccountAccess.RECONNECT),
                        ))
                    val state = AccountsState(
                        removal = if (scenario in listOf("dialog", "failure", "busy")) account else null,
                        busy = scenario in listOf("busy", "saving"),
                        message = if (scenario in listOf("failure", "error")) "Não foi possível concluir a operação. Tente novamente." else null,
                    )
                    val scene = ImageComposeScene(width, height) {
                        GoDriveSyncTheme(style) {
                            Surface {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                    AccountsScreen(login, state, {}, {}, {}, {}, {}, {}, { preferred = it },
                                        { removed = it }, { confirms++ }, { cancels++ })
                                }
                            }
                        }
                    }
                    try {
                        repeat(3) { frame += 100_000_000L; scene.render(frame).close() }
                        val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                        val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                        if (scenario == "accounts") {
                            assertTrue("Padrão" in texts)
                            assertFalse("Conta adicional" in texts)
                            assertTrue("Última sincronização: 17/11/2026 10:25" in texts)
                            assertTrue("Ainda não sincronizada" in texts)
                            val defaults = nodes.filter { hasText(it, "Definir como padrão") }
                            assertEquals(1, defaults.size)
                            assertFalse(defaults.single().config.contains(SemanticsProperties.Disabled))
                            defaults.last().config[SemanticsActions.OnClick].action!!.invoke()
                            assertEquals(2, preferred)
                            nodes.first { hasText(it, "Sair da conta") }.config[SemanticsActions.OnClick].action!!.invoke()
                            assertEquals(1, removed)
                            assertEquals(0, confirms)
                        }
                        if (state.removal != null) {
                            assertTrue(texts.any { it.contains("configurações de pastas e seu histórico") })
                            assertFalse(texts.any { it.contains("token", ignoreCase = true) || it.contains("revog", ignoreCase = true) })
                            val confirm = nodes.first { it.config.getOrNull(SemanticsProperties.TestTag) == "confirm-account-removal" }
                            val cancel = nodes.first { hasText(it, "Cancelar") }
                            assertEquals(state.busy, confirm.config.contains(SemanticsProperties.Disabled))
                            assertEquals(state.busy, cancel.config.contains(SemanticsProperties.Disabled))
                            // Confirmação permanece visível mesmo com erro/progresso na janela menor.
                            assertTrue(confirm.boundsInRoot.height > 0f)
                            assertTrue(cancel.boundsInRoot.height > 0f)
                            if (scenario == "failure" || scenario == "busy") {
                                val feedback = nodes.first { hasText(it, if (scenario == "busy") "Removendo conta…"
                                    else "Não foi possível concluir a operação. Tente novamente.") }
                                assertTrue(feedback.boundsInRoot.height > 0f)
                            }
                            if (!state.busy) {
                                confirm.config[SemanticsActions.OnClick].action!!.invoke()
                                cancel.config[SemanticsActions.OnClick].action!!.invoke()
                                assertEquals(1, confirms); assertEquals(1, cancels)
                            }
                        }
                        if (scenario in listOf("loading", "oauth", "saving")) {
                            nodes.filter { hasText(it, "Sair da conta") || hasText(it, "Definir como padrão") }
                                .forEach { assertTrue(it.config.contains(SemanticsProperties.Disabled)) }
                        }
                        val directory = File("build/accounts-ui").apply { mkdirs() }
                        frame += 100_000_000L
                        scene.render(frame).use { image -> image.encodeToData()!!.use {
                            File(directory, "${style.id}-$width-$scenario.png").writeBytes(it.bytes)
                        } }
                    } finally { scene.close() }
                }
            }
        }
    }

    /** Contexto inicial respeita a padrão e é substituído/limpo quando a conta visual é removida. */
    @Test
    fun `app reconcilia contexto de mapeamentos e ultima conta`() = withDatabase { database ->
        SwingUtilities.invokeAndWait {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val backend = object : LoginAccounts, AccountManagement {
                var saved = listOf(account, account.copy(id = 2, email = "outra@example.invalid", isDefault = false))
                /** Restaura somente dados fictícios, com a primeira conta definida como padrão. */
                override suspend fun list() = saved
                /** Nenhum consentimento é disparado por navegação ou remoção. */
                override suspend fun authorize(accountId: Int?): SavedAccount = error("OAuth indevido")
                /** Perfil imediato sem foto externa. */
                override suspend fun profile(account: SavedAccount) = GoogleAccountProfile(null)
                /** O teste usa somente o contexto visual de mapeamentos. */
                override suspend fun hasMappings(accountId: Int) = false
                /** Simula a preferência persistida sem trocar a seleção explícita. */
                override suspend fun setDefault(id: Int) { saved = saved.map { it.copy(isDefault = it.id == id) } }
                /** Simula saída local; publicação e navegação continuam usando os ViewModels reais. */
                override suspend fun remove(id: Int) { saved = saved.filterNot { it.id == id } }
            }
            val login = LoginViewModel(backend, scope)
            val management = AccountsViewModel(login, backend, scope)
            val mappings = MappingsViewModel(MappingRepository(database), scope, Dispatchers.Unconfined)
            val scene = ImageComposeScene(900, 640) { App(AppStyle.entries.first(), {}, login, management, mappings) }
            /** Avança efeitos de navegação e devolve os nós atuais para inspeção semântica. */
            fun nodes(): List<SemanticsNode> {
                repeat(4) { frame += 100_000_000L; scene.render(frame).close() }
                return scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            }
            try {
                nodes().first { hasText(it, "Mapeamentos") }.config[SemanticsActions.OnClick].action!!.invoke()
                assertTrue(nodes().any { hasText(it, "Mapeamentos de ${account.email}") })
                management.setDefault(2)
                assertTrue(nodes().any { hasText(it, "Mapeamentos de ${account.email}") })
                management.requestRemoval(1); management.confirmRemoval()
                assertTrue(nodes().any { hasText(it, "Mapeamentos de outra@example.invalid") })
                assertTrue(nodes().any { hasText(it, "1 conta(s) salva(s)") })
                management.requestRemoval(2); management.confirmRemoval()
                assertTrue(nodes().any { hasText(it, "Conecte seu Google Drive") })
                assertTrue(nodes().any { hasText(it, "0 conta(s) salva(s)") })
                assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any {
                    text -> text.text.startsWith("Mapeamentos de ") } == true })
            } finally { scene.close(); management.close(); login.close(); scope.cancel() }
        }
    }

    /** Localiza o texto no nó mesclado do botão, que também contém a ação e o estado disabled. */
    private fun hasText(node: SemanticsNode, text: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true

    /** Inclui popups entre os owners e percorre seus descendentes sem depender de coordenadas. */
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
