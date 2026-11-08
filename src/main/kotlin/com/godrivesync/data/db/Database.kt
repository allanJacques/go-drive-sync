package com.godrivesync.data.db

import com.godrivesync.data.db.tables.AccountsTable
import com.godrivesync.data.db.tables.FolderMappingsTable
import com.godrivesync.data.db.tables.SyncHistoryTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** URL relativa ao diretório de execução; o H2 acrescenta a extensão .mv.db ao arquivo. */
const val DEFAULT_DATABASE_URL = "jdbc:h2:file:./drive-sync-db"

/**
 * Configura o H2 e cria as tabelas ausentes antes de disponibilizar o banco ao aplicativo.
 *
 * [jdbcUrl] permite usar outro arquivo nos testes, sem tocar no banco do usuário.
 * Retorna o identificador Exposed que os futuros repositórios poderão passar a transaction.
 * Como em JDBC no Java, o acesso ao disco é bloqueante: chamar antes de iniciar a UI ou
 * em Dispatchers.IO, nunca no corpo de um composable nem na thread de eventos Swing.
 *
 * A criação preserva registros existentes; não migra colunas de esquemas antigos.
 * Uma falha de conexão ou de criação é propagada e impede iniciar o app sem o esquema.
 */
fun initializeDatabase(jdbcUrl: String = DEFAULT_DATABASE_URL): Database {
    // connect registra a configuração; a primeira conexão física abre na transação abaixo.
    val database = Database.connect(url = jdbcUrl, driver = "org.h2.Driver")
    try {
        transaction(database) {
            // A ordem explicita as dependências: contas → mapeamentos → histórico.
            // SchemaUtils.create cria as tabelas ausentes e seus índices, sem apagar dados.
            SchemaUtils.create(AccountsTable, FolderMappingsTable, SyncHistoryTable)
        }
        return database
    } catch (failure: Exception) {
        // Uma inicialização incompleta não deve deixar um banco registrado no Exposed.
        TransactionManager.closeAndUnregister(database)
        throw failure
    }
}
