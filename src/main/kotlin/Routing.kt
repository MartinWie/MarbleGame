package de.mw

import io.github.martinwie.htmx.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.origin
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

/** Coroutine scope for game-related background tasks. */
private val gameScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

/**
 * Schedules auto-advance to next round after the countdown delay.
 * Only advances if the game is still in ROUND_RESULT phase.
 */
private fun scheduleRoundAdvance(game: Game) {
    gameScope.launch {
        delay(ROUND_RESULT_COUNTDOWN_SECONDS * 1000L)
        if (game.phase == GamePhase.ROUND_RESULT) {
            game.nextRound()
            game.broadcastToAllConnected(::renderGameState)
        }
    }
}

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
                basePage("home.title".t(lang), lang, includeHtmx = true) {
                    h1 { +"home.title".t(lang) }

                    if (error == "game_not_found") {
                        div("error-message") {
                            +"error.gameNotFound".t(lang)
                        }
                    }

                    div("card mode-selector-card") {
                        h2 { +"home.chooseMode".t(lang) }
                        p("hint") { +"home.chooseModeHint".t(lang) }

                        div("mode-grid") {
                            button(type = ButtonType.button, classes = "mode-tile") {
                                attributes["data-mode"] = "marbles"
                                attributes["aria-pressed"] = "false"
                                attributes["aria-controls"] = "create-form-marbles"
                                attributes["onclick"] =
                                    """
                                    document.querySelectorAll('.mode-tile').forEach(function(x){x.classList.remove('active');});
                                    document.querySelectorAll('.mode-tile').forEach(function(x){x.setAttribute('aria-pressed','false');});
                                    this.classList.add('active');
                                    this.setAttribute('aria-pressed','true');
                                    document.getElementById('create-form-marbles').classList.add('active');
                                    document.getElementById('create-form-chess').classList.remove('active');
                                    document.getElementById('create-mode-placeholder').classList.remove('active');
                                    """.trimIndent().replace("\n", " ")
                                span("mode-icon") { +"●●" }
                                span("mode-title") { +"home.mode.marbles".t(lang) }
                                span("mode-hint") { +"home.createGameHint".t(lang) }
                            }

                            button(type = ButtonType.button, classes = "mode-tile") {
                                attributes["data-mode"] = "chess"
                                attributes["aria-pressed"] = "false"
                                attributes["aria-controls"] = "create-form-chess"
                                attributes["onclick"] =
                                    """
                                    document.querySelectorAll('.mode-tile').forEach(function(x){x.classList.remove('active');});
                                    document.querySelectorAll('.mode-tile').forEach(function(x){x.setAttribute('aria-pressed','false');});
                                    this.classList.add('active');
                                    this.setAttribute('aria-pressed','true');
                                    document.getElementById('create-form-marbles').classList.remove('active');
                                    document.getElementById('create-form-chess').classList.add('active');
                                    document.getElementById('create-mode-placeholder').classList.remove('active');
                                    """.trimIndent().replace("\n", " ")
                                span("mode-icon") { +"♚" }
                                span("mode-title") { +"home.mode.chess".t(lang) }
                                span("mode-hint") { +"home.createChessHint".t(lang) }
                            }
                        }

                        div("create-mode-forms") {
                            p("hint create-mode-placeholder active") {
                                id = "create-mode-placeholder"
                                +"home.pickGameFirst".t(lang)
                            }

                            form {
                                id = "create-form-marbles"
                                classes = setOf("mode-form")
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

                            form {
                                id = "create-form-chess"
                                classes = setOf("mode-form")
                                hxPost("/chess/create")
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
                                div("option-row") {
                                    label("option-checkbox") {
                                        input(type = InputType.checkBox, name = "timedMode") {
                                            id = "timed-mode"
                                            attributes["onchange"] =
                                                "document.getElementById('timed-config-wrap').classList.toggle('hidden', !this.checked);"
                                        }
                                        span("option-slider") {}
                                        span("option-copy") {
                                            span("option-title") { +"home.chess.option.timed".t(lang) }
                                            span("option-subtitle") { +"home.chess.option.timed.hint".t(lang) }
                                        }
                                    }
                                }
                                div {
                                    id = "timed-config-wrap"
                                    classes = setOf("option-row", "timed-config", "hidden")
                                    label {
                                        htmlFor = "clock-minutes"
                                        +"home.chess.option.clockMinutes".t(lang)
                                    }
                                    input(type = InputType.number, name = "clockMinutes") {
                                        id = "clock-minutes"
                                        min = "1"
                                        max = "60"
                                        step = "1"
                                        value = "5"
                                        attributes["inputmode"] = "numeric"
                                    }
                                }
                                div("option-row") {
                                    label("option-checkbox") {
                                        input(type = InputType.checkBox, name = "streamerMode") { id = "streamer-mode" }
                                        span("option-slider") {}
                                        span("option-copy") {
                                            span("option-title") { +"home.chess.option.streamer".t(lang) }
                                            span("option-subtitle") { +"home.chess.option.streamer.hint".t(lang) }
                                        }
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn btn-primary") { +"button.createChess".t(lang) }
                            }
                        }
                    }
                }
            }
        }

        // Imprint page
        get("/imprint") {
            val lang = call.getLanguage()
            call.respondHtml {
                basePage("${"footer.imprint".t(lang)} - ${"game.title".t(lang)}", lang) {
                    h1 { +"footer.imprint".t(lang) }
                    div("card") {
                        style = "text-align: left;"
                        h2 { +"imprint.headline".t(lang) }
                        p { +"imprint.hobbyProject".t(lang) }
                        h3 { +"imprint.contact".t(lang) }
                        p { +"imprint.email".t(lang) }
                        h3 { +"imprint.liabilityContent".t(lang) }
                        p { +"imprint.liabilityContentText".t(lang) }
                        h3 { +"imprint.liabilityLinks".t(lang) }
                        p { +"imprint.liabilityLinksText".t(lang) }
                    }
                }
            }
        }

        // Privacy policy page
        get("/privacy") {
            val lang = call.getLanguage()
            call.respondHtml {
                basePage("${"footer.privacy".t(lang)} - ${"game.title".t(lang)}", lang) {
                    h1 { +"footer.privacy".t(lang) }
                    div("card") {
                        style = "text-align: left;"
                        h2 { +"privacy.headline".t(lang) }

                        h3 { +"privacy.controller".t(lang) }
                        p { +"privacy.controllerText".t(lang) }
                        p { +"privacy.controllerEmail".t(lang) }
                        p { +"privacy.hobbyProject".t(lang) }

                        h3 { +"privacy.dataCollected".t(lang) }
                        p { +"privacy.dataCollectedText".t(lang) }
                        ul {
                            li { +"privacy.dataSession".t(lang) }
                            li { +"privacy.dataPlayer".t(lang) }
                            li { +"privacy.dataAnalytics".t(lang) }
                        }

                        h3 { +"privacy.legalBasis".t(lang) }
                        p { +"privacy.legalBasisText".t(lang) }
                        ul {
                            li { +"privacy.legalBasisNecessary".t(lang) }
                            li { +"privacy.legalBasisConsent".t(lang) }
                        }

                        h3 { +"privacy.retention".t(lang) }
                        p { +"privacy.retentionText".t(lang) }

                        h3 { +"privacy.rights".t(lang) }
                        p { +"privacy.rightsText".t(lang) }
                        ul {
                            li { +"privacy.rightsAccess".t(lang) }
                            li { +"privacy.rightsRectification".t(lang) }
                            li { +"privacy.rightsErasure".t(lang) }
                            li { +"privacy.rightsRestriction".t(lang) }
                            li { +"privacy.rightsWithdraw".t(lang) }
                            li { +"privacy.rightsComplaint".t(lang) }
                        }

                        h3 { +"privacy.cookies".t(lang) }
                        p { +"privacy.cookiesText".t(lang) }

                        h3 { +"privacy.thirdParties".t(lang) }
                        p { +"privacy.thirdPartiesText".t(lang) }
                    }
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

            // Use HX-Redirect for HTMX requests to force full page navigation
            // This ensures scripts on the game page are properly loaded and executed
            val redirectUrl = "/game/${game.id}"
            if (call.request.headers["HX-Request"] == "true") {
                call.response.header("HX-Redirect", redirectUrl)
                call.respond(HttpStatusCode.OK)
            } else {
                call.respondRedirect(redirectUrl)
            }
        }

        // Create a new chess game
        post("/chess/create") {
            val session =
                call.sessions.get<UserSession>() ?: run {
                    call.respondText("Session not found", status = HttpStatusCode.BadRequest)
                    return@post
                }
            val params = call.receiveParameters()
            val playerName = params["playerName"]?.trim()?.take(MAX_PLAYER_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: "Player"
            val timedModeEnabled = params["timedMode"] == "on"
            val streamerModeEnabled = params["streamerMode"] == "on"
            val clockMinutes = params["clockMinutes"]?.toIntOrNull()?.coerceIn(1, 60) ?: 5
            val initialClockSeconds = clockMinutes * 60
            val lang = call.getLanguage()

            call.sessions.set(session.copy(playerName = playerName))

            val game =
                ChessGameManager.createGame(
                    session.id,
                    timedModeEnabled = timedModeEnabled,
                    streamerModeEnabled = streamerModeEnabled,
                    initialClockSeconds = initialClockSeconds,
                )
            game.addPlayer(session.id, playerName, lang)

            val redirectUrl = "/chess/${game.id}"
            if (call.request.headers["HX-Request"] == "true") {
                call.response.header("HX-Redirect", redirectUrl)
                call.respond(HttpStatusCode.OK)
            } else {
                call.respondRedirect(redirectUrl)
            }
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

            // Use HX-Redirect for HTMX requests to force full page navigation
            // This ensures scripts on the game page are properly loaded and executed
            val redirectUrl = "/game/${game.id}"
            if (call.request.headers["HX-Request"] == "true") {
                call.response.header("HX-Redirect", redirectUrl)
                call.respond(HttpStatusCode.OK)
            } else {
                call.respondRedirect(redirectUrl)
            }
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
                basePage("join.title".t(lang), lang, includeHtmx = true) {
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
            }
        }

        // Direct join via URL for chess games
        get("/chess/{gameId}/join") {
            val session = call.sessions.get<UserSession>()
            val savedName = session?.playerName ?: ""

            val gameId =
                call.parameters["gameId"] ?: run {
                    call.respondRedirect("/")
                    return@get
                }
            val game =
                ChessGameManager.getGame(gameId) ?: run {
                    call.respondRedirect("/?error=game_not_found")
                    return@get
                }

            if (session != null && savedName.isNotEmpty()) {
                if (game.players[session.id] == null) {
                    val lang = call.getLanguage()
                    game.addPlayer(session.id, savedName, lang)
                    game.broadcastToAllConnected(::renderChessState)
                }
                call.respondRedirect("/chess/$gameId")
                return@get
            }

            val lang = call.getLanguage()
            call.respondHtml {
                basePage("join.title".t(lang), lang, includeHtmx = true) {
                    h1 { +"chess.join.title".t(lang) }

                    div("card") {
                        p("hint") { +"chess.join.spectatorHint".t(lang) }
                        form {
                            hxPost("/chess/join")
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
                                +"button.join".t(lang)
                            }
                        }
                    }
                }
            }
        }

        // Join an existing chess game
        post("/chess/join") {
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

            call.sessions.set(session.copy(playerName = playerName))

            val game =
                ChessGameManager.getGame(gameId) ?: run {
                    call.respondRedirect("/?error=game_not_found")
                    return@post
                }

            val lang = call.getLanguage()
            game.addPlayer(session.id, playerName, lang)

            game.broadcastToAllConnected(::renderChessState)

            val redirectUrl = "/chess/${game.id}"
            if (call.request.headers["HX-Request"] == "true") {
                call.response.header("HX-Redirect", redirectUrl)
                call.respond(HttpStatusCode.OK)
            } else {
                call.respondRedirect(redirectUrl)
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
                        basePage("error.gameNotFound".t(lang), lang) {
                            div("card") {
                                h1 { +"error.gameNotFound".t(lang) }
                                a(href = "/", classes = "btn btn-primary") { +"button.goBack".t(lang) }
                            }
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

        // Chess game page
        get("/chess/{gameId}") {
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
                ChessGameManager.getGame(gameId) ?: run {
                    val lang = call.getLanguage()
                    call.respondHtml(HttpStatusCode.NotFound) {
                        basePage("error.gameNotFound".t(lang), lang) {
                            div("card") {
                                h1 { +"error.gameNotFound".t(lang) }
                                a(href = "/", classes = "btn btn-primary") { +"button.goBack".t(lang) }
                            }
                        }
                    }
                    return@get
                }

            val player = game.players[session.id]
            if (player == null) {
                call.respondRedirect("/chess/$gameId/join")
                return@get
            }

            val lang = call.getLanguage()
            call.respondHtml {
                renderChessPage(game, session.id, lang)
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

            // Handle reconnection (e.g., re-add to playerOrder in lobby)
            game.handlePlayerReconnect(session.id)

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
                    val phaseBefore = game.phase
                    val stateChanged = game.handlePlayerDisconnect(session.id)

                    // If disconnect caused a round to resolve, schedule auto-advance
                    if (phaseBefore == GamePhase.GUESSING && game.phase == GamePhase.ROUND_RESULT) {
                        scheduleRoundAdvance(game)
                    }

                    // Notify remaining players about the disconnect
                    if (stateChanged) {
                        game.broadcastToAllConnected(::renderGameState)
                    }
                }
            }
        }

        // SSE endpoint for chess updates
        sse("/chess/{gameId}/events") {
            val session = call.sessions.get<UserSession>() ?: return@sse
            val gameId = call.parameters["gameId"] ?: return@sse
            val game = ChessGameManager.getGame(gameId) ?: return@sse
            val player = game.players[session.id] ?: return@sse
            val lang = call.getLanguage()

            player.lang = lang
            while (player.channel.tryReceive().isSuccess) {
                // Discard old messages
            }

            val connectionId = player.startNewConnection()
            game.handlePlayerReconnect(session.id)

            var connectionAlive = true
            val pingJob =
                launch {
                    while (isActive && connectionAlive) {
                        delay(SSE_PING_INTERVAL_MS)
                        try {
                            send(ServerSentEvent("ping", event = "ping"))
                        } catch (_: Exception) {
                            connectionAlive = false
                            player.channel.trySend("__CONNECTION_CHECK__")
                            break
                        }
                    }
                }

            try {
                game.players.values
                    .filter { it.connected && it.sessionId != session.id }
                    .forEach { other ->
                        other.channel.trySend(renderChessState(game, other.sessionId, other.lang))
                    }

                send(ServerSentEvent(renderChessState(game, session.id, lang), event = "chess-update"))

                val refreshJob =
                    launch {
                        delay(100)
                        if (connectionId == player.currentConnectionId && connectionAlive) {
                            try {
                                send(ServerSentEvent(renderChessState(game, session.id, lang), event = "chess-update"))
                            } catch (_: Exception) {
                                // Connection might be closed, ignore
                            }
                        }
                    }

                for (message in player.channel) {
                    if (!connectionAlive) break
                    if (message == "__CONNECTION_CHECK__") continue
                    if (connectionId != player.currentConnectionId) break
                    send(ServerSentEvent(message, event = "chess-update"))
                }

                refreshJob.cancel()
            } catch (_: ClosedReceiveChannelException) {
                // Normal disconnect
            } catch (e: Exception) {
                logger.warn("Chess SSE error for {}: {}", player.name, e.message)
            } finally {
                pingJob.cancel()
                player.endConnection(connectionId)

                if (!player.connected) {
                    val changed = game.handlePlayerDisconnect(session.id)
                    if (changed) {
                        game.broadcastToAllConnected(::renderChessState)
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

        // Check for expired grace periods in chess
        post("/chess/{gameId}/check-disconnects") {
            val gameId = call.parameters["gameId"] ?: return@post
            val game = ChessGameManager.getGame(gameId) ?: return@post

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
                game.broadcastToAllConnected(::renderChessState)
            }

            call.respondText("OK")
        }

        post("/chess/{gameId}/check-time") {
            val session = call.sessions.get<UserSession>() ?: return@post
            val gameId = call.parameters["gameId"] ?: return@post
            val game = ChessGameManager.getGame(gameId) ?: return@post
            if (game.players[session.id] == null) {
                call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                return@post
            }
            game.applyTurnClockTick()
            val changed = game.checkTurnTimeout()
            if (changed) {
                game.broadcastToAllConnected(::renderChessState)
            }
            call.respondText("OK")
        }

        post("/chess/{gameId}/check-auto-restart") {
            val gameId = call.parameters["gameId"] ?: return@post
            val game = ChessGameManager.getGame(gameId) ?: return@post
            if (game.shouldAutoRestartNow()) {
                game.resetForNewGame()
                game.broadcastToAllConnected(::renderChessState)
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
                scheduleRoundAdvance(game)
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
                scheduleRoundAdvance(game)
            }

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

        // Make a chess move
        post("/chess/{gameId}/move") {
            val session = call.sessions.get<UserSession>() ?: return@post
            val gameId = call.parameters["gameId"] ?: return@post
            val game = ChessGameManager.getGame(gameId) ?: return@post
            val params = call.receiveParameters()
            val from = params["from"] ?: return@post
            val to = params["to"] ?: return@post

            if (!game.makeMove(session.id, from, to)) {
                val err = game.validateMoveError(session.id, from, to)
                if (err == MoveError.NOT_YOUR_TURN) {
                    call.respondText("Not your turn", status = HttpStatusCode.Conflict)
                } else {
                    call.respondText("Invalid move", status = HttpStatusCode.BadRequest)
                }
                return@post
            }

            game.broadcastToAllConnected(::renderChessState)
            call.respondText("OK")
        }

        // Restart chess game
        post("/chess/{gameId}/new-game") {
            val session =
                call.sessions.get<UserSession>() ?: run {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    return@post
                }
            val gameId = call.parameters["gameId"] ?: return@post
            val game = ChessGameManager.getGame(gameId) ?: return@post

            if (game.creatorSessionId != session.id) {
                call.respondText("Only the creator can start a new game", status = HttpStatusCode.Forbidden)
                return@post
            }

            game.resetForNewGame()
            game.broadcastToAllConnected(::renderChessState)

            call.respondText("OK")
        }

        // Get legal moves for selected chess piece
        get("/chess/{gameId}/legal-moves") {
            val session = call.sessions.get<UserSession>() ?: return@get
            val gameId = call.parameters["gameId"] ?: return@get
            val from = call.request.queryParameters["from"] ?: return@get
            val game = ChessGameManager.getGame(gameId) ?: return@get

            val moves = game.legalMovesFor(session.id, from)
            call.respondText(moves.joinToString(","))
        }

        get("/qr") {
            val target =
                call.request.queryParameters["target"] ?: run {
                    call.respondText("Missing target", status = HttpStatusCode.BadRequest)
                    return@get
                }

            if (!target.startsWith("/game/") && !target.startsWith("/chess/")) {
                call.respondText("Invalid target", status = HttpStatusCode.BadRequest)
                return@get
            }
            if (!target.endsWith("/join")) {
                call.respondText("Invalid target", status = HttpStatusCode.BadRequest)
                return@get
            }

            val absoluteTarget = "${call.request.origin.scheme}://${call.request.host()}$target"
            val png = QRCodeService.create(absoluteTarget)
            call.respondBytes(png, contentType = ContentType.Image.PNG)
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
