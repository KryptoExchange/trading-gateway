package ru.krypto.gateway.model

data class BalanceInfo(
    val asset: String,
    val free: String,
    val locked: String
)

data class AccountResponse(
    val balances: List<BalanceInfo>
)
