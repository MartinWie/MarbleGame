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

### Using Docker (Recommended)

```bash
# Build the image
docker build -t marble-game .

# Run the container
docker run -d -p 8080:8080 --name marble-game marble-game

# Open in browser
open http://localhost:8080

# Stop the container
docker stop marble-game
```

### Using Gradle

```bash
# Run the server
./gradlew run

# Open in browser
open http://localhost:8080
```

### Development Scripts

For local development, use the provided scripts that automatically disable analytics:

```bash
./dev-start.sh   # Start dev server in background (PostHog disabled)
./dev-stop.sh    # Stop the dev server
./dev-reload.sh  # Restart the dev server (after code changes)

# View server logs
tail -f dev-server.log
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
| `./dev-start.sh` | Start dev server with analytics disabled |
| `./dev-stop.sh` | Stop the dev server |
| `./dev-reload.sh` | Restart the dev server |
| `./gradlew run` | Run the server (with analytics enabled) |
| `./gradlew build` | Build everything |
| `./gradlew shadowJar` | Build executable JAR with all dependencies |
| `./gradlew test` | Run unit tests |
| `npm run test:e2e` | Run E2E tests (Playwright) |
| `docker build -t marble-game .` | Build Docker image (~221MB) |

## Testing

Run all tests with a single command:

```bash
./test.sh                  # Kotlin unit tests + fast E2E tests (~10s)
./test.sh --full           # Kotlin unit tests + ALL E2E tests (~2-3 min)
./test.sh --headed         # With browser visible
./test.sh --full --headed  # All tests with browser visible
```

### Unit Tests (Kotlin)

```bash
./gradlew test
```

### E2E Tests (Playwright)

E2E tests use Playwright and automatically start the server with `POSTHOG_ENABLED=false` to prevent analytics from being triggered during testing.

```bash
# Install dependencies (first time only)
npm install
npx playwright install chromium

# Run all E2E tests
npm run test:e2e

# Run fast tests only (~5 seconds) - recommended for CI
npm run test:e2e:fast

# Run slow tests only (~2 minutes) - gameplay + disconnect scenarios
npm run test:e2e:slow

# Run with browser visible
npm run test:e2e:headed
npm run test:e2e:fast:headed
npm run test:e2e:slow:headed

# Run with Playwright UI
npm run test:e2e:ui

# Debug mode
npm run test:e2e:debug
```

**Test categories:**
- **Fast tests (21)**: Homepage, game creation, joining, multiplayer flow, static pages
- **Slow tests (4)**: Host disconnect transfer, player disconnect during game, winner determination, host disconnect on game over (require gameplay/SSE timeouts)

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTHOG_ENABLED` | `true` | Set to `false` to disable PostHog analytics and cookie banner |

Example:
```bash
# Run with analytics disabled
POSTHOG_ENABLED=false ./gradlew run
```

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

e2e/                  # Playwright E2E tests
└── game.spec.ts      # Game flow tests
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

## Docker

The project uses a multi-stage Dockerfile with Google's **distroless** base image for a minimal, secure container:

- **Build stage**: `gradle:8.14-jdk21` - compiles and creates the shadow JAR
- **Runtime stage**: `gcr.io/distroless/java21-debian12:nonroot` - minimal JRE only

Benefits:
- ~221MB image size (vs ~400-500MB with full JDK)
- No shell or package manager (reduced attack surface)
- Runs as non-root user
