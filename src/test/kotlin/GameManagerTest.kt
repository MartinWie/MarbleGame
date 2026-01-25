package de.mw

import kotlin.test.*

class GameManagerTest {
    @BeforeTest
    fun setup() {
        // Clear any existing games between tests
        // Since GameManager is an object, we need to clean up
        // We'll work around this by using unique game IDs
    }

    @Test
    fun `createGame returns new game with unique ID`() {
        val game1 = GameManager.createGame("session1")
        val game2 = GameManager.createGame("session2")

        assertNotEquals(game1.id, game2.id)
        assertEquals("session1", game1.creatorSessionId)
        assertEquals("session2", game2.creatorSessionId)
    }

    @Test
    fun `getGame returns created game`() {
        val game = GameManager.createGame("session1")

        val retrieved = GameManager.getGame(game.id)

        assertNotNull(retrieved)
        assertEquals(game.id, retrieved.id)
    }

    @Test
    fun `getGame returns null for non-existent game`() {
        val result = GameManager.getGame("nonexistent-id")

        assertNull(result)
    }

    @Test
    fun `removeGame removes game`() {
        val game = GameManager.createGame("session1")

        assertNotNull(GameManager.getGame(game.id))

        GameManager.removeGame(game.id)

        assertNull(GameManager.getGame(game.id))
    }

    @Test
    fun `findGameByPlayer returns game containing player`() {
        val game = GameManager.createGame("session1")
        game.addPlayer("session1", "Alice")
        game.addPlayer("session2", "Bob")

        val foundByAlice = GameManager.findGameByPlayer("session1")
        val foundByBob = GameManager.findGameByPlayer("session2")

        assertNotNull(foundByAlice)
        assertNotNull(foundByBob)
        assertEquals(game.id, foundByAlice.id)
        assertEquals(game.id, foundByBob.id)
    }

    @Test
    fun `findGameByPlayer returns null for unknown session`() {
        val result = GameManager.findGameByPlayer("unknown-session")

        assertNull(result)
    }

    @Test
    fun `multiple games are tracked independently`() {
        val game1 = GameManager.createGame("creator1")
        val game2 = GameManager.createGame("creator2")

        game1.addPlayer("creator1", "Alice")
        game1.addPlayer("player1", "Bob")

        game2.addPlayer("creator2", "Charlie")
        game2.addPlayer("player2", "Dave")

        assertEquals(2, GameManager.getGame(game1.id)?.players?.size)
        assertEquals(2, GameManager.getGame(game2.id)?.players?.size)

        // Players are in different games
        val aliceGame = GameManager.findGameByPlayer("creator1")
        val charlieGame = GameManager.findGameByPlayer("creator2")

        assertNotEquals(aliceGame?.id, charlieGame?.id)
    }

    @Test
    fun `game ID is case-sensitive for lookup`() {
        val game = GameManager.createGame("session1")
        val id = game.id

        assertNotNull(GameManager.getGame(id))
        assertNull(GameManager.getGame(id.uppercase()))
    }
}
