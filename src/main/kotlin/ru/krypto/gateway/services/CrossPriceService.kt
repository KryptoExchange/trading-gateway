package ru.krypto.gateway.services

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import ru.krypto.gateway.config.AppConfig
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches crypto prices from Binance and USD/RUB rate from external sources,
 * then calculates cross-rates for pairs without a direct external source (RUB pairs, BTC-ETH).
 *
 * USD/RUB rate sources (in priority order):
 *   1. CBR (Central Bank of Russia) — cbr-xml-daily.ru, updated once per business day
 *   2. MOEX ISS (Moscow Exchange) — real-time during trading hours (10:00-23:50 MSK)
 *
 * If both sources fail, the rate is marked unavailable:
 *   - getPrice() returns null for all RUB pairs
 *   - All active RUB orders on the exchange are cancelled via orderbook-service
 *
 * Environment variable USDT_RUB_RATE can override auto-fetching (useful for testing).
 *
 * Exposes prices as plain-text decimals for Hummingbot custom_api.
 */
class CrossPriceService(
    private val httpClient: HttpClient,
    private val rpcClientService: RpcClientService,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(CrossPriceService::class.java)

    private val binancePrices = ConcurrentHashMap<String, BigDecimal>()

    /** If set, env var overrides all sources and is used as a fixed rate. */
    private val envRateOverride: BigDecimal? = System.getenv("USDT_RUB_RATE")?.let {
        try { BigDecimal(it) } catch (_: NumberFormatException) { null }
    }

    @Volatile
    var usdtRubRate: BigDecimal? = envRateOverride
        private set

    /** True when we have a valid rate (from any source or env override). */
    @Volatile
    var rateAvailable: Boolean = envRateOverride != null
        private set

    private var priceJob: Job? = null
    private var rateJob: Job? = null

    private val orderbookBaseUrl get() = appConfig.orderbook.baseUrl

    companion object {
        private const val PRICE_UPDATE_INTERVAL_MS = 5_000L
        private const val RATE_UPDATE_INTERVAL_MS = 3_600_000L // 1 hour

        /** Community JSON proxy of official CBR daily rates. */
        private const val CBR_JSON_URL = "https://www.cbr-xml-daily.ru/daily_json.js"

        /** MOEX ISS API — USD/RUB spot rate (CETS board, USD000UTSTOM instrument). */
        private const val MOEX_USD_RUB_URL =
            "https://iss.moex.com/iss/engines/currency/markets/selt/boards/CETS/securities/USD000UTSTOM.json" +
                "?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST"

        private val RATE_MIN = BigDecimal("30")
        private val RATE_MAX = BigDecimal("500")

        /** RUB trading pairs whose orders are cancelled when the rate becomes unavailable. */
        private val RUB_TRADING_PAIRS = listOf("BTC/RUB", "ETH/RUB", "USDT/RUB")
    }

    fun start(scope: CoroutineScope) {
        if (envRateOverride != null) {
            logger.info(
                "CrossPriceService starting — USDT_RUB_RATE override from env: {}. Auto-update disabled.",
                envRateOverride
            )
        } else {
            logger.info("CrossPriceService starting — will fetch USD/RUB from CBR (primary) / MOEX (fallback)")
        }

        priceJob = scope.launch {
            while (isActive) {
                try {
                    fetchBinancePrices()
                } catch (e: Exception) {
                    logger.error("Failed to fetch Binance prices: {}", e.message)
                }
                delay(PRICE_UPDATE_INTERVAL_MS)
            }
        }

        // Only start rate polling if no env var override
        if (envRateOverride == null) {
            rateJob = scope.launch {
                while (isActive) {
                    try {
                        fetchUsdRubRate()
                    } catch (e: Exception) {
                        logger.error("Unexpected error in rate fetch loop: {}", e.message)
                    }
                    delay(RATE_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        priceJob?.cancel()
        rateJob?.cancel()
    }

    /**
     * Tries to fetch USD/RUB rate: CBR first, then MOEX as fallback.
     * If both fail, marks rate as unavailable and cancels RUB orders.
     */
    private suspend fun fetchUsdRubRate() {
        val cbrRate = fetchFromCbr()
        if (cbrRate != null) {
            applyNewRate(cbrRate, "CBR")
            return
        }

        logger.warn("CBR unavailable, trying MOEX fallback...")

        val moexRate = fetchFromMoex()
        if (moexRate != null) {
            applyNewRate(moexRate, "MOEX")
            return
        }

        // Both sources failed
        if (rateAvailable) {
            logger.error(
                "USD/RUB rate unavailable — both CBR and MOEX failed. " +
                    "Suspending RUB pair pricing and cancelling active RUB orders."
            )
            rateAvailable = false
            usdtRubRate = null
            cancelRubOrders()
        } else {
            logger.warn("USD/RUB rate still unavailable (CBR and MOEX both down)")
        }
    }

    private fun applyNewRate(newRate: BigDecimal, source: String) {
        val wasUnavailable = !rateAvailable
        val oldRate = usdtRubRate

        usdtRubRate = newRate
        rateAvailable = true

        if (wasUnavailable) {
            logger.info("USD/RUB rate restored: {} (source: {}). RUB pairs re-enabled.", newRate, source)
        } else if (oldRate == null || newRate.compareTo(oldRate) != 0) {
            logger.info("USD/RUB rate updated: {} -> {} (source: {})", oldRate, newRate, source)
        }
    }

    /**
     * Fetches USD/RUB from CBR JSON API.
     * Response: {"Date":"...","Valute":{"USD":{"Value":84.7080,...},...}}
     */
    private suspend fun fetchFromCbr(): BigDecimal? {
        return try {
            val response = httpClient.get(CBR_JSON_URL)
            val body = response.bodyAsText()

            val usdSection = body.substringAfter("\"USD\":{", "")
            if (usdSection.isEmpty()) {
                logger.warn("CBR response missing USD section")
                return null
            }
            val valueStr = usdSection
                .substringAfter("\"Value\":", "")
                .substringBefore(",")
                .trim()
            if (valueStr.isEmpty()) {
                logger.warn("CBR response missing Value field for USD")
                return null
            }

            val rate = BigDecimal(valueStr).setScale(4, RoundingMode.HALF_UP)
            if (rate < RATE_MIN || rate > RATE_MAX) {
                logger.warn("CBR returned suspicious USD/RUB rate: {} — ignored", rate)
                return null
            }
            rate
        } catch (e: Exception) {
            logger.warn("Failed to fetch USD/RUB from CBR: {}", e.message)
            null
        }
    }

    /**
     * Fetches USD/RUB from MOEX ISS API (Moscow Exchange, CETS board).
     * Response: {"marketdata":{"columns":["SECID","LAST"],"data":[["USD000UTSTOM",84.175]]}}
     * LAST can be null outside trading hours — treated as unavailable.
     */
    private suspend fun fetchFromMoex(): BigDecimal? {
        return try {
            val response = httpClient.get(MOEX_USD_RUB_URL)
            val body = response.bodyAsText()

            // Parse: "data":[["USD000UTSTOM",84.175]]
            val dataSection = body.substringAfter("\"data\":[[", "")
            if (dataSection.isEmpty()) {
                logger.warn("MOEX response missing data section")
                return null
            }

            // Extract the LAST value (second element after SECID)
            val parts = dataSection.substringBefore("]]").split(",")
            if (parts.size < 2) {
                logger.warn("MOEX response has unexpected data format")
                return null
            }

            val lastStr = parts[1].trim()
            if (lastStr == "null" || lastStr.isEmpty()) {
                logger.warn("MOEX LAST price is null (market likely closed)")
                return null
            }

            val rate = BigDecimal(lastStr).setScale(4, RoundingMode.HALF_UP)
            if (rate < RATE_MIN || rate > RATE_MAX) {
                logger.warn("MOEX returned suspicious USD/RUB rate: {} — ignored", rate)
                return null
            }
            rate
        } catch (e: Exception) {
            logger.warn("Failed to fetch USD/RUB from MOEX: {}", e.message)
            null
        }
    }

    /**
     * Cancels all active orders on RUB trading pairs via orderbook-service internal API.
     */
    private suspend fun cancelRubOrders() {
        for (pair in RUB_TRADING_PAIRS) {
            try {
                val response = rpcClientService.cancelAllByTradingPair(pair)
                logger.info("Cancel RUB orders for {}: {}", pair, response)
            } catch (e: Exception) {
                logger.error("Failed to cancel orders for {}: {}", pair, e.message)
            }
        }
    }

    private suspend fun fetchBinancePrices() {
        for (symbol in listOf("BTCUSDT", "ETHUSDT", "ETHBTC")) {
            try {
                val response = httpClient.get("https://api.binance.com/api/v3/ticker/price") {
                    parameter("symbol", symbol)
                }
                val body = response.bodyAsText()
                val priceStr = body.substringAfter("\"price\":\"").substringBefore("\"")
                val price = BigDecimal(priceStr)
                binancePrices[symbol] = price
                logger.debug("Binance {} = {}", symbol, price)
            } catch (e: Exception) {
                logger.warn("Failed to fetch {} from Binance: {}", symbol, e.message)
            }
        }
    }

    /**
     * Returns mid-price for a given pair symbol (e.g. "BTCRUB", "USDTRUB", "BTCETH").
     * Returns null if price is not available (including when RUB rate is unavailable).
     */
    fun getPrice(symbol: String): BigDecimal? {
        return when (symbol.uppercase()) {
            "BTCUSDT" -> binancePrices["BTCUSDT"]
            "ETHUSDT" -> binancePrices["ETHUSDT"]
            "BTCETH" -> {
                val ethBtc = binancePrices["ETHBTC"] ?: return null
                if (ethBtc.compareTo(BigDecimal.ZERO) == 0) return null
                BigDecimal.ONE.divide(ethBtc, 8, RoundingMode.HALF_UP)
            }
            "USDTRUB" -> if (rateAvailable) usdtRubRate else null
            "BTCRUB" -> {
                if (!rateAvailable) return null
                val rate = usdtRubRate ?: return null
                val btcUsdt = binancePrices["BTCUSDT"] ?: return null
                btcUsdt.multiply(rate).setScale(2, RoundingMode.HALF_UP)
            }
            "ETHRUB" -> {
                if (!rateAvailable) return null
                val rate = usdtRubRate ?: return null
                val ethUsdt = binancePrices["ETHUSDT"] ?: return null
                ethUsdt.multiply(rate).setScale(2, RoundingMode.HALF_UP)
            }
            else -> null
        }
    }
}
