package com.godrivesync.data.models

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

/**
 * Dados seguros para a lista de contas: nenhum token, nem mesmo criptografado, sai na listagem.
 * Como um DTO Java, não é estado observável do Compose; LoginViewModel publica o resultado em StateFlow.
 * @property id Identidade persistente da conta, usada para recuperar as credenciais em outro método.
 * @property email Endereço fornecido pelo Drive da conta autorizada.
 * @property expiresAt Expiração UTC do access token, não do refresh token nem da concessão Google.
 * @property isDefault Preferência visual persistida, sem exclusividade de conexão.
 * @property lastFullSync Última sincronização completa no horário local do banco; null quando nunca ocorreu.
 */
data class SavedAccount(
    val id: Int,
    val email: String,
    val expiresAt: Instant,
    val isDefault: Boolean,
    val lastFullSync: LocalDateTime? = null,
) {
    /** Considera vencido inclusive no instante exato do limite; apenas informa, sem renovar via rede. */
    fun isExpired(clock: Clock = Clock.systemUTC()): Boolean = !expiresAt.isAfter(clock.instant())
}
