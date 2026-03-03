package de.mw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessGameTest {
    private fun startedGame(): ChessGame {
        val game = ChessGame(creatorSessionId = "p1")
        game.addPlayer("p1", "Alice").connected = true
        game.addPlayer("p2", "Bob").connected = true
        return game
    }

    @Test
    fun `first two players get colors and game starts`() {
        val game = ChessGame(creatorSessionId = "p1")

        game.addPlayer("p1", "Alice")
        game.players["p1"]?.connected = true
        assertEquals(ChessPhase.WAITING_FOR_PLAYERS, game.phase)

        game.addPlayer("p2", "Bob")
        game.players["p2"]?.connected = true

        assertEquals(ChessPhase.IN_PROGRESS, game.phase)
        assertEquals(ChessColor.WHITE, game.colorFor("p1"))
        assertEquals(ChessColor.BLACK, game.colorFor("p2"))
    }

    @Test
    fun `third player becomes spectator`() {
        val game = ChessGame(creatorSessionId = "p1")
        game.addPlayer("p1", "Alice").connected = true
        game.addPlayer("p2", "Bob").connected = true
        game.addPlayer("p3", "Charlie").connected = true

        assertNull(game.colorFor("p3"))
        assertEquals(0, game.players["p3"]?.marbles)
    }

    @Test
    fun `legal move advances turn`() {
        val game = startedGame()

        val moved = game.makeMove("p1", "e2", "e4")

        assertTrue(moved)
        assertNull(game.pieceAt("e2"))
        assertNotNull(game.pieceAt("e4"))
        assertEquals(ChessColor.BLACK, game.turn)
    }

    @Test
    fun `cannot move opponent piece`() {
        val game = startedGame()

        val moved = game.makeMove("p1", "e7", "e6")

        assertFalse(moved)
    }

    @Test
    fun `grace period expiry on active player ends game by disconnect`() {
        val game = startedGame()

        game.players["p2"]?.connected = false
        game.players["p2"]?.disconnectedAt = System.currentTimeMillis() - DISCONNECT_GRACE_PERIOD_MS - 1000

        val changed = game.handleGracePeriodExpired("p2")

        assertTrue(changed)
        assertEquals(ChessPhase.GAME_OVER, game.phase)
        assertEquals("p1", game.winnerSessionId)
    }

    @Test
    fun `expired lobby player is removed so newcomer can take second seat`() {
        val game = ChessGame(creatorSessionId = "p1")
        game.addPlayer("p1", "Alice").connected = true
        game.addPlayer("p2", "Bob").connected = true

        game.players["p2"]?.connected = false
        game.players["p2"]?.disconnectedAt = System.currentTimeMillis() - DISCONNECT_GRACE_PERIOD_MS - 1000
        game.phase = ChessPhase.WAITING_FOR_PLAYERS

        val changed = game.handleGracePeriodExpired("p2")
        assertTrue(changed)
        assertNull(game.players["p2"])
        assertNull(game.colorFor("p2"))
        assertEquals(ChessPhase.WAITING_FOR_PLAYERS, game.phase)

        game.addPlayer("p3", "Charlie").connected = true
        assertEquals(ChessColor.BLACK, game.colorFor("p3"))
        assertEquals(ChessPhase.IN_PROGRESS, game.phase)
    }

    @Test
    fun `cannot move when not your turn`() {
        val game = startedGame()

        val moved = game.makeMove("p2", "e7", "e5")

        assertFalse(moved)
        assertEquals(ChessColor.WHITE, game.turn)
    }

    @Test
    fun `spectator cannot move pieces`() {
        val game = startedGame()
        game.addPlayer("p3", "Charlie").connected = true

        val moved = game.makeMove("p3", "e2", "e4")

        assertFalse(moved)
    }

    @Test
    fun `cannot capture own piece`() {
        val game = startedGame()

        val moved = game.makeMove("p1", "d1", "d2")

        assertFalse(moved)
    }

    @Test
    fun `invalid square is rejected`() {
        val game = startedGame()

        assertFalse(game.makeMove("p1", "z9", "e4"))
        assertFalse(game.makeMove("p1", "e2", "a9"))
        assertFalse(game.makeMove("p1", "e2", "e2"))
    }

    @Test
    fun `cannot capture king directly`() {
        val game = startedGame()

        // Build line so queen would otherwise be able to take king on e8
        game.makeMove("p1", "e2", "e4")
        game.makeMove("p2", "e7", "e5")
        game.makeMove("p1", "d1", "h5")
        game.makeMove("p2", "a7", "a5")
        game.makeMove("p1", "h5", "e5")
        game.makeMove("p2", "a5", "a4")
        val kingCapture = game.makeMove("p1", "e5", "e8")

        assertFalse(kingCapture)
        assertEquals(ChessPhase.IN_PROGRESS, game.phase)
        assertEquals('k', game.pieceAt("e8"))
    }

    @Test
    fun `reset starts game with first two connected players only`() {
        val game = startedGame()
        game.addPlayer("p3", "Charlie").connected = true

        game.players["p1"]?.connected = false

        val started = game.resetForNewGame()

        assertTrue(started)
        assertEquals(ChessPhase.IN_PROGRESS, game.phase)
        assertNull(game.colorFor("p1"))
        assertNotNull(game.colorFor("p2"))
        assertNotNull(game.colorFor("p3"))
    }

    @Test
    fun `en passant capture works`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "e2", "e4"))
        assertTrue(game.makeMove("p2", "a7", "a6"))
        assertTrue(game.makeMove("p1", "e4", "e5"))
        assertTrue(game.makeMove("p2", "d7", "d5"))
        assertTrue(game.makeMove("p1", "e5", "d6"))

        assertEquals('P', game.pieceAt("d6"))
        assertNull(game.pieceAt("d5"))
    }

    @Test
    fun `kingside castling works`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "e2", "e4"))
        assertTrue(game.makeMove("p2", "a7", "a6"))
        assertTrue(game.makeMove("p1", "g1", "f3"))
        assertTrue(game.makeMove("p2", "a6", "a5"))
        assertTrue(game.makeMove("p1", "f1", "e2"))
        assertTrue(game.makeMove("p2", "a5", "a4"))
        assertTrue(game.makeMove("p1", "e1", "g1"))

        assertEquals('K', game.pieceAt("g1"))
        assertEquals('R', game.pieceAt("f1"))
        assertNull(game.pieceAt("e1"))
        assertNull(game.pieceAt("h1"))
    }

    @Test
    fun `queenside castling works`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "d2", "d4"))
        assertTrue(game.makeMove("p2", "h7", "h6"))
        assertTrue(game.makeMove("p1", "c1", "e3"))
        assertTrue(game.makeMove("p2", "h6", "h5"))
        assertTrue(game.makeMove("p1", "b1", "c3"))
        assertTrue(game.makeMove("p2", "a7", "a6"))
        assertTrue(game.makeMove("p1", "d1", "d2"))
        assertTrue(game.makeMove("p2", "a6", "a5"))
        assertTrue(game.makeMove("p1", "e1", "c1"))

        assertEquals('K', game.pieceAt("c1"))
        assertEquals('R', game.pieceAt("d1"))
        assertNull(game.pieceAt("e1"))
        assertNull(game.pieceAt("a1"))
    }

    @Test
    fun `cannot castle while king is in check`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "e2", "e3"))
        assertTrue(game.makeMove("p2", "e7", "e6"))
        assertTrue(game.makeMove("p1", "d2", "d3"))
        assertTrue(game.makeMove("p2", "a7", "a6"))
        assertTrue(game.makeMove("p1", "g1", "f3"))
        assertTrue(game.makeMove("p2", "a6", "a5"))
        assertTrue(game.makeMove("p1", "f1", "e2"))
        assertTrue(game.makeMove("p2", "f8", "b4"))

        val castled = game.makeMove("p1", "e1", "g1")

        assertFalse(castled)
        assertEquals('K', game.pieceAt("e1"))
        assertEquals('R', game.pieceAt("h1"))
    }

    @Test
    fun `cannot castle if rook moved before`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "h2", "h3"))
        assertTrue(game.makeMove("p2", "a7", "a6"))
        assertTrue(game.makeMove("p1", "h1", "h2"))
        assertTrue(game.makeMove("p2", "a6", "a5"))
        assertTrue(game.makeMove("p1", "h2", "h1"))
        assertTrue(game.makeMove("p2", "a5", "a4"))
        assertTrue(game.makeMove("p1", "e2", "e3"))
        assertTrue(game.makeMove("p2", "b7", "b6"))
        assertTrue(game.makeMove("p1", "g1", "f3"))
        assertTrue(game.makeMove("p2", "b6", "b5"))
        assertTrue(game.makeMove("p1", "f1", "e2"))
        assertTrue(game.makeMove("p2", "c7", "c6"))

        val castled = game.makeMove("p1", "e1", "g1")

        assertFalse(castled)
        assertEquals('K', game.pieceAt("e1"))
        assertEquals('R', game.pieceAt("h1"))
    }

    @Test
    fun `en passant is only allowed on immediate reply`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "e2", "e4"))
        assertTrue(game.makeMove("p2", "a7", "a6"))
        assertTrue(game.makeMove("p1", "e4", "e5"))
        assertTrue(game.makeMove("p2", "d7", "d5"))
        assertTrue(game.makeMove("p1", "h2", "h3"))
        assertTrue(game.makeMove("p2", "a6", "a5"))

        val delayedEnPassant = game.makeMove("p1", "e5", "d6")

        assertFalse(delayedEnPassant)
        assertEquals('P', game.pieceAt("e5"))
        assertEquals('p', game.pieceAt("d5"))
    }

    @Test
    fun `must answer check with a legal move`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "f2", "f3"))
        assertTrue(game.makeMove("p2", "e7", "e5"))
        assertTrue(game.makeMove("p1", "g2", "g4"))
        assertTrue(game.makeMove("p2", "d8", "h4"))

        // White king is in check from queen on h4; random pawn move must be rejected
        val illegalNonResponse = game.makeMove("p1", "a2", "a3")

        assertFalse(illegalNonResponse)
        assertEquals('P', game.pieceAt("a2"))
        assertEquals(ChessColor.WHITE, game.turn)
    }

    @Test
    fun `fools mate results in checkmate`() {
        val game = startedGame()

        assertTrue(game.makeMove("p1", "f2", "f3"))
        assertTrue(game.makeMove("p2", "e7", "e5"))
        assertTrue(game.makeMove("p1", "g2", "g4"))
        assertTrue(game.makeMove("p2", "d8", "h4"))

        assertEquals(ChessPhase.GAME_OVER, game.phase)
        assertEquals("p2", game.winnerSessionId)
        assertEquals("checkmate", game.endReason)
    }

    @Test
    fun `position with no legal move and no check is stalemate`() {
        val game = startedGame()

        game.forcePositionForTesting(
            pieces =
                mapOf(
                    "a8" to 'k',
                    "c7" to 'K',
                    "b6" to 'Q',
                ),
            turn = ChessColor.BLACK,
        )

        game.evaluateEndStateForTesting(lastMoverSessionId = "p1")

        assertEquals(ChessPhase.GAME_OVER, game.phase)
        assertNull(game.winnerSessionId)
        assertEquals("stalemate", game.endReason)
    }

    @Test
    fun `creator transfers after grace expiry in game over`() {
        val game = startedGame()
        game.phase = ChessPhase.GAME_OVER

        game.players["p1"]?.connected = false
        game.players["p1"]?.disconnectedAt = System.currentTimeMillis() - DISCONNECT_GRACE_PERIOD_MS - 1000

        val changed = game.handleGracePeriodExpired("p1")

        assertTrue(changed)
        assertEquals("p2", game.creatorSessionId)
    }

    @Test
    fun `checked king square is exposed in check`() {
        val game = startedGame()

        game.forcePositionForTesting(
            pieces =
                mapOf(
                    "e8" to 'k',
                    "e1" to 'K',
                    "e2" to 'R',
                ),
            turn = ChessColor.BLACK,
        )

        assertEquals("e8", game.checkedKingSquare())
    }

    @Test
    fun `checked king square is null when not in check or game over`() {
        val game = startedGame()

        assertNull(game.checkedKingSquare())

        game.phase = ChessPhase.GAME_OVER
        assertNull(game.checkedKingSquare())
    }
}
