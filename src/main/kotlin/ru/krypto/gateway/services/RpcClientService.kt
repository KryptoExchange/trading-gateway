package ru.krypto.gateway.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.config.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.rpc.withService
import org.slf4j.LoggerFactory
import ru.krypto.common.interfaces.ApiKeyRpcService
import ru.krypto.common.interfaces.OrderbookRpc
import ru.krypto.common.interfaces.TradingPairRpcService
import ru.krypto.common.model.TradingPairDictionary
import ru.krypto.common.model.ValidateApiKeyRequest
import ru.krypto.common.model.ValidateApiKeyResponse
import ru.krypto.gateway.auth.ApiKeyPrincipal
import ru.krypto.gateway.config.AppConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RpcClientService(private val appConfig: AppConfig) {
    private val logger = LoggerFactory.getLogger(RpcClientService::class.java)
    private val requestTimeoutMs = 5000L
    private val connectTimeoutMs = 3000L

    private val rpcClient = AtomicReference(buildHttpClient())

    private fun buildHttpClient() = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15.seconds
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = requestTimeoutMs
        }
        installKrpc()
    }

    private fun currentClient(): HttpClient = rpcClient.get()

    private fun resetClient(): HttpClient {
        val oldClient = rpcClient.get()
        val newClient = buildHttpClient()
        if (rpcClient.compareAndSet(oldClient, newClient)) {
            try { oldClient.close() } catch (_: Exception) {}
        }
        return rpcClient.get()
    }

    private fun createApiKeyService(): ApiKeyRpcService {
        return currentClient().rpc {
            url {
                host = appConfig.apikey.host
                port = appConfig.apikey.port
                pathSegments = listOf("apikey_rpc")
            }
            rpcConfig {
                serialization { json() }
                connector {
                    waitTimeout = requestTimeoutMs.milliseconds
                    callTimeout = requestTimeoutMs.milliseconds
                }
            }
        }.withService<ApiKeyRpcService>()
    }

    private val apiKeyServiceRef = AtomicReference(createApiKeyService())

    suspend fun validateApiKey(apiKey: String): ApiKeyPrincipal? {
        return try {
            doValidate(apiKey)
        } catch (e: Exception) {
            if (shouldReconnect(e)) {
                logger.warn("API key validation RPC failed, reconnecting: {}", e.message)
                resetClient()
                apiKeyServiceRef.set(createApiKeyService())
                try {
                    doValidate(apiKey)
                } catch (e2: Exception) {
                    logger.error("API key validation retry failed: {}", e2.message)
                    null
                }
            } else {
                logger.error("API key validation failed: {}", e.message)
                null
            }
        }
    }

    private suspend fun doValidate(apiKey: String): ApiKeyPrincipal? {
        val request = ValidateApiKeyRequest(apiKey = apiKey)
        val response: ValidateApiKeyResponse = apiKeyServiceRef.get().validateApiKey(request)

        return if (response.valid && response.ownerUid != null) {
            ApiKeyPrincipal(
                userId = response.ownerUid!!,
                canRead = response.canRead,
                canTrade = response.canTrade,
                canWithdraw = response.canWithdraw
            )
        } else null
    }

    private fun shouldReconnect(cause: Throwable?): Boolean {
        return when (cause) {
            is IOException,
            is HttpRequestTimeoutException,
            is TimeoutCancellationException,
            is CancellationException,
            is IllegalStateException -> true
            else -> false
        }
    }

    private fun createTradingPairService(): TradingPairRpcService {
        return currentClient().rpc {
            url {
                host = appConfig.orderbook.host
                port = appConfig.orderbook.port
                pathSegments = listOf("trading_pair")
            }
            rpcConfig {
                serialization { json() }
                connector {
                    waitTimeout = requestTimeoutMs.milliseconds
                    callTimeout = requestTimeoutMs.milliseconds
                }
            }
        }.withService<TradingPairRpcService>()
    }

    private val tradingPairServiceRef = AtomicReference(createTradingPairService())

    suspend fun getTradingPairs(): List<TradingPairDictionary> {
        return try {
            tradingPairServiceRef.get().tradingPairAll().toList()
        } catch (e: Exception) {
            if (shouldReconnect(e)) {
                logger.warn("TradingPair RPC failed, reconnecting: {}", e.message)
                resetClient()
                apiKeyServiceRef.set(createApiKeyService())
                tradingPairServiceRef.set(createTradingPairService())
                orderbookRpcRef.set(createOrderbookRpc())
                tradingPairServiceRef.get().tradingPairAll().toList()
            } else {
                throw e
            }
        }
    }

    // ── Orderbook RPC ──

    private fun createOrderbookRpc(): OrderbookRpc {
        return currentClient().rpc {
            url {
                host = appConfig.orderbook.host
                port = appConfig.orderbook.port
                pathSegments = listOf("orderbook_rpc")
            }
            rpcConfig {
                serialization { json() }
                connector {
                    waitTimeout = requestTimeoutMs.milliseconds
                    callTimeout = requestTimeoutMs.milliseconds
                }
            }
        }.withService<OrderbookRpc>()
    }

    private val orderbookRpcRef = AtomicReference(createOrderbookRpc())

    suspend fun cancelAllByTradingPair(tradingPair: String): Int {
        return try {
            orderbookRpcRef.get().cancelAllByTradingPair(tradingPair)
        } catch (e: Exception) {
            if (shouldReconnect(e)) {
                logger.warn("Orderbook RPC cancelAllByTradingPair failed, reconnecting: {}", e.message)
                resetClient()
                apiKeyServiceRef.set(createApiKeyService())
                tradingPairServiceRef.set(createTradingPairService())
                orderbookRpcRef.set(createOrderbookRpc())
                orderbookRpcRef.get().cancelAllByTradingPair(tradingPair)
            } else {
                throw e
            }
        }
    }
}
