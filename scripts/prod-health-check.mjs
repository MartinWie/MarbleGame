#!/usr/bin/env node

import { chromium } from '@playwright/test';

const defaults = {
  baseUrl: 'https://games.7mw.de',
  timeoutMs: 25000,
  headed: false,
};

function parseArgs(argv) {
  const options = { ...defaults };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = argv[i + 1];
    if (arg === '--base' && next) {
      options.baseUrl = next;
      i += 1;
      continue;
    }
    if (arg === '--timeout-ms' && next) {
      options.timeoutMs = Number(next);
      i += 1;
      continue;
    }
    if (arg === '--headed') {
      options.headed = true;
      continue;
    }
  }
  return options;
}

function normalizeBase(baseUrl) {
  const url = new URL(baseUrl);
  return `${url.protocol}//${url.host}`;
}

function randomName(prefix) {
  return `${prefix}-${Math.random().toString(36).slice(2, 7)}`;
}

function quoted(value) {
  if (typeof value !== 'string') return String(value);
  return value.includes(' ') ? `"${value}"` : value;
}

async function createGame(page, baseUrl, playerName) {
  await page.goto(`${baseUrl}/`, { waitUntil: 'domcontentloaded' });

  await page.locator('#create-form-marbles input[name="playerName"]').fill(playerName);
  await page.getByRole('button', { name: 'Create Marbles' }).click();

  await page.waitForURL(/\/game\/[a-f0-9]{8}$/);

  const gameId = page.url().split('/').pop();
  if (!gameId) throw new Error('Unable to parse game ID from URL');
  return gameId;
}

async function joinGame(page, baseUrl, gameId, playerName) {
  await page.goto(`${baseUrl}/game/${gameId}/join`, { waitUntil: 'domcontentloaded' });
  if (await page.locator('.game-area').count()) {
    await page.waitForURL(new RegExp(`/game/${gameId}$`));
    return;
  }
  await page.locator('input[name="playerName"]').fill(playerName);
  await page.getByRole('button', { name: 'Join Game' }).click();
  await page.waitForURL(new RegExp(`/game/${gameId}$`));
}

