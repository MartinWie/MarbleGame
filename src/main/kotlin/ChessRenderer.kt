package de.mw

import io.github.martinwie.htmx.*
import kotlinx.html.*

fun HTML.renderChessPage(
    game: ChessGame,
    sessionId: String,
    lang: String,
) {
    basePage(
        title = "${"chess.title".t(lang)} - ${game.id}",
        lang = lang,
        includeHtmx = true,
        containerClasses = "container chess-container",
        extraBodyContent = {
            script(src = "/static/realtime.js") {}
            script(src = "/static/ui-shared.js") {}
            script(src = "/static/chess.js") {}
            script {
                unsafe { +"initChess('${game.id}');" }
            }
        },
    ) {
        div("header") {
            h1 { +"chess.title".t(lang) }
            div("header-actions") {
                button(classes = "btn btn-secondary header-action-btn header-action-btn--icon") {
                    id = "share-btn"
                    attributes["type"] = "button"
                    attributes["data-share-url"] = "/chess/${game.id}/join"
                    attributes["data-share-text"] = "button.share".t(lang)
                    attributes["data-copied-text"] = "button.copied".t(lang)
                    attributes["data-share-title"] = "chess.title".t(lang)
                    attributes["data-share-message"] = "share.text".t(lang)
                    attributes["aria-label"] = "button.share".t(lang)
                    attributes["title"] = "button.share".t(lang)
                    span("share-icon") {
                        unsafe {
                            +
                                """
                                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-share-fill" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
                                  <path d="M11 2.5a2.5 2.5 0 1 1 .603 1.628l-6.718 3.12a2.5 2.5 0 0 1 0 1.504l6.718 3.12a2.5 2.5 0 1 1-.488.876l-6.718-3.12a2.5 2.5 0 1 1 0-3.256l6.718-3.12A2.5 2.5 0 0 1 11 2.5"/>
                                </svg>
                                """.trimIndent()
                        }
                    }
                }
                button(classes = "btn btn-secondary header-action-btn header-action-btn--icon") {
                    id = "qr-btn"
                    attributes["type"] = "button"
                    attributes["aria-label"] = "button.qr".t(lang)
                    attributes["title"] = "button.qr".t(lang)
                    attributes["aria-controls"] = "qr-modal"
                    attributes["aria-expanded"] = "false"
                    span("qr-icon") {
                        unsafe {
                            +
                                """
                                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-qr-code" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
                                  <path d="M2 2h2v2H2z"/>
                                  <path d="M6 0v6H0V0zM5 1H1v4h4zM4 12H2v2h2z"/>
                                  <path d="M6 10v6H0v-6zm-5 1v4h4v-4zm11-9h2v2h-2z"/>
                                  <path d="M10 0v6h6V0zm5 1v4h-4V1zM8 1V0h1v2H8v2H7V1zm0 5V4h1v2zM6 8V7h1V6h1v2h1V7h5v1h-4v1H7V8zm0 0v1H2V8H1v1H0V7h3v1zm10 1h-1V7h1zm-1 0h-1v2h2v-1h-1zm-4 0h2v1h-1v1h-1zm2 3v-1h-1v1h-1v1H9v1h3v-2zm0 0h3v1h-2v1h-1zm-4-1v1h1v-2H7v1z"/>
                                  <path d="M7 12h1v3h4v1H7zm9 2v2h-3v-1h2v-1z"/>
                                </svg>
                                """.trimIndent()
                        }
                    }
                }
                button(classes = "btn btn-secondary header-action-btn header-action-btn--icon") {
                    id = "sound-btn"
                    attributes["type"] = "button"
                    attributes["aria-label"] = "button.sound.on".t(lang)
                    attributes["title"] = "button.sound.on".t(lang)
                    attributes["data-sound-on"] = "button.sound.on".t(lang)
                    attributes["data-sound-off"] = "button.sound.off".t(lang)
                    span("sound-icon") {
                        unsafe {
                            +
                                """
                                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-volume-up" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
                                  <path d="M11.536 14.01A8.47 8.47 0 0 0 14.026 8a8.47 8.47 0 0 0-2.49-6.01l-.708.707A7.48 7.48 0 0 1 13.025 8c0 2.071-.84 3.946-2.197 5.303z"/>
                                  <path d="M10.121 12.596A6.48 6.48 0 0 0 12.025 8a6.48 6.48 0 0 0-1.904-4.596l-.707.707A5.48 5.48 0 0 1 11.025 8a5.48 5.48 0 0 1-1.61 3.89z"/>
                                  <path d="M10.025 8a4.5 4.5 0 0 1-1.318 3.182L8 10.475A3.5 3.5 0 0 0 9.025 8c0-.966-.392-1.841-1.025-2.475l.707-.707A4.5 4.5 0 0 1 10.025 8M7 4a.5.5 0 0 0-.812-.39L3.825 5.5H1.5A.5.5 0 0 0 1 6v4a.5.5 0 0 0 .5.5h2.325l2.363 1.89A.5.5 0 0 0 7 12zM4.312 6.39 6 5.04v5.92L4.312 9.61A.5.5 0 0 0 4 9.5H2v-3h2a.5.5 0 0 0 .312-.11"/>
                                </svg>
                                """.trimIndent()
                        }
                    }
                }
            }
            dialog(classes = "qr-modal") {
                id = "qr-modal"
                div("qr-modal-box") {
                    img(classes = "qr-image") {
                        id = "qr-image"
                        alt = "QR code"
                        loading = ImgLoading.lazy
                    }
                }
            }
        }

        div {
            id = "chess-content"
            unsafe { +renderChessState(game, sessionId, lang) }
        }
    }
}

