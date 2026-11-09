package com.godrivesync.data.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Esquema do histórico de transferências, vinculado a uma conta e opcionalmente a um mapeamento.
 * A definição não executa sincronização nem registra operações por conta própria.
 * As FKs restringem exclusões para preservar o histórico; a política de limpeza será
 * implementada explicitamente pelos futuros repositórios.
 */
object SyncHistoryTable : Table("sync_history") {
    /** Identificador inteiro gerado para cada operação registrada. */
    val id = integer("id").autoIncrement()

    /** Conta da operação, obrigatoriamente existente. */
    val accountId = reference("account_id", AccountsTable.id, onDelete = ReferenceOption.RESTRICT)

    /** Operações sem mapeamento são válidas; um ID informado precisa existir. */
    val mappingId = reference("mapping_id", FolderMappingsTable.id, onDelete = ReferenceOption.RESTRICT).nullable()

    /** Identificador do arquivo remoto envolvido na operação. */
    val driveFileId = varchar("drive_file_id", 100)

    /** Nome exibido no histórico. */
    val fileName = varchar("file_name", 255)

    /** Caminho local associado à transferência. */
    val filePath = text("file_path")

    /** Direção textual prevista no plano: DOWNLOAD ou UPLOAD. */
    val direction = varchar("direction", 10)

    /** Resultado textual previsto no plano: SUCCESS, FAILED ou SKIPPED. */
    val status = varchar("status", 10)

    /** Tamanho em bytes usando Long/BIGINT, inclusive para arquivos maiores que 2 GiB. */
    val fileSize = long("file_size")

    /** Data/hora sem fuso preenchida pelo H2 quando omitida na inserção. */
    val timestamp = datetime("timestamp").defaultExpression(CurrentDateTime)

    /** Detalhes de uma falha; null quando não há mensagem de erro. */
    val errorMessage = text("error_message").nullable()

    /** Identidade própria permite várias operações para o mesmo arquivo. */
    override val primaryKey = PrimaryKey(id)
}
