package com.godrivesync.data.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Esquema dos vínculos entre pastas do Drive e caminhos locais de uma conta.
 * As colunas são metadados compartilhados; operações futuras usarão transaction.
 * A FK impede contas inexistentes e restringe exclusão de contas ainda referenciadas,
 * evitando remover mapeamentos implicitamente em cascata.
 */
object FolderMappingsTable : Table("folder_mappings") {
    /** Identificador do mapeamento, gerado pelo H2. */
    val id = integer("id").autoIncrement()

    /** Conta proprietária obrigatória; sua remoção exige tratar os dependentes primeiro. */
    val accountId = reference("account_id", AccountsTable.id, onDelete = ReferenceOption.RESTRICT)

    /** Identificador estável da pasta na API do Google Drive. */
    val driveFolderId = varchar("drive_folder_id", 100)

    /** Nome de exibição; renomear uma pasta não altera seu identificador remoto. */
    val driveFolderName = varchar("drive_folder_name", 255)

    /** Caminho absoluto local; TEXT evita limitar caminhos ao tamanho de um VARCHAR curto. */
    val localFolderPath = text("local_folder_path")

    /** Valor textual do plano: BIDIRECTIONAL ou DOWNLOAD_ONLY; padrão é bidirecional. */
    val syncDirection = varchar("sync_direction", 20).default("BIDIRECTIONAL")

    /** Data/hora sem fuso da última sincronização, ou null enquanto nunca executada. */
    val lastSync = datetime("last_sync").nullable()

    /** Mapeamentos novos começam ativos; false representa uma pausa. */
    val enabled = bool("enabled").default(true)

    /** Garante identidade única mesmo para mapeamentos com nomes iguais. */
    override val primaryKey = PrimaryKey(id)
}
