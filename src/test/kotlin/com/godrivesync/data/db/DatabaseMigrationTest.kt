package com.godrivesync.data.db

import org.flywaydb.core.api.FlywayException
import org.h2.tools.RunScript
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercita instalação, adoção do esquema anterior e atualizações versionadas com H2 em disco.
 * Os scripts V2 ficam apenas no classpath de testes; nenhum campo fictício chega à distribuição.
 * Fixtures usam dados inventados e bancos isolados, sem ler o arquivo pessoal do aplicativo.
 */
class DatabaseMigrationTest {
    /** Diretório independente por teste, removido pelo JUnit ao terminar. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Confere que a V1 tem checksum e é registrada uma única vez entre inicializações. */
    @Test
    fun recordsInitialMigrationOnce() {
        val url = newDatabaseUrl()
        initializeAndClose(url)
        initializeAndClose(url)
        withConnection(url) { connection ->
            assertEquals(listOf(listOf("1", "SQL", "TRUE")), query(connection,
                """SELECT "version", "type", "success" FROM "flyway_schema_history" WHERE "version" IS NOT NULL"""))
            assertNotNull(query(connection, """SELECT "checksum" FROM "flyway_schema_history" WHERE "version" = '1'""").single().single())
        }
    }

    /** Adota o esquema real anterior ao Flyway sem recriar dados ou reiniciar identidades. */
    @Test
    fun adoptsLegacyDatabasePreservingRowsAndIdentity() {
        val url = newDatabaseUrl()
        createLegacyDatabase(url)
        initializeAndClose(url)
        initializeAndClose(url)
        withConnection(url) { connection ->
            assertEquals(listOf(listOf("1", "BASELINE")), query(connection,
                """SELECT "version", "type" FROM "flyway_schema_history" WHERE "version" IS NOT NULL"""))
            assertLegacyRows(connection)
            execute(connection, """
                INSERT INTO accounts (email, access_token, refresh_token, token_expiry)
                VALUES ('nova@example.invalid', 'ficticio', 'ficticio', CURRENT_TIMESTAMP)
            """.trimIndent())
            assertEquals("2", query(connection, "SELECT id FROM accounts WHERE email = 'nova@example.invalid'").single().single())
        }
    }

    /** Exige o esquema conhecido antes de gravar baseline, inclusive para restrições alteradas. */
    @Test
    fun rejectsUnknownOrAlteredLegacySchemaWithoutHistory() {
        val changes = listOf(
            "DROP TABLE sync_history",
            "ALTER TABLE accounts ALTER COLUMN email VARCHAR(100)",
            "ALTER TABLE accounts ALTER COLUMN access_token DROP NOT NULL",
            "ALTER TABLE folder_mappings ALTER COLUMN enabled SET DEFAULT FALSE",
            "ALTER TABLE accounts DROP CONSTRAINT accounts_email_unique",
            "ALTER TABLE sync_history DROP CONSTRAINT fk_sync_history_account_id__id",
            "ALTER TABLE accounts ADD CONSTRAINT email_extra CHECK (LENGTH(email) > 2)",
            "CREATE TABLE unrelated (id INTEGER)",
        )
        // Cada alteração parte da mesma fixture congelada; assim não há falhas em cascata.
        for (change in changes) {
            val url = newDatabaseUrl()
            createLegacyDatabase(url)
            withConnection(url) { execute(it, change) }
            val failure = assertFailsWith<IllegalStateException>(change) { initializeDatabase(url) }
            assertTrue(failure.message.orEmpty().contains("incompatível"))
            withConnection(url) { connection ->
                assertEquals("0", query(connection, """
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'flyway_schema_history'
                """.trimIndent()).single().single())
                assertEquals("legado@example.invalid", query(connection, "SELECT email FROM accounts").single().single())
            }
        }
    }

    /** Aplica uma V2 hipotética após V1 ou baseline, preservando dados e evitando repetição. */
    @Test
    fun appliesNewColumnOnceToFreshAndLegacyDatabases() {
        for (legacy in listOf(false, true)) {
            val url = newDatabaseUrl()
            if (legacy) createLegacyDatabase(url) else {
                initializeAndClose(url)
                withConnection(url, ::insertLegacyRows)
            }
            val locations = listOf("classpath:db/migration", "classpath:db/test-upgrade")
            migrateDatabase(url, locations)
            migrateDatabase(url, locations)
            withConnection(url) { connection ->
                assertLegacyRows(connection)
                assertEquals(listOf(listOf(null)), query(connection, "SELECT display_name FROM accounts"))
                assertEquals("1", query(connection,
                    """SELECT COUNT(*) FROM "flyway_schema_history" WHERE "version" = '2' AND "success" = TRUE""").single().single())
            }
            // O executável antigo só conhece V1 e deve recusar o arquivo agora na versão 2.
            assertFailsWith<FlywayException> { initializeDatabase(url) }
        }
    }

