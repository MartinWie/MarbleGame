# Load Baseline - 2026-03-07

This file captures a reproducible baseline for realtime capacity so future optimizations can be compared apples-to-apples.

## Scope

- Test target: `marbles` mode only (not chess yet).
- Transport under test: WebSocket (`/ws/game/{gameId}`) for connected players.
- Join path under test: HTTP create/join endpoints with session cookies.

## Environment

- Date: 2026-03-07
- Host: local developer machine
- OS: Darwin 25.3.0 (arm64)
- CPU: Apple M1 Pro
- RAM: 16 GB
- Node.js: v24.6.0
- App run mode: `./gradlew run --no-daemon`
- PostHog: disabled (`POSTHOG_ENABLED=false`)
- JVM heap used during measured run: `-Xms512m -Xmx2048m`
- Repo commit during baseline capture: `f47f56852fe9e9ed53961231bedd6930ca8770f3`

## Method

Script: `scripts/loadtest-realtime.mjs`

Staged ramp approach:

1. Create a fresh game per stage.
2. Ramp N players over configured ramp window.
3. Each player joins game and opens WS with session cookie.
4. Hold all connections for fixed interval.
5. Evaluate stage pass/fail on:
   - open rate >= 98%
   - unexpected closes <= 2%
   - connect latency p95 <= 2000ms

Command used:

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game marbles \
  --start 100 \
  --max 400 \
  --ramp 8 \
  --hold 15 \
  --step +100 \
  --concurrency 100
```

## Results

- Stage 100: PASS
  - joinOk=100, wsOk=100, openRate=100.0%, p95=2ms
- Stage 200: PASS
  - joinOk=200, wsOk=200, openRate=100.0%, p95=182ms
- Stage 300: FAIL
  - joinOk=300, wsOk=300, openRate=100.0%, p95=3921ms

Estimated stable concurrent connected players on this setup: **~200**.

## Failure Signals Seen in Stress Runs

When pushing above this range (especially with more aggressive runs), server logs showed:

- `OutOfMemoryError: Java heap space`
- failures during broadcast render fanout (`GameRenderer.renderGameState` path)
- Netty event loop rejection cascades after heap exhaustion

## Notes for Future Comparison

To compare improvements fairly:

- keep same command and thresholds,
- keep same heap settings,
- run with fresh server process,
- test marbles first, then add equivalent chess baseline in a separate section/file.
