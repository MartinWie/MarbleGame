#!/usr/bin/env node

import { performance } from 'node:perf_hooks';

const defaults = {
  baseUrl: 'http://localhost:8080',
  start: 50,
  max: 1600,
  holdSec: 20,
  rampSec: 10,
  stepMode: 'x2',
  gameType: 'marbles',
  concurrency: 100,
};

function parseArgs(argv) {
  const args = { ...defaults };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    const next = argv[i + 1];
    if (a === '--base' && next) {
      args.baseUrl = next;
      i += 1;
    } else if (a === '--start' && next) {
      args.start = parseInt(next, 10);
      i += 1;
    } else if (a === '--max' && next) {
      args.max = parseInt(next, 10);
      i += 1;
    } else if (a === '--hold' && next) {
      args.holdSec = parseInt(next, 10);
      i += 1;
    } else if (a === '--ramp' && next) {
      args.rampSec = parseInt(next, 10);
      i += 1;
    } else if (a === '--step' && next) {
      args.stepMode = next;
      i += 1;
    } else if (a === '--game' && next) {
      args.gameType = next;
      i += 1;
    } else if (a === '--concurrency' && next) {
      args.concurrency = parseInt(next, 10);
      i += 1;
    }
  }
  return args;
}

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function safeSetCookieArray(headers) {
  if (typeof headers.getSetCookie === 'function') {
    return headers.getSetCookie();
  }
  const one = headers.get('set-cookie');
  return one ? [one] : [];
}

function cookieHeaderFromSetCookie(setCookies) {
  return setCookies.map((c) => c.split(';')[0]).join('; ');
}

function randomName(prefix) {
  return `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
}

function wsUrlFromBase(baseUrl, path) {
  const u = new URL(baseUrl);
  const protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${u.host}${path}`;
}

async function createGame(baseUrl, gameType) {
  const rootResp = await fetch(`${baseUrl}/`, { redirect: 'manual' });
  const rootCookies = cookieHeaderFromSetCookie(safeSetCookieArray(rootResp.headers));

  const endpoint = gameType === 'chess' ? '/chess/create' : '/game/create';
  const body = new URLSearchParams({ playerName: randomName('host') });
  const resp = await fetch(`${baseUrl}${endpoint}`, {
    method: 'POST',
    redirect: 'manual',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      cookie: rootCookies,
    },
    body,
  });

  const location = resp.headers.get('location') || '';
  if (!location) {
    throw new Error(`Create game failed: status=${resp.status}, no Location header`);
  }

  const gameId = location.split('/').pop();
  if (!gameId) {
    throw new Error(`Could not parse game id from location=${location}`);
  }

  return { gameId, hostCookie: rootCookies };
}

async function joinGame(baseUrl, gameType, gameId, playerName) {
  const rootResp = await fetch(`${baseUrl}/`, { redirect: 'manual' });
  const rootCookies = cookieHeaderFromSetCookie(safeSetCookieArray(rootResp.headers));

  const endpoint = gameType === 'chess' ? '/chess/join' : '/game/join';
  const form = new URLSearchParams({ playerName, gameId });

  const resp = await fetch(`${baseUrl}${endpoint}`, {
    method: 'POST',
    redirect: 'manual',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      cookie: rootCookies,
    },
    body: form,
  });

  if (resp.status !== 302 && resp.status !== 200) {
    throw new Error(`Join failed status=${resp.status}`);
  }

  const joinedCookies = safeSetCookieArray(resp.headers);
  const joinedCookieHeader = cookieHeaderFromSetCookie(joinedCookies);
  return joinedCookieHeader || rootCookies;
}

async function connectWs(baseUrl, gameType, gameId, cookieHeader) {
  const path = gameType === 'chess' ? `/ws/chess/${gameId}` : `/ws/game/${gameId}`;
  const wsUrl = wsUrlFromBase(baseUrl, path);
  const t0 = performance.now();

  return await new Promise((resolve) => {
    let opened = false;
    let messages = 0;
    let closedUnexpectedly = false;
    let closeCode = null;
    let closeReason = '';

    const ws = new WebSocket(wsUrl, { headers: { Cookie: cookieHeader } });

    const timeout = setTimeout(() => {
      if (!opened) {
        try {
          ws.close();
        } catch (_) {}
        resolve({ ok: false, connectMs: performance.now() - t0, error: 'open-timeout' });
      }
    }, 10000);

    ws.onopen = () => {
      opened = true;
      clearTimeout(timeout);
      resolve({
        ok: true,
        connectMs: performance.now() - t0,
        socket: ws,
        stats: () => ({ messages, closedUnexpectedly, closeCode, closeReason }),
      });
    };

    ws.onmessage = () => {
      messages += 1;
    };

    ws.onerror = () => {
      if (!opened) {
        clearTimeout(timeout);
        resolve({ ok: false, connectMs: performance.now() - t0, error: 'ws-error' });
      }
    };

    ws.onclose = (evt) => {
      closeCode = evt.code;
      closeReason = evt.reason || '';
      if (opened) {
        closedUnexpectedly = true;
      }
    };
  });
}

function nextStageSize(current, stepMode) {
  if (stepMode.startsWith('x')) {
    const factor = Number(stepMode.slice(1)) || 2;
    return Math.max(current + 1, Math.floor(current * factor));
  }
  if (stepMode.startsWith('+')) {
    const inc = Number(stepMode.slice(1)) || 50;
    return current + inc;
  }
  return current * 2;
}

