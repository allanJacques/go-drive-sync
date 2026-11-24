package com.godrivesync.drive.auth

import com.google.api.client.auth.oauth2.BearerToken
import com.google.api.client.auth.oauth2.ClientParametersAuthentication
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.auth.oauth2.TokenResponse
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.godrivesync.data.security.TokenProtectionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Categorias que permitem decidir entre novo login, correção local e tentativa posterior, sem ler texto remoto. */
enum class CredentialFailure {
    REAUTHORIZATION_REQUIRED, TEMPORARY, CONFIGURATION, INVALID_RESPONSE,
    CREDENTIALS_CHANGED, ACCOUNT_NOT_FOUND, API_REJECTED,
}

/** Falha sanitizada; [statusCode] é opcional e nunca acompanha corpo, URL, cabeçalhos ou causa remota. */
class CredentialException(val reason: CredentialFailure, message: String, val statusCode: Int? = null) : IOException(message)

/**
 * Executa operações do SDK com credenciais renováveis da conta, fora da thread gráfica.
 * Compartilhar uma instância por serviço/banco: o Mutex por ID serializa operações da mesma conta;
 * contas diferentes continuam independentes. Cada operação relê o banco, sem cache de tokens duradouro.
 * A comparação na persistência também protege contra novos logins e outras instâncias do manager.
 * Não inicia navegador: REAUTHORIZATION_REQUIRED é entregue ao dono do fluxo de login.
 */
