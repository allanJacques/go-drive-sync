package com.godrivesync.ui.screens.mappings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.godrivesync.data.models.FolderMapping
import com.godrivesync.data.models.SyncDirection
import com.godrivesync.ui.theme.AppStyle
import com.godrivesync.ui.theme.GoDriveSyncTheme
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.*

/** Exercita botões e diálogos pelo Compose raster nos sete temas e nos dois tamanhos de painel do App. */
class MappingsScreenTest {
    /** Renderiza cada estado e aciona os controles sem banco, arquivos reais ou conta do Drive. */
    @Test
    fun `estados e acoes identificam o vinculo correto`() {
        SwingUtilities.invokeAndWait {
            val first = FolderMapping(5, "pasta", "Projetos", "/tmp/projetos", id = 12)
            val second = first.copy(id = 13, driveFolderName = "Fotos", syncDirection = SyncDirection.DOWNLOAD_ONLY)
            for (style in AppStyle.entries) for ((width, height) in listOf(604 to 456, 824 to 616)) {
                val state = mutableStateOf(MappingsState(accountId = 5))
                var sync: Pair<Int, Int>? = null
                val available = mutableStateOf(false)
                var retries = 0
                var saves = 0
                var removals = 0
                val ready = MappingsState(5, listOf(first, second))
                val scene = ImageComposeScene(width, height) {
                    GoDriveSyncTheme(style) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MappingsScreen(state.value, { retries++ }, { state.value = state.value.copy(dialog = it) },
                                { saves++ }, { removals++ }, if (available.value) { account, id -> sync = account to id } else null)
                        }
                    }
                }
                var frame = 0L
                /** Compõe também os popups e encontra o último botão ou texto com o rótulo solicitado. */
                fun node(label: String): SemanticsNode {
                    repeat(3) { frame += 100_000_000L; scene.render(frame).close() }
                    return scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.last {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
                    }
                }
                /** Invoca o mesmo callback semântico que o botão oferece à acessibilidade. */
                fun click(label: String) { node(label).config[SemanticsActions.OnClick].action!!.invoke() }
                try {
                    node("Carregando mapeamentos…")
                    state.value = MappingsState(error = "Erro de leitura")
                    click("Tentar novamente")
                    assertEquals(1, retries)
                    state.value = ready.copy(items = emptyList())
                    node("Nenhum mapeamento cadastrado para esta conta.")
                    click("+ Novo mapeamento")
                    node("Novo mapeamento")
                    assertTrue(node("Salvar").boundsInRoot.height > 0f)
                    click("Cancelar")
                    assertNull(state.value.dialog)
                    state.value = ready
                    node("↕ Bidirecional")
                    node("↓ Somente download")
                    assertTrue(node("Sincronizar agora").config.contains(SemanticsProperties.Disabled))
                    available.value = true
                    click("Sincronizar agora")
                    assertEquals(5 to 13, sync)
                    click("Editar")
                    assertEquals(MappingDialog.Form(second), state.value.dialog)
                    click("Salvar")
                    assertEquals(1, saves)
                    click("Cancelar")
                    click("✕ Remover")
                    assertEquals(MappingDialog.Remove(second), state.value.dialog)
                    node("Remover mapeamento?")
                    assertEquals(0, removals)
                    click("Remover")
                    assertEquals(1, removals)
                } finally {
                    scene.close()
                }
            }
        }
    }

    /** Percorre a árvore de semântica incluindo os descendentes de cada raiz de diálogo. */
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
