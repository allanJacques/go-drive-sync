package com.godrivesync.data.models

import java.time.LocalDateTime

/**
 * Fotografia imutável de uma conta, independente de uma conexão ou da UI.
 * Como um DTO Java, transporta valores; `copy` cria outra fotografia, sem gravar no banco.
 * Datas seguem o esquema existente: data/hora sem fuso, não instantes UTC.
 *
 * @property id Identidade gerada pelo H2; null representa uma conta ainda não persistida.
 * @property email E-mail único, preservado exatamente como recebido, sem normalização.
 * @property accessToken Conteúdo já criptografado pela camada chamadora; não passar token real puro.
 * @property refreshToken Renovação também já criptografada; este modelo não cifra nem decifra.
 * @property tokenExpiry Expiração do token de acesso.
 * @property isDefault Indica a preferência inicial; o repositório coordena a unicidade.
 * @property lastFullSync Última sincronização completa, ou null se ainda não ocorreu.
 * @property createdAt Null antes de inserir usa o relógio do banco; atualizações preservam a data original.
 */
data class Account(
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiry: LocalDateTime,
    val id: Int? = null,
    val isDefault: Boolean = false,
    val lastFullSync: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null,
) {
    /** Evita que o toString gerado pela data class exponha tokens em logs e depuradores. */
    override fun toString(): String =
        "Account(id=$id, email=$email, accessToken=<omitido>, refreshToken=<omitido>, " +
            "tokenExpiry=$tokenExpiry, isDefault=$isDefault, lastFullSync=$lastFullSync, createdAt=$createdAt)"
}
