package com.godrivesync.drive.api

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.godrivesync.data.models.Account
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.AccountCredentialManager
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure
import java.io.InputStream
import java.time.Instant

/** Prefixo MIME usado pelo Drive para sua família de documentos, pastas e atalhos. */
private const val GOOGLE_NATIVE_MIME_PREFIX = "application/vnd.google-apps."

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
        execute { drive ->
            try {
                val size = binaryDownloadSize(drive, fileId)
                val response = drive.files().get(fileId).executeMedia()
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
                throw failure
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
     * Não substitui handlers, timeouts, política de origem ou renovação definidos pelo manager.
     */
    private suspend fun <T> execute(operation: (Drive) -> T): T = credentials.execute(accountId) { initializer ->
        val drive = Drive.Builder(transport, GsonFactory.getDefaultInstance(), initializer)
            .setRootUrl(rootUrl)
            .setApplicationName("Go Drive Sync")
            .build()
        operation(drive)
    }
}
