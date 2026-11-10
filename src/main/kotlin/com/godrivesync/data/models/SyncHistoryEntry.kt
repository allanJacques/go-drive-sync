package com.godrivesync.data.models

import java.time.LocalDateTime

/** Direção de uma transferência individual; não é a política bidirecional de um mapeamento. */
enum class TransferDirection {
    /** Operação do Drive para o computador. */
    DOWNLOAD,

    /** Operação do computador para o Drive. */
    UPLOAD,
}

/** Resultado da operação; os nomes correspondem aos valores textuais definidos no plano. */
enum class TransferStatus {
    /** Transferência concluída. */
    SUCCESS,

    /** Transferência encerrada com falha. */
    FAILED,

    /** Arquivo deliberadamente não transferido pelas regras de sincronização. */
    SKIPPED,
}

/**
 * Registro imutável de uma transferência; inserir este DTO não transfere o arquivo.
 * Os enums impedem valores arbitrários de direção/status na API dos repositórios.
 *
 * @property accountId Conta existente à qual a operação pertence.
 * @property driveFileId Identificador do arquivo remoto.
 * @property fileName Nome apresentado no histórico.
 * @property filePath Caminho local associado à operação, sem acesso ao sistema de arquivos.
 * @property direction Sentido efetivo da transferência: download ou upload.
 * @property status Resultado informado pelo futuro sincronizador.
 * @property fileSize Tamanho não negativo em bytes; Long comporta arquivos maiores que 2 GiB.
 * @property id Identidade gerada pelo banco; inserções exigem null.
 * @property mappingId Vínculo opcional; se preenchido, deve pertencer à mesma conta.
 * @property timestamp Data/hora sem fuso; null na inserção usa o relógio do banco.
 * @property errorMessage Detalhe opcional da falha, sem credenciais ou tokens.
 */
data class SyncHistoryEntry(
    val accountId: Int,
    val driveFileId: String,
    val fileName: String,
    val filePath: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val fileSize: Long,
    val id: Int? = null,
    val mappingId: Int? = null,
    val timestamp: LocalDateTime? = null,
    val errorMessage: String? = null,
)
