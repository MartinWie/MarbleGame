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
import io.ktor.server.websocket.*
import io.ktor.sse.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.html.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("Routing")

/** Ping interval for SSE keepalive (5 seconds). */
private const val SSE_PING_INTERVAL_MS = 5_000L

private const val WS_PING_MESSAGE = "__PING__"
private const val WS_GAME_UPDATE_PREFIX = "__GAME_UPDATE__:"
private const val WS_CHESS_UPDATE_PREFIX = "__CHESS_UPDATE__:"
private const val INTERNAL_CONNECTION_CHECK = "__CONNECTION_CHECK__"

/** Maximum length for player names to prevent abuse. */
private const val MAX_PLAYER_NAME_LENGTH = 30

private suspend fun DefaultWebSocketServerSession.runRealtimeWebSocketSession(
    player: Player,
    updatePrefix: String,
    logLabel: String,
    notifyOthersOnConnect: () -> Unit,
    renderInitialState: () -> String,
    onDisconnect: () -> Unit,
) {
    while (player.channel.tryReceive().isSuccess) {
        // Discard old messages
    }

    val connectionId = player.startNewConnection()
    logger.debug("{} connected: {} connectionId={}", logLabel, player.name, connectionId)

    var connectionAlive = true
    val pingJob =
        launch {
            while (isActive && connectionAlive) {
                delay(SSE_PING_INTERVAL_MS)
                try {
                    send(Frame.Text(WS_PING_MESSAGE))
                } catch (_: Exception) {
                    connectionAlive = false
                    player.channel.trySend(INTERNAL_CONNECTION_CHECK)
                    break
                }
            }
        }

    val inboundJob =
        launch {
            try {
                for (incomingFrame in incoming) {
                    when (incomingFrame) {
                        is Frame.Close,
                        is Frame.Ping,
                        is Frame.Pong,
                        -> {
                            if (incomingFrame is Frame.Close) {
                                connectionAlive = false
                                player.channel.trySend(INTERNAL_CONNECTION_CHECK)
                                break
                            }
                        }

                        else -> {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unsupported client frame"))
                            connectionAlive = false
                            player.channel.trySend(INTERNAL_CONNECTION_CHECK)
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                connectionAlive = false
                player.channel.trySend(INTERNAL_CONNECTION_CHECK)
            }
        }

    try {
        notifyOthersOnConnect()
        send(Frame.Text(updatePrefix + renderInitialState()))

        val refreshJob =
            launch {
                delay(100)
                if (connectionId == player.currentConnectionId && connectionAlive) {
                    try {
                        send(Frame.Text(updatePrefix + renderInitialState()))
                    } catch (_: Exception) {
                        // Connection might be closed, ignore
                    }
                }
            }

        for (message in player.channel) {
            if (!connectionAlive) {
                break
            }
            if (message == INTERNAL_CONNECTION_CHECK) {
                continue
            }
            if (connectionId != player.currentConnectionId) {
                break
            }
            send(Frame.Text(updatePrefix + message))
        }

        refreshJob.cancel()
    } catch (e: Exception) {
        logger.warn("{} error for {}: {}", logLabel, player.name, e.message)
    } finally {
        pingJob.cancel()
        inboundJob.cancel()
        player.endConnection(connectionId)

        if (!player.connected) {
            onDisconnect()
        }
    }
}

private suspend fun CoroutineScope.runRealtimeSseSession(
    player: Player,
    logLabel: String,
    sendEvent: suspend (String) -> Unit,
    sendPing: suspend () -> Unit,
    notifyOthersOnConnect: () -> Unit,
    renderInitialState: () -> String,
    onDisconnect: () -> Unit,
) {
    while (player.channel.tryReceive().isSuccess) {
        // Discard old messages
    }

    val connectionId = player.startNewConnection()
    logger.debug("{} connected: {} connectionId={}", logLabel, player.name, connectionId)

    var connectionAlive = true
    val pingJob =
        launch {
            while (isActive && connectionAlive) {
                delay(SSE_PING_INTERVAL_MS)
                try {
                    sendPing()
                } catch (e: Exception) {
                    logger.debug("{} ping failed for {}: {}", logLabel, player.name, e.message)
                    connectionAlive = false
                    player.channel.trySend(INTERNAL_CONNECTION_CHECK)
                    break
                }
            }
        }

    try {
        notifyOthersOnConnect()
        sendEvent(renderInitialState())

        val refreshJob =
            launch {
                delay(100)
                if (connectionId == player.currentConnectionId && connectionAlive) {
                    try {
                        sendEvent(renderInitialState())
                    } catch (_: Exception) {
                        // Connection might be closed, ignore
                    }
                }
            }

        for (message in player.channel) {
            if (!connectionAlive) {
                break
            }
            if (message == INTERNAL_CONNECTION_CHECK) {
                continue
            }
            if (connectionId != player.currentConnectionId) {
                break
            }
            sendEvent(message)
        }

        refreshJob.cancel()
    } catch (_: ClosedReceiveChannelException) {
        // Channel closed, normal disconnect
    } catch (e: Exception) {
        logger.warn("{} error for {}: {}", logLabel, player.name, e.message)
    } finally {
        pingJob.cancel()
        player.endConnection(connectionId)

        if (!player.connected) {
            onDisconnect()
        }
    }
}

fun Application.configureRouting() {
    install(SSE)
    install(WebSockets) {
        pingPeriod = null
        timeout = 20.seconds
        maxFrameSize = 64 * 1024L
        masking = false
    }
    RealtimeMaintenanceService.start()
    monitor.subscribe(ApplicationStopped) {
        RealtimeMaintenanceService.stop()
    }
    logger.info(
        "Realtime transport mode: {} (wsEnabled={}, sseEnabled={})",
        RealtimeConfig.transportMode,
        RealtimeConfig.wsEnabled,
        RealtimeConfig.sseEnabled,
    )
    routing {
        fun isAllowedWebSocketOrigin(call: ApplicationCall): Boolean {
            val origin = call.request.headers[HttpHeaders.Origin] ?: return true
            val normalizedOrigin = origin.removeSuffix("/").lowercase()
            if (RealtimeConfig.allowedOrigins.isEmpty()) {
                val req = call.request.origin
                val scheme = req.scheme.lowercase()
                val host = req.serverHost.lowercase()
                val port = req.serverPort
                val defaultPort = if (scheme == "https") 443 else 80
                val withPort = "$scheme://$host:$port"
                val withoutPort = "$scheme://$host"
                return if (port == defaultPort) {
                    normalizedOrigin == withoutPort || normalizedOrigin == withPort
                } else {
                    normalizedOrigin == withPort
                }
            }
            return RealtimeConfig.allowedOrigins.contains(normalizedOrigin)
        }

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

        webSocket("/ws/game/{gameId}") {
            if (!isAllowedWebSocketOrigin(call)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid origin"))
                return@webSocket
            }
            val session =
                call.sessions.get<UserSession>() ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session required"))
                    return@webSocket
                }
            val gameId =
                call.parameters["gameId"] ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing game id"))
                    return@webSocket
                }
            val game =
                GameManager.getGame(gameId) ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Game not found"))
                    return@webSocket
                }
            val player =
                game.players[session.id] ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not in game"))
                    return@webSocket
                }
            val lang = call.getLanguage()
            game.handlePlayerReconnect(session.id)
            player.lang = lang

            runRealtimeWebSocketSession(
                player = player,
                updatePrefix = WS_GAME_UPDATE_PREFIX,
                logLabel = "WS",
                notifyOthersOnConnect = {
                    game.players.values
                        .filter { it.connected && it.sessionId != session.id }
                        .forEach { otherPlayer ->
                            otherPlayer.channel.trySend(renderGameState(game, otherPlayer.sessionId, otherPlayer.lang))
                        }
                },
                renderInitialState = { renderGameState(game, session.id, lang) },
                onDisconnect = {
                    logger.debug("WS disconnected: {}", player.name)
                    val stateChanged = game.handlePlayerDisconnect(session.id)
                    if (stateChanged) {
                        game.broadcastToAllConnected(::renderGameState)
                    }
                },
            )
        }

        webSocket("/ws/chess/{gameId}") {
            if (!isAllowedWebSocketOrigin(call)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid origin"))
                return@webSocket
            }
            val session =
                call.sessions.get<UserSession>() ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session required"))
                    return@webSocket
                }
            val gameId =
                call.parameters["gameId"] ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing game id"))
                    return@webSocket
                }
            val game =
                ChessGameManager.getGame(gameId) ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Game not found"))
                    return@webSocket
                }
            val player =
                game.players[session.id] ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not in game"))
                    return@webSocket
                }
            val lang = call.getLanguage()
            game.handlePlayerReconnect(session.id)
            player.lang = lang

            runRealtimeWebSocketSession(
                player = player,
                updatePrefix = WS_CHESS_UPDATE_PREFIX,
                logLabel = "Chess WS",
                notifyOthersOnConnect = {
                    game.players.values
                        .filter { it.connected && it.sessionId != session.id }
                        .forEach { other ->
                            other.channel.trySend(renderChessState(game, other.sessionId, other.lang))
                        }
                },
                renderInitialState = { renderChessState(game, session.id, lang) },
                onDisconnect = {
                    val changed = game.handlePlayerDisconnect(session.id)
                    if (changed) {
                        game.broadcastToAllConnected(::renderChessState)
                    }
                },
            )
        }

        // SSE endpoint for game updates with ping keepalive
        sse("/game/{gameId}/events") {
            val session = call.sessions.get<UserSession>() ?: return@sse
            val gameId = call.parameters["gameId"] ?: return@sse
            val game = GameManager.getGame(gameId) ?: return@sse
            val player = game.players[session.id] ?: return@sse
            val lang = call.getLanguage()

            player.lang = lang
            game.handlePlayerReconnect(session.id)

            runRealtimeSseSession(
                player = player,
                logLabel = "SSE",
                sendEvent = { html -> send(ServerSentEvent(html, event = "game-update")) },
                sendPing = { send(ServerSentEvent("ping", event = "ping")) },
                notifyOthersOnConnect = {
                    game.players.values
                        .filter { it.connected && it.sessionId != session.id }
                        .forEach { otherPlayer ->
                            otherPlayer.channel.trySend(renderGameState(game, otherPlayer.sessionId, otherPlayer.lang))
                        }
                },
                renderInitialState = { renderGameState(game, session.id, lang) },
                onDisconnect = {
                    logger.debug("SSE disconnected: {}", player.name)
                    val stateChanged = game.handlePlayerDisconnect(session.id)
                    if (stateChanged) {
                        game.broadcastToAllConnected(::renderGameState)
                    }
                },
            )
        }

        // SSE endpoint for chess updates
        sse("/chess/{gameId}/events") {
            val session = call.sessions.get<UserSession>() ?: return@sse
            val gameId = call.parameters["gameId"] ?: return@sse
            val game = ChessGameManager.getGame(gameId) ?: return@sse
            val player = game.players[session.id] ?: return@sse
            val lang = call.getLanguage()

            player.lang = lang
            game.handlePlayerReconnect(session.id)

            runRealtimeSseSession(
                player = player,
                logLabel = "Chess SSE",
                sendEvent = { html -> send(ServerSentEvent(html, event = "chess-update")) },
                sendPing = { send(ServerSentEvent("ping", event = "ping")) },
                notifyOthersOnConnect = {
                    game.players.values
                        .filter { it.connected && it.sessionId != session.id }
                        .forEach { other ->
                            other.channel.trySend(renderChessState(game, other.sessionId, other.lang))
                        }
                },
                renderInitialState = { renderChessState(game, session.id, lang) },
                onDisconnect = {
                    val changed = game.handlePlayerDisconnect(session.id)
                    if (changed) {
                        game.broadcastToAllConnected(::renderChessState)
                    }
                },
            )
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
