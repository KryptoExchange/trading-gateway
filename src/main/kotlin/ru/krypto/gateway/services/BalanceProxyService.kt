package ru.krypto.gateway.services

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import ru.krypto.gateway.config.AppConfig
import ru.krypto.gateway.model.AccountResponse
import ru.krypto.gateway.model.BalanceInfo

class BalanceProxyService(
    private val httpClient: HttpClient,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(BalanceProxyService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val accountBaseUrl get() = appConfig.account.baseUrl

    suspend fun getBalances(apiKey: String): AccountResponse {
        val response = httpClient.get("$accountBaseUrl/api/v1/balances") {
            header("X-API-Key", apiKey)
        }

        val responseBody = response.bodyAsText()

        if (response.status != HttpStatusCode.OK) {
            throw GatewayException(response.status, "Balance query failed: $responseBody")
        }

        val json = mapper.readTree(responseBody)
        val balancesNode = json.path("balances")

        // Group balances by asset, compute free = balance - locked
        val balancesByAsset = mutableMapOf<String, BalanceInfo>()

        if (balancesNode.isArray) {
            for (balanceNode in balancesNode) {
                val asset = balanceNode.path("assetSymbol").asText("")
                if (asset.isBlank()) continue

                val totalBalance = balanceNode.path("balance").asText("0")

                // Fetch available balance per asset if not already known
                // For simplicity, we aggregate: free = available, locked = total - available
                val existing = balancesByAsset[asset]
                if (existing == null) {
                    balancesByAsset[asset] = BalanceInfo(
                        asset = asset,
                        free = PrecisionService.balanceToDecimalString(totalBalance, 8),
                        locked = "0"
                    )
                }
            }
        }

        return AccountResponse(balances = balancesByAsset.values.toList())
    }

    suspend fun getBalanceWithAvailable(apiKey: String, asset: String): BalanceInfo? {
        val response = httpClient.get("$accountBaseUrl/api/v1/balances/$asset") {
            header("X-API-Key", apiKey)
            parameter("walletType", "TRADING")
        }

        if (response.status != HttpStatusCode.OK) return null

        val responseBody = response.bodyAsText()
        val json = mapper.readTree(responseBody)

        val balanceStr = json.path("balance").asText("0")
        val availableStr = json.path("available_balance").asText("0")
        val balanceBI = balanceStr.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
        val availableBI = availableStr.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
        val lockedBI = (balanceBI - availableBI).max(java.math.BigInteger.ZERO)

        return BalanceInfo(
            asset = asset,
            free = PrecisionService.balanceToDecimalString(availableStr, 8),
            locked = PrecisionService.balanceToDecimalString(lockedBI.toString(), 8)
        )
    }
}
