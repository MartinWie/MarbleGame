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

async function clickMode(page, mode) {
  await page.locator(`.mode-tile[data-mode="${mode}"]`).click();
}

async function createGame(page, baseUrl, gameType, playerName) {
  await page.goto(`${baseUrl}/`, { waitUntil: 'domcontentloaded' });
  await clickMode(page, gameType === 'chess' ? 'chess' : 'marbles');

  const formId = gameType === 'chess' ? '#create-form-chess' : '#create-form-marbles';
  const buttonText = gameType === 'chess' ? 'Create Chess' : 'Create Marbles';

  await page.locator(`${formId} input[name="playerName"]`).fill(playerName);
  await page.locator(`${formId} button:has-text("${buttonText}")`).click();

  const pathRegex = gameType === 'chess' ? /\/chess\/[a-f0-9]{8}$/ : /\/game\/[a-f0-9]{8}$/;
  await page.waitForURL(pathRegex);

  const gameId = page.url().split('/').pop();
  if (!gameId) throw new Error(`Unable to parse ${gameType} game ID from URL`);
  return gameId;
}

async function joinGame(page, baseUrl, gameType, gameId, playerName) {
  const joinPath = gameType === 'chess' ? `/chess/${gameId}/join` : `/game/${gameId}/join`;
  await page.goto(`${baseUrl}${joinPath}`, { waitUntil: 'domcontentloaded' });
  if (await page.locator('.chess-board, .game-area').count()) {
    const pathRegex = gameType === 'chess' ? new RegExp(`/chess/${gameId}$`) : new RegExp(`/game/${gameId}$`);
    await page.waitForURL(pathRegex);
    return;
  }
  await page.locator('input[name="playerName"]').fill(playerName);
  await page.locator('button:has-text("Join Game")').click();
  const pathRegex = gameType === 'chess' ? new RegExp(`/chess/${gameId}$`) : new RegExp(`/game/${gameId}$`);
  await page.waitForURL(pathRegex);
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

async function postChessMove(page, gameId, from, to) {
  return await page.evaluate(
    async ({ gameId, from, to }) => {
      const body = new URLSearchParams({ from, to });
      const response = await fetch(`/chess/${gameId}/move`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: body.toString(),
      });
      return response.status;
    },
    { gameId, from, to },
  );
}

async function legalMoves(page, gameId, from) {
  return await page.evaluate(
    async ({ gameId, from }) => {
      const res = await fetch(`/chess/${gameId}/legal-moves?from=${encodeURIComponent(from)}`);
      if (!res.ok) return [];
      const text = await res.text();
      return text ? text.split(',').filter(Boolean) : [];
    },
    { gameId, from },
  );
}