async function postMarblesAction(page, gameId, path, payload = {}) {
  return await page.evaluate(
    async ({ gameId, path, payload }) => {
      const body = new URLSearchParams(payload);
      const response = await fetch(`/game/${gameId}/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: body.toString(),
      });
      return response.status;
    },
    { gameId, path, payload },
  );
}

async function waitForCondition(page, timeoutMs, predicate) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const ok = await page.evaluate(predicate);
    if (ok) return true;
    await page.waitForTimeout(150);
  }
  return false;
}

function createWsTracker(page) {
  const tracker = {
    connections: [],
  };

  page.on('websocket', (ws) => {
    const conn = {
      url: ws.url(),
      frames: 0,
      pingFrames: 0,
      updateFrames: 0,
      closed: false,
      socketError: false,
    };
    tracker.connections.push(conn);

    ws.on('framereceived', (frame) => {
      conn.frames += 1;
      const payload = typeof frame.payload === 'string' ? frame.payload : '';
      if (payload === '__PING__') conn.pingFrames += 1;
      if (payload.startsWith('__GAME_UPDATE__:')) {
        conn.updateFrames += 1;
      }
    });

    ws.on('close', () => {
      conn.closed = true;
    });

    ws.on('socketerror', () => {
      conn.socketError = true;
    });
  });

  return tracker;
}

function summarizeTracker(tracker, wsPathFragment) {
  const matched = tracker.connections.filter((c) => c.url.includes(wsPathFragment));
  return {
    seen: matched.length,
    updates: matched.reduce((acc, c) => acc + c.updateFrames, 0),
    pings: matched.reduce((acc, c) => acc + c.pingFrames, 0),
    closes: matched.filter((c) => c.closed).length,
    errors: matched.filter((c) => c.socketError).length,
    urls: matched.map((c) => c.url),
  };
}

async function waitForWsSeen(tracker, wsPathFragment, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const summary = summarizeTracker(tracker, wsPathFragment);
    if (summary.seen > 0) {
      return {
        ok: true,
        elapsedMs: Date.now() - started,
        ...summary,
      };
    }
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
  return {
    ok: false,
    elapsedMs: Date.now() - started,
    ...summarizeTracker(tracker, wsPathFragment),
  };
}

async function marblesSnapshot(page, expectedNames) {
  return await page.evaluate((expectedNames) => {
    const players = Array.from(document.querySelectorAll('.players-list .player-card'))
      .map((card) => {
        const name = (card.querySelector('.player-name')?.textContent || '').trim();
        const marbles = (card.querySelector('.player-marbles')?.textContent || '').trim();
        return {
          name,
          marbles: parseInt(marbles, 10) || 0,
        };
      })
      .filter((entry) => !!entry.name);

    const marblesByExpected = expectedNames.map((expectedName) => {
      const hit = players.find((entry) => entry.name.startsWith(expectedName));
      return hit ? hit.marbles : null;
    });

    const phase = (document.querySelector('.phase-info h2')?.textContent || '').trim();
    return {
      phase,
      playerCount: players.length,
      marblesByExpected,
    };
  }, expectedNames);
}

async function runMarblesCheck(baseUrl, timeoutMs, contextA, contextB) {
  const host = await contextA.newPage();
  const guest = await contextB.newPage();
  const hostWsTracker = createWsTracker(host);
  const guestWsTracker = createWsTracker(guest);
  const result = { mode: 'marbles', ok: false, gameId: null, checks: [] };
  try {
    const hostName = randomName('host');
    const guestName = randomName('guest');
    const expectedPlayers = [hostName, guestName];

    const gameId = await createGame(host, baseUrl, hostName);
    result.gameId = gameId;
    await joinGame(guest, baseUrl, gameId, guestName);

    const [wsHost, wsGuest] = await Promise.all([
      waitForWsSeen(hostWsTracker, `/ws/game/${gameId}`, timeoutMs),
      waitForWsSeen(guestWsTracker, `/ws/game/${gameId}`, timeoutMs),
    ]);
    result.checks.push({ step: 'ws-host', ...wsHost });
    result.checks.push({ step: 'ws-guest', ...wsGuest });
    if (!wsHost.ok || !wsGuest.ok) return result;

    const startStatus = await postMarblesAction(host, gameId, 'start');
    result.checks.push({ step: 'start', status: startStatus });
    if (startStatus !== 200) return result;

    let placeStatus = await postMarblesAction(host, gameId, 'place', { amount: '1' });
    if (placeStatus !== 200) {
      placeStatus = await postMarblesAction(guest, gameId, 'place', { amount: '1' });
    }
    result.checks.push({ step: 'place', status: placeStatus });
    if (placeStatus !== 200) return result;

    const guessHost = await postMarblesAction(host, gameId, 'guess', { guess: 'EVEN' });
    const guessGuest = await postMarblesAction(guest, gameId, 'guess', { guess: 'ODD' });
    result.checks.push({ step: 'guess', host: guessHost, guest: guessGuest });

    const syncedHost = await waitForCondition(host, timeoutMs, () => {
      return !!document.querySelector('.phase-info') && document.querySelectorAll('.players-list .player-card').length >= 2;
    });
    const syncedGuest = await waitForCondition(guest, timeoutMs, () => {
      return !!document.querySelector('.phase-info') && document.querySelectorAll('.players-list .player-card').length >= 2;
    });

    const snapHost = await marblesSnapshot(host, expectedPlayers);
    const snapGuest = await marblesSnapshot(guest, expectedPlayers);
    const wsHostAfter = summarizeTracker(hostWsTracker, `/ws/game/${gameId}`);
    const wsGuestAfter = summarizeTracker(guestWsTracker, `/ws/game/${gameId}`);
    result.checks.push({ step: 'snapshot-host', snap: snapHost });
    result.checks.push({ step: 'snapshot-guest', snap: snapGuest });
    result.checks.push({ step: 'ws-host-final', ...wsHostAfter });
    result.checks.push({ step: 'ws-guest-final', ...wsGuestAfter });

    result.ok =
      syncedHost &&
      syncedGuest &&
      snapHost.playerCount >= 2 &&
      snapGuest.playerCount >= 2 &&
      !snapHost.marblesByExpected.includes(null) &&
      !snapGuest.marblesByExpected.includes(null) &&
      JSON.stringify(snapHost.marblesByExpected) === JSON.stringify(snapGuest.marblesByExpected) &&
      (wsHostAfter.updates > 0 || wsHostAfter.pings > 0) &&
      (wsGuestAfter.updates > 0 || wsGuestAfter.pings > 0);
    return result;
  } finally {
    await host.close();
    await guest.close();
  }
}

function printResult(result) {
  const icon = result.ok ? 'PASS' : 'FAIL';
  console.log(`[${icon}] mode=${result.mode} game=${result.gameId || '-'} `);
  for (const check of result.checks) {
    console.log('  -', JSON.stringify(check));
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (!Number.isFinite(options.timeoutMs) || options.timeoutMs < 3000) {
    throw new Error(`Invalid --timeout-ms value: ${options.timeoutMs}`);
  }
  const baseUrl = normalizeBase(options.baseUrl);

  console.log('Realtime health check configuration:');
  console.log(`  base: ${baseUrl}`);
  console.log(`  timeoutMs: ${options.timeoutMs}`);
  console.log(`  headed: ${options.headed}`);

  const browser = await chromium.launch({ headless: !options.headed });
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();

  let marblesResult;
  try {
    marblesResult = await runMarblesCheck(baseUrl, options.timeoutMs, contextA, contextB);
  } finally {
    await contextA.close();
    await contextB.close();
    await browser.close();
  }

  printResult(marblesResult);

  if (!marblesResult.ok) {
    console.error('Realtime health check FAILED');
    process.exit(1);
  }

  console.log('Realtime health check PASSED');
  console.log('Suggested CI usage:');
  console.log(`  node scripts/prod-health-check.mjs --base ${quoted(baseUrl)} --timeout-ms ${options.timeoutMs}`);
}

main().catch((err) => {
  console.error('Health check execution error:', err && err.stack ? err.stack : err);
  process.exit(2);
});
