package de.mw

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger(Game::class.java)

/**
 * Phases of the Marble Game.
 *
 * The game progresses through these phases in order:
 * 1. [WAITING_FOR_PLAYERS] - Lobby, waiting for players to join
 * 2. [PLACING_MARBLES] - Current player hides marbles in their hand
 * 3. [GUESSING] - Other players guess if the count is even or odd
 * 4. [ROUND_RESULT] - Shows who won/lost the round
 * 5. [GAME_OVER] - One player has won (or all others eliminated)
 */
enum class GamePhase {
    /** Game created, waiting for players to join. */
    WAITING_FOR_PLAYERS,

    /** Current player is placing marbles (hiding them in their hand). */
    PLACING_MARBLES,

    /** Other players are guessing even or odd. */
    GUESSING,

    /** Showing round results before continuing. */
    ROUND_RESULT,

    /** Game has ended - one player has won. */
    GAME_OVER,
}

/**
 * A player's guess for the current round.
 */
enum class Guess {
    EVEN,
    ODD,
}

/**
 * Result of a completed round.
 *
 * @property placerName Name of the player who placed marbles
 * @property marblesPlaced How many marbles were hidden
 * @property wasEven Whether the count was even
 * @property winners Names of players who guessed correctly
 * @property losers Names of players who guessed incorrectly
 * @property marblesWonPerWinner How many marbles each winner received
 * @property marblesLostByPlacer Net marbles lost by the placer (negative if gained)
 */
data class RoundResult(
    val placerName: String,
    val marblesPlaced: Int,
    val wasEven: Boolean,
    val winners: List<String>,
    val losers: List<String>,
    val marblesWonPerWinner: Int,
    val marblesLostByPlacer: Int,
)

/**
 * Represents a single Marble Game session.
 *
 * ## Game Rules
 * - Players start with 10 marbles each
 * - Each round, one player (the "placer") hides some marbles
 * - Other players guess if the hidden count is EVEN or ODD
 * - Correct guessers split the placed marbles from the placer
 * - Wrong guessers lose marbles equal to the placed amount to the placer
 * - A player with 0 marbles becomes a spectator
 * - Last player with marbles wins
 *
 * ## Connection Handling
 * Players who disconnect have a 30-second grace period to reconnect.
 * During this time, they're still considered "available" for game logic.
 * If they don't reconnect, their marbles are distributed to remaining players.
 *
 * @property id Unique game identifier (8-character code for sharing)
 * @param creatorSessionId Session ID of the player who created the game
 */
