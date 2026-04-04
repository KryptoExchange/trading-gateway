package ru.krypto.gateway.model

data class TradeResponse(
    val id: String,
    val price: String,
    val qty: String,
    val time: Long,
    val isBuyerMaker: Boolean
)
