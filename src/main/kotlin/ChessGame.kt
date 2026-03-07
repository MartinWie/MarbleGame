package de.mw

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val chessLogger = LoggerFactory.getLogger(ChessGame::class.java)

enum class ChessPhase {
    WAITING_FOR_PLAYERS,
    IN_PROGRESS,
    GAME_OVER,
}

enum class ChessColor {
    WHITE,
    BLACK,
}

enum class MoveError {
    NOT_YOUR_TURN,
    INVALID_MOVE,
    OK,
}

class ChessGame(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    creatorSessionId: String,
    private val randomColorAssignment: Boolean = true,
    val timedModeEnabled: Boolean = false,
    val streamerModeEnabled: Boolean = false,
    initialClockSeconds: Int = INITIAL_CLOCK_SECONDS,
) {
    companion object {
        const val INITIAL_CLOCK_SECONDS = 300
        const val MIN_CLOCK_SECONDS = 60
        const val MAX_CLOCK_SECONDS = 3600
        const val WHITE_FIRST_MOVE_GRACE_MS = 10_000L
    }

    private val initialClockMs: Long = (initialClockSeconds.coerceIn(MIN_CLOCK_SECONDS, MAX_CLOCK_SECONDS) * 1000L)

    val createdAt: Long = System.currentTimeMillis()

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    private val stateVersion = AtomicLong(0L)

    var creatorSessionId: String = creatorSessionId
        private set

    val players = ConcurrentHashMap<String, Player>()
    private val playerOrder = CopyOnWriteArrayList<String>()
    private val colorAssignments = ConcurrentHashMap<String, ChessColor>()
    private val playerSignalVersions = ConcurrentHashMap<String, Long>()

    @Volatile
    var phase: ChessPhase = ChessPhase.WAITING_FOR_PLAYERS

    @Volatile
    var turn: ChessColor = ChessColor.WHITE

    @Volatile
    var winnerSessionId: String? = null
        private set

    @Volatile
    var endReason: String? = null
        private set

    @Volatile
    var lastMove: String? = null
        private set

    @Volatile
    var lastMoveMeta: String? = null
        private set

    @Volatile
    var whiteSessionId: String? = null
        private set

    @Volatile
    var blackSessionId: String? = null
        private set

    @Volatile
    private var enPassantTargetSquare: String? = null

    @Volatile
    private var enPassantPawnSquare: String? = null

    @Volatile
    private var whiteTimeRemainingMs: Long = initialClockMs

    @Volatile
    private var blackTimeRemainingMs: Long = initialClockMs

    @Volatile
    private var turnStartedAt: Long? = null

    @Volatile
    private var clockStarted: Boolean = false

    @Volatile
    private var gameStartedAt: Long? = null

    private val kingMoved = ConcurrentHashMap<ChessColor, Boolean>()
    private val rookMoved = ConcurrentHashMap<String, Boolean>()

    private val board = ConcurrentHashMap<String, Char>()
    private var nextRoundForcedPlayers: List<String>? = null
    private var autoRestartAt: Long? = null

    init {
        resetBoard()
    }

    @get:Synchronized
    val allPlayers: List<Player>
        get() = playerOrder.mapNotNull { players[it] }

    @get:Synchronized
    val connectedPlayers: List<Player>
        get() = allPlayers.filter { it.connected }

    fun colorFor(sessionId: String): ChessColor? = colorAssignments[sessionId]

    fun pieceAt(square: String): Char? = board[square.lowercase()]

    fun boardSnapshot(): Map<String, Char> = board.toMap()

    fun enPassantTarget(): String? = enPassantTargetSquare

    fun isCastleAvailable(
        color: ChessColor,
        kingSide: Boolean,
    ): Boolean {
        val key =
            when {
                color == ChessColor.WHITE && kingSide -> "h1"
                color == ChessColor.WHITE && !kingSide -> "a1"
                color == ChessColor.BLACK && kingSide -> "h8"
                else -> "a8"
            }
        return kingMoved[color] != true && rookMoved[key] != true
    }

    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    @Synchronized
    fun addPlayer(
        sessionId: String,
        name: String,
        lang: String = "en",
    ): Player {
        touch()
        val clampedName = name.take(MAX_PLAYER_NAME_LENGTH)

        val existing = players[sessionId]
        if (existing != null) {
            existing.name = clampedName
            existing.lang = lang
            return existing
        }

        val shouldPlay = colorAssignments.size < 2
        val player = Player(sessionId = sessionId, name = clampedName, marbles = if (shouldPlay) 1 else 0, lang = lang)
        players[sessionId] = player
        playerOrder.add(sessionId)

        if (shouldPlay) {
            if (!randomColorAssignment) {
                colorAssignments[sessionId] = if (colorAssignments.values.contains(ChessColor.WHITE)) ChessColor.BLACK else ChessColor.WHITE
            } else {
                colorAssignments[sessionId] = ChessColor.WHITE
                if (colorAssignments.size == 2) {
                    randomizeColorsForActivePlayers()
                }
            }
            updateColorAssignmentCache()
        }

        maybeStartGame()
        return player
    }

    @Synchronized
    fun handlePlayerReconnect(sessionId: String) {
        if (players[sessionId] == null) return
        if (!playerOrder.contains(sessionId)) {
            playerOrder.add(sessionId)
        }
        maybeStartGame()
    }

    @Synchronized
    fun handlePlayerDisconnect(sessionId: String): Boolean {
        val player = players[sessionId] ?: return false
        if (player.connected) {
            player.connected = false
        }
        maybeStartGame()
        return true
    }

    @Synchronized
    fun handleGracePeriodExpired(sessionId: String): Boolean {
        val player = players[sessionId] ?: return false
        if (player.connected || player.isWithinGracePeriod()) return false

        var changed = false

        if (sessionId == creatorSessionId) {
            val newCreator =
                allPlayers.firstOrNull { it.connected && it.sessionId != sessionId }
                    ?: allPlayers.firstOrNull { it.sessionId != sessionId }
            if (newCreator != null) {
                creatorSessionId = newCreator.sessionId
                changed = true
                chessLogger.info("Chess game {} creator transferred to '{}'", id, newCreator.name)
            }
        }

        val hadColor = colorAssignments.containsKey(sessionId)
        if (phase == ChessPhase.IN_PROGRESS && hadColor) {
            updateTurnDeadline()
            val winner = colorAssignments.keys.firstOrNull { it != sessionId }
            if (winner != null) {
                winnerSessionId = winner
                endReason = "disconnect"
                phase = ChessPhase.GAME_OVER
                turnStartedAt = null
                if (promoteLongestConnectedSpectatorAgainst(winner)) {
                    phase = ChessPhase.IN_PROGRESS
                    turn = ChessColor.WHITE
                    resetPlayerClocks()
                    startTurnClock()
                    clockStarted = false
                    winnerSessionId = null
                    endReason = null
                } else {
                    scheduleAutoRestart(10)
                }
                changed = true
            }
        }

        val removed = removeExpiredPlayer(sessionId)
        changed = changed || removed

        if (phase != ChessPhase.GAME_OVER) {
            maybeStartGame()
        }

        return changed
    }

    @Synchronized
    fun makeMove(
        sessionId: String,
        from: String,
        to: String,
    ): Boolean {
        touch()
        if (phase != ChessPhase.IN_PROGRESS) return false

        if (timedModeEnabled && checkTurnTimeout()) {
            return false
        }

        val normalizedFrom = from.trim().lowercase()
        val normalizedTo = to.trim().lowercase()
        if (!isValidSquare(normalizedFrom) || !isValidSquare(normalizedTo) || normalizedFrom == normalizedTo) return false

        val movingColor = colorAssignments[sessionId] ?: return false
        if (movingColor != turn) return false

        val piece = board[normalizedFrom] ?: return false
        if (pieceColor(piece) != movingColor) return false

        val target = board[normalizedTo]
        if (target != null && pieceColor(target) == movingColor) return false
        if (target != null && target.lowercaseChar() == 'k') return false

        val snapshot = snapshotState()

        if (piece.lowercaseChar() == 'k' && isCastlingAttempt(normalizedFrom, normalizedTo)) {
            if (!canCastle(movingColor, normalizedFrom, normalizedTo)) return false

            executeCastling(movingColor, normalizedFrom, normalizedTo)
            kingMoved[movingColor] = true
            clearEnPassant()
            lastMove = "$normalizedFrom-$normalizedTo"
            lastMoveMeta = "castle:$normalizedFrom-$normalizedTo"
            if (isKingInCheck(movingColor)) {
                restoreState(snapshot)
                return false
            }

            updateTurnDeadline()
            turn = oppositeColor(turn)
            startTurnClock()
            if (!clockStarted && movingColor == ChessColor.WHITE) {
                clockStarted = true
            }
            evaluateGameEndAfterMove(sessionId)
            return true
        }

        if (!isLegalPieceMove(piece, normalizedFrom, normalizedTo)) return false

        val capturedPiece =
            if (isEnPassantCapture(piece, normalizedFrom, normalizedTo, target)) {
                val capturedSquare = enPassantPawnSquare ?: return false
                val captured = board[capturedSquare] ?: return false
                if (captured.lowercaseChar() != 'p' || pieceColor(captured) == movingColor) return false
                board.remove(capturedSquare)
                captured
            } else {
                target
            }

        board.remove(normalizedFrom)
        board[normalizedTo] = promoteIfNeeded(piece, normalizedTo)
        lastMove = "$normalizedFrom-$normalizedTo"
        lastMoveMeta =
            when {
                isEnPassantCapture(piece, normalizedFrom, normalizedTo, target) -> "enpassant:$normalizedFrom-$normalizedTo"
                capturedPiece != null -> "capture:$normalizedFrom-$normalizedTo"
                else -> "move:$normalizedFrom-$normalizedTo"
            }

        updatePieceMoveState(piece, normalizedFrom, normalizedTo, capturedPiece)
        updateEnPassantState(piece, normalizedFrom, normalizedTo)

        if (isKingInCheck(movingColor)) {
            restoreState(snapshot)
            return false
        }

        updateTurnDeadline()
        turn = oppositeColor(turn)
        startTurnClock()
        if (!clockStarted && movingColor == ChessColor.WHITE) {
            clockStarted = true
        }
        evaluateGameEndAfterMove(sessionId)
        return true
    }

    @Synchronized
    fun surrender(sessionId: String): Boolean {
        touch()
        if (phase != ChessPhase.IN_PROGRESS) return false
        val surrenderColor = colorAssignments[sessionId] ?: return false
        val winner = colorAssignments.entries.firstOrNull { it.value != surrenderColor }?.key ?: return false

        winnerSessionId = winner
        endReason = "surrender"
        phase = ChessPhase.GAME_OVER
        turnStartedAt = null
        clockStarted = false
        scheduleAutoRestart(10)
        return true
    }

    @Synchronized
    fun validateMoveError(
        sessionId: String,
        from: String,
        to: String,
    ): MoveError {
        if (phase != ChessPhase.IN_PROGRESS) return MoveError.INVALID_MOVE
        val movingColor = colorAssignments[sessionId] ?: return MoveError.INVALID_MOVE
        if (movingColor != turn) return MoveError.NOT_YOUR_TURN
        return if (isMoveLegalForValidation(sessionId, from, to)) MoveError.OK else MoveError.INVALID_MOVE
    }

    @Synchronized
    fun resetForNewGame(): Boolean {
        touch()
        if (phase == ChessPhase.GAME_OVER) {
            rotateAfterGameOverIfNeeded()
        }
        phase = ChessPhase.WAITING_FOR_PLAYERS
        resetBoard()
        turn = ChessColor.WHITE
        winnerSessionId = null
        endReason = null
        lastMove = null
        lastMoveMeta = null
        resetPlayerClocks()
        turnStartedAt = null
        clockStarted = false
        gameStartedAt = null
        autoRestartAt = null

        colorAssignments.clear()

        val connected = allPlayers.filter { it.connected && players.containsKey(it.sessionId) }
        val forced = nextRoundForcedPlayers?.mapNotNull { id -> connected.firstOrNull { it.sessionId == id } }
        nextRoundForcedPlayers = null
        val previouslyActive = connected.filter { it.marbles > 0 }
        val pair =
            if (forced != null && forced.size >= 2) {
                forced.take(2)
            } else if (previouslyActive.size >= 2) {
                previouslyActive.take(2)
            } else {
                connected.take(2)
            }
        if (pair.isNotEmpty()) {
            if (randomColorAssignment) {
                pair.forEach { player ->
                    colorAssignments[player.sessionId] = ChessColor.WHITE
                }
                if (colorAssignments.size == 2) {
                    randomizeColorsForActivePlayers()
                }
            } else {
                colorAssignments[pair[0].sessionId] = ChessColor.WHITE
                if (pair.size > 1) {
                    colorAssignments[pair[1].sessionId] = ChessColor.BLACK
                }
            }
        }

        allPlayers.forEach { player ->
            player.currentGuess = null
            player.marbles = if (colorAssignments.containsKey(player.sessionId)) 1 else 0
        }

        maybeStartGame()
        return phase == ChessPhase.IN_PROGRESS
    }

    @Synchronized
    fun checkTurnTimeout(): Boolean {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) return false
        if (!clockStarted) {
            maybeStartClockAfterWhiteGracePeriod()
        }
        if (!clockStarted) return false
        val remaining = currentTurnRemainingMs()
        if (remaining > 0L) return false

        if (turn == ChessColor.WHITE) {
            whiteTimeRemainingMs = 0L
        } else {
            blackTimeRemainingMs = 0L
        }

        val winner = colorAssignments.entries.firstOrNull { it.value != turn }?.key ?: return false
        winnerSessionId = winner
        endReason = "timeout"
        phase = ChessPhase.GAME_OVER
        turnStartedAt = null
        clockStarted = false
        scheduleAutoRestart(10)
        return true
    }

    fun winner(): Player? = winnerSessionId?.let { players[it] }

    @Synchronized
    fun autoRestartSecondsRemaining(): Int {
        if (streamerModeEnabled || phase != ChessPhase.GAME_OVER) return 0
        val restartAt = autoRestartAt ?: return 0
        val remainingMs = restartAt - System.currentTimeMillis()
        return if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1000L).toInt()
    }

    @Synchronized
    fun scheduleAutoRestart(delaySeconds: Int = 10) {
        if (streamerModeEnabled || phase != ChessPhase.GAME_OVER) {
            autoRestartAt = null
            return
        }
        autoRestartAt = System.currentTimeMillis() + (delaySeconds * 1000L)
    }

    @Synchronized
    fun shouldAutoRestartNow(): Boolean {
        if (streamerModeEnabled || phase != ChessPhase.GAME_OVER) return false
        val restartAt = autoRestartAt ?: return false
        return System.currentTimeMillis() >= restartAt
    }

    @Synchronized
    fun kingSquare(color: ChessColor): String? {
        val kingChar = if (color == ChessColor.WHITE) 'K' else 'k'
        return board.entries.firstOrNull { it.value == kingChar }?.key
    }

    @Synchronized
    fun checkedKingSquare(): String? {
        if (phase != ChessPhase.IN_PROGRESS) return null
        return if (isKingInCheck(turn)) kingSquare(turn) else null
    }

    @Synchronized
    fun turnSecondsRemaining(): Int {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) return 0
        val remainingMs = currentTurnRemainingMs()
        return if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1000L).toInt()
    }

    @Synchronized
    fun whiteClockSecondsRemaining(): Int {
        if (!timedModeEnabled) return 0
        val remainingMs =
            if (turn == ChessColor.WHITE &&
                phase == ChessPhase.IN_PROGRESS
            ) {
                currentTurnRemainingMs()
            } else {
                whiteTimeRemainingMs
            }
        return if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1000L).toInt()
    }

    @Synchronized
    fun blackClockSecondsRemaining(): Int {
        if (!timedModeEnabled) return 0
        val remainingMs =
            if (turn == ChessColor.BLACK &&
                phase == ChessPhase.IN_PROGRESS
            ) {
                currentTurnRemainingMs()
            } else {
                blackTimeRemainingMs
            }
        return if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1000L).toInt()
    }

    @Synchronized
    fun clockStarted(): Boolean = clockStarted

    @Synchronized
    fun applyTurnClockTick(): Boolean {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) return false
        val wasClockStarted = clockStarted
        if (!clockStarted) {
            maybeStartClockAfterWhiteGracePeriod()
        }
        if (!clockStarted) return false
        updateTurnDeadline()
        return !wasClockStarted && clockStarted
    }

    private fun enqueueRenderedState(
        player: Player,
        renderState: (ChessGame, String, String) -> String,
    ) {
        val terminal = phase == ChessPhase.GAME_OVER
        val globalVersion = stateVersion.incrementAndGet()
        val version = playerSignalVersions.merge(player.sessionId, globalVersion) { old, _ -> old + 1 } ?: globalVersion
        player.enqueueState(
            renderState(this, player.sessionId, player.lang),
            terminal = terminal,
            version = version,
        )
    }

    fun broadcastToAllConnected(renderState: (ChessGame, String, String) -> String) {
        players.values
            .filter { it.connected }
            .forEach { player -> enqueueRenderedState(player, renderState) }
    }

    fun broadcastToConnectedExcept(
        excludedSessionId: String,
        renderState: (ChessGame, String, String) -> String,
    ) {
        players.values
            .filter { it.connected && it.sessionId != excludedSessionId }
            .forEach { player -> enqueueRenderedState(player, renderState) }
    }

    fun cleanup() {
        players.values.forEach {
            it.clearPendingStateHtml()
            runCatching { it.channel.close() }
        }
        playerSignalVersions.clear()
    }

    @Synchronized
    internal fun forcePositionForTesting(
        pieces: Map<String, Char>,
        turn: ChessColor,
    ) {
        board.clear()
        pieces.forEach { (square, piece) ->
            board[square.lowercase()] = piece
        }
        this.turn = turn
        this.phase = ChessPhase.IN_PROGRESS
        this.winnerSessionId = null
        this.endReason = null
        this.lastMove = null
        this.lastMoveMeta = null
        clearEnPassant()
        kingMoved[ChessColor.WHITE] = true
        kingMoved[ChessColor.BLACK] = true
        rookMoved["a1"] = true
        rookMoved["h1"] = true
        rookMoved["a8"] = true
        rookMoved["h8"] = true
    }

    @Synchronized
    internal fun evaluateEndStateForTesting(lastMoverSessionId: String) {
        evaluateGameEndAfterMove(lastMoverSessionId)
    }

    @Synchronized
    internal fun forceGameOverForTesting(
        winnerSessionId: String?,
        reason: String,
    ) {
        this.phase = ChessPhase.GAME_OVER
        this.winnerSessionId = winnerSessionId
        this.endReason = reason
        this.turnStartedAt = null
        this.clockStarted = false
        this.autoRestartAt = null
    }

    @Synchronized
    fun legalMovesFor(
        sessionId: String,
        from: String,
    ): List<String> {
        if (phase != ChessPhase.IN_PROGRESS) return emptyList()
        val color = colorAssignments[sessionId] ?: return emptyList()
        if (color != turn) return emptyList()

        val normalizedFrom = from.trim().lowercase()
        if (!isValidSquare(normalizedFrom)) return emptyList()

        val piece = board[normalizedFrom] ?: return emptyList()
        if (pieceColor(piece) != color) return emptyList()

        val moves = mutableListOf<String>()
        for (file in 'a'..'h') {
            for (rank in '1'..'8') {
                val to = "$file$rank"
                if (to == normalizedFrom) continue
                if (isMoveLegalForValidation(sessionId, normalizedFrom, to)) {
                    moves.add(to)
                }
            }
        }
        return moves
    }

    private fun maybeStartGame() {
        if (phase == ChessPhase.GAME_OVER) return

        updateColorAssignmentCache()

        val previousPhase = phase

        phase = if (colorAssignments.size >= 2) ChessPhase.IN_PROGRESS else ChessPhase.WAITING_FOR_PLAYERS
        if (phase == ChessPhase.IN_PROGRESS) {
            if (previousPhase != ChessPhase.IN_PROGRESS) {
                if (whiteTimeRemainingMs <= 0L || blackTimeRemainingMs <= 0L) {
                    resetPlayerClocks()
                }
                gameStartedAt = System.currentTimeMillis()
                startTurnClock()
                clockStarted = false
            }
        } else {
            if (previousPhase == ChessPhase.IN_PROGRESS) {
                updateTurnDeadline()
            }
            turnStartedAt = null
            clockStarted = false
            gameStartedAt = null
        }
        if (phase != ChessPhase.GAME_OVER) {
            autoRestartAt = null
        }
    }

    private fun removeExpiredPlayer(sessionId: String): Boolean {
        val existed = players.remove(sessionId) != null
        playerSignalVersions.remove(sessionId)
        playerOrder.remove(sessionId)
        colorAssignments.remove(sessionId)
        updateColorAssignmentCache()
        return existed
    }

    private fun updateColorAssignmentCache() {
        whiteSessionId = colorAssignments.entries.firstOrNull { it.value == ChessColor.WHITE }?.key
        blackSessionId = colorAssignments.entries.firstOrNull { it.value == ChessColor.BLACK }?.key
    }

    private fun randomizeColorsForActivePlayers() {
        val activeSessions = colorAssignments.keys.toList()
        if (activeSessions.size < 2) {
            updateColorAssignmentCache()
            return
        }
        val shuffled = activeSessions.shuffled(Random.Default)
        colorAssignments.clear()
        colorAssignments[shuffled[0]] = ChessColor.WHITE
        colorAssignments[shuffled[1]] = ChessColor.BLACK
        updateColorAssignmentCache()
    }

    private fun longestConnectedSpectatorSessionId(): String? =
        allPlayers
            .filter { it.connected }
            .filter { colorAssignments[it.sessionId] == null }
            .sortedWith(
                compareBy<Player> { it.connectedSinceAt ?: Long.MAX_VALUE }
                    .thenBy { playerOrder.indexOf(it.sessionId).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE },
            ).firstOrNull()
            ?.sessionId

    private fun promoteLongestConnectedSpectatorAgainst(keepSessionId: String): Boolean {
        val spectator = longestConnectedSpectatorSessionId() ?: return false
        if (players[keepSessionId]?.connected != true || players[spectator] == null) return false

        resetBoard()
        turn = ChessColor.WHITE
        winnerSessionId = null
        endReason = null
        lastMove = null
        lastMoveMeta = null
        resetPlayerClocks()
        gameStartedAt = System.currentTimeMillis()
        startTurnClock()
        clockStarted = false
        colorAssignments.clear()
        colorAssignments[keepSessionId] = ChessColor.WHITE
        colorAssignments[spectator] = ChessColor.BLACK
        players.values.forEach { p -> p.marbles = if (colorAssignments.containsKey(p.sessionId)) 1 else 0 }
        updateColorAssignmentCache()
        return true
    }

    private fun rotateAfterGameOverIfNeeded() {
        if (streamerModeEnabled) {
            rotateWithFixedCreatorIfPossible()
            return
        }

        markLoserAsFreshSpectatorForRotation()

        val loserSessionId =
            winnerSessionId
                ?.let { winner -> colorAssignments.keys.firstOrNull { it != winner } }
                ?: return
        if (players[loserSessionId] == null) return

        val spectator = longestConnectedSpectatorSessionId() ?: return
        if (spectator == loserSessionId) return

        val winner = winnerSessionId ?: return
        nextRoundForcedPlayers = listOf(winner, spectator)
    }

    private fun markLoserAsFreshSpectatorForRotation() {
        val loserSessionId =
            winnerSessionId
                ?.let { winner -> colorAssignments.keys.firstOrNull { it != winner } }
                ?: return
        val loser = players[loserSessionId] ?: return
        if (loser.connected) {
            loser.connectedSinceAt = System.currentTimeMillis()
        }
    }

    private fun rotateWithFixedCreatorIfPossible() {
        val host = creatorSessionId
        val hostPlayer = players[host] ?: return
        if (!hostPlayer.connected) return

        val spectator = longestConnectedSpectatorSessionId() ?: return
        if (spectator == host) return

        nextRoundForcedPlayers = listOf(host, spectator)
    }

    private fun updateTurnDeadline() {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) {
            turnStartedAt = null
            return
        }

        if (!clockStarted) {
            return
        }

        val startedAt =
            turnStartedAt ?: run {
                turnStartedAt = System.currentTimeMillis()
                return
            }

        val now = System.currentTimeMillis()
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        if (turn == ChessColor.WHITE) {
            whiteTimeRemainingMs = (whiteTimeRemainingMs - elapsed).coerceAtLeast(0L)
        } else {
            blackTimeRemainingMs = (blackTimeRemainingMs - elapsed).coerceAtLeast(0L)
        }
        turnStartedAt = now
    }

    private fun resetPlayerClocks() {
        whiteTimeRemainingMs = initialClockMs
        blackTimeRemainingMs = initialClockMs
    }

    private fun startTurnClock() {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) {
            turnStartedAt = null
            return
        }
        if (!clockStarted) {
            turnStartedAt = null
            return
        }
        turnStartedAt = System.currentTimeMillis()
    }

    private fun maybeStartClockAfterWhiteGracePeriod() {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS || clockStarted) return
        if (turn != ChessColor.WHITE) return
        val startedAt = gameStartedAt ?: return
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < WHITE_FIRST_MOVE_GRACE_MS) return
        val overtime = elapsed - WHITE_FIRST_MOVE_GRACE_MS
        clockStarted = true
        startTurnClock()
        if (overtime > 0L) {
            updateTurnDeadlineFromElapsed(overtime)
        }
    }

    private fun updateTurnDeadlineFromElapsed(elapsedMs: Long) {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS || !clockStarted) return
        if (elapsedMs <= 0L) return
        val elapsed = elapsedMs.coerceAtLeast(0L)
        if (turn == ChessColor.WHITE) {
            whiteTimeRemainingMs = (whiteTimeRemainingMs - elapsed).coerceAtLeast(0L)
        } else {
            blackTimeRemainingMs = (blackTimeRemainingMs - elapsed).coerceAtLeast(0L)
        }
        turnStartedAt = System.currentTimeMillis()
    }

    @Synchronized
    internal fun forceCurrentTurnElapsedForTesting(elapsedMs: Long) {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) return
        val now = System.currentTimeMillis()
        turnStartedAt = now - elapsedMs
        if (!clockStarted && turn == ChessColor.WHITE) {
            gameStartedAt = now - elapsedMs
        }
    }

    @Synchronized
    internal fun forceClockStartedForTesting(started: Boolean) {
        clockStarted = started
    }

    private fun currentTurnRemainingMs(): Long {
        if (!timedModeEnabled || phase != ChessPhase.IN_PROGRESS) return 0L
        if (!clockStarted) {
            return if (turn == ChessColor.WHITE) whiteTimeRemainingMs else blackTimeRemainingMs
        }
        val base = if (turn == ChessColor.WHITE) whiteTimeRemainingMs else blackTimeRemainingMs
        val elapsed = (System.currentTimeMillis() - (turnStartedAt ?: System.currentTimeMillis())).coerceAtLeast(0L)
        return (base - elapsed).coerceAtLeast(0L)
    }

    private data class MoveSnapshot(
        val board: Map<String, Char>,
        val enPassantTargetSquare: String?,
        val enPassantPawnSquare: String?,
        val kingMoved: Map<ChessColor, Boolean>,
        val rookMoved: Map<String, Boolean>,
        val lastMove: String?,
        val lastMoveMeta: String?,
        val turn: ChessColor,
        val winnerSessionId: String?,
        val endReason: String?,
        val phase: ChessPhase,
        val whiteTimeRemainingMs: Long,
        val blackTimeRemainingMs: Long,
        val turnStartedAt: Long?,
        val clockStarted: Boolean,
        val gameStartedAt: Long?,
    )

    private fun snapshotState(): MoveSnapshot =
        MoveSnapshot(
            board = board.toMap(),
            enPassantTargetSquare = enPassantTargetSquare,
            enPassantPawnSquare = enPassantPawnSquare,
            kingMoved = kingMoved.toMap(),
            rookMoved = rookMoved.toMap(),
            lastMove = lastMove,
            lastMoveMeta = lastMoveMeta,
            turn = turn,
            winnerSessionId = winnerSessionId,
            endReason = endReason,
            phase = phase,
            whiteTimeRemainingMs = whiteTimeRemainingMs,
            blackTimeRemainingMs = blackTimeRemainingMs,
            turnStartedAt = turnStartedAt,
            clockStarted = clockStarted,
            gameStartedAt = gameStartedAt,
        )

    private fun restoreState(snapshot: MoveSnapshot) {
        board.clear()
        board.putAll(snapshot.board)
        enPassantTargetSquare = snapshot.enPassantTargetSquare
        enPassantPawnSquare = snapshot.enPassantPawnSquare
        kingMoved.clear()
        kingMoved.putAll(snapshot.kingMoved)
        rookMoved.clear()
        rookMoved.putAll(snapshot.rookMoved)
        lastMove = snapshot.lastMove
        lastMoveMeta = snapshot.lastMoveMeta
        turn = snapshot.turn
        winnerSessionId = snapshot.winnerSessionId
        endReason = snapshot.endReason
        phase = snapshot.phase
        whiteTimeRemainingMs = snapshot.whiteTimeRemainingMs
        blackTimeRemainingMs = snapshot.blackTimeRemainingMs
        turnStartedAt = snapshot.turnStartedAt
        clockStarted = snapshot.clockStarted
        gameStartedAt = snapshot.gameStartedAt
    }

    private fun resetBoard() {
        board.clear()
        clearEnPassant()
        kingMoved[ChessColor.WHITE] = false
        kingMoved[ChessColor.BLACK] = false
        rookMoved["a1"] = false
        rookMoved["h1"] = false
        rookMoved["a8"] = false
        rookMoved["h8"] = false
        val files = "abcdefgh"

        files.forEachIndexed { index, file ->
            board["${file}2"] = "PPPPPPPP"[index]
            board["${file}7"] = "pppppppp"[index]
        }

        val whiteBack = "RNBQKBNR"
        val blackBack = "rnbqkbnr"
        files.forEachIndexed { index, file ->
            board["${file}1"] = whiteBack[index]
            board["${file}8"] = blackBack[index]
        }
    }

    private fun promoteIfNeeded(
        piece: Char,
        to: String,
    ): Char {
        if (piece == 'P' && to.endsWith("8")) return 'Q'
        if (piece == 'p' && to.endsWith("1")) return 'q'
        return piece
    }

    private fun pieceColor(piece: Char): ChessColor = if (piece.isUpperCase()) ChessColor.WHITE else ChessColor.BLACK

    private fun isValidSquare(square: String): Boolean {
        if (square.length != 2) return false
        val file = square[0]
        val rank = square[1]
        return file in 'a'..'h' && rank in '1'..'8'
    }

    private fun isLegalPieceMove(
        piece: Char,
        from: String,
        to: String,
    ): Boolean {
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val target = board[to]

        return when (piece.lowercaseChar()) {
            'p' -> isLegalPawnMove(piece, from, to, dx, dy, target)
            'n' -> (kotlin.math.abs(dx) == 1 && kotlin.math.abs(dy) == 2) || (kotlin.math.abs(dx) == 2 && kotlin.math.abs(dy) == 1)
            'b' -> kotlin.math.abs(dx) == kotlin.math.abs(dy) && isPathClear(from, to)
            'r' -> (dx == 0 || dy == 0) && isPathClear(from, to)
            'q' -> ((dx == 0 || dy == 0) || (kotlin.math.abs(dx) == kotlin.math.abs(dy))) && isPathClear(from, to)
            'k' -> kotlin.math.abs(dx) <= 1 && kotlin.math.abs(dy) <= 1
            else -> false
        }
    }

    private fun isLegalPawnMove(
        piece: Char,
        from: String,
        to: String,
        dx: Int,
        dy: Int,
        target: Char?,
    ): Boolean {
        val isWhite = piece.isUpperCase()
        val direction = if (isWhite) 1 else -1
        val startRank = if (isWhite) '2' else '7'

        if (dx == 0 && dy == direction && target == null) return true

        if (dx == 0 && dy == 2 * direction && from[1] == startRank && target == null) {
            val midRank = (from[1].code + direction).toChar()
            return board["${from[0]}$midRank"] == null
        }

        if (kotlin.math.abs(dx) == 1 && dy == direction) {
            if (target != null) return true
            return to == enPassantTargetSquare
        }

        return false
    }

    private fun isPathClear(
        from: String,
        to: String,
    ): Boolean {
        val fromFile = from[0]
        val fromRank = from[1]
        val toFile = to[0]
        val toRank = to[1]

        val fileStep = (toFile - fromFile).coerceIn(-1, 1)
        val rankStep = (toRank - fromRank).coerceIn(-1, 1)

        var file = fromFile.code + fileStep
        var rank = fromRank.code + rankStep

        while (file != toFile.code || rank != toRank.code) {
            if (board["${file.toChar()}${rank.toChar()}"] != null) {
                return false
            }
            file += fileStep
            rank += rankStep
        }

        return true
    }

    private fun isCastlingAttempt(
        from: String,
        to: String,
    ): Boolean = from[1] == to[1] && kotlin.math.abs(to[0] - from[0]) == 2

    private fun canCastle(
        color: ChessColor,
        from: String,
        to: String,
    ): Boolean {
        val expectedKingSquare = if (color == ChessColor.WHITE) "e1" else "e8"
        if (from != expectedKingSquare) return false
        val king = board[from] ?: return false
        if (king.lowercaseChar() != 'k' || pieceColor(king) != color) return false
        if (kingMoved[color] == true) return false

        val isKingSide = to[0] > from[0]
        val rookFrom =
            when {
                color == ChessColor.WHITE && isKingSide -> "h1"
                color == ChessColor.WHITE && !isKingSide -> "a1"
                color == ChessColor.BLACK && isKingSide -> "h8"
                else -> "a8"
            }

        val rook = board[rookFrom] ?: return false
        if (rook.lowercaseChar() != 'r' || pieceColor(rook) != color) return false
        if (rookMoved[rookFrom] == true) return false
        if (isKingInCheck(color)) return false
        if (!isPathClear(from, rookFrom)) return false

        val transitSquare =
            when {
                color == ChessColor.WHITE && isKingSide -> "f1"
                color == ChessColor.WHITE && !isKingSide -> "d1"
                color == ChessColor.BLACK && isKingSide -> "f8"
                else -> "d8"
            }

        val attackedBy = oppositeColor(color)
        if (isSquareAttacked(transitSquare, attackedBy)) return false
        if (isSquareAttacked(to, attackedBy)) return false

        return true
    }

    private fun executeCastling(
        color: ChessColor,
        from: String,
        to: String,
    ) {
        val king = board[from] ?: return
        val isKingSide = to[0] > from[0]
        val rookFrom =
            when {
                color == ChessColor.WHITE && isKingSide -> "h1"
                color == ChessColor.WHITE && !isKingSide -> "a1"
                color == ChessColor.BLACK && isKingSide -> "h8"
                else -> "a8"
            }
        val rookTo =
            when {
                color == ChessColor.WHITE && isKingSide -> "f1"
                color == ChessColor.WHITE && !isKingSide -> "d1"
                color == ChessColor.BLACK && isKingSide -> "f8"
                else -> "d8"
            }
        val rook = board[rookFrom] ?: return

        board.remove(from)
        board.remove(rookFrom)
        board[to] = king
        board[rookTo] = rook

        rookMoved[rookFrom] = true
    }

    private fun isEnPassantCapture(
        piece: Char,
        from: String,
        to: String,
        target: Char?,
    ): Boolean {
        if (piece.lowercaseChar() != 'p') return false
        if (target != null) return false
        if (kotlin.math.abs(to[0] - from[0]) != 1) return false
        return to == enPassantTargetSquare
    }

    private fun updatePieceMoveState(
        piece: Char,
        from: String,
        to: String,
        capturedPiece: Char?,
    ) {
        when {
            piece.lowercaseChar() == 'k' -> kingMoved[pieceColor(piece)] = true
            piece.lowercaseChar() == 'r' && from in setOf("a1", "h1", "a8", "h8") -> rookMoved[from] = true
        }

        if (capturedPiece != null && capturedPiece.lowercaseChar() == 'r' && to in setOf("a1", "h1", "a8", "h8")) {
            rookMoved[to] = true
        }
    }

    private fun updateEnPassantState(
        piece: Char,
        from: String,
        to: String,
    ) {
        if (piece.lowercaseChar() != 'p') {
            clearEnPassant()
            return
        }

        val dy = to[1] - from[1]
        if (kotlin.math.abs(dy) == 2) {
            val direction = if (piece.isUpperCase()) 1 else -1
            val midRank = (from[1].code + direction).toChar()
            enPassantTargetSquare = "${from[0]}$midRank"
            enPassantPawnSquare = to
        } else {
            clearEnPassant()
        }
    }

    private fun clearEnPassant() {
        enPassantTargetSquare = null
        enPassantPawnSquare = null
    }

    private fun isKingInCheck(color: ChessColor): Boolean {
        val kingSquare =
            board.entries
                .firstOrNull { it.value.lowercaseChar() == 'k' && pieceColor(it.value) == color }
                ?.key
                ?: return false

        return isSquareAttacked(kingSquare, oppositeColor(color))
    }

    private fun isSquareAttacked(
        square: String,
        byColor: ChessColor,
    ): Boolean {
        board.entries.forEach { (from, piece) ->
            if (pieceColor(piece) != byColor) return@forEach
            if (canPieceAttack(piece, from, square)) return true
        }
        return false
    }

    private fun canPieceAttack(
        piece: Char,
        from: String,
        to: String,
    ): Boolean {
        if (from == to) return false

        val dx = to[0] - from[0]
        val dy = to[1] - from[1]

        return when (piece.lowercaseChar()) {
            'p' -> {
                val direction = if (piece.isUpperCase()) 1 else -1
                kotlin.math.abs(dx) == 1 && dy == direction
            }
            'n' -> (kotlin.math.abs(dx) == 1 && kotlin.math.abs(dy) == 2) || (kotlin.math.abs(dx) == 2 && kotlin.math.abs(dy) == 1)
            'b' -> kotlin.math.abs(dx) == kotlin.math.abs(dy) && isPathClear(from, to)
            'r' -> (dx == 0 || dy == 0) && isPathClear(from, to)
            'q' -> ((dx == 0 || dy == 0) || (kotlin.math.abs(dx) == kotlin.math.abs(dy))) && isPathClear(from, to)
            'k' -> kotlin.math.abs(dx) <= 1 && kotlin.math.abs(dy) <= 1
            else -> false
        }
    }

    private fun oppositeColor(color: ChessColor): ChessColor = if (color == ChessColor.WHITE) ChessColor.BLACK else ChessColor.WHITE

    private fun evaluateGameEndAfterMove(moverSessionId: String) {
        val sideToMove = turn
        val inCheck = isKingInCheck(sideToMove)
        val hasMoves = hasAnyLegalMove(sideToMove)

        if (!hasMoves) {
            phase = ChessPhase.GAME_OVER
            turnStartedAt = null
            clockStarted = false
            if (inCheck) {
                winnerSessionId = moverSessionId
                endReason = "checkmate"
            } else {
                winnerSessionId = null
                endReason = "stalemate"
            }
            scheduleAutoRestart(10)
        }
    }

    private fun hasAnyLegalMove(color: ChessColor): Boolean {
        val sessionId = colorAssignments.entries.firstOrNull { it.value == color }?.key ?: return false
        val pieces =
            board.entries
                .filter { pieceColor(it.value) == color }
                .map { it.key }

        for (from in pieces) {
            for (file in 'a'..'h') {
                for (rank in '1'..'8') {
                    val to = "$file$rank"
                    if (from == to) continue
                    if (isMoveLegalForValidation(sessionId, from, to)) return true
                }
            }
        }
        return false
    }

    private fun isMoveLegalForValidation(
        sessionId: String,
        from: String,
        to: String,
    ): Boolean {
        val snapshot = snapshotState()
        val result = makeMoveWithoutGameEnd(sessionId, from, to)
        restoreState(snapshot)
        return result
    }

    private fun makeMoveWithoutGameEnd(
        sessionId: String,
        from: String,
        to: String,
    ): Boolean {
        if (phase != ChessPhase.IN_PROGRESS) return false
        val movingColor = colorAssignments[sessionId] ?: return false
        if (movingColor != turn) return false

        val normalizedFrom = from.trim().lowercase()
        val normalizedTo = to.trim().lowercase()
        if (!isValidSquare(normalizedFrom) || !isValidSquare(normalizedTo) || normalizedFrom == normalizedTo) return false

        val piece = board[normalizedFrom] ?: return false
        if (pieceColor(piece) != movingColor) return false
        val target = board[normalizedTo]
        if (target != null && pieceColor(target) == movingColor) return false
        if (target != null && target.lowercaseChar() == 'k') return false

        if (piece.lowercaseChar() == 'k' && isCastlingAttempt(normalizedFrom, normalizedTo)) {
            if (!canCastle(movingColor, normalizedFrom, normalizedTo)) return false
            executeCastling(movingColor, normalizedFrom, normalizedTo)
            kingMoved[movingColor] = true
            clearEnPassant()
            if (isKingInCheck(movingColor)) return false
            turn = oppositeColor(turn)
            return true
        }

        if (!isLegalPieceMove(piece, normalizedFrom, normalizedTo)) return false

        if (isEnPassantCapture(piece, normalizedFrom, normalizedTo, target)) {
            val capturedSquare = enPassantPawnSquare ?: return false
            val captured = board[capturedSquare] ?: return false
            if (captured.lowercaseChar() != 'p' || pieceColor(captured) == movingColor) return false
            board.remove(capturedSquare)
        }

        board.remove(normalizedFrom)
        board[normalizedTo] = promoteIfNeeded(piece, normalizedTo)
        updatePieceMoveState(piece, normalizedFrom, normalizedTo, target)
        updateEnPassantState(piece, normalizedFrom, normalizedTo)

        if (isKingInCheck(movingColor)) return false
        turn = oppositeColor(turn)
        return true
    }
}
