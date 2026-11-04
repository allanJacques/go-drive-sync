package com.godrivesync.data.preferences

import com.godrivesync.ui.theme.AppStyle
import java.util.prefs.Preferences

/**
 * Persistência pequena usando java.util.prefs, a mesma API disponível em aplicações Swing.
 * Guarda somente a aparência no perfil do usuário; não utiliza H2 nem credenciais Google.
 */
class AppearancePreferences {
    // lazy abre o nó apenas no primeiro uso. O caminho identifica as preferências do app.
    private val node by lazy { Preferences.userRoot().node("com/godrivesync/appearance") }

    /** Lê a escolha salva. Falhas de leitura e valores desconhecidos retornam Aurora. */
    fun loadStyle(): AppStyle = runCatching {
        AppStyle.fromId(node.get("theme", null))
    }.getOrDefault(AppStyle.AURORA)

    /**
     * Grava o identificador estável e solicita persistência com flush.
     * Pode lançar exceção de armazenamento: Main executa em Dispatchers.IO e mostra a falha.
     */
    fun saveStyle(style: AppStyle) {
        node.put("theme", style.id)
        node.flush()
    }
}
