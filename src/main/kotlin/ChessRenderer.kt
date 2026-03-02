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
            script(src = "/static/chess.js") {}
            script {
                unsafe { +"initChess('${game.id}');" }
            }
        },
    ) {
        div("header") {
            h1 { +"chess.title".t(lang) }
            button(classes = "btn btn-secondary") {
                id = "share-btn"
                attributes["data-share-text"] = "button.share".t(lang)
                attributes["data-copied-text"] = "button.copied".t(lang)
                attributes["onclick"] =
                    """
                    var btn = this;
                    var url = window.location.origin + '/chess/${game.id}/join';
                    function showCopied() {
                        btn.textContent = btn.dataset.copiedText;
                        btn.classList.add('copied');
                        setTimeout(function() { btn.textContent = btn.dataset.shareText; btn.classList.remove('copied'); }, 2000);
                    }
                    function fallbackCopy() {
                        var ta = document.createElement('textarea');
                        ta.value = url;
                        ta.style.position = 'fixed';
                        ta.style.left = '-9999px';
                        document.body.appendChild(ta);
                        ta.focus();
                        ta.select();
                        ta.setSelectionRange(0, 99999);
                        try { document.execCommand('copy'); showCopied(); } catch(e) { prompt('Copy this link:', url); }
                        document.body.removeChild(ta);
                    }
                    if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
                        navigator.clipboard.writeText(url).then(showCopied).catch(fallbackCopy);
                    } else {
                        fallbackCopy();
                    }
                    """.trimIndent().replace("\n", " ")
                +"button.share".t(lang)
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
                        val label =
                            when (game.colorFor(player.sessionId)) {
                                ChessColor.WHITE -> "chess.color.white".t(lang)
                                ChessColor.BLACK -> "chess.color.black".t(lang)
                                null -> "players.spectator".t(lang)
                            }
                        div("player-name") {
                            +player.name.escapeHtml()
                            if (player.sessionId == sessionId) {
                                +" ${"players.you".t(lang)}"
                            }
                        }
                        div("player-status") { +label }
                        if (!player.connected && player.sessionId != sessionId) {
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

        div("game-area chess-area") {
            when (game.phase) {
                ChessPhase.WAITING_FOR_PLAYERS -> {
                    div("phase-info") {
                        h2 { +"chess.waiting.title".t(lang) }
                        val connected = game.connectedPlayers.count { game.colorFor(it.sessionId) != null }
                        p { +"chess.waiting.players".t(lang, connected) }
                        p("hint") { +"chess.waiting.hint".t(lang) }
                    }
                }

                ChessPhase.IN_PROGRESS -> {
                    div("phase-info") {
                        h2 { +"chess.inProgress.title".t(lang) }
                        val turnText = if (game.turn == ChessColor.WHITE) "chess.color.white".t(lang) else "chess.color.black".t(lang)
                        val checkedKingSquare = game.checkedKingSquare()
                        when {
                            yourColor == null -> p("turn-line") { +"chess.turn".t(lang, turnText) }
                            yourColor == game.turn -> p("turn-line turn-your") { +"chess.turn.yours".t(lang) }
                            else -> p("turn-line turn-wait") { +"chess.turn.wait".t(lang, turnText) }
                        }
                        if (checkedKingSquare != null) {
                            p("check-alert") { +"chess.check".t(lang) }
                        }

                        if (yourColor != null && yourColor == game.turn) {
                            renderMoveForm(game, lang)
                        } else if (yourColor == null) {
                            p("hint") { +"chess.spectator.hint".t(lang) }
                        }

                        renderBoard(game, yourColor ?: ChessColor.WHITE, checkedKingSquare)
                    }
                }

                ChessPhase.GAME_OVER -> {
                    div("phase-info game-over") {
                        h2 { +"phase.gameOver.title".t(lang) }
                        val winner = game.winner()
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
                                else -> null
                            }
                        if (reasonText != null) {
                            p("hint") { +reasonText }
                        }
                        val checkmatedKingSquare =
                            if (game.endReason == "checkmate") {
                                game.kingSquare(game.turn)
                            } else {
                                null
                            }
                        renderBoard(game, yourColor ?: ChessColor.WHITE, checkmatedKingSquare)
                        if (isCreator) {
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

        if (you != null && yourColor == null) {
            div("your-status") { span { +"chess.you.spectator".t(lang) } }
        }
    }
}

private fun FlowContent.renderMoveForm(
    game: ChessGame,
    lang: String,
) {
    div("chess-move-form") {
        attributes["data-invalid-msg"] = "chess.move.invalid".t(lang)
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

private fun DIV.renderBoard(
    game: ChessGame,
    perspective: ChessColor,
    markedKingSquare: String? = null,
) {
    val files = "abcdefgh"
    val fileOrder = if (perspective == ChessColor.WHITE) files.toList() else files.reversed().toList()
    val rankOrder = if (perspective == ChessColor.WHITE) (8 downTo 1).toList() else (1..8).toList()

    div("chess-board-shell") {
        div("chess-board") {
            attributes["data-last-move-meta"] = game.lastMoveMeta ?: ""
            attributes["data-perspective"] = if (perspective == ChessColor.WHITE) "white" else "black"
            attributes["data-checked-king"] = markedKingSquare ?: ""
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
        'K' -> "♔"
        'Q' -> "♕"
        'R' -> "♖"
        'B' -> "♗"
        'N' -> "♘"
        'P' -> "♙"
        'k' -> "♚"
        'q' -> "♛"
        'r' -> "♜"
        'b' -> "♝"
        'n' -> "♞"
        'p' -> "♟"
        else -> null
    }
