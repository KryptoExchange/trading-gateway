package ru.krypto.gateway.services

import org.slf4j.LoggerFactory
import ru.krypto.gateway.kafka.SessionStatisticsMessage
import ru.krypto.gateway.model.TickerResponse
import java.util.concurrent.ConcurrentHashMap

data class TickerData(
    val symbol: String,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val closePrice: Long,
    val volume: Long,
    val sessionStart: Long,
    val sessionEnd: Long,
    val timestamp: Long
)

class TickerCacheService(
    private val orderBookCacheService: OrderBookCacheService
) {
    private val logger = LoggerFactory.getLogger(TickerCacheService::class.java)
    private val cache = ConcurrentHashMap<String, TickerData>()

    fun update(stats: SessionStatisticsMessage) {
        cache[stats.symbol] = TickerData(
            symbol = stats.symbol,
            openPrice = stats.openPrice,
            highPrice = stats.highPrice,
            lowPrice = stats.lowPrice,
            closePrice = stats.closePrice,
            volume = stats.volume,
            sessionStart = stats.sessionStart,
            sessionEnd = stats.sessionEnd,
            timestamp = stats.timestamp
        )
    }

    fun getTicker(symbol: String): TickerResponse? {
        val data = cache[symbol.uppercase()] ?: return null

        val bidPrice = orderBookCacheService.getBestBidPrice(symbol) ?: 0L
        val askPrice = orderBookCacheService.getBestAskPrice(symbol) ?: 0L

        // Approximate quoteVolume = volume * closePrice / 10^8
        val quoteVolume = if (data.closePrice > 0) {
            (data.volume.toBigDecimal() * data.closePrice.toBigDecimal())
                .divide(java.math.BigDecimal.TEN.pow(8), 8, java.math.RoundingMode.HALF_UP)
                .toLong()
        } else 0L

        return TickerResponse(
            symbol = data.symbol,
            lastPrice = PrecisionService.longToDecimalString(data.closePrice),
            bidPrice = PrecisionService.longToDecimalString(bidPrice),
            askPrice = PrecisionService.longToDecimalString(askPrice),
            highPrice = PrecisionService.longToDecimalString(data.highPrice),
            lowPrice = PrecisionService.longToDecimalString(if (data.lowPrice == Long.MAX_VALUE) 0L else data.lowPrice),
            volume = PrecisionService.longToDecimalString(data.volume),
            quoteVolume = PrecisionService.longToDecimalString(quoteVolume),
            openPrice = PrecisionService.longToDecimalString(data.openPrice),
            openTime = data.sessionStart,
            closeTime = data.sessionEnd
        )
    }

    fun getAllTickers(): List<TickerResponse> {
        return cache.keys.mapNotNull { getTicker(it) }
    }
}
