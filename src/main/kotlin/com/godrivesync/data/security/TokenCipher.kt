package com.godrivesync.data.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Falha local sem causa ou conteúdo sensível; pode ser apresentada pelo diagnóstico ou pela futura UI. */
class TokenProtectionException(message: String) : Exception(message)

/**
 * Cifra tokens com AES-256-GCM autenticado usando somente a JCA do JDK, sem estado Cipher compartilhado.
 * A chave pertence ao armazenamento local; cada operação cria seu próprio IV aleatório de 96 bits.
 * O envelope v1 guarda IV + ciphertext + tag de 128 bits em Base64. Não é hash: a chave permite recuperar.
 */
class TokenCipher(private val key: SecretKey) {
    /**
     * Criptografa [token] e autentica [context], que identifica e-mail e finalidade do token.
     * Vincular esse contexto impede trocar cifras entre contas ou entre acesso e renovação.
     */
    fun encrypt(token: String, context: String): String = protectedOperation {
        require(token.isNotBlank())
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        "v1:" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    /** Recusa texto puro, versões desconhecidas, adulteração e chave/contexto incorretos, sem fallback. */
    fun decrypt(envelope: String, context: String): String = protectedOperation {
        require(envelope.startsWith("v1:"))
        val bytes = Base64.getDecoder().decode(envelope.substring(3))
        require(bytes.size > 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8).also { require(it.isNotBlank()) }
    }

    /** Traduz falhas criptográficas/validação sem expor valores, stack de bibliotecas ou causas sensíveis. */
    private fun <T> protectedOperation(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw TokenProtectionException("Não foi possível proteger ou recuperar as credenciais. Verifique a chave e autorize novamente se necessário.")
    }
}
