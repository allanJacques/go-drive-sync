package com.godrivesync.data.db

import com.godrivesync.data.db.tables.AccountsTable
import com.godrivesync.data.db.tables.FolderMappingsTable
import com.godrivesync.data.db.tables.SyncHistoryTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testes de integração do contrato de persistência com H2 real, em arquivos temporários.
 * Exercitam SQL, restrições e reabertura; não dependem de Compose nem de uma conta Google.
 * Todos os tokens usados aqui são textos fictícios, sem qualquer credencial real.
 */
class DatabaseTest {
    /** JUnit cria e remove um diretório por teste, isolando o banco do usuário. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Data fixa sem frações de segundo para conferir o mapeamento java.time ↔ SQL. */
    private val expiry = LocalDateTime.of(2026, 11, 8, 12, 30)

    /** Verifica arquivo, IDs gerados, defaults SQL e preservação dos dados após reinicializar. */
    @Test
    fun createsFileAndPreservesDataOnReinitialization() {
        val databaseFile = File(temporaryFolder.root, "drive-sync-db")
        val url = "jdbc:h2:file:${databaseFile.absolutePath}"
        withDatabase(url) { database ->
            transaction(database) {
                assertEquals(0L, AccountsTable.selectAll().count())
                assertEquals(0L, FolderMappingsTable.selectAll().count())
                assertEquals(0L, SyncHistoryTable.selectAll().count())
                val accountId = insertAccount()
                val mappingId = insertMapping(accountId)
                insertHistory(accountId, mappingId)
            }
        }
        assertTrue(File("${databaseFile.absolutePath}.mv.db").isFile)

        // A segunda chamada repete SchemaUtils.create sobre o mesmo arquivo fechado.
        // Ler os registros comprova que a inicialização é idempotente e não recria o banco.
        withDatabase(url) { database ->
            transaction(database) {
                val account = AccountsTable.selectAll().single()
                val mapping = FolderMappingsTable.selectAll().single()
                val history = SyncHistoryTable.selectAll().single()
                assertTrue(account[AccountsTable.id] > 0)
                assertEquals("teste@example.invalid", account[AccountsTable.email])
                assertEquals(expiry, account[AccountsTable.tokenExpiry])
                assertFalse(account[AccountsTable.isDefault])
                assertNull(account[AccountsTable.lastFullSync])
                assertNotNull(account[AccountsTable.createdAt])
                assertTrue(mapping[FolderMappingsTable.id] > 0)
                assertEquals(account[AccountsTable.id], mapping[FolderMappingsTable.accountId])
                assertEquals("BIDIRECTIONAL", mapping[FolderMappingsTable.syncDirection])
                assertTrue(mapping[FolderMappingsTable.enabled])
                assertNull(mapping[FolderMappingsTable.lastSync])
                assertTrue(history[SyncHistoryTable.id] > 0)
                assertEquals(account[AccountsTable.id], history[SyncHistoryTable.accountId])
                assertEquals(mapping[FolderMappingsTable.id], history[SyncHistoryTable.mappingId])
                assertEquals(5_000_000_000L, history[SyncHistoryTable.fileSize])
                assertNotNull(history[SyncHistoryTable.timestamp])
                assertNull(history[SyncHistoryTable.errorMessage])
            }
        }
    }

    /** Confere campos opcionais preenchidos, pausa e histórico sem vínculo com mapeamento. */
    @Test
    fun storesDatesAndHistoryWithoutMapping() = withTemporaryDatabase { database, _ ->
        transaction(database) {
            val accountId = AccountsTable.insert {
                it[email] = "outra@example.invalid"
                it[accessToken] = "token-ficticio"
                it[refreshToken] = "refresh-ficticio"
                it[tokenExpiry] = expiry
                it[lastFullSync] = expiry.minusDays(1)
                it[isDefault] = true
            }[AccountsTable.id]
            FolderMappingsTable.insert {
                it[FolderMappingsTable.accountId] = accountId
                it[driveFolderId] = "pasta-remota"
                it[driveFolderName] = "Documentos"
                it[localFolderPath] = "/tmp/documentos"
                it[syncDirection] = "DOWNLOAD_ONLY"
                it[lastSync] = expiry.minusHours(1)
                it[enabled] = false
            }
            insertHistory(accountId, mappingId = null, error = "Falha fictícia de rede")
            val account = AccountsTable.selectAll().single()
            val mapping = FolderMappingsTable.selectAll().single()
            val history = SyncHistoryTable.selectAll().single()
            assertEquals(expiry.minusDays(1), account[AccountsTable.lastFullSync])
            assertTrue(account[AccountsTable.isDefault])
            assertEquals(expiry.minusHours(1), mapping[FolderMappingsTable.lastSync])
            assertEquals("DOWNLOAD_ONLY", mapping[FolderMappingsTable.syncDirection])
            assertFalse(mapping[FolderMappingsTable.enabled])
            assertNull(history[SyncHistoryTable.mappingId])
            assertEquals("FAILED", history[SyncHistoryTable.status])
            assertEquals("Falha fictícia de rede", history[SyncHistoryTable.errorMessage])
        }
    }