async function resolveWhiteBlackPages(host, guest, gameId) {
  const hostMoves = await legalMoves(host, gameId, 'e2');
  if (hostMoves.length > 0) return { white: host, black: guest };
  const guestMoves = await legalMoves(guest, gameId, 'e2');
  if (guestMoves.length > 0) return { white: guest, black: host };
  throw new Error('Could not determine white player for chess check');
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
      if (payload.startsWith('__GAME_UPDATE__:') || payload.startsWith('__CHESS_UPDATE__:')) {
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

async function chessSnapshot(page) {
  return await page.evaluate(() => {
    const board = document.querySelector('.chess-board');
    if (!board) return null;
    const e2 = document.querySelector('.chess-square[data-square="e2"]')?.getAttribute('data-piece') || '';
    const e4 = document.querySelector('.chess-square[data-square="e4"]')?.getAttribute('data-piece') || '';
    const meta = board.getAttribute('data-last-move-meta') || '';
    const turn = board.getAttribute('data-turn') || '';
    return { e2, e4, meta, turn };
  });
}

async function runMarblesCheck(baseUrl, timeoutMs, contextA, contextB) {
  const host = await contextA.newPage();
  const guest = await contextB.newPage();
  const hostWsTracker = createWsTracker(host);
  const guestWsTracker = createWsTracker(guest);
  const result = { mode: 'marbles', ok: false, gameId: null, checks: [] };
  try {
    const hostName = randomName('m-host');
    const guestName = randomName('m-guest');
    const expectedPlayers = [hostName, guestName];

    const gameId = await createGame(host, baseUrl, 'marbles', hostName);
    result.gameId = gameId;
    await joinGame(guest, baseUrl, 'marbles', gameId, guestName);

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

async function runChessCheck(baseUrl, timeoutMs, contextA, contextB) {
  const host = await contextA.newPage();
  const guest = await contextB.newPage();
  const hostWsTracker = createWsTracker(host);
  const guestWsTracker = createWsTracker(guest);
  const result = { mode: 'chess', ok: false, gameId: null, checks: [] };
  try {
    const gameId = await createGame(host, baseUrl, 'chess', randomName('c-host'));
    result.gameId = gameId;
    await joinGame(guest, baseUrl, 'chess', gameId, randomName('c-guest'));

    const [wsHost, wsGuest] = await Promise.all([
      waitForWsSeen(hostWsTracker, `/ws/chess/${gameId}`, timeoutMs),
      waitForWsSeen(guestWsTracker, `/ws/chess/${gameId}`, timeoutMs),
    ]);
    result.checks.push({ step: 'ws-host', ...wsHost });
    result.checks.push({ step: 'ws-guest', ...wsGuest });
    if (!wsHost.ok || !wsGuest.ok) return result;

    const { white } = await resolveWhiteBlackPages(host, guest, gameId);
    const moveStatus = await postChessMove(white, gameId, 'e2', 'e4');
    result.checks.push({ step: 'move', status: moveStatus });
    if (moveStatus !== 200) return result;

    const hostSynced = await waitForCondition(host, timeoutMs, () => {
      const e2 = document.querySelector('.chess-square[data-square="e2"]')?.getAttribute('data-piece') || '';
      const e4 = document.querySelector('.chess-square[data-square="e4"]')?.getAttribute('data-piece') || '';
      return e2 === '' && e4 !== '';
    });
    const guestSynced = await waitForCondition(guest, timeoutMs, () => {
      const e2 = document.querySelector('.chess-square[data-square="e2"]')?.getAttribute('data-piece') || '';
      const e4 = document.querySelector('.chess-square[data-square="e4"]')?.getAttribute('data-piece') || '';
      return e2 === '' && e4 !== '';
    });

    const snapHost = await chessSnapshot(host);
    const snapGuest = await chessSnapshot(guest);
    const wsHostAfter = summarizeTracker(hostWsTracker, `/ws/chess/${gameId}`);
    const wsGuestAfter = summarizeTracker(guestWsTracker, `/ws/chess/${gameId}`);
    result.checks.push({ step: 'snapshot-host', snap: snapHost });
    result.checks.push({ step: 'snapshot-guest', snap: snapGuest });
    result.checks.push({ step: 'ws-host-final', ...wsHostAfter });
    result.checks.push({ step: 'ws-guest-final', ...wsGuestAfter });

    result.ok =
      hostSynced &&
      guestSynced &&
      !!snapHost &&
      !!snapGuest &&
      snapHost.e2 === '' &&
      snapGuest.e2 === '' &&
      snapHost.e4 !== '' &&
      snapGuest.e4 !== '' &&
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
  let chessResult;
  try {
    marblesResult = await runMarblesCheck(baseUrl, options.timeoutMs, contextA, contextB);
    chessResult = await runChessCheck(baseUrl, options.timeoutMs, contextA, contextB);
  } finally {
    await contextA.close();
    await contextB.close();
    await browser.close();
  }

  printResult(marblesResult);
  printResult(chessResult);

  if (!marblesResult.ok || !chessResult.ok) {
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
