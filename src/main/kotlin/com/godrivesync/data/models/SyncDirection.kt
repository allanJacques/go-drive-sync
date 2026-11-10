package com.godrivesync.data.models

/** Política de um mapeamento; o nome do enum é persistido no VARCHAR existente. */
enum class SyncDirection {
    /** Permite baixar e enviar arquivos compatíveis, conforme as futuras regras do sincronizador. */
    BIDIRECTIONAL,

    /** Permite somente trazer arquivos do Drive para o computador. */
    DOWNLOAD_ONLY,
}