    /** Usa JDBC direto para provar que unicidade e NOT NULL são garantidos pelo próprio banco. */
    @Test
    fun rejectsDuplicateEmailAndMissingRequiredFields() = withTemporaryDatabase { database, url ->
        transaction(database) { insertAccount() }
        assertSqlState(url, "23505", """
            INSERT INTO accounts (email, access_token, refresh_token, token_expiry)
            VALUES ('teste@example.invalid', 'ficticio', 'ficticio', CURRENT_TIMESTAMP)
        """.trimIndent())
        assertSqlState(url, "23502", """
            INSERT INTO accounts (email, refresh_token, token_expiry)
            VALUES ('incompleta@example.invalid', 'ficticio', CURRENT_TIMESTAMP)
        """.trimIndent())
    }

    /** Verifica as três FKs e impede exclusões que deixariam mapeamentos/histórico órfãos. */
    @Test
    fun enforcesForeignKeysAndRestrictsDeletion() = withTemporaryDatabase { database, url ->
        val (accountId, mappingId) = transaction(database) {
            val accountId = insertAccount()
            val mappingId = insertMapping(accountId)
            insertHistory(accountId, mappingId)
            accountId to mappingId
        }
        assertSqlState(url, "23506", "UPDATE folder_mappings SET account_id = -1 WHERE id = $mappingId")
        assertSqlState(url, "23506", "UPDATE sync_history SET account_id = -1")
        assertSqlState(url, "23506", "UPDATE sync_history SET mapping_id = -1")
        assertSqlState(url, "23503", "DELETE FROM accounts WHERE id = $accountId")
        assertSqlState(url, "23503", "DELETE FROM folder_mappings WHERE id = $mappingId")
    }

    /** Insere uma conta fictícia na transação atual e retorna seu ID gerado pelo H2. */
    private fun insertAccount(): Int = AccountsTable.insert {
        it[email] = "teste@example.invalid"
        it[accessToken] = "token-ficticio"
        it[refreshToken] = "refresh-ficticio"
        it[tokenExpiry] = expiry
    }[AccountsTable.id]

    /** Cria um mapeamento de exemplo na transação atual, omitindo os campos com defaults. */
    private fun insertMapping(accountId: Int): Int = FolderMappingsTable.insert {
        it[FolderMappingsTable.accountId] = accountId
        it[driveFolderId] = "pasta-remota"
        it[driveFolderName] = "Documentos"
        it[localFolderPath] = "/tmp/documentos"
    }[FolderMappingsTable.id]

    /** Registra uma operação fictícia na transação atual; null exercita as colunas opcionais. */
    private fun insertHistory(accountId: Int, mappingId: Int?, error: String? = null) {
        SyncHistoryTable.insert {
            it[SyncHistoryTable.accountId] = accountId
            it[SyncHistoryTable.mappingId] = mappingId
            it[driveFileId] = "arquivo-remoto"
            it[fileName] = "backup.zip"
            it[filePath] = "/tmp/documentos/backup.zip"
            it[direction] = "DOWNLOAD"
            it[status] = if (error == null) "SUCCESS" else "FAILED"
            it[fileSize] = 5_000_000_000L
            it[errorMessage] = error
        }
    }

    /** Executa SQL inválido e compara o SQLState, evitando depender do texto traduzido do erro. */
    private fun assertSqlState(url: String, expected: String, sql: String) {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val failure = assertFailsWith<SQLException> { statement.executeUpdate(sql) }
                assertEquals(expected, failure.sqlState)
            }
        }
    }

    /** Abre um arquivo isolado para o teste e fornece também a URL para verificações via JDBC. */
    private fun withTemporaryDatabase(block: (Database, String) -> Unit) {
        val url = "jdbc:h2:file:${File(temporaryFolder.root, "drive-sync-db").absolutePath}"
        withDatabase(url) { block(it, url) }
    }

    /** Desregistra o banco mesmo após uma asserção falhar; transações fecham suas conexões JDBC. */
    private fun withDatabase(url: String, block: (Database) -> Unit) {
        val database = initializeDatabase(url)
        try {
            block(database)
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }
}
