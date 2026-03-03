package de.mw

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessGameManagerTest {
    private val createdGameIds = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        createdGameIds.forEach { ChessGameManager.removeGame(it) }
        createdGameIds.clear()
    }

    private fun createTrackedGame(sessionId: String): ChessGame {
        val game = ChessGameManager.createGame(sessionId)
        createdGameIds.add(game.id)
        return game
    }

    @Test
    fun `create and get chess game works`() {
        val game = createTrackedGame("session1")

        val retrieved = ChessGameManager.getGame(game.id)

        assertNotNull(retrieved)
        assertEquals(game.id, retrieved.id)
        assertEquals("session1", retrieved.creatorSessionId)
    }

    @Test
    fun `remove chess game deletes it`() {
        val game = createTrackedGame("session1")

        ChessGameManager.removeGame(game.id)
        createdGameIds.remove(game.id)

        assertNull(ChessGameManager.getGame(game.id))
    }

    @Test
    fun `gameCount reflects active chess games`() {
        val initial = ChessGameManager.gameCount()
        val g1 = createTrackedGame("a")
        val g2 = createTrackedGame("b")

        assertEquals(initial + 2, ChessGameManager.gameCount())

        ChessGameManager.removeGame(g1.id)
        createdGameIds.remove(g1.id)
        assertEquals(initial + 1, ChessGameManager.gameCount())

        ChessGameManager.removeGame(g2.id)
        createdGameIds.remove(g2.id)
        assertEquals(initial, ChessGameManager.gameCount())
    }

    @Test
    fun `cleanup removes old finished chess games and closes channels`() {
        val game = createTrackedGame("session1")
        val player = game.addPlayer("session1", "Alice")
        player.connected = true
        game.phase = ChessPhase.GAME_OVER

        val field = game.javaClass.getDeclaredField("lastActivityAt")
        field.isAccessible = true
        field.set(game, System.currentTimeMillis() - (2 * 60 * 60 * 1000L))

        assertFalse(player.channel.isClosedForSend)
        ChessGameManager.cleanupOldGames()

        assertNull(ChessGameManager.getGame(game.id))
        assertTrue(player.channel.isClosedForSend)
        createdGameIds.remove(game.id)
    }
}
