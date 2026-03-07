# WebSocket Migration Plan (SSE -> WS)

This document defines a low-risk migration path from the current SSE + HTTP polling model to a WebSocket-driven realtime stack that is better suited for 100x scale goals.

## Goals

- Reduce server CPU spent on repeated HTML rendering and polling endpoints.
- Reduce network overhead for spectator-heavy games.
- Keep reconnect behavior and correctness at least as strong as today.
- Preserve backward compatibility during rollout (SSE fallback).

## Current Architecture (Baseline)

- Realtime outbound transport: SSE per connected player.
- Inbound actions: HTTP form posts/fetch (`/move`, `/guess`, etc.).
- Polling endpoints for timers/maintenance (`check-time`, `check-disconnects`, `check-auto-restart`).
- Server pushes HTML fragments; client incrementally patches DOM.

Main bottlenecks:

- Per-client render fanout with larger payloads than needed.
- Client-driven polling load scales with number of connections.
- Unlimited channels can amplify memory under backpressure.

## Target Architecture

- Realtime transport: WebSocket per connected client.
- Data protocol: compact JSON messages (typed events, snapshots, acks/errors).
- Server-side schedulers for timed game mechanics (clock/disconnect/auto-restart).
- Client applies state updates (DOM patching can remain initially, then move to state-driven updates).

## Message Protocol (v1)

All messages are JSON with these common fields:

- `type`: message type string
- `gameId`: short id
- `seq`: monotonic server sequence (for ordering/reconnect)
- `ts`: server timestamp (ms)

Server -> client:

- `snapshot`
  - Full current game state for initial connect/reconnect.
  - Includes phase, players, board/state, turn, clocks, metadata.
- `state_patch`
  - Minimal state changes after actions/ticks.
  - Includes only changed fields plus latest `seq`.
- `event`
  - UI hints (check, checkmate, timeout, reconnect info).
- `error`
  - Action failed (`NOT_YOUR_TURN`, `INVALID_MOVE`, etc.) for command channel.
- `pong`
  - Optional keepalive response.

Client -> server:

- `subscribe`
  - `{ type, gameId, lastSeq? }`
- `ping`
  - Keepalive and latency measurement.
- `command` (phase 3+)
  - `{ type: "command", command: "chess.move", payload: { from, to }, reqId }`

### Protocol Compatibility Rules

- Include explicit `version` field in top-level payload (`version: 1`).
- Ignore unknown message types/fields client-side for forward compatibility.
- Server rejects unsupported protocol versions with typed `error`.
- `reqId` is idempotent per connection/session for command retries.

## Security Requirements

- Require authenticated session on WS upgrade.
- Validate game membership at `subscribe` time.
- Validate command authorization server-side (turn ownership, phase checks, role checks).
- Apply per-connection rate limits for commands and subscribe attempts.
- Keep parity with current HTTP validation semantics and error codes.

## Reconnect & Consistency

- Client stores `lastSeq` per open game.
- On reconnect, client sends `subscribe` with `lastSeq`.
- Server behavior:
  - If retained state/patches can bridge gap: send missed patches.
  - Else: send full `snapshot`.
- Keep replay buffer bounded (ring buffer per game + TTL), then fallback to snapshot.
- Client drops out-of-order or duplicate `seq` safely.

## Phased Rollout

### Phase 1 - Transport Introduction (low risk)

- Add WS endpoints alongside SSE:
  - `/ws/chess/{gameId}`
  - `/ws/game/{gameId}`
- Keep inbound HTTP actions unchanged.
- Server still renders existing payloads, but sends via WS for opted-in clients.
- Feature flag: `REALTIME_TRANSPORT=auto|ws|sse` (`auto` preferred).

Exit criteria:

- WS clients functionally equivalent to SSE clients for gameplay.
- Auto fallback to SSE on WS connect failure.

Rollout guardrails:

- Start with canary percentage of sessions.
- Keep immediate rollback switch to force `sse`.
- Expand rollout only if connection error budget remains below threshold.

### Phase 2 - Polling Removal & Server Tickers

- Move `check-time`, `check-disconnects`, `check-auto-restart` from client polling to server schedulers.
- Emit clock/disconnect updates on scheduler ticks through WS/SSE abstraction.

Exit criteria:

- Poll endpoints no longer required for normal operation.
- Request volume per client drops materially under spectator load.

### Phase 3 - JSON State Protocol

- Introduce protocol types (`snapshot`, `state_patch`, `event`).
- Keep HTML path behind feature flag during transition.
- Clients apply JSON updates; minimize full re-render paths.

Exit criteria:

- Majority of updates use JSON patches.
- Payload size and server render CPU significantly reduced.

### Phase 4 - WS Commands (optional)

- Move selected actions (e.g., chess move) from HTTP to WS `command` messages.
- Maintain HTTP as fallback until stable.

Exit criteria:

- Command latency is stable; reconnect correctness preserved.

## Backpressure & Queue Policy

- Replace `Channel.UNLIMITED` with bounded queues.
- Current source location: `Player.channel`.
- Policy:
  - Keep latest state patch (coalesce intermediate patches).
  - Never drop terminal state transitions (GAME_OVER, winner/endReason).
- Track dropped/coalesced counts in metrics.

## Endpoint Mapping (Current -> Target)

- `/game/{id}/check-disconnects` -> server scheduler tick
- `/chess/{id}/check-disconnects` -> server scheduler tick
- `/chess/{id}/check-time` -> server scheduler tick
- `/chess/{id}/check-auto-restart` -> server scheduler tick

## Scaling Architecture for 100x

- Short term:
  - Multiple app instances behind LB.
  - Sticky sessions OR shard by gameId.
- Medium term:
  - Shared realtime backbone (Redis pub/sub or NATS) for cross-instance fanout.
  - Game placement/sharding by `gameId` hash.

## Metrics & SLOs

Track and alert on:

- `p95` move propagation latency
- connection count / active games
- queue depth and dropped/coalesced updates
- reconnect success rate
- patch vs snapshot ratio
- CPU, memory, outbound bandwidth per node

Initial SLO targets:

- `p95` propagation < 250ms under normal load
- reconnect recovery < 2s for transient disconnects
- dropped terminal updates = 0

## Suggested Implementation Order (repo-specific)

1. Add WS route handlers in `Routing.kt` using existing session checks.
2. Extract common broadcast abstraction used by both SSE and WS.
3. Add server schedulers (clock/disconnect/auto-restart) and disable client polling under flag.
4. Add JSON `snapshot` payload for chess first, then marbles.
5. Add `seq` handling and reconnect recovery path.
6. Gradually move command endpoints to WS messages (optional).

## Risks & Mitigations

- Risk: reconnect race/ordering bugs
  - Mitigation: `seq` + snapshot fallback; deterministic tests for reconnect.
- Risk: queue overload under burst
  - Mitigation: bounded queues + coalescing + metrics.
- Risk: rollout regressions
  - Mitigation: feature flags per game type and transport fallback.

## Test Plan Additions

- Unit:
  - sequence handling
  - queue coalescing behavior
  - scheduler tick behavior
- E2E:
  - reconnect resumes with consistent state
  - timeout/disconnect/auto-restart without client polling
  - mixed transport sessions (SSE + WS) in same game
