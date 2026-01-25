package de.mw

import io.ktor.server.application.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class UserSession(
    val id: String,
    val playerName: String? = null,
)

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module() {
    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 86400 // 24 hours
            cookie.httpOnly = true // Prevent JavaScript access (XSS protection)
            // cookie.secure = true // Uncomment for production with HTTPS
            cookie.extensions["SameSite"] = "Lax" // CSRF protection
        }
    }

    // Intercept all requests to ensure a session exists
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.sessions.get<UserSession>() == null) {
            val sessionId = UUID.randomUUID().toString().substring(0, 8) // Short ID
            call.sessions.set(UserSession(id = sessionId))
        }
    }

    configureTemplating()
    configureRouting()
}
