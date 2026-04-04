package ru.krypto.gateway.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import ru.krypto.gateway.auth.ApiKeyAuthProvider
import ru.krypto.gateway.config.AppConfig
import ru.krypto.gateway.kafka.MarketDataConsumer
import ru.krypto.gateway.services.*
import ru.krypto.gateway.websocket.WebSocketHubService

fun Application.configureKoin() {
    val appConfig = AppConfig.load(environment.config)

    install(Koin) {
        slf4jLogger()
        modules(
            configModule(appConfig),
            httpClientModule(),
            redisModule(appConfig),
            serviceModule(),
            kafkaModule()
        )
    }
}

fun configModule(appConfig: AppConfig) = module {
    single { appConfig }
}

fun httpClientModule() = module {
    single {
        HttpClient(Apache) {
            install(ContentNegotiation) {
                jackson {
                    registerModule(JavaTimeModule())
                    registerModule(kotlinModule())
                    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                }
            }
            engine {
                connectTimeout = 5000
                socketTimeout = 10000
            }
        }
    }
}

fun redisModule(appConfig: AppConfig) = module {
    single {
        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 20
            maxIdle = 10
            minIdle = 2
        }
        JedisPool(poolConfig, appConfig.redis.host, appConfig.redis.port)
    }
}

fun serviceModule() = module {
    single { WebSocketHubService() }
    single { OrderBookCacheService(get()) }
    single { TradeCacheService(get()) }
    single { TickerCacheService(get()) }
    single { ExchangeInfoService(get(), get()) }
    single { OrderProxyService(get(), get(), get()) }
    single { BalanceProxyService(get(), get()) }
    single { RpcClientService(get()) }
    single { RateLimitService(get<AppConfig>().rateLimit) }
    single { CrossPriceService(get(), get(), get()) }

    single {
        val appConfig: AppConfig = get()
        val jedisPool: JedisPool = get()
        val rpcClientService: RpcClientService = get()

        ApiKeyAuthProvider(appConfig, jedisPool) { apiKey ->
            rpcClientService.validateApiKey(apiKey)
        }
    }
}

fun kafkaModule() = module {
    single {
        MarketDataConsumer(
            kafkaConfig = get<AppConfig>().kafka,
            orderBookCacheService = get(),
            tradeCacheService = get(),
            tickerCacheService = get()
        )
    }
}
