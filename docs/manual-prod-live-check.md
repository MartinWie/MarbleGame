# Manual Production Live Check (Marbles)

This runbook verifies that the currently deployed production setup is live and marbles realtime sync works as expected.

Target: `https://games.7mw.de`

## Prerequisites

- 2 browser sessions (recommended: two different browsers, or one normal + one incognito).
- Optional for deeper checks:
  - DevTools Network tab (WebSocket filter)
  - Console tab

## Quick Smoke (2-3 min)

1. Open `https://games.7mw.de` in both sessions.
2. Session A creates a game.
3. Session B joins via the shared join link.
4. Session A places marbles and submits a guess.
5. Verify Session B updates automatically without manual refresh.

Pass criteria:

- Both sessions stay in sync in real time.
- No manual refresh required.

## Detailed Verification

### 1) Marbles Realtime Sync

1. Session A: create marbles game (`HostA`).
2. Session B: join (`GuestB`).
3. Session A and B both submit guesses in a round.
4. Verify on both:
   - phase changes together,
   - round result updates together,
   - marble counts match.

Expected:

- Updates appear on both clients within ~1s.

### 2) Reconnect Behavior

1. Keep Session A on game page.
2. Session B refreshes the page 3-5 times quickly.
3. Verify Session A remains stable and Session B reconnects.
4. Make one action from Session A and ensure Session B receives it after reconnect.

Expected:

- No permanent desync.
- Reconnect countdown/status may briefly appear, then clears.

### 3) WebSocket Health Check (optional, recommended)

In browser DevTools for a game page:

- WS URL should be: `wss://games.7mw.de/ws/game/<gameId>`

Expected:

- Socket remains open (not rapid open/close loop).
- Close code `1008` with reason `Invalid origin` must **not** appear in normal usage.

## Failure Signals to Capture

If check fails, capture:

- Game ID
- Time (UTC)
- Browser + OS
- Steps to reproduce
- WebSocket close code/reason from DevTools
- Screenshot of both sessions showing divergence

## Suggested Go/No-Go Checklist

- [ ] Two-client sync works
- [ ] Reconnect after rapid refresh recovers
- [ ] WS connections stay open (no repeated invalid-origin closes)
- [ ] No persistent client divergence after multiple rounds

If all are checked, production realtime is considered healthy for manual live verification.

## Automated Probe (CI/Pipeline Friendly)

Use the scripted probe to validate realtime health automatically after deployment.

Run against production:

```bash
npm run health:realtime:prod
```

Run against local/staging-like target:

```bash
npm run health:realtime:local
# or custom base:
node scripts/prod-health-check.mjs --base https://your-env.example.com --timeout-ms 30000
```

Behavior:

- creates and joins one marbles game
- verifies WS path opens and receives traffic
- executes one gameplay action
- verifies both clients converge to same state
- exits with non-zero status on failure (pipeline-friendly)
