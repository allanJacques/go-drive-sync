package com.godrivesync.drive.api

import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat

/** Tipos nativos sincronizáveis. [officeMimeType] é usado quando o chamador omite o formato de saída. */
enum class GoogleDocumentType(val mimeType: String, val officeMimeType: String) {
    DOCS("application/vnd.google-apps.document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    SHEETS("application/vnd.google-apps.spreadsheet", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    SLIDES("application/vnd.google-apps.presentation", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /** Identifica o documento a partir dos metadados recebidos na listagem. */
    companion object {
        /** Recusa pastas, atalhos e tipos que não fazem parte da sincronização de documentos nativos. */
        fun fromMimeType(mimeType: String): GoogleDocumentType =
            requireNotNull(entries.find { it.mimeType == mimeType }) { "Informe o tipo nativo de Docs, Sheets ou Slides." }
    }
}

/** Rótulos e extensões locais. A disponibilidade de cada combinação vem do Drive para a conta. */
private val knownExportFormats = mapOf(
    GoogleDocumentType.DOCS.officeMimeType to ("Word" to "docx"),
    GoogleDocumentType.SHEETS.officeMimeType to ("Excel" to "xlsx"),
    GoogleDocumentType.SLIDES.officeMimeType to ("PowerPoint" to "pptx"),
    "application/vnd.oasis.opendocument.text" to ("Texto OpenDocument" to "odt"),
    "application/vnd.oasis.opendocument.spreadsheet" to ("Planilha OpenDocument" to "ods"),
    "application/vnd.oasis.opendocument.presentation" to ("Apresentação OpenDocument" to "odp"),
    "application/pdf" to ("PDF" to "pdf"),
    "application/epub+zip" to ("EPUB" to "epub"),
    "application/rtf" to ("Rich Text" to "rtf"),
    "application/zip" to ("Página da Web compactada" to "zip"),
    "text/html" to ("Página da Web" to "html"),
    "text/plain" to ("Texto simples" to "txt"),
    "text/markdown" to ("Markdown" to "md"),
    "text/csv" to ("Valores separados por vírgulas" to "csv"),
    "text/tab-separated-values" to ("Valores separados por tabulações" to "tsv"),
    "image/jpeg" to ("Imagem JPEG" to "jpg"),
    "image/png" to ("Imagem PNG" to "png"),
    "image/svg+xml" to ("Imagem SVG" to "svg"),
)

/** Descreve uma saída pelo [mimeType] usado em files.export, inclusive quando o tipo é novo para o app. */
data class DriveExportFormat(val mimeType: String) {
    /** Texto para a interface. O próprio MIME identifica formatos novos, que continuam selecionáveis. */
    val displayName: String get() = knownExportFormats[mimeType]?.first ?: mimeType

    /** Extensão convencional, sem ponto. O Drive só informa o MIME e pode anunciar formatos desconhecidos. */
    val extension: String? get() = knownExportFormats[mimeType]?.second

    /**
     * Sufixo seguro e estável para gravar o arquivo. Quando faltar uma extensão conhecida, o resumo
     * do MIME separa formatos inéditos do mesmo original sem usar texto remoto como caminho local.
     */
    internal fun fileSuffix(): String {
        extension?.let { return ".$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(mimeType.toByteArray(Charsets.UTF_8))
        return ".export-${HexFormat.of().formatHex(digest, 0, 12)}.bin"
    }
}

/**
 * Fotografia de about.exportFormats para [accountId]. Cada tipo nativo tem as opções anunciadas
 * naquela consulta. Pode ser compartilhada entre Configurações e as exportações de um ciclo da conta.
 */
class DriveExportCatalog internal constructor(
    val accountId: Int,
    private val formats: Map<GoogleDocumentType, List<DriveExportFormat>>,
) {
    /** Devolve as saídas disponíveis para [type]. Uma lista vazia representa ausência confirmada no catálogo. */
    fun formatsFor(type: GoogleDocumentType): List<DriveExportFormat> = formats[type].orEmpty()
}

/** Motivos do Drive relevantes para uma exportação e para o registro futuro no histórico. */
enum class DriveExportFailure {
    EXPORT_FORMAT_UNSUPPORTED, FILE_NOT_EXPORTABLE, EXPORT_SIZE_LIMIT_EXCEEDED, PERMISSION_DENIED
}

/** Etapa que constatou a falha, para distinguir uma consulta válida da recusa de um arquivo pelo endpoint. */
enum class DriveExportStage { CATALOG, EXPORT }

/**
 * Falha definitiva desta solicitação. [sourceMimeType] e [targetMimeType] identificam a conversão.
 * [statusCode] existe quando houve recusa HTTP. Uma recusa explícita de formato traz em
 * [refreshedCatalog] a nova consulta quando disponível, permitindo à fila limitar a falha ao
 * arquivo se a combinação ainda for anunciada. A mensagem local omite conteúdo da resposta.
 */
class DriveExportException internal constructor(
    val reason: DriveExportFailure,
    val sourceMimeType: String,
    val targetMimeType: String,
    val stage: DriveExportStage,
    val statusCode: Int? = null,
    val refreshedCatalog: DriveExportCatalog? = null,
) : IOException(when (reason) {
    DriveExportFailure.EXPORT_FORMAT_UNSUPPORTED -> "O Drive não aceita o formato solicitado para esta exportação."
    DriveExportFailure.FILE_NOT_EXPORTABLE -> "Este arquivo do Drive não pode ser exportado."
    DriveExportFailure.EXPORT_SIZE_LIMIT_EXCEEDED -> "O conteúdo excede o limite de tamanho de exportação do Drive."
    DriveExportFailure.PERMISSION_DENIED -> "A conta não tem permissão para exportar este arquivo."
})
