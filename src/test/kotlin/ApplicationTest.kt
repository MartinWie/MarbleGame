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
}
