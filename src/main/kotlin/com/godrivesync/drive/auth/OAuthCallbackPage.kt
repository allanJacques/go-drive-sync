package com.godrivesync.drive.auth

import java.util.Base64

/**
 * Estados visuais do callback, com textos controlados pelo aplicativo.
 * RECEIVED confirma somente o recebimento; a troca do código por tokens acontece depois.
 * @property title Título da página e do cartão, acessível sem depender de cor ou ícone.
 * @property message Explicação do estado para o usuário, sem dados recebidos na URL.
 * @property guidance Próximo passo disponível fora do navegador.
 * @property symbol Símbolo decorativo; o título transmite o significado aos leitores de tela.
 */
internal enum class OAuthCallbackState(
    val title: String,
    val message: String,
    val guidance: String,
    val symbol: String,
) {
    RECEIVED(
        "Resposta recebida",
        "O Go Drive Sync recebeu sua resposta do Google. Volte ao aplicativo para conferir a conclusão da conexão.",
        "Você já pode fechar esta aba.",
        "↗",
    ),
    DENIED(
        "Autorização não concedida",
        "O Google retornou sem autorizar esta conexão. Volte ao aplicativo para iniciar uma nova tentativa quando quiser.",
        "Você pode fechar esta aba e tentar novamente pelo aplicativo.",
        "−",
    ),
    INVALID(
        "Retorno inválido",
        "Não foi possível validar esta resposta. Volte à aba de autorização do Google para concluir a tentativa ou confira o aplicativo.",
        "Este retorno não concluiu a conexão.",
        "!",
    ),
    ALREADY_RECEIVED(
        "Resposta já recebida",
        "Esta tentativa já recebeu uma resposta. Volte ao Go Drive Sync para conferir o resultado da conexão.",
        "Não é necessário atualizar esta página. Você pode fechar esta aba.",
        "↩",
    ),
    NOT_FOUND(
        "Página não encontrada",
        "Este endereço não corresponde ao retorno de autorização. Volte ao aplicativo para continuar.",
        "Você pode fechar esta aba.",
        "?",
    ),
}

/**
 * Monta HTML independente de Compose com o logo existente e a paleta Aurora.
 * Template e PNG são carregados uma vez do classpath (inclusive no JAR), antes de abrir o listener.
 * CSS e imagens ficam embutidos: a página não precisa buscar recursos após o servidor encerrar.
 */
internal object OAuthCallbackPage {
    /** Marcadores do recurso só recebem constantes locais; nenhum parâmetro OAuth é interpolado. */
    private val template = resourceBytes("/oauth/callback.html").toString(Charsets.UTF_8)

    /** Data URI reutiliza o PNG da aplicação sem adicionar endpoint de imagem nem acesso externo. */
    private val logo = "data:image/png;base64," + Base64.getEncoder().encodeToString(resourceBytes("/icons/app-icon.png"))

    /** Retorna o documento completo para [state], sem código, state OAuth, tokens ou descrições remotas. */
    fun render(state: OAuthCallbackState): String = template
        .replace("{{LOGO}}", logo)
        .replace("{{TITLE}}", state.title)
        .replace("{{MESSAGE}}", state.message)
        .replace("{{GUIDANCE}}", state.guidance)
        .replace("{{SYMBOL}}", state.symbol)

    /** Lê recurso obrigatório e fecha o stream como try-with-resources em Java. */
    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Recurso da página OAuth ausente: $path" }
            .use { it.readAllBytes() }
}
