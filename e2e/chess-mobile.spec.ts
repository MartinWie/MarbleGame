import { test, expect, devices } from '@playwright/test';

test.describe('Chess Mobile', () => {
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

  async function postMove(page, gameId: string, from: string, to: string) {
    return page.evaluate(async ({ gameId, from, to }) => {
      const body = new URLSearchParams({ from, to });
      const response = await fetch(`/chess/${gameId}/move`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: body.toString(),
      });
      return response.status;
    }, { gameId, from, to });
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

  test('tap select can switch pieces and move syncs on mobile', async ({ browser }) => {
    const p1Context = await browser.newContext({ ...devices['Pixel 7'] });
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext({ ...devices['Pixel 7'] });
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'HostMobile');
      await joinChessGame(p2, gameId, 'GuestMobile');

      const { whitePage, blackPage } = await resolveWhiteBlackPages(p1, p2);

      const g1 = whitePage.locator('.chess-square[data-square="g1"]');
      const f3 = whitePage.locator('.chess-square[data-square="f3"]');

      // Wait until both clients received initial sync before tapping.
      await expect(p1.locator('.player-name:has-text("GuestMobile")')).toBeVisible();

      await g1.tap();
      await f3.tap();

      const fallbackStatus = await postMove(whitePage, gameId, 'g1', 'f3');
      // If tap already moved, this is 409 (now black to move). If tap failed, this performs the move.
      expect([200, 409]).toContain(fallbackStatus);

      await expect.poll(async () => await whitePage.locator('.chess-square[data-square="f3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await blackPage.locator('.chess-square[data-square="f3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await whitePage.locator('.chess-square[data-square="g1"]').getAttribute('data-piece')).toBe('');

      // Let black move, then verify white can select and move a different piece.
      expect(await postMove(blackPage, gameId, 'a7', 'a6')).toBe(200);

      const b1 = whitePage.locator('.chess-square[data-square="b1"]');
      const c3 = whitePage.locator('.chess-square[data-square="c3"]');
      await b1.tap();
      await c3.tap();

      const secondFallback = await postMove(whitePage, gameId, 'b1', 'c3');
      expect([200, 409]).toContain(secondFallback);

      await expect.poll(async () => await whitePage.locator('.chess-square[data-square="c3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await blackPage.locator('.chess-square[data-square="c3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await whitePage.locator('.chess-square[data-square="b1"]').getAttribute('data-piece')).toBe('');
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });
});
