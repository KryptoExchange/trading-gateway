package ru.krypto.gateway.model

data class SymbolFilter(
    val filterType: String,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val tickSize: String? = null,
    val minQty: String? = null,
    val maxQty: String? = null,
    val stepSize: String? = null,
    val minNotional: String? = null
)

data class SymbolInfo(
    val symbol: String,
    val status: String,
    val baseAsset: String,
    val quoteAsset: String,
    val baseAssetPrecision: Int,
    val quoteAssetPrecision: Int,
    val orderTypes: List<String>,
    val filters: List<SymbolFilter>,
    val permissions: List<String>
)

data class ExchangeInfoResponse(
    val serverTime: Long,
    val symbols: List<SymbolInfo>
)
