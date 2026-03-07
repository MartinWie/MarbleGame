package de.mw

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val chessManagerLogger = LoggerFactory.getLogger(ChessGameManager::class.java)

object ChessGameManager {
    private const val FINISHED_GAME_TTL_MS = 1 * 60 * 60 * 1000L
    private const val ABANDONED_GAME_TTL_MS = 4 * 60 * 60 * 1000L

    private val games = ConcurrentHashMap<String, ChessGame>()

    private val cleanupScheduler =
        Executors
            .newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ChessGameManager-Cleanup").apply { isDaemon = true }
            }.also { scheduler ->
                scheduler.scheduleAtFixedRate(
                    ::cleanupOldGames,
                    15,
                    15,
                    TimeUnit.MINUTES,
                )
            }

    fun createGame(
        creatorSessionId: String,
        timedModeEnabled: Boolean = false,
        streamerModeEnabled: Boolean = false,
        initialClockSeconds: Int = ChessGame.INITIAL_CLOCK_SECONDS,
    ): ChessGame {
        val game =
            ChessGame(
                creatorSessionId = creatorSessionId,
                timedModeEnabled = timedModeEnabled,
                streamerModeEnabled = streamerModeEnabled,
                initialClockSeconds = initialClockSeconds,
            )
        games[game.id] = game
        chessManagerLogger.info("Chess game {} created, {} active games total", game.id, games.size)
        return game
    }

    fun getGame(gameId: String): ChessGame? = games[gameId]

    fun removeGame(gameId: String) {
        games.remove(gameId)
    }

    fun gameCount(): Int = games.size

    fun cleanupOldGames() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()

        games.forEach { (gameId, game) ->
            val age = now - game.lastActivityAt
            val shouldRemove =
                when {
                    game.phase == ChessPhase.GAME_OVER && age > FINISHED_GAME_TTL_MS -> true
                    age > ABANDONED_GAME_TTL_MS -> true
                    else -> false
                }

            if (shouldRemove) {
                toRemove.add(gameId)
            }
        }

        toRemove.forEach { gameId ->
            games.remove(gameId)?.cleanup()
        }

        if (toRemove.isNotEmpty()) {
            chessManagerLogger.info("Cleaned up {} old chess games, {} remaining", toRemove.size, games.size)
        }
    }
}
