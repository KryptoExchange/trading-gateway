package ru.krypto.gateway.auth

import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import ru.krypto.gateway.config.AppConfig
import java.security.MessageDigest
import java.util.*

data class ApiKeyPrincipal(
    val userId: UUID,
    val canRead: Boolean,
    val canTrade: Boolean,
    val canWithdraw: Boolean
) : Principal

class ApiKeyAuthProvider(
    private val appConfig: AppConfig,
    private val jedisPool: JedisPool,
    private val rpcValidator: suspend (String) -> ApiKeyPrincipal?
) {
    private val logger = LoggerFactory.getLogger(ApiKeyAuthProvider::class.java)

    suspend fun validate(apiKey: String): ApiKeyPrincipal? {
        if (apiKey.isBlank()) return null

        val cacheKey = "apikey:gw:${hashApiKey(apiKey)}"

        // Check Redis cache
        try {
            jedisPool.resource.use { jedis ->
                val cached = jedis.get(cacheKey)
                if (cached != null) {
                    return parseCachedPrincipal(cached)
                }
            }
        } catch (e: Exception) {
            logger.warn("Redis cache read failed for API key validation: {}", e.message)
        }

        // Validate via kRPC to apikey-service
        val principal = rpcValidator(apiKey) ?: return null

        // Cache the result
        try {
            jedisPool.resource.use { jedis ->
                val value = "${principal.userId}|${principal.canRead}|${principal.canTrade}|${principal.canWithdraw}"
                jedis.setex(cacheKey, appConfig.apiKeyCacheTtlSeconds, value)
            }
        } catch (e: Exception) {
            logger.warn("Redis cache write failed for API key validation: {}", e.message)
        }

        return principal
    }

    private fun parseCachedPrincipal(cached: String): ApiKeyPrincipal? {
        return try {
            val parts = cached.split("|")
            ApiKeyPrincipal(
                userId = UUID.fromString(parts[0]),
                canRead = parts[1].toBoolean(),
                canTrade = parts[2].toBoolean(),
                canWithdraw = parts[3].toBoolean()
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse cached API key principal: {}", e.message)
            null
        }
    }

    private fun hashApiKey(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(apiKey.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes).take(32)
    }
}

/**
 * Custom authentication provider that reads API key from X-API-Key header.
 */
fun AuthenticationConfig.apiKeyAuth(
    name: String = "auth-api-key",
    provider: ApiKeyAuthProvider
) {
    bearer(name) {
        realm = "krypto-api"
        // Extract API key from X-API-Key header or Authorization: Bearer header
        authHeader { call ->
            val apiKey = call.request.header("X-API-Key")
            if (apiKey != null) {
                HttpAuthHeader.Single("Bearer", apiKey)
            } else {
                try {
                    call.request.parseAuthorizationHeader()
                } catch (e: Exception) {
                    null
                }
            }
        }
        authenticate { credential ->
            provider.validate(credential.token)
        }
    }
}

suspend fun io.ktor.server.routing.RoutingContext.requireApiKeyPermission(
    permission: String
): Boolean {
    val principal = call.principal<ApiKeyPrincipal>()
    if (principal == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
        return false
    }

    val hasPermission = when (permission) {
        "READ" -> principal.canRead
        "TRADE" -> principal.canTrade
        "WITHDRAW" -> principal.canWithdraw
        else -> false
    }

    if (!hasPermission) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Insufficient API key permissions", "required" to permission.lowercase())
        )
        return false
    }
    return true
}

fun io.ktor.server.routing.RoutingContext.getUserId(): UUID? {
    return call.principal<ApiKeyPrincipal>()?.userId
}

fun io.ktor.server.routing.RoutingContext.getApiKey(): String? {
    return call.request.header("X-API-Key")
        ?: call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
}
