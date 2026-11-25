package com.godrivesync.drive.sync

import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.drive.api.DriveApiClient
import com.godrivesync.drive.api.DriveChange
import com.godrivesync.drive.api.invalidChangesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordena uma rodada de leitura incremental para a conta de [client]. Compartilhe uma instância
 * por conta para serializar também as operações do consumidor. O H2 guarda a posição entre rodadas.
 * Cada página é lida fora de uma transação do tracker. O callback recebe a página já
 * materializada item a item, depois que o cliente liberou o lock de credenciais.
 */
class DriveChangeTracker(private val client: DriveApiClient, private val accounts: AccountRepository) {
    /** Mantém varredura, consumo e confirmação de uma rodada em ordem mesmo com chamadas concorrentes. */
    private val pollLock = Mutex()

    /**
     * Executa [initialScan] se faltar a posição inicial. O callback deve concluir a varredura de todos
     * os mapeamentos abrangidos e persistir seu inventário antes de retornar. Em seguida, lê o que
     * mudou desde o início dessa varredura, inclusive se ela tiver sido confirmada em outra execução.
     *
     * [process] recebe cada mudança em ordem e deve concluir seu efeito durável antes de retornar.
     * O processamento precisa ser idempotente: uma falha antes da confirmação final repete o lote.
     * A corrotina usa IO para poupar a thread gráfica. Erros e cancelamento chegam ao chamador.
     */
    suspend fun poll(initialScan: suspend () -> Unit, process: suspend (DriveChange) -> Unit) = pollLock.withLock {
        withContext(Dispatchers.IO) {
            val start = accounts.changesPageToken(client.accountId) ?: client.getStartPageToken().also { token ->
                initialScan()
                confirm(null, token)
            }
            val visited = mutableSetOf(start)
            var token = start
            while (true) {
                val page = client.getChanges(token)
                val next = page.nextPageToken
                if (next != null && !visited.add(next)) throw invalidChangesResponse()
                for (change in page.changes) {
                    currentCoroutineContext().ensureActive()
                    process(change)
                }
                if (next == null) {
                    confirm(start, checkNotNull(page.newStartPageToken))
                    break
                }
                token = next
            }
        }
    }

    /** Confirma a posição após o callback, se a conta e a posição anterior ainda corresponderem. */
    private suspend fun confirm(expected: String?, next: String) {
        currentCoroutineContext().ensureActive()
        check(accounts.advanceChangesPageToken(client.accountId, expected, next)) {
            "A conta foi removida ou sua posição de mudanças já foi atualizada."
        }
    }
}
