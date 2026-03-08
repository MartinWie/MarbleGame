#!/usr/bin/env node

import { performance } from 'node:perf_hooks';

const defaults = {
  baseUrl: 'http://localhost:8080',
  mode: 'single-hotspot',
  start: 50,
  max: 1600,
  holdSec: 20,
  rampSec: 10,
  stepMode: 'x2',
  concurrency: 100,
  games: 100,
  playersPerGame: 4,
  hotspotPlayers: 300,
  maxTotalPlayers: 4000,
};

function parseArgs(argv) {
  const args = { ...defaults };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    const next = argv[i + 1];
    if (a === '--base' && next) {
      args.baseUrl = next;
      i += 1;
    } else if (a === '--mode' && next) {
      args.mode = next;
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
    } else if (a === '--concurrency' && next) {
      args.concurrency = parseInt(next, 10);
      i += 1;
    } else if (a === '--games' && next) {
      args.games = parseInt(next, 10);
      i += 1;
    } else if (a === '--players-per-game' && next) {
      args.playersPerGame = parseInt(next, 10);
      i += 1;
    } else if (a === '--hotspot-players' && next) {
      args.hotspotPlayers = parseInt(next, 10);
      i += 1;
    } else if (a === '--max-total-players' && next) {
      args.maxTotalPlayers = parseInt(next, 10);
      i += 1;
    }
  }
  return args;
}

