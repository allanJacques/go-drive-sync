package com.godrivesync.drive.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Contrato interno que permite testar o manager sem rede Google nem credenciais reais.
 * A implementação de produção troca código + PKCE via HTTPS; nunca persiste o resultado.
 */
internal fun interface OAuthTokenExchange {
    /** Usa a mesma [redirectUri] da autorização e devolve os tokens com expiração absoluta. */
    suspend fun exchange(configuration: OAuthConfiguration, code: String, verifier: String, redirectUri: URI): OAuthCredentials
}

/**
 * Cliente de token com timeout, sem redirects e com requisição assíncrona cancelável.
 * @param endpoint Substituível apenas internamente para testes HTTP locais; produção usa endpoint fixo HTTPS.
 * @param clock Relógio injetável para testar a conversão de expires_in para Instant.
 */
internal class OAuthTokenClient(
    private val endpoint: URI = URI(TOKEN_ENDPOINT),
    private val clock: Clock = Clock.systemUTC(),
) : OAuthTokenExchange {
    /**
     * Não repete automaticamente o POST: um authorization code só pode ser consumido uma vez.
     * Erros de rede/HTTP não propagam corpo ou causa que possam conter dados sensíveis.
     */
    override suspend fun exchange(
        configuration: OAuthConfiguration, code: String, verifier: String, redirectUri: URI,
    ): OAuthCredentials {
        // Registrar antes da requisição evita superestimar a validade pelo tempo gasto na rede.
        val requestedAt = clock.instant()
        val body = encodeForm(mapOf(
            "client_id" to configuration.clientId,
            "client_secret" to configuration.clientSecret,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri.toString(),
            "grant_type" to "authorization_code",
        ))
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        val response = try {
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            suspendCancellableCoroutine<HttpResponse<String>> { continuation ->
                val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                continuation.invokeOnCancellation { pending.cancel(true) }
                pending.whenComplete { value, failure ->
                    if (failure != null) {
                        continuation.resumeWithException(OAuthException(
                            OAuthFailure.TOKEN_EXCHANGE, "Não foi possível concluir a troca do código com o Google.",
                        ))
                    } else {
                        continuation.resume(value)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Como em efeitos Compose, cancelamento pertence ao ciclo de vida e não vira erro de login.
            throw cancelled
        } catch (_: Exception) {
            throw OAuthException(OAuthFailure.TOKEN_EXCHANGE, "Não foi possível concluir a troca do código com o Google.")
        } finally {
            // shutdownNow não bloqueia a corrotina esperando conexões como close() poderia fazer.
            client.shutdownNow()
        }
        if (response.statusCode() != 200) {
            throw OAuthException(OAuthFailure.TOKEN_EXCHANGE, "O Google recusou a troca do código. Inicie uma nova autorização.")
        }
        return try {
            val json = Json.parseToJsonElement(response.body()) as JsonObject
            val access = json.requiredString("access_token")
            val refresh = json.requiredString("refresh_token")
            require(json.requiredString("token_type").equals("Bearer", ignoreCase = true))
            val seconds = (json["expires_in"] as? JsonPrimitive)?.longOrNull
            require(seconds != null && seconds > 0)
            // Quando scope é omitido, OAuth define os escopos como os originalmente solicitados.
            if ("scope" in json) require(DRIVE_SCOPE in json.requiredString("scope").split(' '))
            OAuthCredentials(access, refresh, requestedAt.plusSeconds(seconds))
        } catch (_: Exception) {
            throw OAuthException(
                OAuthFailure.INVALID_TOKEN_RESPONSE,
                "Resposta de credenciais incompleta ou inválida. Autorize novamente com acesso offline e ao Drive.",
            )
        }
    }
}

/** Codifica parâmetros UTF-8 tanto da URL de autorização quanto do POST, preservando caracteres especiais. */
internal fun encodeForm(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
    "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
}
