package com.godrivesync.drive.api

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.godrivesync.data.models.Account
import com.godrivesync.data.models.SavedAccount
import com.godrivesync.drive.auth.AccountCredentialManager
import com.godrivesync.drive.auth.CredentialException
import com.godrivesync.drive.auth.CredentialFailure

/** Metadados mínimos de um arquivo ou pasta; não retém objetos mutáveis nem credenciais do SDK. */
data class DriveFileSummary(val id: String, val name: String, val mimeType: String)

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
        return execute { drive ->
            val response = drive.files().list()
                .setSpaces("drive")
                .setCorpora("user")
                .setIncludeItemsFromAllDrives(false)
                .setQ("trashed = false")
                .setPageSize(pageSize)
                .setPageToken(pageToken)
                .setFields("nextPageToken,incompleteSearch,files(id,name,mimeType)")
                .execute()
            // DTOs próprios evitam expor os modelos Java mutáveis do SDK à camada chamadora.
            val files = response.files.orEmpty().map { file ->
                if (file.id.isNullOrBlank() || file.name == null || file.mimeType.isNullOrBlank()) {
                    throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                        "O Drive devolveu metadados de arquivo incompletos.")
                }
                DriveFileSummary(file.id, file.name, file.mimeType)
            }
            if (response.nextPageToken != null && response.nextPageToken.isBlank()) {
                throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                    "O Drive devolveu uma continuação de página inválida.")
            }
            DriveFilesPage(files, response.nextPageToken, response.incompleteSearch == true)
        }
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
