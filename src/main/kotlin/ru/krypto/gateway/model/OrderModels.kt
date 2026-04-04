package ru.krypto.gateway.model

data class CreateOrderRequest(
    val symbol: String,
    val side: String,
    val type: String,
    val quantity: String,
    val price: String? = null,
    val timeInForce: String = "GTC",
    val newClientOrderId: String? = null
)

data class OrderResponse(
    val symbol: String,
    val orderId: String,
    val clientOrderId: String? = null,
    val transactTime: Long? = null,
    val price: String,
    val origQty: String,
    val executedQty: String? = null,
    val status: String,
    val type: String,
    val side: String,
    val timeInForce: String? = null,
    val time: Long? = null,
    val updateTime: Long? = null
)

data class CancelOrderResponse(
    val symbol: String,
    val orderId: String,
    val status: String
)
