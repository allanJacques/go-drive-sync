package com.godrivesync.data.db

import org.flywaydb.core.Flyway
import org.h2.tools.RunScript
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Caminho imutável da versão que representa os bancos anteriores ao Flyway. */
private const val INITIAL_SCHEMA_RESOURCE = "/db/migration/V1__cria_tabelas_iniciais.sql"

/**
 * Adota um banco da issue #4 somente quando suas tabelas e restrições correspondem à V1.
 * Bancos vazios seguem para migrate; bancos com histórico são validados pelo próprio Flyway.
 * Lê apenas metadados do banco existente e só grava o baseline depois da comparação.
 * Não usa as Table atuais do Exposed como referência: elas mudarão nas próximas versões.
 * @throws IllegalStateException se o banco não vazio tiver estrutura desconhecida.
 */
internal fun adoptLegacyDatabase(flyway: Flyway) {
    val shouldBaseline = flyway.configuration.dataSource.connection.use { connection ->
        val tables = tableNames(connection)
        if (tables.isEmpty() || flyway.configuration.table in tables) {
            false
        } else {
            // Este H2 anônimo existe só nesta conexão e desaparece ao fechá-la. A V1
            // fornece a referência congelada, sem ler ou copiar tokens/dados do usuário.
            DriverManager.getConnection("jdbc:h2:mem:").use { reference ->
                val stream = checkNotNull(object {}.javaClass.getResourceAsStream(INITIAL_SCHEMA_RESOURCE)) {
                    "Migração inicial não encontrada no classpath: $INITIAL_SCHEMA_RESOURCE"
                }
                stream.bufferedReader(Charsets.UTF_8).use { RunScript.execute(reference, it) }
                check(schemaDefinition(connection) == schemaDefinition(reference)) {
                    "Banco sem histórico Flyway incompatível com a versão inicial. " +
                        "A adoção foi interrompida; confira a estrutura antes de migrar."
                }
            }
            true
        }
    }
    // baseline registra a versão 1 sem executar CREATE sobre as tabelas já existentes.
    // A conexão de inspeção já foi fechada; migrate aplicará somente versões posteriores.
    if (shouldBaseline) flyway.baseline()
}

/**
 * Retrato estrutural de uma tabela, independente dos nomes de constraints gerados pelo H2.
 * @property columns Tipos, tamanhos, precisão, nullabilidade, defaults e geração de valores.
 * @property primaryKey Colunas e ordem da chave primária.
 * @property foreignKeys Destinos, ordem e regras de atualização/exclusão das FKs.
 * @property uniqueIndexes Grupos ordenados de colunas únicas, incluindo a chave primária.
 * @property checks Expressões CHECK associadas à tabela; a V1 não contém nenhuma.
 */
private data class TableDefinition(
    val columns: Set<List<String?>>,
    val primaryKey: Set<List<String?>>,
    val foreignKeys: Set<List<String?>>,
    val uniqueIndexes: Set<List<List<String?>>>,
    val checks: Set<List<String?>>,
)

/** Lista tabelas e views de PUBLIC; nomes inesperados impedem adotar um banco diferente. */
private fun tableNames(connection: Connection): Set<String> =
    readRows(connection.metaData.getTables(null, "PUBLIC", "%", arrayOf("TABLE", "VIEW")), "TABLE_NAME")
        .map { checkNotNull(it.single()) }.toSet()

/** Extrai um retrato comparável de PUBLIC, sem consultar os registros das tabelas. */
private fun schemaDefinition(connection: Connection): Map<String, TableDefinition> =
    tableNames(connection).associateWith { table ->
        val metadata = connection.metaData
        // getColumns recebe padrões LIKE: escapamos '_' e '%' dos nomes para buscar só a tabela exata.
        val escape = metadata.searchStringEscape
        val pattern = table.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%")
        val indexes = readRows(
            metadata.getIndexInfo(null, "PUBLIC", table, true, false),
            "INDEX_NAME", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
        ).filter { it[0] != null }
        TableDefinition(
            columns = readRows(
                metadata.getColumns(null, "PUBLIC", pattern, "%"),
                "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NULLABLE",
                "COLUMN_DEF", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN",
            ).toSet(),
            primaryKey = readRows(metadata.getPrimaryKeys(null, "PUBLIC", table), "COLUMN_NAME", "KEY_SEQ").toSet(),
            foreignKeys = readRows(
                metadata.getImportedKeys(null, "PUBLIC", table),
                "FKCOLUMN_NAME", "KEY_SEQ", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "UPDATE_RULE", "DELETE_RULE",
            ).toSet(),
            // Ignoramos o nome do índice, mas preservamos grupos, ordem e direção das colunas.
            uniqueIndexes = indexes.groupBy { it[0] }.values
                .map { rows -> rows.sortedBy { it[1]?.toInt() }.map { it.drop(1) } }.toSet(),
            checks = checkConstraints(connection, table),
        )
    }

/** Lê expressões CHECK via catálogo H2, pois JDBC não fornece uma API de metadados para elas. */
private fun checkConstraints(connection: Connection, table: String): Set<List<String?>> =
    connection.prepareStatement(
        """
        SELECT c.CHECK_CLAUSE
        FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS c
        JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
          ON c.CONSTRAINT_CATALOG = t.CONSTRAINT_CATALOG
         AND c.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA
         AND c.CONSTRAINT_NAME = t.CONSTRAINT_NAME
        WHERE t.TABLE_SCHEMA = 'PUBLIC' AND t.TABLE_NAME = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, table)
        readRows(statement.executeQuery(), "CHECK_CLAUSE").toSet()
    }

/** Copia campos de metadados e fecha o ResultSet, como try-with-resources no Java. */
private fun readRows(result: ResultSet, vararg fields: String): List<List<String?>> = result.use {
    buildList {
        while (result.next()) add(fields.map { result.getString(it) })
    }
}