class Game(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    creatorSessionId: String,
) {
    /** Timestamp when this game was created (for cleanup purposes). */
    val createdAt: Long = System.currentTimeMillis()

    /** Timestamp of the last activity in this game. */
    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    /**
     * Session ID of the current game creator/host.
     *
     * The creator can start the game. If they leave, this is transferred
     * to another connected player.
     */
    var creatorSessionId: String = creatorSessionId
        private set

    /** All players who have joined (including disconnected ones). */
    val players = ConcurrentHashMap<String, Player>()

    /** Current phase of the game. */
    var phase: GamePhase = GamePhase.WAITING_FOR_PLAYERS

    /** Index into [playerOrder] for the current player's turn. */
    var currentPlayerIndex: Int = 0

    /** Number of marbles placed by current player this round. */
    var currentMarblesPlaced: Int = 0

    /** Result from the most recent round (for display). */
    var lastRoundResult: RoundResult? = null

    /** Ordered list of active player session IDs (determines turn order). */
    private val playerOrder = mutableListOf<String>()

    /** Players waiting to join (spectators who will join next round). */
    private val pendingPlayerOrder = mutableListOf<String>()

    // ==================== Player Accessors ====================

    /** The player whose turn it currently is, or null if no players. */
    val currentPlayer: Player?
        get() {
            if (playerOrder.isEmpty()) return null
            val sessionId = playerOrder.getOrNull(currentPlayerIndex) ?: return null
            return players[sessionId]
        }

    /** All players with marbles (regardless of connection status). */
    val activePlayers: List<Player>
        get() = playerOrder.mapNotNull { players[it] }.filter { it.isActive }

    /** Players who are active AND currently connected. */
    val connectedActivePlayers: List<Player>
        get() = playerOrder.mapNotNull { players[it] }.filter { it.isActiveAndConnected }

    /**
     * Players who are active and either connected OR within grace period.
     *
     * Used for game-over checks to avoid ending the game when a player
     * is temporarily disconnected.
     */
    val availableActivePlayers: List<Player>
        get() = playerOrder.mapNotNull { players[it] }.filter { it.isActive && (it.connected || it.isWithinGracePeriod()) }

    /** All players in the current game (in turn order). */
    val allPlayers: List<Player>
        get() = playerOrder.mapNotNull { players[it] }

    /** Players waiting to join next round. */
    val pendingPlayers: List<Player>
        get() = pendingPlayerOrder.mapNotNull { players[it] }

    // ==================== Player Management ====================

    /**
     * Updates the last activity timestamp.
     * Called automatically on game actions to track game liveness.
     */
    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Adds a player as pending (spectator who will join next round).
     *
     * Used when someone joins a game that's already in progress.
     * They'll watch as a spectator until the next round starts.
     *
     * @param sessionId The player's session ID
     * @param name The player's display name
     * @return The created Player object
     */
    fun addPendingPlayer(
        sessionId: String,
        name: String,
        lang: String = "en",
    ): Player {
        val player = Player(sessionId, name, lang = lang)
        player.marbles = 0 // Start as spectator
        players[sessionId] = player
        if (sessionId !in pendingPlayerOrder && sessionId !in playerOrder) {
            pendingPlayerOrder.add(sessionId)
        }
        return player
    }

    /**
     * Promotes pending players to active players.
     *
     * Called at the start of a new round. Pending players receive
     * 10 marbles and are added to the turn order.
     */
    fun promotePendingPlayers() {
        pendingPlayerOrder.forEach { sessionId ->
            players[sessionId]?.let { player ->
                player.marbles = 10
                playerOrder.add(sessionId)
            }
        }
        pendingPlayerOrder.clear()
    }

    /**
     * Adds a new player to the game.
     *
     * Used during the WAITING_FOR_PLAYERS phase.
     *
     * @param sessionId The player's session ID
     * @param name The player's display name
     * @param lang The player's language preference
     * @return The created Player object
     */
    fun addPlayer(
        sessionId: String,
        name: String,
        lang: String = "en",
    ): Player {
        touch()
        val player = Player(sessionId, name, lang = lang)
        players[sessionId] = player
        if (sessionId !in playerOrder) {
            playerOrder.add(sessionId)
        }
        return player
    }

    /**
     * Removes a player from the game entirely.
     *
     * @param sessionId The player's session ID
     */
    fun removePlayer(sessionId: String) {
        players.remove(sessionId)
        playerOrder.remove(sessionId)
        if (currentPlayerIndex >= playerOrder.size && playerOrder.isNotEmpty()) {
            currentPlayerIndex = 0
        }
    }

    // ==================== Game Flow ====================

    /**
     * Starts the game.
     *
     * Requires at least 2 connected players. Transitions from
     * WAITING_FOR_PLAYERS to PLACING_MARBLES phase.
     *
     * @return `true` if game started successfully, `false` if not enough players
     */
    fun startGame(): Boolean {
        touch()
        val connectedPlayers = players.values.count { it.connected }
        if (connectedPlayers < 2) return false

        phase = GamePhase.PLACING_MARBLES
        currentPlayerIndex = 0
        advanceToNextActivePlayer()
        return true
    }

    /**
     * Current player places marbles to be guessed.
     *
     * @param sessionId Must match the current player
     * @param amount Number of marbles to place (1 to player's total)
     * @return `true` if placement was valid
     */
    fun placeMarbles(
        sessionId: String,
        amount: Int,
    ): Boolean {
        touch()
        val player = currentPlayer

        if (player == null) return false
        if (player.sessionId != sessionId) return false
        if (phase != GamePhase.PLACING_MARBLES) return false
        if (amount < 1 || amount > player.marbles) return false

        currentMarblesPlaced = amount
        players.values.forEach { it.currentGuess = null }
        phase = GamePhase.GUESSING
        return true
    }

    /**
     * Player makes a guess for the current round.
     *
     * @param sessionId The guessing player (cannot be the placer)
     * @param guess EVEN or ODD
     * @return `true` if guess was recorded
     */
    fun makeGuess(
        sessionId: String,
        guess: Guess,
    ): Boolean {
        touch()
        val player = players[sessionId]

        if (player == null) return false
        if (phase != GamePhase.GUESSING) return false
        if (player.sessionId == currentPlayer?.sessionId) return false
        if (player.isSpectator) return false

        player.currentGuess = guess
        return true
    }

    /**
     * Checks if all connected active players have made their guess.
     *
     * @return `true` if round can be resolved
     */
    fun allActivePlayersGuessed(): Boolean {
        val guessingPlayers = connectedActivePlayers.filter { it.sessionId != currentPlayer?.sessionId }
        return guessingPlayers.all { it.currentGuess != null }
    }

    /**
     * Resolves the current round and distributes marbles.
     *
     * - Correct guessers split the placed marbles from the placer
     * - If no correct guesses, placer wins marbles from each wrong guesser
     *
     * @return The round result, or null if not in GUESSING phase
     */
    fun resolveRound(): RoundResult? {
        val placer = currentPlayer ?: return null
        if (phase != GamePhase.GUESSING) return null

        val isEven = currentMarblesPlaced % 2 == 0
        val guessingPlayers = connectedActivePlayers.filter { it.sessionId != placer.sessionId }

        val winners =
            guessingPlayers.filter {
                (it.currentGuess == Guess.EVEN && isEven) || (it.currentGuess == Guess.ODD && !isEven)
            }
        val losers =
            guessingPlayers.filter {
                (it.currentGuess == Guess.EVEN && !isEven) || (it.currentGuess == Guess.ODD && isEven)
            }

        var marblesWonPerWinner = 0
        var marblesLostByPlacer = 0

        if (winners.isNotEmpty()) {
            // Winners split the placed marbles
            marblesWonPerWinner = currentMarblesPlaced / winners.size
            val totalPayout = marblesWonPerWinner * winners.size
            marblesLostByPlacer = minOf(totalPayout, placer.marbles)
            placer.marbles -= marblesLostByPlacer

            val actualPerWinner = marblesLostByPlacer / winners.size
            winners.forEach { it.marbles += actualPerWinner }
        } else if (losers.isNotEmpty()) {
            // All guessers were wrong - placer wins from each loser
            losers.forEach { loser ->
                val winAmount = minOf(currentMarblesPlaced, loser.marbles)
                loser.marbles -= winAmount
                placer.marbles += winAmount
            }
            marblesLostByPlacer = -losers.sumOf { minOf(currentMarblesPlaced, it.marbles + currentMarblesPlaced) }
        }

        lastRoundResult =
            RoundResult(
                placerName = placer.name,
                marblesPlaced = currentMarblesPlaced,
                wasEven = isEven,
                winners = winners.map { it.name },
                losers = losers.map { it.name },
                marblesWonPerWinner = marblesWonPerWinner,
                marblesLostByPlacer = marblesLostByPlacer,
            )

        phase = GamePhase.ROUND_RESULT
        return lastRoundResult
    }

    /**
     * Advances to the next round.
     *
     * Promotes pending players and moves to the next player's turn.
     *
     * @return `true` if game continues, `false` if game over
     */
    fun nextRound(): Boolean {
        promotePendingPlayers()

        val playersWithMarbles = connectedActivePlayers
        if (playersWithMarbles.size <= 1) {
            phase = GamePhase.GAME_OVER
            return false
        }

        currentPlayerIndex = (currentPlayerIndex + 1) % playerOrder.size
        advanceToNextActivePlayer()

        currentMarblesPlaced = 0
        phase = GamePhase.PLACING_MARBLES
        return true
    }

    /**
     * Skips to the next active, connected player.
     *
     * Called when the current player is a spectator or disconnected.
     */
    private fun advanceToNextActivePlayer() {
        val startIndex = currentPlayerIndex
        var attempts = 0

        while ((currentPlayer?.isSpectator == true || currentPlayer?.connected == false) && attempts < playerOrder.size) {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerOrder.size
            attempts++
        }
    }

    /**
     * Gets the winner of the game.
     *
     * @return The winning player, or null if game not over
     */
    fun getWinner(): Player? {
        if (phase != GamePhase.GAME_OVER) return null
        return connectedActivePlayers.firstOrNull() ?: players.values.find { it.isActive }
    }

    // ==================== Disconnect Handling ====================

    /**
     * Handles a player disconnecting.
     *
     * May advance game state if needed (e.g., skip disconnected player's turn,
     * resolve round if all remaining players have guessed).
     *
     * @param sessionId The disconnected player's session ID
     * @return `true` if game state changed and broadcast is needed
     */
    fun handlePlayerDisconnect(sessionId: String): Boolean {
        val player = players[sessionId] ?: return false

        if (player.connected) {
            player.connected = false
        }

        when (phase) {
            GamePhase.WAITING_FOR_PLAYERS -> {
                return true
            }
            GamePhase.PLACING_MARBLES -> {
                if (currentPlayer?.sessionId == sessionId) {
                    advanceToNextActivePlayer()
                }
                val available = availableActivePlayers.size
                if (available <= 1) {
                    logger.info("Game {} ended - only 1 player remaining", id)
                    phase = GamePhase.GAME_OVER
                }
                return true
            }
            GamePhase.GUESSING -> {
                if (allActivePlayersGuessed()) {
                    resolveRound()
                    return true
                }
            }
            GamePhase.ROUND_RESULT -> {
                if (availableActivePlayers.size <= 1) {
                    phase = GamePhase.GAME_OVER
                    return true
                }
            }
            GamePhase.GAME_OVER -> {
                // No action needed
            }
        }
        return true
    }

    /**
     * Handles a player's grace period expiring.
     *
     * Distributes their marbles to remaining players and removes them
     * from the game. If the creator leaves, transfers creator status.
     *
     * @param sessionId The player whose grace period expired
     * @return `true` if game state changed and broadcast is needed
     */
    fun handleGracePeriodExpired(sessionId: String): Boolean {
        val player = players[sessionId] ?: return false

        if (player.connected || player.isWithinGracePeriod()) return false

        // Transfer creator status if needed
        if (sessionId == creatorSessionId) {
            val newCreator = connectedActivePlayers.firstOrNull { it.sessionId != sessionId }
            if (newCreator != null) {
                creatorSessionId = newCreator.sessionId
                logger.info("Game {} creator transferred to '{}'", id, newCreator.name)
            }
        }

        // Distribute marbles to remaining players
        val recipients = connectedActivePlayers.filter { it.sessionId != sessionId }
        if (recipients.isNotEmpty() && player.marbles > 0) {
            val marblesEach = player.marbles / recipients.size
            val remainder = player.marbles % recipients.size

            recipients.forEachIndexed { index, recipient ->
                recipient.marbles += marblesEach + if (index < remainder) 1 else 0
            }
            player.marbles = 0
        }

        // Adjust turn order
        val removedIndex = playerOrder.indexOf(sessionId)
        val wasCurrentPlayer = currentPlayerIndex == removedIndex

        playerOrder.remove(sessionId)

        if (playerOrder.isNotEmpty()) {
            if (removedIndex != -1 && removedIndex < currentPlayerIndex) {
                currentPlayerIndex--
            }
            if (currentPlayerIndex >= playerOrder.size) {
                currentPlayerIndex = 0
            }
        }

        // Check for game over
        if (availableActivePlayers.size <= 1) {
            phase = GamePhase.GAME_OVER
        } else if (wasCurrentPlayer) {
            advanceToNextActivePlayer()
        }

        return true
    }

    // ==================== Broadcasting ====================

    /**
     * Sends updated game state to all connected players.
     *
     * @param renderState Function to render HTML for a specific player's view (game, sessionId, lang)
     */
    fun broadcastToAllConnected(renderState: (Game, String, String) -> String) {
        val connectedPlayers = players.values.filter { it.connected }

        connectedPlayers.forEach { player ->
            val html = renderState(this, player.sessionId, player.lang)
            player.channel.trySend(html)
        }
    }

    /**
     * Resets the game for a new round (Play Again functionality).
     *
     * All connected players get 10 marbles and are re-added to the turn order.
     */
    fun resetForNewGame() {
        phase = GamePhase.WAITING_FOR_PLAYERS
        currentPlayerIndex = 0
        currentMarblesPlaced = 0
        lastRoundResult = null

        val connectedSessionIds =
            players.values
                .filter { it.connected }
                .map { it.sessionId }
                .toSet()

        playerOrder.clear()
        pendingPlayerOrder.clear()

        players.values.forEach { player ->
            player.currentGuess = null
            if (player.connected) {
                player.marbles = 10
                if (player.sessionId !in playerOrder) {
                    playerOrder.add(player.sessionId)
                }
            }
        }

        logger.info("Game {} reset with {} players", id, playerOrder.size)
    }
}
