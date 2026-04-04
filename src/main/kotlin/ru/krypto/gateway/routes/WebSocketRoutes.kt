package ru.krypto.gateway.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import ru.krypto.gateway.auth.ApiKeyAuthProvider
import ru.krypto.gateway.model.WsRequest
import ru.krypto.gateway.model.WsResponse
import ru.krypto.gateway.websocket.WebSocketHubService

private val logger = LoggerFactory.getLogger("WebSocketRoutes")
private val mapper = jacksonObjectMapper()

fun Route.webSocketRoutes() {
    val webSocketHub: WebSocketHubService by inject()
    val apiKeyAuthProvider: ApiKeyAuthProvider by inject()

    // Public WebSocket: /ws/public
    webSocket("/ws/public") {
        logger.info("Public WS connection opened")
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        try {
                            val msg = mapper.readValue<WsRequest>(text)
                            when (msg.method.uppercase()) {
                                "SUBSCRIBE" -> {
                                    webSocketHub.subscribe(this, msg.params)
                                    send(Frame.Text(mapper.writeValueAsString(
                                        WsResponse(result = null, id = msg.id)
                                    )))
                                }
                                "UNSUBSCRIBE" -> {
                                    webSocketHub.unsubscribe(this, msg.params)
                                    send(Frame.Text(mapper.writeValueAsString(
                                        WsResponse(result = null, id = msg.id)
                                    )))
                                }
                                else -> {
                                    send(Frame.Text(mapper.writeValueAsString(
                                        mapOf("error" to "Unknown method: ${msg.method}")
                                    )))
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to parse WS message: {}", e.message)
                            send(Frame.Text(mapper.writeValueAsString(
                                mapOf("error" to "Invalid message format")
                            )))
                        }
                    }
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    else -> {}
                }
            }
        } finally {
            webSocketHub.removeSession(this)
            logger.info("Public WS connection closed")
        }
    }

    // Private WebSocket: /ws/private?apiKey=ak_live_...
    webSocket("/ws/private") {
        val apiKey = call.request.queryParameters["apiKey"]
        if (apiKey.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing apiKey parameter"))
            return@webSocket
        }

        val principal = apiKeyAuthProvider.validate(apiKey)
        if (principal == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid API key"))
            return@webSocket
        }

        if (!principal.canRead) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "API key lacks READ permission"))
            return@webSocket
        }

        val userId = principal.userId.toString()
        webSocketHub.subscribePrivate(this, userId)
        logger.info("Private WS connection opened for user: {}", userId)

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        // Private stream is push-only, but we handle pings
                        val text = frame.readText()
                        try {
                            val msg = mapper.readValue<WsRequest>(text)
                            // Can subscribe to public channels too
                            when (msg.method.uppercase()) {
                                "SUBSCRIBE" -> {
                                    webSocketHub.subscribe(this, msg.params)
                                    send(Frame.Text(mapper.writeValueAsString(
                                        WsResponse(result = null, id = msg.id)
                                    )))
                                }
                                "UNSUBSCRIBE" -> {
                                    webSocketHub.unsubscribe(this, msg.params)
                                    send(Frame.Text(mapper.writeValueAsString(
                                        WsResponse(result = null, id = msg.id)
                                    )))
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore parse errors for private stream
                        }
                    }
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    else -> {}
                }
            }
        } finally {
            webSocketHub.removeSession(this)
            logger.info("Private WS connection closed for user: {}", userId)
        }
    }
}
