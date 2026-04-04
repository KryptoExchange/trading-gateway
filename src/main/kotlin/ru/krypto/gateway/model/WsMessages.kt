package ru.krypto.gateway.model

import com.fasterxml.jackson.annotation.JsonProperty

data class WsRequest(
    val method: String,
    val params: List<String> = emptyList(),
    val id: Long? = null
)

data class WsResponse(
    val result: Any?,
    val id: Long?
)

data class DepthUpdateEvent(
    @JsonProperty("e") val eventType: String = "depthUpdate",
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("U") val firstUpdateId: Long,
    @JsonProperty("u") val lastUpdateId: Long,
    @JsonProperty("b") val bids: List<List<String>>,
    @JsonProperty("a") val asks: List<List<String>>
)

data class TradeEvent(
    @JsonProperty("e") val eventType: String = "trade",
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("t") val tradeId: String,
    @JsonProperty("p") val price: String,
    @JsonProperty("q") val quantity: String,
    @JsonProperty("T") val tradeTime: Long,
    @JsonProperty("m") val isBuyerMaker: Boolean
)

data class ExecutionReportEvent(
    @JsonProperty("e") val eventType: String = "executionReport",
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("c") val clientOrderId: String?,
    @JsonProperty("S") val side: String,
    @JsonProperty("o") val orderType: String,
    @JsonProperty("q") val quantity: String,
    @JsonProperty("p") val price: String,
    @JsonProperty("X") val orderStatus: String,
    @JsonProperty("i") val orderId: String,
    @JsonProperty("l") val lastFilledQty: String?,
    @JsonProperty("L") val lastFilledPrice: String?,
    @JsonProperty("n") val commission: String?,
    @JsonProperty("N") val commissionAsset: String?,
    @JsonProperty("T") val tradeTime: Long
)

data class AccountUpdateEvent(
    @JsonProperty("e") val eventType: String = "outboundAccountPosition",
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("B") val balances: List<AccountUpdateBalance>
)

data class AccountUpdateBalance(
    @JsonProperty("a") val asset: String,
    @JsonProperty("f") val free: String,
    @JsonProperty("l") val locked: String
)
