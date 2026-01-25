package de.mw

import kotlinx.html.*

/**
 * HTML rendering utilities for the Marble Game.
 *
 * Contains functions to render game state and pages as HTML strings
 * for server-side rendering with HTMX updates.
 */

/**
 * Escapes a string for safe HTML output, preventing XSS attacks.
 *
 * Converts special HTML characters to their entity equivalents:
 * - `&` → `&amp;`
 * - `<` → `&lt;`
 * - `>` → `&gt;`
 * - `"` → `&quot;`
 * - `'` → `&#x27;`
 *
 * @return The escaped string safe for HTML output.
 */
internal fun String.escapeHtml(): String =
    this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")

/**
 * Renders the main game page HTML structure.
 *
 * This function generates the complete HTML page for the game, including:
 * - Page head with meta tags, HTMX script, and styles
 * - Game header with title, game code, and share button
 * - Initial game content (via [renderGameState])
 * - Client-side JavaScript for SSE handling, reconnection, and UI interactions
 *
 * The rendered page uses Server-Sent Events (SSE) to receive real-time updates
 * from the server. The JavaScript handles:
 * - SSE connection and reconnection on errors
 * - Ping timeout detection (reloads if no ping for 45 seconds)
 * - Tab visibility changes (reconnects when tab becomes visible)
 * - Countdown timers for disconnected players
 * - Marble picker UI for placing marbles
 *
 * @param game The current game state to render.
 * @param sessionId The session ID of the player viewing the page.
 * @param lang The language code for translations (e.g., "en", "de").
 *
 * @see renderGameState
 */
