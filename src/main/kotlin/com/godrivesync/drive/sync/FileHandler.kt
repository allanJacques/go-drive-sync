package com.godrivesync.drive.sync

import com.godrivesync.drive.api.DriveApiClient
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.coroutines.CoroutineContext

/**
 * Baixa arquivos binários com o [client] da conta. Cada chamada guarda seu progresso e
 * temporário separadamente. [waitBeforeRetry] recebe a espera em milissegundos e permite
 * exercitar o backoff nos testes. A decisão de qual versão sincronizar cabe ao chamador.
 */
class FileHandler internal constructor(
    private val client: DriveApiClient,
    private val waitBeforeRetry: suspend (Long) -> Unit,
) {
    /** Usa uma espera suspensa e cancelável entre tentativas, liberando a thread e o lock da conta. */
    constructor(client: DriveApiClient) : this(client, { delay(it) })

    /**
     * Baixa [fileId] para [destinationPath], criando diretórios pais ausentes. [onProgress] roda
     * em IO e recebe os bytes gravados na tentativa e o total conhecido (null se indisponível).
     * Começa em zero, inclusive para arquivos vazios. O retorno normal confirma a troca do destino.
     * Cancelar ou falhar antes da troca descarta a cópia parcial. Uma troca já concluída é definitiva.
     */
    suspend fun downloadFile(
        fileId: String,
        destinationPath: Path,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ) {
        require(fileId.isNotBlank()) { "Informe o ID do arquivo do Drive." }
        val destination = destinationPath.toAbsolutePath()
        val parent = requireNotNull(destination.parent) { "Informe um caminho de arquivo para o download." }
        withContext(Dispatchers.IO) {
            Files.createDirectories(parent)
            // O temporário no mesmo diretório permite publicar o arquivo inteiro em uma operação do SO.
            val temporary = Files.createTempFile(parent, ".go-drive-sync-", ".part")
            try {
                downloadToTemporary(fileId, temporary, onProgress)
                currentCoroutineContext().ensureActive()
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    /** Repete somente falhas transitórias. Cada tentativa relê os metadados e grava a partir do byte zero. */
    private suspend fun downloadToTemporary(fileId: String, temporary: Path, onProgress: (Long, Long?) -> Unit) {
        val context = currentCoroutineContext()
        var retryDelayMillis = 1_000L
        for (attempt in 1..3) {
            try {
                client.readBinaryFile(fileId) { input, total ->
                    copyToTemporary(input, temporary, total, context, onProgress)
                }
                return
            } catch (failure: CredentialException) {
                val transient = failure.reason == CredentialFailure.TEMPORARY ||
                    failure.statusCode in setOf(408, 429, 500, 502, 503, 504)
                if (!transient || attempt == 3) throw failure
            }
            // As duas esperas são de 1 e 2 segundos. O cancelamento durante a espera impede novo HTTP.
            waitBeforeRetry(retryDelayMillis)
            retryDelayMillis *= 2
        }
    }

    /**
     * Copia em blocos com memória limitada. Isola erros de leitura da rede dos erros de escrita
     * e do callback. Confere o tamanho antes da publicação, inclusive para respostas truncadas.
     */
    private fun copyToTemporary(
        input: InputStream,
        temporary: Path,
        total: Long?,
        context: CoroutineContext,
        onProgress: (Long, Long?) -> Unit,
    ) {
        context.ensureActive()
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        Files.newOutputStream(temporary).use { output ->
            onProgress(0L, total)
            while (true) {
                context.ensureActive()
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    throw CredentialException(CredentialFailure.TEMPORARY, "A leitura do arquivo do Drive foi interrompida.")
                }
                if (count == -1) break
                if (total != null && count > total - written) throw sizeMismatch()
                output.write(buffer, 0, count)
                written += count
                onProgress(written, total)
            }
        }
        if (total != null && written != total) throw sizeMismatch()
    }

    /** Um fim prematuro ou excesso de bytes exige consultar novamente o tamanho e reiniciar a cópia. */
    private fun sizeMismatch(): CredentialException = CredentialException(CredentialFailure.TEMPORARY,
        "O conteúdo recebido não corresponde ao tamanho do arquivo no Drive.")
}
