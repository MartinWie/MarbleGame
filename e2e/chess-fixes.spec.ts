import { test, expect, devices } from '@playwright/test';

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

      await p1.locator('.chess-square[data-square="e2"]').click();
      await p1.locator('.chess-square[data-square="e4"]').click();

      await expect(p1.locator('.move-trail-piece.piece-white')).toBeVisible();
    } finally {
      await p1Context.close();
      await p2Context.close();
    }
  });

  test('mobile share rejection does not fallback to clipboard', async ({ browser }) => {
    const context = await browser.newContext({ ...devices['Pixel 7'] });
    const page = await context.newPage();

    try {
      await createChessGame(page, 'HostShare');

      const onclick = await page.locator('#share-btn').getAttribute('onclick');
      const normalized = (onclick ?? '').replace(/\s+/g, ' ').trim();

      expect(normalized).toContain('if (isMobile && navigator.share) { nativeShare().catch(function() {}); } else { clipboardCopy(); }');
      expect(normalized).not.toContain('nativeShare().catch(function() { clipboardCopy(); });');
    } finally {
      await context.close();
    }
  });
});
