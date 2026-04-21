package ru.krypto.gateway.services

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.krypto.gateway.config.AppConfig
import ru.krypto.gateway.model.ExchangeInfoResponse
import ru.krypto.gateway.model.SymbolFilter
import ru.krypto.gateway.model.SymbolInfo
import java.util.concurrent.ConcurrentHashMap

class ExchangeInfoService(
    private val rpcClientService: RpcClientService,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(ExchangeInfoService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Volatile
    private var cachedInfo: ExchangeInfoResponse? = null
    @Volatile
    private var cacheTimestamp: Long = 0
    private val cacheTtlMs = 5 * 60 * 1000L // 5 minutes
    private val rpcTimeoutMs = 5_000L

    // Precision map: symbol -> (priceDecimals, volumeDecimals)
    val precisionMap = ConcurrentHashMap<String, Pair<Int, Int>>()

    suspend fun getExchangeInfo(): ExchangeInfoResponse {
        val now = System.currentTimeMillis()
        val cached = cachedInfo
        if (cached != null && now - cacheTimestamp < cacheTtlMs) {
            return cached
        }

        return try {
            fetchAndCache()
        } catch (e: TimeoutCancellationException) {
            val fallback = cachedInfo
            if (fallback != null) {
                logger.warn("exchangeInfo RPC timed out ({}ms), serving stale cache", rpcTimeoutMs)
                fallback
            } else {
                logger.error("exchangeInfo RPC timed out and no cache available: {}", e.message)
                throw IllegalStateException("Orderbook-service unavailable: exchangeInfo RPC timed out", e)
            }
        } catch (e: Exception) {
            val fallback = cachedInfo
            if (fallback != null) {
                logger.warn("exchangeInfo RPC failed ({}), serving stale cache", e.message)
                fallback
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchAndCache(): ExchangeInfoResponse {
        val tradingPairs = withTimeout(rpcTimeoutMs) { rpcClientService.getTradingPairs() }

        val symbols = tradingPairs.mapNotNull { pair ->
            try {
                val name = pair.name
                val minimumStep = requireNotNull(pair.minimumStep)
                val minimumVolume = requireNotNull(pair.minimumVolume)
                val accuracyPrice = requireNotNull(pair.accuracyPrice)
                val accuracyVolume = requireNotNull(pair.accuracyVolume)

                // Parse base/quote from pair name (e.g., BTCUSDT -> BTC, USDT)
                val (base, quote) = parseTradingPair(name)

                val apiSymbol = name.replace("/", "")
                precisionMap[apiSymbol] = Pair(accuracyPrice.toInt(), accuracyVolume.toInt())

                val tickSize = stepToString(minimumStep, accuracyPrice.toInt())
                val stepSize = stepToString(minimumVolume, accuracyVolume.toInt())

                SymbolInfo(
                    symbol = apiSymbol,
                    status = "TRADING",
                    baseAsset = base,
                    quoteAsset = quote,
                    baseAssetPrecision = accuracyVolume.toInt(),
                    quoteAssetPrecision = accuracyPrice.toInt(),
                    orderTypes = listOf("LIMIT", "MARKET"),
                    filters = listOf(
                        SymbolFilter(
                            filterType = "PRICE_FILTER",
                            minPrice = tickSize,
                            maxPrice = "1000000.00000000",
                            tickSize = tickSize
                        ),
                        SymbolFilter(
                            filterType = "LOT_SIZE",
                            minQty = stepSize,
                            maxQty = "1000.00000000",
                            stepSize = stepSize
                        ),
                        SymbolFilter(
                            filterType = "MIN_NOTIONAL",
                            minNotional = "10.00000000"
                        )
                    ),
                    permissions = listOf("SPOT")
                )
            } catch (e: Exception) {
                logger.warn("Failed to parse trading pair: {}", e.message)
                null
            }
        }

        val info = ExchangeInfoResponse(
            serverTime = System.currentTimeMillis(),
            symbols = symbols
        )
        cachedInfo = info
        cacheTimestamp = System.currentTimeMillis()
        return info
    }

    private fun parseTradingPair(name: String): Pair<String, String> {
        // Handle explicit separator (e.g., "BTC/USDT")
        if (name.contains("/")) {
            val parts = name.split("/", limit = 2)
            return Pair(parts[0], parts[1])
        }
        // Fallback: match known quote assets (e.g., "BTCUSDT" -> "BTC", "USDT")
        val knownQuotes = listOf("USDT", "RUB", "BTC", "ETH", "TRX")
        for (quote in knownQuotes) {
            if (name.endsWith(quote) && name.length > quote.length) {
                return Pair(name.substring(0, name.length - quote.length), quote)
            }
        }
        val mid = name.length / 2
        return Pair(name.substring(0, mid), name.substring(mid))
    }

    private fun stepToString(step: Long, precision: Int): String {
        if (step <= 0 || precision <= 0) return "0.00000001"
        val bd = java.math.BigDecimal(step).movePointLeft(precision)
        return bd.stripTrailingZeros().toPlainString()
    }
}
