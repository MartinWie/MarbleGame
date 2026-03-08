package de.mw

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PROP_ALLOWED_ORIGINS = "realtime.allowed.origins"
private const val PROP_ALLOW_NULL_ORIGIN = "realtime.allow.null.origin"
private const val PROP_CHESS_RESUME_STALE_PING_MS = "realtime.chess.resume.stale.ping.ms"

class RouteSharedTest {
    private inline fun withRealtimeConfig(
        allowedOrigins: String? = null,
        allowNullOrigin: String? = null,
        chessResumeStalePingMs: String? = null,
        block: () -> Unit,
    ) {
        val previousAllowed = System.getProperty(PROP_ALLOWED_ORIGINS)
        val previousAllowNull = System.getProperty(PROP_ALLOW_NULL_ORIGIN)
        val previousResumeStalePing = System.getProperty(PROP_CHESS_RESUME_STALE_PING_MS)
        try {
            if (allowedOrigins != null) {
                System.setProperty(PROP_ALLOWED_ORIGINS, allowedOrigins)
            } else {
                System.clearProperty(PROP_ALLOWED_ORIGINS)
            }
            if (allowNullOrigin != null) {
                System.setProperty(PROP_ALLOW_NULL_ORIGIN, allowNullOrigin)
            } else {
                System.clearProperty(PROP_ALLOW_NULL_ORIGIN)
            }
            if (chessResumeStalePingMs != null) {
                System.setProperty(PROP_CHESS_RESUME_STALE_PING_MS, chessResumeStalePingMs)
            } else {
                System.clearProperty(PROP_CHESS_RESUME_STALE_PING_MS)
            }
            block()
        } finally {
            if (previousAllowed != null) {
                System.setProperty(PROP_ALLOWED_ORIGINS, previousAllowed)
            } else {
                System.clearProperty(PROP_ALLOWED_ORIGINS)
            }
            if (previousAllowNull != null) {
                System.setProperty(PROP_ALLOW_NULL_ORIGIN, previousAllowNull)
            } else {
                System.clearProperty(PROP_ALLOW_NULL_ORIGIN)
            }
            if (previousResumeStalePing != null) {
                System.setProperty(PROP_CHESS_RESUME_STALE_PING_MS, previousResumeStalePing)
            } else {
                System.clearProperty(PROP_CHESS_RESUME_STALE_PING_MS)
            }
        }
    }

    private fun ApplicationTestBuilder.installOriginProbe() {
        application {
            module()
            routing {
                get("/_test/origin") {
                    call.respondText(if (isAllowedWebSocketOrigin(call)) "ok" else "blocked")
                }
            }
        }
    }

    @Test
    fun `websocket origin check allows matching host header with forwarded origin`() {
        withRealtimeConfig(allowedOrigins = null, allowNullOrigin = null) {
            testApplication {
                installOriginProbe()

                val response =
                    client.get("/_test/origin") {
                        header(HttpHeaders.Origin, "https://games.7mw.de")
                        header(HttpHeaders.Host, "games.7mw.de")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("ok", response.bodyAsText())
            }
        }
    }

    @Test
    fun `websocket origin check rejects null origin by default`() {
        withRealtimeConfig(allowedOrigins = null, allowNullOrigin = null) {
            testApplication {
                installOriginProbe()

                val response =
                    client.get("/_test/origin") {
                        header(HttpHeaders.Origin, "null")
                        header(HttpHeaders.Host, "localhost")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("blocked", response.bodyAsText())
            }
        }
    }

    @Test
    fun `websocket origin check rejects mismatched host when allowlist not set`() {
        withRealtimeConfig(allowedOrigins = null, allowNullOrigin = null) {
            testApplication {
                installOriginProbe()

                val response =
                    client.get("/_test/origin") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        header(HttpHeaders.Host, "games.7mw.de")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("blocked", response.bodyAsText())
            }
        }
    }

    @Test
    fun `websocket origin check with allowlist allows only configured origins`() {
        withRealtimeConfig(allowedOrigins = "https://games.7mw.de", allowNullOrigin = "false") {
            testApplication {
                installOriginProbe()

                val allowed =
                    client.get("/_test/origin") {
                        header(HttpHeaders.Origin, "https://games.7mw.de")
                        header(HttpHeaders.Host, "localhost")
                    }

                val blockedSameHostFallback =
                    client.get("/_test/origin") {
                        header(HttpHeaders.Origin, "https://localhost")
                        header(HttpHeaders.Host, "localhost")
                    }

                assertEquals("ok", allowed.bodyAsText())
                assertEquals("blocked", blockedSameHostFallback.bodyAsText())
            }
        }
    }

    @Test
    fun `websocket origin check allows null origin only when explicitly enabled`() {
        withRealtimeConfig(allowedOrigins = "https://games.7mw.de", allowNullOrigin = "true") {
            testApplication {
                installOriginProbe()

                val response =
                    client.get("/_test/origin") {
                        header(HttpHeaders.Origin, "null")
                        header(HttpHeaders.Host, "games.7mw.de")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("ok", response.bodyAsText())
            }
        }
    }

    @Test
    fun `chess resume stale ping threshold respects override and clamp`() {
        withRealtimeConfig(chessResumeStalePingMs = "25000") {
            assertEquals(25_000L, RealtimeConfig.chessResumeStalePingMs)
        }

        withRealtimeConfig(chessResumeStalePingMs = "1000") {
            assertEquals(5_000L, RealtimeConfig.chessResumeStalePingMs)
        }

        withRealtimeConfig(chessResumeStalePingMs = "999999") {
            assertEquals(120_000L, RealtimeConfig.chessResumeStalePingMs)
        }
    }
}
