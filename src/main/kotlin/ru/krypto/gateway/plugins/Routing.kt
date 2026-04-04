package ru.krypto.gateway.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import ru.krypto.gateway.kafka.MarketDataConsumer
import ru.krypto.gateway.routes.marketDataRoutes
import ru.krypto.gateway.routes.tradingRoutes
import ru.krypto.gateway.routes.webSocketRoutes
import ru.krypto.gateway.services.RateLimitService
import ru.krypto.gateway.websocket.WebSocketHubService

fun Application.configureRouting() {
    val webSocketHub: WebSocketHubService by inject()
    val rateLimitService: RateLimitService by inject()
    val marketDataConsumer: MarketDataConsumer by inject()

    // Route-scoped plugin for general rate limiting (1200 req/min per API key)
    val generalRateLimitPlugin = createRouteScopedPlugin("GeneralRateLimit") {
        onCall { call ->
            val key = call.request.header("X-API-Key")
                ?: call.request.local.remoteHost
            val result = rateLimitService.checkGeneralLimit(key)

            call.response.header("X-RateLimit-Limit", result.limit.toString())
            call.response.header("X-RateLimit-Remaining", result.remaining.toString())
            call.response.header("X-RateLimit-Reset", result.resetTimestamp.toString())

            if (!result.allowed) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf("code" to -1015, "msg" to "Too many requests")
                )
            }
        }
    }

    routing {
        // Health checks (no rate limiting)
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }
        get("/health/ready") {
            val kafkaRunning = marketDataConsumer.isRunning()
            if (kafkaRunning) {
                call.respond(HttpStatusCode.OK, mapOf(
                    "status" to "READY",
                    "kafka" to "connected"
                ))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                    "status" to "NOT_READY",
                    "kafka" to "disconnected"
                ))
            }
        }
        get("/health/live") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "LIVE"))
        }

        // WebSocket stats (internal)
        get("/internal/ws-stats") {
            call.respond(webSocketHub.getStats())
        }

        // API routes with general rate limiting
        route("/api/v1") {
            install(generalRateLimitPlugin)
            marketDataRoutes()
            tradingRoutes()
        }

        // WebSocket endpoints (no rate limiting)
        webSocketRoutes()
    }
}
