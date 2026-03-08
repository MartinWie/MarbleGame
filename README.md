# Marble Game

Marble Game is a realtime multiplayer marbles service built with Kotlin/Ktor and WebSockets.

## Features

- Realtime multiplayer marbles via WebSockets
- Fast reconnect and connection grace period handling
- Share links and QR join flow
- Mobile-first UI with server-rendered HTMX updates
- Automatic round progression and game cleanup

## Tech Stack

- **Backend**: Kotlin + Ktor 3.x
- **Realtime**: WebSockets
- **Frontend**: HTMX + vanilla JavaScript
- **Templating**: kotlinx.html (server-side)
- **Styling**: Custom CSS

## Quick Start

### Using Gradle

```bash
./gradlew run
open http://localhost:8080
```

### Development Scripts

```bash
./dev-start.sh   # Start dev server in background (PostHog disabled)
./dev-stop.sh    # Stop the dev server
./dev-reload.sh  # Restart the dev server

# View server logs
tail -f dev-server.log
```

## Testing

Run all tests:

```bash
./test.sh
./test.sh --full
```

Direct commands:

```bash
./gradlew test
npm run test:e2e
```

Production manual verification runbook:

- `docs/manual-prod-live-check.md`

## Realtime Health Probe

```bash
npm run health:realtime:local
npm run health:realtime:prod
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTHOG_ENABLED` | `true` | Set to `false` to disable PostHog analytics and cookie banner |
| `REALTIME_ALLOWED_ORIGINS` | same-origin | Optional comma-separated WS Origin allowlist |
| `REALTIME_ALLOW_NULL_ORIGIN` | `false` | Optional; only set `true` if `Origin: null` WS clients must be allowed |

## Project Structure

```
src/main/kotlin/
├── Application.kt
├── Game.kt
├── GameManager.kt
├── GameRenderer.kt
├── Player.kt
├── Routing.kt
├── RoutePages.kt
├── RouteMarbles.kt
├── RouteShared.kt
├── RealtimeConfig.kt
├── RealtimeMaintenanceService.kt
├── WebSocketSessionSupport.kt
├── QRCodeService.kt
├── RoutingConstants.kt
└── Translations.kt

src/main/resources/static/
├── game.js
├── realtime.js
├── ui-shared.js
├── style.css
└── assets/icons

e2e/
└── game.spec.ts
```

## Docker

```bash
docker build -t marble-game .
docker run -d -p 8080:8080 --name marble-game marble-game
```
