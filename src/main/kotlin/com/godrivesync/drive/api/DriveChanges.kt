package com.godrivesync.drive.api

import com.google.api.client.util.Data
import com.google.api.services.drive.model.Change
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure

/** Interpretação de uma mudança em relação ao inventário da conta mantido pelo consumidor. */
enum class DriveChangeKind { NEW, MODIFIED, TRASHED, REMOVED }

/** Estado observado para [fileId]. O Drive pode agrupar várias edições na mesma observação. */
sealed class DriveChange(val fileId: String) {
    /** O item está acessível. [file] contém os metadados atuais, inclusive as pastas pai. */
    class Present(val file: DriveFileSummary) : DriveChange(file.id)

    /** O item ainda está visível à conta, e o Drive informou explicitamente que está na lixeira. */
    class Trashed(fileId: String) : DriveChange(fileId)

    /** O item saiu da lista da conta por exclusão ou perda de acesso. O Drive pode omitir o motivo. */
    class Removed(fileId: String) : DriveChange(fileId)

    /** Distingue inclusão de atualização pela presença prévia do ID no inventário desta conta. */
    fun classify(knownFileIds: Set<String>): DriveChangeKind = when (this) {
        is Present -> if (fileId in knownFileIds) DriveChangeKind.MODIFIED else DriveChangeKind.NEW
        is Trashed -> DriveChangeKind.TRASHED
        is Removed -> DriveChangeKind.REMOVED
    }
}

/**
 * Página imutável do ponto de vista do SDK. [nextPageToken] continua a leitura do mesmo lote.
 * Na última página, [newStartPageToken] indica a posição para a próxima consulta incremental.
 * A construção pelo cliente garante que somente um dos dois esteja preenchido.
 */
class DriveChangesPage internal constructor(
    val changes: List<DriveChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?,
)

/** Converte a entrada do SDK antes de liberar a resposta. Uma remoção pode conter apenas o ID. */
internal fun Change.toDriveChange(): DriveChange {
    val id = fileId
    if (Data.isNull(this) || id.isNullOrBlank() || Data.isNull(removed)) throw invalidChangesResponse()
    if (removed == true) return DriveChange.Removed(id)
    val item = file
    if (item == null || Data.isNull(item) || item.id != id || Data.isNull(item.trashed)) throw invalidChangesResponse()
    return if (item.trashed == true) DriveChange.Trashed(id) else DriveChange.Present(item.toSummary())
}

/** Rejeita respostas incompletas sem incluir tokens, IDs ou conteúdo remoto na mensagem. */
internal fun invalidChangesResponse() =
    CredentialException(CredentialFailure.INVALID_RESPONSE, "O Drive devolveu uma resposta de mudanças inválida.")
