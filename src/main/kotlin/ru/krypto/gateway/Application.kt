package ru.krypto.gateway

import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import ru.krypto.gateway.kafka.MarketDataConsumer
import ru.krypto.gateway.plugins.*
import ru.krypto.gateway.services.CrossPriceService
import ru.krypto.gateway.services.RateLimitService

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")

    logger.info("Starting trading-gateway...")

    // Initialize Koin DI
    configureKoin()

    // Configure CORS
    configureCORS()

    // Configure serialization
    configureSerialization()

    // Configure logging
    configureLogging()

    // Configure WebSockets
    configureWebSockets()

    // Configure authentication
    configureAuthentication()

    // Configure routing
    configureRouting()

    // Start Kafka consumers
    val marketDataConsumer: MarketDataConsumer by inject()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    marketDataConsumer.start(appScope)

    // Start cross-price service (Binance prices + RUB cross-rates)
    val crossPriceService: CrossPriceService by inject()
    crossPriceService.start(appScope)

    // Periodic rate limit bucket cleanup (every 5 minutes)
    val rateLimitService: RateLimitService by inject()
    appScope.launch {
        while (isActive) {
            delay(5 * 60 * 1000L)
            rateLimitService.cleanup()
        }
    }

    monitor.subscribe(ApplicationStopped) {
        logger.info("Stopping trading-gateway...")
        crossPriceService.stop()
        marketDataConsumer.stop()
        appScope.cancel()
    }

    logger.info("trading-gateway started successfully on port ${environment.config.property("ktor.deployment.port").getString()}")
}
