package ru.krypto.gateway.model

data class MyTradeResponse(
    val id: String,
    val orderId: String,
    val symbol: String,
    val price: String,
    val qty: String,
    val commission: String,
    val commissionAsset: String,
    val time: Long,
    val isMaker: Boolean,
    val isBuyer: Boolean
)
