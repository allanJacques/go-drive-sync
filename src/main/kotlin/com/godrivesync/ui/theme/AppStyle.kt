package com.godrivesync.ui.theme

/**
 * Catálogo dos sete temas. id é a chave persistida e deve permanecer estável.
 * label e description são textos de exibição e podem mudar sem invalidar a preferência salva.
 */
enum class AppStyle(val id: String, val label: String, val description: String) {
    OBSIDIAN("obsidian", "Obsidiana", "Escuro · grafite e roxo"),
    AURORA("aurora", "Aurora", "Escuro · violeta translúcido"),
    TIDE("tide", "Maré", "Escuro · verde azulado"),
    OBSIDIAN_LIGHT("obsidian-light", "Obsidiana Clara", "Claro · cinza e roxo"),
    AURORA_LIGHT("aurora-light", "Aurora Clara", "Claro · lavanda translúcida"),
    TIDE_LIGHT("tide-light", "Maré Clara", "Claro · menta e turquesa"),
    TANGERINE("tangerine", "Tangerina", "Colorido · laranja e ameixa"),
    ;

    companion object {
        /** Aceita ausência ou identificador antigo/desconhecido, usando Aurora como padrão. */
        fun fromId(id: String?): AppStyle = entries.firstOrNull { it.id == id } ?: AURORA
    }
}