class AccountCredentialManager internal constructor(
    private val accounts: AccountAuthorizationService,
    private val configuration: () -> OAuthConfiguration,
    private val transport: HttpTransport,
    private val tokenEndpoint: String,
    private val apiOrigin: URI,
    private val timeSource: Clock,
) {
    /** Cria a configuração de produção com endpoints fixos e JSON Desktop externo ao aplicativo. */
    constructor(accounts: AccountAuthorizationService, configurationPath: Path = Path.of("credentials.json")) : this(
        accounts, { OAuthConfiguration.load(configurationPath) }, NetHttpTransport(), TOKEN_ENDPOINT,
        URI("https://www.googleapis.com"), Clock.systemUTC(),
    )

    /** Locks guardam somente IDs, nunca credenciais; vivem enquanto este manager estiver em uso. */
    private val accountLocks = ConcurrentHashMap<Int, Mutex>()

    /**
     * [operation] recebe o inicializador para Drive.Builder ou uma HttpRequestFactory.
     * Deve executar/consumir as respostas dentro do bloco e retornar dados materializados, sem guardar
     * o inicializador ou devolver requisições/streams para uso posterior. O bloco é bloqueante em IO,
     * como um SwingWorker; não deve iniciar outras operações deste manager nem alterar seus handlers.
     * Cancelamento é conferido entre etapas; HTTP síncrono termina por resposta ou timeout, não por
     * interrupção imediata. Cancelar depois do commit não desfaz a renovação persistida.
     */
    suspend fun <T> execute(accountId: Int, operation: (HttpRequestInitializer) -> T): T =
        accountLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                try {
                    context.ensureActive()
                    val snapshot = accounts.loadSnapshot(accountId) ?: throw CredentialException(
                        CredentialFailure.ACCOUNT_NOT_FOUND, "A conta não está mais salva neste aplicativo.")
                    val credential = renewableCredential(snapshot) { context.ensureActive() }
                    val initializer = HttpRequestInitializer { request ->
                        configureRequest(request)
                        // Somente a origem da Drive API recebe Bearer. Redirects permanecem desativados.
                        request.interceptor = com.google.api.client.http.HttpExecuteInterceptor {
                            context.ensureActive()
                            requireTrustedOrigin(it.url)
                            credential.intercept(it)
                        }
                        var retried = false
                        request.unsuccessfulResponseHandler = com.google.api.client.http.HttpUnsuccessfulResponseHandler { _, response, supportsRetry ->
                            context.ensureActive()
                            // O SDK só pode repetir conteúdo reproduzível. Um segundo 401 encerra a chamada.
                            if (response.statusCode != 401 || !supportsRetry || retried) false
                            else {
                                retried = true
                                credential.refreshToken()
                            }
                        }
                    }
                    operation(initializer).also { context.ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: CredentialException) {
                    throw failure
                } catch (failure: OAuthException) {
                    throw failure
                } catch (failure: TokenProtectionException) {
                    throw failure
                } catch (failure: AccountAuthorizationException) {
                    throw failure
                } catch (failure: HttpResponseException) {
                    throw CredentialException(CredentialFailure.API_REJECTED,
                        "A API recusou a operação (HTTP ${failure.statusCode}).", failure.statusCode)
                } catch (_: Exception) {
                    context.ensureActive()
                    throw CredentialException(CredentialFailure.TEMPORARY,
                        "Não foi possível concluir a operação. Tente novamente mais tarde.")
                }
            }
        }

    /**
     * Usa Credential/RefreshTokenRequest oficiais e persiste a resposta validada antes de utilizá-la.
     * A subclasse evita o handler padrão que registra exceções HTTP e distingue invalid_grant de
     * indisponibilidade. Tokens transitórios ficam restritos a esta operação.
     */
    private fun renewableCredential(initial: CredentialSnapshot, checkActive: () -> Unit): Credential {
        val config = configuration()
        var snapshot = initial
        var refreshed = false
        val builder = Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
            .setTransport(transport)
            .setJsonFactory(GsonFactory.getDefaultInstance())
            .setTokenServerEncodedUrl(tokenEndpoint)
            .setClientAuthentication(ClientParametersAuthentication(config.clientId, config.clientSecret))
            .setClock { timeSource.millis() }
            .setRequestInitializer { request -> configureRequest(request, retries = 0) }
        return object : Credential(builder) {
            /** Renova com margem de 60 s; token recém-emitido de vida curta não provoca loop de refresh. */
            override fun intercept(request: HttpRequest) {
                checkActive()
                val expiry = snapshot.credentials.expiresAt
                if (accessToken == null || !expiry.isAfter(timeSource.instant()) ||
                    (!refreshed && !expiry.isAfter(timeSource.instant().plusSeconds(60)))) {
                    refreshToken()
                }
                method.intercept(request, accessToken)
            }

            /** Troca via SDK, valida a resposta e grava com comparação otimista; jamais inclui erros remotos. */
            override fun executeRefreshToken(): TokenResponse {
                checkActive()
                val startedAt = timeSource.instant()
                val response = try {
                    super.executeRefreshToken() ?: throw CredentialException(
                        CredentialFailure.REAUTHORIZATION_REQUIRED, "Autorize esta conta novamente para continuar.")
                } catch (failure: TokenResponseException) {
                    val reason = when {
                        failure.statusCode in 400..499 && failure.details?.error == "invalid_grant" ->
                            CredentialFailure.REAUTHORIZATION_REQUIRED
                        failure.statusCode in 400..499 && failure.details?.error in setOf("invalid_client", "unauthorized_client") ->
                            CredentialFailure.CONFIGURATION
                        failure.statusCode == 429 || failure.statusCode >= 500 -> CredentialFailure.TEMPORARY
                        else -> CredentialFailure.INVALID_RESPONSE
                    }
                    throw CredentialException(reason, when (reason) {
                        CredentialFailure.REAUTHORIZATION_REQUIRED -> "Autorize esta conta novamente para continuar."
                        CredentialFailure.CONFIGURATION -> "O Google recusou a configuração OAuth do aplicativo. Verifique o JSON Desktop."
                        CredentialFailure.TEMPORARY -> "O serviço de renovação está indisponível. Tente novamente mais tarde."
                        else -> "O Google recusou a renovação com uma resposta inesperada."
                    })
                } catch (failure: CredentialException) {
                    throw failure
                } catch (_: Exception) {
                    checkActive()
                    throw CredentialException(CredentialFailure.TEMPORARY,
                        "Não foi possível renovar o acesso. Verifique a conexão e tente novamente.")
                }
                checkActive()
                val credentials = validateResponse(response, refreshToken, startedAt)
                snapshot = accounts.saveRefreshed(snapshot, credentials) ?: throw CredentialException(
                    CredentialFailure.CREDENTIALS_CHANGED,
                    "As credenciais foram alteradas durante a operação. Tente novamente com a conta atual.")
                refreshed = true
                return response
            }
        }.apply {
            accessToken = initial.credentials.accessToken
            refreshToken = initial.credentials.refreshToken
            expirationTimeMilliseconds = initial.credentials.expiresAt.toEpochMilli()
        }
    }

    /** Impede persistência de token vazio, tipo incorreto, escopo reduzido ou expiração inválida/estourada. */
    private fun validateResponse(response: TokenResponse, previousRefresh: String, startedAt: Instant): OAuthCredentials = try {
        require(!response.accessToken.isNullOrBlank())
        require(response.tokenType.equals("Bearer", ignoreCase = true))
        val seconds = requireNotNull(response.expiresInSeconds)
        require(seconds > 0)
        val expiry = startedAt.plusSeconds(seconds)
        require(expiry.isAfter(timeSource.instant()))
        expiry.toEpochMilli() // Credential usa milissegundos Long, mesmo que Instant comporte valores maiores.
        val refresh = response.refreshToken ?: previousRefresh
        require(refresh.isNotBlank())
        response.scope?.let { require(DRIVE_SCOPE in it.split(' ')) }
        OAuthCredentials(response.accessToken, refresh, expiry)
    } catch (_: Exception) {
        throw CredentialException(CredentialFailure.INVALID_RESPONSE,
            "O Google devolveu credenciais de renovação incompletas ou inválidas.")
    }

    /** Restringe destino antes de anexar Bearer; portas/origem de testes são injetadas somente internamente. */
    private fun requireTrustedOrigin(url: GenericUrl) {
        val uri = url.toURI()
        if (uri.scheme != apiOrigin.scheme || uri.host != apiOrigin.host || uri.port != apiOrigin.port || uri.userInfo != null) {
            throw CredentialException(CredentialFailure.CONFIGURATION, "Destino não autorizado para credenciais do Drive.")
        }
    }
}

/** Limita I/O bloqueante, desliga logs sensíveis e redirects; token POST nunca recebe repetição automática. */
private fun configureRequest(request: HttpRequest, retries: Int = 1) {
    request.connectTimeout = 15_000
    request.readTimeout = 30_000
    request.numberOfRetries = retries
    request.followRedirects = false
    request.isLoggingEnabled = false
    request.isCurlLoggingEnabled = false
}
