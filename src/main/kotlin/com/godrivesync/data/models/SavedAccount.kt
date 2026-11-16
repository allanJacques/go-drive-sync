package com.godrivesync.data.models

import java.time.Clock
import java.time.Instant

/**
 * Dados seguros para a lista de contas: nenhum token, nem mesmo criptografado, sai na listagem.
 * Como um DTO Java, não é estado observável do Compose; LoginViewModel publica o resultado em StateFlow.
 * @property id Identidade persistente da conta, usada para recuperar as credenciais em outro método.
 * @property email Endereço fornecido pelo Drive da conta autorizada.
 * @property expiresAt Expiração UTC do access token, não do refresh token nem da concessão Google.
 * @property isDefault Preferência já mantida pelo repositório.
 */
data class SavedAccount(val id: Int, val email: String, val expiresAt: Instant, val isDefault: Boolean) {
    /** Considera vencido inclusive no instante exato do limite; apenas informa, sem renovar via rede. */
    fun isExpired(clock: Clock = Clock.systemUTC()): Boolean = !expiresAt.isAfter(clock.instant())
}
