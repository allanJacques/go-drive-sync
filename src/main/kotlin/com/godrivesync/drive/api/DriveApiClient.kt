package com.godrivesync.drive.api

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.EmptyContent
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.Data
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.godrivesync.data.models.Account
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.AccountCredentialManager
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/** Prefixo MIME usado pelo Drive para sua família de documentos, pastas e atalhos. */
private const val GOOGLE_NATIVE_MIME_PREFIX = "application/vnd.google-apps."

/** Limite decimal do envio direto com metadados e conteúdo em um único POST. */
private const val DIRECT_UPLOAD_LIMIT = 5_000_000L
/** O bloco de 1 MiB é múltiplo dos 256 KiB exigidos pelo SDK para uma sessão retomável. */
private const val UPLOAD_CHUNK_SIZE = 1024 * 1024

/**
 * Metadados de um item do Drive. ID, nome e tipo MIME estão presentes nas duas listagens.
 * A listagem por pasta também solicita [size] em bytes, [modifiedTime] como instante UTC e
 * [parents] com os IDs das pastas pai. Tamanho e data ausentes são nulos, e pais omitidos
 * produzem uma lista vazia. Um arquivo de zero bytes conserva o tamanho zero.
 */
data class DriveFileSummary(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long? = null,
    val modifiedTime: Instant? = null,
    val parents: List<String> = emptyList(),
) {
    /** Identifica uma pasta pelo tipo exato que o Drive atribui a ela. */
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"

    /** Identifica a família de tipos do Google pelo prefixo MIME, incluindo pastas e atalhos. */
    val isGoogleNative: Boolean get() = mimeType.startsWith(GOOGLE_NATIVE_MIME_PREFIX)
}

/**
 * Página materializada: [nextPageToken] null encerra a paginação, mesmo se [files] estiver vazio.
 * Uma página vazia com token ainda admite continuação. [incompleteSearch] indica resultado parcial.
 * O token de página é opaco, deve voltar à mesma conta/consulta e não deve ser registrado em logs.
 */
data class DriveFilesPage(
    val files: List<DriveFileSummary>,
    val nextPageToken: String?,
    val incompleteSearch: Boolean,
) {
    /** Omite nomes e token de continuação de mensagens automáticas de diagnóstico. */
    override fun toString(): String =
        "DriveFilesPage(count=${files.size}, hasNextPage=${nextPageToken != null}, incompleteSearch=$incompleteSearch)"
}

/**
 * Cliente vinculado a uma conta persistida, sem cache de tokens ou dependência da UI.
 * Compartilhe [credentials] com os outros clientes da aplicação para manter o Mutex por conta.
 * Construir este wrapper não faz I/O; cada chamada relê/decifra os tokens atuais no manager.
 * Transporte e raiz alternativos são internos, destinados aos testes locais.
 * @property accountId Identidade local estável; não é uma seleção exclusiva de conta no aplicativo.
 */