fun renderChessState(
    game: ChessGame,
    sessionId: String,
    lang: String = "en",
): String {
    val you = game.players[sessionId]
    val yourColor = game.colorFor(sessionId)
    val isCreator = game.creatorSessionId == sessionId

    return buildHTMLString {
        div("players-section") {
            h3 { +"players.title".t(lang) }
            div("players-list") {
                game.allPlayers.forEach { player ->
                    val disconnectedClass = if (!player.connected && player.sessionId != sessionId) " disconnected" else ""
                    div("player-card$disconnectedClass") {
                        val showConnectionState = !player.connected && player.sessionId != sessionId
                        val label =
                            when (game.colorFor(player.sessionId)) {
                                ChessColor.WHITE -> "chess.color.white".t(lang)
                                ChessColor.BLACK -> "chess.color.black".t(lang)
                                null -> "players.spectator".t(lang)
                            }
                        div("player-main") {
                            div("player-name") {
                                +player.name.escapeHtml()
                                if (player.sessionId == sessionId) {
                                    +" ${"players.you".t(lang)}"
                                }
                            }
                            div("player-status") { +label }
                        }
                        div("player-connection-state${if (showConnectionState) " active" else ""}") {
                            if (showConnectionState) {
                                val remaining = player.gracePeriodRemainingSeconds()
                                if (remaining > 0) {
                                    div("player-countdown") {
                                        attributes["data-seconds"] = remaining.toString()
                                        +"${"players.reconnecting".t(lang)} "
                                        span("countdown-timer") { +"${remaining}s" }
                                    }
                                } else {
                                    div("player-disconnected") { +"players.disconnected".t(lang) }
                                }
                            }
                        }
                    }
                }
            }
        }

        div("chess-status-strip") {
            when (game.phase) {
                ChessPhase.WAITING_FOR_PLAYERS -> {
                    div("chess-status-content waiting-status") {
                        val connected = game.connectedPlayers.count { game.colorFor(it.sessionId) != null }
                        p("turn-line turn-wait") { +"chess.waiting.title".t(lang) }
                        p("waiting-ready") { +"chess.waiting.players".t(lang, connected) }
                        p("waiting-share") { +"chess.waiting.hint".t(lang) }
                        if (game.timedModeEnabled || game.streamerModeEnabled) {
                            val enabled = mutableListOf<String>()
                            if (game.timedModeEnabled) {
                                enabled += "chess.option.enabled.timed".t(lang, game.whiteClockSecondsRemaining() / 60)
                            }
                            if (game.streamerModeEnabled) enabled += "chess.option.enabled.streamer".t(lang)
                            p("hint") { +"chess.option.enabled".t(lang, enabled.joinToString(" + ")) }
                        }
                    }
                }

                ChessPhase.IN_PROGRESS -> {
                    div("chess-status-content in-progress-status") {
                        val checkedKingSquare = game.checkedKingSquare()
                        val turnText = if (game.turn == ChessColor.WHITE) "chess.color.white".t(lang) else "chess.color.black".t(lang)
                        when {
                            yourColor == null -> p("turn-line") { +"chess.turn".t(lang, turnText) }
                            yourColor == game.turn -> p("turn-line turn-your") { +"chess.turn.yours".t(lang) }
                            else -> p("turn-line turn-wait") { +"chess.turn.wait".t(lang, turnText) }
                        }
                        p("check-alert") { +(if (checkedKingSquare != null) "chess.check".t(lang) else "") }

                        if (yourColor != null) {
                            renderMoveForm(game, lang)
                        } else {
                            p("hint") { +"chess.spectator.hint".t(lang) }
                        }
                    }
                }

                ChessPhase.GAME_OVER -> {
                    div("chess-status-content game-over-status") {
                        val winner = game.winner()
                        val loser =
                            game.players.entries
                                .firstOrNull { (sid, _) -> game.colorFor(sid) != null && sid != game.winnerSessionId }
                                ?.value
                        if (winner != null) {
                            p("winner-text") { +"chess.winner".t(lang, winner.name) }
                        } else if (game.endReason == "stalemate") {
                            p("winner-text") { +"chess.gameOver.stalemate".t(lang) }
                        }
                        val reasonText =
                            when (game.endReason) {
                                "disconnect" -> "chess.gameOver.disconnect".t(lang)
                                "checkmate" -> "chess.gameOver.checkmate".t(lang)
                                "stalemate" -> "chess.gameOver.stalemateHint".t(lang)
                                "timeout" -> "chess.gameOver.timeout".t(lang)
                                "surrender" -> "chess.gameOver.surrender".t(lang, loser?.name ?: "")
                                else -> null
                            }
                        if (reasonText != null) {
                            p("hint") { +reasonText }
                        }
                        if (!game.streamerModeEnabled) {
                            val sec = game.autoRestartSecondsRemaining()
                            if (sec > 0) {
                                p("hint auto-restart-line") {
                                    attributes["data-seconds"] = sec.toString()
                                    +"chess.autoRestart".t(lang, sec)
                                }
                            }
                        } else if (isCreator) {
                            button(classes = "btn btn-primary") {
                                hxPost("/chess/${game.id}/new-game")
                                hxSwap(HxSwapOption.NONE)
                                +"button.playAgain".t(lang)
                            }
                        }
                    }
                }
            }
        }

        when (game.phase) {
            ChessPhase.IN_PROGRESS -> {
                val checkedKingSquare = game.checkedKingSquare()
                div("chess-board-stage chess-board-stage-outside") {
                    renderBoardWithMeta(game, sessionId, yourColor, checkedKingSquare, lang)
                }
            }

            ChessPhase.GAME_OVER -> {
                val checkmatedKingSquare =
                    if (game.endReason == "checkmate") {
                        game.kingSquare(game.turn)
                    } else {
                        null
                    }
                div("chess-board-stage chess-board-stage-outside") {
                    renderBoardWithMeta(game, sessionId, yourColor, checkmatedKingSquare, lang)
                }
            }

            ChessPhase.WAITING_FOR_PLAYERS -> {
                // no board while waiting
            }
        }

        if (you != null && yourColor == null) {
            div("your-status") { span { +"chess.you.spectator".t(lang) } }
        }
    }
}

