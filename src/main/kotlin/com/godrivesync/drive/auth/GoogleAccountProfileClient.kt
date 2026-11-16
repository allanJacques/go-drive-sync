package com.godrivesync.drive.auth

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.godrivesync.data.models.SavedAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Perfil visual sem credenciais; a foto é opcional e sua URL nunca recebe o Bearer da API. */
data class GoogleAccountProfile(val photoLink: String?)

/** Consulta o perfil com o manager compartilhado, renovando acesso sem abrir o navegador. */
class GoogleAccountProfileClient internal constructor(
    private val credentials: AccountCredentialManager,
    private val endpoint: String,
) {
    /** Usa somente a origem oficial em produção; o endpoint interno permite testes HTTP locais. */
    constructor(credentials: AccountCredentialManager) : this(credentials,
        "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,photoLink)")

    /** Confere a identidade salva e retorna foto opcional; o SDK e JDBC executam em IO no manager. */
    suspend fun load(account: SavedAccount): GoogleAccountProfile = credentials.execute(account.id) { initializer ->
        val response = NetHttpTransport().createRequestFactory(initializer)
            .buildGetRequest(GenericUrl(endpoint)).execute()
        try {
            val user = (Json.parseToJsonElement(response.parseAsString()) as JsonObject)["user"] as JsonObject
            if (user.requiredString("emailAddress") != account.email) throw IllegalArgumentException()
            GoogleAccountProfile((user["photoLink"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() })
        } catch (_: Exception) {
            throw CredentialException(CredentialFailure.INVALID_RESPONSE,
                "Não foi possível confirmar o perfil desta conta.")
        } finally {
            response.disconnect()
        }
    }
}
