package com.godrivesync.drive.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Falha de integração com mensagem local, sem resposta HTTP, SQL, tokens ou causas remotas anexadas. */
class AccountAuthorizationException(message: String) : Exception(message)

/**
 * Identifica a conta pelo Drive com o escopo drive já autorizado; não exige novo escopo de perfil.
 * O endpoint é substituível internamente nos testes. Produção sempre usa HTTPS com redirects desativados.
 */
internal class GoogleAccountClient(
    private val endpoint: URI = URI("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)"),
) {
    /** Consulta o e-mail com Bearer em cabeçalho, sem token na URL; cancelamento aborta a requisição. */
    suspend fun email(accessToken: String): String {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        return try {
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken").GET().build()
            val response = suspendCancellableCoroutine<HttpResponse<String>> { continuation ->
                val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                continuation.invokeOnCancellation { pending.cancel(true) }
                pending.whenComplete { value, failure ->
                    if (failure != null) continuation.resumeWithException(identityFailure())
                    else continuation.resume(value)
                }
            }
            check(response.statusCode() == 200)
            val user = (Json.parseToJsonElement(response.body()) as JsonObject)["user"] as JsonObject
            user.requiredString("emailAddress").also {
                require(it.length <= 255 && '@' in it && it.none { char -> char.isWhitespace() || char.isISOControl() })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw identityFailure()
        } finally {
            client.shutdownNow()
        }
    }

    /** Mensagem uniforme impede que erro de rede ou JSON malformado exponha dados remotos. */
    private fun identityFailure() = AccountAuthorizationException("Não foi possível identificar o e-mail da conta no Google Drive. Nenhuma credencial foi salva.")
}
