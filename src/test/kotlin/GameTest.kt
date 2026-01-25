package de.mw

import kotlin.test.*

class GameTest {
    private fun createGameWithPlayers(vararg names: String): Game {
        val game = Game(creatorSessionId = "creator")
        names.forEachIndexed { index, name ->
            val sessionId = if (index == 0) "creator" else "player$index"
            game.addPlayer(sessionId, name).also { it.connected = true }
        }
        return game
    }

    // ==================== Game Creation & Setup ====================

    @Test
    fun `new game starts in WAITING_FOR_PLAYERS phase`() {
        val game = Game(creatorSessionId = "creator")
        assertEquals(GamePhase.WAITING_FOR_PLAYERS, game.phase)
    }

    @Test
    fun `game has unique 8-character ID`() {
        val game = Game(creatorSessionId = "creator")
        assertEquals(8, game.id.length)
    }

    @Test
    fun `adding players increases player count`() {
        val game = Game(creatorSessionId = "creator")
        assertEquals(0, game.players.size)

        game.addPlayer("session1", "Alice")
        assertEquals(1, game.players.size)

        game.addPlayer("session2", "Bob")
        assertEquals(2, game.players.size)
    }

    @Test
    fun `adding same player twice updates name but doesn't duplicate`() {
        val game = Game(creatorSessionId = "creator")

        game.addPlayer("session1", "Alice")
        assertEquals(1, game.players.size)
        assertEquals(1, game.allPlayers.size)

        game.addPlayer("session1", "Alice Updated")
        assertEquals(1, game.players.size)
        assertEquals(1, game.allPlayers.size)
        assertEquals("Alice Updated", game.players["session1"]?.name)
    }

    @Test
    fun `removing player decreases player count`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        assertEquals(3, game.players.size)