private fun FlowContent.renderBoardWithMeta(
    game: ChessGame,
    sessionId: String,
    yourColor: ChessColor?,
    markedKingSquare: String?,
    lang: String,
) {
    val perspective = yourColor ?: ChessColor.WHITE
    val topSessionId = if (perspective == ChessColor.WHITE) game.blackSessionId else game.whiteSessionId
    val bottomSessionId = if (perspective == ChessColor.WHITE) game.whiteSessionId else game.blackSessionId
    val topColor = if (perspective == ChessColor.WHITE) ChessColor.BLACK else ChessColor.WHITE
    val bottomColor = if (perspective == ChessColor.WHITE) ChessColor.WHITE else ChessColor.BLACK

    fun sideName(sid: String?): String = game.players[sid]?.name ?: "-"

    fun sideLabelPrefix(
        color: ChessColor,
        isBottom: Boolean,
    ): String =
        if (yourColor == null) {
            if (color == ChessColor.WHITE) "chess.color.white".t(lang) else "chess.color.black".t(lang)
        } else {
            if (isBottom) "chess.board.you".t(lang) else "chess.board.enemy".t(lang)
        }

    fun sideLabel(
        color: ChessColor,
        sid: String?,
        isBottom: Boolean,
    ): String = "${sideLabelPrefix(color, isBottom)} ${sideName(sid)}"

    val topClockSeconds =
        when {
            !game.timedModeEnabled -> 0
            topSessionId == game.whiteSessionId -> game.whiteClockSecondsRemaining()
            topSessionId == game.blackSessionId -> game.blackClockSecondsRemaining()
            else -> 0
        }
    val bottomClockSeconds =
        when {
            !game.timedModeEnabled -> 0
            bottomSessionId == game.whiteSessionId -> game.whiteClockSecondsRemaining()
            bottomSessionId == game.blackSessionId -> game.blackClockSecondsRemaining()
            else -> 0
        }

    val topClockSide =
        when {
            topSessionId == game.whiteSessionId -> "white"
            topSessionId == game.blackSessionId -> "black"
            else -> if (perspective == ChessColor.WHITE) "black" else "white"
        }
    val bottomClockSide =
        when {
            bottomSessionId == game.whiteSessionId -> "white"
            bottomSessionId == game.blackSessionId -> "black"
            else -> if (perspective == ChessColor.WHITE) "white" else "black"
        }

    div("chess-board-meta chess-board-meta-enemy") {
        span("board-side-name") { +sideLabel(topColor, topSessionId, isBottom = false) }
        if (game.timedModeEnabled) {
            span("board-side-clock clock-enemy") {
                attributes["data-seconds"] = topClockSeconds.toString()
                attributes["data-clock-side"] = topClockSide
                +formatClock(topClockSeconds)
            }
        }
    }

    renderBoard(game, perspective, yourColor, markedKingSquare, true)

    div("chess-board-meta chess-board-meta-own") {
        span("board-side-name") { +sideLabel(bottomColor, bottomSessionId ?: sessionId, isBottom = true) }
        if (yourColor != null && game.phase == ChessPhase.IN_PROGRESS) {
            button(classes = "btn btn-secondary header-action-btn header-action-btn--icon chess-surrender-btn") {
                id = "surrender-btn"
                attributes["type"] = "button"
                attributes["aria-label"] = "button.surrender".t(lang)
                attributes["title"] = "button.surrender".t(lang)
                span("surrender-icon") {
                    unsafe {
                        +
                            """
                            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-x-circle" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
                              <path d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14m0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16"/>
                              <path d="M4.646 4.646a.5.5 0 0 1 .708 0L8 7.293l2.646-2.647a.5.5 0 0 1 .708.708L8.707 8l2.647 2.646a.5.5 0 0 1-.708.708L8 8.707l-2.646 2.647a.5.5 0 0 1-.708-.708L7.293 8 4.646 5.354a.5.5 0 0 1 0-.708"/>
                            </svg>
                            """.trimIndent()
                    }
                }
            }
        }
        if (game.timedModeEnabled) {
            span("board-side-clock clock-own") {
                attributes["data-seconds"] = bottomClockSeconds.toString()
                attributes["data-clock-side"] = bottomClockSide
                +formatClock(bottomClockSeconds)
            }
        }
        if (yourColor != null && game.phase == ChessPhase.IN_PROGRESS) {
            dialog(classes = "qr-modal surrender-modal") {
                id = "surrender-modal"
                div("qr-modal-box surrender-modal-box") {
                    p("surrender-modal-title") { +"button.surrender".t(lang) }
                    p("surrender-modal-copy") { +"chess.surrender.confirm".t(lang) }
                    div("surrender-modal-actions") {
                        button(classes = "btn btn-secondary") {
                            id = "surrender-cancel-btn"
                            attributes["type"] = "button"
                            +"button.cancel".t(lang)
                        }
                        button(classes = "btn btn-primary") {
                            id = "surrender-confirm-btn"
                            attributes["type"] = "button"
                            hxPost("/chess/${game.id}/surrender")
                            hxSwap(HxSwapOption.NONE)
                            +"button.surrender".t(lang)
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.renderMoveForm(
    game: ChessGame,
    lang: String,
) {
    div("chess-move-form") {
        attributes["data-invalid-msg"] = "chess.move.invalid".t(lang)
        attributes["data-not-your-turn-msg"] = "chess.move.notYourTurn".t(lang)
        attributes["data-network-msg"] = "chess.move.network".t(lang)
        attributes["data-hint-text"] = "chess.hint.overlay".t(lang)
        div {
            style = "display:none"
            input(type = InputType.hidden) {
                name = "from"
                id = "chess-from"
            }
            input(type = InputType.hidden) {
                name = "to"
                id = "chess-to"
            }
        }
    }
}

private fun FlowContent.renderBoard(
    game: ChessGame,
    perspective: ChessColor,
    viewerColor: ChessColor?,
    markedKingSquare: String? = null,
    showLastMove: Boolean = true,
) {
    val files = "abcdefgh"
    val fileOrder = if (perspective == ChessColor.WHITE) files.toList() else files.reversed().toList()
    val rankOrder = if (perspective == ChessColor.WHITE) (8 downTo 1).toList() else (1..8).toList()
    val leftEdgeFile = fileOrder.firstOrNull()
    val bottomEdgeRank = rankOrder.lastOrNull()

    div("chess-board-shell") {
        id = "chess-board-shell"
        div("chess-board") {
            attributes["data-last-move-meta"] = game.lastMoveMeta ?: ""
            attributes["data-perspective"] = if (perspective == ChessColor.WHITE) "white" else "black"
            attributes["data-checked-king"] = markedKingSquare ?: ""
            attributes["data-show-last-move"] = if (showLastMove) "1" else "0"
            attributes["data-turn"] = if (game.turn == ChessColor.WHITE) "white" else "black"
            attributes["data-your-color"] =
                when (viewerColor) {
                    ChessColor.WHITE -> "white"
                    ChessColor.BLACK -> "black"
                    null -> "spectator"
                }
            attributes["data-en-passant"] = game.enPassantTarget() ?: ""
            attributes["data-castle-wk"] = if (game.isCastleAvailable(ChessColor.WHITE, true)) "1" else "0"
            attributes["data-castle-wq"] = if (game.isCastleAvailable(ChessColor.WHITE, false)) "1" else "0"
            attributes["data-castle-bk"] = if (game.isCastleAvailable(ChessColor.BLACK, true)) "1" else "0"
            attributes["data-castle-bq"] = if (game.isCastleAvailable(ChessColor.BLACK, false)) "1" else "0"
            attributes["data-timed-mode"] = if (game.timedModeEnabled) "1" else "0"
            attributes["data-white-seconds"] = game.whiteClockSecondsRemaining().toString()
            attributes["data-black-seconds"] = game.blackClockSecondsRemaining().toString()
            attributes["data-clock-started"] = if (game.clockStarted()) "1" else "0"
            for (rank in rankOrder) {
                for (file in fileOrder) {
                    val square = "$file$rank"
                    val piece = game.pieceAt(square)
                    // Keep standard chess parity: a1 is dark.
                    val colorClass = if ((file.code + rank) % 2 == 0) "dark" else "light"
                    val checkmateClass = if (markedKingSquare == square && game.endReason == "checkmate") " checkmated-king" else ""
                    div("chess-square $colorClass$checkmateClass") {
                        attributes["data-square"] = square
                        attributes["data-file"] = file.toString()
                        attributes["data-rank"] = rank.toString()
                        attributes["draggable"] = if (piece != null) "true" else "false"
                        attributes["data-piece"] = piece?.toString() ?: ""
                        if (file == leftEdgeFile) {
                            span("square-label rank-label") { +rank.toString() }
                        }
                        if (rank == bottomEdgeRank) {
                            span("square-label file-label") { +file.uppercaseChar().toString() }
                        }
                        val pieceClass =
                            when {
                                piece == null -> "chess-piece"
                                piece.isUpperCase() -> "chess-piece piece-white"
                                else -> "chess-piece piece-black"
                            }
                        span(pieceClass) { +(pieceToUnicode(piece) ?: "") }
                    }
                }
            }
        }
    }
}

private fun pieceToUnicode(piece: Char?): String? =
    when (piece) {
        'K' -> "♔\uFE0E"
        'Q' -> "♕\uFE0E"
        'R' -> "♖\uFE0E"
        'B' -> "♗\uFE0E"
        'N' -> "♘\uFE0E"
        'P' -> "♙\uFE0E"
        'k' -> "♚\uFE0E"
        'q' -> "♛\uFE0E"
        'r' -> "♜\uFE0E"
        'b' -> "♝\uFE0E"
        'n' -> "♞\uFE0E"
        'p' -> "♟\uFE0E"
        else -> null
    }

private fun formatClock(seconds: Int): String {
    val clamped = seconds.coerceAtLeast(0)
    val min = clamped / 60
    val sec = clamped % 60
    return String.format("%d:%02d", min, sec)
}
