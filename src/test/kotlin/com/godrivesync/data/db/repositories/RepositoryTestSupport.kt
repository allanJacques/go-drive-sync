package com.godrivesync.data.db.repositories

import com.godrivesync.data.db.initializeDatabase
import com.godrivesync.data.models.Account
import com.godrivesync.data.models.FolderMapping
import com.godrivesync.data.models.SyncHistoryEntry
import com.godrivesync.data.models.TransferDirection
import com.godrivesync.data.models.TransferStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/** Infraestrutura de integração com arquivos H2 isolados, migrados pelo mesmo Flyway do aplicativo. */
abstract class RepositoryTestSupport {
    /** Cada teste recebe um diretório descartável, sem acessar o banco real do usuário. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Data fixa com nanossegundos para detectar perda de precisão na conversão dos DTOs. */
    protected val date = LocalDateTime.of(2026, 11, 9, 12, 30, 15, 123456789)

    /** Abre ou reabre o arquivo [name] e desregistra o Exposed mesmo quando o teste falhar. */
    protected fun withDatabase(name: String = "repository", block: (Database) -> Unit) {
        val url = "jdbc:h2:file:${File(temporaryFolder.root, name).absolutePath}"
        val database = initializeDatabase(url)
        try {
            block(database)
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    /** Cria DTO de conta sem identidade; os textos de tokens são fictícios, nunca credenciais. */
    protected fun account(email: String = "teste@example.invalid"): Account = Account(
        email = email,
        accessToken = "cifra-ficticia-acesso",
        refreshToken = "cifra-ficticia-renovacao",
        tokenExpiry = date,
    )

    /** Descreve uma pasta fictícia, sem criar diretórios nem acessar o Drive. */
    protected fun mapping(accountId: Int): FolderMapping = FolderMapping(
        accountId = accountId,
        driveFolderId = "pasta-remota",
        driveFolderName = "Documentos",
        localFolderPath = "/tmp/documentos",
    )

    /** Fornece operação com arquivo maior que 2 GiB e vínculo opcional para testar o BIGINT e NULL. */
    protected fun entry(accountId: Int, mappingId: Int? = null): SyncHistoryEntry = SyncHistoryEntry(
        accountId = accountId,
        mappingId = mappingId,
        driveFileId = "arquivo-remoto",
        fileName = "backup.zip",
        filePath = "/tmp/documentos/backup.zip",
        direction = TransferDirection.DOWNLOAD,
        status = TransferStatus.SUCCESS,
        fileSize = 5_000_000_000L,
    )
}
