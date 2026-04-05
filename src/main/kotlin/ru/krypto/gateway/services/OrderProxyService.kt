package ru.krypto.gateway.services

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import ru.krypto.gateway.config.AppConfig
import ru.krypto.gateway.model.CancelOrderResponse
import ru.krypto.gateway.model.CreateOrderRequest
import ru.krypto.gateway.model.OrderResponse

class OrderProxyService(
    private val httpClient: HttpClient,
    private val appConfig: AppConfig,
    private val exchangeInfoService: ExchangeInfoService
) {
    private val logger = LoggerFactory.getLogger(OrderProxyService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val orderbookBaseUrl get() = appConfig.orderbook.baseUrl

    /**
     * Convert Binance-style symbol (BTCUSDT) to internal format (BTC/USDT).
     * Uses exchangeInfo to find the correct base/quote split.
     */
    private suspend fun toInternalSymbol(symbol: String): String {
        if (symbol.contains("/")) return symbol

        val info = exchangeInfoService.getExchangeInfo()
        val matched = info.symbols.firstOrNull { it.symbol.replace("/", "") == symbol }
        if (matched != null) {
            return "${matched.baseAsset}/${matched.quoteAsset}"
        }

        val knownQuotes = listOf("USDT", "RUB", "BTC", "ETH", "TRX")
        for (quote in knownQuotes) {
            if (symbol.endsWith(quote) && symbol.length > quote.length) {
                return "${symbol.substring(0, symbol.length - quote.length)}/$quote"
            }
        }
        return symbol
    }

    suspend fun createOrder(request: CreateOrderRequest, apiKey: String): OrderResponse {
        val side = when (request.side.uppercase()) {
            "BUY" -> "BID"
            "SELL" -> "ASK"
            else -> request.side
        }

        val internalSymbol = toInternalSymbol(request.symbol)

        val body = buildMap<String, Any?> {
            put("tradingPair", internalSymbol)
            put("side", side)
            put("executionType", request.type.uppercase())
            put("quantity", PrecisionService.decimalStringToBigInteger(request.quantity).toString())
            if (request.price != null && request.type.uppercase() == "LIMIT") {
                put("price", PrecisionService.decimalStringToBigInteger(request.price).toString())
            }
            put("timeInForce", request.timeInForce)
            if (request.newClientOrderId != null) {
                put("clientOrderId", request.newClientOrderId)
            }
            put("marketType", "SPOT")
            put("isMarketMaker", true)
        }

        val response = httpClient.post("$orderbookBaseUrl/trade/orders") {
            header("X-API-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val responseBody = response.bodyAsText()

        if (response.status != HttpStatusCode.OK) {
            throw GatewayException(response.status, "Order creation failed: $responseBody")
        }

        // orderbook-service returns the order UUID as a plain JSON string
        val orderId = responseBody.trim().removeSurrounding("\"")

        return OrderResponse(
            symbol = request.symbol,
            orderId = orderId,
            clientOrderId = request.newClientOrderId,
            transactTime = System.currentTimeMillis(),
            price = request.price ?: "0",
            origQty = request.quantity,
            executedQty = "0",
            status = "NEW",
            type = request.type.uppercase(),
            side = request.side.uppercase(),
            timeInForce = request.timeInForce
        )
    }

    suspend fun cancelOrder(symbol: String, orderId: String, apiKey: String): CancelOrderResponse {
        val response = httpClient.delete("$orderbookBaseUrl/trade/orders/$orderId") {
            header("X-API-Key", apiKey)
        }

        val responseBody = response.bodyAsText()

        if (response.status != HttpStatusCode.OK) {
            throw GatewayException(response.status, "Order cancellation failed: $responseBody")
        }

        return CancelOrderResponse(
            symbol = symbol,
            orderId = orderId,
            status = "CANCELED"
        )
    }

    suspend fun getOrder(symbol: String, orderId: String, apiKey: String): OrderResponse {
        val response = httpClient.get("$orderbookBaseUrl/trade/orders/$orderId") {
            header("X-API-Key", apiKey)
        }

        val responseBody = response.bodyAsText()

        if (response.status != HttpStatusCode.OK) {
            throw GatewayException(response.status, "Order query failed: $responseBody")
        }

        val orderView = mapper.readTree(responseBody)

        val internalSide = orderView.path("side").asText("")
        val side = when (internalSide) {
            "BID" -> "BUY"
            "ASK" -> "SELL"
            else -> internalSide
        }

        val price = orderView.path("price").asText("0")
        val quantity = orderView.path("quantity").asText("0")

        return OrderResponse(
            symbol = orderView.path("tradingPair").asText(symbol),
            orderId = orderView.path("uuid").asText(orderId),
            clientOrderId = orderView.path("clientOrderId").asText(null),
            price = price,
            origQty = PrecisionService.longToDecimalString(quantity.toLongOrNull() ?: 0),
            executedQty = null,
            status = mapOrderStatus(orderView.path("status").asText("NEW")),
            type = orderView.path("typeExecution").asText("LIMIT").uppercase(),
            side = side,
            timeInForce = orderView.path("timeInForce").asText("GTC"),
            time = try {
                java.time.Instant.parse(orderView.path("issueDate").asText()).toEpochMilli()
            } catch (e: Exception) {
                null
            },
            updateTime = System.currentTimeMillis()
        )
    }

    suspend fun searchOrders(symbol: String, apiKey: String, status: String? = null, limit: Int = 50): List<OrderResponse> {
        val internalSymbol = toInternalSymbol(symbol)
        val body = buildMap<String, Any?> {
            put("tradingPair", internalSymbol)
            if (status != null) put("status", status)
            put("limit", limit)
        }

        val response = httpClient.post("$orderbookBaseUrl/trade/orders/search") {
            header("X-API-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        if (response.status != HttpStatusCode.OK) return emptyList()

        val responseBody = response.bodyAsText()
        val orders = mapper.readTree(responseBody)

        return if (orders.isArray) {
            orders.map { orderView ->
                val internalSide = orderView.path("side").asText("")
                val side = when (internalSide) {
                    "BID" -> "BUY"
                    "ASK" -> "SELL"
                    else -> internalSide
                }
                OrderResponse(
                    symbol = orderView.path("tradingPair").asText(symbol),
                    orderId = orderView.path("uuid").asText(),
                    clientOrderId = orderView.path("clientOrderId").asText(null),
                    price = orderView.path("price").asText("0"),
                    origQty = PrecisionService.longToDecimalString(
                        orderView.path("quantity").asText("0").toLongOrNull() ?: 0
                    ),
                    status = mapOrderStatus(orderView.path("status").asText("NEW")),
                    type = orderView.path("typeExecution").asText("LIMIT").uppercase(),
                    side = side,
                    timeInForce = orderView.path("timeInForce").asText("GTC")
                )
            }
        } else emptyList()
    }

    companion object {
        fun mapOrderStatus(internal: String): String {
            return when (internal.uppercase()) {
                "NEW" -> "NEW"
                "ACCEPTED" -> "NEW"
                "OPEN" -> "NEW"
                "PARTIALLY_FILLED" -> "PARTIALLY_FILLED"
                "FILLED" -> "FILLED"
                "CANCELED" -> "CANCELED"
                "REJECTED" -> "REJECTED"
                "EXPIRED" -> "EXPIRED"
                "FAILED" -> "REJECTED"
                "CANCEL_PENDING" -> "PENDING_CANCEL"
                else -> internal.uppercase()
            }
        }
    }
}

class GatewayException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)
