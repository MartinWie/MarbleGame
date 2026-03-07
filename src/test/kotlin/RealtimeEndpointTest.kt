package de.mw

import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Integration tests for HTTP endpoints and HTMX redirect handling.
 *
 * These tests verify:
 * - Game creation with HX-Redirect header for HTMX requests
 * - Game joining with HX-Redirect header for HTMX requests
 * - Game page access control
 * - Player state after joining
 */
class RealtimeEndpointTest {
    private fun HttpResponse.firstSetCookieValue(name: String): String? =
        headers
            .getAll(HttpHeaders.SetCookie)
            ?.asSequence()
            ?.map { it.substringBefore(';').split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)

    // ==================== HTMX Redirect Tests ====================

    @Test
    fun `game create with HTMX request returns HX-Redirect header`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // First request to get a session
            client.get("/")

            // Create game with HTMX header
            val response =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "TestPlayer") },
                ) {
                    header("HX-Request", "true")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers["HX-Redirect"])
            assertTrue(response.headers["HX-Redirect"]!!.startsWith("/game/"))
        }

    @Test
    fun `chess create accepts timed and streamer options`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")

            val response =
                client.submitForm(
                    url = "/chess/create",
                    formParameters =
                        parameters {
                            append("playerName", "OptionHost")
                            append("timedMode", "on")
                            append("clockMinutes", "7")
                            append("streamerMode", "on")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: error("Missing redirect")
            val gameId = location.substringAfterLast("/chess/")
            val game = ChessGameManager.getGame(gameId) ?: error("Game not found")

            assertTrue(game.timedModeEnabled)
            assertTrue(game.streamerModeEnabled)
            assertEquals(7 * 60, game.whiteClockSecondsRemaining())
            assertEquals(7 * 60, game.blackClockSecondsRemaining())

            ChessGameManager.removeGame(gameId)
        }

    @Test
    fun `chess legal-moves returns empty for non participant`() =
        testApplication {
            application { module() }

            val ownerClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            ownerClient.get("/")
            val createResponse =
                ownerClient.submitForm(
                    url = "/chess/create",
                    formParameters =
                        parameters {
                            append("playerName", "Owner")
                            append("timedMode", "on")
                        },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/chess/")

            val outsiderClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            outsiderClient.get("/")
            val forbidden = outsiderClient.get("/chess/$gameId/legal-moves?from=e2")
            assertEquals(HttpStatusCode.OK, forbidden.status)
            assertEquals("", forbidden.bodyAsText())

            val ownerWhiteResult = ownerClient.get("/chess/$gameId/legal-moves?from=e2")
            assertEquals(HttpStatusCode.OK, ownerWhiteResult.status)
            val ownerBlackResult = ownerClient.get("/chess/$gameId/legal-moves?from=e7")
            assertEquals(HttpStatusCode.OK, ownerBlackResult.status)

            ChessGameManager.removeGame(gameId)
        }

    @Test
    fun `chess websocket endpoint emits ping and update frames`() =
        testApplication {
            application { module() }

            val ownerClient =
                createClient {
                    install(HttpCookies)
                    install(WebSockets)
                    followRedirects = false
                }
            ownerClient.get("/")
            val createResponse =
                ownerClient.submitForm(
                    url = "/chess/create",
                    formParameters = parameters { append("playerName", "WsOwner") },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/chess/")

            ownerClient.webSocket("/ws/chess/$gameId") {
                var sawPing = false
                var sawUpdate = false

                withTimeout(15_000) {
                    while (!sawPing || !sawUpdate) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val text = frame.readText()
                        if (text == "__PING__") {
                            sawPing = true
                        }
                        if (text.startsWith("__CHESS_UPDATE__:")) {
                            sawUpdate = true
                        }
                    }
                }

                assertTrue(sawUpdate)
                assertTrue(sawPing)
            }

            ChessGameManager.removeGame(gameId)
        }

    @Test
    fun `marbles websocket endpoint emits ping and update frames`() =
        testApplication {
            application { module() }

            val ownerClient =
                createClient {
                    install(HttpCookies)
                    install(WebSockets)
                    followRedirects = false
                }
            ownerClient.get("/")
            val createResponse =
                ownerClient.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "WsOwner") },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/game/")

            ownerClient.webSocket("/ws/game/$gameId") {
                var sawPing = false
                var sawUpdate = false

                withTimeout(15_000) {
                    while (!sawPing || !sawUpdate) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val text = frame.readText()
                        if (text == "__PING__") {
                            sawPing = true
                        }
                        if (text.startsWith("__GAME_UPDATE__:")) {
                            sawUpdate = true
                        }
                    }
                }

                assertTrue(sawUpdate)
                assertTrue(sawPing)
            }

            GameManager.removeGame(gameId)
        }

    @Test
    fun `marbles websocket reconnect triggers update to other player only`() =
        testApplication {
            application { module() }

            val hostClient =
                createClient {
                    install(HttpCookies)
                    install(WebSockets)
                    followRedirects = false
                }
            hostClient.get("/")
            val createResponse =
                hostClient.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "Host") },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/game/")

            val joinClient =
                createClient {
                    install(HttpCookies)
                    install(WebSockets)
                    followRedirects = false
                }
            val joinRoot = joinClient.get("/")
            val joinSession = joinRoot.firstSetCookieValue("user_session") ?: error("No join session cookie")
            joinClient.submitForm(
                url = "/game/join",
                formParameters =
                    parameters {
                        append("playerName", "Guest")
                        append("gameId", gameId)
                    },
            )

            val reconnectClient =
                createClient {
                    install(WebSockets)
                    followRedirects = false
                }

            hostClient.webSocket("/ws/game/$gameId") {
                var hostSawReconnect = false

                withTimeout(5_000) {
                    while (true) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val text = frame.readText()
                        if (text.startsWith("__GAME_UPDATE__:")) {
                            break
                        }
                    }
                }

                reconnectClient.webSocket(
                    request = {
                        url("/ws/game/$gameId")
                        header(HttpHeaders.Cookie, "user_session=$joinSession")
                    },
                ) {
                    // consume initial payload quickly
                    withTimeout(5_000) {
                        while (true) {
                            val frame = incoming.receive() as? Frame.Text ?: continue
                            if (frame.readText().startsWith("__GAME_UPDATE__:")) break
                        }
                    }
                }

                withTimeout(8_000) {
                    while (!hostSawReconnect) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val text = frame.readText()
                        if (text.startsWith("__GAME_UPDATE__:") && text.contains("Guest")) {
                            hostSawReconnect = true
                        }
                    }
                }

                assertTrue(hostSawReconnect)
            }

            GameManager.removeGame(gameId)
        }

    @Test
    fun `chess websocket reconnect triggers update to other player only`() =
        testApplication {
            application { module() }

            val hostClient =
                createClient {
                    install(HttpCookies)
                    install(WebSockets)
                    followRedirects = false
                }
            hostClient.get("/")
            val createResponse =
                hostClient.submitForm(
                    url = "/chess/create",
                    formParameters = parameters { append("playerName", "Host") },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/chess/")

            val joinClient =
                createClient {
                    install(HttpCookies)
                    install(WebSockets)
                    followRedirects = false
                }
            val joinRoot = joinClient.get("/")
            val joinSession = joinRoot.firstSetCookieValue("user_session") ?: error("No join session cookie")
            joinClient.submitForm(
                url = "/chess/join",
                formParameters =
                    parameters {
                        append("playerName", "Guest")
                        append("gameId", gameId)
                    },
            )

            val reconnectClient =
                createClient {
                    install(WebSockets)
                    followRedirects = false
                }

            hostClient.webSocket("/ws/chess/$gameId") {
                var hostSawReconnect = false

                withTimeout(5_000) {
                    while (true) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val text = frame.readText()
                        if (text.startsWith("__CHESS_UPDATE__:")) {
                            break
                        }
                    }
                }

                reconnectClient.webSocket(
                    request = {
                        url("/ws/chess/$gameId")
                        header(HttpHeaders.Cookie, "user_session=$joinSession")
                    },
                ) {
                    withTimeout(5_000) {
                        while (true) {
                            val frame = incoming.receive() as? Frame.Text ?: continue
                            if (frame.readText().startsWith("__CHESS_UPDATE__:")) break
                        }
                    }
                }

                withTimeout(8_000) {
                    while (!hostSawReconnect) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val text = frame.readText()
                        if (text.startsWith("__CHESS_UPDATE__:") && text.contains("Guest")) {
                            hostSawReconnect = true
                        }
                    }
                }

                assertTrue(hostSawReconnect)
            }

            ChessGameManager.removeGame(gameId)
        }

    @Test
    fun `legacy sse endpoints are not available`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "NoSseHost") },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/game/")

            val oldSseResponse = client.get("/game/$gameId/events")
            assertEquals(HttpStatusCode.NotFound, oldSseResponse.status)

            GameManager.removeGame(gameId)
        }

    @Test
    fun `game and chess pages include shared realtime scripts`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")

            val marblesCreate =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "ScriptHost1") },
                )
            val marblesUrl = marblesCreate.headers["Location"]!!
            val marblesBody = client.get(marblesUrl).bodyAsText()

            val chessCreate =
                client.submitForm(
                    url = "/chess/create",
                    formParameters = parameters { append("playerName", "ScriptHost2") },
                )
            val chessUrl = chessCreate.headers["Location"]!!
            val chessBody = client.get(chessUrl).bodyAsText()

            assertTrue(marblesBody.contains("/static/realtime.js"))
            assertTrue(marblesBody.contains("/static/ui-shared.js"))
            assertTrue(marblesBody.contains("/static/game.js"))

            assertTrue(chessBody.contains("/static/realtime.js"))
            assertTrue(chessBody.contains("/static/ui-shared.js"))
            assertTrue(chessBody.contains("/static/chess.js"))

            GameManager.removeGame(marblesUrl.substringAfterLast("/game/"))
            ChessGameManager.removeGame(chessUrl.substringAfterLast("/chess/"))
        }

    @Test
    fun `server maintenance ticker auto-restarts chess game over`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/chess/create",
                    formParameters = parameters { append("playerName", "TickerHost") },
                )
            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/chess/")
            val game = ChessGameManager.getGame(gameId) ?: error("Game not found")

            game.forceGameOverForTesting(winnerSessionId = null, reason = "stalemate")
            game.scheduleAutoRestart(delaySeconds = 1)

            var restarted = false
            repeat(30) {
                Thread.sleep(100)
                if (game.phase != ChessPhase.GAME_OVER) {
                    restarted = true
                    return@repeat
                }
            }

            assertTrue(restarted)

            ChessGameManager.removeGame(gameId)
        }

    @Test
    fun `game create without HTMX header returns regular redirect`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // First request to get a session
            client.get("/")

            // Create game without HTMX header
            val response =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "TestPlayer") },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertNotNull(response.headers["Location"])
            assertTrue(response.headers["Location"]!!.startsWith("/game/"))
            assertNull(response.headers["HX-Redirect"])
        }

    @Test
    fun `game join with HTMX request returns HX-Redirect header`() =
        testApplication {
            application { module() }

            // Create a game first
            val game = GameManager.createGame("creator-session")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                        followRedirects = false
                    }

                // First request to get a session
                client.get("/")

                // Join game with HTMX header
                val response =
                    client.submitForm(
                        url = "/game/join",
                        formParameters =
                            parameters {
                                append("playerName", "JoiningPlayer")
                                append("gameId", game.id)
                            },
                    ) {
                        header("HX-Request", "true")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertNotNull(response.headers["HX-Redirect"])
                assertEquals("/game/${game.id}", response.headers["HX-Redirect"])
            } finally {
                GameManager.removeGame(game.id)
            }
        }

    @Test
    fun `game join without HTMX header returns regular redirect`() =
        testApplication {
            application { module() }

            // Create a game first
            val game = GameManager.createGame("creator-session")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                        followRedirects = false
                    }

                // First request to get a session
                client.get("/")

                // Join game without HTMX header
                val response =
                    client.submitForm(
                        url = "/game/join",
                        formParameters =
                            parameters {
                                append("playerName", "JoiningPlayer")
                                append("gameId", game.id)
                            },
                    )

                assertEquals(HttpStatusCode.Found, response.status)
                assertNotNull(response.headers["Location"])
                assertEquals("/game/${game.id}", response.headers["Location"])
                assertNull(response.headers["HX-Redirect"])
            } finally {
                GameManager.removeGame(game.id)
            }
        }

    // ==================== Game Page Access Tests ====================

    @Test
    fun `game page redirects to join page if player not in game`() =
        testApplication {
            application { module() }

            // Create a game with one player
            val game = GameManager.createGame("creator-session")
            game.addPlayer("creator-session", "Creator", "en")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                        followRedirects = false
                    }

                // First request to get a session (different from creator)
                client.get("/")

                // Try to access game page without being a player
                val response = client.get("/game/${game.id}")

                assertEquals(HttpStatusCode.Found, response.status)
                assertTrue(response.headers["Location"]!!.contains("/game/${game.id}/join"))
            } finally {
                GameManager.removeGame(game.id)
            }
        }

    @Test
    fun `game page loads for player in game`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // Get session and create game
            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "TestPlayer") },
                )

            val gameUrl = createResponse.headers["Location"]!!

            // Access game page
            val gameResponse = client.get(gameUrl)

            assertEquals(HttpStatusCode.OK, gameResponse.status)
            val body = gameResponse.bodyAsText()
            assertTrue(body.contains("game-content"))
            assertTrue(body.contains("game.js"))
        }

    @Test
    fun `game page includes game js script`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // Get session and create game
            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "TestPlayer") },
                )

            val gameUrl = createResponse.headers["Location"]!!
            val gameResponse = client.get(gameUrl)
            val body = gameResponse.bodyAsText()

            val gameId = gameUrl.substringAfterLast("/game/")

            // Verify game.js is included
            assertTrue(body.contains("/static/game.js"), "game.js should be included")
            assertTrue(body.contains("initGame("), "initGame should be called")

            // Cleanup
            GameManager.removeGame(gameId)
        }

    @Test
    fun `game page returns 404 for non-existent game`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                }

            client.get("/")
            val response = client.get("/game/nonexistent123")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(
                response.bodyAsText().contains("Game not found") ||
                    response.bodyAsText().contains("Spiel nicht gefunden"),
            )
        }

    @Test
    fun `direct join URL redirects to join page for new players`() =
        testApplication {
            application { module() }

            val game = GameManager.createGame("creator-session")
            game.addPlayer("creator-session", "Creator", "en")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                        followRedirects = false
                    }

                // Get new session without saved name
                client.get("/")

                // Access join URL
                val response = client.get("/game/${game.id}/join")

                // Should show join page since no saved name
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("playerName"))
                assertTrue(body.contains("form"))
            } finally {
                GameManager.removeGame(game.id)
            }
        }

    // ==================== Player State Tests ====================

    @Test
    fun `creating game adds player with correct name`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val response =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "MyPlayerName") },
                )

            // Extract game ID from redirect
            val gameId = response.headers["Location"]!!.substringAfterLast("/game/")
            val game = GameManager.getGame(gameId)

            assertNotNull(game)
            assertEquals(1, game.players.size)
            assertEquals(
                "MyPlayerName",
                game.players.values
                    .first()
                    .name,
            )

            // Cleanup
            GameManager.removeGame(gameId)
        }

    @Test
    fun `joining game adds player with correct name`() =
        testApplication {
            application { module() }

            val game = GameManager.createGame("creator-session")
            game.addPlayer("creator-session", "Creator", "en")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                    }

                client.get("/")
                client.submitForm(
                    url = "/game/join",
                    formParameters =
                        parameters {
                            append("playerName", "JoinerName")
                            append("gameId", game.id)
                        },
                )

                assertEquals(2, game.players.size)
                assertTrue(game.players.values.any { it.name == "JoinerName" })
            } finally {
                GameManager.removeGame(game.id)
            }
        }

    @Test
    fun `empty player name defaults to Player`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val response =
                client.submitForm(
                    url = "/game/create",
                    formParameters =
                        parameters {
                            append("playerName", "   ") // Only whitespace
                        },
                )

            val gameId = response.headers["Location"]!!.substringAfterLast("/game/")
            val game = GameManager.getGame(gameId)

            assertNotNull(game)
            assertEquals(
                "Player",
                game.players.values
                    .first()
                    .name,
            )

            // Cleanup
            GameManager.removeGame(gameId)
        }

    // ==================== Game State Rendering Tests ====================

    @Test
    fun `game page shows waiting status for single player`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "Solo") },
                )

            // Follow redirect manually to game page
            val gameUrl = createResponse.headers["Location"]!!
            val gameResponse = client.get(gameUrl)
            val body = gameResponse.bodyAsText()

            // Extract game ID for cleanup
            val gameId = gameUrl.substringAfterLast("/game/")

            // Should indicate waiting for players
            assertTrue(
                body.contains("waiting") ||
                    body.contains("Waiting") ||
                    body.contains("warten") ||
                    body.contains("Warten"),
                "Should show waiting status. Body: ${body.take(500)}",
            )

            // Cleanup
            GameManager.removeGame(gameId)
        }

    @Test
    fun `game page shows player list`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "HostPlayer") },
                )

            val gameUrl = createResponse.headers["Location"]!!
            val gameResponse = client.get(gameUrl)
            val body = gameResponse.bodyAsText()

            val gameId = gameUrl.substringAfterLast("/game/")

            assertTrue(body.contains("HostPlayer"), "Should show player name in list")

            // Cleanup
            GameManager.removeGame(gameId)
        }

    // ==================== Share Button Tests ====================

    @Test
    fun `game page includes share button with correct URL`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "Host") },
                )

            val gameUrl = createResponse.headers["Location"]!!
            val gameResponse = client.get(gameUrl)
            val body = gameResponse.bodyAsText()

            val gameId = gameUrl.substringAfterLast("/game/")

            assertTrue(body.contains("share-btn"), "Should have share button")
            assertTrue(body.contains("sound-btn"), "Should have sound toggle button")
            assertTrue(body.contains("data-share-url=\"/game/$gameId/join\""), "Share URL should point to join page")
            assertTrue(body.contains("qr-btn"), "Should have QR button")
            assertTrue(body.contains("qr-modal"), "Should have QR modal")
            assertFalse(body.contains("qr-close-btn"), "QR modal should not render explicit close button")
            assertTrue(body.contains("qr-image"), "Should have QR image placeholder")

            // Cleanup
            GameManager.removeGame(gameId)
        }

    @Test
    fun `chess page share button follows html data-attribute contract without inline handlers`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/chess/create",
                    formParameters = parameters { append("playerName", "Host") },
                )

            val chessUrl = createResponse.headers["Location"]!!
            val gameResponse = client.get(chessUrl)
            val body = gameResponse.bodyAsText()

            val gameId = chessUrl.substringAfterLast("/chess/")

            assertTrue(body.contains("share-btn"), "Should have share button")
            assertTrue(body.contains("sound-btn"), "Should have sound toggle button")
            assertTrue(body.contains("data-share-url=\"/chess/$gameId/join\""), "Share URL data attribute should point to chess join page")
            assertTrue(body.contains("data-share-text"), "Share text data attribute should be present")
            assertTrue(body.contains("data-copied-text"), "Copied text data attribute should be present")
            assertTrue(body.contains("data-share-title"), "Share title data attribute should be present")
            assertTrue(body.contains("data-share-message"), "Share message data attribute should be present")
            assertTrue(body.contains("qr-btn"), "Should have QR button")
            assertTrue(body.contains("qr-modal"), "Should have QR modal")
            assertFalse(body.contains("qr-close-btn"), "QR modal should not render explicit close button")
            assertTrue(body.contains("qr-image"), "Should have QR image placeholder")

            val shareButtonHtml = Regex("""<button[^>]*id=\"share-btn\"[^>]*>""").find(body)?.value
            assertNotNull(shareButtonHtml, "Share button HTML should be present")
            assertFalse(shareButtonHtml.contains("onclick=\""), "Share button should not use inline onclick")

            assertFalse(body.contains("nativeShare().catch(function() {});"), "Inline native share logic should be removed")
            assertFalse(body.contains("clipboardCopy();"), "Inline clipboard logic should be removed")

            // Cleanup
            ChessGameManager.removeGame(gameId)
        }

    // ==================== Regression Tests ====================

    @Test
    fun `game js script is loaded and init is called with game id`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val createResponse =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "Test") },
                )

            val gameId = createResponse.headers["Location"]!!.substringAfterLast("/game/")

            // Follow redirect to game page
            val gameResponse = client.get("/game/$gameId")
            val body = gameResponse.bodyAsText()

            // Verify the script is properly loaded
            assertTrue(body.contains("""<script src="/static/game.js">"""), "game.js script tag should be present")
            assertTrue(body.contains("initGame('$gameId')"), "initGame should be called with correct game ID")

            // Cleanup
            GameManager.removeGame(gameId)
        }

    @Test
    fun `HX-Redirect contains correct game ID for create`() =
        testApplication {
            application { module() }

            val client =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            client.get("/")
            val response =
                client.submitForm(
                    url = "/game/create",
                    formParameters = parameters { append("playerName", "Test") },
                ) {
                    header("HX-Request", "true")
                }

            val hxRedirect = response.headers["HX-Redirect"]!!
            val gameId = hxRedirect.substringAfterLast("/game/")

            // Verify game exists
            val game = GameManager.getGame(gameId)
            assertNotNull(game, "Game should exist after HX-Redirect")

            // Cleanup
            GameManager.removeGame(gameId)
        }

    @Test
    fun `HX-Redirect contains correct game ID for join`() =
        testApplication {
            application { module() }

            val game = GameManager.createGame("creator-session")
            game.addPlayer("creator-session", "Creator", "en")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                        followRedirects = false
                    }

                client.get("/")
                val response =
                    client.submitForm(
                        url = "/game/join",
                        formParameters =
                            parameters {
                                append("playerName", "Joiner")
                                append("gameId", game.id)
                            },
                    ) {
                        header("HX-Request", "true")
                    }

                val hxRedirect = response.headers["HX-Redirect"]!!
                assertEquals("/game/${game.id}", hxRedirect, "HX-Redirect should point to correct game")
            } finally {
                GameManager.removeGame(game.id)
            }
        }
}
