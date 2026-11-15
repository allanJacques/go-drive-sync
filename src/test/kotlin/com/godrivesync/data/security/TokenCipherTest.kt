package com.godrivesync.data.security

import org.junit.Test
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/** Exercita confidencialidade do envelope, autenticação e recusa de formatos antigos, com chaves fictícias. */
class TokenCipherTest {
    /** Chave fixa de teste; produção sempre usa SecureRandom e nunca compartilha este valor. */
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    /** Mesmo texto/contexto gera cifras diferentes e ambos retornam o token original. */
    @Test
    fun `cifra e recupera com IV distinto`() {
        val cipher = TokenCipher(key)
        val first = cipher.encrypt("token-ficticio-ç", "conta:acesso")
        val second = cipher.encrypt("token-ficticio-ç", "conta:acesso")
        assertNotEquals(first, second)
        assertFalse(first.contains("token-ficticio"))
        assertEquals("token-ficticio-ç", cipher.decrypt(first, "conta:acesso"))
        assertEquals("token-ficticio-ç", cipher.decrypt(second, "conta:acesso"))
    }

    /** Alterar um byte, o contexto ou a chave invalida a tag; não há retorno parcial do plaintext. */
    @Test
    fun `recusa adulteracao troca de conta finalidade e chave`() {
        val cipher = TokenCipher(key)
        val envelope = cipher.encrypt("segredo-ficticio", "conta:acesso")
        val bytes = Base64.getDecoder().decode(envelope.substring(3))
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        for ((decryptor, value, context) in listOf(
            Triple(cipher, "v1:" + Base64.getEncoder().encodeToString(bytes), "conta:acesso"),
            Triple(cipher, envelope, "outra:acesso"),
            Triple(cipher, envelope, "conta:renovacao"),
            Triple(TokenCipher(SecretKeySpec(ByteArray(32) { 42 }, "AES")), envelope, "conta:acesso"),
        )) {
            val failure = assertFailsWith<TokenProtectionException> { decryptor.decrypt(value, context) }
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("segredo-ficticio"))
        }
    }

    /** Valores legados, versões não suportadas e envelopes truncados nunca são aceitos como credenciais. */
    @Test
    fun `recusa texto puro versao e base64 invalidos`() {
        for (value in listOf("token-ficticio", "v2:abc", "v1:???", "v1:", "v1:AA==")) {
            assertFailsWith<TokenProtectionException> { TokenCipher(key).decrypt(value, "contexto") }
        }
        assertFailsWith<TokenProtectionException> { TokenCipher(key).encrypt(" ", "contexto") }
    }
}
