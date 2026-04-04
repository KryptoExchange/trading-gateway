package ru.krypto.gateway.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import org.koin.ktor.ext.inject
import ru.krypto.gateway.auth.ApiKeyAuthProvider
import ru.krypto.gateway.auth.apiKeyAuth

fun Application.configureAuthentication() {
    val apiKeyAuthProvider: ApiKeyAuthProvider by inject()

    install(Authentication) {
        apiKeyAuth(provider = apiKeyAuthProvider)
    }
}
