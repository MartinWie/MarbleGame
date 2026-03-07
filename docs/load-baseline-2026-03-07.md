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

## Re-run After Harness Update (same day)

After improving the harness with bounded load-generator concurrency (`--concurrency 100`) and per-stage fresh game creation:

### Marbles

Command:

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

Observed:

- 100 PASS (p95 4ms)
- 200 PASS (p95 1ms)
- 300 PASS (p95 1582ms)
- 400 could not start stage cleanly (`fetch failed` while creating next game; server responsiveness degraded)

Interpreted stable range for marbles on this setup: **~300**, with instability beginning around the next stage.

### Chess

Command:

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game chess \
  --start 100 \
  --max 400 \
  --ramp 8 \
  --hold 15 \
  --step +100 \
  --concurrency 100
```

Observed:

- 100 PASS (p95 2ms)
- 200 PASS (p95 1ms)
- 300 PASS (p95 3ms)
- stage transition to 400 showed server unresponsiveness (`fetch failed` creating next game)

Additional fine run:

- 225 PASS (p95 2ms)
- next stage creation (250) failed due to degraded responsiveness

Interpreted stable range for chess on this setup: **~225-300**, with instability around the next stage.

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

## Additional Distribution Profiles (2026-03-07)

These runs cover the second realistic shape: many small games, and mixed hotspot + many-small.

### Marbles many-small

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game marbles \
  --mode many-small \
  --games 100 \
  --players-per-game 4 \
  --ramp 8 \
  --hold 15 \
  --concurrency 120
```

- PASS at total 400 players
- p95 1ms

### Marbles mixed (hotspot + many-small)

Stress candidate:

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game marbles \
  --mode mixed \
  --hotspot-players 300 \
  --games 100 \
  --players-per-game 4 \
  --ramp 8 \
  --hold 15 \
  --concurrency 120 \
  --max-total-players 1500
```

- FAIL at total 700 players (p95 2579ms)

Reduced mixed profile:

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game marbles \
  --mode mixed \
  --hotspot-players 250 \
  --games 75 \
  --players-per-game 4 \
  --ramp 8 \
  --hold 15 \
  --concurrency 120 \
  --max-total-players 1500
```

- PASS at total 550 players (p95 1257ms)

### Chess many-small

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game chess \
  --mode many-small \
  --games 100 \
  --players-per-game 4 \
  --ramp 8 \
  --hold 15 \
  --concurrency 120
```

- PASS at total 400 players
- p95 1ms

### Chess mixed (hotspot + many-small)

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game chess \
  --mode mixed \
  --hotspot-players 300 \
  --games 100 \
  --players-per-game 4 \
  --ramp 8 \
  --hold 15 \
  --concurrency 120 \
  --max-total-players 1500
```

- PASS at total 700 players
- p95 21ms

## WS-only Re-run (after SSE removal)

After removing SSE fallback and running WS-only transport:

### Marbles WS-only

- many-small (`100 x 4 = 400`) PASS, p95 1ms
- mixed (`300 hotspot + 100x4 = 700`) FAIL, p95 3305ms
- mixed reduced (`250 hotspot + 75x4 = 550`) PASS, p95 1466ms

Interpretation: marbles WS-only profile behavior is similar to pre-cut in practical range.

### Chess WS-only

- many-small (`100 x 4 = 400`) PASS, p95 1ms
- mixed (`300 hotspot + 100x4 = 700`) PASS, p95 174ms

Interpretation: chess WS-only profile remains strong and stable for tested ranges.

## After Bounded Queue + Coalescing Refactor

Refactor summary:

- `Player.channel` changed from unbounded payload queue to bounded signal queue.
- Added per-player coalescing:
  - latest non-terminal state is coalesced (latest wins),
  - terminal state is preserved/prioritized.
- Routing now drains pending state snapshots per flush signal.

### Marbles (after)

Command:

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game marbles \
  --start 100 \
  --max 500 \
  --ramp 8 \
  --hold 15 \
  --step +100 \
  --concurrency 100
```

Observed:

- 100 PASS (p95 2ms)
- 200 PASS (p95 2ms)
- 300 PASS (p95 793ms)
- 400 FAIL (openRate 99.3%, p95 5139ms, wsFail open-timeout=3)

Interpreted stable range after refactor: **~300**.

Re-run after terminal/reconnect queue fixes (same command):

- 100 PASS (p95 2ms)
- 200 PASS (p95 3ms)
- 300 PASS (p95 1118ms)
- 400 FAIL (openRate 99.5%, p95 5675ms, wsFail open-timeout=2)

Stable interpretation unchanged: **~300**.

### Chess (after)

Command:

```bash
node ./scripts/loadtest-realtime.mjs \
  --base http://localhost:8080 \
  --game chess \
  --start 100 \
  --max 500 \
  --ramp 8 \
  --hold 15 \
  --step +100 \
  --concurrency 100
```

Observed:

- 100 PASS (p95 2ms)
- 200 PASS (p95 1ms)
- 300 PASS (p95 2ms)
- 400 PASS (p95 9ms)
- 500 PASS (p95 175ms)

Interpreted stable range after refactor on this setup: **at least 500**.

Re-run after terminal/reconnect queue fixes (same command):

- 100 PASS (p95 2ms)
- 200 PASS (p95 1ms)
- 300 PASS (p95 2ms)
- 400 PASS (p95 24ms)
- 500 PASS (p95 177ms)

Stable interpretation unchanged: **at least 500**.
