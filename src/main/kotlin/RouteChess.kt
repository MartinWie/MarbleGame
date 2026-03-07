package de.mw

import io.github.martinwie.htmx.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.html.*

internal const val WS_CHESS_UPDATE_PREFIX = "__CHESS_UPDATE__:"

internal fun Route.registerChessRoutes(isAllowedWebSocketOrigin: (ApplicationCall) -> Boolean) {
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

    // Direct join via URL for chess games
    get("/chess/{gameId}/join") {
        val session = call.sessions.get<UserSession>()
        val savedName = session?.playerName?.take(MAX_PLAYER_NAME_LENGTH) ?: ""

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
                game.broadcastToConnectedExcept(session.id, ::renderChessState)
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

    // Surrender current chess game (active players only)
    post("/chess/{gameId}/surrender") {
        val session =
            call.sessions.get<UserSession>() ?: run {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
        val gameId = call.parameters["gameId"] ?: return@post
        val game =
            ChessGameManager.getGame(gameId) ?: run {
                call.respondText("Game not found", status = HttpStatusCode.NotFound)
                return@post
            }

        if (!game.surrender(session.id)) {
            call.respondText("Cannot surrender", status = HttpStatusCode.BadRequest)
            return@post
        }

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
}
