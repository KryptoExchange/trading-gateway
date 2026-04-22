package ru.krypto.gateway.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import ru.krypto.gateway.auth.getApiKey
import ru.krypto.gateway.auth.getUserId
import ru.krypto.gateway.auth.requireApiKeyPermission
import ru.krypto.gateway.model.CreateOrderRequest
import ru.krypto.gateway.services.BalanceProxyService
import ru.krypto.gateway.services.GatewayException
import ru.krypto.gateway.services.OrderProxyService
import ru.krypto.gateway.services.RateLimitService
import ru.krypto.gateway.services.TradeCacheService

fun Route.tradingRoutes() {
    val orderProxyService: OrderProxyService by inject()
    val balanceProxyService: BalanceProxyService by inject()
    val tradeCacheService: TradeCacheService by inject()
    val rateLimitService: RateLimitService by inject()

    authenticate("auth-api-key") {
        // POST /api/v1/order
        post("/order") {
            if (!requireApiKeyPermission("TRADE")) return@post

            val apiKey = getApiKey() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing API key"))
                return@post
            }

            // Check order rate limit (10/sec + 100k/day)
            val orderLimit = rateLimitService.checkOrderLimit(apiKey)
            if (!orderLimit.allowed) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("code" to -1015, "msg" to "Too many order requests"))
                return@post
            }

            val request = call.receive<CreateOrderRequest>()

            if (request.symbol.isBlank() || request.side.isBlank() || request.type.isBlank() || request.quantity.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Missing required parameters"))
                return@post
            }

            if (request.type.uppercase() == "LIMIT" && request.price.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Price required for LIMIT orders"))
                return@post
            }

            try {
                val response = orderProxyService.createOrder(request, apiKey)
                call.respond(response)
            } catch (e: GatewayException) {
                call.respond(e.status, mapOf("code" to -1000, "msg" to e.message))
            }
        }

        // DELETE /api/v1/order?symbol=BTCUSDT&orderId=<uuid>
        delete("/order") {
            if (!requireApiKeyPermission("TRADE")) return@delete

            val apiKey = getApiKey() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing API key"))
                return@delete
            }

            // Check order rate limit (10/sec + 100k/day)
            val orderLimit = rateLimitService.checkOrderLimit(apiKey)
            if (!orderLimit.allowed) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("code" to -1015, "msg" to "Too many order requests"))
                return@delete
            }

            val symbol = call.request.queryParameters["symbol"] ?: ""
            val orderId = call.request.queryParameters["orderId"]
            if (orderId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Missing parameter: orderId"))
                return@delete
            }

            try {
                val response = orderProxyService.cancelOrder(symbol, orderId, apiKey)
                call.respond(response)
            } catch (e: GatewayException) {
                val code = if (e.status == HttpStatusCode.Conflict || e.status == HttpStatusCode.NotFound) -2011 else -1000
                val msg = if (code == -2011) "Unknown order sent" else (e.message ?: "Order cancellation failed")
                call.respond(e.status, mapOf("code" to code, "msg" to msg))
            }
        }

        // GET /api/v1/order?symbol=BTCUSDT&orderId=<uuid>
        get("/order") {
            if (!requireApiKeyPermission("READ")) return@get

            val apiKey = getApiKey() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing API key"))
                return@get
            }

            val symbol = call.request.queryParameters["symbol"] ?: ""
            val orderId = call.request.queryParameters["orderId"]
            if (orderId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Missing parameter: orderId"))
                return@get
            }

            try {
                val response = orderProxyService.getOrder(symbol, orderId, apiKey)
                call.respond(response)
            } catch (e: GatewayException) {
                val code = if (e.status == HttpStatusCode.NotFound) -2013 else -1000
                val msg = if (code == -2013) "Order does not exist" else (e.message ?: "Order query failed")
                call.respond(e.status, mapOf("code" to code, "msg" to msg))
            }
        }

        // GET /api/v1/myTrades?symbol=BTCUSDT&limit=50&orderId=<id>
        get("/myTrades") {
            if (!requireApiKeyPermission("READ")) return@get

            val userId = getUserId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@get
            }

            val symbol = call.request.queryParameters["symbol"]
            if (symbol.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Missing parameter: symbol"))
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val orderId = call.request.queryParameters["orderId"]?.takeIf { it.isNotBlank() }

            val trades = tradeCacheService.getMyTrades(userId, symbol, limit.coerceIn(1, 1000), orderId)
            call.respond(trades)
        }

        // GET /api/v1/account
        get("/account") {
            if (!requireApiKeyPermission("READ")) return@get

            val apiKey = getApiKey() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing API key"))
                return@get
            }

            try {
                val response = balanceProxyService.getBalances(apiKey)
                call.respond(response)
            } catch (e: GatewayException) {
                call.respond(e.status, mapOf("code" to -1000, "msg" to e.message))
            }
        }
    }
}