fun HTML.renderGamePage(
    game: Game,
    sessionId: String,
    lang: String,
) {
    head {
        title { +"${"game.title".t(lang)} - ${game.id}" }
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no")
        
        // Favicon and app icons
        link(rel = "icon", href = "/static/favicon.ico", type = "image/x-icon")
        link(rel = "apple-touch-icon", href = "/static/apple-touch-icon.png")
        link(rel = "manifest", href = "/static/manifest.json")
        meta(name = "theme-color", content = "#4a90d9")
        meta(name = "apple-mobile-web-app-capable", content = "yes")
        meta(name = "apple-mobile-web-app-status-bar-style", content = "black-translucent")
        meta(name = "apple-mobile-web-app-title", content = "game.title".t(lang))
        
        script(src = "/static/htmx.min.js") {}
        link(rel = "stylesheet", href = "/static/style.css")
    }
    body {
        div("container") {
            div("header") {
                h1 { +"game.title".t(lang) }
                button {
                    id = "share-btn"
                    attributes["data-share-text"] = "button.share".t(lang)
                    attributes["data-copied-text"] = "button.copied".t(lang)
                    attributes["onclick"] =
                        """
                        var btn = this;
                        var url = window.location.origin + '/game/${game.id}/join';
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
                        function clipboardCopy() {
                            if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
                                navigator.clipboard.writeText(url).then(showCopied).catch(fallbackCopy);
                            } else {
                                fallbackCopy();
                            }
                        }
                        if (navigator.share && navigator.canShare && navigator.canShare({ url: url })) {
                            navigator.share({ title: '${"game.title".t(
                            lang,
                        )}', text: '${"share.text".t(lang)}', url: url }).catch(clipboardCopy);
                        } else if (navigator.share) {
                            navigator.share({ url: url }).catch(clipboardCopy);
                        } else {
                            clipboardCopy();
                        }
                        """.trimIndent().replace("\n", " ")
                    +"button.share".t(lang)
                }
            }

            div {
                id = "game-content"
                // Initial content
                unsafe { +renderGameState(game, sessionId, lang) }
            }
        }

        // Use standard EventSource for SSE with ping/reconnect handling
        script {
            unsafe {
                +"""
                (function() {
                    // Update URL to include game ID (for bookmarking/sharing)
                    if (window.location.pathname !== '/game/${game.id}') {
                        history.replaceState(null, '', '/game/${game.id}');
                    }
                    
                    var gameContent = document.getElementById('game-content');
                    var lastPingTime = Date.now();
                    var eventSource = null;
                    var pingCheckInterval = null;
                    var countdownInterval = null;
                    var previousMarbleCount = null;
                    var reconnectTimeout = null;
                    var isConnecting = false;
                    
                    // Create animation container if it doesn't exist
                    function getAnimationContainer() {
                        var container = document.querySelector('.marble-animation-container');
                        if (!container) {
                            container = document.createElement('div');
                            container.className = 'marble-animation-container';
                            document.body.appendChild(container);
                        }
                        return container;
                    }
                    
                    // Animate marbles flying out (when playing or losing)
                    function animateMarblesOut(count, startElement, isLost) {
                        var container = getAnimationContainer();
                        var rect = startElement.getBoundingClientRect();
                        var centerX = rect.left + rect.width / 2;
                        var centerY = rect.top + rect.height / 2;
                        
                        for (var i = 0; i < Math.min(count, 10); i++) {
                            (function(index) {
                                setTimeout(function() {
                                    var marble = document.createElement('div');
                                    marble.className = 'animated-marble fly-out' + (isLost ? ' lost' : '');
                                    // Spread marbles horizontally
                                    var offsetX = (index - Math.min(count, 10) / 2) * 20;
                                    marble.style.left = (centerX + offsetX - 16) + 'px';
                                    marble.style.top = (centerY - 16) + 'px';
                                    container.appendChild(marble);
                                    
                                    // Remove after animation
                                    setTimeout(function() {
                                        marble.remove();
                                    }, 1200);
                                }, index * 80);
                            })(i);
                        }
                    }
                    
                    // Animate marbles flying in (when receiving)
                    function animateMarblesIn(count, targetElement) {
                        var container = getAnimationContainer();
                        var rect = targetElement.getBoundingClientRect();
                        var centerX = rect.left + rect.width / 2;
                        var centerY = rect.top;
                        
                        for (var i = 0; i < Math.min(count, 10); i++) {
                            (function(index) {
                                setTimeout(function() {
                                    var marble = document.createElement('div');
                                    marble.className = 'animated-marble fly-in';
                                    // Spread marbles horizontally
                                    var offsetX = (index - Math.min(count, 10) / 2) * 25;
                                    marble.style.left = (centerX + offsetX - 16) + 'px';
                                    marble.style.top = (centerY - 16) + 'px';
                                    container.appendChild(marble);
                                    
                                    // Remove after animation
                                    setTimeout(function() {
                                        marble.remove();
                                    }, 1400);
                                }, index * 120);
                            })(i);
                        }
                    }
                    
                    // Get current marble count from status bar
                    function getCurrentMarbleCount() {
                        var statusBar = document.querySelector('.your-status strong');
                        if (statusBar) {
                            var count = parseInt(statusBar.textContent);
                            return isNaN(count) ? null : count;
                        }
                        return null;
                    }
                    
                    // Check for marble count changes and animate
                    function checkMarbleChanges() {
                        var currentCount = getCurrentMarbleCount();
                        if (currentCount !== null && previousMarbleCount !== null) {
                            var diff = currentCount - previousMarbleCount;
                            var statusBar = document.querySelector('.your-status');
                            var gameArea = document.querySelector('.game-area');
                            if (diff > 0 && statusBar) {
                                // Gained marbles - animate them flying in
                                animateMarblesIn(diff, statusBar);
                                statusBar.classList.add('marbles-changed', 'marbles-gained');
                                setTimeout(function() {
                                    statusBar.classList.remove('marbles-changed', 'marbles-gained');
                                }, 600);
                            } else if (diff < 0 && gameArea) {
                                // Lost marbles - animate them flying away from game area
                                animateMarblesOut(Math.abs(diff), gameArea, true);
                                if (statusBar) {
                                    statusBar.classList.add('marbles-changed', 'marbles-lost');
                                    setTimeout(function() {
                                        statusBar.classList.remove('marbles-changed', 'marbles-lost');
                                    }, 600);
                                }
                            }
                        }
                        previousMarbleCount = currentCount;
                    }
                    
                    // Countdown timer for disconnected players
                    function startCountdowns() {
                        if (countdownInterval) clearInterval(countdownInterval);
                        countdownInterval = setInterval(function() {
                            var countdowns = document.querySelectorAll('.player-countdown');
                            if (countdowns.length === 0) return;
                            countdowns.forEach(function(el) {
                                var seconds = parseInt(el.dataset.seconds) - 1;
                                el.dataset.seconds = seconds;
                                var timerSpan = el.querySelector('.countdown-timer');
                                if (timerSpan) timerSpan.textContent = seconds + 's';
                                if (seconds <= 0) {
                                    // Grace period expired, notify server
                                    fetch('/game/${game.id}/check-disconnects', { method: 'POST' });
                                }
                            });
                        }, 1000);
                    }
                    
                    // Setup marble picker click handlers
                    function setupMarblePicker() {
                        var picker = document.getElementById('marble-picker');
                        if (!picker) return;
                        var input = document.getElementById('marble-amount');
                        var countDisplay = document.getElementById('selected-count');
                        if (!input || !countDisplay) return;
                        
                        picker.onclick = function(e) {
                            var btn = e.target;
                            while (btn && !btn.classList.contains('marble-btn')) {
                                btn = btn.parentElement;
                            }
                            if (!btn) return;
                            
                            var value = btn.getAttribute('data-value');
                            input.value = value;
                            countDisplay.textContent = value;
                            
                            var allBtns = picker.getElementsByClassName('marble-btn');
                            for (var i = 0; i < allBtns.length; i++) {
                                if (allBtns[i].getAttribute('data-value') === value) {
                                    allBtns[i].classList.add('selected');
                                } else {
                                    allBtns[i].classList.remove('selected');
                                }
                            }
                        };
                        
                        // Setup form submit handler for fly-out animation
                        var form = document.getElementById('place-form');
                        if (form) {
                            form.addEventListener('htmx:beforeRequest', function(e) {
                                var amount = parseInt(input.value) || 1;
                                var selectedBtn = picker.querySelector('.marble-btn.selected');
                                if (selectedBtn) {
                                    animateMarblesOut(amount, selectedBtn);
                                }
                            });
                        }
                    }
                    
                    function connect() {
                        // Prevent multiple simultaneous connection attempts
                        if (isConnecting) {
                            console.log('Connection already in progress, skipping');
                            return;
                        }
                        isConnecting = true;
                        
                        // Clear any pending reconnect
                        if (reconnectTimeout) {
                            clearTimeout(reconnectTimeout);
                            reconnectTimeout = null;
                        }
                        
                        // Close existing connection if any
                        if (eventSource) {
                            eventSource.close();
                            eventSource = null;
                        }
                        
                        console.log('SSE connecting...');
                        eventSource = new EventSource('/game/${game.id}/events');
                        lastPingTime = Date.now();
                        
                        eventSource.addEventListener('open', function(e) {
                            console.log('SSE connection opened');
                            isConnecting = false;
                        });
                        
                        eventSource.addEventListener('game-update', function(e) {
                            console.log('SSE game-update received, updating DOM');
                            gameContent.innerHTML = e.data;
                            htmx.process(gameContent);
                            startCountdowns();
                            setupMarblePicker();
                            checkMarbleChanges();
                            var countdowns = document.querySelectorAll('.player-countdown');
                            console.log('Found ' + countdowns.length + ' countdown elements');
                        });
                        
                        eventSource.addEventListener('ping', function(e) {
                            lastPingTime = Date.now();
                        });
                        
                        eventSource.onerror = function(e) {
                            console.log('SSE connection error, readyState:', eventSource.readyState);
                            isConnecting = false;
                            
                            // Close the broken connection
                            if (eventSource) {
                                eventSource.close();
                                eventSource = null;
                            }
                            
                            // Reconnect after a short delay (not a full page reload)
                            if (!reconnectTimeout) {
                                reconnectTimeout = setTimeout(function() {
                                    reconnectTimeout = null;
                                    console.log('Attempting SSE reconnect...');
                                    connect();
                                }, 1000);
                            }
                        };
                    }
                    
                    // Check if we've received a ping recently (within 45 seconds)
                    pingCheckInterval = setInterval(function() {
                        var timeSinceLastPing = Date.now() - lastPingTime;
                        if (timeSinceLastPing > 45000) {
                            console.log('No ping received for 45s, reconnecting...');
                            connect();
                        }
                    }, 10000);
                    
                    // Handle tab visibility changes - refresh state when tab becomes visible
                    // This fixes sync issues caused by browser throttling background tabs
                    document.addEventListener('visibilitychange', function() {
                        if (document.visibilityState === 'visible') {
                            console.log('Tab became visible, refreshing state...');
                            connect();
                        }
                    });
                    
                    // Initial connection and countdowns
                    connect();
                    startCountdowns();
                    setupMarblePicker();
                    previousMarbleCount = getCurrentMarbleCount();
                    
                    // Cleanup on page unload
                    window.addEventListener('beforeunload', function() {
                        if (pingCheckInterval) clearInterval(pingCheckInterval);
                        if (countdownInterval) clearInterval(countdownInterval);
                        if (reconnectTimeout) clearTimeout(reconnectTimeout);
                        if (eventSource) eventSource.close();
                    });
                })();
                """
            }
        }
    }
}

