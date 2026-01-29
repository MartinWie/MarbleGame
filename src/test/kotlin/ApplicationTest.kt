package de.mw

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun testRoot() =
        testApplication {
            application {
                module()
            }
            client.get("/").apply {
                assertEquals(HttpStatusCode.OK, status)
            }
        }

    @Test
    fun `join page for invalid game redirects to home with error`() =
        testApplication {
            application {
                module()
            }
            client
                .config {
                    followRedirects = false
                }.get("/game/invalid123/join")
                .apply {
                    assertEquals(HttpStatusCode.Found, status)
                    assertTrue(headers["Location"]?.contains("error=game_not_found") == true)
                }
        }

    @Test
    fun `home page shows error message when error param present`() =
        testApplication {
            application {
                module()
            }
            client.get("/?error=game_not_found").apply {
                assertEquals(HttpStatusCode.OK, status)
                val body = bodyAsText()
                assertTrue(body.contains("Game not found"))
            }
        }

    @Test
    fun `home page without error param has no error message`() =
        testApplication {
            application {
                module()
            }
            client.get("/").apply {
                assertEquals(HttpStatusCode.OK, status)
                val body = bodyAsText()
                assertTrue(!body.contains("error-message"))
            }
        }

    // ==================== Language Detection Tests ====================

    @Test
    fun `home page uses German when Accept-Language is de`() =
        testApplication {
            application {
                module()
            }
            client
                .get("/") {
                    header("Accept-Language", "de")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val body = bodyAsText()
                    assertTrue(body.contains("Murmelspiel") || body.contains("Neues Spiel"))
                }
        }

    @Test
    fun `home page uses English when Accept-Language is en`() =
        testApplication {
            application {
                module()
            }
            client
                .get("/") {
                    header("Accept-Language", "en")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val body = bodyAsText()
                    assertTrue(body.contains("Marble Game") || body.contains("Create"))
                }
        }

    @Test
    fun `home page uses English when Accept-Language is missing`() =
        testApplication {
            application {
                module()
            }
            client.get("/").apply {
                assertEquals(HttpStatusCode.OK, status)
                val body = bodyAsText()
                assertTrue(body.contains("Marble Game") || body.contains("Create"))
            }
        }

    @Test
    fun `home page uses German with complex Accept-Language header`() =
        testApplication {
            application {
                module()
            }
            client
                .get("/") {
                    header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val body = bodyAsText()
                    assertTrue(body.contains("Murmelspiel") || body.contains("Neues Spiel"))
                }
        }

    @Test
    fun `home page falls back to English for unknown language`() =
        testApplication {
            application {
                module()
            }
            client
                .get("/") {
                    header("Accept-Language", "fr-FR")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val body = bodyAsText()
                    // Falls back to English since French is not supported
                    assertTrue(body.contains("Marble Game") || body.contains("Create"))
                }
        }

    @Test
    fun `home page handles invalid Accept-Language header gracefully`() =
        testApplication {
            application {
                module()
            }
            client
                .get("/") {
                    header("Accept-Language", "invalid")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    // Should fall back to English
                    val body = bodyAsText()
                    assertTrue(body.contains("Marble Game") || body.contains("Create"))
                }
        }

    @Test
    fun `join page uses German translation`() =
        testApplication {
            application {
                module()
            }
            // Create a game first
            val game = GameManager.createGame("test-session")
            try {
                client
                    .get("/game/${game.id}/join") {
                        header("Accept-Language", "de")
                    }.apply {
                        assertEquals(HttpStatusCode.OK, status)
                        val body = bodyAsText()
                        assertTrue(body.contains("beitreten") || body.contains("Spiel"))
                    }
            } finally {
                GameManager.removeGame(game.id)
            }
        }
}
