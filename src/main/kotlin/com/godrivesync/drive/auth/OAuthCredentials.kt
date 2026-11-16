package com.godrivesync.drive.auth

import java.time.Instant

/**
 * Resultado transitório da autorização, semelhante a um DTO Java, sem gravar no disco.
 * AccountAuthorizationService criptografa os dois tokens antes de persistir e também devolve este
 * tipo ao recuperar credenciais. É uma fotografia; AccountCredentialManager coordena a sessão renovável.
 *
 * @property accessToken Token de acesso em memória; nunca deve aparecer em logs.
 * @property refreshToken Token usado na renovação, também sensível.
 * @property expiresAt Instante UTC de expiração, independente do fuso configurado na máquina.
 */
class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
) {
    /** Permite inspecionar o resultado sem revelar os tokens, inclusive no teste manual. */
    override fun toString(): String = "OAuthCredentials(tokens=<omitidos>, expiresAt=$expiresAt)"
}

/** Categorias estáveis para a futura UI traduzir falhas sem exibir respostas sensíveis do servidor. */
enum class OAuthFailure {
    CONFIGURATION, BROWSER, CALLBACK_SERVER, DENIED, TIMEOUT, TOKEN_EXCHANGE, INVALID_TOKEN_RESPONSE,
}

/**
 * Falha com mensagem local controlada, sem causa remota, URLs de autorização ou conteúdo de tokens.
 * @property reason Categoria que dispensa interpretar o texto para decidir o comportamento da UI.
 */
class OAuthException(val reason: OAuthFailure, message: String) : Exception(message)
