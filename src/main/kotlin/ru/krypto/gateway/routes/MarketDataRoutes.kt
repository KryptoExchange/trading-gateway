package ru.krypto.gateway.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import ru.krypto.gateway.services.CrossPriceService
import ru.krypto.gateway.services.ExchangeInfoService
import ru.krypto.gateway.services.OrderBookCacheService
import ru.krypto.gateway.services.TickerCacheService
import ru.krypto.gateway.services.TradeCacheService

fun Route.marketDataRoutes() {
    val orderBookCacheService: OrderBookCacheService by inject()
    val tradeCacheService: TradeCacheService by inject()
    val tickerCacheService: TickerCacheService by inject()
    val exchangeInfoService: ExchangeInfoService by inject()
    val crossPriceService: CrossPriceService by inject()

    // GET /api/v1/time
    get("/time") {
        call.respond(mapOf("serverTime" to System.currentTimeMillis()))
    }

    // GET /api/v1/exchangeInfo
    get("/exchangeInfo") {
        try {
            val info = exchangeInfoService.getExchangeInfo()
            call.respond(info)
        } catch (e: IllegalStateException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("code" to -1003, "msg" to (e.message ?: "Service unavailable"))
            )
        }
    }

    // GET /api/v1/depth?symbol=BTCUSDT&limit=20
    get("/depth") {
        val symbol = call.request.queryParameters["symbol"]
        if (symbol.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Missing parameter: symbol"))
            return@get
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

        val depth = orderBookCacheService.getDepth(symbol, limit.coerceIn(1, 1000))
        if (depth != null) {
            call.respond(depth)
        } else {
            call.respond(HttpStatusCode.OK, mapOf(
                "lastUpdateId" to 0,
                "bids" to emptyList<Any>(),
                "asks" to emptyList<Any>()
            ))
        }
    }

    // GET /api/v1/trades?symbol=BTCUSDT&limit=50
    get("/trades") {
        val symbol = call.request.queryParameters["symbol"]
        if (symbol.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1100, "msg" to "Missing parameter: symbol"))
            return@get
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500

        val trades = tradeCacheService.getRecentTrades(symbol, limit.coerceIn(1, 1000))
        call.respond(trades)
    }

    // GET /api/v1/price/{symbol} — plain text price for Hummingbot custom_api
    get("/price/{symbol}") {
        val symbol = call.parameters["symbol"]
        if (symbol.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing symbol")
            return@get
        }
        val price = crossPriceService.getPrice(symbol)
        if (price != null) {
            call.respondText(price.toPlainString(), ContentType.Text.Plain)
        } else {
            call.respond(HttpStatusCode.NotFound, "Price not available for $symbol")
        }
    }

    // GET /api/v1/ticker/24hr?symbol=BTCUSDT
    get("/ticker/24hr") {
        val symbol = call.request.queryParameters["symbol"]
        if (symbol != null) {
            val ticker = tickerCacheService.getTicker(symbol)
            if (ticker != null) {
                call.respond(ticker)
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to -1121, "msg" to "Invalid symbol"))
            }
        } else {
            // Return all tickers
            call.respond(tickerCacheService.getAllTickers())
        }
    }
}
