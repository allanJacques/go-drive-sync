package com.godrivesync.data.db.repositories

import com.godrivesync.data.db.tables.FolderMappingsTable
import com.godrivesync.data.db.tables.SyncHistoryTable
import com.godrivesync.data.models.SyncHistoryEntry
import com.godrivesync.data.models.TransferDirection
import com.godrivesync.data.models.TransferStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDateTime

/**
 * Registra e consulta transferências no [database]; não executa uploads/downloads.
 * Chamadas bloqueantes devem sair da thread da UI. DTOs são materializados antes do fim da transação.
 * O histórico não tem update: correções e novas tentativas são novas entradas.
 */
class HistoryRepository(private val database: Database) {
    /**
     * Insere [entry] com ID null, devolvendo ID e timestamp preenchidos pelo banco.
     * Recusa tamanho negativo e vínculo com mapeamento ausente ou de outra conta.
     * A validação e a inserção são uma unidade atômica; FKs protegem contra remoções concorrentes.
     */
    fun insert(entry: SyncHistoryEntry): SyncHistoryEntry = transaction(database) {
        require(entry.id == null) { "Nova entrada de histórico deve ter ID null." }
        require(entry.fileSize >= 0) { "O tamanho do arquivo não pode ser negativo." }
        entry.mappingId?.let { mappingId ->
            val mapping = FolderMappingsTable.selectAll().where { FolderMappingsTable.id eq mappingId }.singleOrNull()
            require(mapping != null && mapping[FolderMappingsTable.accountId] == entry.accountId) {
                "O mapeamento deve existir e pertencer à conta do histórico."
            }
        }
        val id = SyncHistoryTable.insert {
            it[accountId] = entry.accountId
            it[mappingId] = entry.mappingId
            it[driveFileId] = entry.driveFileId
            it[fileName] = entry.fileName
            it[filePath] = entry.filePath
            it[direction] = entry.direction.name
            it[status] = entry.status.name
            it[fileSize] = entry.fileSize
            entry.timestamp?.let { date -> it[timestamp] = date }
            it[errorMessage] = entry.errorMessage
        }[SyncHistoryTable.id]
        SyncHistoryTable.selectAll().where { SyncHistoryTable.id eq id }.single().toHistoryEntry()
    }

    /** Lista o histórico de [accountId] do mais recente para o mais antigo; ID decrescente desempata datas. */
    fun findByAccountId(accountId: Int): List<SyncHistoryEntry> = transaction(database) {
        SyncHistoryTable.selectAll().where { SyncHistoryTable.accountId eq accountId }
            .orderBy(SyncHistoryTable.timestamp to SortOrder.DESC, SyncHistoryTable.id to SortOrder.DESC)
            .map { it.toHistoryEntry() }
    }

    /**
     * Filtra por [direction] e opcionalmente por [accountId]; null inclui todas as contas.
     * Retorna lista vazia quando não há correspondência, com a mesma ordenação de findByAccountId.
     */
    fun findByDirection(direction: TransferDirection, accountId: Int? = null): List<SyncHistoryEntry> =
        transaction(database) {
            val query = SyncHistoryTable.selectAll().where { SyncHistoryTable.direction eq direction.name }
            if (accountId != null) query.andWhere { SyncHistoryTable.accountId eq accountId }
            query.orderBy(SyncHistoryTable.timestamp to SortOrder.DESC, SyncHistoryTable.id to SortOrder.DESC)
                .map { it.toHistoryEntry() }
        }

    /**
     * Remove de todas as contas entradas estritamente anteriores a [cutoff] e retorna a quantidade.
     * A data limite é preservada; compara LocalDateTime sem conversão de fuso ou cálculo implícito de dias.
     */
    fun deleteOlderThan(cutoff: LocalDateTime): Int = transaction(database) {
        SyncHistoryTable.deleteWhere { timestamp less cutoff }
    }

    /** Converte a linha dentro da transação; enums desconhecidos no banco propagam erro de conversão. */
    private fun ResultRow.toHistoryEntry(): SyncHistoryEntry = SyncHistoryEntry(
        id = this[SyncHistoryTable.id],
        accountId = this[SyncHistoryTable.accountId],
        mappingId = this[SyncHistoryTable.mappingId],
        driveFileId = this[SyncHistoryTable.driveFileId],
        fileName = this[SyncHistoryTable.fileName],
        filePath = this[SyncHistoryTable.filePath],
        direction = TransferDirection.valueOf(this[SyncHistoryTable.direction]),
        status = TransferStatus.valueOf(this[SyncHistoryTable.status]),
        fileSize = this[SyncHistoryTable.fileSize],
        timestamp = this[SyncHistoryTable.timestamp],
        errorMessage = this[SyncHistoryTable.errorMessage],
    )
}
