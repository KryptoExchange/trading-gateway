package ru.krypto.gateway.model

data class TickerResponse(
    val symbol: String,
    val lastPrice: String,
    val bidPrice: String,
    val askPrice: String,
    val highPrice: String,
    val lowPrice: String,
    val volume: String,
    val quoteVolume: String,
    val openPrice: String,
    val openTime: Long,
    val closeTime: Long
)