        game.removePlayer("player1")
        assertEquals(2, game.players.size)
        assertNull(game.players["player1"])
    }

    // ==================== Game Start ====================

    @Test
    fun `cannot start game with less than 2 connected players`() {
        val game = Game(creatorSessionId = "creator")
        game.addPlayer("creator", "Alice").connected = true

        assertFalse(game.startGame())
        assertEquals(GamePhase.WAITING_FOR_PLAYERS, game.phase)
    }

    @Test
    fun `cannot start game with 2 players but only 1 connected`() {
        val game = Game(creatorSessionId = "creator")
        game.addPlayer("creator", "Alice").connected = true
        game.addPlayer("player1", "Bob").connected = false

        assertFalse(game.startGame())
        assertEquals(GamePhase.WAITING_FOR_PLAYERS, game.phase)
    }

    @Test
    fun `can start game with 2 connected players`() {
        val game = createGameWithPlayers("Alice", "Bob")

        assertTrue(game.startGame())
        assertEquals(GamePhase.PLACING_MARBLES, game.phase)
    }

    @Test
    fun `game starts with first player as current player`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertEquals("Alice", game.currentPlayer?.name)
    }

    @Test
    fun `game skips disconnected players when starting`() {
        val game = Game(creatorSessionId = "creator")
        game.addPlayer("creator", "Alice").connected = false
        game.addPlayer("player1", "Bob").connected = true
        game.addPlayer("player2", "Charlie").connected = true

        assertTrue(game.startGame())
        assertEquals("Bob", game.currentPlayer?.name)
    }

    // ==================== Placing Marbles ====================

    @Test
    fun `current player can place marbles`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertTrue(game.placeMarbles("creator", 3))
        assertEquals(3, game.currentMarblesPlaced)
        assertEquals(GamePhase.GUESSING, game.phase)
    }

    @Test
    fun `non-current player cannot place marbles`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertFalse(game.placeMarbles("player1", 3))
        assertEquals(0, game.currentMarblesPlaced)
        assertEquals(GamePhase.PLACING_MARBLES, game.phase)
    }

    @Test
    fun `cannot place zero marbles`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertFalse(game.placeMarbles("creator", 0))
    }

    @Test
    fun `cannot place negative marbles`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertFalse(game.placeMarbles("creator", -1))
    }

    @Test
    fun `cannot place more marbles than player has`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertFalse(game.placeMarbles("creator", 11))
    }

    @Test
    fun `can place all marbles player has`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()

        assertTrue(game.placeMarbles("creator", 10))
        assertEquals(10, game.currentMarblesPlaced)
    }

    @Test
    fun `placing marbles resets all player guesses`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()

        // Set some guesses manually
        game.players["player1"]?.currentGuess = Guess.EVEN
        game.players["player2"]?.currentGuess = Guess.ODD

        game.placeMarbles("creator", 3)

        // All guesses should be reset
        assertNull(game.players["creator"]?.currentGuess)
        assertNull(game.players["player1"]?.currentGuess)
        assertNull(game.players["player2"]?.currentGuess)
    }

    @Test
    fun `cannot place marbles in wrong phase`() {
        val game = createGameWithPlayers("Alice", "Bob")

        // Still in WAITING_FOR_PLAYERS
        assertFalse(game.placeMarbles("creator", 3))
    }

    // ==================== Making Guesses ====================

    @Test
    fun `non-placer can make a guess`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.placeMarbles("creator", 3)

        assertTrue(game.makeGuess("player1", Guess.EVEN))
        assertEquals(Guess.EVEN, game.players["player1"]?.currentGuess)
    }

    @Test
    fun `placer cannot make a guess`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.placeMarbles("creator", 3)

        assertFalse(game.makeGuess("creator", Guess.EVEN))
        assertNull(game.players["creator"]?.currentGuess)
    }

    @Test
    fun `spectator cannot make a guess`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.players["player2"]?.marbles = 0 // Make Charlie a spectator

        game.startGame()
        game.placeMarbles("creator", 3)

        assertFalse(game.makeGuess("player2", Guess.EVEN))
    }

    @Test
    fun `cannot guess in wrong phase`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        // Still in PLACING_MARBLES phase

        assertFalse(game.makeGuess("player1", Guess.EVEN))
    }

    @Test
    fun `player can change guess`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.placeMarbles("creator", 3)

        assertTrue(game.makeGuess("player1", Guess.EVEN))
        assertEquals(Guess.EVEN, game.players["player1"]?.currentGuess)

        assertTrue(game.makeGuess("player1", Guess.ODD))
        assertEquals(Guess.ODD, game.players["player1"]?.currentGuess)
    }

    // ==================== All Players Guessed ====================

    @Test
    fun `allActivePlayersGuessed returns false when no one guessed`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()
        game.placeMarbles("creator", 3)

        assertFalse(game.allActivePlayersGuessed())
    }

    @Test
    fun `allActivePlayersGuessed returns false when only some guessed`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()
        game.placeMarbles("creator", 3)

        game.makeGuess("player1", Guess.EVEN)
        // player2 hasn't guessed yet

        assertFalse(game.allActivePlayersGuessed())
    }

    @Test
    fun `allActivePlayersGuessed returns true when all active players guessed`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()
        game.placeMarbles("creator", 3)

        game.makeGuess("player1", Guess.EVEN)
        game.makeGuess("player2", Guess.ODD)

        assertTrue(game.allActivePlayersGuessed())
    }

    @Test
    fun `allActivePlayersGuessed ignores spectators`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.players["player2"]?.marbles = 0 // Make Charlie a spectator

        game.startGame()
        game.placeMarbles("creator", 3)

        // Only Bob needs to guess (Charlie is spectator)
        game.makeGuess("player1", Guess.EVEN)

        assertTrue(game.allActivePlayersGuessed())
    }

    @Test
    fun `allActivePlayersGuessed ignores disconnected players`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()
        game.placeMarbles("creator", 3)

        // Disconnect Charlie
        game.players["player2"]?.connected = false

        // Only Bob needs to guess (Charlie is disconnected)
        game.makeGuess("player1", Guess.EVEN)

        assertTrue(game.allActivePlayersGuessed())
    }

    @Test
    fun `allActivePlayersGuessed returns true when all other players are spectators`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.players["player1"]?.marbles = 0 // Make Bob a spectator

        game.startGame()
        game.placeMarbles("creator", 3)

        // No one can guess - should return true (empty list = all guessed)
        assertTrue(game.allActivePlayersGuessed())
    }

    // ==================== Reset For New Game ====================

    @Test
    fun `resetForNewGame resets game phase to WAITING_FOR_PLAYERS`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        assertEquals(GamePhase.PLACING_MARBLES, game.phase)

        game.resetForNewGame()

        assertEquals(GamePhase.WAITING_FOR_PLAYERS, game.phase)
    }

    @Test
    fun `resetForNewGame resets all player marbles to 10`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.players["creator"]!!.marbles = 5
        game.players["player1"]!!.marbles = 15

        game.resetForNewGame()

        assertEquals(10, game.players["creator"]!!.marbles)
        assertEquals(10, game.players["player1"]!!.marbles)
    }

    @Test
    fun `resetForNewGame clears player guesses`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.placeMarbles("creator", 3)
        game.makeGuess("player1", Guess.EVEN)

        game.resetForNewGame()

        assertNull(game.players["creator"]!!.currentGuess)
        assertNull(game.players["player1"]!!.currentGuess)
    }

    @Test
    fun `resetForNewGame restores player removed from playerOrder due to grace period`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()

        // Simulate Bob disconnecting and grace period expiring
        game.handlePlayerDisconnect("player1")
        game.players["player1"]!!.disconnectedAt = System.currentTimeMillis() - 31_000
        game.handleGracePeriodExpired("player1")

        // Bob should be removed from playerOrder
        assertEquals(2, game.allPlayers.size)
        assertNull(game.allPlayers.find { it.sessionId == "player1" })

        // Bob reconnects
        game.players["player1"]!!.connected = true

        // Reset game
        game.resetForNewGame()

        // Bob should be back in playerOrder
        assertEquals(3, game.allPlayers.size)
        assertNotNull(game.allPlayers.find { it.sessionId == "player1" })
        assertEquals(10, game.players["player1"]!!.marbles)
    }

    @Test
    fun `resetForNewGame only includes connected players in playerOrder`() {
        val game = createGameWithPlayers("Alice", "Bob", "Charlie")
        game.startGame()

        // Disconnect Charlie (but don't expire grace period)
        game.players["player2"]!!.connected = false

        game.resetForNewGame()

        // Only Alice and Bob should be in playerOrder
        assertEquals(2, game.allPlayers.size)
        assertNotNull(game.allPlayers.find { it.sessionId == "creator" })
        assertNotNull(game.allPlayers.find { it.sessionId == "player1" })
        assertNull(game.allPlayers.find { it.sessionId == "player2" })
    }

    @Test
    fun `resetForNewGame clears pending players`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.addPendingPlayer("pending1", "Dave").connected = true

        assertEquals(1, game.pendingPlayers.size)

        game.resetForNewGame()

        // Pending players are promoted to regular players when connected
        assertEquals(0, game.pendingPlayers.size)
        // Dave should now be in allPlayers since he's connected
        assertEquals(3, game.allPlayers.size)
    }

    @Test
    fun `resetForNewGame resets game state variables`() {
        val game = createGameWithPlayers("Alice", "Bob")
        game.startGame()
        game.placeMarbles("creator", 5)
        game.makeGuess("player1", Guess.EVEN)
        game.resolveRound()

        game.resetForNewGame()

        assertEquals(0, game.currentPlayerIndex)
        assertEquals(0, game.currentMarblesPlaced)
        assertNull(game.lastRoundResult)
    }
}
