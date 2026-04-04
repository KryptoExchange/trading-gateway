package ru.krypto.gateway.plugins

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import org.koin.ktor.ext.inject
import ru.krypto.gateway.config.AppConfig
import kotlin.time.Duration.Companion.seconds

fun Application.configureWebSockets() {
    val appConfig: AppConfig by inject()

    install(WebSockets) {
        pingPeriod = appConfig.webSocket.pingIntervalSeconds.seconds
        timeout = appConfig.webSocket.timeoutSeconds.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}
