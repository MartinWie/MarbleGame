package de.mw

import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
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
class SSEEndpointTest {
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

    // ==================== SSE Connection Tests ====================

    @Test
    fun `SSE endpoint requires player to be in game`() =
        testApplication {
            application { module() }

            // Create game with one player
            val game = GameManager.createGame("creator-session")
            game.addPlayer("creator-session", "Creator", "en")

            try {
                val client =
                    createClient {
                        install(HttpCookies)
                    }

                // Get session (different from creator)
                client.get("/")

                // Try to connect to SSE without being a player
                // The SSE endpoint should return early (empty response)
                val response = client.get("/game/${game.id}/events")

                // SSE endpoint returns empty/closes when player not in game
                assertEquals(HttpStatusCode.OK, response.status)
            } finally {
                GameManager.removeGame(game.id)
            }
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
            assertTrue(body.contains("/join"), "Share URL should point to join page")

            // Cleanup
            GameManager.removeGame(gameId)
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
