package com.godrivesync.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.godrivesync.ui.components.AppIcons

/**
 * Destinos conhecidos da aplicação, com os dados usados pela sidebar e pelo cabeçalho.
 * Adicionar um destino exige também tratá-lo no when de DestinationScreen; o compilador ajuda nisso.
 */
enum class AppDestination(val label: String, val subtitle: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", "Seu Drive e suas pastas, em um só lugar.", AppIcons.Dashboard),
    SYNC("Sincronizar", "Acompanhe as transferências entre a nuvem e o computador.", AppIcons.Sync),
    HISTORY("Histórico", "Consulte as operações realizadas nas suas pastas.", AppIcons.History),
    MAPPINGS("Mapeamentos", "Organize as conexões entre suas pastas locais e o Drive.", AppIcons.Folder),
    ACCOUNTS("Contas", "Um espaço para todas as suas contas Google.", AppIcons.Accounts),
    SETTINGS("Configurações", "Deixe o Go Drive Sync do seu jeito.", AppIcons.Settings),
}
