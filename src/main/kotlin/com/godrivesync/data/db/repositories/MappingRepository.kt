package com.godrivesync.data.db.repositories

import com.godrivesync.data.db.tables.FolderMappingsTable
import com.godrivesync.data.models.FolderMapping
import com.godrivesync.data.models.SyncDirection
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * CRUD bloqueante dos mapeamentos no [database] explícito, sem acessar Drive ou pastas locais.
 * Após abrir a UI, chamar em Dispatchers.IO. Resultados são valores independentes da transação.
 * A conta proprietária é imutável para preservar a coerência de registros históricos existentes.
 */
class MappingRepository(private val database: Database) {
    /** Insere [mapping] com ID null e devolve a fotografia persistida; a FK exige uma conta existente. */
    fun save(mapping: FolderMapping): FolderMapping = transaction(database) {
        require(mapping.id == null) { "Novo mapeamento deve ter ID null; use update para editar." }
        val id = FolderMappingsTable.insert {
            it[accountId] = mapping.accountId
            writeValues(it, mapping)
        }[FolderMappingsTable.id]
        FolderMappingsTable.selectAll().where { FolderMappingsTable.id eq id }.single().toMapping()
    }

    /** Lista apenas os vínculos de [accountId], inclusive pausados, ordenados por ID crescente. */
    fun findByAccountId(accountId: Int): List<FolderMapping> = transaction(database) {
        FolderMappingsTable.selectAll().where { FolderMappingsTable.accountId eq accountId }
            .orderBy(FolderMappingsTable.id).map { it.toMapping() }
    }

    /**
     * Atualiza campos editáveis de [mapping]; exige ID preenchido e retorna false se ele não existir.
     * Tentar trocar accountId causa IllegalArgumentException, sem modificar a linha.
     * O dono nunca é escrito no update, inclusive em chamadas concorrentes.
     */
    fun update(mapping: FolderMapping): Boolean = transaction(database) {
        val id = requireNotNull(mapping.id) { "Atualização exige o ID do mapeamento." }
        val existing = FolderMappingsTable.selectAll().where { FolderMappingsTable.id eq id }.singleOrNull()
            ?: return@transaction false
        require(existing[FolderMappingsTable.accountId] == mapping.accountId) {
            "Não é permitido trocar a conta de um mapeamento existente."
        }
        FolderMappingsTable.update({ FolderMappingsTable.id eq id }) { writeValues(it, mapping) } > 0
    }

    /** Remove [id] ou retorna false se ausente; histórico vinculado impede a exclusão pela FK RESTRICT. */
    fun delete(id: Int): Boolean = transaction(database) {
        FolderMappingsTable.deleteWhere { FolderMappingsTable.id eq id } > 0
    }

    /** Altera somente a pausa/ativação de [id]; retorna false se o mapeamento não existir. */
    fun setEnabled(id: Int, enabled: Boolean): Boolean = transaction(database) {
        FolderMappingsTable.update({ FolderMappingsTable.id eq id }) {
            it[FolderMappingsTable.enabled] = enabled
        } > 0
    }

    /** Compartilha campos editáveis entre inserção e atualização, mantendo identidade e dono separados. */
    private fun writeValues(statement: UpdateBuilder<*>, mapping: FolderMapping) {
        statement[FolderMappingsTable.driveFolderId] = mapping.driveFolderId
        statement[FolderMappingsTable.driveFolderName] = mapping.driveFolderName
        statement[FolderMappingsTable.localFolderPath] = mapping.localFolderPath
        statement[FolderMappingsTable.syncDirection] = mapping.syncDirection.name
        statement[FolderMappingsTable.lastSync] = mapping.lastSync
        statement[FolderMappingsTable.enabled] = mapping.enabled
    }

    /** Materializa a linha; uma direção desconhecida gravada por SQL externo falha explicitamente. */
    private fun ResultRow.toMapping(): FolderMapping = FolderMapping(
        id = this[FolderMappingsTable.id],
        accountId = this[FolderMappingsTable.accountId],
        driveFolderId = this[FolderMappingsTable.driveFolderId],
        driveFolderName = this[FolderMappingsTable.driveFolderName],
        localFolderPath = this[FolderMappingsTable.localFolderPath],
        syncDirection = SyncDirection.valueOf(this[FolderMappingsTable.syncDirection]),
        lastSync = this[FolderMappingsTable.lastSync],
        enabled = this[FolderMappingsTable.enabled],
    )
}
