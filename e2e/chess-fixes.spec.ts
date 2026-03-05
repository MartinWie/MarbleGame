import { test, expect, devices } from '@playwright/test';

async function createChessGame(page, name: string) {
  await page.goto('/');
  await page.locator('.mode-tile[data-mode="chess"]').click();
  await page.locator('#create-form-chess input[name="playerName"]').fill(name);
  await page.getByRole('button', { name: 'Create Chess' }).click();
  await expect(page).toHaveURL(/\/chess\/[a-f0-9]{8}/);
  return new URL(page.url()).pathname.split('/chess/')[1];
}

async function joinChessGame(page, gameId: string, name: string) {
  await page.goto(`/chess/${gameId}/join`);
  await page.locator('input[name="playerName"]').fill(name);
  await page.getByRole('button', { name: 'Join Game' }).click();
  await expect(page).toHaveURL(new RegExp(`/chess/${gameId}$`));
}

async function legalMoves(page, gameId: string, from: string): Promise<string[]> {
  return page.evaluate(async ({ gameId, from }) => {
    const res = await fetch(`/chess/${gameId}/legal-moves?from=${encodeURIComponent(from)}`);
    if (!res.ok) return [];
    const text = await res.text();
    if (!text) return [];
    return text.split(',').filter(Boolean);
  }, { gameId, from });
}

async function playMoveEither(p1, p2, gameId: string, from: string, to: string) {
  const s1 = await p1.evaluate(async ({ gameId, from, to }) => {
    const body = new URLSearchParams({ from, to });
    const response = await fetch(`/chess/${gameId}/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: body.toString(),
    });
    return response.status;
  }, { gameId, from, to });
  if (s1 === 200) return;
  const s2 = await p2.evaluate(async ({ gameId, from, to }) => {
    const body = new URLSearchParams({ from, to });
    const response = await fetch(`/chess/${gameId}/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: body.toString(),
    });
    return response.status;
  }, { gameId, from, to });
  expect(s2).toBe(200);
}

async function resolveWhiteBlackPages(p1, p2) {
  await expect(p1.locator('.chess-board')).toBeVisible();
  await expect(p2.locator('.chess-board')).toBeVisible();
  const gameId = new URL(p1.url()).pathname.split('/chess/')[1];
  const p1Moves = await legalMoves(p1, gameId, 'e2');
  if (p1Moves.length > 0) return { whitePage: p1, blackPage: p2 };
  const p2Moves = await legalMoves(p2, gameId, 'e2');
  if (p2Moves.length > 0) return { whitePage: p2, blackPage: p1 };
  throw new Error('Could not resolve white/black player pages via legal-moves');
}

test.describe('Chess Fixes', () => {
  test('move trail keeps moved piece color class', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'HostTrail');
      await joinChessGame(p2, gameId, 'GuestTrail');

      await expect(p1.locator('.player-name:has-text("GuestTrail")')).toBeVisible();

      await playMoveEither(p1, p2, gameId, 'e2', 'e4');

      await expect
        .poll(async () => {
          const c1 = await p1.locator('.chess-square.moved .chess-piece.piece-white').count();
          const c2 = await p2.locator('.chess-square.moved .chess-piece.piece-white').count();
          return c1 + c2;
        })
        .toBeGreaterThan(0);

      await playMoveEither(p1, p2, gameId, 'e7', 'e5');

      await expect
        .poll(async () => {
          const c1 = await p1.locator('.chess-square.moved .chess-piece.piece-black').count();
          const c2 = await p2.locator('.chess-square.moved .chess-piece.piece-black').count();
          return c1 + c2;
        })
        .toBeGreaterThan(0);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('mobile share rejection does not fallback to clipboard', async ({ browser }) => {
    const context = await browser.newContext({ ...devices['Pixel 7'] });
    const page = await context.newPage();

    try {
      await page.addInitScript(() => {
        let shareCalls = 0;
        let clipboardCalls = 0;
        const shareMock = () => {
          shareCalls += 1;
          return Promise.reject(new Error('share-rejected'));
        };
        const clipboardMock = {
          writeText: () => {
            clipboardCalls += 1;
            return Promise.resolve();
          },
        };

        Object.defineProperty(window.navigator, 'share', {
          configurable: true,
          value: shareMock,
        });
        Object.defineProperty(window.navigator, 'clipboard', {
          configurable: true,
          value: clipboardMock,
        });
        Object.defineProperty(window.navigator, '__shareTest', {
          configurable: true,
          value: {
            getShareCalls: () => shareCalls,
            getClipboardCalls: () => clipboardCalls,
          },
        });
      });

      await createChessGame(page, 'HostShare');

      const shareBtn = page.locator('#share-btn');
      await expect(shareBtn).toHaveAttribute('data-share-url', /\/chess\/[a-f0-9]{8}\/join/);
      await expect(shareBtn).not.toHaveAttribute('onclick', /.+/);

      await shareBtn.click();

      await expect
        .poll(async () => {
          return page.evaluate(() => {
            return (window.navigator as unknown as { __shareTest?: { getShareCalls: () => number } }).__shareTest?.getShareCalls() ?? -1;
          });
        })
        .toBe(1);

      await expect
        .poll(async () => {
          return page.evaluate(() => {
            return (window.navigator as unknown as { __shareTest?: { getClipboardCalls: () => number } }).__shareTest?.getClipboardCalls() ?? -1;
          });
        })
        .toBe(0);
    } finally {
      await context.close();
    }
  });

  test('wrong-turn shows dedicated not-your-turn toast', async ({ browser }) => {
    const p1Context = await browser.newContext();
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext();
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'HostTurnMsg');
      await joinChessGame(p2, gameId, 'GuestTurnMsg');

      const { whitePage, blackPage } = await resolveWhiteBlackPages(p1, p2);
      await expect(whitePage.locator('.chess-board')).toBeVisible();
      await expect(blackPage.locator('.chess-board')).toBeVisible();

      // Black tries to move while white is to play.
      await blackPage.locator('.chess-square[data-square="e7"]').click();
      await blackPage.locator('.chess-square[data-square="e5"]').click();

      await expect(blackPage.locator('#chess-toast')).toContainText(/not your turn|nicht am zug/i);
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

});
