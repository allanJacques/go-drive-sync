package com.godrivesync.drive.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Escopo definido no PLAN.md para sincronizar pastas existentes da conta autorizada. */
internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

/** Endpoints fixos impedem que um JSON alterado redirecione códigos e segredos a outro serviço. */
internal const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
internal const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

/**
 * Configuração Desktop lida fora do classpath. Não usa data class para não gerar toString com segredo.
 * @property clientId Identificação pública do aplicativo no Google.
 * @property clientSecret Campo fornecido no JSON Desktop; não substitui PKCE em clientes públicos.
 */
internal class OAuthConfiguration(val clientId: String, val clientSecret: String) {
    companion object {
        /**
         * Lê [path] e aceita apenas o formato installed e endpoints conhecidos do Google.
         * É bloqueante; o manager chama em Dispatchers.IO, como trabalho de disco em SwingWorker.
         * Falhas de arquivo/JSON são substituídas por texto local, sem incluir o conteúdo do arquivo.
         */
        fun load(path: Path): OAuthConfiguration = try {
            val root = Json.parseToJsonElement(Files.readString(path)) as JsonObject
            require("web" !in root && "type" !in root)
            val installed = root["installed"] as JsonObject
            val id = installed.requiredString("client_id")
            val secret = installed.requiredString("client_secret")
            // O JSON baixado pelo Console também pode trazer a URL histórica sem /v2.
            // Em ambos os casos a requisição usa a URL v2 fixa, nunca uma URL arbitrária do arquivo.
            require(installed.requiredString("auth_uri") in setOf(
                AUTHORIZATION_ENDPOINT, "https://accounts.google.com/o/oauth2/auth",
            ))
            require(installed.requiredString("token_uri") == TOKEN_ENDPOINT)
            OAuthConfiguration(id, secret)
        } catch (_: Exception) {
            throw OAuthException(
                OAuthFailure.CONFIGURATION,
                "Não foi possível ler uma credencial Desktop válida. Confira o credentials.json externo.",
            )
        }
    }
}

/** Extrai um texto JSON não vazio, rejeitando números, null e campos ausentes. */
internal fun JsonObject.requiredString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString && value.content.isNotBlank())
    return value.content
}
