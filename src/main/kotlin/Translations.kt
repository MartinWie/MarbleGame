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
                    "button.qr" to "QR",
                    "button.sound.on" to "Sound On",
                    "button.sound.off" to "Sound Off",
                    "button.copied" to "Copied!",
                    "share.text" to "Join my game!",
                    // Home page
                    "home.title" to "Games",
                    "home.createGame" to "Create Marbles",
                    "home.createGameHint" to "Play marbles.",
                    "home.createChess" to "Create Chess",
                    "home.createChessHint" to "2 players, others spectate.",
                    "home.chess.option.timed" to "Play with clock",
                    "home.chess.option.timed.hint" to "Each player has a total time bank.",
                    "home.chess.option.clockMinutes" to "Clock (minutes per player)",
                    "home.chess.option.streamer" to "Streamer mode",
                    "home.chess.option.streamer.hint" to "The creator remains in the game. Spectators rotate after each game.",
                    "home.chooseMode" to "Choose a Game",
                    "home.chooseModeHint" to "Select a game to continue.",
                    "home.pickGameFirst" to "Choose a game first.",
                    "home.mode.marbles" to "Marbles",
                    "home.mode.chess" to "Chess",
                    "home.yourName" to "Your Name:",
                    "home.namePlaceholder" to "Enter your name",
                    "button.create" to "Create Marbles",
                    "button.createChess" to "Create Chess",
                    "error.gameNotFound" to "Game not found. It may have ended or the urls is invalid.",
                    // Join page
                    "join.title" to "Join Game",
                    "chess.join.title" to "Join Chess Game",
                    "chess.join.spectatorHint" to "First 2 players play, all others join as spectators.",
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
                    "button.cancel" to "Cancel",
                    // Chess
                    "chess.title" to "Chess Game",
                    "chess.waiting.title" to "Waiting for Players",
                    "chess.waiting.players" to "Players ready: %d / 2",
                    "chess.waiting.hint" to "Share the link with one opponent. Additional joins become spectators.",
                    "chess.option.enabled" to "Options: %s",
                    "chess.option.enabled.timed" to "Clock %d min",
                    "chess.option.enabled.streamer" to "Streamer",
                    "chess.inProgress.title" to "Game in Progress",
                    "chess.turn" to "Turn: %s",
                    "chess.turn.yours" to "Your turn",
                    "chess.turn.wait" to "Waiting for %s",
                    "chess.turn.timer.white" to "White: %ds",
                    "chess.turn.timer.black" to "Black: %ds",
                    "chess.check" to "Check!",
                    "chess.waitTurn" to "Wait for your turn.",
                    "chess.spectator.hint" to "You are spectating this chess game.",
                    "chess.move.prompt" to "Enter your move:",
                    "chess.select.from" to "Select piece",
                    "chess.select.to" to "Select target",
                    "chess.move.invalid" to "That move is not allowed.",
                    "chess.move.notYourTurn" to "It is not your turn.",
                    "chess.move.network" to "Move could not be sent. Check your connection.",
                    "chess.hint.overlay" to "Tap piece, then target. Or drag.",
                    "chess.move.button" to "Move",
                    "chess.winner" to "%s wins the game!",
                    "chess.gameOver.checkmate" to "Checkmate.",
                    "chess.gameOver.stalemate" to "Draw by stalemate.",
                    "chess.gameOver.stalemateHint" to "No legal moves remain, but the king is not in check.",
                    "chess.gameOver.disconnect" to "Game ended because a player disconnected.",
                    "chess.gameOver.timeout" to "Game ended on time. One player ran out of time.",
                    "chess.gameOver.surrender" to "%s surrendered.",
                    "chess.autoRestart" to "Next match starts automatically in %ds",
                    "chess.color.white" to "White",
                    "chess.color.black" to "Black",
                    "chess.board.enemy" to "Enemy",
                    "chess.board.you" to "You",
                    "button.surrender" to "Surrender",
                    "chess.surrender.confirm" to "Are you sure you want to surrender?",
                    "chess.you.white" to "You play White",
                    "chess.you.black" to "You play Black",
                    "chess.you.spectator" to "You are spectating",
                    // Status bar
                    "status.watching" to "Watching - <strong>You'll join next round!</strong>",
                    "status.pending" to "Pending",
                    "status.yourMarbles" to "Your marbles: <strong>%d</strong>",
                    // Cookie consent
                    "cookie.message" to "We use cookies to analyze site usage and improve your experience.",
                    "cookie.accept" to "Accept",
                    "cookie.reject" to "Reject",
                    // Footer
                    "footer.newGame" to "New Game",
                    "footer.imprint" to "Imprint",
                    "footer.privacy" to "Privacy Policy",
                    "footer.support" to "Support",
                    // Imprint page
                    "imprint.headline" to "Information according to § 5 TMG",
                    "imprint.hobbyProject" to "This is a private, non-commercial hobby project.",
                    "imprint.contact" to "Contact",
                    "imprint.email" to "Email: info-7mw@googlegroups.com",
                    "imprint.liabilityContent" to "Liability for Content",
                    "imprint.liabilityContentText" to
                        "As a service provider, we are responsible for our own content on these pages in accordance with § 7 para.1 TMG. The contents have been created with the utmost care. However, no guarantee can be given for the accuracy, completeness and timeliness of the content.",
                    "imprint.liabilityLinks" to "Liability for Links",
                    "imprint.liabilityLinksText" to
                        "This website contains links to external third-party websites over whose content we have no influence. The respective provider is always responsible for the content of the linked pages.",
                    // Privacy page
                    "privacy.headline" to "Privacy Policy",
                    "privacy.controller" to "1. Data Controller",
                    "privacy.controllerText" to "Responsible for data processing on this website:",
                    "privacy.controllerEmail" to "Email: info-7mw@googlegroups.com",
                    "privacy.hobbyProject" to "This is a private, non-commercial hobby project.",
                    "privacy.dataCollected" to "2. Data Collected",
                    "privacy.dataCollectedText" to "This website processes the following data:",
                    "privacy.dataSession" to
                        "Session cookies: Technically necessary for game functionality. Deleted when browser is closed.",
                    "privacy.dataPlayer" to "Player name: Only stored temporarily in memory during gameplay, not persisted.",
                    "privacy.dataAnalytics" to "Usage statistics: Only with your explicit consent via PostHog (servers in the EU).",
                    "privacy.legalBasis" to "3. Legal Basis",
                    "privacy.legalBasisText" to "Processing is based on:",
                    "privacy.legalBasisNecessary" to "Art. 6(1)(f) GDPR (legitimate interest) for technically necessary cookies",
                    "privacy.legalBasisConsent" to "Art. 6(1)(a) GDPR (consent) for analytics cookies",
                    "privacy.retention" to "4. Retention Period",
                    "privacy.retentionText" to
                        "Session data is deleted when you close the browser. Player names are deleted when the game ends. Analytics data is stored according to PostHog policies.",
                    "privacy.rights" to "5. Your Rights",
                    "privacy.rightsText" to "You have the following rights:",
                    "privacy.rightsAccess" to "Access to your stored data (Art. 15 GDPR)",
                    "privacy.rightsRectification" to "Rectification of inaccurate data (Art. 16 GDPR)",
                    "privacy.rightsErasure" to "Erasure of your data (Art. 17 GDPR)",
                    "privacy.rightsRestriction" to "Restriction of processing (Art. 18 GDPR)",
                    "privacy.rightsWithdraw" to "Withdraw consent at any time (Art. 7 GDPR)",
                    "privacy.rightsComplaint" to "Lodge a complaint with a supervisory authority (Art. 77 GDPR)",
                    "privacy.cookies" to "6. Cookies",
                    "privacy.cookiesText" to
                        "We use technically necessary session cookies for game functionality. Analytics cookies (PostHog) are only set with your explicit consent. You can withdraw your consent at any time.",
                    "privacy.thirdParties" to "7. Third Parties",
                    "privacy.thirdPartiesText" to
                        "PostHog (Analytics): Data is processed on servers in the European Union. More information: posthog.com/privacy",
                ),
            "de" to
                mapOf(
                    // General
                    "game.title" to "Murmelspiel",
                    "button.share" to "Teilen",
                    "button.qr" to "QR",
                    "button.sound.on" to "Sound an",
                    "button.sound.off" to "Sound aus",
                    "button.copied" to "Kopiert!",
                    "share.text" to "Spiel mit mir!",
                    // Home page
                    "home.title" to "Spiele",
                    "home.createGame" to "Murmeln starten",
                    "home.createGameHint" to "Murmeln spielen.",
                    "home.createChess" to "Schach starten",
                    "home.createChessHint" to "2 spielen, der Rest schaut zu.",
                    "home.chess.option.timed" to "Mit Schachuhr spielen",
                    "home.chess.option.timed.hint" to "Jeder Spieler hat X Minuten.",
                    "home.chess.option.clockMinutes" to "Uhr (Minuten pro Spieler)",
                    "home.chess.option.streamer" to "Streamer-Modus",
                    "home.chess.option.streamer.hint" to "Der Creator bleibt im spiel. Jede Runde ein neuer Gegner aus den Zuschauern.",
                    "home.chooseMode" to "Spiel wählen",
                    "home.chooseModeHint" to "Spiel auswählen.",
                    "home.pickGameFirst" to "Spiel wählen.",
                    "home.mode.marbles" to "Murmeln",
                    "home.mode.chess" to "Schach",
                    "home.yourName" to "Dein Name:",
                    "home.namePlaceholder" to "Name eingeben",
                    "button.create" to "Murmeln starten",
                    "button.createChess" to "Schach starten",
                    "error.gameNotFound" to "Spiel nicht gefunden.",
                    // Join page
                    "join.title" to "Spiel beitreten",
                    "chess.join.title" to "Schachspiel beitreten",
                    "chess.join.spectatorHint" to "Die ersten 2 spielen, alle weiteren sind Zuschauer.",
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
                    "button.cancel" to "Abbrechen",
                    // Chess
                    "chess.title" to "Schach",
                    "chess.waiting.title" to "Warte auf Spieler",
                    "chess.waiting.players" to "Spielbereit: %d / 2",
                    "chess.waiting.hint" to "Teile den Link mit einem Gegner. Weitere beitreten als Zuschauer.",
                    "chess.option.enabled" to "Optionen: %s",
                    "chess.option.enabled.timed" to "Uhr %d Min",
                    "chess.option.enabled.streamer" to "Streamer",
                    "chess.inProgress.title" to "Spiel läuft",
                    "chess.turn" to "Am Zug: %s",
                    "chess.turn.yours" to "Du bist am Zug",
                    "chess.turn.wait" to "Warte auf %s",
                    "chess.turn.timer.white" to "Weiss: %ds",
                    "chess.turn.timer.black" to "Schwarz: %ds",
                    "chess.check" to "Schach!",
                    "chess.waitTurn" to "Warte auf deinen Zug.",
                    "chess.spectator.hint" to "Du schaust dieses Schachspiel nur zu.",
                    "chess.move.prompt" to "Zug eingeben:",
                    "chess.select.from" to "Figur wählen",
                    "chess.select.to" to "Zielfeld wählen",
                    "chess.move.invalid" to "Dieser Zug ist nicht erlaubt.",
                    "chess.move.notYourTurn" to "Du bist gerade nicht am Zug.",
                    "chess.move.network" to "Zug konnte nicht gesendet werden. Verbindung prufen.",
                    "chess.hint.overlay" to "Figur antippen, dann Zielfeld. Oder ziehen.",
                    "chess.move.button" to "Ziehen",
                    "chess.winner" to "%s gewinnt das Spiel!",
                    "chess.gameOver.checkmate" to "Schachmatt.",
                    "chess.gameOver.stalemate" to "Remis durch Patt.",
                    "chess.gameOver.stalemateHint" to "Keine legalen Zuge mehr, aber der Konig steht nicht im Schach.",
                    "chess.gameOver.disconnect" to "Spiel wurde wegen Trennung eines Spielers beendet.",
                    "chess.gameOver.timeout" to "Spiel wegen Zeitablauf beendet. Ein Spieler hat auf Zeit verloren.",
                    "chess.gameOver.surrender" to "%s hat aufgegeben.",
                    "chess.autoRestart" to "Naechstes Spiel startet automatisch in %ds",
                    "chess.color.white" to "Weiss",
                    "chess.color.black" to "Schwarz",
                    "chess.board.enemy" to "Gegner",
                    "chess.board.you" to "Du",
                    "button.surrender" to "Aufgeben",
                    "chess.surrender.confirm" to "Mochtest du wirklich aufgeben?",
                    "chess.you.white" to "Du spielst Weiss",
                    "chess.you.black" to "Du spielst Schwarz",
                    "chess.you.spectator" to "Du bist Zuschauer",
                    // Status bar
                    "status.watching" to "Zuschauer - <strong>Du spielst nächste Runde mit!</strong>",
                    "status.pending" to "Wartend",
                    "status.yourMarbles" to "Deine Murmeln: <strong>%d</strong>",
                    // Cookie consent
                    "cookie.message" to "Wir nutzen Cookies zur Analyse und Verbesserung der Website.",
                    "cookie.accept" to "Akzeptieren",
                    "cookie.reject" to "Ablehnen",
                    // Footer
                    "footer.newGame" to "Neues Spiel",
                    "footer.imprint" to "Impressum",
                    "footer.privacy" to "Datenschutz",
                    "footer.support" to "Unterstützen",
                    // Imprint page
                    "imprint.headline" to "Angaben gemäß § 5 TMG",
                    "imprint.hobbyProject" to "Dies ist ein privates, nicht-kommerzielles Hobbyprojekt.",
                    "imprint.contact" to "Kontakt",
                    "imprint.email" to "E-Mail: info-7mw@googlegroups.com",
                    "imprint.liabilityContent" to "Haftung für Inhalte",
                    "imprint.liabilityContentText" to
                        "Als Diensteanbieter sind wir gemäß § 7 Abs.1 TMG für eigene Inhalte auf diesen Seiten nach den allgemeinen Gesetzen verantwortlich. Die Inhalte wurden mit größter Sorgfalt erstellt. Für die Richtigkeit, Vollständigkeit und Aktualität der Inhalte kann jedoch keine Gewähr übernommen werden.",
                    "imprint.liabilityLinks" to "Haftung für Links",
                    "imprint.liabilityLinksText" to
                        "Diese Website enthält Links zu externen Websites Dritter, auf deren Inhalte wir keinen Einfluss haben. Für die Inhalte der verlinkten Seiten ist stets der jeweilige Anbieter verantwortlich.",
                    // Privacy page
                    "privacy.headline" to "Datenschutzerklärung",
                    "privacy.controller" to "1. Verantwortlicher",
                    "privacy.controllerText" to "Verantwortlich für die Datenverarbeitung auf dieser Website:",
                    "privacy.controllerEmail" to "E-Mail: info-7mw@googlegroups.com",
                    "privacy.hobbyProject" to "Dies ist ein privates, nicht-kommerzielles Hobbyprojekt.",
                    "privacy.dataCollected" to "2. Erhobene Daten",
                    "privacy.dataCollectedText" to "Diese Website verarbeitet folgende Daten:",
                    "privacy.dataSession" to
                        "Session-Cookies: Technisch notwendig für die Spielfunktion. Werden nach Schließen des Browsers gelöscht.",
                    "privacy.dataPlayer" to
                        "Spielername: Wird nur temporär während des Spiels im Arbeitsspeicher gespeichert, nicht dauerhaft.",
                    "privacy.dataAnalytics" to
                        "Nutzungsstatistiken: Nur mit Ihrer ausdrücklichen Zustimmung via PostHog (Server in der EU).",
                    "privacy.legalBasis" to "3. Rechtsgrundlage",
                    "privacy.legalBasisText" to "Die Verarbeitung erfolgt auf Basis von:",
                    "privacy.legalBasisNecessary" to "Art. 6 Abs. 1 lit. f DSGVO (berechtigtes Interesse) für technisch notwendige Cookies",
                    "privacy.legalBasisConsent" to "Art. 6 Abs. 1 lit. a DSGVO (Einwilligung) für Analyse-Cookies",
                    "privacy.retention" to "4. Speicherdauer",
                    "privacy.retentionText" to
                        "Session-Daten werden gelöscht, wenn Sie den Browser schließen. Spielnamen werden gelöscht, wenn das Spiel endet. Analyse-Daten werden gemäß PostHog-Richtlinien gespeichert.",
                    "privacy.rights" to "5. Ihre Rechte",
                    "privacy.rightsText" to "Sie haben folgende Rechte:",
                    "privacy.rightsAccess" to "Auskunft über Ihre gespeicherten Daten (Art. 15 DSGVO)",
                    "privacy.rightsRectification" to "Berichtigung unrichtiger Daten (Art. 16 DSGVO)",
                    "privacy.rightsErasure" to "Löschung Ihrer Daten (Art. 17 DSGVO)",
                    "privacy.rightsRestriction" to "Einschränkung der Verarbeitung (Art. 18 DSGVO)",
                    "privacy.rightsWithdraw" to "Widerruf Ihrer Einwilligung jederzeit (Art. 7 DSGVO)",
                    "privacy.rightsComplaint" to "Beschwerde bei einer Aufsichtsbehörde (Art. 77 DSGVO)",
                    "privacy.cookies" to "6. Cookies",
                    "privacy.cookiesText" to
                        "Wir verwenden technisch notwendige Session-Cookies für die Spielfunktion. Analyse-Cookies (PostHog) werden nur mit Ihrer ausdrücklichen Zustimmung gesetzt. Sie können Ihre Einwilligung jederzeit widerrufen.",
                    "privacy.thirdParties" to "7. Drittanbieter",
                    "privacy.thirdPartiesText" to
                        "PostHog (Analyse): Daten werden auf Servern in der Europäischen Union verarbeitet. Mehr Informationen: posthog.com/privacy",
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
