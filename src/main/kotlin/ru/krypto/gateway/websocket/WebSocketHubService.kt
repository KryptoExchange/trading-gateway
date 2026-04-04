package ru.krypto.gateway.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import ru.krypto.gateway.kafka.TradeOrderEventMessage
import ru.krypto.gateway.model.ExecutionReportEvent
import ru.krypto.gateway.services.OrderProxyService
import ru.krypto.gateway.services.PrecisionService
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class WebSocketHubService {
    private val logger = LoggerFactory.getLogger(WebSocketHubService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    // channel (e.g. "btcusdt@depth") -> set of sessions
    private val publicSubscriptions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    // userId -> set of sessions
    private val privateSubscriptions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun subscribe(session: WebSocketSession, channels: List<String>) {
        for (channel in channels) {
            val normalized = channel.lowercase()
            publicSubscriptions.computeIfAbsent(normalized) {
                Collections.newSetFromMap(ConcurrentHashMap())
            }.add(session)
            logger.debug("Session subscribed to public channel: {}", normalized)
        }
    }

    fun unsubscribe(session: WebSocketSession, channels: List<String>) {//
        for (channel in channels) {
            val normalized = channel.lowercase()
            publicSubscriptions[normalized]?.remove(session)
        }
    }

    fun subscribePrivate(session: WebSocketSession, userId: String) {
        privateSubscriptions.computeIfAbsent(userId) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(session)
        logger.debug("Session subscribed to private stream for user: {}", userId)
    }

    fun removeSession(session: WebSocketSession) {
        publicSubscriptions.values.forEach { it.remove(session) }
        privateSubscriptions.values.forEach { it.remove(session) }
    }

    fun broadcast(channel: String, event: Any) {
        val sessions = publicSubscriptions[channel.lowercase()] ?: return
        val json = mapper.writeValueAsString(event)
        val deadSessions = mutableListOf<WebSocketSession>()

        for (session in sessions) {
            try {
                if (session.isActive) {
                    val result = session.outgoing.trySend(Frame.Text(json))
                    if (result.isFailure) {
                        deadSessions.add(session)
                    }
                } else {
                    deadSessions.add(session)
                }
            } catch (e: Exception) {
                deadSessions.add(session)
            }
        }

        if (deadSessions.isNotEmpty()) {
            sessions.removeAll(deadSessions.toSet())
        }
    }

    fun broadcastToUser(userId: String, event: TradeOrderEventMessage) {
        val sessions = privateSubscriptions[userId] ?: return

        val side = when (event.side?.uppercase()) {
            "BID" -> "BUY"
            "ASK" -> "SELL"
            else -> event.side ?: ""
        }

        val execReport = ExecutionReportEvent(
            eventTime = System.currentTimeMillis(),
            symbol = event.tradingPair ?: "",
            clientOrderId = null,
            side = side,
            orderType = "LIMIT",
            quantity = PrecisionService.longToDecimalString(
                event.executedQuantity?.toLongOrNull() ?: 0
            ),
            price = PrecisionService.longToDecimalString(
                event.executionPrice?.toLongOrNull() ?: 0
            ),
            orderStatus = OrderProxyService.mapOrderStatus(event.orderStatus),
            orderId = event.orderId,
            lastFilledQty = event.executedQuantity?.let {
                PrecisionService.longToDecimalString(it.toLongOrNull() ?: 0)
            },
            lastFilledPrice = event.executionPrice?.let {
                PrecisionService.longToDecimalString(it.toLongOrNull() ?: 0)
            },
            commission = event.fee,
            commissionAsset = event.feeAsset,
            tradeTime = System.currentTimeMillis()
        )

        val json = mapper.writeValueAsString(execReport)
        val deadSessions = mutableListOf<WebSocketSession>()

        for (session in sessions) {
            try {
                if (session.isActive) {
                    val result = session.outgoing.trySend(Frame.Text(json))
                    if (result.isFailure) {
                        deadSessions.add(session)
                    }
                } else {
                    deadSessions.add(session)
                }
            } catch (e: Exception) {
                deadSessions.add(session)
            }
        }

        if (deadSessions.isNotEmpty()) {
            sessions.removeAll(deadSessions.toSet())
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "publicChannels" to publicSubscriptions.size,
            "publicSessions" to publicSubscriptions.values.sumOf { it.size },
            "privateSessions" to privateSubscriptions.values.sumOf { it.size }
        )
    }
}
