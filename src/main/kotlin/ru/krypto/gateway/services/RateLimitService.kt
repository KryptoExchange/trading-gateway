package ru.krypto.gateway.services

import org.slf4j.LoggerFactory
import ru.krypto.gateway.config.RateLimitConfig
import java.util.concurrent.ConcurrentHashMap

data class RateLimitResult(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val resetTimestamp: Long
)

class RateLimitService(private val config: RateLimitConfig) {
    private val logger = LoggerFactory.getLogger(RateLimitService::class.java)

    // Per-key general request buckets (1200 req/min)
    private val generalBuckets = ConcurrentHashMap<String, TokenBucket>()
    // Per-key order-per-second buckets (10 orders/sec)
    private val orderSecondBuckets = ConcurrentHashMap<String, TokenBucket>()
    // Per-key daily order buckets (100,000 orders/24hr)
    private val orderDailyBuckets = ConcurrentHashMap<String, TokenBucket>()

    fun checkGeneralLimit(key: String): RateLimitResult {
        val bucket = generalBuckets.computeIfAbsent(key) {
            TokenBucket(config.requestsPerMinute, 60_000L)
        }
        return bucket.tryConsume()
    }

    fun checkOrderLimit(key: String): RateLimitResult {
        // Check per-second limit first
        val secBucket = orderSecondBuckets.computeIfAbsent(key) {
            TokenBucket(config.ordersPerSecond, 1_000L)
        }
        val secResult = secBucket.tryConsume()
        if (!secResult.allowed) return secResult

        // Check daily limit
        val dayBucket = orderDailyBuckets.computeIfAbsent(key) {
            TokenBucket(config.ordersPerDay, 86_400_000L)
        }
        val dayResult = dayBucket.tryConsume()
        if (!dayResult.allowed) {
            // Undo per-second consumption since daily limit was hit
            secBucket.returnToken()
            return dayResult
        }

        return secResult
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThresholdMs = 300_000L // 5 minutes
        val removedGeneral = generalBuckets.entries.removeIf { now - it.value.lastAccessTime > staleThresholdMs }
        val removedOrderSec = orderSecondBuckets.entries.removeIf { now - it.value.lastAccessTime > staleThresholdMs }
        val removedOrderDay = orderDailyBuckets.entries.removeIf { now - it.value.lastAccessTime > staleThresholdMs }
        logger.debug("Rate limit cleanup: general={}, orderSec={}, orderDay={}",
            generalBuckets.size, orderSecondBuckets.size, orderDailyBuckets.size)
    }
}

class TokenBucket(private val limit: Int, private val refillPeriodMs: Long) {
    @Volatile
    private var tokens: Int = limit
    @Volatile
    private var lastRefill: Long = System.currentTimeMillis()
    @Volatile
    var lastAccessTime: Long = System.currentTimeMillis()
        private set

    @Synchronized
    fun tryConsume(): RateLimitResult {
        refill()
        lastAccessTime = System.currentTimeMillis()
        val resetTime = (lastRefill + refillPeriodMs) / 1000

        return if (tokens > 0) {
            tokens--
            RateLimitResult(true, limit, tokens, resetTime)
        } else {
            RateLimitResult(false, limit, 0, resetTime)
        }
    }

    @Synchronized
    fun returnToken() {
        if (tokens < limit) tokens++
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefill
        if (elapsed >= refillPeriodMs) {
            tokens = limit
            lastRefill = now
        }
    }
}
