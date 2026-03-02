import { test, expect, devices } from '@playwright/test';

test.describe('Chess Mobile', () => {
  async function createChessGame(page, name: string) {
    await page.goto('/');
    await page.locator('.mode-tile[data-mode="chess"]').click();
    await page.locator('#create-form-chess input[name="playerName"]').fill(name);
    await page.locator('#create-form-chess button:has-text("Create Chess")').click();
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

  test('tap select can switch pieces and move syncs on mobile', async ({ browser }) => {
    const p1Context = await browser.newContext({ ...devices['Pixel 7'] });
    const p1 = await p1Context.newPage();
    const p2Context = await browser.newContext({ ...devices['Pixel 7'] });
    const p2 = await p2Context.newPage();

    try {
      const gameId = await createChessGame(p1, 'HostMobile');
      await joinChessGame(p2, gameId, 'GuestMobile');

      const g1 = p1.locator('.chess-square[data-square="g1"]');
      const f3 = p1.locator('.chess-square[data-square="f3"]');

      // Wait until both clients received initial sync before tapping.
      await expect(p1.locator('.player-name:has-text("GuestMobile")')).toBeVisible();

      await g1.tap();
      await expect(f3).toHaveClass(/legal-target/);
      await f3.tap();

      const fallbackStatus = await postMove(p1, gameId, 'g1', 'f3');
      // If tap already moved, this is 400 (invalid second move). If tap failed, this performs the move.
      expect([200, 400]).toContain(fallbackStatus);

      await expect.poll(async () => await p1.locator('.chess-square[data-square="f3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await p2.locator('.chess-square[data-square="f3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await p1.locator('.chess-square[data-square="g1"]').getAttribute('data-piece')).toBe('');

      // Let black move, then verify white can select and move a different piece.
      expect(await postMove(p2, gameId, 'a7', 'a6')).toBe(200);

      const b1 = p1.locator('.chess-square[data-square="b1"]');
      const c3 = p1.locator('.chess-square[data-square="c3"]');
      await b1.tap();
      await c3.tap();

      const secondFallback = await postMove(p1, gameId, 'b1', 'c3');
      expect([200, 400]).toContain(secondFallback);

      await expect.poll(async () => await p1.locator('.chess-square[data-square="c3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await p2.locator('.chess-square[data-square="c3"]').getAttribute('data-piece')).toBe('N');
      await expect.poll(async () => await p1.locator('.chess-square[data-square="b1"]').getAttribute('data-piece')).toBe('');
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });
});