function validateArgs(args) {
  const requirePositiveInt = (name) => {
    const value = args[name];
    if (!Number.isFinite(value) || value <= 0 || Math.floor(value) !== value) {
      throw new Error(`Invalid --${name.replace(/[A-Z]/g, (m) => '-' + m.toLowerCase())}: ${value}`);
    }
  };

  if (!['single-hotspot', 'many-small', 'mixed'].includes(args.mode)) {
    throw new Error(`Invalid --mode '${args.mode}'. Use single-hotspot|many-small|mixed.`);
  }


  if (!(args.stepMode.startsWith('x') || args.stepMode.startsWith('+'))) {
    throw new Error(`Invalid --step '${args.stepMode}'. Use xN or +N.`);
  }

  if (args.stepMode.startsWith('x')) {
    const factor = Number(args.stepMode.slice(1));
    if (!Number.isFinite(factor) || factor <= 1) {
      throw new Error(`Invalid --step '${args.stepMode}'. xN requires numeric N > 1.`);
    }
  }
  if (args.stepMode.startsWith('+')) {
    const inc = Number(args.stepMode.slice(1));
    if (!Number.isFinite(inc) || inc < 1 || Math.floor(inc) !== inc) {
      throw new Error(`Invalid --step '${args.stepMode}'. +N requires integer N >= 1.`);
    }
  }

  [
    'start',
    'max',
    'holdSec',
    'rampSec',
    'concurrency',
    'games',
    'playersPerGame',
    'hotspotPlayers',
    'maxTotalPlayers',
  ].forEach(requirePositiveInt);

  if (args.max < args.start) {
    throw new Error(`Invalid range: --max (${args.max}) must be >= --start (${args.start})`);
  }
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

async function createGame(baseUrl) {
  const rootResp = await fetch(`${baseUrl}/`, { redirect: 'manual' });
  const rootCookies = cookieHeaderFromSetCookie(safeSetCookieArray(rootResp.headers));

  const endpoint = '/game/create';
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

async function joinGame(baseUrl, gameId, playerName) {
  const rootResp = await fetch(`${baseUrl}/`, { redirect: 'manual' });
  const rootCookies = cookieHeaderFromSetCookie(safeSetCookieArray(rootResp.headers));

  const endpoint = '/game/join';
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

async function connectWs(baseUrl, gameId, cookieHeader) {
  const path = `/ws/game/${gameId}`;
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

function createStats(label, targetPlayers, extra = {}) {
  return {
    label,
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
    ...extra,
  };
}

function bumpReason(bucket, reason) {
  const key = reason || 'unknown';
  bucket[key] = (bucket[key] || 0) + 1;
}

async function runPlayerTasks(args, tasks, stats, rampMs) {
  const spacingMs = Math.max(1, Math.floor(rampMs / Math.max(1, tasks.length)));
  const stageStartMs = Date.now();
  const taskIndexes = Array.from({ length: tasks.length }, (_, i) => i);
  const workerCount = Math.max(1, Math.min(args.concurrency, tasks.length));

  const workers = Array.from({ length: workerCount }, async () => {
    while (taskIndexes.length > 0) {
      const i = taskIndexes.shift();
      if (typeof i !== 'number') break;

      const task = tasks[i];
      const targetStartMs = stageStartMs + (i * spacingMs);
      const waitMs = Math.max(0, targetStartMs - Date.now());
      if (waitMs > 0) {
        await sleep(waitMs);
      }

      try {
        const cookie = await joinGame(args.baseUrl, task.gameId, task.playerName);
        stats.joinOk += 1;

        const wsRes = await connectWs(args.baseUrl, task.gameId, cookie);
        if (!wsRes.ok) {
          stats.wsFail += 1;
          bumpReason(stats.wsFailReasons, wsRes.error || 'ws-fail');
          continue;
        }

        stats.wsOk += 1;
        stats.connectLatencies.push(wsRes.connectMs);
        stats.sockets.push(wsRes);
      } catch (err) {
        stats.joinFail += 1;
        bumpReason(stats.joinFailReasons, err && err.message ? err.message : 'join-fail');
      }
    }
  });

  await Promise.all(workers);
}

async function finalizeHold(stats, holdSec) {
  await sleep(holdSec * 1000);

  for (const wsRes of stats.sockets) {
    const s = wsRes.stats();
    if (s.closedUnexpectedly) {
      stats.unexpectedCloses += 1;
    }
    stats.totalMessages += s.messages;
  }

  for (const wsRes of stats.sockets) {
    try {
      wsRes.socket.close();
    } catch (_) {}
  }
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

function logStageResult(stage, classified) {
  console.log(
    [
      `label=${stage.label}`,
      `joinOk=${stage.joinOk}`,
      `joinFail=${stage.joinFail}`,
      `wsOk=${stage.wsOk}`,
      `wsFail=${stage.wsFail}`,
      `openRate=${(classified.openRate * 100).toFixed(1)}%`,
      `p95=${Math.round(classified.p95)}ms`,
      `unexpectedCloseRate=${(classified.unexpectedCloseRate * 100).toFixed(1)}%`,
      `msgs=${stage.totalMessages}`,
      `joinFailReasons=${JSON.stringify(stage.joinFailReasons)}`,
      `wsFailReasons=${JSON.stringify(stage.wsFailReasons)}`,
      `clientMem=${JSON.stringify(memorySummary())}`,
      `result=${classified.pass ? 'PASS' : 'FAIL'}`,
    ].join(' | '),
  );
}

async function runSingleHotspotMode(args) {
  let size = args.start;
  let lastPass = null;
  let firstFail = null;
  const stageResults = [];

  while (size <= args.max) {
    console.log(`\nStage: target=${size} players`);
    const { gameId } = await createGame(args.baseUrl);
    console.log(`Stage game: ${gameId}`);

    const stats = createStats(`single-hotspot-${size}`, size, { gameId });
    const tasks = Array.from({ length: size }, () => ({ gameId, playerName: randomName('p') }));

    await runPlayerTasks(args, tasks, stats, Math.max(1, args.rampSec * 1000));
    await finalizeHold(stats, args.holdSec);

    const classified = classifyStage(stats);
    stageResults.push({ ...stats, ...classified });
    logStageResult(stats, classified);

    if (classified.pass) {
      lastPass = size;
      size = nextStageSize(size, args.stepMode);
    } else {
      firstFail = size;
      break;
    }
  }

  return { mode: args.mode, stageResults, lastPass, firstFail };
}

async function runManySmallMode(args) {
  const totalPlayers = args.games * args.playersPerGame;
  console.log(`\nMode many-small: games=${args.games}, playersPerGame=${args.playersPerGame}, totalPlayers=${totalPlayers}`);

  const gameIds = [];
  for (let i = 0; i < args.games; i += 1) {
    const created = await createGame(args.baseUrl);
    gameIds.push(created.gameId);
  }

  const tasks = [];
  for (const gameId of gameIds) {
    for (let i = 0; i < args.playersPerGame; i += 1) {
      tasks.push({ gameId, playerName: randomName('p') });
    }
  }

  const stats = createStats(`many-small-${args.games}x${args.playersPerGame}`, totalPlayers, {
    gameCount: args.games,
    playersPerGame: args.playersPerGame,
  });

  await runPlayerTasks(args, tasks, stats, Math.max(1, args.rampSec * 1000));
  await finalizeHold(stats, args.holdSec);

  const classified = classifyStage(stats);
  logStageResult(stats, classified);

  return {
    mode: args.mode,
    stageResults: [{ ...stats, ...classified }],
    lastPass: classified.pass ? totalPlayers : null,
    firstFail: classified.pass ? null : totalPlayers,
  };
}

async function runMixedMode(args) {
  console.log(
    `\nMode mixed: hotspotPlayers=${args.hotspotPlayers}, smallGames=${args.games}, playersPerGame=${args.playersPerGame}`,
  );

  const hotspot = await createGame(args.baseUrl);
  const gameIds = [];
  for (let i = 0; i < args.games; i += 1) {
    const created = await createGame(args.baseUrl);
    gameIds.push(created.gameId);
  }

  const tasks = [];
  for (let i = 0; i < args.hotspotPlayers; i += 1) {
    tasks.push({ gameId: hotspot.gameId, playerName: randomName('hot') });
  }
  for (const gameId of gameIds) {
    for (let i = 0; i < args.playersPerGame; i += 1) {
      tasks.push({ gameId, playerName: randomName('sm') });
    }
  }

  const totalPlayers = tasks.length;
  if (totalPlayers > args.maxTotalPlayers) {
    throw new Error(`Mixed mode total players ${totalPlayers} exceeds --max-total-players=${args.maxTotalPlayers}`);
  }

  const stats = createStats(
    `mixed-hot${args.hotspotPlayers}-small${args.games}x${args.playersPerGame}`,
    totalPlayers,
    {
      hotspotGameId: hotspot.gameId,
      smallGameCount: args.games,
    },
  );

  await runPlayerTasks(args, tasks, stats, Math.max(1, args.rampSec * 1000));
  await finalizeHold(stats, args.holdSec);

  const classified = classifyStage(stats);
  logStageResult(stats, classified);

  return {
    mode: args.mode,
    stageResults: [{ ...stats, ...classified }],
    lastPass: classified.pass ? totalPlayers : null,
    firstFail: classified.pass ? null : totalPlayers,
  };
}

function printSummary(summary, args) {
  console.log('\n=== Summary ===');

  if (summary.lastPass == null && summary.firstFail != null) {
    console.log(`No passing stage. First failure at ${summary.firstFail}.`);
  } else if (summary.lastPass != null && summary.firstFail == null) {
    if (args.mode === 'single-hotspot') {
      console.log(`No failure up to max=${args.max}. Last passing stage: ${summary.lastPass}.`);
    } else {
      console.log(`Profile passed with total players=${summary.lastPass}.`);
    }
  } else {
    console.log(`Last passing stage: ${summary.lastPass}, first failing stage: ${summary.firstFail}.`);
  }

  const best = summary.stageResults.filter((r) => r.pass).at(-1);
  if (best) {
    console.log(
      `Estimated stable concurrent players for mode ${summary.mode}: ~${best.targetPlayers} ` +
        `(p95 ${Math.round(best.p95)}ms, open ${(best.openRate * 100).toFixed(1)}%).`,
    );
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  validateArgs(args);
  console.log('Load test config:', JSON.stringify(args));

  let summary;
  if (args.mode === 'single-hotspot') {
    summary = await runSingleHotspotMode(args);
  } else if (args.mode === 'many-small') {
    summary = await runManySmallMode(args);
  } else if (args.mode === 'mixed') {
    summary = await runMixedMode(args);
  } else {
    throw new Error(`Unknown --mode '${args.mode}'. Use single-hotspot|many-small|mixed.`);
  }

  printSummary(summary, args);
}

main().catch((err) => {
  console.error('Load test failed:', err?.stack || String(err));
  process.exit(1);
});