    /** Detecta alteração de migração aplicada em vez de reparar/aceitar o checksum silenciosamente. */
    @Test
    fun rejectsChangedChecksum() {
        val url = newDatabaseUrl()
        initializeAndClose(url)
        withConnection(url) { execute(it,
            """UPDATE "flyway_schema_history" SET "checksum" = "checksum" + 1 WHERE "version" = '1'""") }
        assertFailsWith<FlywayException> { initializeDatabase(url) }
    }

    /** Uma falha de SQL impede inicialização e continua exigindo correção nas próximas tentativas. */
    @Test
    fun refusesToContinueAfterPartialMigrationFailure() {
        val url = newDatabaseUrl()
        initializeAndClose(url)
        withConnection(url, ::insertLegacyRows)
        assertFailsWith<FlywayException> {
            migrateDatabase(url, listOf("classpath:db/migration", "classpath:db/test-failure"))
        }
        withConnection(url, ::assertLegacyRows)
        assertFailsWith<FlywayException> { initializeDatabase(url) }
    }

    /** Usa a inicialização pública e remove o registro Exposed para isolar cada etapa do teste. */
    private fun initializeAndClose(url: String) {
        TransactionManager.closeAndUnregister(initializeDatabase(url))
    }

    /** Gera um arquivo diferente a cada chamada, inclusive dentro dos testes com várias situações. */
    private fun newDatabaseUrl(): String =
        "jdbc:h2:file:${File(temporaryFolder.newFolder(), "drive-sync-db").absolutePath}"

    /** Reconstrói a fixture SQL do commit anterior ao Flyway, independente das Table atuais. */
    private fun createLegacyDatabase(url: String) = withConnection(url) { connection ->
        val stream = checkNotNull(javaClass.getResourceAsStream("/db/legacy/issue4.sql"))
        stream.bufferedReader(Charsets.UTF_8).use { RunScript.execute(connection, it) }
        insertLegacyRows(connection)
    }

    /** Insere relações e valores fictícios suficientes para detectar perda de dados entre versões. */
    private fun insertLegacyRows(connection: Connection) {
        execute(connection, """
            INSERT INTO accounts (email, access_token, refresh_token, token_expiry)
            VALUES ('legado@example.invalid', 'token-ficticio', 'refresh-ficticio', TIMESTAMP '2026-11-08 12:30:00');
            INSERT INTO folder_mappings (account_id, drive_folder_id, drive_folder_name, local_folder_path)
            VALUES (1, 'pasta-ficticia', 'Documentos', '/tmp/documentos');
            INSERT INTO sync_history (account_id, mapping_id, drive_file_id, file_name, file_path, direction, status, file_size)
            VALUES (1, 1, 'arquivo-ficticio', 'backup.zip', '/tmp/documentos/backup.zip', 'DOWNLOAD', 'SUCCESS', 5000000000);
        """.trimIndent())
    }

    /** Confere dados das três tabelas, inclusive relacionamentos e tamanho acima de 2 GiB. */
    private fun assertLegacyRows(connection: Connection) {
        assertEquals(listOf(listOf("legado@example.invalid", "token-ficticio", "refresh-ficticio")),
            query(connection, "SELECT email, access_token, refresh_token FROM accounts"))
        assertEquals(listOf(listOf("1", "Documentos", "/tmp/documentos")),
            query(connection, "SELECT account_id, drive_folder_name, local_folder_path FROM folder_mappings"))
        assertEquals(listOf(listOf("1", "1", "backup.zip", "5000000000")),
            query(connection, "SELECT account_id, mapping_id, file_name, file_size FROM sync_history"))
    }

    /** Abre e fecha JDBC explicitamente; a conexão não é compartilhada entre testes. */
    private fun withConnection(url: String, block: (Connection) -> Unit) {
        DriverManager.getConnection(url, "", "").use(block)
    }

    /** Executa SQL de preparação usando o parser H2, inclusive quando há vários comandos. */
    private fun execute(connection: Connection, sql: String) {
        sql.reader().use { RunScript.execute(connection, it) }
    }

    /** Materializa resultados antes de fechar recursos JDBC, mantendo os NULLs originais. */
    private fun query(connection: Connection, sql: String): List<List<String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                }
            }
        }
}
