package com.godrivesync.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.godrivesync.ui.screens.accounts.AccountManagement
import com.godrivesync.ui.screens.accounts.AccountsViewModel
import com.godrivesync.data.db.repositories.MappingRepository
import com.godrivesync.data.db.repositories.RepositoryTestSupport
import com.godrivesync.ui.screens.mappings.MappingsViewModel
import com.godrivesync.App
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import com.godrivesync.drive.auth.GoogleAccountProfile
import com.godrivesync.ui.theme.AppStyle
import com.godrivesync.ui.theme.GoDriveSyncTheme
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.test.*

/** Renderiza Compose real em superfície raster, sem janela ou rede, com H2 temporário no cenário de navegação; PNGs ficam somente em build/. */
class LoginScreenTest : RepositoryTestSupport() {
    /** Relógio virtual de quadros: animações/rolagem precisam de tempo crescente, mesmo sem janela. */
    private var frameTime = 0L
    /** E-mail longo verifica quebra de texto na menor largura suportada. */
    private fun saved(id: Int) = SavedAccount(id,
        if (id == 1) "uma.conta.com.endereco.longo.para.validar.layout@example.invalid" else "conta$id@example.invalid",
        Instant.EPOCH, false)

    /** Estados críticos cabem nos sete temas; callbacks continuam acionáveis e não existe botão Entrar. */
    @Test
    fun `estados de login renderizam em todos os temas e tamanhos`() {
        SwingUtilities.invokeAndWait {
            val states = mapOf(
                "empty" to LoginState(loaded = true),
                "loading" to LoginState(loading = true),
                "error" to LoginState(message = "Não foi possível carregar as contas salvas. Tente novamente."),
                "oauth" to LoginState(loaded = true, authorizing = true),
                "accounts" to LoginState(loaded = true, accounts = listOf(
                    LoginAccount(saved(1), AccountAccess.AVAILABLE),
                    LoginAccount(saved(2), AccountAccess.RECONNECT, "Autorize esta conta novamente para continuar."),
                    LoginAccount(saved(3), AccountAccess.UNAVAILABLE, "Verifique a conexão e tente novamente."),
                )),
            )
            for (style in AppStyle.entries) for ((width, height) in listOf(900 to 640, 1120 to 800)) {
                for ((name, state) in states) {
                    var adds = 0
                    var cancels = 0
                    val scene = ImageComposeScene(width - 296, height - 184) {
                        GoDriveSyncTheme(style) {
                            Surface {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                    LoginScreen(state, { adds++ }, {}, {}, {}, { cancels++ }, {})
                                }
                            }
                        }
                    }
                    try {
                        save(scene, "${style.id}-${width}-$name")
                        val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                        val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                        assertFalse("Entrar" in texts)
                        val add = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any {
                            text -> text.text == "Adicionar conta Google" } == true }
                        assertEquals(!state.loaded || state.loading || state.authorizing,
                            add.config.contains(SemanticsProperties.Disabled))
                        if (name == "empty") {
                            assertTrue(add.config[SemanticsActions.OnClick].action!!.invoke())
                            assertEquals(1, adds)
                        }
                        if (name == "oauth") {
                            val cancel = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any {
                                text -> text.text == "Cancelar autorização" } == true }
                            cancel.config[SemanticsActions.OnClick].action!!.invoke()
                            assertEquals(1, cancels)
                        }
                        if (name == "accounts") {
                            assertTrue("Reconectar" in texts)
                            assertTrue("Tentar novamente" in texts)
                            assertTrue(saved(1).email in texts)
                        }
                    } finally { scene.close() }
                }
            }
        }
    }

    /** Layout completo recupera três contas e abre Dashboard; menu Contas mantém acesso à lista e ações. */
    @Test
    fun `app abre dashboard automaticamente e permite ver todas as contas`() = withDatabase { database ->
        SwingUtilities.invokeAndWait {
            for (style in AppStyle.entries) for ((width, height) in listOf(900 to 640, 1120 to 800)) {
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val backend = object : LoginAccounts {
                    /** Lista persistida fictícia; não há credenciais usadas neste teste. */
                    override suspend fun list() = (1..3).map(::saved)
                    /** Qualquer OAuth automático representa falha no contrato de inicialização. */
                    override suspend fun authorize(accountId: Int?): SavedAccount = error("OAuth automático indevido")
                    /** Falha de uma conta deve aparecer na lista sem impedir o Dashboard. */
                    override suspend fun profile(account: SavedAccount): GoogleAccountProfile {
                        if (account.id == 2) throw CredentialException(CredentialFailure.REAUTHORIZATION_REQUIRED, "Reconecte esta conta.")
                        return GoogleAccountProfile(null)
                    }
                    /** Nenhum mapeamento fictício precisa de operações reais. */
                    override suspend fun hasMappings(accountId: Int) = false
                }
                val vm = LoginViewModel(backend, scope)
                val management = object : AccountManagement {
                    /** Este cenário só navega; qualquer gravação seria uma regressão. */
                    override suspend fun setDefault(id: Int) { error("Gravação inesperada") }
                    /** Não deve excluir ao abrir ou navegar. */
                    override suspend fun remove(id: Int) { error("Exclusão inesperada") }
                }
                val accountsVm = AccountsViewModel(vm, management, scope)
                val mappings = MappingsViewModel(MappingRepository(database), scope, Dispatchers.Unconfined)
                val scene = ImageComposeScene(width, height) { App(style, {}, vm, accountsVm, mappings) }
                try {
                    repeat(4) { nextFrame(scene).close() }
                    save(scene, "app-${style.id}-$width-dashboard")
                    var nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.Text)?.any {
                        text -> text.text == "Todas as contas, no mesmo lugar." } == true })
                    val accounts = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any {
                        text -> text.text == "Ver contas" } == true }
                    accounts.config[SemanticsActions.OnClick].action!!.invoke()
                    repeat(3) { nextFrame(scene).close() }
                    save(scene, "app-${style.id}-$width-accounts")
                    nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.Text)?.any {
                        text -> text.text == saved(3).email } == true })
                    // Sidebar também rola: selecionar o ancestral da lista evita testar o painel errado.
                    val scroll = nodes.first { node -> node.config.contains(SemanticsActions.ScrollBy) &&
                        descendants(node).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any {
                            text -> text.text == "Suas contas Google" } == true } }
                    scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2_000f)
                    repeat(12) { nextFrame(scene).close() }
                    save(scene, "app-${style.id}-$width-accounts-bottom")
                    nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    val add = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any {
                        text -> text.text == "Adicionar conta Google" } == true }
                    assertTrue(add.boundsInRoot.height > 0f, "Adicionar precisa ser acessível pela rolagem")
                    val mapping = nodes.last { it.config.getOrNull(SemanticsProperties.Text)?.any {
                        text -> text.text == "Ver mapeamentos" } == true }
                    mapping.config[SemanticsActions.OnClick].action!!.invoke()
                    repeat(3) { nextFrame(scene).close() }
                    nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.Text)?.any {
                        text -> text.text == "Mapeamentos de ${saved(3).email}" } == true })
                    assertEquals(3, vm.state.value.accounts.size)
                } finally { scene.close(); accountsVm.close(); vm.close(); scope.cancel() }
            }
        }
    }

    /** Percorre a árvore semântica mesclada, onde o botão reúne texto, estado disabled e ação de clique. */
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    /** Avança 100 ms virtuais por quadro, sem sleeps, permitindo concluir a animação semântica de rolagem. */
    private fun nextFrame(scene: ImageComposeScene): org.jetbrains.skia.Image {
        frameTime += 100_000_000L
        return scene.render(frameTime)
    }

    /** Exporta evidência visual descartável; PNGs não são golden files nem entram no Git. */
    private fun save(scene: ImageComposeScene, name: String) {
        val directory = File("build/login-ui").apply { mkdirs() }
        nextFrame(scene).use { image ->
            image.encodeToData()!!.use { File(directory, "$name.png").writeBytes(it.bytes) }
        }
    }
}
