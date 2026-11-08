package com.godrivesync.data.db

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

/** URL relativa ao diretório de execução; o H2 acrescenta a extensão .mv.db ao arquivo. */
const val DEFAULT_DATABASE_URL = "jdbc:h2:file:./drive-sync-db"

/**
 * Aplica as migrações Flyway no H2 antes de disponibilizar o banco ao Exposed e à interface.
 *
 * [jdbcUrl] permite usar outro arquivo nos testes, sem tocar no banco do usuário.
 * Retorna o identificador Exposed que os futuros repositórios poderão passar a transaction.
 * Como em JDBC no Java, o acesso ao disco é bloqueante: chamar antes de iniciar a UI ou
 * em Dispatchers.IO, nunca no corpo de um composable nem na thread de eventos Swing.
 *
 * Bancos da primeira implementação são reconhecidos e adotados sem apagar registros.
 * Scripts pendentes são aplicados em ordem; scripts já aplicados têm checksum validado.
 * Uma falha de conexão, adoção ou migração é propagada e impede abrir a janela.
 */
fun initializeDatabase(jdbcUrl: String = DEFAULT_DATABASE_URL): Database {
    migrateDatabase(jdbcUrl)
    // Só registrar o Exposed após sucesso: não há configuração parcial a remover em falhas.
    return Database.connect(url = jdbcUrl, driver = "org.h2.Driver")
}

/**
 * Executa a política única de adoção, validação e migração, sem registrar o Exposed.
 * [migrationLocations] permite simular versões futuras nos testes sem empacotá-las no app.
 * Em produção, os scripts são lidos de resources/db/migration, inclusive dentro do JAR.
 */
internal fun migrateDatabase(
    jdbcUrl: String,
    migrationLocations: List<String> = listOf("classpath:db/migration"),
) {
    val flyway = Flyway.configure()
        .dataSource(jdbcUrl, "", "")
        .locations(*migrationLocations.toTypedArray())
        .defaultSchema("PUBLIC")
        // A adoção é explícita e validada; não marcar qualquer banco não vazio como versão 1.
        .baselineOnMigrate(false)
        .baselineVersion("1")
        .baselineDescription("Esquema legado da issue 4")
        .validateMigrationNaming(true)
        // Um app antigo não deve abrir um banco que já recebeu migrações de uma versão futura.
        .ignoreMigrationPatterns(*emptyArray<String>())
        .cleanDisabled(true)
        .load()

    adoptLegacyDatabase(flyway)
    flyway.migrate()
}
