package de.mw

import io.github.martinwie.htmx.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.html.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Routing")

/** Ping interval for SSE keepalive (5 seconds). */
private const val SSE_PING_INTERVAL_MS = 5_000L

/** Maximum length for player names to prevent abuse. */
private const val MAX_PLAYER_NAME_LENGTH = 30

fun Application.configureRouting() {
    install(SSE)
    routing {
        // Home page - create or join a game
        get("/") {
            val session = call.sessions.get<UserSession>()
            val savedName = session?.playerName ?: ""
            val error = call.request.queryParameters["error"]
            val lang = call.getLanguage()

            call.respondHtml {
                head {
                    title { +"game.title".t(lang) }
                    meta(name = "viewport", content = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no")
                    script(src = "/static/htmx.min.js") {}
                    link(rel = "stylesheet", href = "/static/style.css")
                    posthogScript()
                }
                body {
                    div("container") {
                        h1 { +"game.title".t(lang) }

                        if (error == "game_not_found") {
                            div("error-message") {
                                +"error.gameNotFound".t(lang)
                            }
                        }

                        div("card") {
                            h2 { +"home.createGame".t(lang) }
                            form {
                                hxPost("/game/create")
                                hxTarget("body")

                                div("form-group") {
                                    label { +"home.yourName".t(lang) }
                                    input(type = InputType.text, name = "playerName") {
                                        required = true
                                        placeholder = "home.namePlaceholder".t(lang)
                                        value = savedName
                                        maxLength = MAX_PLAYER_NAME_LENGTH.toString()
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn btn-primary") { +"button.create".t(lang) }
                            }
                        }
                    }

                    // Cookie consent banner
                    cookieConsentBanner(lang)
                }
            }
        }

        // Create a new game
        post("/game/create") {
            val session =
                call.sessions.get<UserSession>() ?: run {
                    call.respondText("Session not found", status = HttpStatusCode.BadRequest)
                    return@post
                }
            val params = call.receiveParameters()
            val playerName = params["playerName"]?.trim()?.take(MAX_PLAYER_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: "Player"
            val lang = call.getLanguage()

            // Save player name to session
            call.sessions.set(session.copy(playerName = playerName))

            val game = GameManager.createGame(session.id)
            game.addPlayer(session.id, playerName, lang)

            call.respondRedirect("/game/${game.id}")
        }

        // Join an existing game
        post("/game/join") {
            val session =
                call.sessions.get<UserSession>() ?: run {
                    call.respondText("Session not found", status = HttpStatusCode.BadRequest)
                    return@post
                }
            val params = call.receiveParameters()
            val playerName = params["playerName"]?.trim()?.take(MAX_PLAYER_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: "Player"
            val gameId =
                params["gameId"]?.trim()?.lowercase() ?: run {
                    call.respondText("Game ID required", status = HttpStatusCode.BadRequest)
                    return@post
                }

            // Save player name to session
            call.sessions.set(session.copy(playerName = playerName))

            val game =
                GameManager.getGame(gameId) ?: run {
                    call.respondRedirect("/?error=game_not_found")
                    return@post
                }

            val lang = call.getLanguage()

            // Add player - as regular player if waiting/game over, or as pending spectator if game in progress
            if (game.phase == GamePhase.WAITING_FOR_PLAYERS || game.phase == GamePhase.GAME_OVER) {
                game.addPlayer(session.id, playerName, lang)
            } else {
                game.addPendingPlayer(session.id, playerName, lang)
            }

            // Notify existing connected players about the new player
            logger.info("Player '{}' joined game {}", playerName, game.id)
            game.broadcastToAllConnected(::renderGameState)

            call.respondRedirect("/game/${game.id}")
        }

        // Direct join via URL
        get("/game/{gameId}/join") {
            val session = call.sessions.get<UserSession>()
            val savedName = session?.playerName ?: ""

            val gameId =
                call.parameters["gameId"] ?: run {
                    call.respondRedirect("/")
                    return@get
                }
            val game =
                GameManager.getGame(gameId) ?: run {
                    call.respondRedirect("/?error=game_not_found")
                    return@get
                }

            // Auto-join if session has a saved name
            if (session != null && savedName.isNotEmpty()) {
                // Check if already in game
                if (game.players[session.id] == null) {
                    val lang = call.getLanguage()
                    if (game.phase == GamePhase.WAITING_FOR_PLAYERS || game.phase == GamePhase.GAME_OVER) {
                        game.addPlayer(session.id, savedName, lang)
                    } else {
                        game.addPendingPlayer(session.id, savedName, lang)
                    }
                    game.broadcastToAllConnected(::renderGameState)
                }
                call.respondRedirect("/game/$gameId")
                return@get
            }

            val gameInProgress = game.phase != GamePhase.WAITING_FOR_PLAYERS && game.phase != GamePhase.GAME_OVER
            val lang = call.getLanguage()

            call.respondHtml {
                head {
                    title { +"join.title".t(lang) }
                    meta(name = "viewport", content = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no")
                    script(src = "/static/htmx.min.js") {}
                    link(rel = "stylesheet", href = "/static/style.css")
                    posthogScript()
                }
                body {
                    div("container") {
                        h1 { +"join.title".t(lang) }

                        div("card") {
                            if (gameInProgress) {
                                p("hint") { +"join.spectatorHint".t(lang) }
                            }
                            form {
                                hxPost("/game/join")
                                hxTarget("body")

                                input(type = InputType.hidden, name = "gameId") { value = gameId }
                                div("form-group") {
                                    label { +"home.yourName".t(lang) }
                                    input(type = InputType.text, name = "playerName") {
                                        required = true
                                        placeholder = "home.namePlaceholder".t(lang)
                                        value = savedName
                                        maxLength = MAX_PLAYER_NAME_LENGTH.toString()
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn btn-primary") {
                                    if (gameInProgress) +"button.joinSpectator".t(lang) else +"button.join".t(lang)
                                }
                            }
                        }
                    }

                    // Cookie consent banner
                    cookieConsentBanner(lang)
                }
            }
        }

        // Game page
        get("/game/{gameId}") {
            val session =
                call.sessions.get<UserSession>() ?: run {
                    call.respondRedirect("/")
                    return@get
                }
            val gameId =
                call.parameters["gameId"] ?: run {
                    call.respondRedirect("/")
                    return@get
                }
            val game =
                GameManager.getGame(gameId) ?: run {
                    val lang = call.getLanguage()
                    call.respondHtml(HttpStatusCode.NotFound) {
                        head {
                            meta(name = "viewport", content = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no")
                            link(rel = "stylesheet", href = "/static/style.css")
                            posthogScript()
                        }
                        body {
                            div("container") {
                                div("card") {
                                    h1 { +"error.gameNotFound".t(lang) }
                                    a(href = "/", classes = "btn btn-primary") { +"button.goBack".t(lang) }
                                }
                            }

                            // Cookie consent banner
                            cookieConsentBanner(lang)
                        }
                    }
                    return@get
                }

            val player = game.players[session.id]
            if (player == null) {
                call.respondRedirect("/game/$gameId/join")
                return@get
            }

            val lang = call.getLanguage()
            call.respondHtml {
                renderGamePage(game, session.id, lang)
            }
        }

        // SSE endpoint for game updates with ping keepalive
        sse("/game/{gameId}/events") {
            val session = call.sessions.get<UserSession>() ?: return@sse
            val gameId = call.parameters["gameId"] ?: return@sse
            val game = GameManager.getGame(gameId) ?: return@sse
            val player = game.players[session.id] ?: return@sse
            val lang = call.getLanguage()

            // Update player's language preference (may have changed since join)
            player.lang = lang

            // Drain any pending messages from previous connection (handles reconnect scenario)
            while (player.channel.tryReceive().isSuccess) {
                // Discard old messages
            }

            // Start new connection and get connection ID (this also marks player as connected)
            val connectionId = player.startNewConnection()
            logger.debug("SSE connected: {} connectionId={}", player.name, connectionId)

            // Flag to signal when connection should close
            var connectionAlive = true

            // Start ping job to keep connection alive and detect dead connections
            val pingJob =
                launch {
                    while (isActive && connectionAlive) {
                        delay(SSE_PING_INTERVAL_MS)
                        try {
                            send(ServerSentEvent("ping", event = "ping"))
                        } catch (e: Exception) {
                            logger.debug("SSE ping failed for {}: {}", player.name, e.message)
                            connectionAlive = false
                            // Send a dummy message to wake up the channel listener
                            player.channel.trySend("__CONNECTION_CHECK__")
                            break
                        }
                    }
                }

            try {
                // Notify OTHER players about this player's connection/reconnection
                game.players.values
                    .filter { it.connected && it.sessionId != session.id }
                    .forEach { otherPlayer ->
                        otherPlayer.channel.trySend(renderGameState(game, otherPlayer.sessionId, otherPlayer.lang))
                    }

                // Send initial state
                send(ServerSentEvent(renderGameState(game, session.id, lang), event = "game-update"))

                // Start a job to send a refresh after a short delay
                // This catches any broadcasts that happened during connection setup
                val refreshJob =
                    launch {
                        delay(100) // Wait 100ms for any in-flight broadcasts to settle
                        if (connectionId == player.currentConnectionId && connectionAlive) {
                            try {
                                send(ServerSentEvent(renderGameState(game, session.id, lang), event = "game-update"))
                            } catch (e: Exception) {
                                // Connection might be closed, ignore
                            }
                        }
                    }

                for (message in player.channel) {
                    // Check if connection is still alive (ping might have failed)
                    if (!connectionAlive) {
                        break
                    }
                    // Skip internal connection check messages
                    if (message == "__CONNECTION_CHECK__") {
                        continue
                    }
                    // If a new connection has taken over, stop processing and exit
                    if (connectionId != player.currentConnectionId) {
                        break
                    }
                    send(ServerSentEvent(message, event = "game-update"))
                }

                refreshJob.cancel()
            } catch (e: ClosedReceiveChannelException) {
                // Channel closed, normal disconnect
            } catch (e: Exception) {
                logger.warn("SSE error for {}: {}", player.name, e.message)
            } finally {
                pingJob.cancel()
                // Only mark as disconnected if this is still the current connection
                // This prevents race conditions during rapid reconnects
                player.endConnection(connectionId)

                // Only handle disconnect and broadcast if we actually disconnected
                if (!player.connected) {
                    logger.debug("SSE disconnected: {}", player.name)
                    val stateChanged = game.handlePlayerDisconnect(session.id)

                    // Notify remaining players about the disconnect
                    if (stateChanged) {
                        game.broadcastToAllConnected(::renderGameState)
                    }
                }
            }
        }

        // Check for expired grace periods (called by client countdown timer)
        post("/game/{gameId}/check-disconnects") {
            val gameId = call.parameters["gameId"] ?: return@post
            val game = GameManager.getGame(gameId) ?: return@post

            // Check all disconnected players for expired grace periods
            // Make a copy to avoid ConcurrentModificationException
            var stateChanged = false
            val expiredPlayers =
                game.allPlayers
                    .filter { !it.connected && !it.isWithinGracePeriod() }
                    .map { it.sessionId }

            expiredPlayers.forEach { sessionId ->
                if (game.handleGracePeriodExpired(sessionId)) {
                    stateChanged = true
                }
            }

            if (stateChanged) {
                game.broadcastToAllConnected(::renderGameState)
            }

            call.respondText("OK")
        }

        // Start the game
        post("/game/{gameId}/start") {
            val session =
                call.sessions.get<UserSession>() ?: run {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@post
                }
            val gameId = call.parameters["gameId"] ?: return@post
            val game = GameManager.getGame(gameId) ?: return@post

            if (game.creatorSessionId != session.id) {
                call.respondText("Only the creator can start the game", status = HttpStatusCode.Forbidden)
                return@post
            }

            if (!game.startGame()) {
                call.respondText("Need at least 2 connected players", status = HttpStatusCode.BadRequest)
                return@post
            }

            logger.info("Game {} started with {} players", game.id, game.allPlayers.size)
            // Broadcast to all connected players
            game.broadcastToAllConnected(::renderGameState)

            call.respondText("OK")
        }

        // Place marbles
        post("/game/{gameId}/place") {
            val session = call.sessions.get<UserSession>() ?: return@post
            val gameId = call.parameters["gameId"] ?: return@post
            val game = GameManager.getGame(gameId) ?: return@post
            val params = call.receiveParameters()
            val amount = params["amount"]?.toIntOrNull() ?: return@post

            if (!game.placeMarbles(session.id, amount)) {
                call.respondText("Invalid move", status = HttpStatusCode.BadRequest)
                return@post
            }

            // Auto-resolve if no one can guess (e.g., all others are spectators)
            if (game.allActivePlayersGuessed()) {
                game.resolveRound()
            }

            // Broadcast to all connected players
            game.broadcastToAllConnected(::renderGameState)

            call.respondText("OK")
        }

        // Make a guess
        post("/game/{gameId}/guess") {
            val session = call.sessions.get<UserSession>() ?: return@post
            val gameId = call.parameters["gameId"] ?: return@post
            val game = GameManager.getGame(gameId) ?: return@post
            val params = call.receiveParameters()
            val guessStr = params["guess"] ?: return@post
            val guess =
                when (guessStr.uppercase()) {
                    "EVEN" -> Guess.EVEN
                    "ODD" -> Guess.ODD
                    else -> return@post
                }

            if (!game.makeGuess(session.id, guess)) {
                call.respondText("Invalid guess", status = HttpStatusCode.BadRequest)
                return@post
            }

            // Check if all players have guessed
            if (game.allActivePlayersGuessed()) {
                game.resolveRound()
            }

            // Broadcast to all connected players
            game.broadcastToAllConnected(::renderGameState)

            call.respondText("OK")
        }

        // Continue to next round
        post("/game/{gameId}/next-round") {
            val session = call.sessions.get<UserSession>() ?: return@post
            val gameId = call.parameters["gameId"] ?: return@post
            val game = GameManager.getGame(gameId) ?: return@post

            game.nextRound()

            // Broadcast to all connected players
            game.broadcastToAllConnected(::renderGameState)

            call.respondText("OK")
        }

        // New game (restart)
        post("/game/{gameId}/new-game") {
            val session = call.sessions.get<UserSession>() ?: return@post
            val gameId = call.parameters["gameId"] ?: return@post
            val game = GameManager.getGame(gameId) ?: return@post

            // Reset game state and rebuild playerOrder with connected players
            game.resetForNewGame()

            // Broadcast to all connected players
            game.broadcastToAllConnected(::renderGameState)

            call.respondText("OK")
        }

        // Static resources
        staticResources("/static", "static")

        // Root-level icon redirects (browsers/devices request these automatically)
        get("/favicon.ico") {
            call.respondRedirect("/static/favicon.ico", permanent = true)
        }
        get("/apple-touch-icon.png") {
            call.respondRedirect("/static/apple-touch-icon.png", permanent = true)
        }
        get("/apple-touch-icon-precomposed.png") {
            call.respondRedirect("/static/apple-touch-icon.png", permanent = true)
        }
        get("/manifest.json") {
            call.respondRedirect("/static/manifest.json", permanent = true)
        }
    }
}