class DriveApiClient internal constructor(
    val accountId: Int,
    private val credentials: AccountCredentialManager,
    private val transport: HttpTransport,
    private val rootUrl: String,
) {
    init {
        require(accountId > 0) { "A conta precisa ter um ID persistido válido." }
    }

    /**
     * Aceita o DTO do repositório, mas conserva apenas seu ID. Seus tokens são cifras, nunca Bearer.
     * Conta sem ID é recusada; remoção posterior é detectada pelo manager antes da requisição.
     */
    constructor(account: Account, credentials: AccountCredentialManager) : this(
        requireNotNull(account.id) { "Salve a conta antes de criar o cliente Drive." },
        credentials, NetHttpTransport(), "https://www.googleapis.com/",
    )

    /** Fábricas usam a projeção sem tokens disponível nas telas e no serviço de contas. */
    companion object {
        /**
         * Vincula uma conta já salva às credenciais persistidas, incluindo refresh e expiração.
         * A carga é adiada até cada operação, evitando usar a fotografia antiga após uma reconexão.
         * Não cria outro manager, não abre OAuth e não verifica conectividade durante a construção.
         */
        fun fromSavedAccount(account: SavedAccount, credentials: AccountCredentialManager): DriveApiClient =
            DriveApiClient(account.id, credentials, NetHttpTransport(), "https://www.googleapis.com/")
    }

    /**
     * Lista uma página de arquivos/pastas não excluídos do corpus do usuário, sem Shared Drives.
     * [pageSize] limita a página a 1..1000 itens; [pageToken] continua a mesma consulta nesta conta.
     * Não percorre pastas nem inicia sincronização. Retorna dados consumidos integralmente em IO,
     * semelhante a um SwingWorker: a função suspensa pode ser chamada pela corrotina do ViewModel.
     * Falhas autenticadas mantêm as categorias do manager; cancelamento também é propagado.
     */
    suspend fun listFilesPage(pageSize: Int = 10, pageToken: String? = null): DriveFilesPage {
        require(pageSize in 1..1000) { "O tamanho da página deve estar entre 1 e 1000." }
        require(pageToken == null || pageToken.isNotBlank()) { "O token de página não pode estar vazio." }
        return readFilesPage("trashed = false", "id,name,mimeType", pageSize, pageToken)
    }

    /**
     * Retorna os filhos imediatos de [folderId] fora da lixeira, na ordem recebida do Drive.
     * O ID pode ser "root" para o Meu Drive da conta. Uma pasta vazia produz uma lista vazia.
     * Cada página usa as credenciais atuais da conta. Falhas e cancelamento se propagam mesmo
     * depois da primeira página, pois o chamador só recebe a lista ao término da consulta.
     */
    suspend fun listFiles(folderId: String): List<DriveFileSummary> {
        require(folderId.isNotBlank()) { "Informe o ID da pasta do Drive." }
        // A consulta tem sua própria sintaxe para literais. O SDK cuida da codificação da URL.
        val escapedId = folderId.replace("\\", "\\\\").replace("'", "\\'")
        val query = "'$escapedId' in parents and trashed = false"
        return buildList {
            val seenTokens = mutableSetOf<String>()
            var pageToken: String? = null
            do {
                val page = readFilesPage(query, "id,name,mimeType,size,modifiedTime,parents", 1000, pageToken)
                if (page.incompleteSearch) {
                    throw CredentialException(CredentialFailure.TEMPORARY,
                        "O Drive informou que a busca na pasta ficou incompleta. Tente novamente.")
                }
                addAll(page.files)
                // Só o token indica continuação, inclusive quando a página atual está vazia.
                pageToken = page.nextPageToken
                if (pageToken != null && !seenTokens.add(pageToken)) {
                    throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                        "O Drive repetiu uma continuação de página.")
                }
            } while (pageToken != null)
        }
    }

    /**
     * Abre o conteúdo binário de [fileId] para uma única tentativa de download desta conta.
     * [consume] recebe o stream e o tamanho descompactado, quando conhecido. Ele deve ler o
     * conteúdo dentro da chamada em IO. O cliente libera a resposta e o lock ao terminar.
     */
    internal suspend fun readBinaryFile(fileId: String, consume: (InputStream, Long?) -> Unit) {
        require(fileId.isNotBlank()) { "Informe o ID do arquivo do Drive." }
        readMedia(consume) { drive ->
            val size = binaryDownloadSize(drive, fileId)
            drive.files().get(fileId).executeMedia() to size
        }
    }

    /**
     * Cria um item na conta usando o stream aberto pelo FileHandler. Em uma sessão retomável,
     * [onChunk] recebe o acumulado confirmado pelo Drive. O chamador publica a conclusão só
     * depois que esta função valida o ID. Uma falha incerta nunca reinicia o POST de criação.
     */
    internal suspend fun createFile(
        name: String,
        parentFolderId: String,
        mime: String,
        input: InputStream,
        total: Long,
        onChunk: (Long) -> Unit,
    ): String = execute({ request -> configureUploadSession(request, total) }) { drive ->
        val metadata = DriveFile().setName(name).setParents(listOf(parentFolderId))
        val content = InputStreamContent(mime, input).setLength(total).setCloseInputStream(false)
        val create = drive.files().create(metadata, content).setFields("id").setDisableGZipContent(true)
        val uploader = create.mediaHttpUploader
        uploader.isDirectUploadEnabled = total <= DIRECT_UPLOAD_LIMIT
        uploader.chunkSize = UPLOAD_CHUNK_SIZE
        var confirmed = 0L
        var stalled = 0
        uploader.setProgressListener { transfer ->
            if (!transfer.isDirectUploadEnabled && transfer.uploadState == MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS) {
                val received = transfer.numBytesUploaded
                // Uma resposta 308 pode aceitar apenas parte do bloco ou repetir a posição anterior.
                if (received > confirmed) {
                    confirmed = received
                    stalled = 0
                    if (received < total) onChunk(received)
                } else if (++stalled >= 3) {
                    throw CredentialException(CredentialFailure.TEMPORARY, "O Drive deixou de confirmar os blocos do upload.")
                }
            }
        }
        create.execute()?.id?.takeIf { it.isNotBlank() }
            ?: throw CredentialException(CredentialFailure.INVALID_RESPONSE, "O Drive não informou o ID do arquivo enviado.")
    }

    /**
     * Habilita uma consulta de posição na mesma sessão após falha de transporte ou erro do servidor
     * durante um PUT. O SDK transforma a repetição em uma consulta vazia e usa o Range confirmado
     * para reposicionar o stream. Após renovar um 401, preparamos a mesma consulta explicitamente.
     */
    private fun configureUploadSession(request: HttpRequest, total: Long) {
        // A fábrica do SDK atribui o verbo depois de chamar o inicializador. Conferimos no callback.
        request.setIOExceptionHandler { current, supportsRetry -> current.requestMethod == "PUT" && supportsRetry }
        val authHandler = request.unsuccessfulResponseHandler
        request.setUnsuccessfulResponseHandler { current, response, supportsRetry ->
            if (authHandler.handleResponse(current, response, supportsRetry)) {
                if (current.requestMethod == "PUT") {
                    current.content = EmptyContent()
                    current.headers.contentRange = "bytes */$total"
                }
                true
            } else {
                current.requestMethod == "PUT" && supportsRetry && response.statusCode in setOf(500, 502, 503, 504)
            }
        }
    }

    /** Consulta as conversões da conta no Drive, incluindo tipos de saída ainda desconhecidos pelo aplicativo. */
    suspend fun listExportFormats(): DriveExportCatalog = execute { drive -> readExportCatalog(drive) }

    /** Materializa a resposta dentro do lock da conta. Um campo ausente indica resposta inválida. */
    private fun readExportCatalog(drive: Drive): DriveExportCatalog {
        val raw = try {
            drive.about().get().setFields("exportFormats").execute().exportFormats
        } catch (_: IllegalArgumentException) {
            // O parser do SDK também usa esta exceção quando o JSON tem a estrutura errada.
            throw invalidExportCatalog()
        }
        if (raw == null || Data.isNull(raw)) throw invalidExportCatalog()
        val formats = GoogleDocumentType.entries.associateWith { type ->
            val targets = raw[type.mimeType]
            if (Data.isNull(targets)) throw invalidExportCatalog()
            targets.orEmpty().distinct().map { mime ->
                if (mime.isNullOrBlank() || Data.isNull(mime) || '/' !in mime) throw invalidExportCatalog()
                DriveExportFormat(mime)
            }
        }
        return DriveExportCatalog(accountId, formats)
    }

    /** Uma resposta incompleta não permite concluir que um formato saiu do catálogo. */
    private fun invalidExportCatalog(): CredentialException =
        CredentialException(CredentialFailure.INVALID_RESPONSE, "O Drive devolveu um catálogo de exportação inválido.")

    /** Lê uma única conversão de [fileId]. O tamanho do arquivo nativo não mede o resultado convertido. */
    internal suspend fun readExportedFile(
        fileId: String,
        type: GoogleDocumentType,
        format: DriveExportFormat,
        consume: (InputStream, Long?) -> Unit,
    ) {
        readMedia(consume, { drive, failure -> exportRejection(drive, failure, type, format) }) { drive ->
            drive.files().export(fileId, format.mimeType).executeMedia() to null
        }
    }

    /**
     * Traduz recusas específicas do Drive sem expor o JSON remoto. Se a conversão foi recusada,
     * consulta as capacidades novamente sob o mesmo lock. Um erro de rede nessa consulta deixa
     * o catálogo desconhecido e preserva a recusa original para o histórico.
     */
    private fun exportRejection(
        drive: Drive,
        error: GoogleJsonResponseException,
        type: GoogleDocumentType,
        format: DriveExportFormat,
    ): DriveExportException? {
        if (error.statusCode !in 400..499) return null
        val details = error.details
        val errors = details?.errors.orEmpty()
        val reasons = errors.map { it.reason }
        // Algumas respostas usam a categoria genérica badRequest e explicam a conversão na mensagem.
        val unsupportedConversion = "The requested conversion is not supported."
        val reason = when {
            "exportSizeLimitExceeded" in reasons -> DriveExportFailure.EXPORT_SIZE_LIMIT_EXCEEDED
            "fileNotExportable" in reasons || "fileNotDownloadable" in reasons -> DriveExportFailure.FILE_NOT_EXPORTABLE
            "insufficientFilePermissions" in reasons -> DriveExportFailure.PERMISSION_DENIED
            "exportFormatNotFound" in reasons || "conversionNotSupported" in reasons -> DriveExportFailure.EXPORT_FORMAT_UNSUPPORTED
            error.statusCode == 400 && (details?.message == unsupportedConversion ||
                errors.any { it.reason == "badRequest" && it.message == unsupportedConversion }) ->
                DriveExportFailure.EXPORT_FORMAT_UNSUPPORTED
            else -> return null
        }
        var refreshed: DriveExportCatalog? = null
        if (reason == DriveExportFailure.EXPORT_FORMAT_UNSUPPORTED) {
            refreshed = try {
                readExportCatalog(drive)
            } catch (_: IOException) {
                null
            }
        }
        return DriveExportException(reason, type.mimeType, format.mimeType, DriveExportStage.EXPORT, error.statusCode, refreshed)
    }

    /**
     * Compartilha a leitura autenticada de mídia inteira entre download e exportação. [open] fornece
     * a resposta e um tamanho conhecido opcional. [classifyRejection] pode traduzir erros da operação
     * antes da sanitização do manager. O stream é consumido e fechado dentro do bloqueio da conta.
     */
    private suspend fun readMedia(
        consume: (InputStream, Long?) -> Unit,
        classifyRejection: (Drive, GoogleJsonResponseException) -> DriveExportException? = { _, _ -> null },
        open: (Drive) -> Pair<HttpResponse, Long?>,
    ) {
        execute { drive ->
            try {
                val (response, size) = open(drive)
                try {
                    // Solicitamos o arquivo inteiro. Aceitar HTTP 206 poderia publicar apenas um trecho.
                    if (response.statusCode != 200) {
                        throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                            "O Drive não enviou uma resposta de arquivo completo.")
                    }
                    val content = response.content ?: InputStream.nullInputStream()
                    // Content-Length de uma resposta gzip mede bytes comprimidos, diferentes dos gravados.
                    val total = size ?: response.headers.contentLength?.takeIf {
                        it >= 0 && response.contentEncoding.isNullOrBlank()
                    }
                    // O manager sanitiza exceções do SDK. Guarde o resultado do consumidor até sair do
                    // bloco para que uma falha de disco ou do callback mantenha a sua origem.
                    runCatching { consume(content, total) }
                } finally {
                    response.disconnect()
                }
            } catch (failure: GoogleJsonResponseException) {
                // O Drive também usa 403 para limite de requisições. Permissão negada continua definitiva.
                val rateLimited = failure.details?.errors.orEmpty().any { error ->
                    error.reason == "rateLimitExceeded" || error.reason == "userRateLimitExceeded"
                }
                if (failure.statusCode == 403 && rateLimited) {
                    throw CredentialException(CredentialFailure.TEMPORARY,
                        "O Drive limitou a frequência de downloads. Tente novamente.", 403)
                }
                // Devolvemos o erro específico fora do manager, assim como os erros da cópia local.
                val rejection = classifyRejection(drive, failure) ?: throw failure
                Result.failure(rejection)
            }
        }.getOrThrow()
    }

    /** Consulta tipo, permissão e tamanho antes da mídia. Tamanho ausente é válido, inclusive para o progresso. */
    private fun binaryDownloadSize(drive: Drive, fileId: String): Long? {
        val file = drive.files().get(fileId).setFields("mimeType,size,capabilities/canDownload").execute()
        val size = file.getSize()
        if (file.mimeType.isNullOrBlank() || file.capabilities?.canDownload == null || (size != null && size < 0)) {
            throw CredentialException(CredentialFailure.INVALID_RESPONSE, "O Drive devolveu metadados de download inválidos.")
        }
        if (file.mimeType.startsWith(GOOGLE_NATIVE_MIME_PREFIX)) {
            throw CredentialException(CredentialFailure.API_REJECTED, "O item do Google Drive não é um arquivo binário.")
        }
        if (file.capabilities.canDownload != true) {
            throw CredentialException(CredentialFailure.API_REJECTED, "A conta não tem permissão para baixar este arquivo.")
        }
        return size
    }

    /**
     * Lê uma página autenticada com o filtro [query] e a projeção [fileFields]. Converte os
     * dados do SDK em valores Kotlin na corrotina de IO e libera o bloqueio da conta a cada página.
     */
    private suspend fun readFilesPage(query: String, fileFields: String, pageSize: Int, pageToken: String?): DriveFilesPage =
        execute { drive ->
            val response = drive.files().list()
                .setSpaces("drive")
                .setCorpora("user")
                .setIncludeItemsFromAllDrives(false)
                .setQ(query)
                .setPageSize(pageSize)
                .setPageToken(pageToken)
                .setFields("nextPageToken,incompleteSearch,files($fileFields)")
                .execute()
            // DTOs próprios evitam expor os modelos Java mutáveis do SDK à camada chamadora.
            val files = response.files.orEmpty().map { file ->
                if (file.id.isNullOrBlank() || file.name == null || file.mimeType.isNullOrBlank()) {
                    throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                        "O Drive devolveu metadados de arquivo incompletos.")
                }
                DriveFileSummary(
                    id = file.id,
                    name = file.name,
                    mimeType = file.mimeType,
                    size = file.getSize(),
                    modifiedTime = file.modifiedTime?.let { Instant.ofEpochMilli(it.value) },
                    parents = file.parents.orEmpty().toList(),
                )
            }
            if (response.nextPageToken != null && response.nextPageToken.isBlank()) {
                throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                    "O Drive devolveu uma continuação de página inválida.")
            }
            DriveFilesPage(files, response.nextPageToken, response.incompleteSearch == true)
        }

    /**
     * Constrói e utiliza Drive dentro do lock/escopo de credenciais, sem expor serviço ou initializer.
     * Toda futura operação deve consumir respostas/streams aqui e devolver somente dados prontos.
     * A configuração opcional de mídia complementa o inicializador do manager para a sessão de upload.
     */
    private suspend fun <T> execute(
        configureMedia: HttpRequestInitializer? = null,
        operation: (Drive) -> T,
    ): T = credentials.execute(accountId) { initializer ->
        val drive = Drive.Builder(transport, GsonFactory.getDefaultInstance()) { request ->
            initializer.initialize(request)
            configureMedia?.initialize(request)
        }
            .setRootUrl(rootUrl)
            .setApplicationName("Go Drive Sync")
            .build()
        operation(drive)
    }
}
