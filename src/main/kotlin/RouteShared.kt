package de.mw

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

internal fun isAllowedWebSocketOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin] ?: return true
    val normalizedOrigin = origin.removeSuffix("/").lowercase()
    if (RealtimeConfig.allowedOrigins.isEmpty()) {
        val req = call.request.origin
        val scheme = req.scheme.lowercase()
        val host = req.serverHost.lowercase()
        val port = req.serverPort
        val defaultPort = if (scheme == "https") 443 else 80
        val withPort = "$scheme://$host:$port"
        val withoutPort = "$scheme://$host"
        return if (port == defaultPort) {
            normalizedOrigin == withoutPort || normalizedOrigin == withPort
        } else {
            normalizedOrigin == withPort
        }
    }
    return RealtimeConfig.allowedOrigins.contains(normalizedOrigin)
}

internal fun Route.registerSharedRoutes() {
    get("/qr") {
        val target =
            call.request.queryParameters["target"] ?: run {
                call.respondText("Missing target", status = HttpStatusCode.BadRequest)
                return@get
            }

        if (!target.startsWith("/game/") && !target.startsWith("/chess/")) {
            call.respondText("Invalid target", status = HttpStatusCode.BadRequest)
            return@get
        }
        if (!target.endsWith("/join")) {
            call.respondText("Invalid target", status = HttpStatusCode.BadRequest)
            return@get
        }

        val absoluteTarget = "${call.request.origin.scheme}://${call.request.host()}$target"
        val png = QRCodeService.create(absoluteTarget)
        call.respondBytes(png, contentType = ContentType.Image.PNG)
    }

    // Static resources
    staticResources("/static", "static")

    // Root-level icon redirects (browsers/devices request these automatically)
    get("/favicon.ico") {
        call.respondRedirect("/static/favicon.ico", permanent = true)
    }
    get("/apple-touch-icon.png") {
        call.respondRedirect("/static/apple-touch-icon.png", permanent = true)
    }
    get("/apple-touch-icon-precomposed.png") {
        call.respondRedirect("/static/apple-touch-icon.png", permanent = true)
    }
    get("/manifest.json") {
        call.respondRedirect("/static/manifest.json", permanent = true)
    }
}
