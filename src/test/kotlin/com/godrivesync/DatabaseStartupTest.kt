package com.godrivesync

import com.godrivesync.data.db.initializeDatabase
import org.flywaydb.core.api.FlywayException
import org.h2.api.ErrorCode
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.sql.SQLException
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.test.*

/** Verifica falhas anteriores ao Compose com H2 temporário, cadeias JDBC e Swing sem janela nativa. */
class DatabaseStartupTest {
    /** Arquivos independentes; nenhuma conexão do usuário/DBeaver é fechada ou modificada. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Wrappers Flyway/JDBC mantêm classificação por código; palavras em mensagens não bastam. */
    @Test
    fun `reconhece banco ocupado em causas e encadeamento jdbc`() {
        val locked = SQLException("texto remoto que não será mostrado", "90020", ErrorCode.DATABASE_ALREADY_OPEN_1)
        assertEquals(DatabaseStartupFailure.IN_USE, classifyDatabaseStartupFailure(locked))
        assertEquals(DatabaseStartupFailure.IN_USE,
            classifyDatabaseStartupFailure(FlywayException("wrapper", IllegalStateException(locked))))
        val chained = SQLException("falha externa").apply { nextException = locked }
        assertEquals(DatabaseStartupFailure.IN_USE, classifyDatabaseStartupFailure(chained))
        assertEquals(DatabaseStartupFailure.INITIALIZATION,
            classifyDatabaseStartupFailure(SQLException("Database already in use", "90020", 50000)))
    }

    /** Corrupção/permissão/migração não são rotuladas como bloqueio; ciclos não causam loop infinito. */
    @Test(timeout = 5_000)
    fun `outras falhas e ciclos recebem mensagem distinta`() {
        val first = SQLException("credencial-ficticia-secreta")
        val second = IllegalStateException("caminho-privado", first)
        first.initCause(second)
        val failure = classifyDatabaseStartupFailure(first)
        assertEquals(DatabaseStartupFailure.INITIALIZATION, failure)
        assertFalse(failure.message.contains("secreta"))
        assertFalse(failure.message.contains("caminho-privado"))
        assertEquals(DatabaseStartupFailure.INITIALIZATION,
            classifyDatabaseStartupFailure(FlywayException("checksum inválido")))
    }

    /** O bloqueio do arquivo real provoca erro H2; após liberá-lo, o mesmo banco mantém os registros. */
    @Test
    fun `arquivo h2 bloqueado impede inicializacao e preserva dados`() {
        val path = temporaryFolder.root.toPath().resolve("startup")
        val url = "jdbc:h2:file:$path"
        val initial = initializeDatabase(url)
        TransactionManager.closeAndUnregister(initial)
        DriverManager.getConnection(url, "", "").use { connection ->
            connection.createStatement().use { it.executeUpdate("""
                INSERT INTO accounts (email, access_token, refresh_token, token_expiry)
                VALUES ('teste@example.invalid', 'cifra-ficticia', 'cifra-ficticia', CURRENT_TIMESTAMP)
            """.trimIndent()) }
        }
        val reported = mutableListOf<DatabaseStartupFailure>()
        // FileChannel usa o mesmo bloqueio exclusivo que impede outro processo de abrir o H2.
        FileChannel.open(path.resolveSibling("startup.mv.db"), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val result = initializeDesktopDatabase({ initializeDatabase(url) }, reported::add)
                assertNull(result)
                assertEquals(listOf(DatabaseStartupFailure.IN_USE), reported)
            }
        }
        val reopened = assertNotNull(initializeDesktopDatabase({ initializeDatabase(url) }, reported::add))
        try {
            assertEquals(1, reported.size) // Sucesso não mostra aviso nem bloqueia a continuação do Main.
            DriverManager.getConnection(url, "", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT email FROM accounts").use {
                        assertTrue(it.next())
                        assertEquals("teste@example.invalid", it.getString(1))
                    }
                }
            }
        } finally { TransactionManager.closeAndUnregister(reopened) }
    }

    /** Falha aguarda o relator e retorna null; erros fatais da JVM continuam propagados. */
    @Test
    fun `falha impede continuacao sem engolir erro fatal`() {
        var reports = 0
        val result = initializeDesktopDatabase({ throw IllegalStateException("segredo fictício") }) {
            reports++
            assertEquals(DatabaseStartupFailure.INITIALIZATION, it)
        }
        assertNull(result)
        assertEquals(1, reports)
        assertFailsWith<LinkageError> {
            initializeDesktopDatabase({ throw LinkageError("fatal") }) { reports++ }
        }
        assertEquals(1, reports)
    }

    /** Relator executa na EDT e aguarda seu retorno; chamadas que já vêm da EDT não causam deadlock. */
    @Test
    fun `dialogo usa edt tanto na entrada principal quanto na edt`() {
        var reports = 0
        val present: (DatabaseStartupFailure) -> Unit = {
            assertTrue(SwingUtilities.isEventDispatchThread())
            assertEquals(DatabaseStartupFailure.IN_USE, it)
            reports++
        }
        showDatabaseStartupFailure(DatabaseStartupFailure.IN_USE, headless = false, present = present)
        assertEquals(1, reports)
        SwingUtilities.invokeAndWait {
            showDatabaseStartupFailure(DatabaseStartupFailure.IN_USE, headless = false, present = present)
        }
        assertEquals(2, reports)
    }

    /** Headless não tenta abrir janela; falha do toolkit não impede que Main receba null e encerre. */
    @Test
    fun `terminal sem tela e falha do dialogo permitem encerramento`() {
        showDatabaseStartupFailure(DatabaseStartupFailure.IN_USE, headless = true) { error("não deve abrir") }
        val result = initializeDesktopDatabase({ throw IllegalStateException() }) { failure ->
            showDatabaseStartupFailure(failure, headless = false) { throw IllegalStateException("toolkit indisponível") }
        }
        assertNull(result)
    }

    /** Renderiza o painel real nos dois estados, verifica botão OK e grava PNGs descartáveis em build/. */
    @Test
    fun `painel de erro mostra mensagem e confirma com ok`() {
        SwingUtilities.invokeAndWait {
            for (failure in DatabaseStartupFailure.entries) {
                val pane = createDatabaseStartupPane(failure)
                assertEquals(failure.message, pane.message)
                assertEquals(JOptionPane.ERROR_MESSAGE, pane.messageType)
                pane.setSize(pane.preferredSize)
                layoutChildren(pane)
                assertTrue(pane.width <= 700 && pane.height <= 400)
                val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try { pane.printAll(graphics) } finally { graphics.dispose() }
                val directory = File("build/startup-ui").apply { mkdirs() }
                ImageIO.write(image, "png", File(directory, "${failure.name.lowercase()}.png"))
                val button = descendants(pane).filterIsInstance<JButton>().single()
                assertEquals("OK", button.text)
                button.doClick()
                assertEquals("OK", pane.value)
            }
        }
    }

    /** Calcula layout recursivamente como faria a janela, sem depender de DISPLAY no CI. */
    private fun layoutChildren(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutChildren)
    }

    /** Percorre componentes Swing para verificar a ação real criada pelo JOptionPane. */
    private fun descendants(container: Container): List<java.awt.Component> =
        container.components.toList() + container.components.filterIsInstance<Container>().flatMap(::descendants)
}
