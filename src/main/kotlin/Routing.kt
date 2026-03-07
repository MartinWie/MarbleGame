package de.mw

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("Routing")

fun Application.configureRouting() {
    install(WebSockets) {
        pingPeriod = null
        timeout = 20.seconds
        maxFrameSize = 64 * 1024L
        masking = false
    }
    RealtimeMaintenanceService.start()
    monitor.subscribe(ApplicationStopped) {
        RealtimeMaintenanceService.stop()
    }
    logger.info("Realtime transport mode: ws")

    routing {
        registerPageRoutes()
        registerMarblesRoutes(::isAllowedWebSocketOrigin)
        registerChessRoutes(::isAllowedWebSocketOrigin)
        registerSharedRoutes()
    }
}
