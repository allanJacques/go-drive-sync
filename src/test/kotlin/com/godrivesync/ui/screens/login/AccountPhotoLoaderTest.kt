package com.godrivesync.ui.screens.login

import org.junit.Test
import java.net.URI
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** Verifica limites de origem/decodificação com imagens fictícias, sem downloads do Google. */
class AccountPhotoLoaderTest {
    /** Hosts parecidos, HTTP, userinfo e porta arbitrária nunca são tratados como foto pública confiável. */
    @Test
    fun `restringe origem de foto sem repassar credenciais`() {
        val loader = AccountPhotoLoader()
        assertTrue(loader.isGooglePhoto(URI("https://lh3.googleusercontent.com/photo")))
        assertTrue(loader.isGooglePhoto(URI("https://lh3.ggpht.com/photo")))
        listOf("http://lh3.googleusercontent.com/photo", "https://googleusercontent.com.evil.invalid/photo",
            "https://evilgoogleusercontent.com/photo", "https://user@lh3.googleusercontent.com/photo",
            "https://lh3.googleusercontent.com:123/photo", "file:///etc/passwd").forEach {
            assertFalse(loader.isGooglePhoto(URI(it)), it)
        }
    }

    /** Miniatura preserva proporção; bytes inválidos e dimensões excessivas são recusados. */
    @Test
    fun `reduz imagem e rejeita conteudo invalido ou gigante`() {
        val loader = AccountPhotoLoader()
        val photo = loader.decode(png(300, 150))
        assertEquals(96, photo.width)
        assertEquals(48, photo.height)
        assertFails { loader.decode(byteArrayOf(0, 1, 2)) }
        assertFails { loader.decode(png(2049, 1)) }
    }

    /** Gera PNG somente em memória; não depende de avatar pessoal nem de artefato versionado. */
    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
        it.toByteArray()
    }
}
