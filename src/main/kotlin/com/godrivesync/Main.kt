package com.godrivesync

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image
import java.awt.Dimension
import com.godrivesync.data.preferences.AppearancePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ICON_RESOURCE = "/icons/app-icon.png"

/**
 * Inicia o aplicativo desktop: preferências → estado observável → janela → interface.
 * A função application cria o ciclo de vida Compose; Window corresponde à janela que,
 * em Swing, normalmente seria um JFrame. O conteúdo é descrito por funções composable.
 */
fun main() {
    val preferences = AppearancePreferences()
    // Leitura antes de abrir a janela: evita exibir Aurora por um instante antes do tema salvo.
    val initialStyle = preferences.loadStyle()
    application {
        // remember preserva o resultado entre recomposições: decodificamos o PNG uma vez.
        val icon = remember { loadWindowIcon() }
        // mutableStateOf avisa o Compose quando o valor muda; by permite ler/escrever
        // como uma variável comum. remember sozinho não salva nada entre execuções.
        var style by remember { mutableStateOf(initialStyle) }
        var themeSaveFailed by remember { mutableStateOf(false) }
        // Incrementar este contador permite repetir uma gravação mesmo sem mudar o tema.
        var saveRevision by remember { mutableIntStateOf(0) }
        // Unit é uma chave constante: o efeito começa uma vez nesta composição e é
        // cancelado quando ela sai. I/O não deve ocorrer diretamente ao desenhar a tela.
        LaunchedEffect(Unit) {
            // snapshotFlow observa os estados lidos no bloco e emite pares distintos.
            // collect processa as gravações em sequência; mudanças rápidas podem ser
            // agrupadas, mas a escolha mais recente será observada pelo coletor ativo.
            snapshotFlow { style to saveRevision }.collect { (selected, _) ->
                themeSaveFailed = try {
                    // Como em SwingWorker, o trabalho de disco sai da thread da interface.
                    // Ao retornar, atualizamos o estado que controla a mensagem de erro.
                    withContext(Dispatchers.IO) { preferences.saveStyle(selected) }
                    false
                } catch (cancelled: CancellationException) {
                    // Cancelamento é parte do ciclo de vida, não uma falha ao salvar.
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Go Drive Sync",
            icon = icon,
            state = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                size = DpSize(1120.dp, 800.dp),
            ),
        ) {
            // Esta propriedade pertence à janela AWT subjacente, não ao layout Compose.
            LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
            // O estado fica no pai; as funções passadas abaixo são callbacks dos filhos.
            App(style, { style = it }, icon, themeSaveFailed, { saveRevision++ })
        }
    }
}

/**
 * Lê o PNG do classpath (também funciona dentro do JAR) e o converte em Painter.
 * Painter é a abstração de desenho aceita por Image e pelo ícone da janela.
 * @return null quando o recurso falta ou é inválido; a janela ainda pode abrir.
 */
fun loadWindowIcon(): Painter? {
    val stream = object {}::class.java.getResourceAsStream(ICON_RESOURCE) ?: return null
    return try {
        // use fecha o stream mesmo se a leitura falhar, como try-with-resources em Java.
        val bytes = stream.use { it.readAllBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    } catch (_: Exception) {
        null
    }
}
