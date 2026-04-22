package ru.krypto.gateway.services

import org.slf4j.LoggerFactory
import ru.krypto.gateway.kafka.TradeOrderEventMessage
import ru.krypto.gateway.model.MyTradeResponse
import ru.krypto.gateway.model.TradeEvent
import ru.krypto.gateway.model.TradeResponse
import ru.krypto.gateway.websocket.WebSocketHubService
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

data class CachedTrade(
    val tradeId: String,
    val orderId: String,
    val makerOrderId: String?,
    val tradingPair: String,
    val price: String,
    val quantity: String,
    val isMaker: Boolean,
    val isBuyer: Boolean,
    val fee: String,
    val feeAsset: String,
    val userId: String?,
    val timestamp: Long
)

class TradeCacheService(
    private val webSocketHub: WebSocketHubService,
    private val maxTradesPerPair: Int = 500
) {
    private val logger = LoggerFactory.getLogger(TradeCacheService::class.java)
    private val publicTrades = ConcurrentHashMap<String, ConcurrentLinkedDeque<CachedTrade>>()
    private val userTrades = ConcurrentHashMap<String, ConcurrentLinkedDeque<CachedTrade>>()
    private val tradeCounter = AtomicLong(0)

    fun onTradeEvent(event: TradeOrderEventMessage) {
        // Only process fill events
        val status = event.orderStatus.uppercase()
        if (status != "FILLED" && status != "PARTIALLY_FILLED") return
        if (event.tradingPair == null || event.executionPrice == null || event.executedQuantity == null) return

        val pair = event.tradingPair
        val tradeId = event.tradeUUID ?: "t-${tradeCounter.incrementAndGet()}"
        val price = event.executionPrice
        val quantity = event.executedQuantity
        val isMaker = event.maker ?: false
        val side = event.side?.uppercase() ?: ""
        val isBuyer = side == "BID" || side == "BUY"
        val fee = event.fee ?: "0"
        val feeAsset = event.feeAsset ?: ""
        val timestamp = if (event.executedAt != null) {
            try {
                Instant.parse(event.executedAt).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        } else {
            System.currentTimeMillis()
        }

        val trade = CachedTrade(
            tradeId = tradeId,
            orderId = event.orderId,
            makerOrderId = event.makerOrderId,
            tradingPair = pair,
            price = price,
            quantity = quantity,
            isMaker = isMaker,
            isBuyer = isBuyer,
            fee = fee,
            feeAsset = feeAsset,
            userId = event.userId,
            timestamp = timestamp
        )

        // Add to public trades ring buffer
        val deque = publicTrades.computeIfAbsent(pair) { ConcurrentLinkedDeque() }
        deque.addFirst(trade)
        while (deque.size > maxTradesPerPair) {
            deque.pollLast()
        }

        // Add to user trades
        if (event.userId != null) {
            val userKey = "${event.userId}:$pair"
            val userDeque = userTrades.computeIfAbsent(userKey) { ConcurrentLinkedDeque() }
            userDeque.addFirst(trade)
            while (userDeque.size > maxTradesPerPair) {
                userDeque.pollLast()
            }
        }

        // Broadcast to WS subscribers
        val tradeEvent = TradeEvent(
            eventTime = timestamp,
            symbol = pair,
            tradeId = tradeId,
            price = PrecisionService.longToDecimalString(price.toLongOrNull() ?: 0),
            quantity = PrecisionService.longToDecimalString(quantity.toLongOrNull() ?: 0),
            tradeTime = timestamp,
            isBuyerMaker = isBuyer && isMaker
        )
        webSocketHub.broadcast("${pair.lowercase()}@trade", tradeEvent)

        // Broadcast execution report to private WS
        if (event.userId != null) {
            webSocketHub.broadcastToUser(event.userId, event)
        }
    }

    fun getRecentTrades(symbol: String, limit: Int): List<TradeResponse> {
        val trades = publicTrades[symbol.uppercase()] ?: return emptyList()
        return trades.take(limit).map { trade ->
            TradeResponse(
                id = trade.tradeId,
                price = PrecisionService.longToDecimalString(trade.price.toLongOrNull() ?: 0),
                qty = PrecisionService.longToDecimalString(trade.quantity.toLongOrNull() ?: 0),
                time = trade.timestamp,
                isBuyerMaker = trade.isBuyer && trade.isMaker
            )
        }
    }

    fun getMyTrades(userId: UUID, symbol: String, limit: Int, orderId: String? = null): List<MyTradeResponse> {
        val key = "$userId:${symbol.uppercase()}"
        val trades = userTrades[key] ?: return emptyList()
        return trades.asSequence()
            .filter { orderId == null || it.orderId == orderId }
            .take(limit)
            .map { trade ->
                MyTradeResponse(
                    id = trade.tradeId,
                    orderId = trade.orderId,
                    symbol = trade.tradingPair,
                    price = PrecisionService.longToDecimalString(trade.price.toLongOrNull() ?: 0),
                    qty = PrecisionService.longToDecimalString(trade.quantity.toLongOrNull() ?: 0),
                    commission = trade.fee,
                    commissionAsset = trade.feeAsset,
                    time = trade.timestamp,
                    isMaker = trade.isMaker,
                    isBuyer = trade.isBuyer
                )
            }
            .toList()
    }
}
