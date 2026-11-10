package com.godrivesync.data.models

import java.time.LocalDateTime

/**
 * Valores imutáveis do vínculo entre uma pasta remota e uma pasta local.
 * Alterar uma cópia não acessa disco nem inicia sincronização; update faz a persistência.
 *
 * @property accountId Conta proprietária existente; não pode ser trocada depois da criação.
 * @property driveFolderId Identificador estável da pasta na API do Drive.
 * @property driveFolderName Nome de exibição da pasta remota.
 * @property localFolderPath Caminho absoluto local, sem consultar ou criar a pasta ao persistir.
 * @property id Identidade gerada pelo H2; null antes de salvar.
 * @property syncDirection Política de transferência, distinta da direção de uma operação do histórico.
 * @property lastSync Última sincronização como data/hora sem fuso; null enquanto não executada.
 * @property enabled false pausa o mapeamento sem removê-lo.
 */
data class FolderMapping(
    val accountId: Int,
    val driveFolderId: String,
    val driveFolderName: String,
    val localFolderPath: String,
    val id: Int? = null,
    val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val lastSync: LocalDateTime? = null,
    val enabled: Boolean = true,
)
