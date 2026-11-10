package com.godrivesync.data.db.repositories

import com.godrivesync.data.db.tables.AccountsTable
import com.godrivesync.data.models.Account
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persiste contas usando a DSL Exposed e devolve DTOs já materializados, sem consultas pendentes.
 * [database] é o identificador retornado por initializeDatabase, nunca uma conexão global implícita.
 * As chamadas são bloqueantes: após abrir a UI, executá-las em Dispatchers.IO, como JDBC fora da EDT.
 * Tokens devem chegar criptografados; este CRUD não implementa OAuth nem gerenciamento de chaves.
 */
class AccountRepository(private val database: Database) {
    /** Coordena todas as instâncias no processo, inclusive se usarem identificadores distintos do mesmo H2. */
    private companion object {
        /** Mantido até o commit/rollback, também protege a inserção simultânea das primeiras contas. */
        val writeLock = ReentrantLock()
    }

    /**
     * Insere quando [account] tem id null; caso contrário atualiza o ID existente e preserva createdAt.
     * Não faz upsert por e-mail: duplicatas falham pela restrição UNIQUE do H2.
     * Retorna uma nova fotografia com ID e data preenchidos. ID inexistente causa IllegalArgumentException.
     * isDefault=true desmarca as demais contas na mesma transação; false pode deixar nenhuma padrão.
     * Falhas são propagadas e desfazem também a mudança da conta padrão.
     */
    fun save(account: Account): Account = writeTransaction {
        val id = account.id
        if (id != null) {
            require(AccountsTable.selectAll().where { AccountsTable.id eq id }.any()) {
                "Conta $id não encontrada para atualização."
            }
        }
        if (account.isDefault) {
            AccountsTable.update({ AccountsTable.isDefault eq true }) { it[isDefault] = false }
        }
        val savedId = if (id == null) {
            AccountsTable.insert {
                writeValues(it, account)
                account.createdAt?.let { date -> it[createdAt] = date }
            }[AccountsTable.id]
        } else {
            AccountsTable.update({ AccountsTable.id eq id }) { writeValues(it, account) }
            id
        }
        AccountsTable.selectAll().where { AccountsTable.id eq savedId }.single().toAccount()
    }

    /** Busca exata pelo e-mail, sem normalização; retorna null quando não há registro. */
    fun findByEmail(email: String): Account? = transaction(database) {
        AccountsTable.selectAll().where { AccountsTable.email eq email }.singleOrNull()?.toAccount()
    }

    /** Lista todas as contas por ID crescente; lista vazia é um resultado válido. */
    fun findAll(): List<Account> = transaction(database) {
        AccountsTable.selectAll().orderBy(AccountsTable.id).map { it.toAccount() }
    }

    /**
     * Escolhe [id] como a única conta padrão. Retorna false para ID ausente sem alterar a preferência.
     * As duas atualizações são atômicas: outros leitores não veem o estado intermediário.
     */
    fun setDefault(id: Int): Boolean = writeTransaction {
        if (AccountsTable.selectAll().where { AccountsTable.id eq id }.empty()) {
            return@writeTransaction false
        }
        AccountsTable.update({ AccountsTable.isDefault eq true }) { it[isDefault] = false }
        AccountsTable.update({ AccountsTable.id eq id }) { it[isDefault] = true }
        true
    }

    /**
     * Remove [id], retornando false se ausente. FKs propagam erro se houver mapeamentos ou histórico.
     * Não escolhe outra conta padrão automaticamente nem apaga registros dependentes.
     */
    fun delete(id: Int): Boolean = writeTransaction {
        AccountsTable.deleteWhere { AccountsTable.id eq id } > 0
    }

    /**
     * Serializa gravações da API no processo até a transação terminar, sem alterar o esquema existente.
     * Uma transação externa adiaria o commit para depois de liberar o lock; por isso é rejeitada antes
     * de adquirir o lock. Chamadores devem usar estas operações como unidades completas de trabalho.
     * A garantia não abrange SQL externo; o aplicativo usa um único processo H2 embarcado.
     */
    private fun <T> writeTransaction(block: JdbcTransaction.() -> T): T {
        check(TransactionManager.currentOrNull() == null) {
            "Gravações de contas devem ser chamadas fora de uma transação Exposed externa."
        }
        return writeLock.withLock { transaction(database) { block() } }
    }

    /** Preenche campos editáveis; identidade e data de criação não são alteradas no update. */
    private fun writeValues(statement: UpdateBuilder<*>, account: Account) {
        statement[AccountsTable.email] = account.email
        statement[AccountsTable.accessToken] = account.accessToken
        statement[AccountsTable.refreshToken] = account.refreshToken
        statement[AccountsTable.tokenExpiry] = account.tokenExpiry
        statement[AccountsTable.isDefault] = account.isDefault
        statement[AccountsTable.lastFullSync] = account.lastFullSync
    }

    /** Lê inclusive os campos TEXT dentro da transação, entregando somente valores Kotlin ao chamador. */
    private fun ResultRow.toAccount(): Account = Account(
        id = this[AccountsTable.id],
        email = this[AccountsTable.email],
        accessToken = this[AccountsTable.accessToken],
        refreshToken = this[AccountsTable.refreshToken],
        tokenExpiry = this[AccountsTable.tokenExpiry],
        isDefault = this[AccountsTable.isDefault],
        lastFullSync = this[AccountsTable.lastFullSync],
        createdAt = this[AccountsTable.createdAt],
    )
}
