package com.godrivesync.data.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Descreve as contas Google na DSL do Exposed; este object guarda metadados, não registros.
 * Diferente de uma entidade Java de ORM, cada propriedade representa uma coluna SQL.
 * Tokens deverão chegar criptografados pela futura camada de autenticação/persistência:
 * declarar uma coluna TEXT não criptografa seu conteúdo nem implementa login.
 */
object AccountsTable : Table("accounts") {
    /** Identificador inteiro gerado pelo H2 em cada inserção. */
    val id = integer("id").autoIncrement()

    /** E-mail obrigatório e único; o índice impede cadastrar o mesmo valor duas vezes. */
    val email = varchar("email", 255).uniqueIndex()

    /** Token de acesso já criptografado; a tabela não recebe tokens reais nesta etapa. */
    val accessToken = text("access_token")

    /** Token de renovação também deve ser criptografado antes da gravação. */
    val refreshToken = text("refresh_token")

    /** Expiração como LocalDateTime (data/hora sem fuso), correspondente ao DATETIME do plano. */
    val tokenExpiry = datetime("token_expiry")

    /** Indica a conta padrão; a regra de escolher uma única conta ficará no repositório. */
    val isDefault = bool("is_default").default(false)

    /** Ausente até a primeira sincronização completa bem-sucedida. */
    val lastFullSync = datetime("last_full_sync").nullable()

    /** Data/hora preenchida pelo banco quando a inserção omite este campo. */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    /** Chave primária explícita: usamos Table/DSL, sem classes de entidade DAO. */
    override val primaryKey = PrimaryKey(id)
}
