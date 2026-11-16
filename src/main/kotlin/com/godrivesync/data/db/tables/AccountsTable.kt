package com.godrivesync.data.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Descreve as contas Google na DSL do Exposed; este object guarda metadados, não registros.
 * Diferente de uma entidade Java de ORM, cada propriedade representa uma coluna SQL.
 * Tokens chegam criptografados pelo AccountAuthorizationService:
 * declarar uma coluna TEXT não criptografa seu conteúdo nem implementa login.
 */
object AccountsTable : Table("accounts") {
    /** Identificador inteiro gerado pelo H2 em cada inserção. */
    val id = integer("id").autoIncrement()

    /** E-mail obrigatório e único; o índice impede cadastrar o mesmo valor duas vezes. */
    val email = varchar("email", 255).uniqueIndex()

    /** Token de acesso já criptografado; nunca enviar token em texto puro ao repositório. */
    val accessToken = text("access_token")

    /** Token de renovação também deve ser criptografado antes da gravação. */
    val refreshToken = text("refresh_token")

    /** Expiração como LocalDateTime interpretado em UTC pela autenticação; SQL não armazena o fuso. */
    val tokenExpiry = datetime("token_expiry")

    /** Indica a conta padrão; AccountRepository coordena a escolha de no máximo uma conta. */
    val isDefault = bool("is_default").default(false)

    /** Ausente até a primeira sincronização completa bem-sucedida. */
    val lastFullSync = datetime("last_full_sync").nullable()

    /** Data/hora preenchida pelo banco quando a inserção omite este campo. */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    /** Chave primária explícita: usamos Table/DSL, sem classes de entidade DAO. */
    override val primaryKey = PrimaryKey(id)
}
