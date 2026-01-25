# Marble Game

A real-time multiplayer marble game inspired by the Korean drama "Squid Game". Built with Kotlin/Ktor using Server-Sent Events (SSE) for instant updates.

## Game Rules

1. Each player starts with **10 marbles**
2. Players take turns being the "placer"
3. The placer hides 1 or more marbles in their hand
4. Other players guess: **EVEN** or **ODD**
5. **Correct guessers** split the placed marbles equally
6. **Wrong guessers** lose marbles to the placer
7. Players with **0 marbles** become spectators
8. **Last player standing wins!**

## Features

- Real-time multiplayer via SSE (Server-Sent Events)
- Mobile-first responsive design
- Shareable game links with unique codes
- Auto-reconnect on connection loss
- Player disconnect detection
- Session-based player names

## Tech Stack

- **Backend**: Kotlin + Ktor 3.x
- **Real-time**: SSE with 5-second keepalive pings
- **Frontend**: HTMX + vanilla JavaScript
- **Templating**: kotlinx.html (server-side)
- **Styling**: Custom CSS with dark theme

## Quick Start

```bash
# Run the server
./gradlew run

# Open in browser
open http://localhost:8080
```

## How to Play

1. Open the game in your browser
2. Enter your name and create a new game
3. Share the game link with friends
4. Wait for everyone to join, then start!
5. Take turns placing marbles and guessing

## Building

| Task | Description |
|------|-------------|
| `./gradlew run` | Run the development server |
| `./gradlew build` | Build everything |
| `./gradlew buildFatJar` | Build executable JAR with all dependencies |
| `./gradlew test` | Run tests |

## Project Structure

```
src/main/kotlin/
├── Application.kt    # Ktor setup, sessions, plugins
├── Game.kt           # Game class, phases, and round result logic
├── GameManager.kt    # Singleton managing all active games (with auto-cleanup)
├── GameRenderer.kt   # HTML rendering functions for game UI
├── Player.kt         # Player state, connection handling, grace period
├── Routing.kt        # HTTP endpoints, SSE, form handling
└── Templating.kt     # HTML templating configuration

src/main/resources/
├── application.yaml  # Server configuration (port, etc.)
├── logback.xml       # Logging configuration
└── static/
    ├── htmx.min.js   # HTMX library (served locally)
    ├── index.html    # SSE demo page (legacy)
    └── style.css     # Mobile-first responsive styles
```

### Code Organization

| File | Lines | Description |
|------|-------|-------------|
| `Player.kt` | ~150 | Player class with connection state, grace period logic |
| `Game.kt` | ~600 | Core game logic, phases, marble distribution |
| `GameManager.kt` | ~120 | Thread-safe game registry with TTL-based cleanup |
| `GameRenderer.kt` | ~500 | Server-side HTML rendering with kotlinx.html |
| `Routing.kt` | ~550 | HTTP routes, SSE endpoints, form handlers |
| `Application.kt` | ~40 | Application entry point and session config |

## Production Notes

### Security
- Session cookies use `httpOnly` and `SameSite=Lax` flags
- Player names are limited to 30 characters
- HTML output is XSS-protected via `escapeHtml()`
- HTMX is served locally (no CDN dependency)

### Scalability
- Games auto-cleanup after inactivity (1h for finished, 4h for abandoned)
- Each player has a dedicated SSE channel for broadcasting
- Connection grace period (30s) handles brief disconnects

## Network

The server runs on port `8080` by default. To play with others on the same network, find your local IP (e.g., `192.168.x.x`) and share `http://<your-ip>:8080`.