fun renderGameState(
    game: Game,
    sessionId: String,
    lang: String = "en",
): String {
    val player = game.players[sessionId]
    val isCurrentPlayer = game.currentPlayer?.sessionId == sessionId
    val isCreator = game.creatorSessionId == sessionId

    return buildString {
        // Players list
        append("""<div class="players-section">""")
        append("""<h3>${"players.title".t(lang)}</h3>""")
        append("""<div class="players-list">""")
        game.allPlayers.forEach { p ->
            val statusClass =
                buildString {
                    when {
                        p.sessionId == sessionId -> append("you")
                        p.isSpectator -> append("spectator")
                        game.currentPlayer?.sessionId == p.sessionId -> append("current")
                    }
                    if (!p.connected && p.sessionId != sessionId) {
                        append(" disconnected")
                    }
                }
            val gracePeriodRemaining = p.gracePeriodRemainingSeconds()
            val markerText =
                when {
                    p.sessionId == sessionId -> " ${"players.you".t(lang)}"
                    !p.connected && gracePeriodRemaining > 0 -> "" // Will show countdown instead
                    !p.connected -> " ${"players.offline".t(lang)}"
                    game.currentPlayer?.sessionId == p.sessionId && game.phase != GamePhase.WAITING_FOR_PLAYERS -> " ${"players.current".t(
                        lang,
                    )}"
                    else -> ""
                }
            append("""<div class="player-card $statusClass">""")
            append("""<div class="player-name">${p.name.escapeHtml()}$markerText</div>""")
            append("""<div class="player-marbles">${p.marbles} ${"players.marbles".t(lang)}</div>""")
            if (p.isSpectator) {
                append("""<div class="player-status">${"players.spectator".t(lang)}</div>""")
            }
            // Show countdown timer for disconnected players within grace period
            if (!p.connected && gracePeriodRemaining > 0 && p.sessionId != sessionId) {
                append("""<div class="player-countdown" data-seconds="$gracePeriodRemaining" data-player="${p.sessionId}">""")
                append("""${"players.reconnecting".t(lang)} <span class="countdown-timer">${gracePeriodRemaining}s</span>""")
                append("""</div>""")
            } else if (!p.connected && p.sessionId != sessionId) {
                append("""<div class="player-disconnected">${"players.disconnected".t(lang)}</div>""")
            }
            if (game.phase == GamePhase.GUESSING && p.currentGuess != null && p.sessionId != game.currentPlayer?.sessionId) {
                append("""<div class="player-guessed">${"players.guessed".t(lang)}</div>""")
            }
            append("""</div>""")
        }
        // Show pending players (spectators joining next round)
        if (game.pendingPlayers.isNotEmpty()) {
            append("""<div class="pending-players">""")
            append("""<h4>${"players.joiningNextRound".t(lang)}</h4>""")
            game.pendingPlayers.forEach { p ->
                val isYou = p.sessionId == sessionId
                val statusClass = if (isYou) "you pending" else "pending"
                append("""<div class="player-card $statusClass">""")
                append("""<div class="player-name">${p.name.escapeHtml()}${if (isYou) " ${"players.you".t(lang)}" else ""}</div>""")
                append("""<div class="player-status">${"players.spectatorJoining".t(lang)}</div>""")
                append("""</div>""")
            }
            append("""</div>""")
        }
        append("""</div></div>""")

        // Game area
        append("""<div class="game-area">""")

        when (game.phase) {
            GamePhase.WAITING_FOR_PLAYERS -> {
                val connectedCount = game.players.values.count { it.connected }
                val totalPlayers = game.allPlayers.size
                append("""<div class="phase-info">""")
                append("""<h2>${"phase.waiting.title".t(lang)}</h2>""")
                append("""<p>${"phase.waiting.playerCount".t(lang, totalPlayers, connectedCount)}</p>""")
                if (isCreator && connectedCount >= 2) {
                    append(
                        """<button class="btn btn-primary" hx-post="/game/${game.id}/start" hx-swap="none">${"button.startGame".t(
                            lang,
                        )}</button>""",
                    )
                } else if (isCreator) {
                    append("""<p class="hint">${"phase.waiting.needPlayers".t(lang)}</p>""")
                } else {
                    val hostName = game.players[game.creatorSessionId]?.name?.escapeHtml() ?: "Host"
                    append("""<p class="hint">${"phase.waiting.waitingHost".t(lang, hostName)}</p>""")
                }
                append("""</div>""")
            }

            GamePhase.PLACING_MARBLES -> {
                if (isCurrentPlayer && player != null) {
                    append("""<div class="phase-info">""")
                    append("""<h2>${"phase.placing.yourTurn".t(lang)}</h2>""")
                    append("""<p>${"phase.placing.instruction".t(lang)}</p>""")
                    append("""<form hx-post="/game/${game.id}/place" hx-swap="none" class="place-form" id="place-form">""")
                    append("""<input type="hidden" name="amount" id="marble-amount" value="1" />""")
                    append("""<div class="marble-grid" id="marble-picker">""")
                    for (i in 1..player.marbles) {
                        val selectedClass = if (i == 1) " selected" else ""
                        append("""<button type="button" class="marble-btn$selectedClass" data-value="$i">$i</button>""")
                    }
                    append("""</div>""")
                    append("""<p class="marble-count">${"phase.placing.count".t(lang)}</p>""")
                    append("""<button type="submit" class="btn btn-primary">${"button.placeMarbles".t(lang)}</button>""")
                    append("""</form>""")
                    append("""</div>""")
                } else {
                    append("""<div class="phase-info">""")
                    append("""<h2>${"phase.placing.waiting".t(lang)}</h2>""")
                    append("""<p>${"phase.placing.deciding".t(lang, game.currentPlayer?.name?.escapeHtml() ?: "")}</p>""")
                    append("""<div class="waiting-animation">...</div>""")
                    append("""</div>""")
                }
            }

            GamePhase.GUESSING -> {
                if (isCurrentPlayer) {
                    append("""<div class="phase-info">""")
                    append("""<h2>${"phase.guessing.waitingGuesses".t(lang)}</h2>""")
                    append("""<p>${"phase.guessing.youPlaced".t(lang, game.currentMarblesPlaced)}</p>""")
                    val guessedCount = game.connectedActivePlayers.count { it.currentGuess != null && it.sessionId != sessionId }
                    val totalGuessers = game.connectedActivePlayers.count { it.sessionId != sessionId }
                    append("""<p>${"phase.guessing.guessCount".t(lang, guessedCount, totalGuessers)}</p>""")
                    append("""</div>""")
                } else if (player?.isSpectator == true) {
                    append("""<div class="phase-info">""")
                    append("""<h2>${"phase.guessing.spectating".t(lang)}</h2>""")
                    append("""<p>${"phase.guessing.outOfMarbles".t(lang)}</p>""")
                    append("""</div>""")
                } else if (player?.currentGuess != null) {
                    append("""<div class="phase-info">""")
                    append("""<h2>${"phase.guessing.waitingOthers".t(lang)}</h2>""")
                    append("""<p>${"phase.guessing.youGuessed".t(lang, player.currentGuess.toString())}</p>""")
                    append("""</div>""")
                } else {
                    append("""<div class="phase-info">""")
                    append("""<h2>${"phase.guessing.makeGuess".t(lang)}</h2>""")
                    append(
                        """<p>${"phase.guessing.prompt".t(lang, game.currentPlayer?.name?.escapeHtml() ?: "")}</p>""",
                    )
                    append("""<div class="guess-buttons">""")
                    append(
                        """<button class="btn btn-even" hx-post="/game/${game.id}/guess" hx-vals='{"guess":"EVEN"}' hx-swap="none">${"guess.even".t(
                            lang,
                        )}</button>""",
                    )
                    append(
                        """<button class="btn btn-odd" hx-post="/game/${game.id}/guess" hx-vals='{"guess":"ODD"}' hx-swap="none">${"guess.odd".t(
                            lang,
                        )}</button>""",
                    )
                    append("""</div></div>""")
                }
            }

            GamePhase.ROUND_RESULT -> {
                val result = game.lastRoundResult
                append("""<div class="phase-info result-phase">""")
                append("""<h2>${"phase.result.title".t(lang)}</h2>""")
                if (result != null) {
                    append("""<div class="result-card">""")
                    append(
                        """<p>${"phase.result.placed".t(lang, result.placerName.escapeHtml(), result.marblesPlaced)}</p>""",
                    )
                    append(
                        """<p class="result-answer">${if (result.wasEven) {
                            "phase.result.wasEven".t(
                                lang,
                            )
                        } else {
                            "phase.result.wasOdd".t(lang)
                        }}</p>""",
                    )
                    if (result.winners.isNotEmpty()) {
                        append(
                            """<p class="winners">${"phase.result.winners".t(
                                lang,
                                result.winners.joinToString(", ") { it.escapeHtml() },
                                result.marblesWonPerWinner,
                            )}</p>""",
                        )
                    }
                    if (result.losers.isNotEmpty()) {
                        append(
                            """<p class="losers">${"phase.result.losers".t(
                                lang,
                                result.losers.joinToString(", ") { it.escapeHtml() },
                            )}</p>""",
                        )
                    }
                    append("""</div>""")
                }
                append(
                    """<button class="btn btn-primary" hx-post="/game/${game.id}/next-round" hx-swap="none">${"button.continue".t(
                        lang,
                    )}</button>""",
                )
                append("""</div>""")
            }

            GamePhase.GAME_OVER -> {
                val winner = game.getWinner()
                append("""<div class="phase-info game-over">""")
                append("""<h2>${"phase.gameOver.title".t(lang)}</h2>""")
                if (winner != null) {
                    append("""<div class="winner-announcement">""")
                    append("""<p class="winner-text">${"phase.gameOver.winner".t(lang, winner.name.escapeHtml(), winner.marbles)}</p>""")
                    if (winner.sessionId == sessionId) {
                        append("""<p class="you-won">${"phase.gameOver.youWon".t(lang)}</p>""")
                    }
                    append("""</div>""")
                }
                append(
                    """<button class="btn btn-primary" hx-post="/game/${game.id}/new-game" hx-swap="none">${"button.playAgain".t(
                        lang,
                    )}</button>""",
                )
                append("""</div>""")
            }
        }

        append("""</div>""")

        // Your status
        val isPending = game.pendingPlayers.any { it.sessionId == sessionId }
        if (player != null) {
            append("""<div class="your-status">""")
            if (isPending) {
                append("""<span>${"status.watching".t(lang)}</span>""")
                append("""<span class="pending-badge">${"status.pending".t(lang)}</span>""")
            } else {
                append("""<span>${"status.yourMarbles".t(lang, player.marbles)}</span>""")
                if (player.isSpectator) {
                    append("""<span class="spectator-badge">${"players.spectator".t(lang)}</span>""")
                }
            }
            append("""</div>""")
        }
    }
}
