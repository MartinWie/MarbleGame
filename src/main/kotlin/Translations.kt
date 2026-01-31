package de.mw

import io.ktor.server.application.*
import io.ktor.server.request.*

/**
 * Internationalization (i18n) support for the Marble Game.
 *
 * Provides translations for English (default) and German based on browser language settings.
 */
object Translations {
    private val strings =
        mapOf(
            "en" to
                mapOf(
                    // General
                    "game.title" to "Marble Game",
                    "button.share" to "Share",
                    "button.copied" to "Copied!",
                    "share.text" to "Join my Marble Game!",
                    // Home page
                    "home.createGame" to "Create New Game",
                    "home.yourName" to "Your Name:",
                    "home.namePlaceholder" to "Enter your name",
                    "button.create" to "Create Game",
                    "error.gameNotFound" to "Game not found. It may have ended or the urls is invalid.",
                    // Join page
                    "join.title" to "Join Marble Game",
                    "join.spectatorHint" to "Game in progress - you'll join as a spectator and play in the next round!",
                    "button.join" to "Join Game",
                    "button.joinSpectator" to "Join as Spectator",
                    "button.goBack" to "Go back",
                    // Game - Players section
                    "players.title" to "Players",
                    "players.you" to "(You)",
                    "players.current" to "(Current)",
                    "players.offline" to "(Offline)",
                    "players.marbles" to "marbles",
                    "players.spectator" to "Spectator",
                    "players.guessed" to "Guessed!",
                    "players.reconnecting" to "Reconnecting...",
                    "players.disconnected" to "Disconnected",
                    "players.joiningNextRound" to "Joining Next Round",
                    "players.spectatorJoining" to "Spectator - Joining next round",
                    // Waiting for players phase
                    "phase.waiting.title" to "Waiting for Players",
                    "phase.waiting.playerCount" to "Players: %d (%d connected)",
                    "button.startGame" to "Start Game",
                    "phase.waiting.needPlayers" to "Need at least 2 connected players to start",
                    "phase.waiting.waitingHost" to "Waiting for <strong>%s</strong> to start the game...",
                    // Placing marbles phase
                    "phase.placing.yourTurn" to "Your Turn",
                    "phase.placing.instruction" to "How many marbles do you play?",
                    "phase.placing.count" to "<span id=\"selected-count\">1</span> marble(s)",
                    "button.placeMarbles" to "Play",
                    "phase.placing.waiting" to "Waiting...",
                    "phase.placing.deciding" to "<strong>%s</strong> is playing...",
                    // Guessing phase
                    "phase.guessing.waitingGuesses" to "Waiting for Guesses",
                    "phase.guessing.youPlaced" to "You played <strong>%d</strong> marble(s). Waiting for guesses...",
                    "phase.guessing.guessCount" to "Guesses: %d / %d",
                    "phase.guessing.spectating" to "Spectating",
                    "phase.guessing.outOfMarbles" to "You're out of marbles! Watch and wait for the next round.",
                    "phase.guessing.waitingOthers" to "Waiting for Others",
                    "phase.guessing.youGuessed" to "You guessed <strong>%s</strong>. Waiting for others...",
                    "phase.guessing.makeGuess" to "Your Guess!",
                    "phase.guessing.prompt" to "<strong>%s</strong> played marbles. Even or odd?",
                    "guess.even" to "EVEN",
                    "guess.odd" to "ODD",
                    // Round result phase
                    "phase.result.title" to "Result",
                    "phase.result.placed" to "<strong>%s</strong> played <strong>%d</strong> marble(s)",
                    "phase.result.wasEven" to "It was <strong>EVEN</strong>!",
                    "phase.result.wasOdd" to "It was <strong>ODD</strong>!",
                    "phase.result.winners" to "Winners: %s (+%d each)",
                    "phase.result.losers" to "Lost their bet: %s",
                    "phase.result.youWon" to "You won! +%d marbles",
                    "phase.result.youLost" to "You lost! -%d marbles",
                    "phase.result.youGained" to "You gained +%d marbles",
                    "phase.result.youLostMarbles" to "You lost -%d marbles",
                    "phase.result.youBrokeEven" to "You broke even",
                    "phase.result.nextRoundIn" to "Next round in",
                    "button.continue" to "Continue",
                    // Game over phase
                    "phase.gameOver.title" to "Game Over!",
                    "phase.gameOver.winner" to "%s wins with %d marbles!",
                    "phase.gameOver.youWon" to "Congratulations! You won!",
                    "phase.gameOver.youLost" to "Better luck next time!",
                    "phase.gameOver.waitingForHost" to "Waiting for %s to start a new game...",
                    "button.playAgain" to "Play Again",
                    // Status bar
                    "status.watching" to "Watching - <strong>You'll join next round!</strong>",
                    "status.pending" to "Pending",
                    "status.yourMarbles" to "Your marbles: <strong>%d</strong>",
                    // Cookie consent
                    "cookie.message" to "We use cookies to analyze site usage and improve your experience.",
                    "cookie.accept" to "Accept",
                    "cookie.reject" to "Reject",
                ),
            "de" to
                mapOf(
                    // General
                    "game.title" to "Murmelspiel",
                    "button.share" to "Teilen",
                    "button.copied" to "Kopiert!",
                    "share.text" to "Spiel mit mir Murmeln!",
                    // Home page
                    "home.createGame" to "Neues Spiel",
                    "home.yourName" to "Dein Name:",
                    "home.namePlaceholder" to "Name eingeben",
                    "button.create" to "Spiel erstellen",
                    "error.gameNotFound" to "Spiel nicht gefunden.",
                    // Join page
                    "join.title" to "Spiel beitreten",
                    "join.spectatorHint" to "Spiel läuft - du spielst nächste Runde mit!",
                    "button.join" to "Beitreten",
                    "button.joinSpectator" to "Als Zuschauer beitreten",
                    "button.goBack" to "Zurück",
                    // Game - Players section
                    "players.title" to "Spieler",
                    "players.you" to "(Du)",
                    "players.current" to "(Am Zug)",
                    "players.offline" to "(Offline)",
                    "players.marbles" to "Murmeln",
                    "players.spectator" to "Zuschauer",
                    "players.guessed" to "Geraten!",
                    "players.reconnecting" to "Verbindet...",
                    "players.disconnected" to "Getrennt",
                    "players.joiningNextRound" to "Nächste Runde dabei",
                    "players.spectatorJoining" to "Zuschauer - Spielt nächste Runde mit",
                    // Waiting for players phase
                    "phase.waiting.title" to "Warte auf Spieler",
                    "phase.waiting.playerCount" to "Spieler: %d (%d verbunden)",
                    "button.startGame" to "Spiel starten",
                    "phase.waiting.needPlayers" to "Mindestens 2 verbundene Spieler benötigt",
                    "phase.waiting.waitingHost" to "Warte auf <strong>%s</strong>...",
                    // Placing marbles phase
                    "phase.placing.yourTurn" to "Du bist dran",
                    "phase.placing.instruction" to "Wie viele Murmeln spielst du?",
                    "phase.placing.count" to "<span id=\"selected-count\">1</span> Murmel(n)",
                    "button.placeMarbles" to "Spielen",
                    "phase.placing.waiting" to "Warten...",
                    "phase.placing.deciding" to "<strong>%s</strong> spielt...",
                    // Guessing phase
                    "phase.guessing.waitingGuesses" to "Warte auf Antworten",
                    "phase.guessing.youPlaced" to "Du hast <strong>%d</strong> Murmel(n) gespielt. Warte auf die anderen...",
                    "phase.guessing.guessCount" to "Antworten: %d / %d",
                    "phase.guessing.spectating" to "Zuschauen",
                    "phase.guessing.outOfMarbles" to "Keine Murmeln mehr! Warte auf die nächste Runde.",
                    "phase.guessing.waitingOthers" to "Warte auf andere",
                    "phase.guessing.youGuessed" to "Du hast <strong>%s</strong> getippt. Warte auf die anderen...",
                    "phase.guessing.makeGuess" to "Dein Tipp!",
                    "phase.guessing.prompt" to "<strong>%s</strong> hat gespielt. Gerade oder ungerade?",
                    "guess.even" to "GERADE",
                    "guess.odd" to "UNGERADE",
                    // Round result phase
                    "phase.result.title" to "Ergebnis",
                    "phase.result.placed" to "<strong>%s</strong> hatte <strong>%d</strong> Murmel(n)",
                    "phase.result.wasEven" to "Es war <strong>GERADE</strong>!",
                    "phase.result.wasOdd" to "Es war <strong>UNGERADE</strong>!",
                    "phase.result.winners" to "Gewinner: %s (+%d pro Person)",
                    "phase.result.losers" to "Verloren: %s",
                    "phase.result.youWon" to "Gewonnen! +%d Murmeln",
                    "phase.result.youLost" to "Verloren! -%d Murmeln",
                    "phase.result.youGained" to "Du hast +%d Murmeln gewonnen",
                    "phase.result.youLostMarbles" to "Du hast -%d Murmeln verloren",
                    "phase.result.youBrokeEven" to "Unentschieden",
                    "phase.result.nextRoundIn" to "Nächste Runde in",
                    "button.continue" to "Weiter",
                    // Game over phase
                    "phase.gameOver.title" to "Spiel vorbei!",
                    "phase.gameOver.winner" to "%s gewinnt mit %d Murmeln!",
                    "phase.gameOver.youWon" to "Du hast gewonnen!",
                    "phase.gameOver.youLost" to "Vielleicht beim nächsten Mal!",
                    "phase.gameOver.waitingForHost" to "Warte auf %s für ein neues Spiel...",
                    "button.playAgain" to "Nochmal spielen",
                    // Status bar
                    "status.watching" to "Zuschauer - <strong>Du spielst nächste Runde mit!</strong>",
                    "status.pending" to "Wartend",
                    "status.yourMarbles" to "Deine Murmeln: <strong>%d</strong>",
                    // Cookie consent
                    "cookie.message" to "Wir nutzen Cookies zur Analyse und Verbesserung der Website.",
                    "cookie.accept" to "Akzeptieren",
                    "cookie.reject" to "Ablehnen",
                ),
        )

