package com.godrivesync

import com.godrivesync.data.db.initializeDatabase
import org.h2.api.ErrorCode
import org.jetbrains.exposed.v1.jdbc.Database
import java.awt.GraphicsEnvironment
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Título compartilhado do aviso anterior à criação da janela Compose. */
internal const val DATABASE_STARTUP_TITLE = "Não foi possível abrir o Go Drive Sync"

/**
 * Mensagens locais da inicialização; não incorporam SQL, caminhos, credenciais ou texto de exceções.
 * Banco ocupado é uma condição identificável; as demais falhas não permitem inferir uma causa única.
 */
internal enum class DatabaseStartupFailure(val message: String) {
    IN_USE(
        "O banco de dados está sendo usado por outro programa\n" +
            "ou por outra instância do Go Drive Sync.\n\n" +
            "Feche a outra instância ou desconecte ferramentas\n" +
            "como o DBeaver e tente novamente.",
    ),
    INITIALIZATION(
        "Não foi possível inicializar o banco de dados local.\n\n" +
            "Pode haver um problema de acesso ao arquivo ou de\n" +
            "validação/atualização do banco. Se o erro persistir,\n" +
            "procure suporte para verificar a instalação.\n\n" +
            "Não apague o banco: ele contém suas contas e configurações.",
    ),
}

/**
 * Inicializa o H2 antes do Compose e relata falhas recuperáveis da inicialização.
 * Retorna null após o aviso para que Main encerre com erro, sem criar serviços ou janela principal.
 * As funções injetáveis permitem testar o fluxo sem abrir diálogos nem acessar o banco pessoal.
 * Erros fatais da JVM não são capturados como se fossem falhas comuns de banco.
 */
internal fun initializeDesktopDatabase(
    initialize: () -> Database = { initializeDatabase() },
    report: (DatabaseStartupFailure) -> Unit = { showDatabaseStartupFailure(it) },
): Database? = try {
    initialize()
} catch (failure: Exception) {
    report(classifyDatabaseStartupFailure(failure))
    null
}

/**
 * Procura o código H2 90020 nas causas e na cadeia JDBC, inclusive sob wrappers do Flyway.
 * Usa identidade para evitar ciclos de exceções; nunca interpreta mensagens ou confunde outro SQLState.
 */
internal fun classifyDatabaseStartupFailure(failure: Throwable): DatabaseStartupFailure {
    val pending = ArrayDeque<Throwable>().apply { add(failure) }
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current is SQLException) {
            if (current.errorCode == ErrorCode.DATABASE_ALREADY_OPEN_1) return DatabaseStartupFailure.IN_USE
            current.nextException?.let(pending::addLast)
        }
        current.cause?.let(pending::addLast)
    }
    return DatabaseStartupFailure.INITIALIZATION
}

/**
 * Emite mensagem sanitizada no terminal e, quando há ambiente gráfico, aguarda confirmação na EDT.
 * Como Compose ainda não iniciou, Swing fornece um diálogo independente dos sete temas do aplicativo.
 * headless/present permitem verificar o fallback e a execução na EDT sem criar uma janela nos testes.
 */
internal fun showDatabaseStartupFailure(
    failure: DatabaseStartupFailure,
    headless: Boolean = GraphicsEnvironment.isHeadless(),
    present: (DatabaseStartupFailure) -> Unit = ::displayDatabaseStartupFailure,
) {
    System.err.println("$DATABASE_STARTUP_TITLE\n${failure.message}")
    if (headless) return
    try {
        // invokeAndWait aguarda OK/fechamento, sem encerrar o processo antes de o usuário ler o aviso.
        // Quando já estamos na EDT, chamada direta evita o deadlock proibido pelo Swing.
        if (SwingUtilities.isEventDispatchThread()) present(failure)
        else SwingUtilities.invokeAndWait { present(failure) }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (_: Exception) {
        // Se o ambiente gráfico falhar, a explicação já está no terminal e Main ainda pode encerrar.
        System.err.println("Não foi possível exibir o diálogo. Consulte a mensagem acima.")
    }
}

/** Cria o conteúdo Swing com texto em linhas limitadas e um único botão OK; chamar na EDT. */
internal fun createDatabaseStartupPane(failure: DatabaseStartupFailure): JOptionPane = JOptionPane(
    failure.message, JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null, arrayOf("OK"), "OK",
)

/** Mostra aviso modal centralizado e libera recursos tanto ao confirmar quanto ao fechar pelo X. */
private fun displayDatabaseStartupFailure(failure: DatabaseStartupFailure) {
    val dialog = createDatabaseStartupPane(failure).createDialog(null, DATABASE_STARTUP_TITLE)
    try { dialog.isVisible = true } finally { dialog.dispose() }
}
