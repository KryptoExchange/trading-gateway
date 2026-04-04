package ru.krypto.gateway.model

data class DepthResponse(
    val lastUpdateId: Long,
    val bids: List<List<String>>,
    val asks: List<List<String>>
)