    /**
     * Gets a translated string for the given language and key.
     *
     * @param lang The language code (e.g., "en", "de")
     * @param key The translation key
     * @param args Optional format arguments for string interpolation
     * @return The translated string, or English fallback, or the key itself if not found
     */
    fun get(
        lang: String,
        key: String,
        vararg args: Any,
    ): String {
        val template =
            strings[lang]?.get(key)
                ?: strings["en"]?.get(key)
                ?: return key
        return if (args.isEmpty()) template else template.format(*args)
    }
}

/**
 * Gets the preferred language from the Accept-Language header.
 *
 * Parses the Accept-Language header and returns the primary language code.
 * Falls back to "en" if the header is missing or unparseable.
 *
 * Example: "de-DE,de;q=0.9,en;q=0.8" → "de"
 *
 * @return The two-letter language code (e.g., "en", "de")
 */
fun ApplicationCall.getLanguage(): String {
    val acceptLanguage = request.headers["Accept-Language"] ?: return "en"
    return acceptLanguage
        .split(",")
        .firstOrNull()
        ?.split("-")
        ?.firstOrNull()
        ?.split(";")
        ?.firstOrNull()
        ?.lowercase()
        ?.takeIf { it.length == 2 }
        ?: "en"
}

/**
 * Shorthand extension to get a translated string.
 */
fun String.t(
    lang: String,
    vararg args: Any,
): String = Translations.get(lang, this, *args)

/**
 * Outputs a translated string as raw HTML into the current element.
 *
 * Use this for translation strings that contain HTML markup (e.g., `<strong>`, `<span>`).
 * User-provided arguments MUST be escaped with [escapeHtml] before passing to this function
 * to prevent XSS attacks.
 *
 * Example:
 * ```kotlin
 * p { tr("phase.result.placed", lang, playerName.escapeHtml(), marbleCount) }
 * ```
 *
 * @param key The translation key
 * @param lang The language code (e.g., "en", "de")
 * @param args Optional format arguments (must be pre-escaped if user input!)
 */
fun kotlinx.html.FlowOrPhrasingContent.tr(
    key: String,
    lang: String,
    vararg args: Any,
) {
    consumer.onTagContentUnsafe { +key.t(lang, *args) }
}
