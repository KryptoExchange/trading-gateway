package ru.krypto.gateway.config

import io.ktor.server.config.*

data class UpstreamConfig(val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
}

data class KafkaTopicsConfig(
    val marketState: String,
    val tradeOrderEvents: String,
    val marketOhclv: String
)

data class KafkaConfig(
    val bootstrapServers: String,
    val consumerGroupId: String,
    val topics: KafkaTopicsConfig
)

data class RedisConfig(val host: String, val port: Int)

data class RateLimitConfig(
    val requestsPerMinute: Int,
    val ordersPerSecond: Int,
    val ordersPerDay: Int
)

data class WebSocketConfig(
    val pingIntervalSeconds: Long,
    val timeoutSeconds: Long
)

data class AppConfig(
    val orderbook: UpstreamConfig,
    val account: UpstreamConfig,
    val apikey: UpstreamConfig,
    val kafka: KafkaConfig,
    val redis: RedisConfig,
    val rateLimit: RateLimitConfig,
    val webSocket: WebSocketConfig,
    val apiKeyCacheTtlSeconds: Long
) {
    companion object {
        fun load(config: ApplicationConfig): AppConfig {
            return AppConfig(
                orderbook = UpstreamConfig(
                    host = config.property("gateway.orderbook.host").getString(),
                    port = config.property("gateway.orderbook.port").getString().toInt()
                ),
                account = UpstreamConfig(
                    host = config.property("gateway.account.host").getString(),
                    port = config.property("gateway.account.port").getString().toInt()
                ),
                apikey = UpstreamConfig(
                    host = config.property("gateway.apikey.host").getString(),
                    port = config.property("gateway.apikey.port").getString().toInt()
                ),
                kafka = KafkaConfig(
                    bootstrapServers = config.property("kafka.bootstrapServers").getString(),
                    consumerGroupId = config.property("kafka.consumerGroupId").getString(),
                    topics = KafkaTopicsConfig(
                        marketState = config.property("kafka.topics.marketState").getString(),
                        tradeOrderEvents = config.property("kafka.topics.tradeOrderEvents").getString(),
                        marketOhclv = config.property("kafka.topics.marketOhclv").getString()
                    )
                ),
                redis = RedisConfig(
                    host = config.property("redis.host").getString(),
                    port = config.property("redis.port").getString().toInt()
                ),
                rateLimit = RateLimitConfig(
                    requestsPerMinute = config.property("rateLimit.requestsPerMinute").getString().toInt(),
                    ordersPerSecond = config.property("rateLimit.ordersPerSecond").getString().toInt(),
                    ordersPerDay = config.property("rateLimit.ordersPerDay").getString().toInt()
                ),
                webSocket = WebSocketConfig(
                    pingIntervalSeconds = config.property("websocket.pingIntervalSeconds").getString().toLong(),
                    timeoutSeconds = config.property("websocket.timeoutSeconds").getString().toLong()
                ),
                apiKeyCacheTtlSeconds = config.property("auth.apiKeyCacheTtlSeconds").getString().toLong()
            )
        }
    }
}