async function runStage(args, gameId, targetPlayers) {
  const results = {
    targetPlayers,
    joinOk: 0,
    joinFail: 0,
    wsOk: 0,
    wsFail: 0,
    connectLatencies: [],
    sockets: [],
    unexpectedCloses: 0,
    totalMessages: 0,
    joinFailReasons: Object.create(null),
    wsFailReasons: Object.create(null),
  };

  function bumpReason(bucket, reason) {
    const key = reason || 'unknown';
    bucket[key] = (bucket[key] || 0) + 1;
  }

  const rampMs = Math.max(1, args.rampSec * 1000);
  const spacingMs = Math.max(1, Math.floor(rampMs / targetPlayers));

  const taskIndexes = Array.from({ length: targetPlayers }, (_, i) => i);
  const workerCount = Math.max(1, Math.min(args.concurrency, targetPlayers));

  const workers = Array.from({ length: workerCount }, async () => {
    while (taskIndexes.length > 0) {
      const i = taskIndexes.shift();
      if (typeof i !== 'number') break;

      await sleep(i * spacingMs);
      const playerName = randomName('p');
      try {
        const cookie = await joinGame(args.baseUrl, args.gameType, gameId, playerName);
        results.joinOk += 1;

        const wsRes = await connectWs(args.baseUrl, args.gameType, gameId, cookie);
        if (!wsRes.ok) {
          results.wsFail += 1;
          bumpReason(results.wsFailReasons, wsRes.error || 'ws-fail');
          continue;
        }

        results.wsOk += 1;
        results.connectLatencies.push(wsRes.connectMs);
        results.sockets.push(wsRes);
      } catch (err) {
        results.joinFail += 1;
        bumpReason(results.joinFailReasons, err && err.message ? err.message : 'join-fail');
      }
    }
  });

  await Promise.all(workers);

  await sleep(args.holdSec * 1000);

  for (const wsRes of results.sockets) {
    const s = wsRes.stats();
    if (s.closedUnexpectedly) {
      results.unexpectedCloses += 1;
    }
    results.totalMessages += s.messages;
  }

  for (const wsRes of results.sockets) {
    try {
      wsRes.socket.close();
    } catch (_) {}
  }

  return results;
}

function classifyStage(stage) {
  const openRate = stage.targetPlayers > 0 ? stage.wsOk / stage.targetPlayers : 0;
  const unexpectedCloseRate = stage.wsOk > 0 ? stage.unexpectedCloses / stage.wsOk : 1;
  const p95 = percentile(stage.connectLatencies, 95);

  const pass = openRate >= 0.98 && unexpectedCloseRate <= 0.02 && p95 <= 2000;
  return { pass, openRate, unexpectedCloseRate, p95 };
}

function memorySummary() {
  const m = process.memoryUsage();
  const toMb = (b) => Math.round((b / (1024 * 1024)) * 10) / 10;
  return {
    rssMb: toMb(m.rss),
    heapUsedMb: toMb(m.heapUsed),
    heapTotalMb: toMb(m.heapTotal),
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  console.log('Load test config:', JSON.stringify(args));

  let size = args.start;
  let lastPass = null;
  let firstFail = null;
  const stageResults = [];

  while (size <= args.max) {
    console.log(`\nStage: target=${size} players`);
    const { gameId } = await createGame(args.baseUrl, args.gameType);
    console.log(`Stage game: ${gameId}`);

    const stage = await runStage(args, gameId, size);
    const c = classifyStage(stage);
    stageResults.push({ ...stage, ...c });

    console.log(
      [
        `joinOk=${stage.joinOk}`,
        `joinFail=${stage.joinFail}`,
        `wsOk=${stage.wsOk}`,
        `wsFail=${stage.wsFail}`,
        `openRate=${(c.openRate * 100).toFixed(1)}%`,
        `p95=${Math.round(c.p95)}ms`,
        `unexpectedCloseRate=${(c.unexpectedCloseRate * 100).toFixed(1)}%`,
        `msgs=${stage.totalMessages}`,
        `joinFailReasons=${JSON.stringify(stage.joinFailReasons)}`,
        `wsFailReasons=${JSON.stringify(stage.wsFailReasons)}`,
        `clientMem=${JSON.stringify(memorySummary())}`,
        `result=${c.pass ? 'PASS' : 'FAIL'}`,
      ].join(' | '),
    );

    if (c.pass) {
      lastPass = stage.targetPlayers;
      size = nextStageSize(size, args.stepMode);
    } else {
      firstFail = stage.targetPlayers;
      break;
    }
  }

  console.log('\n=== Summary ===');
  if (lastPass == null && firstFail != null) {
    console.log(`No passing stage. First failure at ${firstFail}.`);
  } else if (lastPass != null && firstFail == null) {
    console.log(`No failure up to max=${args.max}. Last passing stage: ${lastPass}.`);
  } else {
    console.log(`Last passing stage: ${lastPass}, first failing stage: ${firstFail}.`);
  }

  const best = stageResults.filter((r) => r.pass).at(-1);
  if (best) {
    console.log(
      `Estimated stable concurrent players (local, this machine): ~${best.targetPlayers} ` +
        `(p95 ${Math.round(best.p95)}ms, open ${(best.openRate * 100).toFixed(1)}%).`,
    );
  }
}

main().catch((err) => {
  console.error('Load test failed:', err?.stack || String(err));
  process.exit(1);
});
