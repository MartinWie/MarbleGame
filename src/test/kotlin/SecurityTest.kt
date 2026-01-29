package de.mw

import kotlin.test.*

class SecurityTest {
    private fun createGameWithPlayers(vararg names: String): Game {
        val game = Game(creatorSessionId = "creator", random = kotlin.random.Random(1))
        names.forEachIndexed { index, name ->
            val sessionId = if (index == 0) "creator" else "player$index"
            game.addPlayer(sessionId, name).also { it.connected = true }
        }
        return game
    }

    // ==================== XSS Prevention Tests ====================

    @Test
    fun `player name with HTML tags is stored as-is in Player object`() {
        val game = Game(creatorSessionId = "creator")
        val maliciousName = "<script>alert('xss')</script>"

        game.addPlayer("creator", maliciousName)

        // The name is stored as-is (escaping happens at render time)
        assertEquals(maliciousName, game.players["creator"]?.name)
    }

    @Test
    fun `player name with special characters is stored correctly`() {
        val game = Game(creatorSessionId = "creator")
        val specialName = "Bob & Alice <friends> \"quoted\" 'apostrophe'"

        game.addPlayer("creator", specialName)

        assertEquals(specialName, game.players["creator"]?.name)
    }

    @Test
    fun `pending player name with HTML tags is stored correctly`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        val maliciousName = "<img src=x onerror=alert('xss')>"

        game.addPendingPlayer("pending1", maliciousName)

        assertEquals(maliciousName, game.players["pending1"]?.name)
    }

    @Test
    fun `round result stores placer name correctly for later escaping`() {
        val game = Game(creatorSessionId = "creator", random = kotlin.random.Random(1))
        val maliciousName = "<script>evil()</script>"
        game.addPlayer("creator", maliciousName).connected = true
        game.addPlayer("player1", "Bob").connected = true
        game.startGame()
        game.placeMarbles("creator", 2)
        game.makeGuess("player1", Guess.EVEN)

        val result = game.resolveRound()

        // Placer name is stored as-is; escaping happens at render time
        assertNotNull(result)
        assertEquals(maliciousName, result.placerName)
    }

    @Test
    fun `round result stores winner names correctly for later escaping`() {
        val game = Game(creatorSessionId = "creator", random = kotlin.random.Random(1))
        val maliciousName = "<b onmouseover=alert('xss')>hover me</b>"
        game.addPlayer("creator", "Alice").connected = true
        game.addPlayer("player1", maliciousName).connected = true
        game.startGame()
        game.placeMarbles("creator", 2) // Even
        game.makeGuess("player1", Guess.EVEN) // Correct guess

        val result = game.resolveRound()

        assertNotNull(result)
        assertTrue(result.winners.contains(maliciousName))
    }

    @Test
    fun `round result stores loser names correctly for later escaping`() {
        val game = Game(creatorSessionId = "creator", random = kotlin.random.Random(1))
        val maliciousName = "<iframe src='evil.com'></iframe>"
        game.addPlayer("creator", "Alice").connected = true
        game.addPlayer("player1", maliciousName).connected = true
        game.startGame()
        game.placeMarbles("creator", 2) // Even
        game.makeGuess("player1", Guess.ODD) // Wrong guess

        val result = game.resolveRound()

        assertNotNull(result)
        assertTrue(result.losers.contains(maliciousName))
    }
}
