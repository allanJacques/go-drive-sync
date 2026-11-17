package com.godrivesync.ui.screens.login

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Baixa somente fotos públicas do Google, sem Authorization e sem seguir redirects.
 * Limites de bytes, dimensões e tempo impedem uma foto de consumir recursos ilimitados.
 * O ViewModel mantém o bitmap pequeno em memória durante a janela; nenhum arquivo é salvo.
 */
class AccountPhotoLoader {
    /** Retorna miniatura de até 96 px em IO; ausência/falha é convertida em iniciais pelo chamador. */
    suspend fun load(link: String): ImageBitmap = withContext(Dispatchers.IO) {
        val uri = URI(link)
        require(isGooglePhoto(uri))
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        try {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()
            // O subscriber limita o corpo durante o download, antes de acumular todos os bytes.
            val response = client.send(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), 1_048_576L))
            ensureActive()
            require(response.statusCode() == 200)
            decode(response.body())
        } finally {
            client.shutdownNow()
        }
    }

    /** Aceita HTTPS dos hosts de fotos conhecidos; rejeita credenciais/portas e domínios parecidos. */
    internal fun isGooglePhoto(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            listOf("googleusercontent.com", "ggpht.com").any { host == it || host.endsWith(".$it") }
    }

    /** Inspeciona tamanho antes de decodificar e reduz a miniatura fora da thread Compose. */
    internal fun decode(bytes: ByteArray): ImageBitmap {
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            requireNotNull(input)
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..2048 && height in 1..2048)
                val source = reader.read(0)
                val scale = minOf(96.0 / width, 96.0 / height, 1.0)
                val target = BufferedImage(maxOf(1, (width * scale).toInt()),
                    maxOf(1, (height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
                val graphics = target.createGraphics()
                try { graphics.drawImage(source, 0, 0, target.width, target.height, null) }
                finally { graphics.dispose() }
                return target.toComposeImageBitmap()
            } finally { reader.dispose() }
        }
    }
}
