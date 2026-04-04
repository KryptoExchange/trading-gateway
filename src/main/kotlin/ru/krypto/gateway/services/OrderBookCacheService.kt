package ru.krypto.gateway.services

import org.slf4j.LoggerFactory
import ru.krypto.gateway.kafka.MarketDataMessage
import ru.krypto.gateway.model.DepthResponse
import ru.krypto.gateway.model.DepthUpdateEvent
import ru.krypto.gateway.websocket.WebSocketHubService
import java.util.concurrent.ConcurrentHashMap

data class OrderBookSnapshot(
    val tradingPair: String,
    val bids: List<Pair<Long, Long>>,
    val asks: List<Pair<Long, Long>>,
    val referenceSeq: Long,
    val timestamp: Long
)

class OrderBookCacheService(
    private val webSocketHub: WebSocketHubService
) {
    private val logger = LoggerFactory.getLogger(OrderBookCacheService::class.java)
    private val cache = ConcurrentHashMap<String, OrderBookSnapshot>()

    fun update(data: MarketDataMessage) {
        val pair = data.tradingPair
        val previousSnapshot = cache[pair]

        val bids = mutableListOf<Pair<Long, Long>>()
        if (data.bidPrices != null && data.bidVolumes != null) {
            for (i in 0 until minOf(data.bidSize, data.bidPrices.size, data.bidVolumes.size)) {
                bids.add(data.bidPrices[i] to data.bidVolumes[i])
            }
        }

        val asks = mutableListOf<Pair<Long, Long>>()
        if (data.askPrices != null && data.askVolumes != null) {
            for (i in 0 until minOf(data.askSize, data.askPrices.size, data.askVolumes.size)) {
                asks.add(data.askPrices[i] to data.askVolumes[i])
            }
        }

        val snapshot = OrderBookSnapshot(
            tradingPair = pair,
            bids = bids,
            asks = asks,
            referenceSeq = data.referenceSeq,
            timestamp = data.timestamp
        )
        cache[pair] = snapshot

        // Compute diff and broadcast via WebSocket
        val diffEvent = computeDiffEvent(previousSnapshot, snapshot)
        if (diffEvent != null) {
            webSocketHub.broadcast("${pair.lowercase()}@depth", diffEvent)
        }
    }

    fun getDepth(symbol: String, limit: Int): DepthResponse? {
        val snapshot = cache[symbol.uppercase()] ?: return null
        return DepthResponse(
            lastUpdateId = snapshot.referenceSeq,
            bids = snapshot.bids.take(limit).map { (price, volume) ->
                listOf(PrecisionService.longToDecimalString(price), PrecisionService.longToDecimalString(volume))
            },
            asks = snapshot.asks.take(limit).map { (price, volume) ->
                listOf(PrecisionService.longToDecimalString(price), PrecisionService.longToDecimalString(volume))
            }
        )
    }

    fun getBestBidPrice(symbol: String): Long? {
        return cache[symbol.uppercase()]?.bids?.firstOrNull()?.first
    }

    fun getBestAskPrice(symbol: String): Long? {
        return cache[symbol.uppercase()]?.asks?.firstOrNull()?.first
    }

    private fun computeDiffEvent(
        previous: OrderBookSnapshot?,
        current: OrderBookSnapshot
    ): DepthUpdateEvent? {
        // If no previous snapshot, send full as diff
        val prevBidMap = previous?.bids?.associate { it } ?: emptyMap()
        val prevAskMap = previous?.asks?.associate { it } ?: emptyMap()
        val currBidMap = current.bids.associate { it }
        val currAskMap = current.asks.associate { it }

        val bidDiffs = mutableListOf<List<String>>()
        val askDiffs = mutableListOf<List<String>>()

        // Bids: added or changed
        for ((price, volume) in currBidMap) {
            if (prevBidMap[price] != volume) {
                bidDiffs.add(listOf(
                    PrecisionService.longToDecimalString(price),
                    PrecisionService.longToDecimalString(volume)
                ))
            }
        }
        // Bids: removed (volume = 0)
        for ((price, _) in prevBidMap) {
            if (price !in currBidMap) {
                bidDiffs.add(listOf(PrecisionService.longToDecimalString(price), "0"))
            }
        }

        // Asks: added or changed
        for ((price, volume) in currAskMap) {
            if (prevAskMap[price] != volume) {
                askDiffs.add(listOf(
                    PrecisionService.longToDecimalString(price),
                    PrecisionService.longToDecimalString(volume)
                ))
            }
        }
        // Asks: removed (volume = 0)
        for ((price, _) in prevAskMap) {
            if (price !in currAskMap) {
                askDiffs.add(listOf(PrecisionService.longToDecimalString(price), "0"))
            }
        }

        if (bidDiffs.isEmpty() && askDiffs.isEmpty()) return null

        return DepthUpdateEvent(
            eventTime = current.timestamp,
            symbol = current.tradingPair,
            firstUpdateId = previous?.referenceSeq ?: 0,
            lastUpdateId = current.referenceSeq,
            bids = bidDiffs,
            asks = askDiffs
        )
    }
}
